package com.wasimaster.wmkeyboard.ime.handwriting.onyx

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.rx.RxBaseAction
import com.onyx.android.sdk.utils.ResManager
import com.wasimaster.wmkeyboard.core.handwriting.HwPoint
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import com.wasimaster.wmkeyboard.core.shell.HiddenApi
import com.wasimaster.wmkeyboard.ime.HandwritingUi

object OnyxInkSupport {
    /** Raw mode stays opt-in even on devices that identify themselves as BOOX. */
    val deviceSupported: Boolean by lazy {
        listOf(Build.MANUFACTURER, Build.BRAND).any {
            it.equals("onyx", ignoreCase = true) || it.contains("boox", ignoreCase = true)
        }
    }
    private var sessionFailed = false
    val runtimeAvailable: Boolean get() = deviceSupported && !sessionFailed

    internal fun failForSession() {
        sessionFailed = true
    }
}

/** Service-level pause boundaries; view attachment remains the second safeguard. */
object OnyxInkLifecycle {
    private val hosts = mutableSetOf<OnyxRawInkView>()
    private var imeSuspended = false

    internal fun register(host: OnyxRawInkView) {
        hosts += host
        if (imeSuspended) host.suspendFromIme()
    }

    internal fun unregister(host: OnyxRawInkView) {
        hosts -= host
    }

    fun suspendAll() {
        imeSuspended = true
        hosts.toList().forEach { it.suspendFromIme() }
    }

    fun resumeAll() {
        imeSuspended = false
        hosts.toList().forEach { it.resumeFromIme() }
    }

    /**
     * Briefly unlocks raw scribble mode when a non-canvas key (Space, Backspace,
     * Enter, suggestion chip) edits the target field while no ink is pending,
     * so firmwares that freeze full-screen refreshes during raw mode still paint
     * the text edit immediately.
     */
    fun pulseForExternalUpdate() {
        hosts.toList().forEach { it.pulseFromExternalUpdate() }
    }
}

private object OnyxSdkInitialization {
    private var ready = false

    @Synchronized
    fun initialize(context: Context): Boolean {
        if (ready) return true
        val app = context.applicationContext
        // The pen/base SDKs reflect into framework APIs on Android 11+.
        HiddenApi.exempt()
        ResManager.init(app)
        RxBaseAction.init(app)
        ready = true
        return true
    }
}

@Composable
@Suppress("LongParameterList")
internal fun OnyxInkCanvas(
    modifier: Modifier,
    style: OnyxPenStyle,
    chrome: OnyxChromeRegions,
    handwriting: HandwritingUi,
    inputState: OnyxInputState,
    onStrokeStart: () -> Unit,
    onStrokeCancel: () -> Unit,
    onStroke: (HwStroke, IntSize) -> Unit,
    onStylusActivity: (Long) -> Unit,
    onFailure: () -> Unit,
) {
    AndroidView(
        factory = { context -> OnyxRawInkView(context) },
        modifier = modifier,
        onRelease = { view -> view.dispose() },
        update = { view ->
            view.bind(
                style = style,
                chrome = chrome,
                handwriting = handwriting,
                inputState = inputState,
                onStrokeStart = onStrokeStart,
                onStrokeCancel = onStrokeCancel,
                onStroke = onStroke,
                onStylusActivity = onStylusActivity,
                onFailure = onFailure,
            )
        },
    )
}

/**
 * Word-burst hardware raw ink surface:
 * - Keeps BOOX firmware raw drawing continuously enabled across all strokes of
 *   a word so strokes never vanish, quantize into software paths, or hit an
 *   inter-stroke dead zone.
 * - Compose renders zero ink underneath; raw hardware ink is flushed and cleared
 *   once per word when recognition commits (or when the canvas resets).
 */
internal class OnyxRawInkView(context: Context) : View(context) {
    private var helper: TouchHelper? = null
    private var disposed = false
    private var lifecycleSuspended = false
    private var rawEnabled = false
    private var clearPending = false
    private var failureReported = false
    private var hasUnflushedHardwareInk = false
    private var previousStrokeCount = 0
    private var lastLanguageTag: String? = null
    private var currentStyle: OnyxPenStyle? = null
    private var currentChrome = OnyxChromeRegions()
    private var inputState = OnyxInputState()
    private var onStrokeStart: () -> Unit = {}
    private var onStrokeCancel: () -> Unit = {}
    private var onStroke: (HwStroke, IntSize) -> Unit = { _, _ -> }
    private var onStylusActivity: (Long) -> Unit = {}
    private var onFailure: () -> Unit = {}
    private val collector = OnyxStrokeCollector()

    private val finishClear = Runnable {
        clearPending = false
        if (!disposed && !lifecycleSuspended && isAttachedToWindow) {
            invalidate()
            // Refresh the whole IME window so newly populated suggestion chips
            // paint alongside the cleared canvas before raw mode re-locks.
            runCatching { EpdController.refreshScreen(rootView ?: this, UpdateMode.DU) }
                .onFailure { Log.w(TAG, "EPD refresh failed; continuing", it) }
            enableRawDrawing()
        }
    }

    private val rawCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcutDrawing: Boolean, touchPoint: TouchPoint) {
            val point = HwPoint(touchPoint.x, touchPoint.y, touchPoint.timestamp)
            post {
                if (isInactive()) return@post
                val now = SystemClock.uptimeMillis()
                val unfinished = collector.begin(point, IntSize(width, height), now)
                hasUnflushedHardwareInk = true
                reportStylusActivity(point.t)
                onStrokeStart()
                if (unfinished != null) {
                    onStroke(unfinished, IntSize(width, height))
                }
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
            val point = HwPoint(touchPoint.x, touchPoint.y, touchPoint.timestamp)
            post {
                if (isInactive()) return@post
                collector.move(point)
                reportStylusActivity(point.t)
            }
        }

        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
            // SDK callback objects are mutable and may be recycled: copy immediately.
            val rawPoints = touchPointList.points?.map { HwPoint(it.x, it.y, it.timestamp) }.orEmpty()
            post {
                if (isInactive() || rawPoints.isEmpty()) return@post
                val now = SystemClock.uptimeMillis()
                val stroke = collector.onPointList(rawPoints, IntSize(width, height), now)
                reportStylusActivity(rawPoints.last().t)
                if (stroke != null) {
                    hasUnflushedHardwareInk = true
                    onStroke(stroke, IntSize(width, height))
                }
            }
        }

        override fun onEndRawDrawing(outsideLimitRegion: Boolean, touchPoint: TouchPoint) {
            val point = HwPoint(touchPoint.x, touchPoint.y, touchPoint.timestamp)
            post {
                if (isInactive()) return@post
                val now = SystemClock.uptimeMillis()
                val stroke = collector.end(point, IntSize(width, height), now)
                reportStylusActivity(point.t)
                if (stroke != null) {
                    hasUnflushedHardwareInk = true
                    // Keep raw drawing enabled across strokes of the same word;
                    // flushing waits until the word commits and clears.
                    onStroke(stroke, IntSize(width, height))
                } else {
                    onStrokeCancel()
                }
            }
        }

        override fun onBeginRawErasing(shortcutErasing: Boolean, touchPoint: TouchPoint) = Unit
        override fun onEndRawErasing(outsideLimitRegion: Boolean, touchPoint: TouchPoint) = Unit
        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) = Unit
        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) = Unit
    }

    @Suppress("LongParameterList")
    fun bind(
        style: OnyxPenStyle,
        chrome: OnyxChromeRegions,
        handwriting: HandwritingUi,
        inputState: OnyxInputState,
        onStrokeStart: () -> Unit,
        onStrokeCancel: () -> Unit,
        onStroke: (HwStroke, IntSize) -> Unit,
        onStylusActivity: (Long) -> Unit,
        onFailure: () -> Unit,
    ) {
        this.inputState = inputState
        this.onStrokeStart = onStrokeStart
        this.onStrokeCancel = onStrokeCancel
        this.onStroke = onStroke
        this.onStylusActivity = onStylusActivity
        this.onFailure = onFailure
        if (currentStyle != style) {
            currentStyle = style
            applyStyle()
        }
        if (currentChrome != chrome) {
            currentChrome = chrome
            updateDrawingRegions()
        }
        val clearedWord = previousStrokeCount > 0 && handwriting.strokes.isEmpty()
        val switchedLanguage = lastLanguageTag != null &&
            lastLanguageTag != handwriting.languageTag &&
            hasUnflushedHardwareInk
        previousStrokeCount = handwriting.strokes.size
        lastLanguageTag = handwriting.languageTag

        if (clearedWord || switchedLanguage) {
            flushAndClearHardwareInk()
        } else if (!rawEnabled && !clearPending) {
            post { enableRawDrawing() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        OnyxInkLifecycle.register(this)
        post { enableRawDrawing() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDrawingRegions()
        post { enableRawDrawing() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            post { enableRawDrawing() }
        } else {
            clearPending = false
            collector.cancel()
            pauseRawDrawing()
        }
    }

    override fun onDetachedFromWindow() {
        pauseRawDrawing()
        closeHelper()
        OnyxInkLifecycle.unregister(this)
        super.onDetachedFromWindow()
    }

    fun suspendFromIme() {
        lifecycleSuspended = true
        clearPending = false
        hasUnflushedHardwareInk = false
        collector.cancel()
        pauseRawDrawing()
        removeCallbacks(finishClear)
    }

    fun resumeFromIme() {
        lifecycleSuspended = false
        post { enableRawDrawing() }
    }

    fun pulseFromExternalUpdate() {
        if (isInactive() || !rawEnabled || collector.inStroke || previousStrokeCount > 0) return
        flushAndClearHardwareInk()
    }

    fun dispose() {
        disposed = true
        collector.cancel()
        pauseRawDrawing()
        closeHelper()
        OnyxInkLifecycle.unregister(this)
    }

    private fun isInactive(): Boolean = disposed || lifecycleSuspended || failureReported

    private fun canEnableRawDrawing(): Boolean {
        if (isInactive() || clearPending || inputState.fallbackStylusActive) return false
        return isAttachedToWindow && windowVisibility == VISIBLE && width > 0 && height > 0
    }

    private fun enableRawDrawing() {
        if (!canEnableRawDrawing()) return
        removeCallbacks(finishClear)
        val initialized = runCatching {
            OnyxInkSupport.runtimeAvailable && OnyxSdkInitialization.initialize(context)
        }.getOrElse {
            fail(it)
            return
        }
        if (!initialized) {
            fail(IllegalStateException("Onyx pen SDK is unavailable on this device"))
            return
        }
        if (helper == null) {
            runCatching {
                val created = TouchHelper.create(this, rawCallback)
                created.setTouchListenerEnabled(false)
                helper = created
                applyStyle()
                updateDrawingRegions()
                if (!failureReported) {
                    created.openRawDrawing()
                    // openRawDrawing() resets stroke width/style on several firmwares.
                    applyStyle()
                }
            }.onFailure {
                fail(it)
                return
            }
        }
        if (!rawEnabled) {
            runCatching {
                helper?.setRawDrawingRenderEnabled(true)
                helper?.setRawDrawingEnabled(true)
            }.onFailure {
                fail(it)
                return
            }
            rawEnabled = true
            inputState.rawCaptureActive = true
        }
    }

    private fun pauseRawDrawing() {
        removeCallbacks(finishClear)
        if (!rawEnabled) return
        runCatching {
            helper?.setRawDrawingRenderEnabled(false)
            helper?.setRawDrawingEnabled(false)
        }
        rawEnabled = false
        inputState.rawCaptureActive = false
    }

    private fun closeHelper() {
        removeCallbacks(finishClear)
        clearPending = false
        hasUnflushedHardwareInk = false
        collector.cancel()
        runCatching { helper?.closeRawDrawing() }
        helper = null
        rawEnabled = false
        inputState.rawCaptureActive = false
    }

    private fun flushAndClearHardwareInk() {
        if (isInactive()) return
        hasUnflushedHardwareInk = false
        clearPending = true
        pauseRawDrawing()
        invalidate()
        rootView?.invalidate()
        removeCallbacks(finishClear)
        postDelayed(finishClear, CLEAR_REFRESH_MS)
    }

    private fun applyStyle() {
        val style = currentStyle ?: return
        runCatching {
            helper
                ?.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                ?.setStrokeColor(style.color.toArgb())
                ?.setStrokeWidth(style.widthDp * resources.displayMetrics.density)
        }.onFailure { fail(it) }
    }

    private fun updateDrawingRegions() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val corner = (FULLSCREEN_EXCLUSION_DP * density).toInt()
        val chipHeight = (CHIP_HEIGHT_EXCLUSION_DP * density).toInt()
        val chipWidth = (LANGUAGE_EXCLUSION_DP * density).toInt()
        val excluded = buildList {
            if (currentChrome.languageChip) add(Rect(0, 0, chipWidth, chipHeight))
            if (currentChrome.fullScreenButton) add(Rect(width - corner, 0, width, corner))
        }
        runCatching { helper?.setLimitRect(Rect(0, 0, width, height), excluded) }
            .onFailure { fail(it) }
    }

    private fun reportStylusActivity(rawTimestamp: Long) {
        val now = SystemClock.uptimeMillis()
        // Raw firmware timestamps are millisecond-scale but their epoch is not
        // contractual. Recognition keeps repaired deltas; palm rejection needs uptime.
        val uptime = if (rawTimestamp in 1..now + UPTIME_SKEW_MS) rawTimestamp else now
        onStylusActivity(uptime)
    }

    private fun fail(error: Throwable) {
        Log.w(TAG, "Disabling Onyx raw ink; falling back to Compose", error)
        OnyxInkSupport.failForSession()
        pauseRawDrawing()
        closeHelper()
        if (!failureReported) {
            failureReported = true
            post { onFailure() }
        }
    }

    companion object {
        private const val TAG = "OnyxRawInk"
        private const val CLEAR_REFRESH_MS = 40L
        private const val UPTIME_SKEW_MS = 1_000L
        private const val FULLSCREEN_EXCLUSION_DP = 44
        private const val CHIP_HEIGHT_EXCLUSION_DP = 44
        private const val LANGUAGE_EXCLUSION_DP = 88
    }
}

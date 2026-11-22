package com.wasimaster.wmkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import com.wasimaster.wmkeyboard.core.settings.PrivacyShadePhoto
import com.wasimaster.wmkeyboard.core.settings.PrivacyShadeSettings
import com.wasimaster.wmkeyboard.core.settings.PrivacyShadeStyle
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The privacy shade (#1242): a cover over the app above the docked keyboard.
 *
 * Drawn in the keyboard's own window, which already runs the full height of
 * the screen with the board at its bottom (#1123), so it needs no permission
 * to draw over other apps and goes away with the keyboard. The cover is a
 * plain view laid *under* the keyboard's content, so the key preview bubbles
 * that rise above the top row are still drawn over it.
 *
 * What a finger can do with it:
 *
 * - drag the handle on its lower edge up to see the last lines of the
 *   conversation just above the keys, and down to cover them again; a tap on
 *   the handle covers everything again;
 * - tap the close button beside the handle to take the shade down;
 * - with [PrivacyShadeSettings.touchThrough] off, hold a finger anywhere on
 *   it to lift it until the finger comes up.
 *
 * With touch-through on (the default), only the handle and the close button
 * are the keyboard's: [touchRegion] adds them to the window's touchable
 * region, and every other touch on the shade reaches the app under it, so the
 * chat still scrolls. The window's insets are recomputed through
 * [requestInsets] whenever that region moves.
 */
internal class PrivacyShade(
    private val context: Context,
    private val requestInsets: () -> Unit,
) {
    private var view: ShadeView? = null
    private var settings = PrivacyShadeSettings()

    /** The shade is drawn: switched on, and docked in a full-height window. */
    var showing: Boolean = false
        private set

    /** Whether the shade takes every touch on itself; see [PrivacyShadeSettings.touchThrough]. */
    val takesTouches: Boolean get() = showing && !settings.touchThrough

    /**
     * Puts the cover's view into [window], once, at the bottom of the content
     * frame's stack. False when the window is not laid out the way AOSP lays
     * an input method window out, in which case there is no shade.
     */
    fun attach(window: Window): Boolean {
        view?.let { if (it.parent != null) return true }
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return false
        val shade = ShadeView(context)
        content.addView(
            shade,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        view = shade
        shade.apply(settings, showing)
        return true
    }

    /**
     * Takes the latest settings and whether a shade can be drawn at all now:
     * docked rather than floating, and in a window as tall as the screen.
     */
    fun update(next: PrivacyShadeSettings, drawable: Boolean) {
        val wasShowing = showing
        val wasTouches = takesTouches
        settings = next
        showing = next.on && drawable
        // Held only while the shade is up: the keyboard's process has no
        // use for a screen-sized picture the rest of the time.
        loadPhoto(if (showing) next.photo else null)
        view?.apply(next, showing)
        if (!showing) view?.coverAll()
        if (wasShowing != showing || wasTouches != takesTouches) requestInsets()
    }

    /** Where the board starts, in window pixels, as the insets just worked it out. */
    fun setBoardTop(px: Int) {
        view?.setBoardTop(px)
    }

    /** Back to covering everything; the keyboard just came up again. */
    fun coverAll() {
        view?.coverAll()
    }

    /** Adds the handle and the close button to a touchable region, in window pixels. */
    fun touchRegion(region: Region) {
        val v = view ?: return
        if (!showing) return
        val offset = IntArray(2)
        v.getLocationInWindow(offset)
        val r = Rect()
        v.controlsRect(r)
        r.offset(offset[0], offset[1])
        if (!r.isEmpty) region.op(r, Region.Op.UNION)
    }

    /** Called back from the view when the user closes the shade with its button. */
    var onClose: () -> Unit = {}

    /** The user's photo on the cover (#1417), decoded; and the file name it was decoded from. */
    private var photo: Bitmap? = null
    private var photoName: String? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Decodes the photo [name] names off the main thread, no larger than the
     * screen needs, and hands it to the view; null lets go of the one held.
     */
    private fun loadPhoto(name: String?) {
        if (name == photoName) return
        photoName = name
        photo = null
        view?.invalidate()
        val file = PrivacyShadePhoto.file(context.filesDir, name) ?: return
        if (!file.isFile) {
            // Not there yet (a restore writes it a moment before the setting),
            // or locked away before the first unlock: look again next time.
            photoName = null
            return
        }
        val metrics = context.resources.displayMetrics
        val screen = maxOf(metrics.widthPixels, metrics.heightPixels)
        thread(name = "privacy-shade-photo", isDaemon = true) {
            val decoded = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= screen) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
            main.post {
                // A newer pick, or the shade gone down, while this one decoded.
                if (photoName == name) {
                    photo = decoded
                    view?.invalidate()
                }
            }
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class ShadeView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private fun dp(v: Float) = v * density

        private val coverPaint = Paint()
        private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val photoMatrix = Matrix()
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = Paint()
        private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = dp(13f)
        }
        private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            strokeCap = Paint.Cap.ROUND
        }
        private val closeRing = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hint = context.getString(R.string.ime_privacy_shade_hint)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private var boardTop = 0
        /** How much of the app above the board is uncovered, in px. */
        private var reveal = 0
        private var drawn = false
        private var touchThrough = true

        /**
         * Whether the hint is written on the cover. Not on the black one,
         * which is there to leave an OLED screen's pixels off (#1428).
         */
        private var showHint = true

        /** The cover's opacity and the photo's against its colour; see [PrivacyShadeSettings.photoOpacity]. */
        private var coverOpacity = PrivacyShadeSettings.DEFAULT_OPACITY
        private var photoOpacity = PrivacyShadeSettings.DEFAULT_PHOTO_OPACITY

        /** Lifted by a held finger, with touch-through off. */
        private var peeking = false

        // The drag under way, if any.
        private var dragging = false
        private var downY = 0f
        private var downReveal = 0
        private var moved = false
        private var downOnClose = false

        private val handleWidth = dp(HANDLE_WIDTH_DP)
        private val stripHeight = dp(STRIP_HEIGHT_DP)
        private val closeSize = dp(CLOSE_SIZE_DP)
        private val minCover = dp(MIN_COVER_DP).roundToInt()

        init {
            // Nothing for a screen reader to read in the cover itself; the
            // tool's own button says whether the shade is up.
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setWillNotDraw(false)
        }

        fun apply(next: PrivacyShadeSettings, show: Boolean) {
            drawn = show
            touchThrough = next.touchThrough
            showHint = next.style != PrivacyShadeStyle.BLACK
            val (cover, ink) = colours(next.style)
            coverPaint.color = cover
            coverOpacity = next.opacity.coerceIn(PrivacyShadeSettings.MIN_OPACITY, 1f)
            photoOpacity = next.photoOpacity.coerceIn(PrivacyShadeSettings.MIN_PHOTO_OPACITY, 1f)
            handlePaint.color = ink
            handlePaint.alpha = HANDLE_ALPHA
            edgePaint.color = ink
            edgePaint.alpha = EDGE_ALPHA
            hintPaint.color = ink
            hintPaint.alpha = HINT_ALPHA
            closePaint.color = ink
            closeRing.color = ink
            closeRing.alpha = CLOSE_RING_ALPHA
            visibility = if (show) VISIBLE else GONE
            invalidate()
        }

        fun setBoardTop(px: Int) {
            if (px == boardTop) return
            boardTop = px
            reveal = reveal.coerceIn(0, maxReveal())
            invalidate()
        }

        fun coverAll() {
            if (reveal == 0 && !peeking) return
            reveal = 0
            peeking = false
            invalidate()
            requestInsets()
        }

        private fun maxReveal(): Int = (boardTop - minCover).coerceAtLeast(0)

        /** The bottom of the cover, in this view's pixels. */
        private fun coverBottom(): Int = (boardTop - reveal).coerceAtLeast(0)

        /** The handle and close button's strip, centred on the cover's lower edge. */
        fun controlsRect(out: Rect) {
            if (!drawn || boardTop <= 0) {
                out.setEmpty()
                return
            }
            val bottom = coverBottom()
            val cx = width / 2f
            val half = handleWidth / 2f + closeSize + dp(8f)
            out.set(
                (cx - half).roundToInt(),
                (bottom - stripHeight).roundToInt().coerceAtLeast(0),
                (cx + half).roundToInt(),
                bottom,
            )
        }

        private fun closeCenterX(): Float = width / 2f + handleWidth / 2f + dp(8f) + closeSize / 2f

        override fun onDraw(canvas: Canvas) {
            if (!drawn || boardTop <= 0 || peeking) return
            val bottom = coverBottom().toFloat()
            if (bottom <= 0f) return
            drawCover(canvas, bottom)
            canvas.drawRect(0f, bottom - dp(1f), width.toFloat(), bottom, edgePaint)
            // The handle: a pill on the edge, the grip a drag takes.
            val cx = width / 2f
            val cy = bottom - stripHeight / 2f
            val pill = RectF(cx - handleWidth / 2f, cy - dp(2.5f), cx + handleWidth / 2f, cy + dp(2.5f))
            canvas.drawRoundRect(pill, dp(3f), dp(3f), handlePaint)
            // The close button beside it.
            val ccx = closeCenterX()
            val r = closeSize / 2f
            canvas.drawCircle(ccx, cy, r, closeRing)
            val arm = r * 0.42f
            canvas.drawLine(ccx - arm, cy - arm, ccx + arm, cy + arm, closePaint)
            canvas.drawLine(ccx - arm, cy + arm, ccx + arm, cy - arm, closePaint)
            // What the handle is for, in the middle of the cover while there is room.
            if (showHint && bottom > dp(HINT_ROOM_DP)) {
                canvas.drawText(hint, cx, bottom / 2f, hintPaint)
            }
        }

        /**
         * The colour, and the photo over it when there is one (#1417). The
         * two together let through exactly as much of the app as the colour
         * alone would at [coverOpacity]: with c that opacity and p the
         * photo's, the photo goes on at c*p over the colour at
         * c(1-p)/(1-cp), which leaves 1-c of the app.
         */
        private fun drawCover(canvas: Canvas, bottom: Float) {
            val bmp = photo
            if (bmp == null) {
                coverPaint.alpha = (coverOpacity * 255).roundToInt()
                canvas.drawRect(0f, 0f, width.toFloat(), bottom, coverPaint)
                return
            }
            val onTop = coverOpacity * photoOpacity
            val under = if (onTop >= 0.999f) 0f else coverOpacity * (1f - photoOpacity) / (1f - onTop)
            coverPaint.alpha = (under * 255).roundToInt()
            canvas.drawRect(0f, 0f, width.toFloat(), bottom, coverPaint)
            // Cropped to fill the whole band above the board, and fixed there:
            // dragging the edge uncovers the app, it does not slide the photo.
            val scale = maxOf(width.toFloat() / bmp.width, boardTop.toFloat() / bmp.height)
            photoMatrix.setScale(scale, scale)
            photoMatrix.postTranslate((width - bmp.width * scale) / 2f, (boardTop - bmp.height * scale) / 2f)
            photoPaint.alpha = (onTop * 255).roundToInt()
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), bottom)
            canvas.drawBitmap(bmp, photoMatrix, photoPaint)
            canvas.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!drawn) return false
            val controls = Rect().also { controlsRect(it) }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val y = event.y
                    val onControls = controls.contains(event.x.roundToInt(), y.roundToInt())
                    if (onControls) {
                        dragging = true
                        moved = false
                        downY = y
                        downReveal = reveal
                        downOnClose = abs(event.x - closeCenterX()) <= closeSize
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    // Anywhere else on the cover only reaches here with
                    // touch-through off: hold to see under it.
                    if (!touchThrough && y < coverBottom()) {
                        peeking = true
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        invalidate()
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val dy = downY - event.y
                        if (!moved && abs(dy) > slop) moved = true
                        if (moved) {
                            reveal = (downReveal + dy.roundToInt()).coerceIn(0, maxReveal())
                            invalidate()
                        }
                        return true
                    }
                    return peeking
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        val tap = !moved && event.actionMasked == MotionEvent.ACTION_UP
                        if (tap && downOnClose) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onClose()
                        } else if (tap) {
                            reveal = 0
                            invalidate()
                        }
                        // The handle moved with the edge: the window's
                        // touchable region has to follow it.
                        requestInsets()
                        return true
                    }
                    if (peeking) {
                        peeking = false
                        invalidate()
                        return true
                    }
                    return false
                }
            }
            return dragging || peeking
        }
    }

    private companion object {
        const val HANDLE_WIDTH_DP = 44f
        const val STRIP_HEIGHT_DP = 36f
        const val CLOSE_SIZE_DP = 26f
        /** The least of the app the cover keeps covered while it is dragged up. */
        const val MIN_COVER_DP = 48f
        /** Below this much cover the hint is left out, so it never sits on the handle. */
        const val HINT_ROOM_DP = 120f
        const val HANDLE_ALPHA = 200
        const val EDGE_ALPHA = 60
        const val HINT_ALPHA = 120
        const val CLOSE_RING_ALPHA = 36

        /** The cover's colour and the colour its handle and hint are drawn in. */
        fun colours(style: PrivacyShadeStyle): Pair<Int, Int> = when (style) {
            PrivacyShadeStyle.DARK -> Color.rgb(0x12, 0x13, 0x16) to Color.WHITE
            PrivacyShadeStyle.BLACK -> Color.BLACK to Color.WHITE
            PrivacyShadeStyle.GREY -> Color.rgb(0x5F, 0x63, 0x68) to Color.WHITE
            PrivacyShadeStyle.LIGHT -> Color.rgb(0xF1, 0xF3, 0xF4) to Color.rgb(0x20, 0x21, 0x24)
            PrivacyShadeStyle.WARM -> Color.rgb(0x3E, 0x2F, 0x26) to Color.rgb(0xFF, 0xE0, 0xC2)
        }
    }
}

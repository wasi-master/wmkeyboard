package com.wasimaster.wmkeyboard.ime.speedtest

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.SpeedTestSettings
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardLabels
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardPalette
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardRenderer
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSize
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSpec
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardStyle
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestConfig
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestEngine
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestMath
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestPhase
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestResult
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestServer
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.ProviderPicks
import com.wasimaster.wmkeyboard.ime.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The speed test on the keyboard: starts and stops a run, publishes its
 * progress, remembers the result, and turns it into the card the panel
 * previews and the field receives. The sockets are [SpeedTestEngine]'s, the
 * drawing [SpeedCardRenderer]'s; this is the part that knows about the
 * keyboard.
 */
class SpeedTestController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
    private val host: Host,
) {
    /** What the controller needs of the keyboard. */
    interface Host {
        val state: KeyboardUiState
        fun publish(change: (KeyboardUiState) -> KeyboardUiState)
        fun haptic()

        /** Whether a run may go on this connection, data saving considered. */
        fun metered(): MeteredDecision

        /** The user said "run anyway": the feature is allowed for the rest of the session. */
        fun grantMetered()

        /** Sends [file] (a PNG) into the field, as a sticker where [mode] asks and the app takes one. */
        fun send(file: File, mode: MediaSendMode)

        /** Hands [file] to another app's share screen. */
        fun share(file: File)

        /** Copies [file] into Pictures/WM Keyboard; [confirm] says so in a toast. */
        fun saveToGallery(file: File, confirm: Boolean)
        fun insertText(text: String)
        fun toast(message: Int)

        /** Puts the keys back: after the text went into the field there is nothing left to do here. */
        fun closePanel()
    }

    private val engine = SpeedTestEngine()
    private var runJob: Job? = null
    private var previewJob: Job? = null
    private var themePalette: SpeedCardPalette? = null

    private val ui: SpeedTestUi get() = host.state.speedTest
    private val settings: SpeedTestSettings get() = host.state.settings.speedTest

    /** The card as the settings have it: the chips write there and read from there. */
    private fun spec(s: SpeedTestSettings = settings) = SpeedCardSpec(
        style = s.cardStyle,
        size = s.cardSize,
        layout = s.cardLayout,
        unit = s.unit,
        showIp = s.showIp,
    )

    private fun config(s: SpeedTestSettings = settings) = SpeedTestConfig(
        durationS = s.duration,
        connections = s.connections,
        upload = s.upload,
    )

    private fun publish(change: (SpeedTestUi) -> SpeedTestUi) = host.publish { it.copy(speedTest = change(it.speedTest)) }

    /** The panel opened: the last run comes back as the result on screen, with its card. */
    fun onOpen() {
        if (ui.stage == SpeedTestStage.RUNNING) return
        val remembered = ui.result ?: SpeedTestResult.decode(settings.lastResult)
        publish {
            it.copy(
                stage = if (remembered != null) SpeedTestStage.DONE else SpeedTestStage.IDLE,
                result = remembered,
                error = null,
                estimatedMb = SpeedTestMath.estimatedDataMb(config(), remembered),
            )
        }
        if (remembered != null && (ui.preview == null || ui.previewSpec != spec())) renderPreview()
    }

    /** The panel closed: a run under way stops; the result stays for the next open. */
    fun onClose() {
        cancel()
        previewJob?.cancel()
        previewJob = null
    }

    /** Stops a run under way, if any. */
    fun cancel() {
        val job = runJob ?: return
        runJob = null
        job.cancel()
        publish { it.copy(stage = if (it.result != null) SpeedTestStage.DONE else SpeedTestStage.IDLE, phase = null) }
    }

    fun onAction(action: SpeedTestAction) {
        when (action) {
            SpeedTestAction.Start, SpeedTestAction.Again -> start(force = false)
            SpeedTestAction.AllowMetered -> {
                host.grantMetered()
                start(force = true)
            }
            SpeedTestAction.Cancel -> {
                host.haptic()
                if (runJob != null) cancel() else publish { it.copy(stage = if (it.result != null) SpeedTestStage.DONE else SpeedTestStage.IDLE) }
            }
            is SpeedTestAction.SetStyle ->
                changeSpec(settings.copy(cardStyle = action.style)) { repository.setSpeedTestCardStyle(action.style) }
            is SpeedTestAction.SetSize ->
                changeSpec(settings.copy(cardSize = action.size)) { repository.setSpeedTestCardSize(action.size) }
            is SpeedTestAction.SetLayout ->
                changeSpec(settings.copy(cardLayout = action.layout)) { repository.setSpeedTestCardLayout(action.layout) }
            is SpeedTestAction.SetUnit ->
                changeSpec(settings.copy(unit = action.unit)) { repository.setSpeedTestUnit(action.unit) }
            SpeedTestAction.Send -> export { file, s ->
                if (s.saveToGallery) host.saveToGallery(file, confirm = false)
                host.send(file, if (s.cardSize == SpeedCardSize.STICKER) s.sendMode else MediaSendMode.IMAGE)
            }
            SpeedTestAction.Save -> export { file, _ -> host.saveToGallery(file, confirm = true) }
            SpeedTestAction.Share -> export { file, _ -> host.share(file) }
            SpeedTestAction.InsertText -> {
                val result = ui.result ?: return
                host.haptic()
                host.insertText(SpeedTestMath.summaryLine(result, settings.unit))
                host.closePanel()
            }
            is SpeedTestAction.Theme -> {
                if (themePalette == action.palette) return
                themePalette = action.palette
                if (settings.cardStyle == SpeedCardStyle.THEME && ui.result != null) renderPreview()
            }
        }
    }

    /**
     * A chip changed the card: the preview is drawn again at once from
     * [next], the settings as they are about to be, and the setting is
     * written so the card comes back this way next time.
     */
    private fun changeSpec(next: SpeedTestSettings, write: suspend () -> Unit) {
        host.haptic()
        if (ui.result != null && ui.stage != SpeedTestStage.RUNNING) renderPreview(next)
        scope.launch { write() }
    }

    // ---- the run ----

    private fun start(force: Boolean) {
        if (runJob?.isActive == true) return
        host.haptic()
        val estimate = SpeedTestMath.estimatedDataMb(config(), ui.result)
        if (!force) {
            when (host.metered()) {
                MeteredDecision.ALLOWED -> Unit
                MeteredDecision.ASK -> {
                    publish { it.copy(stage = SpeedTestStage.ASK_METERED, estimatedMb = estimate, canAllow = true, error = null) }
                    return
                }
                MeteredDecision.BLOCKED -> {
                    publish { it.copy(stage = SpeedTestStage.ASK_METERED, estimatedMb = estimate, canAllow = false, error = null) }
                    return
                }
            }
        }
        val server = SpeedTestProviders.server(host.state.settings, ProviderPicks.of(ProviderTool.SPEED_TEST))
        if (server == null) {
            publish { it.copy(stage = SpeedTestStage.ERROR, error = context.getString(R.string.ime_speed_no_server)) }
            return
        }
        val previous = ui.result
        val config = config()
        publish {
            it.copy(
                stage = SpeedTestStage.RUNNING,
                phase = SpeedTestPhase.META,
                liveMbps = 0.0,
                liveSamples = emptyList(),
                pingMs = null,
                previous = previous,
                preview = null,
                previewSpec = null,
                error = null,
                estimatedMb = estimate,
                serverLabel = server.label,
            )
        }
        runJob = scope.launch {
            try {
                val result = engine.run(server, config) { progress ->
                    publish {
                        it.copy(
                            phase = progress.phase,
                            liveMbps = progress.mbps,
                            liveSamples = progress.samples,
                            pingMs = progress.pingMs ?: it.pingMs,
                        )
                    }
                }
                runJob = null
                publish { it.copy(stage = SpeedTestStage.DONE, phase = null, result = result, previous = previous) }
                host.haptic()
                renderPreview()
                repository.setSpeedTestLastResult(result.encode())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                runJob = null
                publish { it.copy(stage = SpeedTestStage.ERROR, phase = null, error = errorText(t, server)) }
            }
        }
    }

    /** What the error face says: the server's refusal in words when that is what stopped the run. */
    private fun errorText(t: Throwable, server: SpeedTestServer): String {
        // The offline build refuses before any byte moves, and the refusal's
        // own message is untranslated English that says nothing about a way out.
        if (OfflineBuild.active) return context.getString(OfflineBuild.network(R.string.ime_speed_error))
        if (t is SpeedTestEngine.PhaseFailedException) {
            val phase = context.getString(
                if (t.phase == SpeedTestPhase.UPLOAD) R.string.ime_speed_phase_upload else R.string.ime_speed_phase_download,
            )
            return when (val status = t.status) {
                null -> context.getString(R.string.ime_speed_phase_failed, phase)
                HTTP_TOO_MANY -> context.getString(R.string.ime_speed_rate_limited, server.label)
                else -> context.getString(R.string.ime_speed_http_error, server.label, status, phase)
            }
        }
        return t.localizedMessage?.takeIf { m -> m.isNotBlank() && m.length < 120 }
            ?: context.getString(R.string.ime_speed_error)
    }

    // ---- the card ----

    private fun labels() = SpeedCardLabels(
        download = context.getString(R.string.ime_speed_phase_download),
        upload = context.getString(R.string.ime_speed_phase_upload),
        ping = context.getString(R.string.ime_speed_phase_ping),
        jitter = context.getString(R.string.ime_speed_label_jitter),
        vsLast = context.getString(R.string.ime_speed_vs_last),
        wordmark = WORDMARK,
    )

    /** Draws the card small for the panel, off the main thread. */
    private fun renderPreview(s: SpeedTestSettings = settings) {
        val result = ui.result ?: return
        val spec = spec(s)
        val previous = ui.previous
        val palette = themePalette
        val labels = labels()
        // About the panel's height at this density, whatever the card's size.
        val targetPx = context.resources.displayMetrics.density * PREVIEW_HEIGHT_DP
        val scale = (targetPx / spec.size.height).coerceIn(0.1f, 1f)
        previewJob?.cancel()
        previewJob = scope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { SpeedCardRenderer.render(result, spec, labels, palette, previous, scale = scale) }.getOrNull()
            }
            publish { it.copy(preview = bitmap?.asImageBitmap(), previewSpec = spec) }
        }
    }

    /** Draws the card at full size into the media cache and hands the file on. */
    private fun export(then: (File, SpeedTestSettings) -> Unit) {
        val result = ui.result ?: return
        if (ui.rendering) return
        host.haptic()
        val s = settings
        val spec = spec(s)
        val previous = ui.previous
        val palette = themePalette
        val labels = labels()
        publish { it.copy(rendering = true) }
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = SpeedCardRenderer.render(result, spec, labels, palette, previous)
                    val dir = File(context.cacheDir, "media").apply { mkdirs() }
                    val name = "speed_${result.at}_${spec.style.name}_${spec.size.name}_${spec.layout.name}".lowercase() + ".png"
                    File(dir, name).also { target ->
                        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }.getOrNull()
            }
            publish { it.copy(rendering = false) }
            if (file == null) host.toast(R.string.ime_speed_card_error) else then(file, s)
        }
    }

    private companion object {
        const val HTTP_TOO_MANY = 429
        const val PREVIEW_HEIGHT_DP = 200f
        const val WORDMARK = "WM Keyboard"
    }
}

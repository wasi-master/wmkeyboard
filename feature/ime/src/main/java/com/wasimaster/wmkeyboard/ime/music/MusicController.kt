package com.wasimaster.wmkeyboard.ime.music

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.core.media.MediaSnapshot
import com.wasimaster.wmkeyboard.core.music.MusicCardStyle
import com.wasimaster.wmkeyboard.core.music.MusicClient
import com.wasimaster.wmkeyboard.core.music.MusicClip
import com.wasimaster.wmkeyboard.core.music.MusicHistory
import com.wasimaster.wmkeyboard.core.music.MusicLink
import com.wasimaster.wmkeyboard.core.music.MusicMetadata
import com.wasimaster.wmkeyboard.core.music.MusicTextFormat
import com.wasimaster.wmkeyboard.core.music.MusicTextFormats
import com.wasimaster.wmkeyboard.core.music.SongMatch
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.voice.MicBlockWatcher
import com.wasimaster.wmkeyboard.core.voice.WhisperRecorder
import com.wasimaster.wmkeyboard.ime.ClipboardWriteGate
import com.wasimaster.wmkeyboard.ime.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keyboard half of the Music ID tool.
 *
 * Holds the panel's [MusicUi], runs the microphone, asks the recognisers
 * and types or sends what they said. Owned by the service and built on
 * first use; nothing here runs until the panel is opened.
 *
 * The microphone is the one thing that must never outlive the panel: every
 * way out of the panel, the field and the service ends in [release].
 */
@Suppress("detekt.LongParameterList", "detekt.TooManyFunctions")
internal class MusicController(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> KeyboardSettings,
    private val insertText: (String) -> Unit,
    /** Puts a picture file into the field, with the fallbacks every picture has. */
    private val insertImage: (File, String) -> Unit,
    private val haptic: () -> Unit,
    private val panelOpen: () -> Boolean,
    private val openToolSettings: () -> Unit,
    /** What the phone is playing right now, or null. */
    private val nowPlaying: () -> MediaSnapshot?,
    /** Dictation holds the microphone. */
    private val voiceBusy: () -> Boolean,
    private val secureField: () -> Boolean,
    private val incognito: () -> Boolean,
    private val dataSaver: () -> DataSaverStatus,
    /** The user said "use data" for the rest of the session. */
    private val grantMetered: () -> Unit,
    /** The microphone permission's trampoline. */
    private val requestMic: () -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(MusicUi())
    val state: StateFlow<MusicUi> = _state

    val callbacks = MusicCallbacks(state = state, onAction = ::dispatch)

    private var recorder: WhisperRecorder? = null
    private var ticker: Job? = null
    private var work: Job? = null
    private var watcher: Job? = null
    private val blockWatcher by lazy { MicBlockWatcher(this.context) }

    /** A clip data saving held back, for "Use data". */
    private var pendingClip: FloatArray? = null

    /** The last cover fetched, by address, so a history pick does not fetch it again. */
    private var artCache: Pair<String, Bitmap>? = null

    // ---- Lifecycle -------------------------------------------------------

    fun onPanelOpen() {
        if (watcher == null) {
            watcher = scope.launch {
                MusicHistory.get(context).state.collect { data -> _state.update { it.copy(history = data.entries) } }
            }
        }
        refreshSources()
        _state.update { it.copy(notice = null, pictureStrip = false, micBusy = voiceBusy()) }
        // A clip or a question left from last time is stale; a result is still worth showing.
        when (_state.value.phase) {
            is MusicPhase.Listening, is MusicPhase.Recognizing, MusicPhase.MeteredAsk -> _state.update { it.copy(phase = MusicPhase.Idle) }
            else -> Unit
        }
        pendingClip = null
    }

    fun onPanelClose() {
        cancelListening()
        work?.cancel()
        work = null
        _state.update { it.copy(pictureStrip = false, notice = null) }
    }

    /** Back from a system screen (the microphone permission), so what it changed shows. */
    fun onKeyboardShown() {
        if (watcher == null) return
        refreshSources()
        if (_state.value.phase == MusicPhase.NeedsMic && micGranted() && panelOpen()) listen()
    }

    /** The keyboard is going away: the microphone goes with it. */
    fun release() {
        cancelListening()
        work?.cancel()
        work = null
        if (_state.value.phase is MusicPhase.Listening || _state.value.phase is MusicPhase.Recognizing) {
            _state.update { it.copy(phase = MusicPhase.Idle) }
        }
    }

    private fun refreshSources() {
        val online = MusicProviders.online(settings()).map { typeLabel(it.typeId) }
        _state.update { it.copy(onlineNames = online) }
    }

    private fun typeLabel(typeId: String): String =
        ProviderTypes.of(typeId)?.let { context.getString(it.labelRes) } ?: typeId

    // ---- Actions ---------------------------------------------------------

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun dispatch(action: MusicAction) {
        if (action !is MusicAction.Dismiss) _state.update { it.copy(notice = null) }
        when (action) {
            MusicAction.Listen -> listen()
            MusicAction.Stop -> stopEarly()
            MusicAction.ListenAnyway -> listen(skipNowPlaying = true)
            is MusicAction.Insert -> insert(action.format)
            is MusicAction.InsertPicture -> insertPicture(action.style)
            is MusicAction.PictureStrip -> _state.update { it.copy(pictureStrip = action.open) }
            is MusicAction.Open -> open(action.link)
            MusicAction.Copy -> copy()
            is MusicAction.Tab -> _state.update { it.copy(tab = action.tab, pictureStrip = false) }
            is MusicAction.HistoryPick -> pickHistory(action.id)
            is MusicAction.HistoryDelete -> {
                haptic()
                MusicHistory.get(context).remove(action.id)
            }
            MusicAction.HistoryClear -> {
                haptic()
                MusicHistory.get(context).clear()
            }
            MusicAction.UseData -> {
                grantMetered()
                val clip = pendingClip ?: return
                pendingClip = null
                work = scope.launch { askOnline(MusicProviders.online(settings()), clip) }
            }
            MusicAction.RequestMic -> requestMic()
            MusicAction.OpenSettings -> openToolSettings()
            MusicAction.Dismiss -> _state.update { it.copy(notice = null, phase = if (it.phase == MusicPhase.MeteredAsk) MusicPhase.Idle else it.phase) }
        }
    }

    // ---- Listening -------------------------------------------------------

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun listen(skipNowPlaying: Boolean = false) {
        if (recorder != null) return
        work?.cancel()
        pendingClip = null
        _state.update { it.copy(pictureStrip = false) }
        if (voiceBusy()) {
            _state.update { it.copy(micBusy = true, notice = context.getString(R.string.ime_music_mic_busy)) }
            return
        }
        val s = settings()
        if (!skipNowPlaying && s.music.nowPlayingFirst) {
            nowPlaying()?.takeIf { it.title.isNotBlank() }?.let { playing ->
                haptic()
                _state.update { it.copy(phase = MusicPhase.NowPlaying(playing.toMatch(), playing.art)) }
                return
            }
        }
        if (!micGranted()) {
            _state.update { it.copy(phase = MusicPhase.NeedsMic) }
            return
        }
        refreshSources()
        if (_state.value.onlineNames.isEmpty()) {
            _state.update { it.copy(phase = MusicPhase.NeedsProvider) }
            return
        }
        haptic()
        val seconds = s.music.clipSeconds.coerceIn(MusicClip.MIN_SECONDS, MusicClip.MAX_SECONDS)
        val rec = WhisperRecorder(
            onLevel = { level -> main.post { onLevel(level) } },
            onMaxReached = { main.post { finishListening() } },
            onLost = { main.post { finishListening() } },
            maxSeconds = seconds,
        )
        if (!rec.start()) {
            _state.update { it.copy(phase = MusicPhase.Failed(context.getString(R.string.ime_music_mic_failed))) }
            return
        }
        recorder = rec
        _state.update { it.copy(phase = MusicPhase.Listening(0f, seconds)) }
        runCatching { blockWatcher.start(rec.audioSessionId) { main.post { onMicBlocked() } } }
        ticker = scope.launch {
            while (isActive && recorder === rec) {
                delay(TICK_MS)
                _state.update { ui ->
                    val p = ui.phase
                    if (p is MusicPhase.Listening) ui.copy(phase = p.copy(secondsLeft = rec.secondsLeft)) else ui
                }
            }
        }
    }

    private fun onLevel(level: Float) {
        val quantized = (level * LEVEL_STEPS).toInt() / LEVEL_STEPS.toFloat()
        _state.update {
            val p = it.phase
            if (p is MusicPhase.Listening && p.level != quantized) it.copy(phase = p.copy(level = quantized)) else it
        }
    }

    private fun onMicBlocked() {
        if (recorder == null) return
        cancelListening()
        _state.update { it.copy(phase = MusicPhase.Failed(context.getString(R.string.ime_music_mic_blocked))) }
    }

    /** The big button tapped while listening: ask about what was heard, if that is enough to ask about. */
    private fun stopEarly() {
        val rec = recorder ?: return
        if (rec.sampleCount < MusicClip.ASK_AFTER_SECONDS * MusicClip.SAMPLE_RATE) {
            _state.update { it.copy(notice = context.getString(R.string.ime_music_too_short)) }
            return
        }
        haptic()
        finishListening()
    }

    private fun finishListening() {
        val rec = recorder ?: return
        recorder = null
        ticker?.cancel()
        ticker = null
        runCatching { blockWatcher.stop() }
        val clients = MusicProviders.online(settings())
        _state.update { it.copy(phase = MusicPhase.Recognizing(clients.firstOrNull()?.let { c -> typeLabel(c.typeId) }.orEmpty())) }
        work = scope.launch {
            val samples = withContext(Dispatchers.IO) { rec.stop() }
            if (samples.size < MusicClip.ASK_AFTER_SECONDS * MusicClip.SAMPLE_RATE) {
                _state.update { it.copy(phase = MusicPhase.Idle, notice = context.getString(R.string.ime_music_too_short)) }
                return@launch
            }
            if (!MusicClip.heardSomething(samples)) {
                _state.update { it.copy(phase = MusicPhase.NoMatch(silent = true)) }
                return@launch
            }
            recognize(clients, samples)
        }
    }

    private fun cancelListening() {
        val rec = recorder ?: return
        recorder = null
        ticker?.cancel()
        ticker = null
        runCatching { blockWatcher.stop() }
        scope.launch(Dispatchers.IO) { rec.stop() }
    }

    // ---- Recognising -----------------------------------------------------

    private suspend fun recognize(clients: List<MusicClient>, samples: FloatArray) {
        if (clients.isEmpty()) {
            _state.update { it.copy(phase = MusicPhase.NeedsProvider) }
            return
        }
        if (secureField() || incognito()) {
            _state.update { it.copy(phase = MusicPhase.Idle, notice = context.getString(R.string.ime_music_secure_field)) }
            return
        }
        when (dataSaver().decide(MeteredFeature.CLOUD_MUSIC)) {
            MeteredDecision.ASK -> {
                pendingClip = samples
                _state.update { it.copy(phase = MusicPhase.MeteredAsk) }
                return
            }
            MeteredDecision.BLOCKED -> {
                _state.update { it.copy(phase = MusicPhase.Idle, notice = context.getString(R.string.ime_music_metered_blocked)) }
                return
            }
            MeteredDecision.ALLOWED -> Unit
        }
        askOnline(clients, samples)
    }

    private suspend fun askOnline(clients: List<MusicClient>, samples: FloatArray) {
        if (clients.isEmpty()) {
            _state.update { it.copy(phase = MusicPhase.NeedsProvider) }
            return
        }
        _state.update { it.copy(phase = MusicPhase.Recognizing(typeLabel(clients.first().typeId))) }
        val result = withContext(Dispatchers.IO) { MusicProviders.recognize(clients, samples, MusicClip.SAMPLE_RATE) }
        when (result) {
            is MusicProviders.Result.Found -> show(result.match, enrich = !result.match.hasArt)
            MusicProviders.Result.NotFound -> _state.update { it.copy(phase = MusicPhase.NoMatch(silent = false)) }
            is MusicProviders.Result.Failed -> _state.update {
                it.copy(phase = MusicPhase.Failed(result.message ?: context.getString(R.string.ime_music_failed_generic)))
            }
        }
    }

    /** Whether a lookup for a cover may go out now: not in a password field, not incognito, and data saving allows it outright. */
    private fun lookupsAllowed(): Boolean =
        !secureField() && !incognito() && dataSaver().allows(MeteredFeature.CLOUD_MUSIC)

    private suspend fun show(match: SongMatch, enrich: Boolean) {
        val full = if (enrich && lookupsAllowed()) {
            withContext(Dispatchers.IO) { runCatching { MusicMetadata.enrich(match) }.getOrDefault(match) }
        } else {
            match
        }
        val art = full.artUrl?.let { loadArt(it) }
        if (settings().music.keepHistory) MusicHistory.get(context).add(full)
        haptic()
        _state.update { it.copy(phase = MusicPhase.Result(full, art), tab = MusicTab.LISTEN) }
    }

    private suspend fun loadArt(url: String): Bitmap? {
        artCache?.takeIf { it.first == url }?.let { return it.second }
        if (!lookupsAllowed()) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ToolHttp.bytes(url, source = NetSource.MUSIC_METADATA, maxBytes = MAX_ART_BYTES).body
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > MAX_ART_PX || bounds.outHeight / sample > MAX_ART_PX) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
        if (bitmap != null) artCache = url to bitmap
        return bitmap
    }

    private fun MediaSnapshot.toMatch(): SongMatch = SongMatch(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        offsetMs = positionMs,
        via = VIA_NOW_PLAYING,
    )

    // ---- What the tool types and sends ----------------------------------

    private fun insert(format: MusicTextFormat?) {
        val match = _state.value.match ?: return
        val s = settings().music
        insertText(MusicTextFormats.render(match, format ?: s.textFormat, s.openLink))
    }

    private fun insertPicture(style: MusicCardStyle?) {
        val match = _state.value.match ?: return
        val art = _state.value.art
        val chosen = style ?: settings().music.cardStyle
        haptic()
        _state.update { it.copy(pictureStrip = false) }
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = MusicCard.render(chosen, match, art, context.getString(R.string.ime_music_card_footer))
                    val dir = File(context.cacheDir, "media").apply { mkdirs() }
                    val target = File(dir, "music_${(match.identity + chosen.id).hashCode().toUInt()}.png")
                    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
                    bitmap.recycle()
                    target
                }.getOrNull()
            }
            if (file == null) {
                _state.update { it.copy(notice = context.getString(R.string.ime_music_picture_failed)) }
            } else {
                insertImage(file, "image/png")
            }
        }
    }

    private fun open(link: MusicLink) {
        val url = _state.value.match?.link(link) ?: return
        haptic()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.ime_music_no_app)
        }
    }

    private fun copy() {
        val match = _state.value.match ?: return
        val s = settings().music
        val text = MusicTextFormats.render(match, s.textFormat, s.openLink)
        haptic()
        ClipboardWriteGate.writeText(context, text, "song")
        _state.update { it.copy(notice = context.getString(R.string.ime_music_copied)) }
    }

    private fun pickHistory(id: Long) {
        val entry = _state.value.history.firstOrNull { it.id == id } ?: return
        haptic()
        work?.cancel()
        work = scope.launch {
            val art = entry.match.artUrl?.let { loadArt(it) }
            _state.update { it.copy(phase = MusicPhase.Result(entry.match, art), tab = MusicTab.LISTEN, pictureStrip = false) }
        }
    }

    private fun toast(@StringRes res: Int) {
        Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val VIA_NOW_PLAYING = "now_playing"
        private const val TICK_MS = 250L
        private const val LEVEL_STEPS = 20
        private const val MAX_ART_BYTES = 4L * 1024 * 1024
        private const val MAX_ART_PX = 800
        private const val PNG_QUALITY = 100
    }
}

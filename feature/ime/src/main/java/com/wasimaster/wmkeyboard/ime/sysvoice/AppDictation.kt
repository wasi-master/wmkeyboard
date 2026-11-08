package com.wasimaster.wmkeyboard.ime.sysvoice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.RomanizedSpeech
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled
import com.wasimaster.wmkeyboard.core.settings.serverFor
import com.wasimaster.wmkeyboard.core.settings.voiceFor
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.TranscriptionClient
import com.wasimaster.wmkeyboard.core.voice.VoiceBias
import com.wasimaster.wmkeyboard.core.voice.VoiceBiasRequest
import com.wasimaster.wmkeyboard.core.voice.VoiceClipGate
import com.wasimaster.wmkeyboard.core.voice.VoiceDenoise
import com.wasimaster.wmkeyboard.core.voice.VoiceInputEngine
import com.wasimaster.wmkeyboard.core.voice.VoiceMics
import com.wasimaster.wmkeyboard.core.voice.VoicePunctuation
import com.wasimaster.wmkeyboard.core.voice.VoiceStream
import com.wasimaster.wmkeyboard.core.voice.WhisperRecorder
import com.wasimaster.wmkeyboard.core.voice.asr4all.Asr4AllEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.moonshine.MoonshineEngine
import com.wasimaster.wmkeyboard.core.voice.otherRecognitionService
import com.wasimaster.wmkeyboard.core.voice.parakeet.ParakeetEngine
import com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation
import com.wasimaster.wmkeyboard.core.voice.recognitionServices
import com.wasimaster.wmkeyboard.core.voice.sherpa.OmnilingualEngine
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaEngine
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.core.voice.vosk.VoskEngine
import com.wasimaster.wmkeyboard.core.voice.vosk.VoskNative
import com.wasimaster.wmkeyboard.core.voice.vad.VoiceVad
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCpp
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCppPlan
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperEngine
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperLanguages
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperModel
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperScript
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleEngine
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleLanguages
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One dictation for another app (#1142): WM Keyboard's own recognition
 * service, and the voice input window apps open with
 * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, both run their requests through
 * this. It hears with the engine the keyboard's settings give the language,
 * the way the keyboard's own microphone would, without the keyboard being
 * open.
 *
 * Kept apart from the keyboard's dictation on purpose: that one lives in the
 * input method service and writes into a text field as it goes, with undo,
 * continuous phrases and the keys working around it. A request from another
 * app is one phrase, start to result, and the app does the typing.
 *
 * - An on-device engine records the phrase with [WhisperRecorder] until a
 *   pause and transcribes it. The engines that hear as they go (Sherpa,
 *   Omnilingual, Asr4All) send their words as partial results while the
 *   phrase is being said.
 * - The transcription server gets the recorded phrase.
 * - The phone's recognizer is another app's: the request is passed on to it
 *   ([VoiceInputEngine.avoidDefault]), never to the default, which may be
 *   this very service.
 *
 * Every [Listener] call is on the main thread, and ends with exactly one of
 * [Listener.onResult] or [Listener.onError], unless [cancel] came first.
 * Start, stop and cancel on the main thread too.
 */
class AppDictation(
    context: Context,
    private val settings: KeyboardSettings,
    /** The languages to listen for, the first the one the request starts in; see [AppDictationPlan.languages]. */
    private val languageIds: List<String>,
    private val listener: Listener,
    /** A pause length the calling app asked for, in milliseconds; see [AppDictationPlan.pauseMs]. */
    private val requestedPauseMs: Long? = null,
    /**
     * Pass the request to another app's recognizer whatever the engine:
     * voice typing is switched off, so the keyboard's engines are not to
     * hear anything.
     */
    private val forwardOnly: Boolean = false,
) {

    interface Listener {
        /** The microphone is open. */
        fun onReady()

        /** The microphone level, 0..1. */
        fun onLevel(level: Float)

        /** Speech was heard for the first time in this request. */
        fun onSpeechStart() {}

        /** The words heard so far, for engines that can tell. */
        fun onPartial(text: String) {}

        /** Listening has stopped; the words are being worked out. */
        fun onEndOfSpeech() {}

        fun onResult(text: String)

        fun onError(error: Error)
    }

    /** Why a request ended without words. */
    enum class Error {
        /** Nothing that sounded like speech. */
        NO_SPEECH,

        /** No engine can hear this request: nothing downloaded, no server, no recognizer. */
        NO_ENGINE,

        /** WM Keyboard may not use the microphone. */
        PERMISSION,

        /** The microphone would not open, or went away. */
        MIC,

        /** The server or the recognizer could not be reached. */
        NETWORK,

        /** The recognizer this was passed to is busy. */
        BUSY,

        /** The engine failed on the phrase. */
        TRANSCRIBE,
    }

    /** What a phrase is transcribed with on the phone. */
    private sealed interface Local {
        data class Whisper(val model: WhisperModel) : Local
        data object Whistle : Local
        data class Et(val model: EtModel) : Local
    }

    private val app = context.applicationContext
    private val filesDir: File = app.filesDir
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tag: String = tagOf(languageIds.first())
    private val pauseMs = AppDictationPlan.pauseMs(settings.voiceBar.silenceStopMs, requestedPauseMs)
    private val plain = settings.voiceBar.typingMode == VoiceBarSettings.TYPING_PLAIN

    /** The recording of the phrase; null before, and once it has ended. Main thread writes, any reads. */
    @Volatile private var recorder: WhisperRecorder? = null

    /** The engine the recording is for; null for the server. */
    private var capturing: Local? = null

    /** The request was passed to another app's recognizer. */
    private var forward: VoiceInputEngine? = null

    /** A result, an error or a cancel has ended the request: nothing more is said. */
    private var done = false

    /** The words of an engine that hears as it goes, fed from [liveJob]. */
    @Volatile private var liveStream: VoiceStream? = null

    /** The thread [liveStream] belongs to, where it is closed. */
    @Volatile private var liveStreamDispatcher: CoroutineDispatcher = SherpaNative.dispatcher
    @Volatile private var liveFed = 0
    private var liveJob: Job? = null
    private var lastLevel = -1f
    private var spoke = false

    private val noSpeechCheck = Runnable {
        val rec = recorder ?: return@Runnable
        if (!rec.heardSpeech) {
            stopRecording(rec)
            fail(Error.NO_SPEECH)
        }
    }

    /** Opens the microphone, or ends the request at once with the reason it cannot. */
    fun start() {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(Error.PERMISSION)
            return
        }
        val local = if (forwardOnly) null else localVoice()
        val fallback = if (local == null && !forwardOnly) fallbackVoice() else null
        val forwardTo = forwardTarget()
        val onDeviceRecognizer = settings.whisper.systemOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        val route = AppDictationPlan.route(
            engine = if (forwardOnly) AppDictationPlan.ENGINE_SYSTEM else settings.whisper.voiceFor(languageIds.first()).engine,
            onDeviceReady = local != null,
            fallbackReady = fallback != null,
            serverReady = !forwardOnly && settings.whisper.serverFor(languageIds.first()).url.isNotBlank(),
            forwardReady = forwardTo != null || onDeviceRecognizer,
        )
        when (route) {
            AppDictationPlan.Route.ON_DEVICE -> (local ?: fallback)?.let { startCapture(it) } ?: fail(Error.NO_ENGINE)
            AppDictationPlan.Route.SERVER -> startCapture(null)
            AppDictationPlan.Route.FORWARD -> startForward(forwardTo)
            AppDictationPlan.Route.NONE -> fail(Error.NO_ENGINE)
        }
    }

    /** The user is done talking: what was heard becomes the result. */
    fun stop() {
        if (done) return
        forward?.let {
            it.finish()
            return
        }
        recorder?.let { endPhrase() }
    }

    /** Drops the request: the microphone closes and nothing more is said. */
    fun cancel() {
        if (done) return
        done = true
        main.removeCallbacks(noSpeechCheck)
        forward?.cancel()
        forward = null
        recorder?.let(::stopRecording)
        liveJob?.cancel()
        dropLiveStream()
        scope.cancel()
    }

    // ---- choosing the engine ----

    /** The model on the phone that the language's engine runs, when it is there and can run now. */
    private fun localVoice(): Local? {
        val choice = settings.whisper.voiceFor(languageIds.first())
        val voice = when (choice.engine) {
            "whisper" -> if (!isWhisperEnabled()) {
                null
            } else {
                (
                    choice.modelId?.let(WhisperCatalog::byId)?.takeIf { WhisperStore.isDownloaded(filesDir, it) }
                        ?: WhisperStore.modelForLanguage(
                            filesDir,
                            languageIds.first(),
                            settings.whisper.modelId,
                            settings.whisper.modelByLang,
                        )
                    )?.let { Local.Whisper(it) }
            }
            "whistle" -> Local.Whistle.takeIf { whistleCovers() && WhistleModelStore.isDownloaded(filesDir) }
            else -> EtEngine.byId(choice.engine)
                ?.takeIf(::etCovers)
                ?.let { engine ->
                    choice.modelId?.let(EtCatalog::byId)
                        ?.takeIf { model ->
                            model.engine == engine && languageIds.all(model::supports) &&
                                EtStore.isDownloaded(filesDir, model)
                        }
                        ?: EtStore.active(filesDir, engine, languageIds)
                }
                ?.let { Local.Et(it) }
        }
        return voice?.takeIf(::runtimeHere)
    }

    /**
     * Any model on the phone that hears these languages, for when the
     * language's own engine cannot run: Whistle, then the file-by-file
     * engines, then Whisper, as the keyboard falls back offline.
     */
    private fun fallbackVoice(): Local? {
        if (whistleCovers() && WhistleModelStore.isDownloaded(filesDir)) {
            Local.Whistle.takeIf(::runtimeHere)?.let { return it }
        }
        for (engine in EtEngine.entries) {
            if (etCovers(engine)) {
                EtStore.active(filesDir, engine, languageIds)?.let { Local.Et(it) }?.takeIf(::runtimeHere)?.let { return it }
            }
        }
        if (!isWhisperEnabled()) return null
        return WhisperStore.modelForLanguage(
            filesDir,
            languageIds.first(),
            settings.whisper.modelId,
            settings.whisper.modelByLang,
        )?.let { Local.Whisper(it) }?.takeIf(::runtimeHere)
    }

    /**
     * Whether [voice]'s runtime is in the app now. Where it is a part that
     * downloads, the keyboard and the settings fetch it; a request from
     * another app does not start a download nobody is watching.
     */
    private fun runtimeHere(voice: Local): Boolean = when (voice) {
        is Local.Whisper -> if (voice.model.ggml) WhisperCpp.moduleReady else WhisperEngine.ready
        Local.Whistle -> WhisperCpp.moduleReady
        is Local.Et -> when {
            voice.model.engine.onnx -> SherpaNative.moduleReady
            voice.model.engine == EtEngine.VOSK -> VoskNative.moduleReady
            else -> FeatureModules.executorch.installed
        }
    }

    private fun whistleCovers(): Boolean = WhistleEngine.shipped && languageIds.all(WhistleLanguages::supports)

    private fun etCovers(engine: EtEngine): Boolean = engine.runtimeShipped && languageIds.all(engine::supports)

    /**
     * Another app's recognizer for the "phone's recognizer" engine: the one
     * picked under Recognition service, else the phone's default when it is
     * not WM Keyboard, else the first one installed. Null when the phone's
     * on-device recognizer is to be used, or there is none.
     */
    private fun forwardTarget(): ComponentName? {
        val installed = recognitionServices(app).map { it.first }
        settings.whisper.systemService
            .takeIf { it.isNotBlank() }
            ?.let(ComponentName::unflattenFromString)
            ?.takeIf { it in installed }
            ?.let { return it }
        return otherRecognitionService(app)
    }

    // ---- recording ----

    private fun startCapture(voice: Local?) {
        val live = (voice as? Local.Et)?.model?.takeIf { liveEngine(it.engine) }
        val maxSeconds = when {
            (voice as? Local.Et)?.model?.engine == EtEngine.PARAKEET -> ParakeetEngine.MAX_CLIP_SECONDS
            (voice as? Local.Whisper)?.model?.ggml == true || live != null -> WhisperCppPlan.MAX_CLIP_SECONDS
            else -> WhisperMel.CHUNK_SECONDS
        }
        val denoise = settings.whisper.denoise
            .takeIf { voice != null && it != VoiceDenoise.OFF }
            ?.let { VoiceDenoise.newStream(it, filesDir) }
        lateinit var rec: WhisperRecorder
        val ended: () -> Unit = { main.post { if (recorder === rec) endPhrase() } }
        rec = WhisperRecorder(
            onLevel = { level -> onLevel(rec, level) },
            onMaxReached = ended,
            onLost = { main.post { if (recorder === rec) failRecording(rec, Error.MIC) } },
            // An engine that hears as it goes ends the phrase by its own
            // phrase end detector; the others by the recorder's loudness.
            silenceStopMs = if (live != null) 0 else pauseMs,
            onSilence = ended,
            preferredDevice = VoiceMics.deviceFor(app, settings.voiceBar.mic),
            maxSeconds = maxSeconds,
            denoise = denoise,
        )
        if (!rec.start()) {
            fail(Error.MIC)
            return
        }
        recorder = rec
        capturing = voice
        listener.onReady()
        main.postDelayed(noSpeechCheck, AppDictationPlan.NO_SPEECH_MS)
        warm(voice)
        if (live != null) startLive(live, rec)
    }

    /** Reader thread: the level, quantized so the main thread hears of changes only. */
    private fun onLevel(rec: WhisperRecorder, level: Float) {
        val quantized = (level * 8).toInt() / 8f
        val heard = rec.heardSpeech
        main.post {
            if (recorder !== rec || done) return@post
            if (quantized != lastLevel) {
                lastLevel = quantized
                listener.onLevel(quantized)
            }
            if (heard && !spoke) {
                spoke = true
                listener.onSpeechStart()
            }
        }
    }

    /** Loads the model while the phrase is being said, so the result does not wait for it. */
    private fun warm(voice: Local?) {
        when (voice) {
            is Local.Whisper -> scope.launch(Dispatchers.Default) {
                runCatching {
                    if (voice.model.ggml) {
                        WhisperCpp.warm(
                            WhisperStore.modelFile(filesDir, voice.model),
                            WhisperCppPlan.wantsGpu(voice.model, settings.whisper.compute),
                        )
                    } else {
                        WhisperEngine.warm(
                            WhisperStore.modelFile(filesDir, voice.model),
                            WhisperStore.vocabFile(filesDir, voice.model),
                        )
                    }
                }
            }
            Local.Whistle -> scope.launch(Dispatchers.Default) {
                runCatching { WhistleEngine.warm(WhistleModelStore.modelFile(filesDir)) }
            }
            is Local.Et -> if (!liveEngine(voice.model.engine)) {
                scope.launch(EtNative.dispatcher) {
                    runCatching {
                        when (voice.model.engine) {
                            EtEngine.PARAKEET -> ParakeetEngine.warm(filesDir, voice.model)
                            else -> MoonshineEngine.warm(filesDir, voice.model)
                        }
                    }
                }
            }
            null -> Unit
        }
    }

    /**
     * Feeds the phrase to an engine that hears as it goes while it is being
     * said: its words go out as partial results, and its phrase end detector
     * ends the phrase.
     */
    private fun startLive(model: EtModel, rec: WhisperRecorder) {
        val words = liveWords(model)
        liveFed = 0
        liveJob = scope.launch(liveDispatcher(model.engine)) {
            val stream = runCatching { newLiveStream(model, words) }.getOrNull() ?: return@launch
            liveStreamDispatcher = liveDispatcher(model.engine)
            liveStream = stream
            var shown = ""
            while (isActive && recorder === rec) {
                val count = rec.sampleCount
                if (count > liveFed) {
                    stream.feed(rec.samples(liveFed, count))
                    liveFed = count
                    val text = stream.text().trim()
                    if (text != shown) {
                        shown = text
                        withContext(Dispatchers.Main) { if (!done && recorder === rec) listener.onPartial(text) }
                    }
                    if (stream.endpoint) {
                        withContext(Dispatchers.Main) { if (recorder === rec) endPhrase() }
                        break
                    }
                }
                delay(LIVE_POLL_MS)
            }
        }
    }

    private fun newLiveStream(model: EtModel, words: List<String>): VoiceStream {
        val vad = VoiceVad.Setup(settings.whisper.vad, settings.whisper.vadThreshold, settings.whisper.smartTurn)
        return when (model.engine) {
            EtEngine.SHERPA -> SherpaEngine.newStream(filesDir, model, pauseMs, words, vad)
            EtEngine.OMNILINGUAL -> OmnilingualEngine.newStream(filesDir, model, pauseMs, languageIds, vad)
            EtEngine.VOSK -> VoiceVad.endpointed(
                VoskEngine.newStream(filesDir, model),
                VoiceVad.phraseEnd(filesDir, vad, null, pauseMs),
            )
            EtEngine.PARAKEET -> VoiceVad.endpointed(
                ParakeetEngine.newStream(filesDir, model),
                VoiceVad.phraseEnd(filesDir, vad, null, pauseMs),
            )
            else -> VoiceVad.endpointed(
                Asr4AllEngine.newStream(filesDir, model),
                VoiceVad.phraseEnd(filesDir, vad, null, pauseMs),
            )
        }
    }

    private fun dropLiveStream() {
        val stream = liveStream as? AutoCloseable ?: return
        liveStream = null
        CoroutineScope(liveStreamDispatcher).launch { runCatching { stream.close() } }
    }

    // ---- the result ----

    /** The phrase is over: transcribe what was recorded. Main thread. */
    private fun endPhrase() {
        val rec = recorder ?: return
        recorder = null
        main.removeCallbacks(noSpeechCheck)
        val voice = capturing
        listener.onEndOfSpeech()
        scope.launch {
            val pcm = withContext(Dispatchers.IO) { runCatching { rec.stop() }.getOrDefault(FloatArray(0)) }
            // The feeding loop is done with the stream before it is finished here.
            liveJob?.cancelAndJoin()
            if (done) return@launch
            if (!VoiceClipGate.hasSpeech(pcm)) {
                dropLiveStream()
                fail(Error.NO_SPEECH)
                return@launch
            }
            val faint = VoiceClipGate.isFaint(pcm)
            val result = runCatching {
                if (voice == null) transcribeOnServer(pcm) else transcribeOnDevice(voice, pcm)
            }
            if (done) return@launch
            result
                .map { VoiceClipGate.clean(it, faint) }
                .onSuccess { if (it.isBlank()) fail(Error.NO_SPEECH) else succeed(it) }
                .onFailure { e -> fail(if (ToolHttp.isUnreachable(e)) Error.NETWORK else Error.TRANSCRIBE) }
        }
    }

    private suspend fun transcribeOnDevice(voice: Local, pcm: FloatArray): String = when (voice) {
        is Local.Whisper -> withContext(Dispatchers.Default) { transcribeWithWhisper(voice.model, pcm) }
        Local.Whistle -> withContext(Dispatchers.Default) {
            WhistleEngine.transcribe(
                WhistleModelStore.modelFile(filesDir),
                pcm,
                language = languageIds.singleOrNull(),
                keywords = biasWords(VoiceBias.RECOGNIZER_LIMIT),
            ).trim()
        }
        is Local.Et -> {
            val text = withContext(liveDispatcher(voice.model.engine)) {
                val live = liveStream
                if (live != null) {
                    liveStream = null
                    try {
                        if (pcm.size > liveFed) live.feed(pcm.copyOfRange(liveFed, pcm.size))
                        live.finish()
                    } finally {
                        (live as? AutoCloseable)?.let { runCatching { it.close() } }
                    }
                } else {
                    transcribeWithEt(voice.model, pcm)
                }
            }
            punctuated(voice.model, text.trim())
        }
    }

    private fun transcribeWithWhisper(model: WhisperModel, pcm: FloatArray): String {
        val translate = settings.whisper.translate && model.supportsTranslate
        if (model.ggml) {
            val codes = WhisperCppPlan.languages(model, languageIds)
            val result = WhisperCpp.transcribe(
                WhisperStore.modelFile(filesDir, model),
                pcm,
                codes,
                translate,
                WhisperCppPlan.prompt(biasWords(VoiceBias.RECOGNIZER_LIMIT)),
                WhisperCppPlan.beamSize(model),
                WhisperCppPlan.wantsGpu(model, settings.whisper.compute),
            ) ?: return ""
            val detected = model.multilingual && codes.isEmpty()
            return if (detected) WhisperScript.rescueAmong(result.text, languageIds) else result.text
        }
        // As in the keyboard: a graph can be told one language, never several.
        val langToken = languageIds.singleOrNull()?.let(model::langTokenFor)
        val text = WhisperEngine.transcribe(
            WhisperStore.modelFile(filesDir, model),
            WhisperStore.vocabFile(filesDir, model),
            pcm,
            translate,
            langToken,
        ).trim()
        return if (langToken == null && model.fixedLang == null) WhisperScript.rescueAmong(text, languageIds) else text
    }

    /** [pcm] through a file-by-file [model] in one go. On [liveDispatcher] only. */
    private fun transcribeWithEt(model: EtModel, pcm: FloatArray): String = when (model.engine) {
        EtEngine.ASR4ALL, EtEngine.SHERPA, EtEngine.OMNILINGUAL, EtEngine.VOSK -> newLiveStream(model, liveWords(model)).let { stream ->
            try {
                stream.feed(pcm)
                stream.finish()
            } finally {
                (stream as? AutoCloseable)?.let { runCatching { it.close() } }
            }
        }
        EtEngine.PARAKEET -> ParakeetEngine.transcribe(filesDir, model, pcm)
        EtEngine.MOONSHINE -> MoonshineEngine.transcribe(filesDir, model, pcm)
    }

    /**
     * Marks and capitals for a [model] that writes none, as the keyboard adds
     * them: spoken punctuation first, then the punctuation model, when **Add
     * punctuation and capitals** is on and the typing mode is not plain.
     */
    private suspend fun punctuated(model: EtModel, text: String): String {
        if (text.isBlank() || plain || !DictationPunctuation.needed(model) || !settings.whisper.punctuate) return text
        val spoken = if (settings.voiceSpokenPunctuation) VoicePunctuation.apply(text, tag) else text
        val choice = settings.whisper.punctuationModel
        return withContext(SherpaNative.dispatcher) {
            runCatching { DictationPunctuation.punctuate(filesDir, spoken, languageIds, choice) }.getOrDefault(spoken)
        }
    }

    private suspend fun transcribeOnServer(pcm: FloatArray): String = withContext(Dispatchers.IO) {
        val whisper = settings.whisper
        val languageId = languageIds.first()
        // The server detects the language itself when told nothing, and one
        // request can name one language only.
        val language = if (whisper.serverSendLanguage && languageIds.size == 1) {
            WhisperLanguages.codeForLanguage(languageId)
        } else {
            null
        }
        val target = whisper.serverFor(languageId)
        TranscriptionClient.transcribe(
            target.url,
            target.key,
            target.model,
            language,
            pcm,
            prompt = VoiceBias.serverPrompt(biasWords(VoiceBias.PROMPT_WORD_LIMIT), whisper.serverPrompt),
            path = target.path,
        )
    }

    // ---- passing it on ----

    private fun startForward(target: ComponentName?) {
        val engine = VoiceInputEngine(app).apply {
            service = target
            avoidDefault = true
        }
        forward = engine
        engine.start(
            tag,
            allowOnDevice = settings.whisper.systemOnDevice,
            formatting = !plain,
            bias = VoiceBiasRequest(biasWords(VoiceBias.RECOGNIZER_LIMIT), deviceContext = settings.whisper.biasPersonalWords),
            alsoListenFor = languageIds.drop(1).map(::tagOf),
            listener = object : VoiceInputEngine.Listener {
                override fun onListening() {
                    if (forward === engine && !done) listener.onReady()
                }

                override fun onLevel(level: Float) {
                    if (forward === engine && !done) listener.onLevel(level)
                }

                override fun onPartial(text: String) {
                    if (forward !== engine || done) return
                    if (!spoke) {
                        spoke = true
                        listener.onSpeechStart()
                    }
                    listener.onPartial(text)
                }

                override fun onEndOfSpeech() {
                    if (forward === engine && !done) listener.onEndOfSpeech()
                }

                override fun onFinal(text: String) {
                    if (forward !== engine) return
                    forward = null
                    if (text.isBlank()) fail(Error.NO_SPEECH) else succeed(text.trim())
                }

                override fun onError(kind: VoiceInputEngine.ErrorKind) {
                    if (forward !== engine) return
                    forward = null
                    fail(
                        when (kind) {
                            VoiceInputEngine.ErrorKind.NO_SPEECH -> Error.NO_SPEECH
                            VoiceInputEngine.ErrorKind.NETWORK -> Error.NETWORK
                            VoiceInputEngine.ErrorKind.BUSY -> Error.BUSY
                            VoiceInputEngine.ErrorKind.PERMISSION -> Error.PERMISSION
                            VoiceInputEngine.ErrorKind.LANGUAGE -> Error.NO_ENGINE
                            VoiceInputEngine.ErrorKind.OTHER -> Error.TRANSCRIBE
                        },
                    )
                }
            },
        )
    }

    // ---- endings ----

    private fun succeed(text: String) {
        if (done) return
        done = true
        // Said punctuation ("comma") as the keyboard types it. An engine that
        // went through [punctuated] has had it already; a second pass finds
        // nothing left to turn into a mark.
        val out = if (settings.voiceSpokenPunctuation && !plain) VoicePunctuation.apply(text, tag) else text
        listener.onResult(out)
        scope.cancel()
    }

    private fun fail(error: Error) {
        if (done) return
        done = true
        main.removeCallbacks(noSpeechCheck)
        listener.onError(error)
        scope.cancel()
    }

    private fun failRecording(rec: WhisperRecorder, error: Error) {
        stopRecording(rec)
        fail(error)
    }

    private fun stopRecording(rec: WhisperRecorder) {
        if (recorder === rec) recorder = null
        CoroutineScope(Dispatchers.IO).launch { runCatching { rec.stop() } }
    }

    // ---- helpers ----

    /** The words the user asked dictation to lean towards; their learned words stay with the keyboard. */
    private fun biasWords(limit: Int): List<String> =
        VoiceBias.select(VoiceBias.parseList(settings.whisper.biasWords), emptyList(), limit)

    private fun liveWords(model: EtModel): List<String> =
        if (model.engine == EtEngine.SHERPA) biasWords(VoiceBias.RECOGNIZER_LIMIT) else emptyList()

    private fun liveEngine(engine: EtEngine): Boolean = engine.live

    private fun liveDispatcher(engine: EtEngine): CoroutineDispatcher = engine.dispatcher

    companion object {
        private const val LIVE_POLL_MS = 120L

        /** BCP-47 tag of [languageId], as a recognizer takes it. */
        fun tagOf(languageId: String): String = LanguageRegistry.byId(languageId).localeTag

        /**
         * The languages a request listens for: [requestedTag] when the calling
         * app named one WM Keyboard knows, else the Voice typing language,
         * following the keyboard's first language when that is unset.
         */
        fun languagesFor(settings: KeyboardSettings, requestedTag: String?): List<String> =
            AppDictationPlan.languages(
                requestedTag = requestedTag,
                languageForTag = { LanguageRegistry.byLocale(it)?.id },
                picked = settings.whisper.languages,
                keyboard = settings.enabledLanguages.firstOrNull()?.id ?: "en",
                heard = RomanizedSpeech::heard,
            )
    }
}

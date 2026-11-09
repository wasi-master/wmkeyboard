package com.wasimaster.wmkeyboard.core.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.speech.RecognitionService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Thin wrapper around the platform [SpeechRecognizer] for the voice input
 * tool. One instance lives in the service; each dictation session creates
 * a fresh recognizer (reusing one across sessions strands BUSY state on
 * several OEM implementations) and destroys it when the session ends.
 *
 * A session is asked of the recognizers in [VoiceRoutes.plan] one after the
 * other: one that cannot start hands the phrase to the next, and the listener
 * hears of an error only when the last of them has failed.
 *
 * Every method must be called on the main thread — SpeechRecognizer's own
 * requirement.
 */
class VoiceInputEngine(private val context: Context) {

    /** Why a session ended without a final result, pre-digested for the UI. */
    enum class ErrorKind { NO_SPEECH, NETWORK, BUSY, PERMISSION, LANGUAGE, OTHER }

    interface Listener {
        /** The mic is open and capturing. */
        fun onListening()

        /** Sound level changed; [level] is folded into 0..1. */
        fun onLevel(level: Float)

        fun onPartial(text: String)

        /**
         * The recognizer stopped listening by itself — a pause, or its own
         * limit — and is working out the result. Anything said from here on
         * is not heard (#315).
         */
        fun onEndOfSpeech() {}

        /** Terminal: the recognizer is already released when this fires. */
        fun onFinal(text: String)

        /** Terminal: the recognizer is already released when this fires. */
        fun onError(kind: ErrorKind)
    }

    private var recognizer: SpeechRecognizer? = null

    /**
     * The recognizers that are no use for a language, for the rest of the
     * process. Either one said it does not know the language (typically the
     * on-device one for bn-BD, which has no local model), or the on-device one
     * opened the microphone and then heard nothing at all: no level, no
     * partial. Android System Intelligence's SODA pipeline can stall that way
     * (seen on Android 15, en-US: "Pipeline lagging", then NO_SPEECH for every
     * phrase), and from outside it looks exactly like a dead microphone. Such
     * a pair is passed over from then on, so that a continuous dictation does
     * not pay for the same failure before every phrase.
     */
    private val deaf = mutableSetOf<Pair<VoiceRoute, String>>()

    /**
     * The recognizer's own number for the error the listener was last told of
     * (`SpeechRecognizer.ERROR_*`), and 0 when there was none. [ErrorKind.OTHER]
     * covers a dozen different failures; the number is what tells them apart
     * in a report.
     */
    var lastErrorCode: Int = 0
        private set

    private val main = Handler(Looper.getMainLooper())

    /** The pending stall check of the session running now, if it is on-device. */
    private var stallCheck: Runnable? = null

    /** [finish] was called on the session running now: it ends, it is not restarted. */
    private var finishing = false

    /**
     * The pending wait for the result of the session running now, once it has
     * stopped listening (#664). See [RESULT_MS].
     */
    private var resultCheck: Runnable? = null

    /** Starts, or restarts, [resultCheck] for the session running now. */
    private var awaitResult: (() -> Unit)? = null

    /**
     * A recognition service the user picked by name (#993): another app's,
     * such as Marmalade, Transcribro or FUTO Voice Input, in place of the one
     * the phone has set as its default. Null is the default. A pick whose app
     * has since gone is passed over for the default rather than failing.
     */
    var service: ComponentName? = null

    /**
     * Never bind the phone's default recognizer (#1142). Set by WM Keyboard's
     * own recognition service, which may itself be that default: asking it
     * would only come back here. A session that would have gone to the
     * default goes to another app's recognizer instead, or ends with
     * [ErrorKind.LANGUAGE] when there is none.
     */
    var avoidDefault: Boolean = false

    /** What [VoiceRoutes.plan] needs to know about this phone, read now. */
    private fun phone(allowOnDevice: Boolean): VoiceRoutes.Phone {
        val others = recognitionServices(context).map { it.first }
        val default = defaultRecognitionService(context)
        return VoiceRoutes.Phone(
            // A pick whose app has since gone is passed over.
            picked = service?.takeIf { it in others }?.flattenToString(),
            onDevice = allowOnDevice &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
            default = default?.flattenToString(),
            defaultIsOurs = default?.packageName == context.packageName,
            defaultPreinstalled = default != null && preinstalled(default.packageName),
            others = others.map { it.flattenToString() },
            avoidDefault = avoidDefault,
        )
    }

    /** Whether [packageName] came with the phone, updated since or not. */
    private fun preinstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    /** False on devices without any recognizer to ask (no GMS). */
    fun isAvailable(): Boolean = VoiceRoutes.plan(phone(allowOnDevice = true)).isNotEmpty()

    /**
     * Starts one utterance session; results stream to [listener]. Prefers
     * the on-device recognizer (API 31+): faster, offline, and it skips the
     * per-utterance beep that makes continuous dictation grating. A
     * recognizer that cannot take the phrase (it is not really installed, no
     * default is set, it does not know the language, it refuses the keyboard)
     * hands it to the next one in [VoiceRoutes.plan], and one that fails on a
     * language is remembered for it.
     *
     * [formatting] asks the recognizer for punctuation and capital letters
     * (API 33+). Plain voice typing turns it off, because there the words are
     * wanted exactly as they were said.
     *
     * [alsoListenFor] are more language tags the speaker may switch to (#416).
     * Android 14 lets a recognizer detect the language among a set and follow
     * a switch mid-phrase; [languageTag] stays the one it starts in. Earlier
     * versions, and recognizers that ignore the extras, hear [languageTag]
     * alone.
     */
    fun start(
        languageTag: String,
        listener: Listener,
        allowOnDevice: Boolean = true,
        formatting: Boolean = true,
        bias: VoiceBiasRequest = VoiceBiasRequest.NONE,
        alsoListenFor: List<String> = emptyList(),
    ) {
        cancel()
        lastErrorCode = 0
        val all = VoiceRoutes.plan(phone(allowOnDevice))
        if (all.isEmpty()) {
            // Nothing to ask but the default, which is not to be asked.
            main.post { listener.onError(ErrorKind.LANGUAGE) }
            return
        }
        // Every one of them deaf to this language leaves nothing, and then
        // they are all asked again: the user may have put that right since.
        val routes = all.filterNot { (it to languageTag) in deaf }.ifEmpty { all }
        listen(Request(languageTag, listener, formatting, bias, alsoListenFor), routes, 0, emptyList())
    }

    /** What [start] was asked for, carried from one recognizer to the next. */
    private class Request(
        val languageTag: String,
        val listener: Listener,
        val formatting: Boolean,
        val bias: VoiceBiasRequest,
        val alsoListenFor: List<String>,
    )

    private fun open(route: VoiceRoute): SpeechRecognizer = when (route) {
        VoiceRoute.OnDevice ->
            // Only ever planned on API 31 and later.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        VoiceRoute.Default -> SpeechRecognizer.createSpeechRecognizer(context)
        is VoiceRoute.Service ->
            SpeechRecognizer.createSpeechRecognizer(context, ComponentName.unflattenFromString(route.component))
    }

    /** Tells the listener of the error [code], after everything is released. */
    private fun report(listener: Listener, code: Int) {
        lastErrorCode = code
        listener.onError(errorKind(code))
    }

    /**
     * Asks [routes]`[index]` for the phrase. [failed] are the recognizers
     * before it that could not start, kept so that the error told at the end
     * is the most useful one and not merely the last.
     */
    private fun listen(ask: Request, routes: List<VoiceRoute>, index: Int, failed: List<VoiceRoutes.Failure>) {
        val route = routes[index]
        val hasNext = index < routes.lastIndex
        val onDevice = route == VoiceRoute.OnDevice
        val listener = ask.listener
        val session = open(route)
        recognizer = session
        finishing = false
        // A healthy recognizer reports a level many times a second, silence
        // included, so an on-device session with neither a level nor a
        // partial after this long has stalled and will only end in NO_SPEECH.
        var heard = false
        // The newest partial, which stands in for the result of a recognizer
        // that never sends one.
        var lastPartial = ""
        // Every recognizer is trusted to end a session in onResults or
        // onError. Transcribro (a Whisper recognition service people install
        // where there is no Google) ends one on its own pause detection with
        // onEndOfSpeech and then, at times, nothing more, which left the
        // panel on its spinner for good (#664). A session that has stopped
        // listening gets RESULT_MS to answer; after that it is cancelled and
        // what was heard lands as the result, so continuous dictation goes on.
        // Transcribro sends the phrase as a partial a moment after the pause
        // and the result up to a dozen seconds later, or never, so a partial
        // once listening has stopped shortens the wait to RESULT_AFTER_PARTIAL_MS.
        val waitForResult: (Long) -> Unit = { delay ->
            resultCheck?.let(main::removeCallbacks)
            val check = Runnable {
                if (recognizer === session) {
                    cancel()
                    if (lastPartial.isBlank()) report(listener, VoiceRoutes.ERROR_NO_MATCH) else listener.onFinal(lastPartial)
                }
            }
            resultCheck = check
            main.postDelayed(check, delay)
        }
        awaitResult = { waitForResult(RESULT_MS) }

        /** This recognizer is done with: the next takes the phrase. Only with [hasNext]. */
        fun moveOn(code: Int) {
            release(session)
            listen(ask, routes, index + 1, failed + VoiceRoutes.Failure(route, code))
        }
        if (onDevice && hasNext) {
            val check = Runnable {
                if (recognizer === session && !heard) {
                    deaf += route to ask.languageTag
                    moveOn(VoiceRoutes.ERROR_NO_MATCH)
                }
            }
            stallCheck = check
            main.postDelayed(check, STALL_MS)
        }
        session.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening()

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                heard = true
                // Typical rmsdB swings about -2..10 dB; fold into 0..1.
                listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (recognizer === session && resultCheck == null) waitForResult(RESULT_MS)
                listener.onEndOfSpeech()
            }

            override fun onError(error: Int) {
                // Android reports some failures twice (no default set is one);
                // the first report has already moved the phrase on.
                if (recognizer !== session) return
                val kind = errorKind(error)
                // Ended before the stall check ran: the same stall.
                val stall = onDevice && !heard && kind == ErrorKind.NO_SPEECH
                if (stall || kind == ErrorKind.LANGUAGE) deaf += route to ask.languageTag
                // Before a single sound came through, most errors mean the
                // recognizer never got going: it is not installed, no default
                // is set, it is busy, it refuses the keyboard.
                val neverStarted = !heard && VoiceRoutes.cannotStart(error)
                // Stopped by the user, a failure is only remembered for the
                // next phrase: nobody is waiting to be heard any more.
                if (!finishing && hasNext && (stall || neverStarted || (onDevice && kind == ErrorKind.LANGUAGE))) {
                    moveOn(error)
                    return
                }
                release(session)
                // The last of several that could not start: say the most
                // useful of their reasons.
                val told = if (neverStarted) VoiceRoutes.toTell(failed + VoiceRoutes.Failure(route, error)).code else error
                report(listener, told)
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== session) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank() && onDevice && !heard && !finishing && hasNext) {
                    // An empty final from a recognizer that never heard a thing.
                    deaf += route to ask.languageTag
                    moveOn(VoiceRoutes.ERROR_NO_MATCH)
                    return
                }
                release(session)
                if (text.isBlank()) report(listener, VoiceRoutes.ERROR_NO_MATCH) else listener.onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    heard = true
                    lastPartial = text
                    // Words after the end of speech are the phrase the
                    // recognizer settled on; its result should follow at once.
                    if (recognizer === session && resultCheck != null) waitForResult(RESULT_AFTER_PARTIAL_MS)
                    listener.onPartial(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        session.startListening(recognizerIntent(ask.languageTag, ask.formatting, ask.bias, ask.alsoListenFor))
    }

    private fun recognizerIntent(
        languageTag: String,
        formatting: Boolean = true,
        bias: VoiceBiasRequest = VoiceBiasRequest.NONE,
        alsoListenFor: List<String> = emptyList(),
    ): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && formatting) {
                // Recognizer-side punctuation/casing where supported.
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                )
            }
            // Words to lean towards (#305). Android 13 added both extras, and
            // "depending on the recognizer implementation" either may be
            // ignored; one that ignores them loses nothing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (bias.words.isNotEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias.words))
                }
                if (bias.deviceContext) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true)
                }
            }
            // More than one language at once (#416). Detection picks the
            // language of the phrase among these; switching follows a change
            // of language part way through one. Both are requests the
            // recognizer may decline, and one that does hears EXTRA_LANGUAGE.
            val others = alsoListenFor.filter { !it.equals(languageTag, ignoreCase = true) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && others.isNotEmpty()) {
                val allowed = arrayListOf(languageTag).apply { addAll(others) }
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
            }
        }

    // ---- on-device model management (API 33+) ----

    enum class ModelCheckResult { UNSUPPORTED, INSTALLED, DOWNLOADABLE, PENDING }

    interface ModelDownloadCallback {
        fun onProgress(percent: Int)

        /** The model is installed; the next session will use it. */
        fun onSuccess()

        /**
         * Queued by the system (waiting for Wi-Fi / idle), or requested on
         * API 33 where no completion callback exists. No further callbacks;
         * a later [checkOnDeviceModel] reports the outcome.
         */
        fun onScheduled()

        fun onError()
    }

    /**
     * Asks the on-device recognizer whether [languageTag]'s model is
     * installed, downloadable, or mid-download. UNSUPPORTED on Android 12L
     * and below (the query API is 33+), without an on-device recognizer,
     * or for languages it can't serve at all.
     */
    fun checkOnDeviceModel(languageTag: String, onResult: (ModelCheckResult) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            onResult(ModelCheckResult.UNSUPPORTED)
            return
        }
        val checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        checker.checkRecognitionSupport(
            recognizerIntent(languageTag),
            ContextCompat.getMainExecutor(context),
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    checker.destroy()
                    onResult(
                        when {
                            recognitionSupport.installedOnDeviceLanguages
                                .containsTag(languageTag) -> ModelCheckResult.INSTALLED
                            recognitionSupport.pendingOnDeviceLanguages
                                .containsTag(languageTag) -> ModelCheckResult.PENDING
                            recognitionSupport.supportedOnDeviceLanguages
                                .containsTag(languageTag) -> ModelCheckResult.DOWNLOADABLE
                            else -> ModelCheckResult.UNSUPPORTED
                        },
                    )
                }

                override fun onError(error: Int) {
                    checker.destroy()
                    onResult(ModelCheckResult.UNSUPPORTED)
                }
            },
        )
    }

    /** Downloads [languageTag]'s on-device model via the system recognizer. */
    fun downloadModel(languageTag: String, callback: ModelDownloadCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            callback.onError()
            return
        }
        val downloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = recognizerIntent(languageTag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            downloader.triggerModelDownload(
                intent,
                ContextCompat.getMainExecutor(context),
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) =
                        callback.onProgress(completedPercent)

                    override fun onSuccess() {
                        downloader.destroy()
                        // The earlier network fallback for this language is
                        // stale now — the next session goes on-device.
                        deaf -= VoiceRoute.OnDevice to languageTag
                        callback.onSuccess()
                    }

                    override fun onScheduled() {
                        downloader.destroy()
                        callback.onScheduled()
                    }

                    override fun onError(error: Int) {
                        downloader.destroy()
                        callback.onError()
                    }
                },
            )
        } else {
            // API 33: fire-and-forget — the system downloads in the
            // background and support re-checks pick up the result.
            downloader.triggerModelDownload(intent)
            downloader.destroy()
            deaf -= VoiceRoute.OnDevice to languageTag
            callback.onScheduled()
        }
    }

    /** Locale-tag membership, tolerating region differences (bn ~ bn-BD). */
    private fun List<String>.containsTag(tag: String): Boolean {
        val language = tag.substringBefore('-')
        return any {
            it.equals(tag, ignoreCase = true) ||
                it.substringBefore('-').equals(language, ignoreCase = true)
        }
    }

    /** Stop capturing and let the recognizer deliver its final result. */
    fun finish() {
        finishing = true
        stallCheck?.let(main::removeCallbacks)
        stallCheck = null
        if (recognizer != null) awaitResult?.invoke()
        recognizer?.stopListening()
    }

    /** Abandon the session: no more callbacks, mic released. */
    fun cancel() {
        recognizer?.let {
            it.cancel()
            release(it)
        }
    }

    private fun release(session: SpeechRecognizer) {
        session.destroy()
        if (recognizer === session) {
            recognizer = null
            stallCheck?.let(main::removeCallbacks)
            stallCheck = null
            resultCheck?.let(main::removeCallbacks)
            resultCheck = null
            awaitResult = null
        }
    }

    private companion object {
        /** How long an on-device session may stay deaf before it counts as stalled. */
        const val STALL_MS = 1800L

        /**
         * How long a session that has stopped listening may take to send its
         * result. A network recognizer answers in a second or two; a Whisper
         * one on the phone transcribes only now, so this leaves it room.
         */
        const val RESULT_MS = 12_000L

        /**
         * The same wait once a partial has come in after listening stopped:
         * the words are known, only the recognizer's result is missing.
         */
        const val RESULT_AFTER_PARTIAL_MS = 3_000L
    }

    private fun errorKind(code: Int): ErrorKind = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ErrorKind.NO_SPEECH
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        -> ErrorKind.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_AUDIO,
        -> ErrorKind.BUSY
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorKind.PERMISSION
        // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE, API 33
        // constants inlined so the mapping compiles against older SDKs too.
        12, 13 -> ErrorKind.LANGUAGE
        else -> ErrorKind.OTHER
    }
}

/**
 * What the system recognizer is told to listen for (#305): [words], and
 * whether it may also lean on what it knows about the device itself (its own
 * idea of contacts and apps, which never passes through the keyboard).
 */
data class VoiceBiasRequest(val words: List<String>, val deviceContext: Boolean) {
    companion object {
        val NONE = VoiceBiasRequest(emptyList(), deviceContext = false)
    }
}

/**
 * Every other app's recognition service installed on the phone (#993), with
 * its label, the default among them. What the system recognizer's settings
 * offer to pick from; the manifest's `<queries>` names the intent so Android
 * 11 and later show them. WM Keyboard's own (#1142) is left out: the keyboard
 * sending its dictation to itself would go round in a circle.
 */
fun recognitionServices(context: Context): List<Pair<ComponentName, String>> {
    val pm = context.packageManager
    return runCatching {
        pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).mapNotNull { info ->
            val si = info.serviceInfo ?: return@mapNotNull null
            if (si.packageName == context.packageName) return@mapNotNull null
            ComponentName(si.packageName, si.name) to info.loadLabel(pm).toString()
        }
    }.getOrDefault(emptyList()).distinctBy { it.first }
}

/**
 * The recognition service Android has set as the default, when it lets the
 * setting be read; null when it does not or none is set.
 */
fun defaultRecognitionService(context: Context): ComponentName? = runCatching {
    android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        ?.takeIf { it.isNotBlank() }
        ?.let(ComponentName::unflattenFromString)
}.getOrNull()

/**
 * Another app's recognizer to stand in for the default (#1142): the default
 * itself when it is another app's, else the first installed one, Google's
 * ahead of the rest since it is the one most phones ship as the default.
 */
fun otherRecognitionService(context: Context): ComponentName? {
    val others = recognitionServices(context).map { it.first }
    defaultRecognitionService(context)?.takeIf { it in others }?.let { return it }
    return others.firstOrNull { it.packageName == GOOGLE_RECOGNIZER_PACKAGE } ?: others.firstOrNull()
}

private const val GOOGLE_RECOGNIZER_PACKAGE = "com.google.android.googlequicksearchbox"

package com.wasimaster.wmkeyboard.ime.sysvoice

/**
 * The decisions behind a dictation another app asked for (#1142), kept apart
 * from Android so they can be tested: which way the audio goes, which
 * languages are listened for, and when a phrase ends.
 */
internal object AppDictationPlan {

    /** Where the audio of one request goes. */
    enum class Route {
        /** A model on the phone: Whisper, Whistle, Sherpa and the rest. */
        ON_DEVICE,

        /** The transcription server the user set up. */
        SERVER,

        /** Another app's recognizer, for the "phone's recognizer" engine. */
        FORWARD,

        /** Nothing can hear this request. */
        NONE,
    }

    /**
     * The route for [engine] (a `voice_engine` value: `system`, `server`, or
     * an on-device engine's id), given what is ready: the engine's own model
     * ([onDeviceReady]), any other model on the phone ([fallbackReady]), a
     * server address ([serverReady]) and another app's recognizer
     * ([forwardReady]).
     *
     * Audio only ever goes where the user sent it, or stays on the phone. An
     * on-device pick without its model takes another model on the phone, and
     * never a server or another app's recognizer. A server with no address,
     * and the phone's recognizer where there is none to ask (a phone without
     * Google, where WM Keyboard is the only recognizer), take a model on the
     * phone when there is one.
     */
    fun route(
        engine: String,
        onDeviceReady: Boolean,
        fallbackReady: Boolean,
        serverReady: Boolean,
        forwardReady: Boolean,
    ): Route = when (engine) {
        ENGINE_SERVER -> when {
            serverReady -> Route.SERVER
            fallbackReady -> Route.ON_DEVICE
            else -> Route.NONE
        }
        ENGINE_SYSTEM -> when {
            forwardReady -> Route.FORWARD
            fallbackReady -> Route.ON_DEVICE
            else -> Route.NONE
        }
        else -> if (onDeviceReady || fallbackReady) Route.ON_DEVICE else Route.NONE
    }

    /**
     * The languages to listen for, the first the one the request starts in.
     * A language the calling app asked for ([requestedTag], the
     * `EXTRA_LANGUAGE` of its intent) wins when WM Keyboard knows it
     * ([languageForTag]): a search box set to French gets French. Otherwise
     * the keyboard's own choice ([picked], the Voice typing language, empty to
     * follow the keyboard), and the keyboard's first language ([keyboard])
     * when it follows the keyboard. [heard] turns a romanized language into
     * the one it is spoken as (Hinglish is heard as Hindi).
     */
    fun languages(
        requestedTag: String?,
        languageForTag: (String) -> String?,
        picked: List<String>,
        keyboard: String,
        heard: (List<String>) -> List<String>,
    ): List<String> {
        val requested = requestedTag?.takeIf { it.isNotBlank() }?.let(languageForTag)
        val ids = when {
            requested != null -> listOf(requested)
            picked.isEmpty() -> listOf(keyboard)
            keyboard in picked -> listOf(keyboard) + (picked - keyboard)
            else -> picked
        }
        return heard(ids).ifEmpty { listOf(keyboard) }
    }

    /**
     * How long a pause ends the phrase, in milliseconds. A request names its
     * own ([requestedMs], `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`);
     * otherwise the keyboard's **Stop after a pause** ([settingMs]). That
     * setting is off by default, because in the keyboard the user stops a
     * dictation with the mic; another app has no such button, so off is read
     * as [DEFAULT_PAUSE_MS] here.
     */
    fun pauseMs(settingMs: Int, requestedMs: Long?): Int {
        requestedMs?.takeIf { it > 0 }?.let { return it.coerceIn(MIN_PAUSE_MS.toLong(), MAX_PAUSE_MS.toLong()).toInt() }
        return if (settingMs > 0) settingMs else DEFAULT_PAUSE_MS
    }

    /**
     * How long to wait for the first word before the request ends with
     * nothing heard: long enough to start talking, short enough that a mic
     * opened by mistake closes by itself.
     */
    const val NO_SPEECH_MS = 6_000L

    const val DEFAULT_PAUSE_MS = 1_500
    const val MIN_PAUSE_MS = 500
    const val MAX_PAUSE_MS = 10_000

    const val ENGINE_SYSTEM = "system"
    const val ENGINE_SERVER = "server"
}

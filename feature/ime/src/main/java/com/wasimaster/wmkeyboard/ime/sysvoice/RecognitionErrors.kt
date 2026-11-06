package com.wasimaster.wmkeyboard.ime.sysvoice

/**
 * How an [AppDictation] ending reads in Android's speech recognizer protocol
 * (#1142). The codes are `SpeechRecognizer.ERROR_*`, inlined so this stays
 * plain Kotlin that a test can run.
 */
internal object RecognitionErrors {

    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9

    /** Android 12's "no model for this language", which callers read as a reason to try another way. */
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /**
     * The code for [error]. Nothing heard reads as a timeout when no speech
     * was heard at all ([spoke] false), and as "no match" when there was
     * speech but no words came of it, which is how Google's recognizer tells
     * the two apart. No engine for the request is "language unavailable"
     * from Android 12 ([sdk] 31), where the code exists, and a client error
     * before it.
     */
    fun code(error: AppDictation.Error, spoke: Boolean, sdk: Int): Int = when (error) {
        AppDictation.Error.NO_SPEECH -> if (spoke) ERROR_NO_MATCH else ERROR_SPEECH_TIMEOUT
        AppDictation.Error.NO_ENGINE -> if (sdk >= LANGUAGE_UNAVAILABLE_SINCE) ERROR_LANGUAGE_UNAVAILABLE else ERROR_CLIENT
        AppDictation.Error.PERMISSION -> ERROR_INSUFFICIENT_PERMISSIONS
        AppDictation.Error.MIC -> ERROR_AUDIO
        AppDictation.Error.NETWORK -> ERROR_NETWORK
        AppDictation.Error.BUSY -> ERROR_RECOGNIZER_BUSY
        AppDictation.Error.TRANSCRIBE -> ERROR_CLIENT
    }

    /**
     * A microphone level, 0..1, as the decibels `rmsChanged` takes: the
     * inverse of how the keyboard folds a recognizer's -2..10 dB into 0..1,
     * so an app drawing the level sees the range it expects.
     */
    fun rmsDb(level: Float): Float = level.coerceIn(0f, 1f) * RMS_SPAN_DB + RMS_FLOOR_DB

    private const val RMS_FLOOR_DB = -2f
    private const val RMS_SPAN_DB = 12f
    private const val LANGUAGE_UNAVAILABLE_SINCE = 31
}

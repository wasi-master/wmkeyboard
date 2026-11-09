package com.wasimaster.wmkeyboard.core.voice

/** One recognizer a dictation on the phone's recognizer may be asked of. */
sealed interface VoiceRoute {
    /** The recognizer the phone's maker named for recognition on the phone. */
    data object OnDevice : VoiceRoute

    /** The recognition service Android has set as the default. */
    data object Default : VoiceRoute

    /** A recognition service asked for by name: [component] is its flattened component name. */
    data class Service(val component: String) : VoiceRoute
}

/**
 * Which recognizers a dictation tries, in what order, and what it says when
 * none of them starts.
 *
 * One recognizer was asked and its answer believed. That holds on a phone
 * whose recognizers are all in order, and fails on the many that are not:
 *
 * - Android says an on-device recognizer "is available" when the maker's
 *   configuration names one, installed or not. Asking a name with nothing
 *   behind it ends in `ERROR_TOO_MANY_REQUESTS` or `ERROR_CLIENT` before the
 *   microphone opens.
 * - Android says recognition "is available" when any app offers a recognition
 *   service. With no default set (Xiaomi, Honor and others ship that way),
 *   asking the default ends in `ERROR_CLIENT` although Google's recognizer is
 *   installed and would answer when named.
 * - A maker's own assistant set as the default may refuse another app, or not
 *   know the language.
 *
 * So the recognizers are a list, and one that cannot start hands the phrase to
 * the next. Kept free of Android types so the order can be tested.
 */
object VoiceRoutes {

    /** What the phone offers at the moment a dictation starts. Components are flattened names. */
    data class Phone(
        /** The recognition service the user picked, when its app still offers it. */
        val picked: String? = null,
        /** An on-device recognizer is named, and the user has not turned it off. */
        val onDevice: Boolean = false,
        /** Android's default recognition service; null when none is set or it cannot be read. */
        val default: String? = null,
        /** The default is WM Keyboard's own service, which must not be asked (#1142). */
        val defaultIsOurs: Boolean = false,
        /** The default's app came with the phone, rather than being installed by the user. */
        val defaultPreinstalled: Boolean = false,
        /** Every other app's recognition service, WM Keyboard's own left out. */
        val others: List<String> = emptyList(),
        /** The default is not to be asked whatever it is (#1142). */
        val avoidDefault: Boolean = false,
    )

    /** A recognizer that did not start, and the error it gave. */
    data class Failure(val route: VoiceRoute, val code: Int)

    /**
     * The recognizers to try for [phone], first to last.
     *
     * A recognition service the user picked is the only one asked: the audio
     * goes where they said and nowhere else. Otherwise the on-device
     * recognizer comes first, then the default, then every other app's.
     *
     * The others stand in only for a default that is not set, is gone, or came
     * with the phone. A recognizer the user installed and made the default was
     * chosen on purpose, often because it keeps the audio on the phone, and a
     * phrase it turns down is not passed to a different app behind their back.
     */
    fun plan(phone: Phone): List<VoiceRoute> {
        phone.picked?.let { return listOf(VoiceRoute.Service(it)) }
        val routes = ArrayList<VoiceRoute>()
        if (phone.onDevice) routes += VoiceRoute.OnDevice
        // The default, when it is another app's and that app is still here.
        val named = phone.default?.takeIf { it in phone.others }
        when {
            phone.avoidDefault -> named?.let { routes += VoiceRoute.Service(it) }
            // With no recognition service on the phone at all there is no
            // default to ask either, and the panel says so instead.
            !phone.defaultIsOurs && phone.others.isNotEmpty() -> routes += VoiceRoute.Default
        }
        if (named == null || phone.defaultPreinstalled) {
            phone.others.filter { it != named }.sortedBy(::standInRank).forEach { routes += VoiceRoute.Service(it) }
        }
        return routes
    }

    /**
     * Whether [code], from a recognizer that has heard nothing yet, means it
     * never got going, so that the next one should be asked. A lost
     * connection, a microphone another app holds, and silence are not that:
     * the next recognizer would meet the same.
     */
    fun cannotStart(code: Int): Boolean = when (code) {
        ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_AUDIO, ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> false
        else -> true
    }

    /**
     * The failure to tell the user about when every recognizer in [failures]
     * failed to start. The on-device recognizer's is passed over when any
     * other was tried, since most languages have no on-device model and that
     * is no news. Among the rest the most useful wins: a server that could not
     * be reached, then a recognizer without the microphone permission, a busy
     * one, a language nobody knows, and last an error with only a number.
     */
    fun toTell(failures: List<Failure>): Failure {
        val told = failures.filter { it.route != VoiceRoute.OnDevice }.ifEmpty { failures }
        return told.maxBy { rank(it.code) }
    }

    private fun rank(code: Int): Int = when (code) {
        ERROR_SERVER -> 4
        ERROR_INSUFFICIENT_PERMISSIONS -> 3
        ERROR_RECOGNIZER_BUSY -> 2
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> 1
        else -> 0
    }

    /** Google's recognizers first: they are what most phones were built around. */
    private fun standInRank(component: String): Int =
        GOOGLE_RECOGNIZERS.indexOf(component.substringBefore('/')).let { if (it < 0) GOOGLE_RECOGNIZERS.size else it }

    private val GOOGLE_RECOGNIZERS = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.tts",
        "com.google.android.as",
    )

    // android.speech.SpeechRecognizer's error codes, by value: this file stays
    // free of Android so its tests run on the JVM alone.
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

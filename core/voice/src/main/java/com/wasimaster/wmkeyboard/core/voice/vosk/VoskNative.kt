package com.wasimaster.wmkeyboard.core.voice.vosk

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/**
 * Vosk (#1165), through `native/vosk-jni`: Alpha Cephei's Kaldi recogniser.
 * Two libraries, both behind the `vosk` gate (`:feature:vosk` on Play):
 * `libvosk.so`, Alpha Cephei's own build out of their vosk-android AAR on
 * Maven Central (about 10 MB on arm64), and `libwmvosk.so`, this project's
 * bridge, which finds libvosk's C API by its soname and so must come second.
 * Nothing fetches either until someone picks the Vosk engine.
 *
 * Handles are plain longs, freed by their owners. Nothing here is
 * thread-safe, so every call runs on [dispatcher], one thread.
 */
object VoskNative {

    private val library = GatedLibrary({ FeatureModules.vosk }) {
        runCatching {
            FeatureModules.loadLibrary("vosk")
            FeatureModules.loadLibrary("wmvosk")
            nativeBind()
        }.getOrDefault(false)
    }

    /** Whether both libraries are loaded on this device and build, and the bridge found the C API. */
    val available: Boolean get() = library.available

    /** Whether this build has them, counting a module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /** Where the `vosk` module stands, for the settings screen's banner. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.vosk.state

    /** Whether the module is here; true wherever the APK carries the libraries. */
    val moduleReady: Boolean get() = FeatureModules.vosk.installed

    /** Asks for the module. Idempotent. */
    fun requestModule() = FeatureModules.vosk.requestInstall()

    /** The one thread every handle is used on. */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "vosk").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    /** Finds libvosk's C API; false when the library is missing or lacks a function the bridge calls. */
    @JvmStatic external fun nativeBind(): Boolean

    /** The model unpacked in the folder [path]; 0 when it will not load. */
    @JvmStatic external fun nativeModel(path: String): Long

    @JvmStatic external fun nativeModelFree(model: Long)

    /** A recogniser on [model] for audio at [sampleRate]; 0 on failure. */
    @JvmStatic external fun nativeRecognizer(model: Long, sampleRate: Float): Long

    @JvmStatic external fun nativeRecognizerFree(recognizer: Long)

    /**
     * The first [count] of [samples] (16 kHz mono, in [-1, 1]). 1 when Vosk
     * heard an utterance end, to be read with [nativeResult]; 0 while it goes
     * on; -1 on an error.
     */
    @JvmStatic external fun nativeAccept(recognizer: Long, samples: FloatArray, count: Int): Int

    /** The utterance that just ended: Vosk's JSON, `{"text": ...}`, UTF-8. */
    @JvmStatic external fun nativeResult(recognizer: Long): ByteArray

    /** What the current utterance holds so far: `{"partial": ...}`, UTF-8. */
    @JvmStatic external fun nativePartial(recognizer: Long): ByteArray

    /** The last utterance, flushed: `{"text": ...}`, UTF-8. The recogniser starts afresh. */
    @JvmStatic external fun nativeFinal(recognizer: Long): ByteArray

    @JvmStatic external fun nativeReset(recognizer: Long)
}

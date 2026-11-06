package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-flavor facade over offline Whisper. The interpreter side
 * ([WhisperModule.BRIDGE_CLASS]) is reached by reflection because its home
 * differs per channel — embedded in this module for sideload builds, in the
 * on-demand `:feature:litert` split for Play builds; see [WhisperRuntime].
 *
 * On a Play install where the split is not yet present, [transcribe] throws
 * a [WhisperException] that says so, the same error shape the keyboard
 * already renders for a damaged model, and [warm] asks Play for it, so the
 * download starts while the first phrase is still being said. F-Droid's
 * build has no LiteRT, so [AVAILABLE] is false there and nothing offers it.
 */
object WhisperEngine {

    val AVAILABLE: Boolean = BuildConfig.ENABLE_WHISPER

    @Volatile
    private var runtime: WhisperRuntime? = null

    /** Where the interpreter's module stands. Always installed outside Play. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.litert.state

    /** Whether [transcribe] can run right now, without asking for anything. */
    val ready: Boolean get() = runtime != null || FeatureModules.litert.installed

    /** Asks Play for the module. A no-op where it is compiled in. */
    fun requestModule() = FeatureModules.litert.requestInstall()

    /**
     * The loaded runtime, or the reason there is none yet as a
     * [WhisperException]. No restart is needed after the module arrives:
     * SplitCompat (installed at startup in Play builds) lets this process
     * load the split's classes and native libraries as soon as the install
     * completes.
     */
    private fun runtime(): WhisperRuntime {
        runtime?.let { return it }
        if (!FeatureModules.litert.installed) {
            FeatureModules.litert.requestInstall()
            throw WhisperException(R.string.core_voice_whisper_module_downloading)
        }
        return try {
            // A build with native delivery fetched LiteRT's library rather
            // than shipping it; LiteRT loads it by name, so it goes in first.
            check(NativeDelivery.preload("tensorflowlite_jni")) { "libtensorflowlite_jni.so would not load" }
            FeatureModules.load<WhisperRuntime>(WhisperModule.BRIDGE_CLASS, WhisperEngine::class.java.classLoader!!)
                .also { runtime = it }
        } catch (e: Throwable) {
            // Installed but not loadable — a split installed moments ago on a
            // device where the classloader will only see it after a restart.
            throw WhisperException(R.string.core_voice_whisper_module_restart, cause = e)
        }
    }

    /**
     * Transcribes one PCM utterance (16 kHz mono float in [-1, 1]) to text.
     * [translate] forces the translate-to-English task where the model supports
     * it. [langToken] is the Whisper `<|xx|>` token id to force on a grouped
     * graph — null (or a graph without the input) means auto-detect. Call on a
     * background dispatcher only. Throws [WhisperException] on failure, which
     * carries the string resource the UI should show.
     */
    fun transcribe(
        modelFile: File,
        vocabFile: File,
        pcm: FloatArray,
        translate: Boolean,
        langToken: Int? = null,
    ): String {
        WhisperGraphFix.ensure(modelFile, vocabFile)
        return runtime().transcribe(modelFile, vocabFile, pcm, translate, langToken)
    }

    /**
     * Loads the interpreter and the vocabulary ahead of [transcribe]. Mapping a
     * graph and building its interpreter takes about as long as a short phrase
     * takes to say, so done while the phrase is being recorded it costs the user
     * nothing, and done after it they wait for it. Best effort: whatever goes
     * wrong here goes wrong again in [transcribe], which is where it is reported.
     * Call on a background dispatcher only.
     */
    fun warm(modelFile: File, vocabFile: File) {
        runCatching {
            // Before the interpreter maps the graph, never under it.
            WhisperGraphFix.ensure(modelFile, vocabFile)
            runtime().warm(modelFile, vocabFile)
        }
    }

    /** Frees the cached interpreter. Non-blocking, so IME trim-memory never waits. */
    fun release() {
        runtime?.release()
    }

    /** The model file loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = runtime?.loadedModel()
}

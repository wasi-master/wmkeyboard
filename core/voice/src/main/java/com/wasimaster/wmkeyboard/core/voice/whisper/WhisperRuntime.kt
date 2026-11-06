package com.wasimaster.wmkeyboard.core.voice.whisper

import java.io.File

/**
 * The seam between the app and the LiteRT interpreter that runs Whisper.
 * `WhisperEngine` (the facade every caller uses) reaches its implementation,
 * `LitertWhisperRuntime`, only by reflection — see [WhisperModule.BRIDGE_CLASS]
 * — because the class lives in a different place per channel, exactly as the
 * LiteRT-LM runtime does (see `LlmRuntime` in `:core:intelligence`):
 *
 * - Play builds: in the on-demand `:feature:litert` module, downloaded the
 *   first time offline dictation is used. The interpreter is about 4 MB of
 *   native code per ABI, and most installs keep the system recogniser.
 * - Every other build but F-Droid's: compiled straight into `:core:voice` (the
 *   `src/whisperbridge` source directory), where Play's delivery does not
 *   exist to fetch it later.
 *
 * No type here may come from LiteRT: this interface is what the base APK
 * compiles against in Play builds, where that library is absent.
 */
interface WhisperRuntime {

    /** Contract of [WhisperEngine.transcribe]; same threading rules, same exceptions. */
    fun transcribe(
        modelFile: File,
        vocabFile: File,
        pcm: FloatArray,
        translate: Boolean,
        langToken: Int?,
    ): String

    /** Contract of [WhisperEngine.warm]. */
    fun warm(modelFile: File, vocabFile: File)

    /** Contract of [WhisperEngine.release]. */
    fun release()

    /** The model file loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = null
}

object WhisperModule {

    /** FQN `WhisperEngine` instantiates reflectively; keep rule in :app. */
    const val BRIDGE_CLASS = "com.wasimaster.wmkeyboard.core.voice.whisper.bridge.LitertWhisperRuntime"
}

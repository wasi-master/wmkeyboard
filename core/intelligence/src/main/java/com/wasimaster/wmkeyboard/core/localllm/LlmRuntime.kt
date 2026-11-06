package com.wasimaster.wmkeyboard.core.localllm

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import java.io.File

/**
 * The seam between the app and an on-device runtime: LiteRT-LM, reached as
 * below, or llama.cpp ([LlamaCppRuntime], in this module, for GGUF files).
 *
 * `LocalLlmEngine` (the
 * facade every caller uses) reaches its implementation, `LitertLmRuntime`,
 * only by reflection — see [LlmModule.BRIDGE_CLASS] — because the class lives
 * in a different place per channel:
 *
 * - Play builds: in the on-demand `:feature:llm` module, downloaded the first
 *   time On-device AI is used, keeping ~20 MB per ABI out of every install.
 * - Every other build but F-Droid's: compiled straight into `:core:intelligence`
 *   (the `src/llmbridge` source directory), embedded like it always was.
 *
 * No type here may come from the LiteRT-LM SDK: this interface is what the
 * base APK compiles against in Play builds, where that SDK is absent.
 */
interface LlmRuntime {

    /** Contract of [LocalLlmEngine.generate]; same blocking/threading rules. */
    fun generate(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        onPartial: ((String) -> Unit)?,
    ): String

    /**
     * Contract of [LocalLlmEngine.generateWithImages]: [generate] with
     * pictures attached to the user turn, as encoded image bytes (JPEG/PNG).
     * Plain byte arrays, not SDK types, for the reason at the top of this file.
     */
    fun generateWithImages(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        images: List<ByteArray>,
        onPartial: ((String) -> Unit)?,
    ): String

    /** Contract of [LocalLlmEngine.chatSession]. */
    fun chatSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
    ): Chat

    /** Contract of [LocalLlmEngine.release]. */
    fun release()

    /** The model file loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = null

    /**
     * Contract of [LocalLlmEngine.stop]. A no-op where the runtime cannot cut
     * an answer short: LiteRT-LM's blocking generation always runs to its end.
     */
    fun stop() = Unit

    /** Contract of [LocalLlmEngine.gpuMemoryMayBeStranded]. */
    fun gpuMemoryMayBeStranded(): Boolean

    /** Contract of [LocalLlmEngine.strandedGpuMemoryKb]. */
    fun strandedGpuMemoryKb(): Long

    /** Contract of [LocalLlmEngine.ChatSession], minus the facade wrapper. */
    interface Chat {
        fun sendMessage(user: String, onPartial: ((String) -> Unit)?): String

        /**
         * [sendMessage] with pictures (encoded JPEG/PNG bytes) on this
         * message. A runtime whose chats take none, or a model that cannot
         * see, throws [VisionUnsupportedException]; the caller then sends
         * the words alone.
         */
        fun sendMessage(user: String, images: List<ByteArray>, onPartial: ((String) -> Unit)?): String {
            if (images.isEmpty()) return sendMessage(user, onPartial)
            throw VisionUnsupportedException("This chat takes no pictures")
        }

        fun seed(transcript: List<ChatReplay.Turn>)

        fun recordStoppedTurn(user: String, fullAnswer: String)

        fun close()
    }
}

/**
 * Whether the runtime's home is present on this install, and how to fetch it
 * when it is not. The default says "always present", which is correct for
 * every channel except Play: sideloaded builds embed the bridge, and F-Droid's
 * never reaches this code (the On-device provider is hidden there).
 * Play builds swap in a SplitInstall-backed gate at startup — see
 * `installLlmDelivery` in `:app`.
 */
interface LlmModuleGate {
    fun installed(): Boolean

    /** Idempotent; safe to call while a download is already running. */
    fun requestInstall()

    /**
     * Play is holding the download for the user's yes, which it asks only
     * over an app screen: the settings app shows the question when it next
     * comes to the front. False everywhere but Play.
     */
    fun waitingForConfirmation(): Boolean = false

    /**
     * Why [requestInstall] cannot fetch the runtime's library by itself, as
     * a [com.wasimaster.wmkeyboard.core.modules.NativeConsent] id: the
     * offline build's import, or the download opt-in or a licence still to
     * agree to. Null when a download can just start, or nothing is missing.
     */
    fun missingConsent(): String? = null
}

object LlmModule {

    /** FQN [LocalLlmEngine] instantiates reflectively; keep rule in :app. */
    const val BRIDGE_CLASS = "com.wasimaster.wmkeyboard.core.localllm.bridge.LitertLmRuntime"

    @Volatile
    var gate: LlmModuleGate = object : LlmModuleGate {
        override fun installed() = true
        override fun requestInstall() = Unit
    }
}

package com.wasimaster.wmkeyboard.core.localllm

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException

/**
 * Full-flavor facade over the LiteRT-LM runtime. The runtime implementation
 * ([LlmModule.BRIDGE_CLASS]) is reached by reflection because its home
 * differs per channel — embedded in this module for sideload builds, in the
 * on-demand `:feature:llm` split for Play builds; see [LlmRuntime].
 *
 * On a Play install where the split is not yet present, the first call kicks
 * off the download and throws a user-presentable [IOException], exactly the
 * error shape callers already render — so "AI part is still downloading" and
 * "model ran out of memory" travel the same path. F-Droid's build has no
 * LiteRT-LM at all, so [AVAILABLE] is false there and nothing offers it.
 *
 * A GGUF file goes to llama.cpp instead ([LlamaCpp]), the second runtime,
 * whatever the channel: the model the user picked decides the runtime, and
 * LiteRT-LM stays the default for everything it can run.
 */
object LocalLlmEngine {

    val AVAILABLE: Boolean = BuildConfig.ENABLE_LOCAL_LLM

    @Volatile
    private var runtime: LlmRuntime? = null

    /**
     * The loaded runtime, or the reason there is none yet as an
     * [IOException]. Every failure path requests what is missing, so a
     * retry after the download finishes just works — without a restart:
     * SplitCompat (installed at startup in Play builds) lets this process
     * load the split's classes and native libraries as soon as the install
     * completes.
     */
    /** llama.cpp for a GGUF, LiteRT-LM for everything else. */
    private fun runtimeFor(context: Context, modelFile: File, backend: LocalLlmBackend): LlmRuntime =
        if (LlamaCpp.handles(modelFile)) LlamaCpp.runtime(context, backend) else runtime(context)

    private fun runtime(context: Context): LlmRuntime {
        runtime?.let { return it }
        if (!BuildConfig.ENABLE_LITERT_LM) {
            throw IOException(context.getString(R.string.core_intel_llm_litert_unavailable))
        }
        if (!LlmModule.gate.installed()) {
            LlmModule.gate.requestInstall()
            // Play may be waiting for a yes it can ask only in the settings
            // app; "try again in a moment" never came true then (Telegram, 4529).
            val message = if (LlmModule.gate.waitingForConfirmation()) {
                R.string.core_intel_llm_module_confirm
            } else {
                R.string.core_intel_llm_module_downloading
            }
            throw IOException(context.getString(message))
        }
        // LiteRT-LM loads its library by name; a delivered one goes in first.
        if (!NativeDelivery.preload("litertlm_jni")) {
            throw IOException(context.getString(R.string.core_intel_llm_module_restart))
        }
        return try {
            val impl = Class.forName(LlmModule.BRIDGE_CLASS, true, context.classLoader)
                .getDeclaredConstructor()
                .newInstance() as LlmRuntime
            runtime = impl
            impl
        } catch (e: Throwable) {
            // Installed but not loadable — a split installed moments ago on a
            // device where the classloader will only see it after a restart.
            throw IOException(context.getString(R.string.core_intel_llm_module_restart), e)
        }
    }

    /**
     * Runs one system+user exchange on the local model, blocking until the
     * full response is ready. Call on an IO dispatcher only. When [onPartial]
     * is set, the accumulated response text is forwarded as it streams.
     *
     * Throws [IOException] with a user-presentable message on any failure.
     */
    fun generate(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        onPartial: ((String) -> Unit)? = null,
    ): String = runtimeFor(context, modelFile, backend)
        .generate(context, modelFile, backend, contextTokens, system, user, onPartial)

    /**
     * [generate] with pictures on the user turn (#234: a chat screenshot for
     * the reply tool). Builds the engine with its vision encoder, which only
     * multimodal models carry; a model without one fails with
     * [VisionUnsupportedException], a subclass of [IOException], so a caller
     * that has another way to read the picture can tell the two failures
     * apart and fall back rather than show an error.
     */
    fun generateWithImages(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        images: List<ByteArray>,
        onPartial: ((String) -> Unit)? = null,
    ): String = runtimeFor(context, modelFile, backend)
        .generateWithImages(context, modelFile, backend, contextTokens, system, user, images, onPartial)

    /**
     * Opens a chat whose context lives across [ChatSession.sendMessage] calls
     * — real multi-turn on the conversation's KV cache, unlike [generate],
     * which starts over every call. Creating the session needs the runtime,
     * so on a Play install this is the call that can throw the
     * still-downloading [IOException]; the engine and the native conversation
     * are built on the first message.
     */
    fun chatSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
    ): ChatSession = ChatSession(
        runtimeFor(context, modelFile, backend).chatSession(context, modelFile, backend, contextTokens, system),
    )

    /** Wrapper keeping the public session type stable across channels. */
    class ChatSession internal constructor(
        private val delegate: LlmRuntime.Chat,
    ) {

        fun sendMessage(user: String, onPartial: ((String) -> Unit)? = null): String =
            delegate.sendMessage(user, onPartial)

        /**
         * [sendMessage] with pictures on this message, for a model that can
         * see. Throws [VisionUnsupportedException] where it cannot, or where
         * the runtime's chats take no pictures (LiteRT-LM's): send the words
         * alone then.
         */
        fun sendMessage(user: String, images: List<ByteArray>, onPartial: ((String) -> Unit)? = null): String =
            delegate.sendMessage(user, images, onPartial)

        fun seed(transcript: List<ChatReplay.Turn>) = delegate.seed(transcript)

        fun recordStoppedTurn(user: String, fullAnswer: String) =
            delegate.recordStoppedTurn(user, fullAnswer)

        fun close() = delegate.close()
    }

    /**
     * Frees the cached engine. Non-blocking; safe when the runtime was never
     * loaded (nothing to free) — the IME calls this on every trim-memory.
     */
    fun release() {
        runtime?.release()
        LlamaCpp.release()
    }

    /**
     * Cuts the running answer short, where the runtime can: llama.cpp stops
     * at its next token and the caller gets the text so far. LiteRT-LM's
     * generation cannot be interrupted and runs to its end as before.
     */
    fun stop() {
        runtime?.stop()
        LlamaCpp.stop()
    }

    /**
     * Cheap: whether a GPU engine has come and gone in this process, which is
     * when its memory can outlive it. Say yes before paying for
     * [strandedGpuMemoryKb]. Either runtime's: llama.cpp's OpenCL backend
     * keeps a context for the life of the process as LiteRT-LM's does.
     */
    fun gpuMemoryMayBeStranded(): Boolean =
        runtime?.gpuMemoryMayBeStranded() == true || LlamaCpp.gpuMemoryMayBeStranded()

    /**
     * Graphics memory left behind by GPU engines that are gone, in KB; 0 when
     * none is. Only the process ending gives it back. Reads the process's
     * memory maps — never on the main thread. Each runtime measures the whole
     * process against what it held before its own first GPU engine, so the
     * larger reading is the one from the earlier start.
     */
    fun strandedGpuMemoryKb(): Long =
        maxOf(runtime?.strandedGpuMemoryKb() ?: 0L, LlamaCpp.strandedGpuMemoryKb())

    /**
     * Compute = GPU applies to a GGUF model only from the next process (see
     * [LlamaCpp.waitsForGpuRestart]): the keyboard may recycle its process
     * when it is out of sight, as for stranded GPU memory.
     */
    fun waitsForGpuRestart(): Boolean = LlamaCpp.waitsForGpuRestart
}

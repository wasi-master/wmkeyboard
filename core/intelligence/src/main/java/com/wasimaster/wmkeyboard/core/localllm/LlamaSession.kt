package com.wasimaster.wmkeyboard.core.localllm

import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * A GGUF model opened by llama.cpp. On the CPU the weights are memory-mapped,
 * so opening is cheap until the first prompt touches them; on a GPU
 * ([onGpu]) they are uploaded to it while it opens. Plain Kotlin over
 * [LlamaNative], with nothing from Android, so it runs on a desktop JVM
 * against a host build of the library (native/llamacpp-jni/host-test.sh).
 */
internal class LlamaModel private constructor(
    val file: File,
    val handle: Long,
    val info: LlamaModelInfo,
    /** Its layers were offloaded to a GPU device. */
    val onGpu: Boolean,
) : Closeable {

    @Volatile
    private var closed = false

    val description: String
        get() = String(LlamaNative.nativeModelDescription(handle), Charsets.UTF_8)

    fun tokenize(text: String, addSpecial: Boolean): IntArray =
        LlamaNative.nativeTokenize(handle, text.toByteArray(Charsets.UTF_8), addSpecial)

    /** One token's text as raw bytes: a token can hold part of a character. */
    fun pieceBytes(token: Int): ByteArray = LlamaNative.nativeTokenPiece(handle, token)

    override fun close() {
        if (closed) return
        closed = true
        LlamaNative.nativeModelClose(handle)
    }

    companion object {
        /**
         * Opens [file], or null when llama.cpp cannot read it as a model (or
         * the GPU could not take it). [gpuLayers] 0 keeps it on the CPU.
         */
        fun open(file: File, useMmap: Boolean = true, gpuLayers: Int = 0): LlamaModel? {
            val handle = LlamaNative.nativeModelOpen(file.path, useMmap, gpuLayers)
            if (handle == 0L) return null
            return LlamaModel(file, handle, LlamaModelInfo.of(LlamaNative.nativeModelInfo(handle)), gpuLayers > 0)
        }
    }
}

/**
 * A vision model's projector (its `mmproj` GGUF) over the [LlamaModel] it
 * belongs to: what turns a picture into the embeddings a prompt carries.
 * Close it before the model.
 */
internal class LlamaVision private constructor(
    val model: LlamaModel,
    val file: File,
    val handle: Long,
) : Closeable {

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        LlamaNative.nativeVisionClose(handle)
    }

    companion object {
        /** Opens [file] for [model], or null when it is no projector of pictures for it. */
        fun open(model: LlamaModel, file: File, useGpu: Boolean, threads: Int): LlamaVision? {
            val handle = LlamaNative.nativeVisionOpen(model.handle, file.path, useGpu, threads)
            if (handle == 0L) return null
            return LlamaVision(model, file, handle)
        }
    }
}

/** A picture as llama.cpp's projector takes it: [width] by [height] pixels, RGB, three bytes each. */
internal class LlamaImage(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(width > 0 && height > 0 && rgb.size == width * height * 3) { "not $width x $height RGB" }
    }
}

/** One message of a conversation, as the chat template takes it. */
internal data class LlamaMessage(val role: String, val text: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/** Why an answer ended. */
internal enum class LlamaStop { END, CONTEXT_FULL, ABORTED, ERROR, MAX_TOKENS }

/** What [LlamaSession.generate] produced, and why it stopped. */
internal data class LlamaAnswer(val text: String, val stop: LlamaStop, val tokens: Int)

/** A prompt that could not be loaded; [code] is one of LlamaNative's `PROMPT_` values. */
internal class LlamaPromptException(val code: Int) : IOException("llama.cpp prompt failed: $code")

/**
 * One llama.cpp context over a [LlamaModel]: its KV cache, its sampler and the
 * tokens it holds. A prompt that begins with tokens the cache already holds
 * (the next turn of the same chat, or the same system prompt again) decodes
 * only the rest, which is how a chat stays warm across turns without
 * replaying anything.
 *
 * Not thread-safe, except [abort]: one caller at a time, which
 * [LlamaCppRuntime]'s lock sees to.
 */
internal class LlamaSession(
    val model: LlamaModel,
    contextTokens: Int,
    threads: Int,
    batch: Int = DEFAULT_BATCH,
) : Closeable {

    val handle: Long = LlamaNative.nativeSessionOpen(model.handle, contextTokens, threads, batch)
        .also { if (it == 0L) throw IOException("llama.cpp could not allocate a context of $contextTokens tokens") }

    @Volatile
    private var closed = false

    @Volatile
    private var abortRequested = false

    /** The window prompt and answer share, in tokens. */
    val contextSize: Int = LlamaNative.nativeContextSize(handle)

    /** Tokens the cache holds now. */
    val tokensUsed: Int get() = LlamaNative.nativeTokensUsed(handle)

    /** Tokens the last prompt kept from the cache rather than decoding again. */
    val lastReused: Int get() = LlamaNative.nativeLastReused(handle)

    fun setSampler(temperature: Float, topK: Int = 40, topP: Float = 0.95f, minP: Float = 0.05f, seed: Int = -1) =
        LlamaNative.nativeSetSampler(handle, temperature, topK, topP, minP, seed)

    /**
     * The temperature and top_p the sampler chain was last built with. The
     * native session starts on [LocalLlmSampling]'s defaults.
     */
    private var sampling: Pair<Double, Double> = LocalLlmSampling.DEFAULT_TEMPERATURE to LocalLlmSampling.DEFAULT_TOP_P

    /** Rebuilds the sampler when the On-device AI row's values changed (Telegram 13475). */
    private fun syncSampling() {
        val wanted = LocalLlmSampling.temperature to LocalLlmSampling.topP
        if (wanted == sampling) return
        setSampler(wanted.first.toFloat(), topP = wanted.second.toFloat())
        sampling = wanted
    }

    /**
     * Renders [messages] with the model's own chat template and loads them,
     * ending on the assistant's turn. Returns the prompt's length in tokens.
     * Throws [LlamaPromptException] when the prompt does not fit, the
     * template cannot render, or [abort] stopped it.
     */
    fun promptChat(messages: List<LlamaMessage>, enableThinking: Boolean = true): Int {
        abortRequested = false
        val result = LlamaNative.nativePromptChat(
            handle,
            Array(messages.size) { messages[it].role },
            Array(messages.size) { messages[it].text.toByteArray(Charsets.UTF_8) },
            enableThinking,
        )
        if (result < 0) throw LlamaPromptException(result)
        return result
    }

    /**
     * [promptChat] with [images] at the start of the last user message, read
     * by [vision]. Throws [LlamaPromptException] as [promptChat] does, with
     * [LlamaNative.PROMPT_IMAGE_FAILED] when the projector cannot read one.
     */
    fun promptChatImages(
        vision: LlamaVision,
        messages: List<LlamaMessage>,
        images: List<LlamaImage>,
        enableThinking: Boolean = true,
    ): Int {
        if (images.isEmpty()) return promptChat(messages, enableThinking)
        abortRequested = false
        val result = LlamaNative.nativePromptChatImages(
            handle,
            vision.handle,
            Array(messages.size) { messages[it].role },
            Array(messages.size) { messages[it].text.toByteArray(Charsets.UTF_8) },
            Array(images.size) { images[it].rgb },
            IntArray(images.size) { images[it].width },
            IntArray(images.size) { images[it].height },
            enableThinking,
        )
        if (result < 0) throw LlamaPromptException(result)
        return result
    }

    /** [promptChat] for raw text: no template, the model continues it. */
    fun promptRaw(text: String, addSpecial: Boolean = true): Int {
        abortRequested = false
        val result = LlamaNative.nativePromptRaw(handle, text.toByteArray(Charsets.UTF_8), addSpecial)
        if (result < 0) throw LlamaPromptException(result)
        return result
    }

    /**
     * Samples the answer to the loaded prompt, token by token, until the model
     * ends it, the window is full, [maxTokens] is reached or [abort] is
     * called. [onPartial] gets the whole text so far after every piece; an
     * exception it throws is swallowed, as the LiteRT-LM runtime does, so a
     * stale UI callback can never end the answer. The answer's tokens stay in
     * the cache, so the next turn's prompt reuses them.
     */
    fun generate(maxTokens: Int, onPartial: ((String) -> Unit)? = null): LlamaAnswer {
        syncSampling()
        val text = StringBuilder()
        var count = 0
        while (true) {
            if (abortRequested) return LlamaAnswer(text.toString(), LlamaStop.ABORTED, count)
            if (count >= maxTokens) return LlamaAnswer(text.toString(), LlamaStop.MAX_TOKENS, count)
            val piece = LlamaNative.nativeNext(handle)
            if (piece == null) {
                val stop = when (LlamaNative.nativeStopReason(handle)) {
                    LlamaNative.STOP_END -> LlamaStop.END
                    LlamaNative.STOP_CONTEXT_FULL -> LlamaStop.CONTEXT_FULL
                    LlamaNative.STOP_ABORTED -> LlamaStop.ABORTED
                    else -> LlamaStop.ERROR
                }
                return LlamaAnswer(text.toString(), stop, count)
            }
            count++
            if (piece.isEmpty()) continue
            text.append(String(piece, Charsets.UTF_8))
            if (onPartial != null) runCatching { onPartial(text.toString()) }
        }
    }

    /**
     * Each token of [text]'s log-probability (natural log) given the tokens
     * before it, the model's own scoring with no sampling involved. The first
     * token has none and is left out. Replaces the cache's contents.
     */
    fun score(text: String, addSpecial: Boolean = true): List<LlamaTokenScore>? {
        abortRequested = false
        val tokens = model.tokenize(text, addSpecial)
        if (tokens.size < 2) return emptyList()
        val logprobs = LlamaNative.nativeScore(handle, tokens) ?: return null
        return (1 until tokens.size).map { i ->
            LlamaTokenScore(tokens[i], model.pieceBytes(tokens[i]), logprobs[i])
        }
    }

    /** Stops [promptChat], [generate] or [score] at its next step. Any thread. */
    fun abort() {
        if (closed) return
        abortRequested = true
        LlamaNative.nativeAbort(handle)
    }

    override fun close() {
        if (closed) return
        closed = true
        LlamaNative.nativeSessionClose(handle)
    }

    companion object {
        /**
         * Tokens decoded in one batch while loading a prompt. Smaller than
         * llama.cpp's 2048: the compute buffer grows with it, and a phone has
         * the memory for the weights and the cache before it has it for a
         * fast prefill.
         */
        const val DEFAULT_BATCH = 256
    }
}

/** One token of a scored text: its id, its bytes, and its log-probability in nats. */
internal class LlamaTokenScore(val token: Int, val bytes: ByteArray, val logprob: Float) {
    val text: String get() = String(bytes, Charsets.UTF_8)
}

package com.wasimaster.wmkeyboard.core.semantic

import java.io.Closeable
import java.io.File

/**
 * The seam between search by meaning and the LiteRT interpreter that runs
 * EmbeddingGemma 2's graphs. [EmbeddingEngine] reaches the implementation,
 * `LitertEmbeddingRuntime`, only by reflection (see
 * [SemanticModule.BRIDGE_CLASS]), because the class lives in a different place
 * per channel, exactly as Whisper's, the sticker cutout's and PaddleOCR's do:
 *
 * - Play builds: in the on-demand `:feature:litert` module.
 * - Every other build with LiteRT: compiled into `:core:intelligence` (the
 *   `src/semanticbridge` source directory), the interpreter itself delivered
 *   as a pinned library where the build delivers them.
 *
 * Only the graphs run on the other side; tokens, the token table and the
 * picture's patches are [EmbeddingInput] on this side. No type here may come
 * from LiteRT. Not safe to share between threads.
 */
interface EmbeddingRuntime : Closeable {

    /** Loads the text encoder. Throws when it will not load. */
    fun openText(encoder: File, threads: Int)

    /**
     * Loads an NPU build of the text encoder ([SemanticNpu]) in place of the
     * CPU one, on LiteRT's CompiledModel with the dispatch libraries in
     * [dispatchDir]. [encode] then runs on it. Throws when it will not load;
     * [openText] puts the CPU encoder back.
     */
    fun openTextNpu(encoder: File, dispatchDir: File) {
        throw UnsupportedOperationException("no NPU in this runtime")
    }

    /**
     * Runs the encoder on [embeddings], [length] rows of
     * [EmbeddingInput.WIDTH] floats, through the `encoder_1x<n>` signature
     * that fits them, into [out] ([EmbeddingInput.DIMENSIONS] floats, not
     * normalised).
     */
    fun encode(embeddings: FloatArray, length: Int, out: FloatArray)

    /** Loads the vision encoder and adapter. Throws when either will not load. */
    fun openVision(encoder: File, adapter: File, threads: Int)

    /**
     * Runs the vision tower on [patches] ([EmbeddingInput.MAX_PATCHES] ×
     * [EmbeddingInput.PATCH_VALUES]) and [positions], writing one
     * [EmbeddingInput.WIDTH]-float soft token per valid output into [out].
     * Returns how many are valid.
     */
    fun see(patches: FloatArray, positions: IntArray, out: FloatArray): Int

    /** Frees the vision graphs, keeping the text encoder. */
    fun closeVision()
}

object SemanticModule {

    /** FQN [EmbeddingEngine] instantiates reflectively; keep rule in :app. */
    const val BRIDGE_CLASS = "com.wasimaster.wmkeyboard.core.semantic.bridge.LitertEmbeddingRuntime"
}

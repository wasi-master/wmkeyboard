package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.voice.moonshine.MoonshineEngine
import com.wasimaster.wmkeyboard.core.voice.parakeet.ParakeetEngine
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model-independent parts of the ExecuTorch engines: the piece
 * vocabularies, and Parakeet's and Moonshine's decode loops against fakes.
 * The real models were checked on the desktop; see native/executorch-jni.
 */
class EtModelsTest {

    // ---- vocabularies ----

    /** A SentencePiece ModelProto with [pieces], each (text, type). */
    private fun sentencePiece(vararg pieces: Pair<String, Int>): ByteArray {
        fun varint(out: ByteArrayOutputStream, v: Int) {
            var x = v
            while (x >= 0x80) {
                out.write((x and 0x7f) or 0x80)
                x = x ushr 7
            }
            out.write(x)
        }
        val model = ByteArrayOutputStream()
        for ((text, type) in pieces) {
            val piece = ByteArrayOutputStream()
            val bytes = text.toByteArray(Charsets.UTF_8)
            piece.write(0x0a) // field 1, length-delimited
            varint(piece, bytes.size)
            piece.write(bytes)
            piece.write(0x15) // field 2, fixed32: the score, skipped
            piece.write(ByteArray(4))
            piece.write(0x18) // field 3, varint: the type
            varint(piece, type)
            model.write(0x0a)
            varint(model, piece.size())
            model.write(piece.toByteArray())
        }
        // A trailing field the reader must step over.
        model.write(0x12)
        varint(model, 2)
        model.write(byteArrayOf(1, 2))
        return model.toByteArray()
    }

    @Test
    fun `a SentencePiece model decodes words, bytes and skips control pieces`() {
        val vocab = PieceVocab.fromSentencePiece(
            sentencePiece(
                "<unk>" to 2, "<s>" to 3, "▁hel" to 1, "lo" to 1, "▁w" to 1,
                "<0xC3>" to 6, "<0xB6>" to 6, "rld" to 1,
            ),
        )
        assertEquals(8, vocab.size)
        // ö is two byte pieces, joined before decoding.
        assertEquals("hello wörld", vocab.decode(listOf(1, 2, 3, 4, 5, 6, 7)))
    }

    @Test
    fun `a tokenizer json decodes the same way and skips its special tokens`() {
        val vocab = PieceVocab.fromTokenizerJson(
            """
            {"model": {"type": "BPE", "vocab": {"<unk>": 0, "<s>": 1, "</s>": 2, "▁Good": 3, "▁day": 4, ".": 5}},
             "added_tokens": [{"id": 1, "content": "<s>", "special": true}, {"id": 2, "content": "</s>", "special": true}]}
            """,
        )
        assertEquals("Good day.", vocab.decode(listOf(1, 3, 4, 5, 2)))
    }

    // ---- Parakeet ----

    @Test
    fun `parakeet ultra is used over v3, the same size, when both are downloaded`() {
        val ultra = EtCatalog.byId("parakeet-ultra")!!
        val v3 = EtCatalog.byId("parakeet-v3")!!
        assertEquals(v3.millions, ultra.millions)
        assertTrue(ultra.outranks(v3))
        assertTrue(!v3.outranks(ultra))
        assertEquals(ultra, listOf(v3, ultra).maxWithOrNull(EtModel.ORDER))
        assertEquals(ultra, listOf(ultra, v3).maxWithOrNull(EtModel.ORDER))
    }

    @Test
    fun `a GPU build of parakeet loses to any CPU build the same size, and names its twin`() {
        val ultra = EtCatalog.byId("parakeet-ultra")!!
        val v3 = EtCatalog.byId("parakeet-v3")!!
        val ultraGpu = EtCatalog.byId("parakeet-ultra-gpu")!!
        val v3Gpu = EtCatalog.byId("parakeet-v3-gpu")!!
        assertTrue(ultraGpu.gpu && v3Gpu.gpu && !ultra.gpu)
        assertEquals(ultra, listOf(ultraGpu, ultra).maxWithOrNull(EtModel.ORDER))
        assertEquals(v3, listOf(ultraGpu, v3).maxWithOrNull(EtModel.ORDER))
        assertTrue(ultraGpu.outranks(v3Gpu))
        assertEquals(ultra.id, ultraGpu.cpuTwin)
        assertEquals(v3.id, v3Gpu.cpuTwin)
        // No Vulkan in a unit test: the GPU builds are not offered.
        assertTrue(EtCatalog.forEngine(EtEngine.PARAKEET).none { it.gpu })
    }

    /**
     * A transducer that emits [script] in order, one token per joint call,
     * then blanks; durations from [durations]. Two encoder frames.
     */
    private class FakeParakeet(private val script: List<Pair<Int, Int>>) : EtRunner {
        var joints = 0
        val decoded = ArrayList<Long>()
        private val state = TensorSpec(DType.FLOAT, longArrayOf(2, 1, 4))

        override fun shape(method: String): MethodShape? = when (method) {
            "preprocessor" -> MethodShape(listOf(TensorSpec(DType.FLOAT, longArrayOf(800_000))), emptyList())
            "decoder_step" -> MethodShape(listOf(TensorSpec(DType.LONG, longArrayOf(1, 1)), state, state), emptyList())
            else -> null
        }

        override fun run(method: String, inputs: List<Tensor>): List<Tensor> = when (method) {
            "preprocessor" -> listOf(Tensor(longArrayOf(1, 128, 10), FloatArray(1280)), Tensor.long(10))
            "encoder" -> listOf(Tensor(longArrayOf(1, 3, 4), FloatArray(12)), Tensor.long(3))
            "decoder_step" -> {
                decoded += inputs[0].scalar
                listOf(Tensor(longArrayOf(1, 1, 4), FloatArray(4)), Tensor.zeros(state), Tensor.zeros(state))
            }
            "joint" -> {
                val (token, duration) = script.getOrElse(joints) { BLANK to 1 }
                joints++
                listOf(Tensor(LongArray(0), longArrayOf(token.toLong())), Tensor(LongArray(0), longArrayOf(duration.toLong())))
            }
            else -> error(method)
        }

        companion object {
            const val BLANK = 99
        }
    }

    @Test
    fun `parakeet walks frames by the duration head and feeds each token back`() {
        // Two tokens on frame 0 (duration 0 keeps the frame), then a skip of 2
        // frames, then a blank that moves past the end.
        val fake = FakeParakeet(listOf(5 to 0, 6 to 2, FakeParakeet.BLANK to 1))
        val tokens = ParakeetEngine.decode(fake, FloatArray(16_000), FakeParakeet.BLANK)
        assertEquals(listOf(5, 6), tokens)
        // Primed with blank, then each emitted token.
        assertEquals(listOf(99L, 5L, 6L), fake.decoded)
    }

    @Test
    fun `parakeet forces the frame on after ten symbols in place`() {
        val fake = FakeParakeet(List(30) { 7 to 0 })
        val tokens = ParakeetEngine.decode(fake, FloatArray(16_000), FakeParakeet.BLANK)
        // Three frames, ten symbols each before the decoder is moved on.
        assertEquals(30, tokens.size)
    }

    // ---- Moonshine ----

    private class FakeEncoder : EtRunner {
        override fun shape(method: String) =
            MethodShape(listOf(TensorSpec(DType.FLOAT, longArrayOf(1, 480_000))), emptyList())

        override fun run(method: String, inputs: List<Tensor>) = listOf(Tensor(longArrayOf(1, 10, 288), FloatArray(2880)))
    }

    /** Appends tokens 10, 11, 12, then end; or repeats 10 forever when [loops]. */
    private class FakeDecoder(private val loops: Boolean = false) : EtRunner {
        override fun shape(method: String) = MethodShape(
            listOf(TensorSpec(DType.LONG, longArrayOf(1, 178)), TensorSpec(DType.FLOAT, longArrayOf(1, 1248, 288))),
            emptyList(),
        )

        override fun run(method: String, inputs: List<Tensor>): List<Tensor> {
            val tokens = inputs[0].longs
            val next = if (loops) 10L else listOf(10L, 11L, 12L, 2L)[tokens.size - 1]
            return listOf(Tensor(longArrayOf(1, tokens.size + 1L), tokens + next))
        }
    }

    @Test
    fun `moonshine decodes until the end token, without start or end`() {
        assertEquals(listOf(10, 11, 12), MoonshineEngine.decode(FakeEncoder(), FakeDecoder(), FloatArray(32_000)))
    }

    @Test
    fun `moonshine stops a decoder that repeats itself at a budget the audio could fill`() {
        val tokens = MoonshineEngine.decode(FakeEncoder(), FakeDecoder(loops = true), FloatArray(32_000))
        // Two seconds: about 13 tokens plus slack, nowhere near the 178 limit.
        assertTrue(tokens.size in 10..16)
    }
}

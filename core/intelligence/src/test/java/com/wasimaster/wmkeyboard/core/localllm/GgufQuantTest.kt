package com.wasimaster.wmkeyboard.core.localllm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** How a GGUF stores its weights, and which formats Compute on NPU runs on the NPU. */
class GgufQuantTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A GGUF v3 header with [pairs] as its key-value pairs, no tensors. */
    private fun gguf(vararg pairs: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(v: String) = v.toByteArray().let { u64(it.size.toLong()); out.write(it) }
        out.write("GGUF".toByteArray())
        u32(3)
        u64(0)
        u64(pairs.size.toLong())
        for ((key, value) in pairs) {
            str(key)
            when (value) {
                is String -> { u32(8); str(value) }
                is Int -> { u32(4); u32(value) }
                is List<*> -> { u32(9); u32(8); u64(value.size.toLong()); value.forEach { str(it as String) } }
                else -> error("unsupported")
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `the header's file type wins over the name`() {
        val tokens = List(500) { "token$it" }
        val file = temp.newFile("renamed-Q4_K_M.gguf").apply {
            writeBytes(gguf("general.architecture" to "llama", "tokenizer.ggml.tokens" to tokens, "general.file_type" to 7))
        }
        assertEquals(GgufQuant.Q8_0, GgufFile.quantization(file))
    }

    @Test
    fun `a header without a file type falls back to the name`() {
        val file = temp.newFile("Llama-3.2-1B-Instruct-Q4_0.gguf").apply {
            writeBytes(gguf("general.architecture" to "llama"))
        }
        assertEquals(GgufQuant.Q4_0, GgufFile.quantization(file))
    }

    @Test
    fun `an unknown file type is no format at all`() {
        // 33 is an old repacked Q4_0_4_4, which the NPU does not run.
        val file = temp.newFile("model-Q4_0_4_4.gguf").apply {
            writeBytes(gguf("general.architecture" to "llama", "general.file_type" to 33))
        }
        assertNull(GgufFile.quantization(file))
        assertNull(GgufFile.quantization(temp.newFile("text.gguf").apply { writeText("not a gguf") }))
    }

    @Test
    fun `names give the longest tag that stands as a word`() {
        assertEquals(GgufQuant.Q4_K_M, GgufQuant.ofName("Qwen3VL-4B-Instruct-Q4_K_M.gguf"))
        assertEquals(GgufQuant.Q8_0, GgufQuant.ofName("smollm2-360m-instruct-q8_0.gguf"))
        assertEquals(GgufQuant.IQ4_XS, GgufQuant.ofName("MiniCPM-V-4_7-1B-IQ4_XS.gguf"))
        assertEquals(GgufQuant.F16, GgufQuant.ofName("qwen2.5-0.5b-instruct-fp16.gguf"))
        assertNull(GgufQuant.ofName("my-model.gguf"))
    }

    @Test
    fun `only Q4_0, Q8_0 and MXFP4 run on the NPU`() {
        assertEquals(setOf(GgufQuant.Q4_0, GgufQuant.Q8_0, GgufQuant.MXFP4), GgufQuant.entries.filter { it.npu }.toSet())
    }

    @Test
    fun `every GGUF row says its format, and each format of a model is a row`() {
        val gguf = LocalLlmCatalog.models.filter { it.runtime == LocalLlmRuntime.LLAMA_CPP }
        assertTrue(gguf.all { it.quant != null })
        // The file name agrees with the format the row claims.
        gguf.forEach { assertEquals(it.id, it.quant, GgufQuant.ofName(it.fileName)) }
        val llama = LocalLlmCatalog.versionsOf(LocalLlmCatalog.byId("gguf-llama3.2-1b")!!)
        assertEquals(listOf(GgufQuant.Q4_0, GgufQuant.Q4_K_M, GgufQuant.Q8_0), llama.map { it.quant })
        // The first row keeps its id, so a file downloaded before versions is still found.
        assertEquals("gguf-llama3.2-1b", llama[1].id)
        assertEquals("Llama 3.2 1B (GGUF, Q4_K_M)", llama[1].displayName)
        assertEquals("Llama 3.2 1B (GGUF)", llama[0].familyName)
        // A model with one file has no family to choose in.
        assertNull(LocalLlmCatalog.byId("gguf-qwen3-0.6b")!!.family)
    }
}

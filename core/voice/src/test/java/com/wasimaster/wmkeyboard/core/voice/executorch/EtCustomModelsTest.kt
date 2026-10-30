package com.wasimaster.wmkeyboard.core.voice.executorch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtCustomModelsTest {

    @Test
    fun `the int8 copy of each part is the one kept`() {
        val kept = EtCustomModels.parts(
            listOf(
                "encoder-epoch-99-avg-1.onnx", "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt", "README.md",
            ),
        )
        assertEquals(
            listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
            kept,
        )
    }

    @Test
    fun `a model with no joiner is not a transducer`() {
        assertNull(EtCustomModels.parts(listOf("encoder.onnx", "decoder.onnx", "tokens.txt")))
    }
}

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
            listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "tokens.txt", "joiner-epoch-99-avg-1.int8.onnx"),
            kept,
        )
    }

    @Test
    fun `a model with no joiner keeps the three parts a Paraformer has`() {
        assertEquals(
            listOf("encoder.onnx", "decoder.onnx", "tokens.txt"),
            EtCustomModels.parts(listOf("encoder.onnx", "decoder.onnx", "tokens.txt")),
        )
    }

    @Test
    fun `a model missing a part it needs is refused`() {
        assertNull(EtCustomModels.parts(listOf("encoder.onnx", "tokens.txt")))
    }
}

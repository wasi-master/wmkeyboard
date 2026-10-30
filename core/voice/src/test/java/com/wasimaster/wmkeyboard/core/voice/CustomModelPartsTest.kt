package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.voice.executorch.EtCustomModels
import com.wasimaster.wmkeyboard.core.voice.punctuation.PunctuationCustomModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomModelPartsTest {

    @Test fun aTransducerKeepsItsFourPartsInt8First() {
        val names = listOf("encoder.onnx", "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
        assertEquals(listOf("encoder.int8.onnx", "decoder.onnx", "tokens.txt", "joiner.int8.onnx"), EtCustomModels.parts(names))
    }

    @Test fun aParaformerHasNoJoiner() {
        val names = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
        assertEquals(names, EtCustomModels.parts(names))
    }

    @Test fun aModelWithoutADecoderOrTokensIsNotOne() {
        assertNull(EtCustomModels.parts(listOf("model.onnx", "tokens.txt")))
        assertNull(EtCustomModels.parts(listOf("encoder.onnx", "decoder.onnx")))
    }

    @Test fun aPunctuationModelNeedsItsVocabulary() {
        assertEquals(
            listOf("model.int8.onnx", "bpe.vocab"),
            PunctuationCustomModels.parts(listOf("model.onnx", "model.int8.onnx", "bpe.vocab", "README.md")),
        )
        assertNull(PunctuationCustomModels.parts(listOf("model.onnx")))
        assertNull(PunctuationCustomModels.parts(listOf("bpe.vocab")))
    }
}

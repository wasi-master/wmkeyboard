package com.wasimaster.wmkeyboard.core.voice.whisper

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperCustomModelsTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A whisper.cpp header followed by some bytes, as `convert-h5-to-ggml.py` writes it. */
    private fun ggml(
        vocab: Int = 51865,
        audioLayers: Int = 6,
        textLayers: Int = 6,
        mels: Int = 80,
        magic: Int = 0x67676d6c,
        audioCtx: Int = 1500,
    ): File {
        val buf = ByteBuffer.allocate(4 * 12 + 64).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(magic)
        buf.putInt(vocab)
        buf.putInt(audioCtx)
        buf.putInt(512) // n_audio_state
        buf.putInt(8) // n_audio_head
        buf.putInt(audioLayers)
        buf.putInt(448) // n_text_ctx
        buf.putInt(512) // n_text_state
        buf.putInt(8) // n_text_head
        buf.putInt(textLayers)
        buf.putInt(mels)
        buf.putInt(9) // ftype: q5_1
        return tmp.newFile().apply { writeBytes(buf.array()) }
    }

    @Test
    fun `a whisper cpp model is told by its header`() {
        val header = WhisperCustomModels.header(ggml())!!
        assertEquals(WhisperSize.BASE, header.size)
        assertFalse(header.englishOnly)
    }

    @Test
    fun `the size class follows the encoder, turbo by its short decoder`() {
        assertEquals(WhisperSize.TINY, WhisperCustomModels.header(ggml(audioLayers = 4, textLayers = 4))!!.size)
        assertEquals(WhisperSize.SMALL, WhisperCustomModels.header(ggml(audioLayers = 12, textLayers = 12))!!.size)
        assertEquals(WhisperSize.MEDIUM, WhisperCustomModels.header(ggml(audioLayers = 24, textLayers = 24))!!.size)
        assertEquals(WhisperSize.TURBO, WhisperCustomModels.header(ggml(audioLayers = 32, textLayers = 4, mels = 128))!!.size)
        assertEquals(WhisperSize.LARGE, WhisperCustomModels.header(ggml(audioLayers = 32, textLayers = 32, mels = 128))!!.size)
    }

    @Test
    fun `the English-only tokenizer makes an English model`() {
        assertTrue(WhisperCustomModels.header(ggml(vocab = 51864))!!.englishOnly)
    }

    @Test
    fun `anything else is not a whisper model`() {
        assertNull(WhisperCustomModels.header(ggml(magic = 0x46554747))) // GGUF
        assertNull(WhisperCustomModels.header(ggml(vocab = 32000))) // an old GGML LLM
        assertNull(WhisperCustomModels.header(ggml(audioCtx = 2048)))
        assertNull(WhisperCustomModels.header(ggml(mels = 40)))
        assertNull(WhisperCustomModels.header(tmp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }))
    }

    @Test
    fun `the id is the file name without ggml and bin, in safe characters`() {
        assertEquals("custom-hinglish-swift-q5_1", WhisperCustomModels.idFor("ggml-hinglish-swift-q5_1.bin"))
        assertEquals("custom-my-model", WhisperCustomModels.idFor("My Model.bin"))
        assertEquals("custom-model", WhisperCustomModels.idFor("ggml-.bin"))
        assertTrue(WhisperCustomModels.isCustom(WhisperCustomModels.idFor("x.bin")))
    }
}

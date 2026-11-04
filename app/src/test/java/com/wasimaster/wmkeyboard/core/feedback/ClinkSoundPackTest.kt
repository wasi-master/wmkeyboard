package com.wasimaster.wmkeyboard.core.feedback

import java.util.zip.ZipInputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Clink `.clinkpack` rewritten as a `.wmsoundpack`. */
@OptIn(ExperimentalEncodingApi::class)
class ClinkSoundPackTest {

    /** The smallest header [SoundPackFile] accepts as a WAV. */
    private val wav = "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `every recording becomes a press sample`() {
        val encoded = Base64.Default.encode(wav)
        val pack = """{"pack": {"id": "arcade", "name": "Arcade", "blurb": "A blip.",
            "sampleNames": ["arcade-1", "arcade-2"], "fileExtension": "wav", "gain": 0.3},
            "samples": ["$encoded", "$encoded"]}"""
        val zip = ClinkSoundPack.toWmSoundPack(pack)!!
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes()
            }
        }
        val manifest = entries.getValue(SoundPackFile.MANIFEST).decodeToString()
        assertTrue(manifest.contains("\"${SoundPackFile.FORMAT}\""))
        assertTrue(manifest.contains("\"Arcade\""))
        assertEquals(listOf("sample_0.wav", "sample_1.wav"), entries.keys.filter { it != SoundPackFile.MANIFEST }.sorted())
        assertTrue(entries.getValue("sample_0.wav").contentEquals(wav))
    }

    @Test
    fun `a pack with no readable recording is refused`() {
        assertNull(ClinkSoundPack.toWmSoundPack("""{"pack": {"name": "x"}, "samples": ["!!!"]}"""))
        assertNull(ClinkSoundPack.toWmSoundPack("""{"pack": {"name": "x"}, "samples": []}"""))
        assertNull(ClinkSoundPack.toWmSoundPack("not json"))
    }
}

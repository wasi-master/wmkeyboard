package com.wasimaster.wmkeyboard.core.prediction

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Clink `.clex`, written here the way Clink's `build-pack.py` writes one. */
class ClinkLexiconTest {

    private fun clex(words: List<Pair<String, Int>>, alphabet: String = "abc"): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        out.write("CLEX".toByteArray())
        u32(1); u32(words.size); u32(alphabet.length)
        alphabet.forEach { u32(it.code) }
        out.write(ByteArray((alphabet.length + 1) * alphabet.length))
        var offset = 0
        for ((word, _) in words) { u32(offset); offset += word.toByteArray().size }
        u32(offset)
        words.forEach { out.write(it.second) }
        words.forEach { out.write(it.first.length) }
        words.forEach { out.write(it.first.toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun `reads every word with its score on this app's scale`() {
        val words = ClinkLexicon.read(clex(listOf("aan" to 180, "die" to 212, "ê" to 1)))
        assertEquals(listOf("aan", "die", "ê"), words.map { it.first })
        assertEquals(DictionaryLoader.scaleAospFrequency(212), words[1].second)
        assertTrue(words.all { it.second >= 1 })
    }

    @Test
    fun `the plain list reads back through the dictionary loader`() {
        val text = ClinkLexicon.toText(clex(listOf("aan" to 180, "die" to 212)))
        val entries = DictionaryLoader.loadEntries(text.byteInputStream())
        assertEquals(listOf("aan", "die"), entries.map { it.first })
    }

    @Test
    fun `a truncated or foreign file reads as nothing`() {
        val whole = clex(listOf("aan" to 180, "die" to 212))
        assertTrue(ClinkLexicon.read(whole.copyOf(whole.size - 2)).isEmpty())
        assertFalse(ClinkLexicon.looksLikeClex("PK\u0003\u0004".toByteArray()))
        assertTrue(ClinkLexicon.read(ByteArray(0)).isEmpty())
    }

    @Test
    fun `the dictionary importer takes a lexicon`() {
        val inspection = CustomDictionaries.inspect(
            listOf(CustomDictionaries.ImportFile("af.clex", clex(listOf("aan" to 180, "die" to 212)))),
        )
        assertTrue("got $inspection", inspection is CustomDictionaries.Inspection.Found)
        val found = (inspection as CustomDictionaries.Inspection.Found).dictionaries.single()
        assertEquals(listOf("aan", "die"), found.words.map { it.first })
    }
}

package com.wasimaster.wmkeyboard.core.vocab

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabPackFileTest {

    private val abhor = VocabWord(
        word = "abhor",
        pos = listOf("verb"),
        ipa = mapOf("us" to "/əbˈhɔɹ/", "uk" to "/əbˈhɔː/"),
        respelling = "ab-HOR",
        audio = mapOf("us" to "https://x/En-us-abhor.ogg.mp3"),
        senses = listOf(
            VocabSense(
                pos = "verb",
                definition = "To regard as horrifying or detestable.",
                example = "I abhor traffic jams.",
                quotations = listOf(VocabQuotation("Let loue bee without dissimulation", "1611, The Holy Bible")),
                synonyms = listOf("detest", "loathe"),
                tags = listOf("transitive"),
            ),
        ),
        synonyms = listOf("hate", "detest"),
        antonyms = listOf("love"),
        family = VocabFamily(derived = listOf("abhorrable"), related = listOf("abhorrence")),
        forms = listOf("abhors", "abhorred", "abhorring"),
        hyphenation = listOf("ab", "hor"),
        rhymes = "-ɔː(ɹ)",
        etymology = "From Middle English abhorren.",
        origin = listOf(VocabOrigin("Middle English", "abhorren"), VocabOrigin("Latin", "abhorreō")),
        root = "Proto-Indo-European *ǵʰers-",
        attested = "1449",
        mnemonic = "Ab + horror.",
        translations = mapOf("bn" to VocabTranslation(w = listOf("ঘৃণা করা")), "hy" to VocabTranslation(listOf("ատել"), listOf("atel"))),
        sources = listOf("ws1", "b333"),
        triggers = listOf(VocabTrigger("hate", listOf("hated", "hates", "hating"), 2.7)),
    )

    private val pack = VocabPack(
        meta = VocabPackMeta(
            id = "ws1",
            name = "Word Smart 1",
            sources = listOf(VocabSource("ws1", "Word Smart 1", "WS1")),
            attribution = listOf(VocabAttribution("Wiktionary", "CC-BY-SA-3.0", "https://kaikki.org")),
        ),
        words = listOf(abhor),
    )

    @Test
    fun `round trip keeps every field`() {
        val text = VocabPackFile.encode(pack, appVersion = 41, appVersionName = "1.4.0")
        val decoded = VocabPackFile.decode(text)
        assertNotNull(decoded)
        assertEquals(pack.meta, decoded!!.meta)
        assertEquals(listOf(abhor), decoded.words)
        assertTrue(text.contains("\"format\": \"wmkeyboard-vocab\""))
    }

    @Test
    fun `wrong format tag is not a pack`() {
        assertNull(VocabPackFile.decode("""{"format":"wmkeyboard-snippets","words":[]}"""))
        assertNull(VocabPackFile.decode("not json"))
    }

    @Test
    fun `unknown keys are ignored and lemmas normalised`() {
        val text = """
            {"format":"wmkeyboard-vocab","version":9,"mystery":true,
             "pack":{"id":"x","name":"X","future":1},
             "words":[{"word":"  Abhor ","novel":[1,2]},{"word":"abhor"},{"word":"   "},{"word":"Alacrity"}]}
        """.trimIndent()
        val decoded = VocabPackFile.decode(text)!!
        assertEquals(listOf("abhor", "alacrity"), decoded.words.map { it.word })
    }

    @Test
    fun `gzipped stream inflates`() {
        val text = VocabPackFile.encode(pack, 0, "")
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val decoded = VocabPackFile.decode(ByteArrayInputStream(bytes))
        assertEquals(listOf("abhor"), decoded!!.words.map { it.word })
        val plain = VocabPackFile.decode(ByteArrayInputStream(text.toByteArray()))
        assertEquals(listOf("abhor"), plain!!.words.map { it.word })
    }

    @Test
    fun `word cap holds`() {
        val many = (1..VocabPackFile.MAX_WORDS + 10).joinToString(",") { """{"word":"w$it"}""" }
        val decoded = VocabPackFile.decode("""{"format":"wmkeyboard-vocab","words":[$many]}""")!!
        assertEquals(VocabPackFile.MAX_WORDS, decoded.words.size)
    }

    @Test
    fun `accent helpers fall back`() {
        assertEquals("/əbˈhɔː/", abhor.ipaFor(VocabAccent.UK))
        assertEquals("https://x/En-us-abhor.ogg.mp3", abhor.audioFor(VocabAccent.UK))
        assertEquals("To regard as horrifying or detestable.", abhor.definition)
        assertEquals(listOf("abhorrable", "abhorrence"), abhor.familyWords)
    }

    @Test
    fun `file name is safe`() {
        assertEquals("GRE words.wmvocab.json", VocabPackFile.fileName(VocabPackMeta(name = "GRE words")))
        assertEquals("a_b.wmvocab.json", VocabPackFile.fileName(VocabPackMeta(name = "a/b")))
        assertEquals("vocabulary.wmvocab.json", VocabPackFile.fileName(VocabPackMeta(name = "")))
    }
}

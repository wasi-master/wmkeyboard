package com.wasimaster.wmkeyboard.core.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabIndexTest {

    private fun word(
        lemma: String,
        triggers: List<VocabTrigger> = emptyList(),
        forms: List<String> = emptyList(),
        sources: List<String> = emptyList(),
        mnemonic: String? = null,
    ) = VocabWord(word = lemma, triggers = triggers, forms = forms, sources = sources, mnemonic = mnemonic)

    private fun pack(id: String, words: List<VocabWord>, user: Boolean = false, enabled: Boolean = true) =
        VocabPack(VocabPackMeta(id = id, name = id, userCreated = user), words, enabled = enabled)

    @Test
    fun `triggers resolve with inflected replacements`() {
        val abhor = word(
            "abhor",
            triggers = listOf(VocabTrigger("hate", listOf("hated", "hates", "hating"), 2.7)),
            forms = listOf("abhors", "abhorred", "abhorring"),
        )
        val index = VocabIndex.build(listOf(pack("ws1", listOf(abhor))))
        assertEquals("abhor", index.hitsFor("HATE").single().replacement)
        assertEquals("abhorred", index.hitsFor("hated").single().replacement)
        assertEquals("abhors", index.hitsFor("hates").single().replacement)
        assertEquals("abhorring", index.hitsFor("hating").single().replacement)
        assertTrue(index.hitsFor("love").isEmpty())
        assertEquals(abhor, index.lookup("Abhor"))
    }

    @Test
    fun `gap floor filters and hits are capped and ordered`() {
        val words = (1..6).map { i ->
            word("w$i", triggers = listOf(VocabTrigger("big", emptyList(), i.toDouble())))
        }
        val index = VocabIndex.build(listOf(pack("p", words)))
        val hits = index.hitsFor("big")
        assertEquals(VocabIndex.MAX_HITS_PER_TRIGGER, hits.size)
        assertEquals(listOf("w6", "w5", "w4", "w3"), hits.map { it.lemma })
        assertEquals(listOf("w6", "w5"), index.hitsFor("big", minGap = 5.0).map { it.lemma })
    }

    @Test
    fun `packs merge by lemma with source union and user overrides`() {
        val hosted = pack("ws1", listOf(word("abhor", sources = listOf("ws1", "b333"), mnemonic = "hosted")))
        val user = pack("user_1", listOf(word("abhor", sources = listOf("mine"), mnemonic = "mine")), user = true)
        val index = VocabIndex.build(listOf(user, hosted))
        val merged = index.lookup("abhor")!!
        assertEquals(listOf("ws1", "b333", "mine"), merged.sources)
        assertEquals("mine", merged.mnemonic)
        assertEquals("ws1", index.packOf("abhor")?.id)
        assertEquals(1, index.size)
    }

    @Test
    fun `disabled packs are skipped`() {
        val index = VocabIndex.build(listOf(pack("off", listOf(word("alacrity")), enabled = false)))
        assertNull(index.lookup("alacrity"))
        assertTrue(index.isEmpty)
        assertTrue(VocabIndex.EMPTY.hitsFor("anything").isEmpty())
    }

    @Test
    fun `matching form falls back to the lemma`() {
        val record = word("terse", forms = listOf("terser", "tersest"))
        assertEquals("terser", VocabIndex.matchingForm(record, "shorter"))
        assertEquals("terse", VocabIndex.matchingForm(record, "shortened"))
        assertEquals("terse", VocabIndex.matchingForm(word("terse"), "shorter"))
    }
}

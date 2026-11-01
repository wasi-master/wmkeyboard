package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.prediction.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks over the hardcoded wordlist catalog: every entry must point at
 * a language the registry actually has (else its download row could never be
 * shown, or worse, its dictionary would load for nothing), and ids/urls must
 * be well-formed since the store keys directories by them.
 */
class DictionaryCatalogTest {

    @Test
    fun everyEntryTargetsARegisteredLanguage() {
        val known = LanguageRegistry.all.mapTo(HashSet()) { it.id }
        for (entry in DictionaryCatalog.entries) {
            assertTrue(
                "catalog entry ${entry.id} targets unknown language ${entry.languageId}",
                entry.languageId in known,
            )
        }
    }

    @Test
    fun idsAreUniqueAndFilesystemSafe() {
        assertEquals(
            DictionaryCatalog.entries.size,
            DictionaryCatalog.entries.map { it.id }.toSet().size,
        )
        for (entry in DictionaryCatalog.entries) {
            assertTrue(
                "id ${entry.id} is not filesystem/url safe",
                entry.id.matches(Regex("[a-z][a-z0-9_]*")),
            )
            assertTrue(entry.repoCode.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun urlsPointAtTheDataRepo() {
        for (entry in DictionaryCatalog.entries) {
            val stem = entry.fileStem ?: "${entry.repoCode}_${entry.suffix}"
            assertEquals(
                "https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/data/${entry.repoCode}/$stem.txt.gz",
                entry.url,
            )
        }
    }

    @Test
    fun sizesAndCountsArePlausible() {
        // Languages whose *entire* vocabulary is tiny by design — the list is
        // complete, not a truncated scrape, so the size floor does not apply.
        val completeTinyVocabularies = setOf("tok")
        for (entry in DictionaryCatalog.entries) {
            assertTrue(
                "${entry.id} word count",
                entry.totalWordCount >= 1000 || entry.id in completeTinyVocabularies,
            )
            assertTrue("${entry.id} gz size", entry.approxGzBytes > 0)
        }
        // The pt special case: two entries per source, one language, distinct
        // variants. The variant label is a string resource now, so the two are
        // told apart by which resource each names — the point being that they
        // name two different ones, whatever those two happen to say.
        for (source in WordlistSource.entries) {
            val pt = DictionaryCatalog.forLanguage("pt").filter { it.source == source }
            assertEquals(2, pt.size)
            assertEquals(
                setOf(
                    R.string.core_pred_wordlist_variant_europe_label,
                    R.string.core_pred_wordlist_variant_brazil_label,
                ),
                pt.mapNotNull { it.variantRes }.toSet(),
            )
        }
    }

    @Test
    fun everyAospListHasACountedListBesideIt() {
        // "Use ours instead" has to be possible wherever AOSP is the default.
        val aosp = DictionaryCatalog.entries.filter { it.source == WordlistSource.AOSP }
        assertEquals(27, aosp.size)
        for (entry in aosp) {
            assertTrue(
                "${entry.id} has no counted list to switch to",
                DictionaryCatalog.forLanguage(entry.languageId).any { it.source == WordlistSource.FREQUENCY },
            )
            // A list downloaded before AOSP lists existed carries no source
            // marker and is taken for the entry whose id is the language's.
            // That has to stay the counted one it really is.
            assertTrue(entry.id != entry.languageId)
            assertTrue(entry.url, "_aosp" in entry.url)
        }
    }

    @Test
    fun aospIsPreferredWhereItExists() {
        assertEquals("en_aosp", DictionaryCatalog.preferred("en")?.id)
        assertEquals("de_aosp", DictionaryCatalog.preferred("de")?.id)
        assertEquals("he_aosp", DictionaryCatalog.preferred("he")?.id)
        // Europe first, as it was before there were two sources.
        assertEquals("pt_aosp", DictionaryCatalog.preferred("pt")?.id)
        assertEquals("th", DictionaryCatalog.preferred("th")?.id)
        assertEquals(null, DictionaryCatalog.preferred("zzz"))
    }

    @Test
    fun englishAospListsAreTheFourSpellings() {
        val en = DictionaryCatalog.forLanguage("en").filter { it.source == WordlistSource.AOSP }
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/data/en/en_aosp.txt.gz",
                "https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/data/en/en_gb_aosp.txt.gz",
                "https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/data/en/en_ca_aosp.txt.gz",
                "https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/data/en/en_au_aosp.txt.gz",
            ),
            en.map { it.url },
        )
    }
}

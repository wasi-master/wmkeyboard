package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The set a language offers to download, and the switch that decides whether
 * any of it arrives unasked.
 *
 * The prompt shown as a language is added is built entirely out of
 * [languageData], so what it lists and the size it quotes are these numbers;
 * a pack left out of [LanguageData] is a pack that downloads without ever
 * appearing in the question.
 */
class LanguageDataTest {

    @Test
    fun `english offers all three kinds of data`() {
        val data = languageData("en")
        assertNotNull("English has a downloadable word list", data.wordlist)
        assertNotNull("English has an emoji keyword pack", data.emojiDict)
        assertNotNull("English has an n-gram pack", data.ngram)
        assertFalse(data.isEmpty)
    }

    @Test
    fun `the quoted size covers every pack, not only the word list`() {
        val data = languageData("en")
        val list = data.wordlist!!
        // The prompt fetches the Large tier, so it pays for that share of the
        // published file rather than all of it.
        val wordlistBytes = DictionaryCatalog.approxBytes(list, DictionaryCatalog.DictionarySize.LARGE)
        assertTrue(wordlistBytes in 1..list.approxGzBytes)
        // The size on the dialog is what the user agrees to spend, so a pack
        // left out of the total is a download they were never asked about.
        assertEquals(
            wordlistBytes + data.emojiDict!!.approxGzBytes + data.ngram!!.approxGzBytes,
            data.bytes,
        )
    }

    @Test
    fun `a language with nothing to fetch is empty, so no dialog is shown`() {
        assertTrue(LanguageData(null, null, null, 0L).isEmpty)
    }

    @Test
    fun `every n-gram pack can quote a size`() {
        for (entry in NgramPackCatalog.entries) {
            assertTrue(
                "${entry.languageId} has no size for the prompt",
                entry.approxGzBytes > 0,
            )
        }
    }

    @Test
    fun `automatic downloads follow the channel`() {
        // Off would leave a fresh install predicting worse than it can, which
        // is not what someone who never opened Settings asked for either — so
        // on, everywhere but F-Droid. There the listing says the network is
        // something you open, reviewers check that against a capture taken from
        // first launch, and the prompt when a language is added still offers
        // every one of these downloads.
        assertEquals(
            !BuildConfig.ENABLE_FDROID,
            KeyboardSettings().autoDownloadLanguageData,
        )
    }
}

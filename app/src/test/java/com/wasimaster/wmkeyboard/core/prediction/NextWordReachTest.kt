package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The next-word controls are settings of knobs the engine already had: which
 * follower sources the empty strip reads, and how many followers carry into
 * a typed word's strip.
 */
class NextWordReachTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun corpus(): NgramPack {
        val file = File(temp.root, "ngrams.wmng")
        ByteArrayOutputStream().use { bytes ->
            val pack = NgramPackBuilder().apply {
                addBigram("of", "the", 204351)
                addBigram("of", "a", 50000)
                addBigram("of", "course", 40000)
                addBigram("of", "time", 30000)
            }.build()
            NgramPackCodec.write(pack, bytes)
            file.writeBytes(bytes.toByteArray())
        }
        return NgramPack.of(MappedNgramPack.open(file) ?: error("failed to map the pack"))
    }

    private fun engine(lexicon: UserLexicon = UserLexicon(null)): SuggestionEngine {
        val trie = Trie().apply {
            insert("tip", 120)
            insert("tap", 110)
            insert("course", 90)
        }
        return SuggestionEngine(trie, BengaliPhoneticIndex(emptyList()), lexicon).apply { ngramPack = corpus() }
    }

    @Test
    fun aPairTypedOnceWaitsBehindTheCorpus() {
        // One use is not a habit: what the corpus expects comes first until
        // the pair is typed again, or every word ever typed after a word
        // would lead the strip after it.
        val lexicon = UserLexicon(null)
        lexicon.learnBigram("of", "zebra")
        val engine = engine(lexicon)
        val once = engine.suggest("", previousWord = "of", limit = 5)
        assertEquals("the", once.first())
        assertTrue(once.toString(), "zebra" in once)
        lexicon.learnBigram("of", "zebra")
        assertEquals("zebra", engine.suggest("", previousWord = "of", limit = 5).first())
    }

    @Test
    fun normalIsWhatTheStripAlwaysOffered() {
        val engine = engine()
        assertEquals(NextWordReach.NORMAL, engine.nextWordReach)
        assertEquals(listOf("the", "a", "course", "time"), engine.suggest("", previousWord = "of"))
    }

    @Test
    fun offPredictsNothingAfterASpace() {
        val lexicon = UserLexicon(null)
        repeat(3) { lexicon.learnBigram("of", "course") }
        val engine = engine(lexicon).apply { nextWordReach = NextWordReach.OFF }
        assertEquals(emptyList<String>(), engine.suggest("", previousWord = "of"))
    }

    @Test
    fun fewerReadsOnlyTheUsersOwnFollowers() {
        val lexicon = UserLexicon(null)
        val engine = engine(lexicon).apply { nextWordReach = NextWordReach.FEWER }
        // The corpus pack alone says nothing at this level.
        assertEquals(emptyList<String>(), engine.suggest("", previousWord = "of"))
        repeat(3) { lexicon.learnBigram("of", "course") }
        assertEquals(listOf("course"), engine.suggest("", previousWord = "of"))
    }

    /**
     * What the empty strip showed stays while its letters are typed, whatever
     * predicted it (0.6.8): `the`, on a two-chip strip after `of`, is still
     * there after `t`. The corpus pack's deeper guesses, the ones not shown,
     * are carried only at MORE: `time` is fourth after `of`.
     */
    @Test
    fun moreCarriesCorpusFollowersIntoATypedWord() {
        val normal = engine().suggest("t", previousWord = "of", limit = 8, phoneticSlots = 2)
        assertTrue("the shown follower dropped at NORMAL: $normal", normal.any { it.equals("the", true) })
        assertFalse("an unshown corpus follower carried at NORMAL: $normal", normal.any { it.equals("time", true) })
        val more = engine().apply { nextWordReach = NextWordReach.MORE }
            .suggest("t", previousWord = "of", limit = 8, phoneticSlots = 2)
        assertTrue("the corpus follower missing at MORE: $more", more.any { it.equals("time", true) })
    }

    @Test
    fun everyLevelCarriesWhatItsNameSays() {
        assertEquals(0, NextWordReach.OFF.carry)
        assertTrue(NextWordReach.FEWER.carry < NextWordReach.NORMAL.carry)
        assertTrue(NextWordReach.NORMAL.carry < NextWordReach.MORE.carry)
        assertTrue(NextWordReach.FEWER.personalOnly && !NextWordReach.NORMAL.personalOnly)
        assertTrue(NextWordReach.NORMAL.carryPersonalOnly && !NextWordReach.MORE.carryPersonalOnly)
    }
}

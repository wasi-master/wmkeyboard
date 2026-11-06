package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Full pinyin decoded over every split worth reading (#580): `xian` as 先 and
 * as 西安, `nine` as 您饿 and as 你呢, a mistyped key repaired, the list ordered
 * by how much of the buffer each candidate covers, and the held candidate's
 * menu. Fixture dictionaries in the shipped pack's 0–1000 scale, as in
 * [JianpinTest].
 */
class PinyinGraphTest {

    private val syllables = setOf(
        "xi", "an", "xian", "ni", "ne", "nin", "e", "hao", "ha", "o", "wo", "bu", "bi", "xiang", "xu",
        "he", "huan", "men", "de", "ma", "fang", "fan", "gan", "ge", "qi",
    )

    @Before
    fun setUp() {
        reset()
        PinyinSyllables.valid = syllables
        Jianpin.index = Jianpin.build(syllables)
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf(
                "xian\t先\t960", "xian\t现\t950", "xian\t西安\t500", "xi\t西\t900", "xi\t喜\t880",
                "an\t安\t850", "nin\t您\t900", "e\t饿\t700", "ni\t你\t990", "ne\t呢\t950",
                "ha\t哈\t800", "o\t哦\t800", "hao\t好\t960", "nihao\t你好\t1000",
                "wo\t我\t996", "bu\t不\t995", "bi\t必\t700", "xu\t须\t600", "bixu\t必须\t800",
                "xiang\t想\t900", "xihuan\t喜欢\t900", "he\t和\t970", "buxiang\t不想\t300",
                "fangan\t方案\t700", "fang\t方\t900", "fan\t反\t800", "gan\t干\t800",
            ),
        )
    }

    @After
    fun tearDown() = reset()

    private fun reset() {
        CjkConfig.jianpin = true
        CjkConfig.fuzzyPinyin = false
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        CjkConfig.traditionalOutput = false
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.ngrams = CjkNgrams.EMPTY
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        PinyinSyllables.valid = emptySet()
        Jianpin.index = Jianpin.EMPTY
        CjkLearning.store = null
    }

    @Test
    fun `a syllable that is also two offers both readings`() {
        val cands = PinyinComposer.candidates("xian", 40)
        assertEquals(cands.toString(), "先", cands.first())
        assertTrue(cands.toString(), "西安" in cands)
        assertEquals(4, PinyinComposer.consumedFor("xian", "西安"))
        // The first half alone is a prefix commit, after everything that covers the buffer.
        assertEquals(2, PinyinComposer.consumedFor("xian", "西"))
        assertTrue(cands.indexOf("西") > cands.indexOf("西安"))
        // Typed with the apostrophe, one character cannot cover two syllables.
        assertFalse("先" in PinyinComposer.candidates("xi'an", 40))
        // Also where the first syllable ends in -ng: fang'an.
        assertTrue("方案" in PinyinComposer.candidates("fangan", 40))
    }

    @Test
    fun `two ways to place a boundary are both decoded`() {
        val cands = PinyinComposer.candidates("nine", 40)
        assertTrue(cands.toString(), "你呢" in cands)
        assertTrue(cands.toString(), "您饿" in cands)
        assertEquals(4, PinyinComposer.consumedFor("nine", "你呢"))
        assertEquals(2, PinyinComposer.consumedFor("nine", "你"))
        assertEquals(3, PinyinComposer.consumedFor("nine", "您"))
    }

    @Test
    fun `a split into more syllables than needed is not offered`() {
        // ni + ha + o spells the same letters as ni + hao, one syllable longer.
        for (c in PinyinComposer.candidates("nihao", 40)) {
            assertTrue("$c", PinyinComposer.consumedFor("nihao", c) != 4)
        }
        assertEquals("你好", PinyinComposer.candidates("nihao").first())
    }

    @Test
    fun `a mistyped key next to the right one is repaired`() {
        // q sits beside a: xiqn is xian with a missed key.
        val cands = PinyinComposer.candidates("xiqn", 40)
        assertTrue(cands.toString(), "先" in cands)
        assertEquals(4, PinyinComposer.consumedFor("xiqn", "先"))
        // Nothing is repaired where the letters already spell syllables.
        assertFalse("先" in PinyinComposer.candidates("xi", 40))
    }

    // #1107: a thumb that meant the space bar and caught the row above.

    @Test
    fun `a letter typed for the space bar between syllables is left out`() {
        // Without Jianpin the `b` had no reading and nothing converted past it.
        CjkConfig.jianpin = false
        val cands = PinyinComposer.candidates("nihaobwo", 60)
        assertTrue(cands.toString(), "你好我" in cands)
        assertEquals(8, PinyinComposer.consumedFor("nihaobwo", "你好我"))
    }

    @Test
    fun `a bottom-row letter that starts a syllable is never left out`() {
        // `bu` is a syllable: nihaobu is 你好不, not 你好 with a slip.
        CjkConfig.jianpin = false
        val cands = PinyinComposer.candidates("nihaobu", 60)
        assertTrue(cands.toString(), "你好不" in cands)
        assertFalse(cands.toString(), cands.any { it == "你好" && PinyinComposer.consumedFor("nihaobu", it) == 7 })
    }

    @Test
    fun `candidates are ordered by how much of the buffer they cover`() {
        val cands = PinyinComposer.candidates("wbxh", 60)
        val covered = cands.map { PinyinComposer.consumedFor("wbxh", it) }
        assertEquals(cands.toString(), covered.sortedDescending(), covered)
        assertTrue(cands.toString(), "我不" in cands || "我必须" in cands)
    }

    @Test
    fun `a learned single character stays among the single characters`() {
        val store = CjkUserHistory(null)
        CjkLearning.store = store
        repeat(5) { store.learn("pinyin", "wo", "我") }
        val cands = PinyinComposer.candidates("wbxh", 60)
        // Picked before, so it leads its own group...
        val singles = cands.filter { PinyinComposer.consumedFor("wbxh", it) == 1 }
        assertEquals(cands.toString(), "我", singles.first())
        // ...but never jumps above the readings of all four letters (the issue's 我 first).
        assertEquals(cands.toString(), 4, PinyinComposer.consumedFor("wbxh", cands.first()))
    }

    @Test
    fun `a swipe spells readings and their initials`() {
        val spellings = PinyinGlide.spellings(CjkDictionaries.pinyin, syllables).toMap()
        // Every reading, at the frequency of its commonest word.
        assertEquals(960, spellings["xian"])
        assertEquals(990, spellings["ni"])
        // The initials of a reading of two syllables or more: `bx` for 必须.
        assertEquals(800, spellings["bx"])
        assertEquals(1000, spellings["nh"])
        // ...but not of one syllable, where the initial is just a letter.
        assertFalse("x" in spellings)
        assertTrue(PinyinGlide.spellings(ConversionDictionary.EMPTY, syllables).isEmpty())
    }

    @Test
    fun `the candidate menu blocks, demotes and promotes`() {
        val store = CjkUserHistory(null)
        CjkLearning.store = store
        val before = PinyinComposer.candidates("xian", 40)
        assertEquals("先", before[0])
        assertEquals("现", before[1])

        // Later: one step down, behind what it tied with.
        PinyinComposer.adjustChoice("xian", 0, CandidateAdjustment.DEMOTE)
        val demoted = PinyinComposer.candidates("xian", 40)
        assertTrue(demoted.toString(), demoted.indexOf("先") > demoted.indexOf("现"))

        // Earlier: picked once more, so it leads its reading again.
        PinyinComposer.adjustChoice("xian", demoted.indexOf("先"), CandidateAdjustment.PROMOTE)
        assertEquals("先", PinyinComposer.candidates("xian", 40).first())

        // Never: gone, and gone from every path built through it.
        PinyinComposer.adjustChoice("xian", 0, CandidateAdjustment.BLOCK)
        val blocked = PinyinComposer.candidates("xian", 40)
        assertFalse(blocked.toString(), "先" in blocked)
        assertFalse(PinyinComposer.candidates("xianhao", 40).any { it.startsWith("先") })

        // Clearing the learned history clears what the menu said too.
        store.clear()
        assertEquals("先", PinyinComposer.candidates("xian", 40).first())
    }
}

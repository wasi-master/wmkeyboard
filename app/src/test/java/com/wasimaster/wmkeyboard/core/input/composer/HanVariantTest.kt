package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Traditional output and the Taiwan/Hong Kong layers on top of it. */
class HanVariantTest {

    private fun asset(name: String): Sequence<String> =
        File("src/main/assets/dictionaries/$name").readText().lineSequence()

    @Before
    fun reset() {
        CjkConfig.traditionalOutput = false
        CjkConfig.kyujitaiOutput = false
        HanVariant.jpPhrases = HanPhraseTable.EMPTY
        HanVariant.jpCharacters = HanPhraseTable.EMPTY
        HanVariant.region = HanVariant.HanRegion.GENERIC
        HanVariant.s2t = HanPhraseTable.EMPTY
        HanVariant.s2tPhrases = HanPhraseTable.EMPTY
        HanVariant.twPhrases = HanPhraseTable.EMPTY
        HanVariant.twVariantsPhrases = HanPhraseTable.EMPTY
        HanVariant.twVariants = HanPhraseTable.EMPTY
        HanVariant.hkVariantsPhrases = HanPhraseTable.EMPTY
        HanVariant.hkVariants = HanPhraseTable.EMPTY
        PinyinSyllables.valid = emptySet()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.ngrams = CjkNgrams.EMPTY
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        CjkLearning.store = null
    }

    @After
    fun tearDown() = reset()

    private fun table(name: String) = HanPhraseTable.parse(asset(name))

    /**
     * Rows of the downloadable `s2t_phrases` pack, verbatim. The pack lives in
     * the data repository, not the APK, so the tests carry the rows they need.
     */
    private val phrasePack = sequenceOf(
        "头发\t頭髮",
        "发展\t發展",
        "皇后\t皇后",
        "后来\t後來",
        "干净\t乾淨",
        "干部\t幹部",
        "出租车\t出租車",
    )

    private fun loadShipped() {
        HanVariant.s2t = table("s2t.txt")
        HanVariant.s2tPhrases = HanPhraseTable.parse(phrasePack)
        HanVariant.twPhrases = table("tw_phrases.txt")
        HanVariant.twVariantsPhrases = table("tw_variants_phrases.txt")
        HanVariant.twVariants = table("tw_variants.txt")
        HanVariant.hkVariantsPhrases = table("hk_variants_phrases.txt")
        HanVariant.hkVariants = table("hk_variants.txt")
        assertTrue("s2t missing", HanVariant.s2t.size > 3000)
        assertTrue("tw phrases missing", HanVariant.twPhrases.size > 500)
        assertTrue("tw variants missing", !HanVariant.twVariants.isEmpty())
        assertTrue("hk variants missing", !HanVariant.hkVariants.isEmpty())
    }

    @Test
    fun `the longest key wins, and a key needs the whole of itself`() {
        val t = HanPhraseTable.of(mapOf("a" to "1", "ab" to "2", "abcd" to "4", "b" to "5"))
        assertEquals(1, t.keyLength(t.matchPrefix("axcd", 0, 4)))
        assertEquals(2, t.keyLength(t.matchPrefix("abc", 0, 3)))
        assertEquals(4, t.keyLength(t.matchPrefix("abcde", 0, 5)))
        // The end bound is respected: "abcd" is there, but only "ab" fits.
        assertEquals(2, t.keyLength(t.matchPrefix("abcd", 0, 2)))
        assertEquals(1, t.keyLength(t.matchPrefix("xb", 1, 2)))
        assertEquals(-1, t.matchPrefix("xyz", 0, 3))
        assertEquals(-1, HanPhraseTable.EMPTY.matchPrefix("a", 0, 1))
    }

    @Test
    fun `japanese old forms follow the word`() {
        HanVariant.jpPhrases = table("jp_shinjitai_phrases.txt")
        HanVariant.jpCharacters = table("jp_shinjitai_characters.txt")
        // Off: nothing changes, the same instance comes back.
        val plain = "学校"
        assertTrue(HanVariant.toKyujitai(plain) === plain)
        CjkConfig.kyujitaiOutput = true
        assertEquals("學校", HanVariant.toKyujitai("学校"))
        assertEquals("國會", HanVariant.toKyujitai("国会"))
        // 弁 has three old forms; the word picks one.
        assertEquals("辯論", HanVariant.toKyujitai("弁論"))
        assertEquals("辨當", HanVariant.toKyujitai("弁当"))
        // Kana pass through untouched.
        assertEquals("がっこう", HanVariant.toKyujitai("がっこう"))
        // Chinese Traditional output is a separate switch.
        assertEquals("学校", HanVariant.toTraditional("学校"))
    }

    @Test
    fun `the word decides the character`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        for (region in HanVariant.HanRegion.entries) {
            HanVariant.region = region
            // The three the issue reports, and the words that want the other form.
            assertEquals("$region", "頭髮", HanVariant.toTraditional("头发"))
            assertEquals("$region", "發展", HanVariant.toTraditional("发展"))
            assertEquals("$region", "皇后", HanVariant.toTraditional("皇后"))
            assertEquals("$region", "後來", HanVariant.toTraditional("后来"))
            assertEquals("$region", "乾淨", HanVariant.toTraditional("干净"))
            assertEquals("$region", "幹部", HanVariant.toTraditional("干部"))
        }
    }

    @Test
    fun `without the phrase pack it converts one character at a time`() {
        loadShipped()
        HanVariant.s2tPhrases = HanPhraseTable.EMPTY
        CjkConfig.traditionalOutput = true
        // The bundled character table alone: the most common form, right or not.
        assertEquals("發展", HanVariant.toTraditional("发展"))
        assertEquals("頭發", HanVariant.toTraditional("头发"))
    }

    @Test
    fun `a stitched candidate is segmented, not looked up whole`() {
        // No table has 头发干净 as one word; the lattice stitches it from two.
        loadShipped()
        CjkConfig.traditionalOutput = true
        assertEquals("頭髮乾淨", HanVariant.toTraditional("头发干净"))
        assertEquals("皇后後來", HanVariant.toTraditional("皇后后来"))
        HanVariant.region = HanVariant.HanRegion.TAIWAN_WORDING
        assertEquals("計程車光碟", HanVariant.toTraditional("出租车光盘"))
    }

    @Test
    fun `characters outside the basic plane pass through whole`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        assertEquals("𠀀發展", HanVariant.toTraditional("𠀀发展"))
    }

    @Test
    fun `regional character preferences follow the region`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        // 为 → 爲 in OpenCC's standard set; Taiwan and Hong Kong both write 為.
        assertEquals("爲", HanVariant.toTraditional("为"))
        HanVariant.region = HanVariant.HanRegion.TAIWAN
        assertEquals("為", HanVariant.toTraditional("为"))
        HanVariant.region = HanVariant.HanRegion.HONG_KONG
        assertEquals("為", HanVariant.toTraditional("为"))
        // Hong Kong's own: 说 → 說 → 説. Taiwan keeps 說.
        assertEquals("説", HanVariant.toTraditional("说"))
        HanVariant.region = HanVariant.HanRegion.TAIWAN
        assertEquals("說", HanVariant.toTraditional("说"))
    }

    @Test
    fun `a table assignment invalidates cached candidates`() {
        val before = CjkDictionaries.generation
        HanVariant.s2tPhrases = HanPhraseTable.of(mapOf("a" to "b"))
        assertTrue(CjkDictionaries.generation != before)
    }

    @Test
    fun `the toggle off is the identity whatever the region`() {
        loadShipped()
        HanVariant.region = HanVariant.HanRegion.TAIWAN
        assertEquals("出租车", HanVariant.toTraditional("出租车"))
    }

    @Test
    fun `generic converts characters but not vocabulary`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        assertEquals("出租車", HanVariant.toTraditional("出租车"))
    }

    @Test
    fun `taiwan wording swaps the word, not just the characters`() {
        // The whole point of the opt-in layer: 出租车 in Traditional characters
        // is 出租車, which Taipei calls 計程車.
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.TAIWAN_WORDING
        assertEquals("計程車", HanVariant.toTraditional("出租车"))
        assertEquals("光碟", HanVariant.toTraditional("光盘"))
    }

    @Test
    fun `plain taiwan keeps the typed word (#641)`() {
        // s2tw, not s2twp: Taiwan character forms, but the word is the one
        // whose reading was typed. 出租車 is chu zu che; 計程車 is ji cheng che.
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.TAIWAN
        assertEquals("出租車", HanVariant.toTraditional("出租车"))
        assertEquals("我支持這個決定", HanVariant.toTraditional("我支持这个决定"))
        assertEquals("法律文件", HanVariant.toTraditional("法律文件"))
        assertEquals("手術程序", HanVariant.toTraditional("手术程序"))
        // Taiwan's character forms still apply.
        assertEquals("為", HanVariant.toTraditional("为"))
        assertEquals("頭髮", HanVariant.toTraditional("头发"))
    }

    @Test
    fun `plain taiwan offers the typed word, not its synonym`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.TAIWAN
        PinyinSyllables.valid = setOf("chu", "zu", "che")
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf("chuzuche\t出租车\t500", "chu\t出\t900"),
        )
        val candidates = PinyinComposer.candidates("chuzuche")
        assertTrue("got $candidates", "出租車" in candidates)
        assertTrue("got $candidates", "計程車" !in candidates)
        assertEquals(8, PinyinComposer.consumedFor("chuzuche", "出租車"))
    }

    @Test
    fun `a lone character offers every traditional form (#608)`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.TAIWAN
        // The conversion first, then OpenCC's other values for the character.
        assertEquals(listOf("發", "髮"), HanVariant.traditionalForms("发"))
        assertTrue("乾" in HanVariant.traditionalForms("干"))
        assertTrue("干" in HanVariant.traditionalForms("干"))
        assertEquals(listOf("後", "后"), HanVariant.traditionalForms("后"))
        // Taiwan writes 裡 for the first form; 里 itself stays.
        assertEquals("裡", HanVariant.traditionalForms("里").first())
        assertTrue("里" in HanVariant.traditionalForms("里"))
        assertTrue("鐘" in HanVariant.traditionalForms("钟"))
        // A word keeps the one form its phrase decides.
        assertEquals(listOf("頭髮"), HanVariant.traditionalForms("头发"))
        // Off, it is the candidate itself.
        CjkConfig.traditionalOutput = false
        assertEquals(listOf("发"), HanVariant.traditionalForms("发"))
    }

    @Test
    fun `pinyin offers the other forms and commits the picked one (#608)`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.TAIWAN
        PinyinSyllables.valid = setOf("fa", "tou")
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf("fa\t发\t900", "fa\t法\t800", "toufa\t头发\t500", "tou\t头\t700"),
        )
        val fa = PinyinComposer.candidates("fa")
        assertTrue("got $fa", fa.indexOf("發") < fa.indexOf("髮"))
        assertTrue("got $fa", "法" in fa)
        assertEquals(2, PinyinComposer.consumedFor("fa", "髮"))
        val toufa = PinyinComposer.candidates("toufa")
        assertEquals("頭髮", toufa.first())
        assertTrue("got $toufa", "頭發" !in toufa)
    }

    @Test
    fun `hong kong keeps standard vocabulary`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.HONG_KONG
        // The Taiwan phrase table is Taiwan's; Hong Kong shares the standard
        // wording and differs in character preferences.
        assertEquals("出租車", HanVariant.toTraditional("出租车"))
    }

    @Test
    fun `a word in no table passes through unchanged`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.TAIWAN
        assertEquals("你好", HanVariant.toTraditional("你好"))
    }

    @Test
    fun `regional conversion does not disturb prefix commit`() {
        // The layer changes a candidate's length (出租車 → 計程車 is the same
        // length, but 中間件 → 中介軟體 is not). consumedFor must keep reporting
        // the *input* it covers, which comes from the reading, not the output.
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.TAIWAN_WORDING
        PinyinSyllables.valid = setOf("chu", "zu", "che")
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf("chuzuche\t出租车\t500", "chu\t出\t900"),
        )
        val candidates = PinyinComposer.candidates("chuzuche")
        assertTrue("got $candidates", "計程車" in candidates)
        assertEquals(8, PinyinComposer.consumedFor("chuzuche", "計程車"))
        candidates.forEachIndexed { index, text ->
            assertEquals(text, PinyinComposer.consumedFor("chuzuche", text), PinyinComposer.consumedForIndex("chuzuche", index))
        }
    }

    @Test
    fun `a length-changing phrase still commits its own reading`() {
        loadShipped()
        CjkConfig.traditionalOutput = true
        HanVariant.region = HanVariant.HanRegion.TAIWAN_WORDING
        // 中間件 (3 chars) becomes 中介軟體 (4) — output length and input length
        // have nothing to do with each other, which is exactly the invariant.
        assertEquals("中介軟體", HanVariant.toTraditional("中间件"))
        PinyinSyllables.valid = setOf("zhong", "jian", "jian2")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("zhongjian\t中间件\t500"))
        assertEquals(9, PinyinComposer.consumedFor("zhongjian", "中介軟體"))
    }
}

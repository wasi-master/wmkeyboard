package com.wasimaster.wmkeyboard.core.input.composer

import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What pinyin learned from libpinyin, libime and Rime: other spellings and
 * slips of a syllable, the unfinished last syllable, held-back repairs, the
 * character levels, phrases and word pairs learned from picks, the word
 * before the caret, what follows a commit, the typed shortcuts and the
 * stroke filter. Fixture dictionaries in the shipped pack's 0–1000 scale.
 */
class PinyinExtrasTest {

    private val syllables = setOf(
        "lve", "lv", "lu", "ju", "jue", "liu", "bang", "ba", "qing", "qi", "zhong", "zhou", "guo", "ren", "re",
        "nian", "niang", "niao", "ni", "a", "wo", "de", "ma", "ta", "ai", "shuo", "wu", "liao", "fa", "xi", "huan",
        "yi", "er", "san", "mei", "ge", "zhe",
    )

    @Before
    fun setUp() {
        reset()
        PinyinSyllables.valid = syllables
        Jianpin.index = Jianpin.build(syllables)
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf(
                "lve\t略\t800", "lv\t绿\t800", "lu\t路\t900", "ju\t句\t800", "jue\t觉\t850", "liu\t六\t900",
                "bang\t帮\t900", "ba\t把\t900", "qing\t请\t950", "qing\t情\t900", "qi\t起\t900",
                "zhong\t中\t980", "zhongguo\t中国\t990", "zhongguoren\t中国人\t900", "guo\t国\t900",
                "ren\t人\t950", "nian\t年\t960", "niang\t娘\t700", "niao\t鸟\t700", "ni\t你\t990", "a\t啊\t900",
                "wo\t我\t996", "de\t的\t999", "ma\t妈\t900", "mama\t妈妈\t800", "ta\t他\t950", "ta\t她\t940",
                "ai\t爱\t900", "shuo\t说\t900", "wu\t无\t900", "liao\t聊\t700", "wuliao\t无聊\t700",
                "yi\t一\t990", "yi\t乙\t500", "er\t二\t900", "san\t三\t900", "zhe\t这\t990",
                "qi\t琌\t990",
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
        CjkConfig.pinyinShortcuts = true
        CjkConfig.pinyinPrediction = true
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        CjkDictionaries.stroke = CodeTableDictionary.EMPTY
        CjkContext.before = ""
        HanLevels.table = HanLevels.EMPTY
        PinyinSyllables.valid = emptySet()
        Jianpin.index = Jianpin.EMPTY
        CjkLearning.store = null
        CjkLearning.enabled = true
        CjkNgrams.bigramWeight = 0.0
    }

    private fun cands(b: String, n: Int = 40) = PinyinComposer.candidates(b, n)

    // --- spellings ------------------------------------------------------------

    @Test
    fun `other spellings of a syllable read as that syllable`() {
        val tables = PinyinSpellings.build(syllables)
        assertEquals("lve", tables.aliases["lue"])
        assertEquals("ju", tables.aliases["jv"])
        assertEquals("jue", tables.aliases["jve"])
        assertEquals("liu", tables.aliases["liou"])
        assertEquals("bang", tables.aliases["bagn"])
        assertEquals("zhong", tables.aliases["zhon"])
        // Never a syllable in its own right: lu is lu.
        assertFalse("lu" in tables.aliases)

        assertEquals("略", cands("lue").first())
        assertEquals("句", cands("jv").first())
        assertEquals("六", cands("liou").first())
        assertEquals("帮", cands("bagn").first())
        assertEquals("中", cands("zhon").first())
        assertEquals(4, PinyinComposer.consumedFor("liou", "六"))
    }

    @Test
    fun `swapped, missing and extra keys are repaired`() {
        assertTrue(cands("qign").toString(), "请" in cands("qign"))
        assertEquals(4, PinyinComposer.consumedFor("qign", "请"))
        assertTrue(cands("zhng").toString(), "中" in cands("zhng"))
        assertTrue(cands("zhnog").toString(), "中" in cands("zhnog"))
    }

    @Test
    fun `the unfinished last syllable is read to its end`() {
        // `zho` is no syllable, only the start of zhong and zhou.
        assertTrue(cands("zho").toString(), "中" in cands("zho"))
        assertTrue(cands("guozho").toString(), "国中" in cands("guozho") || "国" in cands("guozho"))
        assertEquals(3, PinyinComposer.consumedFor("zho", "中"))
        val nia = cands("nia")
        assertTrue(nia.toString(), "年" in nia)
        // The letters as typed still read, too.
        assertTrue(nia.toString(), "你啊" in nia || "你" in nia)
    }

    // --- ranking --------------------------------------------------------------

    @Test
    fun `a repair far behind what was typed is not offered`() {
        val graph = Lattice.Graph(
            intArrayOf(0, 2),
            arrayOf(
                listOf(Lattice.Arc(1, "ni"), Lattice.Arc(1, "nian", -9.0, guess = true, correction = true)),
                emptyList(),
            ),
        )
        val dict = CjkDictionaries.pinyin
        val out = Lattice.decode(graph, dict, CjkNgrams.EMPTY, Lattice.Opts(coverageOrder = true)).map { it.text }
        assertTrue(out.toString(), "你" in out)
        assertFalse(out.toString(), "年" in out)
        // Close enough, and it is offered.
        val near = Lattice.Graph(
            intArrayOf(0, 2),
            arrayOf(listOf(Lattice.Arc(1, "ni"), Lattice.Arc(1, "nian", -0.5, guess = true, correction = true)), emptyList()),
        )
        assertTrue("年" in Lattice.decode(near, dict, CjkNgrams.EMPTY, Lattice.Opts(coverageOrder = true)).map { it.text })
    }

    @Test
    fun `characters outside the common set sink`() {
        assertEquals("琌", cands("qi").first())
        HanLevels.table = HanLevels.parse(sequenceOf("1\t起一二三", "2\t乙", "# comment"))
        assertEquals(1, HanLevels.table.levelOf('起'.code))
        assertEquals(2, HanLevels.table.levelOf('乙'.code))
        assertEquals(0, HanLevels.table.levelOf('琌'.code))
        assertTrue(HanLevels.penalty("琌") < HanLevels.penalty("乙"))
        assertEquals(0.0, HanLevels.penalty("起"), 0.0)
        assertEquals("起", cands("qi").first())
    }

    // --- learning -------------------------------------------------------------

    @Test
    fun `a buffer picked piece by piece is learned as a phrase`() {
        CjkLearning.store = CjkUserHistory(null)
        val b = "wodemama"
        assertFalse(cands(b).take(1).contains("我的妈妈") && false)
        PinyinComposer.learnChoice(b, cands(b).indexOf("我"))
        val rest = b.substring(PinyinComposer.consumedFor(b, "我"))
        PinyinComposer.learnChoice(rest, cands(rest).indexOf("的"))
        val last = rest.substring(PinyinComposer.consumedFor(rest, "的"))
        PinyinComposer.learnChoice(last, cands(last).indexOf("妈妈"))
        assertEquals(1, CjkLearning.store!!.countFor("pinyin", "wodemama", "我的妈妈"))
        assertEquals("我的妈妈", cands(b).first())
        // And the abbreviation finds the learned phrase too.
        assertTrue(cands("wdmm").toString(), "我的妈妈" in cands("wdmm"))
        // The pairs between the picks were learned on the way.
        assertEquals(listOf("的" to 1), CjkLearning.store!!.followersOf("pinyin", "我"))
    }

    @Test
    fun `the word before the caret decides between readings`() {
        val store = CjkUserHistory(null)
        CjkLearning.store = store
        assertEquals("他", cands("ta").first())
        repeat(3) { store.learnPair("pinyin", "说", "她") }
        CjkContext.before = "她说"
        assertEquals("她", cands("ta").first())
        CjkContext.before = "hello "
        assertEquals("他", cands("ta").first())
    }

    @Test
    fun `history fades unless it is used`() {
        val store = CjkUserHistory(null)
        store.learn("pinyin", "ta", "她")
        store.learn("pinyin", "ni", "你")
        store.learn("pinyin", "ni", "你")
        // Two full quiet periods for 她; 你 keeps being picked.
        repeat(2) {
            repeat(CjkUserHistory.AGE_EVERY.toInt()) { i -> if (i % 500 == 0) store.learn("pinyin", "ni", "你") else store.learnPair("x", "a", "b") }
        }
        assertEquals(0, store.countFor("pinyin", "ta", "她"))
        assertTrue(store.countFor("pinyin", "ni", "你") > 0)
    }

    // --- after a commit -------------------------------------------------------

    @Test
    fun `what usually follows is offered once a commit lands`() {
        CjkDictionaries.pinyinNgrams = CjkNgrams.parse(
            sequenceOf("我\t1000", "爱\t300", "说\t200", "无\t100", "无聊\t80", "无法\t60", "法\t50", "我\t爱\t120", "我\t说\t60"),
        )
        val after = PinyinComposer.predictions("今天我", 8)
        assertEquals(after.toString(), "爱", after.first())
        assertTrue(after.toString(), "说" in after)
        // The rest of a word it starts.
        assertTrue(PinyinComposer.predictions("无", 8).toString(), "聊" in PinyinComposer.predictions("无", 8))
        // A habit of the user's leads.
        CjkLearning.store = CjkUserHistory(null)
        PinyinComposer.learnPrediction("我", "说")
        assertEquals("说", PinyinComposer.predictions("我", 8).first())
        // Off, and after Latin text, nothing.
        assertTrue(PinyinComposer.predictions("hello", 8).isEmpty())
        CjkConfig.pinyinPrediction = false
        assertTrue(PinyinComposer.predictions("我", 8).isEmpty())
    }

    @Test
    fun `the pair model indexes followers and completions`() {
        val ngrams = CjkNgrams.parse(sequenceOf("a\t5", "b\t3", "ab\t2", "c\t1", "a\tb\t4", "a\tc\t9", "b\tc\t1"))
        assertEquals(listOf("c" to 9, "b" to 4), ngrams.successors("a"))
        assertEquals(listOf("ab" to 2), ngrams.completions("a", 5))
        assertTrue(ngrams.successors("zzz").isEmpty())
        // Interpolation is a finite score for an unseen pair, and higher for a seen one.
        CjkNgrams.bigramWeight = 0.3
        assertTrue(ngrams.logProbability("a", "c", 0, 0) > ngrams.logProbability("b", "a", 0, 0))
    }

    // --- shortcuts ------------------------------------------------------------

    @Test
    fun `numbers spell out in Chinese numerals`() {
        assertEquals("十", PinyinShortcuts.lower("10"))
        assertEquals("十二", PinyinShortcuts.lower("12"))
        assertEquals("一百一十二", PinyinShortcuts.lower("112"))
        assertEquals("一千零五", PinyinShortcuts.lower("1005"))
        assertEquals("十万零一十", PinyinShortcuts.lower("100010"))
        assertEquals("一亿零一", PinyinShortcuts.lower("100000001"))
        assertEquals("零", PinyinShortcuts.lower("0"))
        val n = PinyinShortcuts.numbers("v1234.5")
        assertEquals("一千二百三十四点五", n[0])
        assertEquals("壹仟贰佰叁拾肆点伍", n[1])
        assertTrue(n.toString(), "一二三四点五" in n)
        assertTrue("壹佰元整" in PinyinShortcuts.numbers("v100"))
        assertEquals(listOf("一百", "壹佰", "一〇〇", "壹佰元整", "100"), cands("v100"))
        assertEquals(4, PinyinComposer.consumedFor("v100", "一百"))
        assertTrue(PinyinComposer.buffersChar('5', "v1"))
        assertTrue(PinyinComposer.buffersChar('.', "v1"))
        assertFalse(PinyinComposer.buffersChar('.', "v1.5"))
        assertFalse(PinyinComposer.buffersChar('5', "ni"))
        assertFalse(PinyinComposer.buffersChar('5', ""))
    }

    @Test
    fun `date, time and weekday follow the reading's own best`() {
        val day = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 13, 14, 5) }
        assertEquals(listOf("2026年10月13日", "二〇二六年十月十三日", "2026-10-13", "10月13日"), PinyinShortcuts.expand("rq", day))
        assertEquals(listOf("14:05", "14点5分", "下午2点5分"), PinyinShortcuts.expand("sj", day))
        assertEquals(listOf("星期二", "周二", "礼拜二"), PinyinShortcuts.expand("xq", day))
        val rq = cands("rq")
        assertEquals(rq.toString(), 2, PinyinComposer.consumedFor("rq", rq[1]))
        assertTrue(rq[1].endsWith("日"))
        // Never learned.
        CjkLearning.store = CjkUserHistory(null)
        PinyinComposer.learnChoice("rq", 1)
        assertTrue(CjkLearning.store!!.isEmptyFor("pinyin"))
        CjkConfig.pinyinShortcuts = false
        assertFalse(cands("rq").any { it.endsWith("日") && it.length > 4 })
    }

    // --- stroke filter --------------------------------------------------------

    @Test
    fun `a stroke filter narrows the list to the strokes typed`() {
        CjkDictionaries.stroke = CodeTableDictionary.parse(
            sequenceOf("1\t一\t10", "5\t乙\t5", "11\t二\t8"),
            CodeTableDictionary.STROKE_CODE,
        )
        assertEquals(listOf("一", "乙"), cands("yi").filter { it == "一" || it == "乙" })
        assertEquals(listOf("乙"), cands("yi`z"))
        assertEquals(listOf("一"), cands("yi`h"))
        assertEquals("yi 乙", PinyinComposer.composeBuffer("yi`z"))
        // The filter goes with the pick.
        assertEquals(4, PinyinComposer.consumedFor("yi`z", "乙"))
        assertEquals("er", PinyinComposer.remainderAfterCommit("er`h"))
        assertTrue(PinyinComposer.buffersChar('`', "yi"))
        assertFalse(PinyinComposer.buffersChar('`', "yi`h"))
        assertFalse(PinyinComposer.buffersChar('`', ""))
    }

    @Test
    fun aConversionFromOutsideTheListIsLearnedUnderItsReading() {
        val store = CjkUserHistory(null)
        CjkLearning.store = store
        // A pair already held is what makes 我 a word the context can name.
        store.learnPair("pinyin", "我", "的")
        CjkContext.before = "我"
        PinyinComposer.learnConversion("wode", "窝德")
        assertEquals(1, store.countFor("pinyin", "wode", "窝德"))
        assertTrue(store.followersOf("pinyin", "我").contains("窝德" to 1))
    }

    @Test
    fun aConversionThatCannotBeThatReadingIsNotLearned() {
        val store = CjkUserHistory(null)
        CjkLearning.store = store
        // One character for two syllables, Latin, and keys that spell no reading.
        PinyinComposer.learnConversion("wode", "窝")
        PinyinComposer.learnConversion("wode", "wo德")
        PinyinComposer.learnConversion("wdmm", "窝德妈妈")
        assertEquals(0, store.countFor("pinyin", "wode", "窝"))
        assertEquals(0, store.countFor("pinyin", "wode", "wo德"))
        assertEquals(0, store.countFor("pinyin", "wdmm", "窝德妈妈"))
    }
}

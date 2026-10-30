package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Vietnamese Telex/VNI, Japanese romaji→kana, and the composer factory wiring. */
class CjkComposerTest {

    private val latin = ScriptRegistry[ScriptId.LATIN]
    private val japanese = ScriptRegistry[ScriptId.JAPANESE]
    private val han = ScriptRegistry[ScriptId.HAN]

    // The CJK conversion tables and syllable inventory are process globals; tests
    // that load them must not leak into the many tests that assume them empty.
    @Before
    @After
    fun resetCjkGlobals() {
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.japanese = ConversionDictionary.EMPTY
        PinyinSyllables.valid = emptySet()
        T9Pinyin.index = emptyMap()
        Jianpin.index = Jianpin.EMPTY
        ZhuyinSyllables.table = emptyMap()
        CjkDictionaries.cangjie = CodeTableDictionary.EMPTY
        CjkDictionaries.stroke = CodeTableDictionary.EMPTY
        CjkDictionaries.jyutping = ConversionDictionary.EMPTY
        JyutpingSyllables.valid = emptySet()
        CjkConfig.fuzzyPinyin = false
        CjkConfig.jianpin = true
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        CjkConfig.traditionalOutput = false
        CjkConfig.looseKanaMarks = false
        HanVariant.s2t = HanPhraseTable.EMPTY
    }

    @Test
    fun `factory maps the new composer types`() {
        assertSame(VietnameseTelexComposer, composerFor(latin, ComposerType.TELEX))
        assertSame(VietnameseVniComposer, composerFor(latin, ComposerType.VNI))
        assertSame(JapaneseComposer, composerFor(japanese, ComposerType.ROMAJI))
        assertSame(PinyinComposer, composerFor(han, ComposerType.PINYIN))
        assertSame(StrokeComposer, composerFor(han, ComposerType.STROKE))
        assertSame(T9PinyinComposer, composerFor(han, ComposerType.T9_PINYIN))
        assertSame(ZhuyinComposer, composerFor(han, ComposerType.ZHUYIN))
        assertSame(T9ZhuyinComposer, composerFor(han, ComposerType.T9_ZHUYIN))
        assertSame(CangjieComposer, composerFor(han, ComposerType.CANGJIE))
        assertSame(CangjieQuickComposer, composerFor(han, ComposerType.CANGJIE_QUICK))
        assertSame(WubiComposer, composerFor(han, ComposerType.WUBI))
        assertSame(JyutpingComposer, composerFor(han, ComposerType.JYUTPING))
        assertSame(RomanizedCantoneseComposer.YALE, composerFor(han, ComposerType.YALE))
        assertSame(RomanizedCantoneseComposer.CANTONESE_PINYIN, composerFor(han, ComposerType.CANTONESE_PINYIN))
    }

    /**
     * Every composer that converts has to be able to say which pack it is
     * waiting for, and it has to name one the catalog really carries — that
     * string is what the strip chip looks the pack's title up by, and a typo
     * would simply show no chip at all (#260).
     */
    @Test
    fun `a conversion composer names the catalog pack it is missing`() {
        for (type in ComposerType.entries) {
            val script = if (type == ComposerType.ROMAJI) japanese else han
            val composer = composerFor(script, type)
            if (!composer.isConversion) {
                assertNull("$type converts nothing and must ask for no pack", composer.missingPack)
                continue
            }
            val pack = composer.missingPack
            assertNotNull("$type converts but names no pack while its table is empty", pack)
            assertNotNull("$type names a pack the catalog does not have: $pack", CjkDictCatalog.byId(pack!!))
        }
    }

    /**
     * And it has to stop asking once the table is there. Pinyin, T9 and Zhuyin
     * read the same table, so one download answers for all three; Japanese
     * reads its own and keeps asking.
     */
    @Test
    fun `a loaded table stops naming its pack`() {
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("ni\tN1\t100"))

        assertNull(PinyinComposer.missingPack)
        assertNull(T9PinyinComposer.missingPack)
        assertNull(ZhuyinComposer.missingPack)
        assertEquals("ja_kana", JapaneseComposer.missingPack)
        assertEquals("cangjie", CangjieComposer.missingPack)
        assertEquals("cangjie", CangjieQuickComposer.missingPack)
        assertEquals("stroke", StrokeComposer.missingPack)
        assertEquals("jyutping", JyutpingComposer.missingPack)
    }

    @Test
    fun `telex applies tones and letter marks`() {
        val c = VietnameseTelexComposer
        assertEquals("á", c.composeBuffer("as"))
        assertEquals("à", c.composeBuffer("af"))
        assertEquals("ả", c.composeBuffer("ar"))
        assertEquals("ã", c.composeBuffer("ax"))
        assertEquals("ạ", c.composeBuffer("aj"))
        assertEquals("â", c.composeBuffer("aa"))
        assertEquals("ă", c.composeBuffer("aw"))
        assertEquals("đ", c.composeBuffer("dd"))
        assertEquals("ơ", c.composeBuffer("ow"))
        assertEquals("ư", c.composeBuffer("uw"))
    }

    @Test
    fun `telex composes whole syllables with the tone on the right vowel`() {
        val c = VietnameseTelexComposer
        assertEquals("việt", c.composeBuffer("vieejt"))
        assertEquals("tiếng", c.composeBuffer("tieengs"))
        assertEquals("đây", c.composeBuffer("ddaay"))
        // A repeated tone key cancels the tone and types the letter.
        assertEquals("as", c.composeBuffer("ass"))
    }

    @Test
    fun `vni uses digits for tones and marks`() {
        val c = VietnameseVniComposer
        assertEquals("á", c.composeBuffer("a1"))
        assertEquals("à", c.composeBuffer("a2"))
        assertEquals("â", c.composeBuffer("a6"))
        assertEquals("ă", c.composeBuffer("a8"))
        assertEquals("ô", c.composeBuffer("o6"))
        assertEquals("ơ", c.composeBuffer("o7"))
        assertEquals("ư", c.composeBuffer("u7"))
        assertEquals("đ", c.composeBuffer("d9"))
        assertEquals("việt", c.composeBuffer("viet65"))
    }

    @Test
    fun `vni consumes digit keys into the buffer`() {
        assertTrue(VietnameseVniComposer.bufferDigits)
    }

    @Test
    fun `romaji composes hiragana with sokuon and syllabic n`() {
        val c = JapaneseComposer
        assertEquals("こんにちは", c.composeBuffer("konnichiha"))
        assertEquals("ありがとう", c.composeBuffer("arigatou"))
        assertEquals("にほん", c.composeBuffer("nihon"))
        assertEquals("けっこん", c.composeBuffer("kekkon"))
        assertEquals("がっこう", c.composeBuffer("gakkou"))
        assertEquals("しんぶん", c.composeBuffer("shinbun"))
        assertEquals("おんな", c.composeBuffer("onna"))
        assertEquals("きょう", c.composeBuffer("kyou"))
        assertEquals("し", c.composeBuffer("shi"))
        assertEquals("つ", c.composeBuffer("tsu"))
    }

    @Test
    fun `japanese offers the kana and katakana as candidates when no dictionary is loaded`() {
        // In a plain JVM test the conversion tables are empty, so candidates are
        // just the reading itself in both kana (plus half-width katakana).
        val cands = JapaneseComposer.candidates("nihon")
        assertTrue("にほん" in cands)
        assertTrue("ニホン" in cands)
        assertTrue("ﾆﾎﾝ" in cands)
    }

    // --- Japanese conversion upgrade (Phase 5) ----------------------------------

    @Test
    fun `kana transducer tracks the romaji span behind each kana unit`() {
        // A yōon cluster is one kana unit produced from three romaji chars.
        val kya = Kana.transduce("kya")
        assertEquals(listOf("きゃ"), kya.map { it.kana })
        assertEquals(listOf(3), kya.map { it.romajiLen })

        // Sokuon + syllabic n each carry their own span; the totals reconcile.
        val kekkon = Kana.transduce("kekkon")
        assertEquals(listOf("け", "っ", "こ", "ん"), kekkon.map { it.kana })
        assertEquals(listOf(2, 1, 2, 1), kekkon.map { it.romajiLen })

        // The spans always sum back to the input length — no romaji char is lost
        // or double-counted, so a whole-buffer choice consumes the whole buffer.
        for (r in listOf("konnichiha", "arigatou", "nihon", "onna", "n'ya")) {
            assertEquals(r.length, Kana.transduce(r).sumOf { it.romajiLen })
            assertEquals(Kana.toHiragana(r), Kana.transduce(r).joinToString("") { it.kana })
        }
    }

    @Test
    fun `katakana converts to half-width including dakuten`() {
        assertEquals("ﾆﾎﾝ", Kana.toHalfWidthKatakana("ニホン"))
        assertEquals("ﾏｸﾄﾞﾅﾙﾄﾞ", Kana.toHalfWidthKatakana("マクドナルド"))
        // Dakuten/handakuten decompose to base + a half-width mark.
        assertEquals("ｶﾞ", Kana.toHalfWidthKatakana("ガ"))
        assertEquals("ﾊﾟ", Kana.toHalfWidthKatakana("パ"))
        assertEquals("ｳﾞ", Kana.toHalfWidthKatakana("ヴ"))
    }

    @Test
    fun `kana variant key reaches kana spelled in romaji`() {
        // Godan's 小゛゜ key: the romaji keys collapse into the marked kana.
        assertEquals("が", Kana.cycleLastKana("ka"))
        assertEquals("hiが", Kana.cycleLastKana("hika"))
        assertEquals("ば", Kana.cycleLastKana("ha"))
        assertEquals("っ", Kana.cycleLastKana("tsu"))
        assertEquals("ぁ", Kana.cycleLastKana("a"))
        assertEquals("きや", Kana.cycleLastKana("kya"))
        // A doubled consonant only read as っ because of what followed it.
        assertEquals("っが", Kana.cycleLastKana("kka"))
        // Kana typed as kana swaps in place, as it always did.
        assertEquals("が", Kana.cycleLastKana("か"))
        assertEquals("か", Kana.cycleLastKana("が"))
        assertEquals("づ", Kana.cycleLastKana("っ"))
        assertEquals("ガ", Kana.cycleLastKana("カ"))
        // In front of a caret inside the reading, not at its end.
        assertEquals("がな", Kana.cycleLastKana("かな", end = 1))
        assertEquals("がna", Kana.cycleLastKana("kana", end = 2))
        // Nothing to cycle: no kana yet, or one with no other form.
        assertEquals(null, Kana.cycleLastKana("k"))
        assertEquals(null, Kana.cycleLastKana("na"))
        assertEquals(null, Kana.cycleLastKana("n"))
        assertEquals(null, Kana.cycleLastKana(""))
    }

    @Test
    fun `kana variant key cycles small dakuten and handakuten forms`() {
        // Dakuten-only ring: か↔が.
        assertEquals('が', Kana.cycleVariant('か'))
        assertEquals('か', Kana.cycleVariant('が'))
        // は→ば→ぱ→は (dakuten then handakuten).
        assertEquals('ば', Kana.cycleVariant('は'))
        assertEquals('ぱ', Kana.cycleVariant('ば'))
        assertEquals('は', Kana.cycleVariant('ぱ'))
        // つ→っ→づ→つ (small then dakuten).
        assertEquals('っ', Kana.cycleVariant('つ'))
        assertEquals('づ', Kana.cycleVariant('っ'))
        assertEquals('つ', Kana.cycleVariant('づ'))
        // A kana with no variant cycles to itself, so the key is a no-op on it.
        assertEquals('な', Kana.cycleVariant('な'))
        assertEquals('ん', Kana.cycleVariant('ん'))
    }

    /**
     * A layout may put katakana straight on its keys, in which case the kana
     * reaches the buffer as katakana and the 小゛゜ key has to cycle it there
     * (issue #526). Same rings, one 0x60 offset up.
     */
    @Test
    fun `the kana variant key cycles katakana too`() {
        // Small-form ring: エ↔ェ.
        assertEquals('ェ', Kana.cycleVariant('エ'))
        assertEquals('エ', Kana.cycleVariant('ェ'))
        // Dakuten-only ring: カ↔ガ.
        assertEquals('ガ', Kana.cycleVariant('カ'))
        assertEquals('カ', Kana.cycleVariant('ガ'))
        // ハ→バ→パ→ハ.
        assertEquals('バ', Kana.cycleVariant('ハ'))
        assertEquals('パ', Kana.cycleVariant('バ'))
        assertEquals('ハ', Kana.cycleVariant('パ'))
        // ツ→ッ→ヅ→ツ and ウ→ゥ→ヴ→ウ, the three-form rings.
        assertEquals('ッ', Kana.cycleVariant('ツ'))
        assertEquals('ヅ', Kana.cycleVariant('ッ'))
        assertEquals('ツ', Kana.cycleVariant('ヅ'))
        assertEquals('ゥ', Kana.cycleVariant('ウ'))
        assertEquals('ヴ', Kana.cycleVariant('ゥ'))
        assertEquals('ウ', Kana.cycleVariant('ヴ'))
        // ワ→ヮ, the last ring, and the no-variant katakana stay put.
        assertEquals('ヮ', Kana.cycleVariant('ワ'))
        assertEquals('ナ', Kana.cycleVariant('ナ'))
        assertEquals('ン', Kana.cycleVariant('ン'))
        // Half-width katakana is not cycled: its marks are separate code points,
        // so a one-char swap cannot express ｶ → ｶﾞ.
        assertEquals('ｶ', Kana.cycleVariant('ｶ'))
    }

    @Test
    fun `japanese segments kana and reports consumed romaji length`() {
        // Kana readings are fine in tests; the words are ASCII stand-ins.
        CjkDictionaries.japanese = ConversionDictionary.parse(
            sequenceOf(
                "に\tN1\t100",
                "にほん\tNH\t50",
            ),
        )
        val cands = JapaneseComposer.candidates("nihon")
        // Whole-reading NH (にほん) outranks the leading-mora N1 (に).
        assertEquals("NH", cands[0])
        assertTrue("N1" in cands)
        // Plain kana forms remain available as trailing choices.
        assertTrue("にほん" in cands)
        assertTrue("ニホン" in cands)
        // consumedFor reports ROMAJI input chars, though segmentation is on kana:
        // にほん spans nihon (5), に spans ni (2).
        assertEquals(5, JapaneseComposer.consumedFor("nihon", "NH"))
        assertEquals(2, JapaneseComposer.consumedFor("nihon", "N1"))
        // A plain-kana or unknown choice consumes the whole buffer.
        assertEquals(5, JapaneseComposer.consumedFor("nihon", "にほん"))
        assertEquals(5, JapaneseComposer.consumedFor("nihon", "??"))
    }

    // --- Kana without marks (#293) ----------------------------------------------

    @Test
    fun `a plain kana lists the forms the variant key would make of it`() {
        assertEquals("が", Kana.markedForms('か'))
        assertEquals("ばぱ", Kana.markedForms('は'))
        assertEquals("っづ", Kana.markedForms('つ'))
        assertEquals("ゃ", Kana.markedForms('や'))
        // Nothing to add to a kana with no variant, or to one already marked.
        assertEquals("", Kana.markedForms('な'))
        assertEquals("", Kana.markedForms('が'))
        assertEquals("", Kana.markedForms('っ'))
        // Katakana is left out on purpose: `ja_kana` files its readings in
        // hiragana, so a katakana option would widen the lattice for nothing.
        assertEquals("", Kana.markedForms('カ'))
        assertEquals("", Kana.markedForms('ハ'))
    }

    /**
     * A table on the real pack's scale. `ja_kana` frequencies are `30000 - cost`,
     * so ordinary words sit between 22000 and 27000, and the pack's total is in
     * the billions — which is what makes a second word expensive. The filler row
     * supplies that total; three toy rows on their own would make 勝つ + 乞う
     * nearly free and say nothing about what a device does.
     */
    private fun kanaPack(vararg rows: String): ConversionDictionary =
        ConversionDictionary.parse(rows.asSequence() + "ん\tん\t2000000000")

    @Test
    fun `kana typed without marks finds the marked reading`() {
        CjkDictionaries.japanese = kanaPack(
            "がっこう\t学校\t26742", "かつこう\t葛洪\t23287", "かつ\t勝つ\t25000", "こう\t乞う\t25000",
        )
        assertTrue("off by default here", "学校" !in JapaneseComposer.candidates("かつこう"))

        CjkConfig.looseKanaMarks = true
        // The everyday word leads the given name spelled exactly, and it eats
        // the whole buffer: four kana, four input chars.
        val cands = JapaneseComposer.candidates("かつこう")
        assertEquals("学校", cands.first())
        assertEquals(4, JapaneseComposer.consumedFor("かつこう", "学校"))
        // Nothing the exact spelling offered has gone anywhere.
        assertTrue("葛洪" in cands)
        assertEquals(2, JapaneseComposer.consumedFor("かつこう", "勝つ"))
    }

    @Test
    fun `a missing mark is found anywhere in the word`() {
        // The fourth kana, past the two-unit cap a spelling guess gets elsewhere.
        CjkDictionaries.japanese = kanaPack("とうきょう\t東京\t26000")
        CjkConfig.looseKanaMarks = true
        assertEquals("東京", JapaneseComposer.candidates("とうきよう").first())
        assertEquals(5, JapaneseComposer.consumedFor("とうきよう", "東京"))
    }

    @Test
    fun `a reading typed exactly wins a near tie and loses a clear one`() {
        CjkConfig.looseKanaMarks = true
        CjkDictionaries.japanese = kanaPack("かき\t柿\t25000", "かぎ\t鍵\t25300")
        var cands = JapaneseComposer.candidates("かき")
        assertTrue("the exact reading leads", cands.indexOf("柿") < cands.indexOf("鍵"))

        CjkDictionaries.japanese = kanaPack("かき\t柿\t25000", "かぎ\t鍵\t26500")
        cands = JapaneseComposer.candidates("かき")
        assertTrue("the much commoner word leads", cands.indexOf("鍵") in 0 until cands.indexOf("柿"))
    }

    @Test
    fun `a typed mark is never taken back off`() {
        CjkDictionaries.japanese = ConversionDictionary.parse(sequenceOf("かき\t柿\t100"))
        CjkConfig.looseKanaMarks = true
        assertTrue("柿" !in JapaneseComposer.candidates("がき"))
        assertTrue("柿" !in JapaneseComposer.candidates("かぎ"))
    }

    @Test
    fun `romaji is never widened`() {
        // `ga` costs what `ka` does on a QWERTY layout, so `kaki` means かき.
        CjkDictionaries.japanese = ConversionDictionary.parse(
            sequenceOf("かき\t柿\t100", "かぎ\t鍵\t300"),
        )
        CjkConfig.looseKanaMarks = true
        val cands = JapaneseComposer.candidates("kaki")
        assertTrue("柿" in cands)
        assertTrue("鍵" !in cands)
    }

    @Test
    fun `pinyin shows the raw reading and is a conversion ime`() {
        assertTrue(PinyinComposer.isConversion)
        assertEquals("nihao", PinyinComposer.composeBuffer("nihao"))
        // No dictionary loaded → no character candidates, but it never crashes.
        assertEquals(emptyList<String>(), PinyinComposer.candidates("nihao"))
    }

    // --- Pinyin syllable segmentation (Phase 1) ---------------------------------
    // Dictionary "words" below are ASCII stand-ins (N1, NH, …): the segmenter and
    // consumed-length math are script-agnostic, so the tests need no real Hanzi.

    private val fixtureSyllables = setOf("ni", "hao", "xi", "an", "wo")

    /**
     * The real ~410-syllable inventory, read from disk the way
     * `zhuyin encodes every syllable the shipped inventory contains` does. The
     * backtracking cases only exist against the full set — a hand-written fixture
     * cannot reproduce which prefixes happen to collide.
     */
    private val shippedSyllables: Set<String> by lazy {
        val asset = File("src/main/assets/dictionaries/pinyin_syllables.txt").readText()
        PinyinSyllables.parse(asset.lineSequence())
            .also { assertTrue("inventory asset is missing or empty", it.size > 400) }
    }

    @Test
    fun `segmenter splits a multi-syllable buffer longest-first`() {
        val segs = PinyinSyllables.segment("nihao", fixtureSyllables)
        assertEquals(listOf("ni", "hao"), segs.map { it.syllable })
        assertEquals(listOf(2, 3), segs.map { it.inputLen })
    }

    @Test
    fun `segmenter backtracks instead of dropping the tail`() {
        // A greedy longest-match takes the -n/-ng final, then dead-ends on the
        // vowel-initial syllable that follows and returns only what it had — so
        // the trailing letters silently vanish from the candidate list. Each of
        // these is a real word that lost characters that way.
        val inventory = shippedSyllables
        for ((buffer, expected) in listOf(
            "sanguo" to listOf("san", "guo"),   // 三国, greedy: [sang], "uo" dropped
            "jinian" to listOf("ji", "nian"),   // 纪念, greedy: [jin], "ian" dropped
            "xianguo" to listOf("xian", "guo"), // greedy: [xiang], "uo" dropped
            "banian" to listOf("ba", "nian"),   // greedy: [ban], "ian" dropped
        )) {
            val segs = PinyinSyllables.segment(buffer, inventory)
            assertEquals(buffer, expected, segs.map { it.syllable })
            // Nothing may be left behind: the whole buffer is a real reading.
            assertEquals(buffer, buffer.length, segs.sumOf { it.inputLen })
        }
    }

    @Test
    fun `segmenter prefers the longest split that still completes`() {
        // Both ping|an and pin|gan cover the buffer, so the old longest-first
        // preference still decides — backtracking only overrides it when the
        // longer step cannot reach the end.
        val segs = PinyinSyllables.segment("pingan", shippedSyllables)
        assertEquals(listOf("ping", "an"), segs.map { it.syllable })
        assertEquals(6, segs.sumOf { it.inputLen })
    }

    @Test
    fun `segmenter input lengths sum to the segmented prefix`() {
        // The invariant prefix commit cannot survive without: consumedFor adds
        // these up to decide how much of the buffer to delete, so a span that
        // over- or under-counts eats the user's text.
        //
        // Paired against the vowel-initial syllables specifically, because those
        // are the ones a preceding -n/-ng/-r final can swallow the first letter
        // of — the shape that makes a greedy walk lose the tail. There are only
        // about a dozen: standard pinyin spells i-/u- initial syllables yi-/wu-.
        val inventory = shippedSyllables
        val vowelInitial = inventory.filter { it.first() in "aeo" }
        assertTrue("no vowel-initial syllables found", vowelInitial.size > 8)
        for (a in inventory) {
            for (b in vowelInitial) {
                val buffer = a + b
                val segs = PinyinSyllables.segment(buffer, inventory)
                val consumed = segs.sumOf { it.inputLen }
                assertTrue("$buffer consumed $consumed of ${buffer.length}", consumed <= buffer.length)
                // The spans tile the consumed prefix with no gap or overlap: with
                // no separators in play, the syllables are the buffer's own chars.
                assertEquals(buffer, buffer.take(consumed), segs.joinToString("") { it.syllable })
                // And a concatenation of two real syllables always segments whole.
                assertEquals(buffer, buffer.length, consumed)
            }
        }
    }

    @Test
    fun `segmenter folds an apostrophe boundary into the next syllable span`() {
        val segs = PinyinSyllables.segment("xi'an", fixtureSyllables)
        assertEquals(listOf("xi", "an"), segs.map { it.syllable })
        // The apostrophe counts toward "an"'s input length so a prefix commit
        // deletes it along with the syllable.
        assertEquals(listOf(2, 3), segs.map { it.inputLen })
    }

    @Test
    fun `segmenter stops at the first uncovered position`() {
        // "ni" is valid, "zz" is not: the trailing junk is left unconsumed.
        assertEquals(listOf("ni"), PinyinSyllables.segment("nizz", fixtureSyllables).map { it.syllable })
        assertEquals(emptyList<String>(), PinyinSyllables.segment("zz", fixtureSyllables).map { it.syllable })
    }

    @Test
    fun `pinyin ranks whole-phrase above leading syllable and reports consumed length`() {
        PinyinSyllables.valid = fixtureSyllables
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf(
                "ni\tN1\t100",
                "hao\tH1\t100",
                "nihao\tNH\t50",
            ),
        )
        // Whole-phrase NH (from nihao) outranks the single-syllable N1 (from ni).
        assertEquals(listOf("NH", "N1"), PinyinComposer.candidates("nihao"))
        // NH consumed the whole 5-char buffer; N1 consumed only the "ni" prefix.
        assertEquals(5, PinyinComposer.consumedFor("nihao", "NH"))
        assertEquals(2, PinyinComposer.consumedFor("nihao", "N1"))
        // An unknown candidate falls back to consuming the whole buffer.
        assertEquals(5, PinyinComposer.consumedFor("nihao", "??"))
    }

    /**
     * The dictionary keys a *joined* reading, so `xian` covers both the syllable
     * xiàn and the two-syllable place name 西安 — which sits 81st of 82 by
     * frequency in the shipped pack and so never reached a three-slot strip.
     * Typing the boundary is the user saying which one they meant, and the
     * syllable count is the only thing that carries that intent to the ranking.
     */
    @Test
    fun `an apostrophe boundary surfaces the phrase over commoner single syllables`() {
        PinyinSyllables.valid = setOf("xi", "an", "xian")
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf(
                "xian\t现\t911",
                "xian\t见\t860",
                "xian\t先\t789",
                "xian\t西安\t7",
            ),
        )
        // Two syllables spelled out → the two-character word leads.
        assertEquals("西安", PinyinComposer.candidates("xi'an").first())
        // Typed as one syllable, the common single characters keep their order.
        assertEquals(listOf("现", "见", "先", "西安"), PinyinComposer.candidates("xian"))
        // The phrase still consumes the whole buffer, apostrophe included.
        assertEquals(5, PinyinComposer.consumedFor("xi'an", "西安"))
    }

    @Test
    fun `pinyin without a loaded inventory falls back to whole-buffer lookup`() {
        // No syllable inventory → no segmentation → dictionary prefix matching,
        // each candidate consuming the whole buffer (old behaviour, no regression).
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("ni\tN1\t100"))
        assertEquals(listOf("N1"), PinyinComposer.candidates("ni"))
        assertEquals(2, PinyinComposer.consumedFor("ni", "N1"))
    }

    // --- Fuzzy Pinyin (Phase 3) -------------------------------------------------

    @Test
    fun `fuzzy expands a syllable to valid variants only`() {
        val valid = setOf("shang", "shan", "sang", "san", "shi", "si")
        val e = PinyinFuzzy.expand("shang", valid)
        assertTrue("shang" in e && "shan" in e && "sang" in e && "san" in e)
        // Never yields a non-syllable.
        assertTrue(e.all { it == "shang" || it in valid })
        // sh↔s only (no bogus final swaps).
        assertEquals(setOf("shi", "si"), PinyinFuzzy.expand("shi", valid))
    }

    @Test
    fun `pinyin composer fuzzy matches a confusable spelling`() {
        PinyinSyllables.valid = setOf("shi", "si")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("shi\tSH\t100"))
        // Off: "si" finds nothing.
        assertEquals(emptyList<String>(), PinyinComposer.candidates("si"))
        CjkConfig.fuzzyPinyin = true
        // On: "si" also looks up "shi".
        assertTrue("SH" in PinyinComposer.candidates("si"))
        assertEquals(2, PinyinComposer.consumedFor("si", "SH"))
    }

    // --- Double Pinyin (Phase 3, Xiaohe scheme) ---------------------------------

    @Test
    fun `double pinyin xiaohe translates key codes to full pinyin`() {
        val valid = setOf("ni", "hao", "zhu", "an", "ang")
        val t = DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
        assertEquals("nihao", DoublePinyin.translate("nihc", t, valid)) // ni + h+ao
        assertEquals("zhu", DoublePinyin.translate("vu", t, valid))     // v=zh + u
        // Zero-initial leads: a + final key.
        assertEquals("an", DoublePinyin.translate("aj", t, valid))
        assertEquals("ang", DoublePinyin.translate("ah", t, valid))
        // Each syllable spans two keys.
        val segs = DoublePinyin.segments("nihc", t, valid)
        assertEquals(listOf("ni", "hao"), segs.map { it.syllable })
        assertEquals(listOf(2, 2), segs.map { it.inputLen })
    }

    @Test
    fun `enter writes the keys pressed, the region shows the pinyin`() {
        // #514: the composing line reads nihao, Enter writes nihc.
        PinyinSyllables.valid = setOf("ni", "hao")
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        assertEquals("nihao", PinyinComposer.composeBuffer("nihc"))
        assertEquals("nihc", PinyinComposer.typedReading("nihc"))
        // #515: Japanese confirms the kana, never the romaji behind it.
        assertEquals("かな", JapaneseComposer.typedReading("kana"))
    }

    @Test
    fun `double pinyin survives an apostrophe`() {
        // An apostrophe means nothing in Double Pinyin — a syllable is always
        // two keys — but the user can still type one, and stepping blindly by two
        // would pair it with a real key and desync the parity of every syllable
        // after it, mistranslating the rest of the buffer.
        val valid = setOf("ni", "hao")
        val t = DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
        val segs = DoublePinyin.segments("ni'hc", t, valid)
        assertEquals(listOf("ni", "hao"), segs.map { it.syllable })
        // The skipped key is charged to the syllable it introduced, so a prefix
        // commit deletes exactly the three chars the user typed for "hao".
        assertEquals(listOf(2, 3), segs.map { it.inputLen })
        assertEquals(5, segs.sumOf { it.inputLen })
        // The preview agrees with the segmentation.
        assertEquals("nihao", DoublePinyin.translate("ni'hc", t, valid))
        // A dangling key after a separator still shows up raw.
        assertEquals("nih", DoublePinyin.translate("ni'h", t, valid))
    }

    @Test
    fun `double pinyin microsoft sogou ziranma and pinyinpp translate key codes to full pinyin`() {
        val valid = setOf("ni", "hao", "hui", "hu", "zhong", "xiang", "an", "lve", "chu", "shi")

        // Microsoft
        val ms = DoublePinyin.tableFor(DoublePinyinScheme.MICROSOFT)!!
        assertEquals("hui", DoublePinyin.translate("hv", ms, valid))   // h + v=ui
        assertEquals("hu", DoublePinyin.translate("hu", ms, valid))    // h + u=u
        assertEquals("zhong", DoublePinyin.translate("vs", ms, valid)) // v=zh + s=ong
        assertEquals("xiang", DoublePinyin.translate("xd", ms, valid)) // x + d=iang
        assertEquals("an", DoublePinyin.translate("oj", ms, valid))    // o=zero + j=an
        assertEquals("lve", DoublePinyin.translate("lt", ms, valid))   // l + t=ve

        // Sogou
        val sg = DoublePinyin.tableFor(DoublePinyinScheme.SOGOU)!!
        assertEquals("zhong", DoublePinyin.translate("vs", sg, valid))

        // Ziranma
        val zr = DoublePinyin.tableFor(DoublePinyinScheme.ZIRANMA)!!
        assertEquals("xiang", DoublePinyin.translate("xd", zr, valid))

        // PinyinPP
        val pp = DoublePinyin.tableFor(DoublePinyinScheme.PINYINPP)!!
        assertEquals("zhong", DoublePinyin.translate("vy", pp, valid)) // v=zh + y=ong
        assertEquals("xiang", DoublePinyin.translate("xh", pp, valid)) // x + h=iang
        assertEquals("an", DoublePinyin.translate("af", pp, valid))    // a=zero + f=an
        assertEquals("lve", DoublePinyin.translate("lx", pp, valid))   // l + x=ve
        assertEquals("chu", DoublePinyin.translate("uu", pp, valid))   // u=ch + u=u
        assertEquals("shi", DoublePinyin.translate("ii", pp, valid))   // i=sh + i=i
    }

    @Test
    fun `pinyin composer converts double pinyin and reports key-consumed length`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        CjkDictionaries.pinyin =
            ConversionDictionary.parse(sequenceOf("ni\tN1\t100", "hao\tH1\t100", "nihao\tNH\t50"))
        CjkConfig.doublePinyin = DoublePinyinScheme.XIAOHE
        // "nihc" (Xiaohe) → ni | hao.
        assertEquals("nihao", PinyinComposer.composeBuffer("nihc"))
        assertEquals(listOf("NH", "N1"), PinyinComposer.candidates("nihc"))
        // Consumed length is in KEYS: 2 per syllable.
        assertEquals(4, PinyinComposer.consumedFor("nihc", "NH"))
        assertEquals(2, PinyinComposer.consumedFor("nihc", "N1"))
    }

    // --- Zhuyin / Bopomofo 注音 ---------------------------------------------------

    @Test
    fun `zhuyin encodes pinyin syllables to bopomofo`() {
        assertEquals("ㄋㄧ", ZhuyinSyllables.encode("ni"))
        assertEquals("ㄏㄠ", ZhuyinSyllables.encode("hao"))
        assertEquals("ㄓㄨㄤ", ZhuyinSyllables.encode("zhuang"))
        // The seven whose written -i is no vowel at all: zhi is bare ㄓ.
        assertEquals("ㄓ", ZhuyinSyllables.encode("zhi"))
        assertEquals("ㄙ", ZhuyinSyllables.encode("si"))
        // Zero-initial y-/w- spellings carry the medial alone.
        assertEquals("ㄧ", ZhuyinSyllables.encode("yi"))
        assertEquals("ㄨㄥ", ZhuyinSyllables.encode("weng"))
        assertEquals("ㄩㄥ", ZhuyinSyllables.encode("yong"))
        // After j/q/x the letter u always spells ü.
        assertEquals("ㄐㄩ", ZhuyinSyllables.encode("ju"))
        assertEquals("ㄑㄩㄢ", ZhuyinSyllables.encode("quan"))
        // v is the inventory's spelling of ü elsewhere.
        assertEquals("ㄌㄩ", ZhuyinSyllables.encode("lv"))
        // Outside the standard set → unencodable, left out of the table.
        assertEquals("", ZhuyinSyllables.encode("hm"))
    }

    @Test
    fun `zhuyin encodes every syllable the shipped inventory contains`() {
        // The guardrail for the mapping rules: the bopomofo table is derived from
        // this asset at runtime, so a syllable the rules cannot spell would be
        // silently untypeable on the 注音 pad. Read from disk the way
        // AssetLayoutsTest reaches the shipped layouts.
        val asset = File("src/main/assets/dictionaries/pinyin_syllables.txt").readText()
        val inventory = PinyinSyllables.parse(asset.lineSequence())
        assertTrue("inventory asset is missing or empty", inventory.size > 400)
        val unencodable = inventory.filter { ZhuyinSyllables.encode(it).isEmpty() }.sorted()
        assertEquals("syllables with no bopomofo spelling: $unencodable", emptyList<String>(), unencodable)
        // And the mapping is injective — two pinyin syllables sharing a bopomofo
        // form would make one of them unreachable.
        val table = ZhuyinSyllables.buildTable(inventory)
        assertEquals(inventory.size, table.size)
    }

    @Test
    fun `zhuyin folds a tone mark into the syllable it follows`() {
        val table = ZhuyinSyllables.buildTable(setOf("ni", "hao"))
        val toned = ZhuyinSyllables.segment("ㄋㄧˇㄏㄠˇ", table)
        assertEquals(listOf("ni", "hao"), toned.map { it.pinyin })
        // The tone mark is counted in the span (so a prefix commit deletes it)
        // but never reaches the reading.
        assertEquals(listOf(3, 3), toned.map { it.inputLen })
        // Tone 1 is unmarked, so an untoned buffer still segments cleanly.
        val untoned = ZhuyinSyllables.segment("ㄋㄧㄏㄠ", table)
        assertEquals(listOf("ni", "hao"), untoned.map { it.pinyin })
        assertEquals(listOf(2, 2), untoned.map { it.inputLen })
    }

    @Test
    fun `zhuyin ranks whole-phrase above leading syllable and consumes bopomofo`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        ZhuyinSyllables.table = ZhuyinSyllables.buildTable(PinyinSyllables.valid)
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf("ni\tN1\t100", "hao\tH1\t100", "nihao\tNH\t50"),
        )
        assertEquals(listOf("NH", "N1"), ZhuyinComposer.candidates("ㄋㄧㄏㄠ"))
        // Consumed lengths are in BOPOMOFO chars, though lookup happened in pinyin.
        assertEquals(4, ZhuyinComposer.consumedFor("ㄋㄧㄏㄠ", "NH"))
        assertEquals(2, ZhuyinComposer.consumedFor("ㄋㄧㄏㄠ", "N1"))
        // Tone marks count toward the span they were typed into.
        assertEquals(6, ZhuyinComposer.consumedFor("ㄋㄧˇㄏㄠˇ", "NH"))
        assertEquals(3, ZhuyinComposer.consumedFor("ㄋㄧˇㄏㄠˇ", "N1"))
    }

    @Test
    fun `zhuyin without a table commits the raw bopomofo`() {
        assertEquals(emptyList<String>(), ZhuyinComposer.candidates("ㄋㄧ"))
        assertEquals("ㄋㄧ", ZhuyinComposer.composeBuffer("ㄋㄧ"))
    }

    // --- T9 / 九宫格 pinyin -------------------------------------------------------

    @Test
    fun `t9 encodes syllables to keypad digits`() {
        assertEquals("64", T9Pinyin.encode("ni"))
        assertEquals("426", T9Pinyin.encode("hao"))
        // z=9 h=4 a=2 n=6 g=4
        assertEquals("94264", T9Pinyin.encode("zhang"))
        // A letter the keypad cannot produce leaves the syllable unindexed.
        assertEquals("", T9Pinyin.encode("nü"))
    }

    @Test
    fun `t9 index groups syllables sharing a digit code`() {
        val index = T9Pinyin.buildIndex(setOf("ni", "mi", "hao", "o"))
        // 6=mno, 4=ghi → "64" spells both mi and ni.
        assertEquals(listOf("mi", "ni"), index["64"])
        assertEquals(listOf("hao"), index["426"])
        assertEquals(listOf("o"), index["6"])
    }

    @Test
    fun `t9 segments digit runs and reports consumed digits`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf(
                "ni\tN1\t100",
                "hao\tH1\t100",
                "nihao\tNH\t50",
            ),
        )
        // 64 → ni, 426 → hao, so 64426 is the whole phrase.
        assertEquals(listOf("NH", "N1"), T9PinyinComposer.candidates("64426"))
        // Consumed lengths are in DIGITS: the phrase spans all 5, "ni" only 2.
        assertEquals(5, T9PinyinComposer.consumedFor("64426", "NH"))
        assertEquals(2, T9PinyinComposer.consumedFor("64426", "N1"))
        assertEquals(5, T9PinyinComposer.consumedFor("64426", "??"))
        // The composing region reads back the pinyin of the best candidate, a
        // mark between syllables so the split the decoder chose shows (#635).
        assertEquals("ni'hao", T9PinyinComposer.composeBuffer("64426"))
        // Enter writes the letters, without the marks.
        assertEquals("nihao", T9PinyinComposer.typedReading("64426"))
    }

    @Test
    fun `t9 segmenter backtracks over digit codes`() {
        // The digit alphabet is denser than the letter one — 412 syllables
        // collapse onto ~230 codes over eight symbols — so a greedy walk loses
        // the tail more often here than in full pinyin, not less.
        // san=726, guo=486 → 726486; greedy takes sang=7264 and dead-ends on 86,
        // which spells no syllable, so the split has to be reconsidered.
        PinyinSyllables.valid = setOf("san", "guo", "sang")
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        val segs = PinyinSyllables.segment("726486", T9Pinyin.index.keys)
        assertEquals(listOf("726", "486"), segs.map { it.syllable })
        assertEquals(6, segs.sumOf { it.inputLen })
    }

    @Test
    fun `t9 disambiguates a code shared by several syllables`() {
        PinyinSyllables.valid = setOf("ni", "mi")
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        CjkDictionaries.pinyin =
            ConversionDictionary.parse(sequenceOf("ni\tN1\t100", "mi\tM1\t10"))
        // One digit run, both readings tried — the dictionary's frequency decides.
        val cands = T9PinyinComposer.candidates("64")
        assertTrue("N1" in cands)
        assertTrue("M1" in cands)
        assertEquals(2, T9PinyinComposer.consumedFor("64", "M1"))
    }

    /** xian/zhan share 9426, xi/yi/zi share 94, an/ao share 26 (issue #635). */
    private fun loadT9Readings() {
        PinyinSyllables.valid = setOf("xi", "yi", "zi", "xian", "zhan", "an", "ao")
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf(
                "xian\t现\t500",
                "zhan\t站\t100",
                "xi\t西\t300",
                "yi\t一\t900",
                "an\t安\t200",
                "xian\t西安\t50",
            ),
        )
    }

    @Test
    fun `t9 lists the readings of the next code, longest first, the decoder's own leading`() {
        loadT9Readings()
        // The decoder reads 9426 as xian (现), so xian leads; then the rest of
        // the four-key syllables, then the two-key ones by their commonest
        // character: 一 900, 西 300, and zi with none.
        assertEquals(listOf("xian", "zhan", "yi", "xi", "zi"), T9PinyinComposer.readings("9426"))
        // Nothing composing, nothing to list.
        assertEquals(emptyList<String>(), T9PinyinComposer.readings(""))
    }

    @Test
    fun `t9 picking a reading settles that syllable and moves on to the next`() {
        loadT9Readings()
        // xi for the first two keys: its letters replace its digits.
        assertEquals("xi26", T9PinyinComposer.pickReading("9426", "xi"))
        // The rest still converts, now only as xi + an/ao, so 西安 — which 9426
        // as one syllable ranked below 现 — leads, and 现 cannot be read from
        // two syllables at all.
        val cands = T9PinyinComposer.candidates("xi26")
        assertEquals("西安", cands.first())
        assertTrue("现" !in cands)
        assertEquals(4, T9PinyinComposer.consumedFor("xi26", "西安"))
        assertEquals("xi'an", T9PinyinComposer.composeBuffer("xi26"))
        assertEquals("xian", T9PinyinComposer.typedReading("xi26"))
        // The list has moved on to 26.
        assertEquals(listOf("an", "ao"), T9PinyinComposer.readings("xi26"))
        // A second pick is set off from the first, so it never reads as xian.
        assertEquals("xi'an", T9PinyinComposer.pickReading("xi26", "an"))
        assertEquals(emptyList<String>(), T9PinyinComposer.readings("xi'an"))
        assertEquals("西安", T9PinyinComposer.candidates("xi'an").first())
        assertEquals(5, T9PinyinComposer.consumedFor("xi'an", "西安"))
    }

    @Test
    fun `t9 refuses a reading that does not spell the next keys`() {
        loadT9Readings()
        assertNull(T9PinyinComposer.pickReading("9426", "an"))
        assertNull(T9PinyinComposer.pickReading("xi'an", "an"))
    }

    @Test
    fun `t9 backspace takes back a key, then a whole pick`() {
        loadT9Readings()
        assertEquals("xi2", T9PinyinComposer.backspaceBuffer("xi26"))
        // Only picks left: the last goes back to its digits, never half a syllable.
        assertEquals("xi26", T9PinyinComposer.backspaceBuffer("xi'an"))
        assertEquals("94", T9PinyinComposer.backspaceBuffer("xi"))
        assertEquals("942", T9PinyinComposer.backspaceBuffer("9426"))
    }

    @Test
    fun `t9 buffers digits from the very first keystroke`() {
        // Its whole alphabet is digits, so unlike VNI a digit must be able to
        // start the buffer — otherwise the first key of every word commits as a
        // literal number.
        assertTrue(T9PinyinComposer.bufferDigits)
        assertTrue(T9PinyinComposer.digitsStartBuffer)
        assertTrue(VietnameseVniComposer.bufferDigits)
        assertTrue(!VietnameseVniComposer.digitsStartBuffer)
    }

    @Test
    fun `t9 without an index commits the raw digits`() {
        // No inventory loaded → nothing segments → no candidates, and the buffer
        // shows the digits themselves rather than trapping the user.
        assertEquals(emptyList<String>(), T9PinyinComposer.candidates("64"))
        assertEquals("64", T9PinyinComposer.composeBuffer("64"))
    }

    // --- Cantonese Jyutping 粵拼 --------------------------------------------------

    private val jyutFixture = setOf("nei", "hou", "gwaang")

    @Test
    fun `jyutping folds a tone digit into the syllable it follows`() {
        val toned = JyutpingSyllables.segment("nei5hou2", jyutFixture)
        // The digit is counted in the span but never part of the reading, which
        // stays toneless to match the table.
        assertEquals(listOf("nei", "hou"), toned.map { it.syllable })
        assertEquals(listOf(4, 4), toned.map { it.inputLen })
        // Tones omitted behaves identically, one char shorter per syllable.
        val untoned = JyutpingSyllables.segment("neihou", jyutFixture)
        assertEquals(listOf("nei", "hou"), untoned.map { it.syllable })
        assertEquals(listOf(3, 3), untoned.map { it.inputLen })
        // Greedy longest match: the 6-char syllable wins over its prefixes.
        assertEquals(listOf("gwaang"), JyutpingSyllables.segment("gwaang", jyutFixture).map { it.syllable })
    }

    @Test
    fun `jyutping backtracks when a longer syllable would dead-end`() {
        // aakek (啞劇) is aa + kek, but aak is also a real syllable, so a greedy
        // longest-match takes aak and then dead-ends on the leftover ek — losing a
        // real word. Jyutping finals include -p/-t/-k, so a syllable can borrow the
        // first letter of a vowel-initial one that follows; the split has to be
        // searched, not guessed.
        val inv = setOf("aa", "aak", "kek")
        assertEquals(
            listOf("aa", "kek"),
            JyutpingSyllables.segment("aakek", inv).map { it.syllable },
        )
        assertEquals(listOf(2, 3), JyutpingSyllables.segment("aakek", inv).map { it.inputLen })
        // Where the longest match does work out it is still preferred.
        assertEquals(listOf("aak"), JyutpingSyllables.segment("aak", inv).map { it.syllable })
        // A trailing half-typed syllable is still left as raw input.
        assertEquals(listOf("aa"), JyutpingSyllables.segment("aaz", inv).map { it.syllable })
    }

    @Test
    fun `jyutping ranks whole-phrase above leading syllable and reports consumed input`() {
        JyutpingSyllables.valid = jyutFixture
        CjkDictionaries.jyutping = ConversionDictionary.parse(
            sequenceOf("nei\tN1\t100", "hou\tH1\t100", "neihou\tNH\t50"),
        )
        assertEquals(listOf("NH", "N1"), JyutpingComposer.candidates("neihou"))
        assertEquals(6, JyutpingComposer.consumedFor("neihou", "NH"))
        assertEquals(3, JyutpingComposer.consumedFor("neihou", "N1"))
        // With tones typed, consumed lengths include the digits so a prefix
        // commit deletes them along with their syllable.
        assertEquals(listOf("NH", "N1"), JyutpingComposer.candidates("nei5hou2"))
        assertEquals(8, JyutpingComposer.consumedFor("nei5hou2", "NH"))
        assertEquals(4, JyutpingComposer.consumedFor("nei5hou2", "N1"))
    }

    @Test
    fun `jyutping buffers tone digits but never starts a buffer with one`() {
        // Digits are tones applied to a syllable already being typed, so unlike
        // T9 a digit on an empty buffer stays a literal number.
        assertTrue(JyutpingComposer.bufferDigits)
        assertTrue(!JyutpingComposer.digitsStartBuffer)
    }

    @Test
    fun `jyutping without an inventory falls back to whole-buffer lookup`() {
        CjkDictionaries.jyutping = ConversionDictionary.parse(sequenceOf("nei\tN1\t100"))
        assertEquals(listOf("N1"), JyutpingComposer.candidates("nei"))
        assertEquals(3, JyutpingComposer.consumedFor("nei", "N1"))
    }

    // --- Traditional output (Phase 12) -------------------------------------------
    // Stand-ins: "S" maps to "T" the way a simplified char maps to a traditional
    // one, so the conversion is exercised without a Hanzi table.

    private fun loadS2t() {
        HanVariant.s2t = HanPhraseTable.parse(sequenceOf("S\tT", "X\tY"))
    }

    @Test
    fun `traditional conversion is identity when off or unloaded`() {
        loadS2t()
        // Loaded but toggled off.
        assertEquals("S", HanVariant.toTraditional("S"))
        // Toggled on but no map.
        HanVariant.s2t = HanPhraseTable.EMPTY
        CjkConfig.traditionalOutput = true
        assertEquals("S", HanVariant.toTraditional("S"))
        // Both: converts, and leaves unmapped characters alone.
        loadS2t()
        assertEquals("T", HanVariant.toTraditional("S"))
        assertEquals("TY", HanVariant.toTraditional("SX"))
        assertEquals("AB", HanVariant.toTraditional("AB"))
    }

    @Test
    fun `traditional parse keeps phrases, identity rows and the first value`() {
        val table = HanPhraseTable.parse(
            sequenceOf("S\tT", "A\tA", "AB\tCD", "Q\tR P", "# comment", "bad-line"),
        )
        assertEquals("T", table["S"])
        // Identity rows are phrase rules (皇后 → 皇后), not noise.
        assertEquals("A", table["A"])
        assertEquals("CD", table["AB"])
        // OpenCC lists alternatives after the first; the first is the conversion.
        assertEquals("R", table["Q"])
        assertEquals(null, table["bad-line"])
        assertEquals(4, table.size)
    }

    @Test
    fun `traditional output keeps prefix commit working`() {
        // The regression this phase exists to prevent: consumedFor finds the chosen
        // candidate by string equality against what ranked() produced. Convert the
        // list *after* the composer and the converted string matches nothing, so
        // consumedFor silently falls back to the whole buffer length and every
        // multi-syllable commit starts eating the tail.
        PinyinSyllables.valid = setOf("ni", "hao")
        // One character per syllable, as real Hanzi always is — the decoder drops
        // a word too short to cover its span, which is what makes `xi'an` mean
        // 西安 rather than 现.
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf("ni\tS\t100", "hao\tH\t100", "nihao\tSX\t50"),
        )
        loadS2t()
        CjkConfig.traditionalOutput = true
        // Candidates come back converted: SX→TY (whole phrase), S→T (leading).
        assertEquals(listOf("TY", "T"), PinyinComposer.candidates("nihao"))
        // And the converted strings still resolve to their real consumed lengths,
        // not the 5-char buffer-length fallback.
        assertEquals(5, PinyinComposer.consumedFor("nihao", "TY"))
        assertEquals(2, PinyinComposer.consumedFor("nihao", "T"))
    }

    @Test
    fun `traditional output converts a code-table composer too`() {
        CjkDictionaries.cangjie = CodeTableDictionary.parse(
            sequenceOf("a\tS\t10"),
            CodeTableDictionary.CANGJIE_CODE,
        )
        loadS2t()
        CjkConfig.traditionalOutput = true
        assertEquals(listOf("T"), CangjieComposer.candidates("a"))
        // One code run spells one character, so the whole buffer is consumed
        // whatever the candidate converted to.
        assertEquals(1, CangjieComposer.consumedFor("a", "T"))
    }

    @Test
    fun `traditional output leaves japanese alone`() {
        // Simplified/Traditional is a Chinese distinction; Japanese shinjitai is a
        // separate system this toggle must not touch.
        CjkDictionaries.japanese = ConversionDictionary.parse(sequenceOf("に\tS\t100"))
        loadS2t()
        CjkConfig.traditionalOutput = true
        assertTrue("S" in JapaneseComposer.candidates("ni"))
    }

    @Test
    fun `conversion dictionary parses tsv and ranks by frequency`() {
        val dict = ConversionDictionary.parse(
            sequenceOf(
                "ni\t你\t100",
                "ni\t尼\t10",
                "hao\t好\t100",
                "# comment",
                "nihao\t你好\t50",
            ),
        )
        assertEquals(listOf("你", "尼"), dict.exact("ni"))
        assertEquals(listOf("好"), dict.exact("hao"))
        assertTrue("你好" in dict.candidates("nihao"))
    }

    // #960: editing inside a reading, and punctuation joining it.

    @Test
    fun `japanese caret offsets follow the whole reading's kana`() {
        // `kana` is かな: the n starts な, so a caret after `ka` is one kana in
        // and a caret after `kan` is still one kana in, in front of な.
        assertEquals(1, JapaneseComposer.previewOffset("kana", 2))
        assertEquals(1, JapaneseComposer.previewOffset("kana", 3))
        assertEquals(2, JapaneseComposer.previewOffset("kana", 4))
        assertEquals(0, JapaneseComposer.previewOffset("kana", 0))
    }

    @Test
    fun `japanese caret in the field maps back to the keys in front of it`() {
        assertEquals(0, JapaneseComposer.bufferIndex("kana", 0))
        assertEquals(2, JapaneseComposer.bufferIndex("kana", 1))
        assertEquals(4, JapaneseComposer.bufferIndex("kana", 2))
        // Kana typed on a kana board is one key per kana.
        assertEquals(2, JapaneseComposer.bufferIndex("かつこう", 2))
    }

    @Test
    fun `japanese punctuation joins a reading and not an empty buffer`() {
        assertTrue(JapaneseComposer.buffersChar('。', "nihon"))
        assertTrue(JapaneseComposer.buffersChar('、', "kyou"))
        assertTrue(JapaneseComposer.buffersChar('-', "ka"))
        assertTrue(!JapaneseComposer.buffersChar('。', ""))
        assertTrue(!JapaneseComposer.buffersChar('「', "ka"))
        assertEquals("にほん。", JapaneseComposer.composeBuffer("nihon。"))
    }
}

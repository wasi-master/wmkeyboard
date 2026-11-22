package com.wasimaster.wmkeyboard.core.input.chewing

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngine
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngines
import com.wasimaster.wmkeyboard.core.script.ComposerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the Chewing engine: what [ChewingComposer] makes of
 * libchewing's answers, and how it falls back. libchewing itself is tested
 * in native/chewing-jni (`cargo test`); here a stand-in answers for it.
 */
class ChewingComposerTest {

    /** ㄋㄧˇㄏㄠˇ: 你好 as a sentence, 妳好 next, then the phrases at the front. */
    private val nihao = "ㄋㄧˇㄏㄠˇ"

    private class FakeConverter(var menu: ChewingRuntime.Menu?) : ChewingConverter {
        override var generation: Int = 0
        var asked = 0
        val learned = mutableListOf<String>()

        override fun convert(reading: String, limit: Int): ChewingRuntime.Menu? {
            asked++
            return menu
        }

        override fun learnPhrase(reading: String, consumed: Int, phrase: String) {
            learned += "phrase $reading $consumed $phrase"
        }

        override fun learnSentence(reading: String, sentence: Int) {
            learned += "sentence $reading $sentence"
        }
    }

    /** The built-in composer's part: keys as typed, and its own list for the fallback. */
    private object Base : Composer {
        val learned = mutableListOf<Int>()
        override val isTransliterating: Boolean get() = true
        override val isConversion: Boolean get() = true
        override fun buffersChar(c: Char): Boolean = c == 'ˊ'
        override fun backspaceBuffer(buffer: String): String = buffer.dropLast(2)
        override fun candidates(buffer: String): List<String> = listOf("基", "礎")
        override fun consumedForIndex(buffer: String, index: Int): Int = 1
        override fun learnChoice(buffer: String, index: Int) {
            learned += index
        }
    }

    private fun menu(
        texts: List<String> = listOf("你好", "妳好", "你", "妳"),
        consumed: IntArray = intArrayOf(6, 6, 3, 3),
        sentence: IntArray = intArrayOf(0, 1, -1, -1),
        preedit: String = "你好",
        tail: Int = 6,
    ) = ChewingRuntime.Menu(texts, consumed, sentence, preedit, tail)

    @Test
    fun `the strip is libchewing's list, sentence first, with what each consumes`() {
        val composer = ChewingComposer(Base, FakeConverter(menu()))
        assertEquals(listOf("你好", "妳好", "你", "妳"), composer.candidates(nihao))
        assertEquals(6, composer.consumedForIndex(nihao, 0))
        assertEquals(3, composer.consumedForIndex(nihao, 2))
        assertEquals(3, composer.consumedFor(nihao, "妳"))
        // A text not on the list takes the whole reading, as everywhere else.
        assertEquals(nihao.length, composer.consumedFor(nihao, "好"))
    }

    @Test
    fun `a shorter list is a prefix of a longer one`() {
        val texts = (0 until 40).map { "字$it" }
        val composer = ChewingComposer(
            Base,
            FakeConverter(menu(texts, IntArray(40) { 3 }, IntArray(40) { -1 })),
        )
        assertEquals(composer.candidates(nihao, 100).take(12), composer.candidates(nihao, 12))
        assertEquals(composer.candidates(nihao, 12), composer.candidates(nihao))
    }

    @Test
    fun `the composing text is the converted syllables and the open one's keys`() {
        // ㄋㄧˇㄏㄠ: ㄏㄠ is still open, so it shows as typed after 你.
        val composer = ChewingComposer(Base, FakeConverter(menu(preedit = "你", tail = 3)))
        assertEquals("你ㄏㄠ", composer.composeBuffer("ㄋㄧˇㄏㄠ"))
        // Enter writes what the region shows, as in Chewing.
        assertEquals("你ㄏㄠ", composer.typedReading("ㄋㄧˇㄏㄠ"))
    }

    @Test
    fun `a reading with no word for it shows its keys`() {
        val composer = ChewingComposer(
            Base,
            FakeConverter(menu(emptyList(), IntArray(0), IntArray(0), preedit = "", tail = 2)),
        )
        assertEquals("ㄈㄛ", composer.composeBuffer("ㄈㄛ"))
        assertTrue(composer.candidates("ㄈㄛ").isEmpty())
    }

    @Test
    fun `a sentence pick learns the sentence, a phrase pick the phrase`() {
        val converter = FakeConverter(menu())
        val composer = ChewingComposer(Base, converter)
        composer.learnChoice(nihao, 1)
        composer.learnChoice(nihao, 3)
        assertEquals(listOf("sentence $nihao 1", "phrase $nihao 3 妳"), converter.learned)
    }

    @Test
    fun `without libchewing the built-in composer answers`() {
        val converter = FakeConverter(null)
        val composer = ChewingComposer(Base, converter)
        assertEquals(listOf("基", "礎"), composer.candidates("ㄐㄧ"))
        assertEquals(1, composer.consumedForIndex("ㄐㄧ", 0))
        assertEquals("ㄐㄧ", composer.composeBuffer("ㄐㄧ"))
        composer.learnChoice("ㄐㄧ", 1)
        assertEquals(listOf(1), Base.learned.takeLast(1))
        assertTrue(converter.learned.isEmpty())
        // Not cached: the next keystroke asks libchewing again.
        converter.menu = menu()
        assertEquals("你好", composer.candidates(nihao).first())
    }

    @Test
    fun `one answer per reading until libchewing's answers change`() {
        val converter = FakeConverter(menu())
        val composer = ChewingComposer(Base, converter)
        composer.candidates(nihao)
        composer.composeBuffer(nihao)
        composer.consumedForIndex(nihao, 2)
        assertEquals(1, converter.asked)
        converter.generation++
        composer.candidates(nihao)
        assertEquals(2, converter.asked)
    }

    @Test
    fun `keys stay the built-in composer's`() {
        val composer = ChewingComposer(Base, FakeConverter(menu()))
        assertEquals("ㄋㄧ", composer.backspaceBuffer("ㄋㄧˇㄏ"))
        assertTrue(composer.buffersChar('ˊ'))
        assertTrue(composer.isConversion)
        assertTrue(composer.isTransliterating)
        assertNull(composer.missingPack)
    }

    @Test
    fun `unpack reads the bridge's arrays`() {
        // count 2, tail 5, then (consumed, sentence) per candidate.
        val info = intArrayOf(2, 5, 6, 0, 3, -1, 0, 0)
        val parsed = ChewingRuntime.unpack(arrayOf("你好", "你", "你ㄏ"), info, 6)
        assertEquals(listOf("你好", "你"), parsed.texts)
        assertEquals(listOf(6, 3), parsed.consumed.toList())
        assertEquals(listOf(0, -1), parsed.sentence.toList())
        assertEquals("你ㄏ", parsed.preedit)
        assertEquals(5, parsed.tail)
        // Lengths past the reading are pulled back into it.
        val wild = ChewingRuntime.unpack(arrayOf("字", ""), intArrayOf(1, 99, 50, -1), 3)
        assertEquals(3, wild.consumed[0])
        assertEquals(3, wild.tail)
    }

    @Test
    fun `Chewing stays out until picked and open, and only takes Zhuyin`() {
        // Not configured in a test, and no library: nothing replaces Zhuyin.
        assertNull(ChewingEngine.composerFor(Base, ComposerType.ZHUYIN, "zh"))
        assertNull(ChewingEngine.composerFor(Base, ComposerType.PINYIN, "zh"))
        assertNull(ChewingEngine.composerFor(Base, ComposerType.T9_ZHUYIN, "zh"))
    }

    @Test
    fun `an engine with a higher priority answers first`() {
        val asked = object : Composer {
            override val isConversion: Boolean get() = true
        }
        val wide = object : Composer {}
        val narrow = object : Composer {}
        fun engine(name: String, rank: Int, answer: Composer) = object : ConversionEngine {
            override val id: String = name
            override val priority: Int = rank
            override fun composerFor(base: Composer, type: ComposerType, langId: String?): Composer? =
                answer.takeIf { base === asked }
        }
        ConversionEngines.register(engine("test-wide", 0, wide))
        ConversionEngines.register(engine("test-narrow", 1, narrow))
        assertSame(narrow, ConversionEngines.resolve(asked, ComposerType.ZHUYIN, "zh"))
    }

    @Test
    fun `the dictionary is a pinned pack of the catalog`() {
        val pack = ChewingData.pack
        assertSame(pack, CjkDictCatalog.byId("chewing"))
        assertTrue(pack.available)
        assertEquals(pack.fileName, pack.path.substringAfterLast('/'))
        assertTrue(pack.fileName.contains(ChewingData.DATA_VERSION))
        assertEquals(64, pack.sha256.length)
        // Offered in Chewing's own group, never by the built-in packs' rows.
        assertTrue(CjkDictCatalog.forLang("zh").none { it.id == "chewing" })
    }
}

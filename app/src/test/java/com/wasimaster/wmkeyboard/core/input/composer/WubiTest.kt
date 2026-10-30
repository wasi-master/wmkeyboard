package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ComposerType
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Wubi 86 against a small table of its real codes, and against the real pack when it is on disk. */
class WubiTest {

    private val dict = CodeTableDictionary.parse(
        sequenceOf(
            "a\t工\t900",
            "aaaa\t工\t900",
            "aaaa\t恭恭敬敬\t3",
            "aaad\t工期\t5",
            "aadg\t工厂\t80",
            "khlg\t中国\t700",
            "khlg\t跨国\t60",
            "wyth\t信息\t500",
            "wytg\t信\t1",
            "wy\t住\t40",
            // The filter a malformed row has to get past.
            "zzzy\tㄤ\t9",
            "aaaaa\t长\t9",
        ),
        CodeTableDictionary.WUBI_CODE,
    )

    @After
    fun reset() {
        CjkDictionaries.wubi = CodeTableDictionary.EMPTY
    }

    @Test
    fun `a full code offers its character or phrase first`() {
        CjkDictionaries.wubi = dict
        assertEquals(listOf("中国", "跨国"), WubiComposer.candidates("khlg"))
        assertEquals(listOf("信息"), WubiComposer.candidates("wyth"))
    }

    @Test
    fun `phrases and characters rank together, the exact code leading`() {
        CjkDictionaries.wubi = dict
        assertEquals("工", WubiComposer.candidates("a").first())
        assertEquals(listOf("工", "恭恭敬敬"), WubiComposer.candidates("aaaa"))
        // Nothing is spelled `wyt` exactly, so frequency alone orders what starts with it.
        assertEquals(listOf("信息", "信"), WubiComposer.candidates("wyt"))
    }

    @Test
    fun `z stands in for a component`() {
        CjkDictionaries.wubi = dict
        assertEquals(listOf("中国", "跨国"), WubiComposer.candidates("kzlg"))
        // Alone it would match everything, so it offers nothing.
        assertEquals(emptyList<String>(), WubiComposer.candidates("zz"))
    }

    @Test
    fun `the table refuses codes no key can spell`() {
        assertEquals(emptyList<String>(), dict.candidates("zzzy"))
        assertEquals(listOf("工", "恭恭敬敬"), dict.candidates("aaaa"))
    }

    @Test
    fun `a commit takes four keys and leaves the next code converting`() {
        CjkDictionaries.wubi = dict
        assertEquals(listOf("信息"), WubiComposer.candidates("wythkhlg"))
        assertEquals(4, WubiComposer.consumedFor("wythkhlg", "信息"))
        assertEquals(4, WubiComposer.consumedForIndex("wythkhlg", 0))
        assertEquals(2, WubiComposer.consumedFor("wy", "住"))
    }

    @Test
    fun `the buffer stays in letters and the composer is wired up`() {
        assertEquals("khlg", WubiComposer.composeBuffer("KHLG"))
        assertTrue(WubiComposer.isConversion)
        assertEquals("wubi", WubiComposer.missingPack)
        assertEquals("wubi", CjkDictCatalog.forComposer(ComposerType.WUBI)?.id)
        CjkDictionaries.wubi = dict
        assertEquals(null, WubiComposer.missingPack)
    }

    @Test
    fun `no table loaded yields no candidates`() {
        assertEquals(emptyList<String>(), WubiComposer.candidates("khlg"))
    }

    /** Skipped where the data repository is not checked out beside the app. */
    @Test
    fun `the published pack spells the codes every Wubi chart gives`() {
        val file = File(System.getProperty("user.home"), "Work/wmkeyboard-data/cjk/wubi86.tsv")
        assumeTrue(file.isFile)
        CjkDictionaries.wubi = file.bufferedReader().useLines {
            CodeTableDictionary.parse(it, CodeTableDictionary.WUBI_CODE)
        }
        val known = mapOf(
            "a" to "工", "g" to "一", "k" to "中", "q" to "我", "r" to "的",
            "icy" to "汉", "pb" to "字", "gggg" to "王", "khlg" to "中国", "wyth" to "信息",
        )
        for ((code, word) in known) assertEquals(code, word, WubiComposer.candidates(code).first())
    }
}

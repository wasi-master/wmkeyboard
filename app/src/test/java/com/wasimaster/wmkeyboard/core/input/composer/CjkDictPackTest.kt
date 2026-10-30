package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Downloadable-pack catalog, on-disk resolution, and the reload state token. */
class CjkDictPackTest {

    private fun tempFilesDir(): File =
        File(System.getProperty("java.io.tmpdir"), "cjkpack-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `catalog maps packs to their language and is unavailable until hosted`() {
        val pinyin = CjkDictCatalog.byId("pinyin")!!
        assertEquals("zh", pinyin.langId)
        assertEquals(
            listOf("pinyin", "pinyin_ngrams", "stroke", "cangjie", "wubi", "s2t_phrases"),
            CjkDictCatalog.forLang("zh").map { it.id },
        )
        assertEquals(listOf("ja_kana", "ja_furigana"), CjkDictCatalog.forLang("ja").map { it.id })
        assertEquals(listOf("s2t_phrases", "jyutping"), CjkDictCatalog.forLang("yue").map { it.id })
        assertEquals(emptyList<CjkDictPack>(), CjkDictCatalog.forLang("en"))
        // The shipped packs are hosted (path + checksum present).
        assertTrue(pinyin.available)
        // A pack is unavailable if either the path or the checksum is missing.
        assertFalse(pinyin.copy(path = "").available)
        // The path resolves against the data repository, at its default here.
        assertEquals("https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/cjk/pinyin_v2.tsv", pinyin.url)
        assertFalse(pinyin.copy(sha256 = "").available)
        // Every shipped pack is hosted, so none of them renders as unavailable.
        assertTrue(CjkDictCatalog.packs.all { it.available })
    }

    @Test
    fun `store resolves a downloaded pack file and reflects it in the token`() {
        val filesDir = tempFilesDir()
        val pinyin = CjkDictCatalog.byId("pinyin")!!

        assertFalse(CjkDictStore.isDownloaded(filesDir, pinyin))
        assertNull(CjkDictStore.downloadedFileFor(filesDir, "pinyin"))
        val before = CjkDictStore.stateToken(filesDir)

        // Simulate a completed download landing in place.
        val file = CjkDictStore.packFile(filesDir, pinyin)
        file.parentFile!!.mkdirs()
        file.writeText("ni\tN1\t100\n")

        assertTrue(CjkDictStore.isDownloaded(filesDir, pinyin))
        assertEquals(file, CjkDictStore.downloadedFileFor(filesDir, "pinyin"))
        assertNotEquals(before, CjkDictStore.stateToken(filesDir))

        filesDir.deleteRecursively()
    }

    @Test
    fun `an earlier version keeps converting until the current one replaces it`() {
        val filesDir = tempFilesDir()
        val pinyin = CjkDictCatalog.byId("pinyin")!!
        val dir = CjkDictStore.packDir(filesDir, pinyin).apply { mkdirs() }
        val legacy = File(dir, "pinyin.tsv").apply { writeText("ni\tN1\t100\n") }

        // The old copy converts, but the row still offers the new version.
        assertEquals(legacy, CjkDictStore.downloadedFileFor(filesDir, "pinyin"))
        assertFalse(CjkDictStore.isDownloaded(filesDir, pinyin))

        // Once the new version is in place it wins, and the old copy goes.
        val current = CjkDictStore.packFile(filesDir, pinyin).apply { writeText("ni\tN2\t100\n") }
        assertEquals(current, CjkDictStore.downloadedFileFor(filesDir, "pinyin"))
        CjkDictStore.dropLegacy(filesDir, pinyin)
        assertFalse(legacy.exists())
        assertTrue(current.isFile)

        filesDir.deleteRecursively()
    }
}

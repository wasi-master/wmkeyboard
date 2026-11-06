package com.wasimaster.wmkeyboard.core.voice.executorch

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A model that comes as one zip (Vosk's, #1165): laid out safely, and known to be whole. */
class EtArchiveTest {

    private val root: File = Files.createTempDirectory("etarchive").toFile()

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun zip(name: String, vararg entries: Pair<String, String?>): File {
        val file = File(root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(ZipEntry(path))
                if (text != null) out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun archive(file: File) = EtFile(file.name, file.length(), "ab".repeat(32), archive = true)

    @Test fun `the folder vosk wraps a model in is dropped`() {
        val zip = zip(
            "m.zip",
            "vosk-model-small-de-0.15/" to null,
            "vosk-model-small-de-0.15/am/final.mdl" to "am",
            "vosk-model-small-de-0.15/conf/model.conf" to "conf",
            "vosk-model-small-de-0.15/README" to "readme",
        )
        val dir = File(root, "model").apply { mkdirs() }
        val entry = archive(zip)
        EtArchive.unpack(zip, dir, entry)
        assertEquals("am", File(dir, "am/final.mdl").readText())
        assertEquals("conf", File(dir, "conf/model.conf").readText())
        assertTrue(EtArchive.mark(dir, entry).isFile)
        assertFalse(zip.exists())
        assertFalse(File(dir, ".unpacking").exists())
        assertFalse(File(dir, "vosk-model-small-de-0.15").exists())
    }

    @Test fun `an archive with no single folder is laid out as it is`() {
        val zip = zip("m.zip", "am/final.mdl" to "am", "README" to "r")
        val dir = File(root, "model").apply { mkdirs() }
        EtArchive.unpack(zip, dir, archive(zip))
        assertEquals("am", File(dir, "am/final.mdl").readText())
        assertEquals("r", File(dir, "README").readText())
    }

    @Test fun `a name that climbs out of the folder is refused and leaves nothing`() {
        val zip = zip("bad.zip", "model/ok" to "fine", "model/../../escaped" to "no")
        val dir = File(root, "model").apply { mkdirs() }
        try {
            EtArchive.unpack(zip, dir, archive(zip))
            fail("unpacked an entry outside the folder")
        } catch (_: IOException) {
        }
        assertFalse(File(root, "escaped").exists())
        assertFalse(EtArchive.mark(dir, archive(zip)).exists())
        assertFalse(File(dir, ".unpacking").exists())
        assertTrue(zip.exists())
    }

    @Test fun `common folder is only a folder every entry is under`() {
        assertEquals("a", EtArchive.commonFolder(listOf("a", "a/b", "a/c/d")))
        assertNull(EtArchive.commonFolder(listOf("a/b", "c/d")))
        assertNull(EtArchive.commonFolder(listOf("README")))
        assertNull(EtArchive.commonFolder(emptyList()))
    }

    @Test fun `a vosk model is downloaded once its zip is unpacked, not before`() {
        val model = EtCatalog.forEngine(EtEngine.VOSK).first()
        val files = File(root, "files")
        val zipEntry = model.files.single()
        assertFalse(EtStore.isDownloaded(files, model))
        // The zip whole on disk is not yet a model.
        val dir = EtStore.dir(files, model).apply { mkdirs() }
        File(dir, zipEntry.name).writeText("partial")
        assertFalse(EtStore.isDownloaded(files, model))
        EtArchive.mark(dir, zipEntry).writeText(zipEntry.sha256)
        assertTrue(EtStore.isDownloaded(files, model))
        EtStore.delete(files, model)
        assertFalse(EtStore.isDownloaded(files, model))
    }
}

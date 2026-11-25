package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileZipTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(path: String, text: String = "hello"): File {
        val f = File(temp.root, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    @Test
    fun aZipComesBackAsItWent() = runBlocking {
        val tree = File(temp.root, "tree").apply { mkdirs() }
        file("tree/a.txt", "alpha")
        file("tree/sub/b.txt", "beta")
        val single = file("single.md", "# hi")
        val archive = File(temp.root, "out/pack.zip").apply { parentFile?.mkdirs() }

        assertTrue(FileZip.zip(listOf(tree, single), archive))
        val names = archive.inputStream().use { FileZip.entries(it) }.map { it.path }
        assertTrue("tree/sub/b.txt" in names)
        assertTrue("single.md" in names)

        val unpacked = FileZip.unzip(archive, File(temp.root, "out"))
        assertNotNull(unpacked)
        assertEquals("pack", unpacked!!.name)
        assertEquals("beta", File(unpacked, "tree/sub/b.txt").readText())
        assertEquals("# hi", File(unpacked, "single.md").readText())
    }

    @Test
    fun unpackingTwiceMakesASecondFolder() = runBlocking {
        val archive = File(temp.root, "a.zip")
        FileZip.zip(listOf(file("x.txt")), archive)
        val first = FileZip.unzip(archive, temp.root)
        val second = FileZip.unzip(archive, temp.root)
        assertEquals("a", first?.name)
        assertEquals("a (1)", second?.name)
    }

    @Test
    fun anEntryThatClimbsOutIsSkipped() = runBlocking {
        val archive = File(temp.root, "evil.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in listOf("../escape.txt", "/root.txt", "C:/drive.txt", "ok/fine.txt", "..\\win.txt")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        val out = File(temp.root, "dest").apply { mkdirs() }
        val unpacked = FileZip.unzip(archive, out)
        assertNotNull(unpacked)
        assertTrue(File(unpacked, "ok/fine.txt").exists())
        assertFalse(File(temp.root, "escape.txt").exists())
        assertFalse(File(out, "escape.txt").exists())
        assertFalse(File(temp.root, "win.txt").exists())
        assertEquals(listOf("ok"), unpacked!!.list()?.toList())
    }

    @Test
    fun safePathsStayInside() {
        assertEquals("a/b.txt", FileZip.safePath("a/b.txt"))
        assertEquals("a/b.txt", FileZip.safePath("a\\b.txt"))
        assertEquals("a/b.txt", FileZip.safePath("./a//b.txt"))
        assertNull(FileZip.safePath("../x"))
        assertNull(FileZip.safePath("a/../../x"))
        assertNull(FileZip.safePath("/etc/passwd"))
        assertNull(FileZip.safePath("C:/x"))
        assertNull(FileZip.safePath(""))
    }

    @Test
    fun aBrokenZipLeavesNothing() = runBlocking {
        val archive = file("broken.zip", "this is not a zip at all")
        val before = temp.root.list()!!.toSet()
        assertNull(FileZip.unzip(archive, temp.root))
        assertEquals(before, temp.root.list()!!.toSet())
    }

    @Test
    fun twoSourcesOfOneNameBothGoIn() = runBlocking {
        val a = file("one/same.txt", "1")
        val b = file("two/same.txt", "2")
        val archive = File(temp.root, "both.zip")
        assertTrue(FileZip.zip(listOf(a, b), archive))
        val names = archive.inputStream().use { FileZip.entries(it) }.map { it.path }
        assertEquals(listOf("same.txt", "same (1).txt"), names)
    }
}

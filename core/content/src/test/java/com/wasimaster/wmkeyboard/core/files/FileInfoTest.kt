package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileInfoTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aTallyCountsEverythingBelow() = runBlocking {
        File(temp.root, "a/b").mkdirs()
        File(temp.root, "a/x.pdf").writeBytes(ByteArray(100))
        File(temp.root, "a/b/y.pdf").writeBytes(ByteArray(50))
        File(temp.root, "a/b/z.jpg").writeBytes(ByteArray(10))
        val steps = ArrayList<FileInfo.Tally>()
        val tally = FileInfo.tally(File(temp.root, "a")) { steps += it }
        assertEquals(160L, tally.bytes)
        assertEquals(3, tally.files)
        assertEquals(1, tally.folders)
        assertEquals(150L, tally.byKind[FileKind.PDF])
        assertEquals(10L, tally.byKind[FileKind.IMAGE])
        assertEquals(tally, steps.last())
    }

    @Test
    fun sha256MatchesTheKnownValue() = runBlocking {
        val hash = FileInfo.sha256("abc".byteInputStream())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }
}

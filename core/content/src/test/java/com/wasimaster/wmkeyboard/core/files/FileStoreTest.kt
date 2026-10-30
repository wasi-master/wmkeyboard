package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(path: String, bytes: Int = 1, modified: Long = 1_000L): File {
        val f = File(temp.root, path)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(modified)
        return f
    }

    private val mimeOf: (String) -> String? = { name ->
        when (name.substringAfterLast('.')) {
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            else -> null
        }
    }

    @Test
    fun foldersComeFirstThenNamesWhateverTheCase() {
        file("b.txt")
        file("A.txt")
        File(temp.root, "zebra").mkdir()
        val names = FileStore.list(temp.root)?.map { it.name }
        assertEquals(listOf("zebra", "A.txt", "b.txt"), names)
    }

    @Test
    fun sortsByDateAndSizeAndReverses() {
        file("old.txt", bytes = 30, modified = 1_000L)
        file("new.txt", bytes = 10, modified = 9_000L)
        assertEquals(listOf("old.txt", "new.txt"), FileStore.list(temp.root, FileSort.DATE)?.map { it.name })
        assertEquals(listOf("new.txt", "old.txt"), FileStore.list(temp.root, FileSort.DATE, descending = true)?.map { it.name })
        assertEquals(listOf("new.txt", "old.txt"), FileStore.list(temp.root, FileSort.SIZE)?.map { it.name })
    }

    @Test
    fun hiddenFilesStayOutUntilAskedFor() {
        file(".secret")
        file("plain")
        assertEquals(listOf("plain"), FileStore.list(temp.root)?.map { it.name })
        assertEquals(listOf(".secret", "plain"), FileStore.list(temp.root, showHidden = true)?.map { it.name })
    }

    @Test
    fun filterKeepsFoldersAndMatchingTypes() {
        file("a.png")
        file("b.pdf")
        File(temp.root, "dir").mkdir()
        val names = FileStore.list(temp.root, mimes = listOf("image/*"), mimeOf = mimeOf)?.map { it.name }
        assertEquals(listOf("dir", "a.png"), names)
    }

    @Test
    fun anUnreadableFolderIsNullNotEmpty() {
        assertNull(FileStore.list(File(temp.root, "not-there")))
    }

    @Test
    fun mimeFiltersUnderstandWildcards() {
        assertTrue(FileStore.mimeMatches("image/png", emptyList()))
        assertTrue(FileStore.mimeMatches("image/png", listOf("*/*")))
        assertTrue(FileStore.mimeMatches("IMAGE/PNG", listOf("image/*")))
        assertTrue(FileStore.mimeMatches("application/pdf", listOf("text/*", "application/pdf")))
        assertFalse(FileStore.mimeMatches("video/mp4", listOf("image/*")))
        assertFalse(FileStore.mimeMatches("", listOf("image/*")))
    }

    @Test
    fun searchNeedsEveryWordAndWalksDown() = runBlocking {
        file("deep/er/holiday photo.png")
        file("holiday.txt")
        file("photo.txt")
        val names = FileStore.search(temp.root, "photo HOLIDAY").map { it.name }
        assertEquals(listOf("holiday photo.png"), names)
    }

    @Test
    fun searchStopsAtItsCap() = runBlocking {
        repeat(10) { file("match$it.txt") }
        assertEquals(3, FileStore.search(temp.root, "match", cap = 3).size)
    }

    @Test
    fun searchOfNothingFindsNothing() = runBlocking {
        file("a.txt")
        assertTrue(FileStore.search(temp.root, "   ").isEmpty())
    }
}

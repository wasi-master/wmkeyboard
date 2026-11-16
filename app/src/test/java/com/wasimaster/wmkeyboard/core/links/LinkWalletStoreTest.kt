package com.wasimaster.wmkeyboard.core.links

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Link wallet's file and its address rules (#1214). */
class LinkWalletStoreTest {

    @Test
    fun addressesAreTidiedOrRefused() {
        assertEquals("https://example.com/a", LinkWalletStore.normalise(" example.com/a "))
        assertEquals("https://example.com", LinkWalletStore.normalise("https://example.com"))
        assertEquals("mailto:me@example.com", LinkWalletStore.normalise("mailto:me@example.com"))
        assertEquals("http://localhost:8080", LinkWalletStore.normalise("localhost:8080"))
        assertNull(LinkWalletStore.normalise("javascript:alert(1)"))
        assertNull(LinkWalletStore.normalise("file:///sdcard/x"))
        assertNull(LinkWalletStore.normalise("two words"))
        assertNull(LinkWalletStore.normalise("https://"))
    }

    @Test
    fun theFirstLinkOfAMessageIsPickedOut() {
        assertEquals("https://github.com/x", LinkWalletStore.firstLinkIn("look at https://github.com/x, it's good"))
        assertNull(LinkWalletStore.firstLinkIn("nothing here"))
    }

    @Test
    fun aBlankNameIsTheHost() {
        assertEquals("github.com", LinkWalletStore.hostOf("https://www.github.com/a?b"))
    }

    @Test
    fun linksKeepTheirOrderThroughTheFile() {
        val dir = Files.createTempDirectory("links").toFile()
        val file = File(dir, LinkWalletStore.FILE_PATH)
        file.parentFile.mkdirs()
        val store = LinkWalletStore(file)
        val a = assertNotNull(store.add("", "example.com"))
        val b = store.add("Docs", "https://docs.example.com")!!
        store.move(b.id, -1)
        store.save()
        val again = LinkWalletStore(file)
        assertEquals(listOf("Docs", "example.com"), again.all().map { it.title })
        val (index, gone) = again.delete(b.id)!!
        again.restore(index, gone)
        assertEquals(listOf(b.id, a.id), again.all().map { it.id })
        dir.deleteRecursively()
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}

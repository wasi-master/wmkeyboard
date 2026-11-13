package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Picture snippets (competitor gap list item 12, LeanType L1). */
class SnippetImageTest {

    @get:Rule val tmp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)

    private fun store(): Pair<SnippetStore, File> {
        val files = tmp.newFolder("files")
        return SnippetStore(File(files, SnippetStore.FILE_PATH)) to files
    }

    @Test fun onlyNamesThisAppMakesArePictures() {
        assertTrue(SnippetStore.isImageName("s_0123abcd.png"))
        assertTrue(SnippetStore.isImageName("s_x.JPG"))
        assertFalse(SnippetStore.isImageName("../evil.png"))
        assertFalse(SnippetStore.isImageName("a/b.png"))
        assertFalse(SnippetStore.isImageName(".png"))
        assertFalse(SnippetStore.isImageName("s_x.svg"))
        assertFalse(SnippetStore.isImageName(null))
        assertNull(SnippetStore.imageFile(File("/x"), "../../etc/passwd.png"))
        assertEquals(
            File(File("/x", SnippetStore.IMAGES_DIR), "s_a.gif"),
            SnippetStore.imageFile(File("/x"), "s_a.gif"),
        )
    }

    @Test fun aStoredSnippetDropsAPictureNameItCouldNotHaveMade() {
        val (store, _) = store()
        val kept = store.add(Snippet(id = 0, label = "cat", text = "", image = "s_cat.png"))
        val dropped = store.add(Snippet(id = 0, label = "bad", text = "", image = "../x.png"))
        assertEquals("s_cat.png", kept.image)
        assertNull(dropped.image)
    }

    @Test fun thePictureSurvivesASaveAndAReload() {
        val (store, files) = store()
        store.add(Snippet(id = 0, label = "cat", text = "", trigger = "cat", image = "s_cat.png"))
        store.save()
        val again = SnippetStore(File(files, SnippetStore.FILE_PATH))
        assertEquals("s_cat.png", again.items().single().image)
        assertEquals("s_cat.png", again.matchTrigger("cat")?.image)
    }

    @Test fun aPictureSnippetOffersOneCandidateCarryingThePicture() {
        val (store, _) = store()
        val snippet = store.add(
            Snippet(id = 0, label = "cat", text = "a cat", alternates = listOf("a dog"), image = "s_cat.png"),
        )
        val candidates = store.candidates(snippet)
        assertEquals(1, candidates.size)
        assertEquals("s_cat.png", candidates.single().image)
        assertEquals("a cat", candidates.single().text)
    }

    @Test fun linkedPicturesAreToldApartByTheirFiles() {
        val (store, _) = store()
        val one = store.add(Snippet(id = 0, label = "one", text = "", image = "s_one.png"))
        val two = store.add(Snippet(id = 0, label = "two", text = "", image = "s_two.png"))
        val parent = store.add(Snippet(id = 0, label = "pets", text = "pets", children = listOf(one.id, two.id)))
        val images = store.candidates(parent).map { it.image }
        assertEquals(listOf(null, "s_one.png", "s_two.png"), images)
    }

    @Test fun importedPicturesAreWrittenBesideTheSnippets() {
        val (store, files) = store()
        store.addAll(
            listOf(Snippet(id = 0, label = "cat", text = "", trigger = "cat", image = "s_cat.png")),
            images = mapOf("s_cat.png" to png, "../escape.png" to png),
        )
        val written = SnippetStore.imageFile(files, "s_cat.png")!!
        assertTrue(written.readBytes().contentEquals(png))
        assertFalse(File(files, "snippets/escape.png").exists())
    }

    @Test fun pruningKeepsPicturesInUseAndRecentOnes() {
        val (store, files) = store()
        store.addAll(
            listOf(Snippet(id = 0, label = "cat", text = "", image = "s_cat.png")),
            images = mapOf("s_cat.png" to png, "s_old.png" to png, "s_new.png" to png),
        )
        val old = SnippetStore.imageFile(files, "s_old.png")!!
        val now = System.currentTimeMillis()
        old.setLastModified(now - 2L * 24 * 60 * 60 * 1000)
        store.pruneImages(now)
        assertTrue(SnippetStore.imageFile(files, "s_cat.png")!!.exists())
        // Unused but young: a delete's undo may still want it.
        assertTrue(SnippetStore.imageFile(files, "s_new.png")!!.exists())
        assertFalse(old.exists())
    }

    @Test fun picturesAreRecognisedByTheirFirstBytes() {
        assertEquals("png", SnippetStore.sniffExtension(png))
        assertEquals("jpg", SnippetStore.sniffExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("gif", SnippetStore.sniffExtension("GIF89a".toByteArray()))
        assertEquals("webp", SnippetStore.sniffExtension("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertNull(SnippetStore.sniffExtension("hello".toByteArray()))
        assertTrue(SnippetStore.newImageName("image/jpeg").endsWith(".jpg"))
        assertTrue(SnippetStore.newImageName(null, png).endsWith(".png"))
        assertEquals("image/webp", SnippetStore.imageMime("s_a.webp"))
    }

    @Test fun aSnippetFileCarriesItsPicturesBothWays() {
        val snippets = listOf(
            Snippet(id = 1, label = "Cat", text = "", image = "s_cat.png"),
            Snippet(id = 2, label = "Hi", text = "hello"),
        )
        val text = SnippetFile.encode(snippets, 1, "1", images = mapOf("s_cat.png" to png, "s_unused.png" to png))
        assertFalse(text.contains("s_unused.png"))
        val read = SnippetFile.decode(text)!!
        // The picture-only snippet survives because its picture came along.
        assertEquals(listOf("s_cat.png", null), read.snippets.map { it.image })
        assertTrue(read.images.getValue("s_cat.png").contentEquals(png))
        assertEquals(setOf("s_cat.png"), SnippetPayload.readText(text, "x")!!.images.keys)
    }

    @Test fun aPictureTheFileDidNotBringFallsBackToItsWords() {
        val snippets = listOf(
            Snippet(id = 1, label = "Cat", text = "a cat", image = "s_cat.png"),
            Snippet(id = 2, label = "Dog", text = "", image = "s_dog.png"),
        )
        val read = SnippetFile.decode(SnippetFile.encode(snippets, 1, "1"))!!
        assertEquals(listOf("Cat"), read.snippets.map { it.label })
        assertNull(read.snippets.single().image)
        assertTrue(read.images.isEmpty())
    }

    @Test fun aTextOnlyFileHasNoPicturesKey() {
        val text = SnippetFile.encode(listOf(Snippet(id = 1, label = "Hi", text = "hello")), 1, "1")
        assertFalse(text.contains("\"images\""))
    }

    @Test fun anExportReadsThePicturesItsSnippetsUse() {
        val (_, files) = store()
        val file = SnippetStore.imageFile(files, "s_cat.png")!!
        file.parentFile!!.mkdirs()
        file.writeBytes(png)
        val bytes = SnippetStore.imageBytes(
            files,
            listOf(Snippet(id = 1, label = "Cat", text = "", image = "s_cat.png"), Snippet(id = 2, label = "x", text = "y", image = "s_gone.png")),
        )
        assertEquals(setOf("s_cat.png"), bytes.keys)
    }
}

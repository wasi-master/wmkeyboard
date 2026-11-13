package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoManifest
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnippetPayloadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, text: String): File =
        temp.newFile(name).apply { writeText(text) }

    private fun file(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `the app's own format is recognised`() {
        val native = SnippetFile.encode(
            listOf(Snippet(id = 1, label = "A", text = "a", trigger = "aa")),
            appVersion = 1,
            appVersionName = "1.0",
        )
        val parsed = SnippetPayload.read(file("x.wmsnippets.json", native), "x.wmsnippets.json")
        assertNotNull(parsed)
        assertEquals(SnippetPayload.Source.NATIVE, parsed!!.source)
        assertFalse(parsed.isEspanso)
        assertEquals(1, parsed.snippets.size)
    }

    @Test
    fun `an espanso match file is recognised`() {
        val parsed = SnippetPayload.read(
            file("greetings.yml", "matches:\n  - trigger: \":hi\"\n    replace: \"hello\"\n"),
            "greetings.yml",
        )
        assertNotNull(parsed)
        assertEquals(SnippetPayload.Source.ESPANSO_FILE, parsed!!.source)
        assertTrue(parsed.isEspanso)
        assertEquals(":hi", parsed.snippets.single().trigger)
        assertEquals("greetings", parsed.suggestedName)
    }

    @Test
    fun `an espanso package archive is recognised by its first bytes`() {
        val (bytes, _) = EspansoWriter.encodePackage(
            listOf(Snippet(id = 1, label = "A", text = "a", trigger = ":a")),
            emptyList(),
            EspansoManifest(name = "pack", title = "Pack", description = ""),
        )
        val parsed = SnippetPayload.read(file("pack.zip", bytes), "pack.zip")
        assertNotNull(parsed)
        assertEquals(SnippetPayload.Source.ESPANSO_PACKAGE, parsed!!.source)
        assertEquals("Pack", parsed.suggestedName)
    }

    @Test
    fun `the app's own tagged format is never read as espanso`() {
        // The ordering rule: an untagged format must never be tried ahead of a
        // tagged one. A native file that happens to contain the word "matches"
        // still decodes as native.
        val native = SnippetFile.encode(
            listOf(Snippet(id = 1, label = "matches:", text = "matches:\n  - trigger: x")),
            appVersion = 1,
            appVersionName = "1.0",
        )
        val parsed = SnippetPayload.read(file("y.wmsnippets.json", native), "y.wmsnippets.json")!!
        assertEquals(SnippetPayload.Source.NATIVE, parsed.source)
    }

    @Test
    fun `an espanso file is never read as the app's own format`() {
        val parsed = SnippetPayload.readText(
            "matches:\n  - trigger: \"{\"\n    replace: \"format\"\n",
            "x.yml",
        )!!
        assertEquals(SnippetPayload.Source.ESPANSO_FILE, parsed.source)
    }

    @Test
    fun `something that is neither is refused`() {
        assertNull(SnippetPayload.read(file("a.txt", "just some words\n"), "a.txt"))
        assertNull(SnippetPayload.read(file("b.json", """{"hello":"world"}"""), "b.json"))
        assertNull(SnippetPayload.readText("", "c.yml"))
    }

    @Test
    fun `a zip that is not an espanso package is refused rather than mistaken`() {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("pack.json"))
                zip.write("""{"format":"stickers"}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertNull(SnippetPayload.read(file("s.wmstickers", bytes), "s.wmstickers"))
    }

    @Test
    fun `an oversized text payload is refused`() {
        val huge = "matches:\n" + "  - trigger: \"x\"\n    replace: \"y\"\n".repeat(200_000)
        assertTrue(huge.length > com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoFile.MAX_BYTES)
        assertNull(SnippetPayload.read(file("big.yml", huge), "big.yml"))
    }

    @Test
    fun `a package brings the pictures its image matches name`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 7, 7)
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("cats/0.1.0/images/Cat.PNG"))
                zip.write(png)
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("cats/0.1.0/package.yml"))
                zip.write("matches:\n  - trigger: \":cat\"\n    image_path: \"/home/me/.config/espanso/cat.png\"\n".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val parsed = SnippetPayload.read(file("cats.zip", bytes), "cats.zip")!!
        val snippet = parsed.snippets.single()
        val name = snippet.image!!
        assertTrue(parsed.images.getValue(name).contentEquals(png))
    }
}

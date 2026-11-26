package com.wasimaster.wmkeyboard.core.notes

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pictures in notes (#1586): their addresses, a zip round trip and a vault's links. */
class NoteAttachmentsTest {

    private val ours = "0123456789abcdef0123.jpg"

    @Test
    fun `relink points each picture address somewhere else and leaves the rest`() {
        val body = "a ![](attachments/$ours) b ![x](<attachments/Pasted image.png>) c ![](https://e.com/p.png)"
        val out = NoteAttachments.relink(body) { name -> if (name == ours) "WM/$name" else "pics/$name" }
        assertEquals("a ![](WM/$ours) b ![x](<pics/Pasted image.png>) c ![](https://e.com/p.png)", out)
    }

    @Test
    fun `relink reads a percent-encoded space and keeps an address it gives null for`() {
        val body = "![](attachments/Pasted%20image.png) ![](attachments/other.png)"
        val out = NoteAttachments.relink(body) { name -> if (name == "Pasted image.png") "attachments/$ours" else null }
        assertEquals("![](attachments/$ours) ![](attachments/other.png)", out)
    }

    @Test
    fun `a zip of our own comes back with its pictures and unchanged links`() {
        val dir = Files.createTempDirectory("notes").toFile()
        try {
            val picture = NoteAttachments.save(dir, byteArrayOf(1, 2, 3), "jpg")!!
            val note = Note(1, "look\n\n![](attachments/$picture)", title = "Trip")
            val zip = ByteArrayOutputStream().also {
                NoteFiles.writeZip(listOf(note), NoteFiles.Format.MARKDOWN, it) { ref -> NoteAttachments.file(dir, ref) }
            }.toByteArray()
            val other = Files.createTempDirectory("notes2").toFile()
            try {
                val back = NoteFiles.readZip(ByteArrayInputStream(zip)) { name, bytes -> NoteAttachments.adopt(other, name, bytes) }
                assertEquals(1, back.size)
                assertTrue(back[0].body, back[0].body.endsWith("![](attachments/$picture)"))
                assertTrue(File(NoteAttachments.dir(other), picture).isFile)
            } finally {
                other.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a picture with another app's name is kept under a new one and the link follows`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("vault/note.md"))
                z.write("see ![](attachments/Pasted%20image.png)\n".toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("vault/attachments/Pasted image.png"))
                z.write(byteArrayOf(9, 9))
                z.closeEntry()
                z.putNextEntry(ZipEntry("vault/attachments/readme.txt"))
                z.write("not a picture".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val dir = Files.createTempDirectory("notes").toFile()
        try {
            val back = NoteFiles.readZip(ByteArrayInputStream(zip)) { name, bytes -> NoteAttachments.adopt(dir, name, bytes) }
            // readme.txt in the attachments folder is a text file, so it is a note.
            val note = back.single { it.body.startsWith("see") }
            val name = NoteAttachments.referenced(note.body).single()
            assertTrue(NoteAttachments.isName(name))
            assertTrue(File(NoteAttachments.dir(dir), name).isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `adopt refuses what is not a picture and renames what is not ours`() {
        val dir = Files.createTempDirectory("notes").toFile()
        try {
            assertNull(NoteAttachments.adopt(dir, "script.sh", byteArrayOf(1)))
            // A name that tries to leave the folder is stored under a fresh one of ours.
            val kept = NoteAttachments.adopt(dir, "../$ours", byteArrayOf(1))!!
            assertTrue(kept, NoteAttachments.isName(kept))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an Obsidian export points the pictures where the vault keeps them`() {
        val note = Note(1, "![](attachments/$ours)")
        val text = NoteFiles.toObsidian(note) { name -> "Attachments/$name" }
        assertTrue(text, text.contains("![](Attachments/$ours)"))
    }
}

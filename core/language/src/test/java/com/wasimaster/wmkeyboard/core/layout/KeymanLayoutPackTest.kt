package com.wasimaster.wmkeyboard.core.layout

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeymanLayoutPackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val suffix = ".wmlayout.json"
    private val grids = mapOf("kmn_b$suffix" to "second", "kmn_a$suffix" to "first")

    private fun zip(name: String, entries: Map<String, String>): File =
        folder.newFile(name).also { file ->
            ZipOutputStream(file.outputStream()).use { out ->
                for ((entry, text) in entries) {
                    out.putNextEntry(ZipEntry(entry))
                    out.write(text.encodeToByteArray())
                    out.closeEntry()
                }
            }
        }

    /** A pin for [entries], with the digest worked out by the same function the install checks with. */
    private fun pinFor(entries: Map<String, String>): KeymanLayoutPack.Pin {
        val raw = entries.values.sumOf { it.encodeToByteArray().size.toLong() }
        val digest = KeymanLayoutPack.digestOf(zip("pin-source.zip", entries), KeymanLayoutPack.Pin("", entries.size, raw))
        return KeymanLayoutPack.Pin(digest!!, entries.size, raw)
    }

    @After
    fun reset() = KeymanLayoutPack.configure(null, null)

    @Test
    fun `a build with no pin never asks for anything`() {
        KeymanLayoutPack.configure(null, folder.newFolder())
        assertFalse(KeymanLayoutPack.needsDownload)
        assertFalse(KeymanLayoutPack.isMissing("asset_kmn_a"))
    }

    @Test
    fun `only keyman layouts wait for the pack`() {
        KeymanLayoutPack.configure(pinFor(grids), folder.newFolder())
        assertTrue(KeymanLayoutPack.needsDownload)
        assertTrue(KeymanLayoutPack.isMissing("asset_kmn_a"))
        assertFalse(KeymanLayoutPack.isMissing("asset_fr_azerty"))
    }

    @Test
    fun `the pinned pack installs and serves its grids`() {
        val dir = folder.newFolder()
        KeymanLayoutPack.configure(pinFor(grids), dir)
        val revision = KeymanLayoutPack.revision.value
        val part = File(dir, "incoming").also { zip("good.zip", grids).copyTo(it) }

        assertTrue(KeymanLayoutPack.install(part))

        assertFalse(KeymanLayoutPack.needsDownload)
        assertFalse(KeymanLayoutPack.isMissing("asset_kmn_a"))
        assertEquals(revision + 1, KeymanLayoutPack.revision.value)
        assertEquals("first", KeymanLayoutPack.read("kmn_a", suffix))
        assertNull(KeymanLayoutPack.read("kmn_absent", suffix))
        assertNull(KeymanLayoutPack.read("fr_azerty", suffix))
    }

    @Test
    fun `the digest ignores the order the zip holds its entries in`() {
        val pin = pinFor(grids)
        val reversed = zip("reversed.zip", grids.entries.reversed().associate { it.key to it.value })
        assertEquals(pin.digest, KeymanLayoutPack.digestOf(reversed, pin))
    }

    @Test
    fun `changed bytes are refused and the old pack stays`() {
        val dir = folder.newFolder()
        val pin = pinFor(grids)
        KeymanLayoutPack.configure(pin, dir)
        val tampered = grids + ("kmn_a$suffix" to "FIRST")
        assertFalse(KeymanLayoutPack.install(zip("tampered.zip", tampered)))
        assertTrue(KeymanLayoutPack.needsDownload)
    }

    @Test
    fun `an entry outside the pack's names or past its size is refused`() {
        val pin = pinFor(grids)
        assertNull(KeymanLayoutPack.digestOf(zip("path.zip", mapOf("../kmn_a$suffix" to "first", "kmn_b$suffix" to "second")), pin))
        assertNull(KeymanLayoutPack.digestOf(zip("big.zip", mapOf("kmn_a$suffix" to "first", "kmn_b$suffix" to "second".repeat(99))), pin))
    }

    @Test
    fun `an older pack keeps drawing until the pinned one arrives`() {
        val dir = folder.newFolder()
        zip("old.zip", grids).copyTo(File(dir, "keyman-layouts-old.zip"))
        KeymanLayoutPack.configure(pinFor(grids), dir)
        assertTrue(KeymanLayoutPack.needsDownload)
        assertFalse(KeymanLayoutPack.isMissing("asset_kmn_a"))
        assertEquals("second", KeymanLayoutPack.read("kmn_b", suffix))
    }
}

package com.wasimaster.wmkeyboard.core.settings

import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle written and read a section at a time (#1080): what lets a backup
 * be made and restored in a heap that could not hold all of it.
 */
class ConfigBackupStreamingTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }

    private fun payload(tag: String) = buildJsonObject { put("tag", JsonPrimitive(tag)) }

    private fun read(text: String): Pair<Int?, List<Pair<ConfigBackup.Section, JsonElement>>> {
        val seen = ArrayList<Pair<ConfigBackup.Section, JsonElement>>()
        val version = ConfigBackup.readSections(text.byteInputStream()) { section, element -> seen += section to element }
        return version to seen
    }

    @Test
    fun `sections come back one at a time in file order`() {
        val text = ConfigBackup.encode(
            7,
            "x",
            mapOf(
                ConfigBackup.Section.EMOJI to payload("emoji"),
                ConfigBackup.Section.SETTINGS to payload("settings"),
                ConfigBackup.Section.NOTES to payload("notes"),
            ),
        )
        val (version, seen) = read(text)
        assertEquals(7, version)
        assertEquals(
            listOf(ConfigBackup.Section.SETTINGS, ConfigBackup.Section.EMOJI, ConfigBackup.Section.NOTES),
            seen.map { it.first },
        )
        assertEquals("emoji", seen[1].second.jsonObject["tag"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a bundle written before the section writer is still read`() {
        // Backups made before #1080 were one pretty-printed tree.
        val legacy = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("format", JsonPrimitive(ConfigBackup.FORMAT))
                put("version", JsonPrimitive(1))
                put("appVersion", JsonPrimitive(39))
                put("appVersionName", JsonPrimitive("0.6.4"))
                put(
                    "sections",
                    buildJsonObject {
                        put("settings", payload("s"))
                        put("clipboard", payload("c"))
                    },
                )
            },
        )
        assertTrue(ConfigBackup.isComplete(legacy.byteInputStream()))
        val (version, seen) = read(legacy)
        assertEquals(39, version)
        assertEquals(listOf(ConfigBackup.Section.SETTINGS, ConfigBackup.Section.CLIPBOARD), seen.map { it.first })
    }

    @Test
    fun `another format or a newer version hands nothing over`() {
        val other = """{"format":"other","version":1,"sections":{"settings":{}}}"""
        val newer = """{"format":"${ConfigBackup.FORMAT}","version":${ConfigBackup.VERSION + 1},"sections":{"settings":{}}}"""
        for (text in listOf(other, newer)) {
            val (version, seen) = read(text)
            assertNull(text, version)
            assertTrue(text, seen.isEmpty())
        }
    }

    @Test
    fun `a file naming its format after its sections is held, then handed over`() {
        val text = """{"sections":{"settings":{"a":1}},"format":"${ConfigBackup.FORMAT}","version":1}"""
        val (version, seen) = read(text)
        assertEquals(0, version)
        assertEquals(listOf(ConfigBackup.Section.SETTINGS), seen.map { it.first })
        val newer = """{"sections":{"settings":{}},"format":"${ConfigBackup.FORMAT}","version":99}"""
        assertTrue(read(newer).second.isEmpty())
    }

    @Test
    fun `an unknown section is dropped and the rest still read`() {
        val text = """{"format":"${ConfigBackup.FORMAT}","version":1,"sections":{"later":{"x":1},"emoji":{}}}"""
        assertEquals(listOf(ConfigBackup.Section.EMOJI), read(text).second.map { it.first })
    }

    @Test
    fun `whatever follows the document is not read`() {
        // An export from before the truncating write could keep an older,
        // longer file's tail; firstJsonDocument forgives that on the text path.
        val text = ConfigBackup.encode(1, "", mapOf(ConfigBackup.Section.SETTINGS to payload("s"))) + "\"tail\": 1}}"
        assertTrue(ConfigBackup.isComplete(text.byteInputStream()))
        assertEquals(1, read(text).second.size)
    }

    @Test
    fun `each section is built only once the one before it is written`() = runBlocking {
        val out = ByteArrayOutputStream()
        val sizesWhenBuilt = ArrayList<Int>()
        ConfigBackup.encodeSectionsTo(
            out,
            1,
            "",
            setOf(ConfigBackup.Section.SETTINGS, ConfigBackup.Section.THEMES, ConfigBackup.Section.EMOJI),
        ) { section ->
            sizesWhenBuilt += out.size()
            if (section == ConfigBackup.Section.THEMES) null else payload("x".repeat(10_000))
        }
        // Built in section order, each after everything before it is out.
        assertEquals(3, sizesWhenBuilt.size)
        assertTrue(sizesWhenBuilt[1] > 10_000)
        assertEquals(sizesWhenBuilt[1], sizesWhenBuilt[2])
        val (_, seen) = read(out.toString(Charsets.UTF_8.name()))
        // A section built as null is left out, as an empty store is.
        assertEquals(listOf(ConfigBackup.Section.SETTINGS, ConfigBackup.Section.EMOJI), seen.map { it.first })
    }

    @Test
    fun `a cut bundle is not complete, so nothing is restored from it`() {
        val text = ConfigBackup.encode(1, "", mapOf(ConfigBackup.Section.SETTINGS to payload("s".repeat(5_000))))
        assertFalse(ConfigBackup.isComplete(text.dropLast(3).byteInputStream()))
    }

    @Test
    fun `a large encrypted backup round-trips a section at a time`() = runBlocking {
        // Twelve sections of 2 MiB each, sealed into a file and read back
        // through the cipher and the section reader, never as one string.
        val sections = listOf(
            ConfigBackup.Section.SETTINGS,
            ConfigBackup.Section.THEMES,
            ConfigBackup.Section.DICTIONARY,
            ConfigBackup.Section.CLIPBOARD,
            ConfigBackup.Section.SNIPPETS,
            ConfigBackup.Section.STICKERS,
            ConfigBackup.Section.ICONS,
            ConfigBackup.Section.WORDLISTS,
            ConfigBackup.Section.EMOJI,
            ConfigBackup.Section.STATISTICS,
            ConfigBackup.Section.VOCAB,
            ConfigBackup.Section.NOTES,
        )
        val size = 2 * 1024 * 1024
        val file = File.createTempFile("wmkb-bundle", ".enc")
        try {
            file.outputStream().buffered().use { raw ->
                BackupCrypto.encrypting(raw, passphrase, salt, 1_000, 1_000).buffered().use { out ->
                    ConfigBackup.encodeSectionsTo(out, 3, "big", sections.toSet()) { section ->
                        buildJsonObject { put("data", JsonPrimitive(section.id.first().toString().repeat(size))) }
                    }
                }
            }
            assertTrue(file.length() > sections.size.toLong() * size)

            // As the automatic backup checks its staged file.
            file.inputStream().use { raw ->
                val plain = requireNotNull(BackupCrypto.decrypting(raw, passphrase)).buffered()
                assertTrue(ConfigBackup.isComplete(plain))
            }
            // As a restore reads it.
            val seen = ArrayList<ConfigBackup.Section>()
            val version = file.inputStream().use { raw ->
                val plain = requireNotNull(BackupCrypto.decrypting(raw, passphrase)).buffered()
                ConfigBackup.readSections(plain) { section, element ->
                    val data = element.jsonObject["data"]?.jsonPrimitive?.content.orEmpty()
                    assertEquals(section.id, size, data.length)
                    assertEquals(section.id, section.id.first(), data.first())
                    seen += section
                }
            }
            assertEquals(3, version)
            assertEquals(sections, seen)
        } finally {
            file.delete()
        }
    }
}

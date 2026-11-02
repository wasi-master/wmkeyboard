package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTest {

    private fun bundle(
        sections: Map<ConfigBackup.Section, JsonObject> = mapOf(
            ConfigBackup.Section.SETTINGS to buildJsonObject { },
        ),
        appVersion: Int = 42,
    ) = ConfigBackup.encode(appVersion, "1.2.3", sections)

    // ---- checking a staged file without building it (#1080) ----

    @Test
    fun `a whole bundle is complete and a cut one is not`() {
        val payload = buildJsonObject { put("a", JsonPrimitive("x".repeat(100_000))) }
        val text = bundle(sections = mapOf(ConfigBackup.Section.SETTINGS to payload))
        assertTrue(ConfigBackup.isComplete(text.byteInputStream()))
        assertFalse(ConfigBackup.isComplete(text.dropLast(20).byteInputStream()))
        assertFalse(ConfigBackup.isComplete(text.take(text.length / 2).byteInputStream()))
    }

    @Test
    fun `another format or a newer version is not complete`() {
        assertFalse(ConfigBackup.isComplete("""{"format":"other","version":1,"sections":{}}""".byteInputStream()))
        assertFalse(
            ConfigBackup.isComplete(
                """{"format":"${ConfigBackup.FORMAT}","version":${ConfigBackup.VERSION + 1},"sections":{}}""".byteInputStream(),
            ),
        )
    }

    @Test
    fun `the stream encoder writes what the string encoder writes`() {
        val sections = mapOf(ConfigBackup.Section.SETTINGS to buildJsonObject { put("a", JsonPrimitive(1)) })
        val out = java.io.ByteArrayOutputStream()
        ConfigBackup.encodeTo(out, 42, "1.2.3", sections)
        assertEquals(ConfigBackup.encode(42, "1.2.3", sections), out.toString(Charsets.UTF_8.name()))
    }

    // ---- round trip ----

    @Test
    fun `a bundle round-trips its sections and app version`() {
        val payload = buildJsonObject { put("a", JsonPrimitive(1)) }
        val text = bundle(
            sections = mapOf(
                ConfigBackup.Section.SETTINGS to payload,
                ConfigBackup.Section.EMOJI to payload,
            ),
        )
        val parsed = requireNotNull(ConfigBackup.decode(text))
        assertEquals(42, parsed.appVersion)
        assertEquals(
            setOf(ConfigBackup.Section.SETTINGS, ConfigBackup.Section.EMOJI),
            parsed.sections.keys,
        )
        assertTrue(parsed.has(ConfigBackup.Section.EMOJI))
        assertFalse(parsed.has(ConfigBackup.Section.THEMES))
    }

    @Test
    fun `sections are written in a stable order whatever the caller passed`() {
        val payload = buildJsonObject { }
        val forwards = ConfigBackup.encode(
            1,
            "x",
            linkedMapOf(
                ConfigBackup.Section.SETTINGS to payload,
                ConfigBackup.Section.EMOJI to payload,
            ),
        )
        val backwards = ConfigBackup.encode(
            1,
            "x",
            linkedMapOf(
                ConfigBackup.Section.EMOJI to payload,
                ConfigBackup.Section.SETTINGS to payload,
            ),
        )
        assertEquals(forwards, backwards)
    }

    @Test
    fun `clock encodes after notes and the to-do list comes last`() {
        // Sections encode in declaration order and older builds read bundles
        // positionally, so a new section may only ever be appended.
        assertEquals(ConfigBackup.Section.TODO, ConfigBackup.Section.entries.last())
        assertEquals(ConfigBackup.Section.AI_MEMORY, ConfigBackup.Section.entries[ConfigBackup.Section.entries.size - 2])
        val payload = buildJsonObject { put("a", JsonPrimitive(1)) }
        val text = ConfigBackup.encode(
            1,
            "x",
            linkedMapOf(
                ConfigBackup.Section.CLOCK to payload,
                ConfigBackup.Section.NOTES to payload,
                ConfigBackup.Section.SWIPE to payload,
            ),
        )
        assertTrue(text.indexOf("\"swipe\"") < text.indexOf("\"notes\""))
        assertTrue(text.indexOf("\"notes\"") < text.indexOf("\"clock\""))
        val parsed = requireNotNull(ConfigBackup.decode(text))
        assertTrue(parsed.has(ConfigBackup.Section.NOTES))
        assertTrue(parsed.has(ConfigBackup.Section.CLOCK))
    }

    @Test
    fun `AI memory is never a sync section`() {
        assertFalse(ConfigBackup.Section.AI_MEMORY.syncable)
        assertTrue(ConfigBackup.Section.entries.filter { it != ConfigBackup.Section.AI_MEMORY }.all { it.syncable })
    }

    @Test
    fun `something else entirely is not a bundle`() {
        assertNull(ConfigBackup.decode("not json at all"))
        assertNull(ConfigBackup.decode("{}"))
        assertNull(ConfigBackup.decode("""{"format":"wmkeyboard-settings","sections":{}}"""))
        // The right format, but no sections object to read.
        assertNull(ConfigBackup.decode("""{"format":"${ConfigBackup.FORMAT}"}"""))
    }

    @Test
    fun `a section this app has never heard of is dropped, not fatal`() {
        val text = """
            {"format":"${ConfigBackup.FORMAT}","version":1,"appVersion":1,
             "sections":{"settings":{},"telepathy":{"on":true}}}
        """.trimIndent()
        val parsed = ConfigBackup.decode(text)
        assertEquals(setOf(ConfigBackup.Section.SETTINGS), parsed?.sections?.keys)
    }

    // ---- the version gate ----

    @Test
    fun `a bundle from a newer format is refused rather than half-applied`() {
        val text = """
            {"format":"${ConfigBackup.FORMAT}","version":${ConfigBackup.VERSION + 1},
             "appVersion":1,"sections":{"settings":{}}}
        """.trimIndent()
        assertNull(ConfigBackup.decode(text))
    }

    @Test
    fun `this format and an older one are both still read`() {
        assertNotNull(ConfigBackup.decode(bundle()))
        val older = """
            {"format":"${ConfigBackup.FORMAT}","version":0,"appVersion":1,
             "sections":{"settings":{}}}
        """.trimIndent()
        assertNotNull(ConfigBackup.decode(older))
    }

    @Test
    fun `a bundle with no version at all is assumed to be ours`() {
        // Nothing has ever written one without it, but a hand-edited file is a
        // thing people do, and refusing it would be unhelpful rather than safe.
        val text = """
            {"format":"${ConfigBackup.FORMAT}","appVersion":1,"sections":{"settings":{}}}
        """.trimIndent()
        assertNotNull(ConfigBackup.decode(text))
    }

    // ---- the empty-list-versus-parse-failure rule ----

    @Test
    fun `nothing decoded out of something is a failure, not an empty section`() {
        assertNull(ConfigBackup.decodedList(emptyList<String>(), encodedSize = 3))
    }

    @Test
    fun `nothing decoded out of nothing is an empty section`() {
        assertEquals(
            emptyList<String>(),
            ConfigBackup.decodedList(emptyList<String>(), encodedSize = 0),
        )
    }

    @Test
    fun `a decode that worked is passed through`() {
        assertEquals(listOf("a", "b"), ConfigBackup.decodedList(listOf("a", "b"), encodedSize = 2))
        // A codec that dropped one entry of three still decoded something, and
        // partial is not the failure this rule is looking for.
        assertEquals(listOf("a"), ConfigBackup.decodedList(listOf("a"), encodedSize = 3))
    }

    @Test
    fun `a decode that threw stays null`() {
        assertNull(ConfigBackup.decodedList<String>(null, encodedSize = 0))
        assertNull(ConfigBackup.decodedList<String>(null, encodedSize = 3))
    }

    // ---- what must never travel in a bundle ----

    @Test
    fun `this install's own backup settings are left out of an export`() {
        // Otherwise a restore onto a new phone switches automatic backup on,
        // pointing at a folder URI granted to a device that is not this one.
        val prefs = mutablePreferencesOf().apply {
            this[booleanPreferencesKey(SettingsBackup.AUTO_BACKUP_ENABLED)] = true
            this[stringPreferencesKey(SettingsBackup.AUTO_BACKUP_FOLDER_URI)] = "content://tree/1"
            this[stringPreferencesKey(SettingsBackup.AUTO_BACKUP_KDF_SALT)] = "c2FsdA=="
            this[stringPreferencesKey(SettingsBackup.AUTO_BACKUP_LAST_ERROR)] = "PERMISSION_LOST"
            this[booleanPreferencesKey("auto_backup_encrypt")] = true
            this[booleanPreferencesKey("keep_me")] = true
        }
        val encoded = SettingsBackup.encodeSettings(
            prefs,
            includeSecrets = true,
            exclude = SettingsBackup.TRANSIENT_KEYS,
        )
        for (key in SettingsBackup.TRANSIENT_KEYS) {
            assertFalse(key, encoded.containsKey(key))
        }
        // The user's actual choices are not transient and do travel.
        assertTrue(encoded.containsKey("auto_backup_encrypt"))
        assertTrue(encoded.containsKey("keep_me"))
    }

    @Test
    fun `the standalone settings export drops them too`() {
        val prefs = mutablePreferencesOf().apply {
            this[stringPreferencesKey(SettingsBackup.AUTO_BACKUP_FOLDER_URI)] = "content://tree/1"
        }
        val text = SettingsBackup.encode(prefs, includeSecrets = true, appVersion = 1, "1")
        assertFalse(text.contains(SettingsBackup.AUTO_BACKUP_FOLDER_URI))
    }

    @Test
    fun `the backup passphrase is treated as a credential`() {
        assertTrue(SettingsBackup.AUTO_BACKUP_PASSPHRASE in SettingsBackup.SECRET_KEYS)
        val prefs = mutablePreferencesOf().apply {
            this[stringPreferencesKey(SettingsBackup.AUTO_BACKUP_PASSPHRASE)] = "hunter2"
        }
        val withheld = SettingsBackup.encodeSettings(prefs, includeSecrets = false)
        assertFalse(withheld.containsKey(SettingsBackup.AUTO_BACKUP_PASSPHRASE))
        val included = SettingsBackup.encodeSettings(prefs, includeSecrets = true)
        assertTrue(included.containsKey(SettingsBackup.AUTO_BACKUP_PASSPHRASE))
    }

    @Test
    fun `the two exclusion sets do not overlap`() {
        // They are applied together in the config bundle and separately in the
        // standalone file, so an overlap would mean one of them is a lie about
        // why a key is missing.
        assertTrue((SettingsBackup.TRANSIENT_KEYS intersect SettingsBackup.SECRET_KEYS).isEmpty())
        assertTrue((SettingsBackup.TRANSIENT_KEYS intersect SettingsBackup.THEME_KEYS).isEmpty())
    }
}

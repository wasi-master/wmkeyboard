package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SettingsHistoryTest {

    private val height = intPreferencesKey("key_height")
    private val sound = booleanPreferencesKey("key_sound")
    private val signal = intPreferencesKey("lexicon_version")
    private val secret = stringPreferencesKey("deepl_api_key")

    // `key to value` is DataStore's own pair here, a member of the key.
    private fun map(vararg pairs: Preferences.Pair<*>): Map<Preferences.Key<*>, Any> =
        mutablePreferencesOf(*pairs).asMap()

    @Test
    fun `diff keeps only tracked keys that changed`() {
        val before = map(height to 52, sound to true, signal to 1, secret to "a")
        val after = map(height to 56, sound to true, signal to 2, secret to "b")
        val changes = SettingsHistory.diff(before, after)
        assertEquals(listOf("key_height"), changes.map { it.key })
        assertEquals(52, changes[0].before?.value)
        assertEquals(56, changes[0].after?.value)
    }

    @Test
    fun `a key that appears or goes has a null side`() {
        val changes = SettingsHistory.diff(map(height to 52), map(sound to false))
        val byKey = changes.associateBy { it.key }
        assertNull(byKey.getValue("key_height").after)
        assertNull(byKey.getValue("key_sound").before)
    }

    @Test
    fun `plan writes what differs and removes what the point lacked`() {
        val point = listOf(SettingsBackup.Entry("key_height", "int", 52))
        val plan = SettingsHistory.plan(point, map(height to 56, sound to false, signal to 3))
        assertEquals(listOf("key_height"), plan.set.map { it.name })
        // The signal is not a setting: a restore leaves it alone.
        assertEquals(setOf("key_sound"), plan.remove)
    }

    @Test
    fun `revert takes the value from before the oldest batch`() {
        fun batch(id: Long, at: Long, before: Int, after: Int) = SettingsHistory.Batch(
            id, at, SettingsHistory.Kind.EDIT,
            listOf(
                SettingsHistory.Change(
                    "key_height",
                    SettingsBackup.Entry("key_height", "int", before),
                    SettingsBackup.Entry("key_height", "int", after),
                ),
            ),
        )
        val revert = SettingsHistory.revertOf(listOf(batch(2, 200, 54, 56), batch(1, 100, 52, 54)))
        assertEquals(52, revert.getValue("key_height")?.value)
    }

    @Test
    fun `quick changes to one setting coalesce, and cancel out when back where they started`() = runBlocking {
        var now = 1_000L
        val dir = Files.createTempDirectory("history").toFile()
        val history = SettingsHistory(dir) { now }
        fun change(before: Int, after: Int) = listOf(
            SettingsHistory.Change(
                "key_height",
                SettingsBackup.Entry("key_height", "int", before),
                SettingsBackup.Entry("key_height", "int", after),
            ),
        )
        history.record(SettingsHistory.Kind.EDIT, change(52, 54))
        now += 500
        history.record(SettingsHistory.Kind.EDIT, change(54, 58))
        assertEquals(1, history.batches.value.size)
        assertEquals(58, history.batches.value[0].changes[0].after?.value)
        now += 500
        history.record(SettingsHistory.Kind.EDIT, change(58, 52))
        assertTrue(history.batches.value.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `an undo marks what it undid, and the file reads back`() = runBlocking {
        var now = 1_000L
        val dir = Files.createTempDirectory("history").toFile()
        val history = SettingsHistory(dir) { now }
        val edit = history.record(
            SettingsHistory.Kind.EDIT,
            listOf(SettingsHistory.Change("key_sound", null, SettingsBackup.Entry("key_sound", "boolean", false))),
        )!!
        now += 10_000
        history.record(
            SettingsHistory.Kind.UNDO,
            listOf(SettingsHistory.Change("key_sound", SettingsBackup.Entry("key_sound", "boolean", false), null)),
            undid = listOf(edit.id),
        )
        assertNull(history.lastUndoable())
        val reread = SettingsHistory(dir) { now }
        reread.load()
        assertEquals(2, reread.batches.value.size)
        assertTrue(reread.batches.value.first { it.id == edit.id }.undone)
        assertFalse(reread.batches.value.first { it.id != edit.id }.undoable)
        dir.deleteRecursively()
    }

    @Test
    fun `a restore point holds settings and not secrets`() = runBlocking {
        val dir = Files.createTempDirectory("history").toFile()
        val history = SettingsHistory(dir)
        val prefs = mutablePreferencesOf(height to 52, secret to "k", signal to 4)
        val point = history.createPoint(prefs, "  Test  ", "1.0")
        assertEquals("Test", point.name)
        assertEquals(1, point.count)
        assertEquals(listOf("key_height"), history.pointEntries(point.id)?.map { it.name })
        dir.deleteRecursively()
    }
}

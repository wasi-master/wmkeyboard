package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupScheduleTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_754_575_353_000L

    private fun settings(lastRunAtMs: Long, intervalHours: Int = 24) =
        AutoBackupSettings(lastRunAtMs = lastRunAtMs, intervalHours = intervalHours)

    @Test
    fun `a backup that has never run is due`() {
        assertTrue(AutoBackupRunner.isDue(settings(lastRunAtMs = 0), now))
    }

    @Test
    fun `the interval has to have elapsed`() {
        assertFalse(AutoBackupRunner.isDue(settings(now - 23 * hour), now))
        assertTrue(AutoBackupRunner.isDue(settings(now - 24 * hour), now))
        assertTrue(AutoBackupRunner.isDue(settings(now - 400 * hour), now))
    }

    @Test
    fun `the interval is the one the user set`() {
        assertFalse(AutoBackupRunner.isDue(settings(now - 5 * hour, intervalHours = 6), now))
        assertTrue(AutoBackupRunner.isDue(settings(now - 6 * hour, intervalHours = 6), now))
    }

    @Test
    fun `a last run in the future does not postpone the next one`() {
        // The clock moved back, or a bundle from another device carried its
        // timestamp in. Waiting for the stamp to come round again could mean
        // waiting weeks, with nothing anywhere saying why.
        assertTrue(AutoBackupRunner.isDue(settings(now + 400 * hour), now))
    }

    @Test
    fun `a nonsense interval cannot stop backups altogether`() {
        assertTrue(AutoBackupRunner.isDue(settings(now - hour, intervalHours = 0), now))
        assertTrue(AutoBackupRunner.isDue(settings(now - hour, intervalHours = -3), now))
    }

    @Test
    fun `the default is a daily backup`() {
        assertFalse(AutoBackupRunner.isDue(AutoBackupSettings(lastRunAtMs = now - 23 * hour), now))
        assertTrue(AutoBackupRunner.isDue(AutoBackupSettings(lastRunAtMs = now - 25 * hour), now))
    }

    private val mb = 1024L * 1024L

    @Test
    fun `only the largest heavy sections are left out`() {
        // Issue #692: big sticker packs used to take the word lists and the
        // vocabulary out with them.
        val sizes = mapOf(
            ConfigBackup.Section.STICKERS to 40 * mb,
            ConfigBackup.Section.ICONS to 3 * mb,
            ConfigBackup.Section.WORDLISTS to 1 * mb,
            ConfigBackup.Section.VOCAB to 0L,
        )
        val dropped = AutoBackupRunner.sectionsToDrop(sizes.keys, 16 * mb) { sizes.getValue(it) }
        assertEquals(setOf(ConfigBackup.Section.STICKERS), dropped)
    }

    @Test
    fun `nothing is left out under the limit`() {
        val sizes = mapOf(ConfigBackup.Section.STICKERS to 10 * mb, ConfigBackup.Section.ICONS to 5 * mb)
        assertEquals(emptySet<ConfigBackup.Section>(), AutoBackupRunner.sectionsToDrop(sizes.keys, 16 * mb) { sizes.getValue(it) })
    }

    @Test
    fun `a periodic job may fire an eighth of its period early`() {
        assertEquals(90L * 60L * 1000L, AutoBackupScheduler.flexMs(12))
        // An interval under an hour is read as one.
        assertEquals(AutoBackupScheduler.flexMs(1), AutoBackupScheduler.flexMs(0))
    }
}

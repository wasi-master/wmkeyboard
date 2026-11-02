package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationExportTest {

    @Test
    fun `a blank request is the settings and the dictionary`() {
        val expected = setOf(ConfigBackup.Section.SETTINGS, ConfigBackup.Section.DICTIONARY)
        assertEquals(expected, AutomationExport.parseSections(null))
        assertEquals(expected, AutomationExport.parseSections(" , "))
    }

    @Test
    fun `sections are read by id in any case`() {
        assertEquals(
            setOf(ConfigBackup.Section.DICTIONARY, ConfigBackup.Section.SNIPPETS),
            AutomationExport.parseSections("Dictionary, snippets"),
        )
    }

    @Test
    fun `the clipboard and the notes cannot be asked for`() {
        assertFalse(ConfigBackup.Section.CLIPBOARD in AutomationExport.EXPORTABLE)
        assertFalse(ConfigBackup.Section.NOTES in AutomationExport.EXPORTABLE)
        assertNull(AutomationExport.parseSections("dictionary,clipboard"))
        assertNull(AutomationExport.parseSections("everything"))
    }

    @Test
    fun `the log reads back what it wrote, cut to its limit`() {
        val records = List(AutomationExport.MAX_LOG + 5) { i ->
            AutomationExportRecord(1000L + i, "settings, dictionary", if (i % 2 == 0) "net.dinglisch.android.taskerm" else "", i == 1)
        }
        assertEquals(records.take(AutomationExport.MAX_LOG), AutomationExportRecord.decodeLog(AutomationExportRecord.encodeLog(records)))
    }

    @Test
    fun `an unreadable log line is dropped, not fatal`() {
        assertEquals(emptyList<AutomationExportRecord>(), AutomationExportRecord.decodeLog(""))
        assertEquals(1, AutomationExportRecord.decodeLog("junk\n5\tsettings\t\t0").size)
    }
}

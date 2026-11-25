package com.wasimaster.wmkeyboard.core.settings.foreign

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Other keyboards' clipboard histories (#1542), as Import into a new clipboard reads them. */
class ForeignClipboardTest {

    @Test
    fun `FUTO's clipboard file keeps text, time and pin and drops pictures`() {
        val clips = ForeignClipboard.fromJson(
            """[{"timestamp":2000,"pinned":true,"text":"pinned one","uri":null,"mimeTypes":["text/plain"]},
               {"timestamp":1000,"pinned":false,"text":null,"uri":"content://x","mimeTypes":["image/png"]},
               {"timestamp":3000,"pinned":false,"text":"newest","uri":null,"mimeTypes":["text/plain"]}]""",
        )!!
        assertEquals(listOf("newest", "pinned one"), clips.map { it.text })
        assertTrue(clips.last().pinned)
        assertEquals(3000L, clips.first().timestamp)
    }

    @Test
    fun `FlorisBoard's text items skip sensitive ones and media`() {
        val clips = ForeignClipboard.fromJson(
            """[{"type":"TEXT","text":"hello","creationTimestampMs":5,"isPinned":false,"mimeTypes":[],"isSensitive":false},
               {"type":"TEXT","text":"hunter2","creationTimestampMs":6,"isPinned":false,"mimeTypes":[],"isSensitive":true},
               {"type":"IMAGE","text":"pic","creationTimestampMs":7,"isPinned":false,"mimeTypes":[]}]""",
        )!!
        assertEquals(listOf("hello"), clips.map { it.text })
    }

    @Test
    fun `a pinned list marks its clips pinned and a repeated text comes once`() {
        val clips = ForeignClipboard.fromJson(
            """{"version":4,"history":[{"content":"a","timestamp":10}],"pinned":[{"content":"a","timestamp":5},{"content":"b"}]}""",
        )!!
        assertEquals(setOf("a", "b"), clips.map { it.text }.toSet())
        assertTrue(clips.all { it.pinned })
    }

    @Test
    fun `plain text is not a clipboard file`() {
        assertNull(ForeignClipboard.fromJson("just a line\nand another"))
    }

    @Test
    fun `a backup zip's clipboard entry is found among the rest`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("FUTOKeyboardSettings_CfgExportVersion"))
                z.write("1".toByteArray())
                z.putNextEntry(ZipEntry("clipboard.json"))
                z.write("""[{"timestamp":1,"pinned":false,"text":"from futo","uri":null,"mimeTypes":[]}]""".toByteArray())
            }
        }.toByteArray()
        val clips = ForeignClipboard.readZip(ByteArrayInputStream(zip))!!
        assertEquals(listOf("from futo"), clips.map { it.text })
    }
}

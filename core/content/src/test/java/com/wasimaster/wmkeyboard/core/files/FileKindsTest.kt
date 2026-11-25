package com.wasimaster.wmkeyboard.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindsTest {

    @Test
    fun theExtensionDecidesWhereItIsKnown() {
        assertEquals(FileKind.IMAGE, FileKinds.of("IMG_1.JPG", ""))
        assertEquals(FileKind.PDF, FileKinds.of("a.pdf", "application/octet-stream"))
        assertEquals(FileKind.SHEET, FileKinds.of("budget.xlsx", ""))
        assertEquals(FileKind.APK, FileKinds.of("NewPipe.apk", ""))
        assertEquals(FileKind.CODE, FileKinds.of("main.ts", "video/mp2t"))
        assertEquals(FileKind.ARCHIVE, FileKinds.of("thesis.zip", ""))
    }

    @Test
    fun theTypeDecidesForAnUnknownName() {
        assertEquals(FileKind.AUDIO, FileKinds.of("recording", "audio/ogg"))
        assertEquals(FileKind.TEXT, FileKinds.of("README", "text/plain"))
        assertEquals(FileKind.OTHER, FileKinds.of("blob", ""))
    }

    @Test
    fun aFolderIsAFolder() {
        assertEquals(FileKind.FOLDER, FileKinds.of(FileEntry("/x/a.zip", "a.zip", true, 0, 0, "")))
    }

    @Test
    fun badgesAreShortExtensions() {
        assertEquals("JPG", FileKinds.badgeOf("a.jpg"))
        assertEquals("XLSX", FileKinds.badgeOf("a.xlsx"))
        assertEquals("", FileKinds.badgeOf(".hidden"))
        assertEquals("", FileKinds.badgeOf("noext"))
        assertEquals("", FileKinds.badgeOf("v1.0-final-release"))
    }

    @Test
    fun textualKindsAreTextAndCode() {
        assertTrue(FileKinds.isTextual(FileKind.TEXT))
        assertTrue(FileKinds.isTextual(FileKind.CODE))
        assertFalse(FileKinds.isTextual(FileKind.PDF))
        assertEquals("kotlin", FileKinds.codeLanguage("Main.kt"))
        assertEquals("python", FileKinds.codeLanguage("x.py"))
    }
}

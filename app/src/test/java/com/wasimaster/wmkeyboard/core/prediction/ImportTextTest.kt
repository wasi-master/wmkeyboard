package com.wasimaster.wmkeyboard.core.prediction

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Word-list bytes in the encodings Windows Japanese input exports (gap list item 20). */
class ImportTextTest {

    private val msIme = "!Microsoft IME Dictionary Tool\r\nよろしく\t宜しく\t名詞\r\nがっこう\t学校\t名詞\r\n"

    private fun utf8(bytes: ByteArray) = String(ImportText.toUtf8(bytes), Charsets.UTF_8)

    @Test fun utf16LittleEndianWithBomBecomesUtf8() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + msIme.toByteArray(Charsets.UTF_16LE)
        assertEquals(msIme, utf8(bytes))
    }

    @Test fun utf16BigEndianWithBomBecomesUtf8() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + msIme.toByteArray(Charsets.UTF_16BE)
        assertEquals(msIme, utf8(bytes))
    }

    @Test fun bareUtf16IsFoundByTheNulsOfItsTabsAndBreaks() {
        assertEquals(msIme, utf8(msIme.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun shiftJisWithKanaBecomesUtf8() {
        val atok = "よろしく\t宜しく\t名詞\r\n"
        assertEquals(atok, utf8(atok.toByteArray(Charset.forName("windows-31j"))))
    }

    @Test fun utf8StaysAsItIsAndLosesOnlyItsBom() {
        val plain = "hello 120\nworld 80\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(plain, ImportText.toUtf8(plain))
        val japanese = msIme.toByteArray(Charsets.UTF_8)
        assertArrayEquals(japanese, ImportText.toUtf8(japanese))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + plain
        assertArrayEquals(plain, ImportText.toUtf8(bom))
    }

    @Test fun binaryIsLeftForTheTextSniffToRefuse() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48)
        assertArrayEquals(png, ImportText.toUtf8(png))
    }
}

package com.wasimaster.wmkeyboard.core.theme

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codes are built here the way Kyng writes them (tagged fields, varint
 * lengths, raw deflate, URL-safe base64 without padding), since no real code
 * may be copied into the repository.
 */
class KyngShareCodeTest {

    private val colours = intArrayOf(
        0xFF101820.toInt(), 0xFF2A3440.toInt(), 0xFF3C4A5A.toInt(), 0xFF1E2630.toInt(),
        0xFF303A46.toInt(), 0xFFF0F4F8.toInt(), 0xFF9AA8B6.toInt(), 0xFFFFB347.toInt(),
        0x33FFFFFF, 0x66000000, 0xFF2A3440.toInt(), 0xFFFFFFFF.toInt(),
    )

    @Test
    fun `a code with its own palette becomes a theme of its colours`() {
        val code = code(name = "Harbour", picks = byteArrayOf(2, 5, 3, 0, 6, 0x81.toByte(), 0x01), palette = palette("Night"))
        val result = KyngShareCode.read("Try my theme: $code !", "kyng_1")
        assertTrue(result.toString(), result is KyngShareCode.Result.Converted)
        val converted = result as KyngShareCode.Result.Converted
        val theme = converted.theme
        assertEquals("Harbour", theme.name)
        assertEquals("kyng_1", theme.id)
        assertTrue(theme.dark)
        assertEquals(0xFF101820L, theme.boardBackground)
        assertEquals(0xFF2A3440L, theme.keyBackground)
        assertEquals(0xFF3C4A5AL, theme.pressedKeyBackground)
        assertEquals(0xFF1E2630L, theme.modifierKeyBackground)
        assertEquals(0xFFF0F4F8L, theme.keyText)
        assertEquals(0xFF9AA8B6L, theme.hintText)
        assertEquals(0xFFFFB347L, theme.accent)
        assertEquals(0xFFFFB347L, theme.enterKeyBackground)
        assertEquals(0xFF000000L, theme.enterKeyText)
        assertEquals(0xFFFFFFFFL, theme.popupText)
        // The style (2) and effect (6, index 129) are Kyng's catalog; palette 0 is the code's own.
        assertEquals(setOf(KyngShareCode.Part.STYLE, KyngShareCode.Part.EFFECT), converted.catalogParts)
    }

    @Test
    fun `an uncompressed code reads too`() {
        val code = code(name = "Plain", picks = byteArrayOf(), palette = palette(""), deflate = false)
        val result = KyngShareCode.read(code, "k") as KyngShareCode.Result.Converted
        assertEquals("Plain", result.theme.name)
    }

    @Test
    fun `a code using one of Kyng's palettes has no colours to bring`() {
        val code = code(name = "Sunset", picks = byteArrayOf(3, 17), palette = null)
        assertEquals(KyngShareCode.Result.CatalogPaletteOnly("Sunset"), KyngShareCode.read(code, "k"))
    }

    @Test
    fun `text without a code, and a broken code, say which`() {
        assertEquals(KyngShareCode.Result.NotACode, KyngShareCode.read("hello", "k"))
        assertEquals(KyngShareCode.Result.Damaged, KyngShareCode.read("KBTH1.AAAA", "k"))
        val good = code(name = "X", picks = byteArrayOf(), palette = palette("p"))
        assertEquals(KyngShareCode.Result.Damaged, KyngShareCode.read(good.dropLast(6), "k"))
    }

    private fun palette(name: String): ByteArray {
        val nameBytes = name.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(nameBytes.size)
        out.write(nameBytes)
        for (c in colours) {
            out.write(c ushr 24)
            out.write(c ushr 16 and 0xFF)
            out.write(c ushr 8 and 0xFF)
            out.write(c and 0xFF)
        }
        return out.toByteArray()
    }

    private fun code(name: String, picks: ByteArray, palette: ByteArray?, deflate: Boolean = true): String {
        val body = ByteArrayOutputStream()
        fun field(tag: Int, bytes: ByteArray) {
            body.write(tag)
            var n = bytes.size
            while (n >= 128) {
                body.write(128 or (n and 127))
                n = n ushr 7
            }
            body.write(n)
            body.write(bytes)
        }
        field(1, name.toByteArray())
        field(3, picks)
        if (palette != null) field(4, palette)
        val payload = body.toByteArray()
        val packed = if (deflate) {
            val d = Deflater(9, true)
            d.setInput(payload)
            d.finish()
            val buf = ByteArray(payload.size + 64)
            val n = d.deflate(buf)
            d.end()
            buf.copyOf(n)
        } else {
            payload
        }
        val framed = byteArrayOf((2 or if (deflate) 1 else 0).toByte()) + packed
        return KyngShareCode.PREFIX + base64Url(framed)
    }

    private fun base64Url(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(alphabet[n ushr 18 and 63]).append(alphabet[n ushr 12 and 63])
            if (i + 1 < bytes.size) sb.append(alphabet[n ushr 6 and 63])
            if (i + 2 < bytes.size) sb.append(alphabet[n and 63])
            i += 3
        }
        return sb.toString()
    }
}

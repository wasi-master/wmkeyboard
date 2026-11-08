package com.wasimaster.wmkeyboard.core.media

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1179: each format loses its metadata blocks and keeps the ones that draw the picture. */
class MetadataStripTest {

    private fun bytes(vararg parts: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) when (p) {
            is Int -> out.write(p)
            is String -> out.write(p.toByteArray(Charsets.ISO_8859_1))
            is ByteArray -> out.write(p)
            else -> error("part $p")
        }
        return out.toByteArray()
    }

    private fun ByteArray.has(text: String): Boolean = String(this, Charsets.ISO_8859_1).contains(text)

    /** A JPEG segment: marker, big-endian length, payload. */
    private fun segment(marker: Int, payload: ByteArray): ByteArray =
        bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF, payload)

    @Test
    fun `a JPEG keeps JFIF, ICC, tables and scan, and loses EXIF, XMP, IPTC, comments and its trailer`() {
        val jfif = segment(0xE0, bytes("JFIF", 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        val exif = segment(0xE1, bytes("Exif", 0, 0, "GPS-SECRET"))
        val xmp = segment(0xE1, bytes("http://ns.adobe.com/xap/1.0/", 0, "<x:xmpmeta>owner</x:xmpmeta>"))
        val icc = segment(0xE2, bytes("ICC_PROFILE", 0, 1, 1, "profile"))
        val mpf = segment(0xE2, bytes("MPF", 0, "second picture"))
        val iptc = segment(0xED, bytes("Photoshop 3.0", 0, "8BIM by-line"))
        val comment = segment(0xFE, bytes("made by someone"))
        val dqt = segment(0xDB, ByteArray(65) { it.toByte() })
        val sos = segment(0xDA, bytes(1, 1, 0, 0, 0x3F, 0))
        // Scan data with a stuffed 0xFF00 and a restart marker inside it.
        val scan = bytes(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56)
        val src = bytes(0xFF, 0xD8, jfif, exif, xmp, icc, mpf, iptc, comment, dqt, sos, scan, 0xFF, 0xD9, "MOTION-PHOTO-MP4")

        val out = MetadataStrip.strip(src)!!

        assertArrayEquals(bytes(0xFF, 0xD8, jfif, icc, dqt, sos, scan, 0xFF, 0xD9), out)
        listOf("GPS-SECRET", "xmpmeta", "8BIM", "made by someone", "second picture", "MOTION-PHOTO").forEach {
            assertFalse(it, out.has(it))
        }
    }

    @Test
    fun `a progressive JPEG keeps every scan and the tables between them`() {
        val sos = segment(0xDA, bytes(1, 1, 0, 0, 0x3F, 0))
        val dht = segment(0xC4, ByteArray(20) { 1 })
        val src = bytes(0xFF, 0xD8, sos, 0x01, 0x02, dht, sos, 0x03, segment(0xFE, bytes("note")), sos, 0x04, 0xFF, 0xD9)

        val out = MetadataStrip.jpeg(src)!!

        assertArrayEquals(bytes(0xFF, 0xD8, sos, 0x01, 0x02, dht, sos, 0x03, sos, 0x04, 0xFF, 0xD9), out)
    }

    @Test
    fun `a JPEG cut off in its scan is closed rather than refused`() {
        val sos = segment(0xDA, bytes(1, 1, 0, 0, 0x3F, 0))
        val out = MetadataStrip.jpeg(bytes(0xFF, 0xD8, sos, 0x11, 0x22))!!
        assertArrayEquals(bytes(0xFF, 0xD8, sos, 0x11, 0x22, 0xFF, 0xD9), out)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray =
        bytes(0, 0, (data.size shr 8) and 0xFF, data.size and 0xFF, type, data, 0, 0, 0, 0)

    @Test
    fun `a PNG keeps its image and colour chunks and loses text, EXIF and time`() {
        val sig = bytes(0x89, "PNG", 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = chunk("IHDR", ByteArray(13))
        val iccp = chunk("iCCP", bytes("icc", 0, 0, 1))
        val text = chunk("tEXt", bytes("Author", 0, "Someone"))
        val itxt = chunk("iTXt", bytes("XML:com.adobe.xmp", 0, "xmp"))
        val exif = chunk("eXIf", bytes("MM", 0, 42, "GPS"))
        val time = chunk("tIME", ByteArray(7))
        val idat = chunk("IDAT", bytes(1, 2, 3))
        val iend = chunk("IEND", ByteArray(0))
        val src = bytes(sig, ihdr, iccp, text, itxt, exif, time, idat, iend, "TRAILER")

        assertArrayEquals(bytes(sig, ihdr, iccp, idat, iend), MetadataStrip.strip(src))
    }

    private fun riffChunk(fourcc: String, data: ByteArray): ByteArray {
        val pad = if (data.size % 2 == 1) bytes(0) else ByteArray(0)
        return bytes(fourcc, data.size and 0xFF, (data.size shr 8) and 0xFF, 0, 0, data, pad)
    }

    @Test
    fun `a WebP loses its EXIF and XMP chunks and the flags that announce them`() {
        // Flags: ICC 0x20, alpha 0x10, EXIF 0x08, XMP 0x04.
        val vp8x = riffChunk("VP8X", bytes(0x20 or 0x10 or 0x08 or 0x04, 0, 0, 0, 9, 0, 0, 9, 0, 0))
        val iccp = riffChunk("ICCP", bytes("icc"))
        val image = riffChunk("VP8L", bytes(0x2F, 1, 2, 3, 4))
        val exif = riffChunk("EXIF", bytes("GPS-SECRET"))
        val xmp = riffChunk("XMP ", bytes("<x/>"))
        val body = bytes(vp8x, iccp, image, exif, xmp)
        val src = bytes("RIFF", (body.size + 4) and 0xFF, (body.size + 4) shr 8, 0, 0, "WEBP", body)

        val out = MetadataStrip.strip(src)!!

        val keptVp8x = vp8x.copyOf().also { it[8] = (0x20 or 0x10).toByte() }
        val keptBody = bytes(keptVp8x, iccp, image)
        assertArrayEquals(bytes("RIFF", (keptBody.size + 4) and 0xFF, (keptBody.size + 4) shr 8, 0, 0, "WEBP", keptBody), out)
        assertFalse(out.has("GPS-SECRET"))
    }

    @Test
    fun `a GIF keeps its frames, timing and loop and loses comments and XMP`() {
        val header = bytes("GIF89a", 1, 0, 1, 0, 0x80, 0, 0) // global table of 2 colours
        val palette = ByteArray(6)
        val loop = bytes(0x21, 0xFF, 11, "NETSCAPE2.0", 3, 1, 0, 0, 0)
        val comment = bytes(0x21, 0xFE, 5, "hello", 0)
        val xmp = bytes(0x21, 0xFF, 11, "XMP DataXMP", 4, "<x/>", 0)
        val gce = bytes(0x21, 0xF9, 4, 0, 10, 0, 0, 0)
        val frame = bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0)
        val src = bytes(header, palette, loop, comment, xmp, gce, frame, 0x3B)

        assertArrayEquals(bytes(header, palette, loop, gce, frame, 0x3B), MetadataStrip.strip(src))
    }

    @Test
    fun `formats it does not know, and damaged files, give null`() {
        assertNull(MetadataStrip.strip(bytes(0, 0, 0, 0x18, "ftypheic", 0, 0, 0, 0)))
        assertNull(MetadataStrip.strip(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x7F, 0x00, "Exif")))
        assertNull(MetadataStrip.strip(bytes("GIF89a", 1, 0, 1, 0, 0, 0, 0, 0x99)))
    }

    @Test
    fun `sniff reads the bytes, not the name`() {
        assertEquals(MetadataStrip.Format.JPEG, MetadataStrip.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(MetadataStrip.Format.GIF, MetadataStrip.sniff(bytes("GIF87a")))
        assertTrue(MetadataStrip.sniff(bytes("RIFF", 0, 0, 0, 0, "WAVE")) == null)
    }
}

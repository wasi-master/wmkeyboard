package com.wasimaster.wmkeyboard.core.media

import java.io.ByteArrayOutputStream

/**
 * Takes the metadata out of a picture's bytes without touching its pixels
 * (#1179): what is left is the compressed image and what a viewer needs to
 * draw it right, nothing about who took it, where, when or with what.
 *
 * Each format is walked block by block and rebuilt from the blocks on a
 * short list of ones that only describe the image. Anything else goes: EXIF
 * (with its GPS, camera, serial numbers and dates), XMP, IPTC, comments,
 * maker notes, text chunks, and whatever is stored after the image's end (a
 * motion photo's video, a second picture, a phone maker's trailer). The
 * colour profile stays, or the picture would arrive in the wrong colours.
 *
 * The EXIF orientation goes with the rest; the caller writes it back on its
 * own (see `MediaScrub`), so a sideways photo still arrives upright.
 *
 * Pure byte work, no Android: everything here is unit tested on the JVM.
 * A return of null means the bytes are not one of these four formats, or
 * are damaged in a way this walk will not guess past.
 */
object MetadataStrip {

    enum class Format(val mime: String) {
        JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp"), GIF("image/gif"),
    }

    /** The format [bytes] are in, by their first bytes rather than any name or MIME type. */
    fun sniff(bytes: ByteArray): Format? = when {
        bytes.size >= 3 && u(bytes, 0) == 0xFF && u(bytes, 1) == 0xD8 && u(bytes, 2) == 0xFF -> Format.JPEG
        bytes.size >= 8 && (0 until 8).all { bytes[it] == PNG_SIGNATURE[it] } -> Format.PNG
        bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" -> Format.WEBP
        bytes.size >= 6 && (ascii(bytes, 0, 6) == "GIF87a" || ascii(bytes, 0, 6) == "GIF89a") -> Format.GIF
        else -> null
    }

    /** [bytes] without their metadata, or null when [sniff] does not know them or the walk fails. */
    fun strip(bytes: ByteArray): ByteArray? = when (sniff(bytes)) {
        Format.JPEG -> jpeg(bytes)
        Format.PNG -> png(bytes)
        Format.WEBP -> webp(bytes)
        Format.GIF -> gif(bytes)
        null -> null
    }

    // ---- JPEG ----------------------------------------------------------

    /**
     * Keeps the tables, frame and scan segments, JFIF, the ICC profile and
     * Adobe's colour-transform marker; drops every other APPn segment and
     * every comment. Stops at the end-of-image marker, so a trailer after it
     * (a motion photo's MP4, an MPF second picture) is left behind too.
     */
    fun jpeg(src: ByteArray): ByteArray? {
        if (sniff(src) != Format.JPEG) return null
        val out = ByteArrayOutputStream(src.size)
        out.write(0xFF)
        out.write(0xD8)
        var i = 2
        while (i < src.size) {
            if (u(src, i) != 0xFF) return null
            var m = i + 1
            while (m < src.size && u(src, m) == 0xFF) m++ // fill bytes
            if (m >= src.size) return null
            val marker = u(src, m)
            i = m + 1
            if (marker == JPEG_EOI) {
                out.write(0xFF)
                out.write(JPEG_EOI)
                return out.toByteArray()
            }
            if (marker in JPEG_RST0..JPEG_RST7 || marker == JPEG_TEM) {
                out.write(0xFF)
                out.write(marker)
                continue
            }
            if (i + 2 > src.size) return null
            val length = (u(src, i) shl 8) or u(src, i + 1)
            if (length < 2 || i + length > src.size) return null
            if (keepJpegSegment(marker, src, i + 2, length - 2)) {
                out.write(0xFF)
                out.write(marker)
                out.write(src, i, length)
            }
            i += length
            if (marker == JPEG_SOS) {
                // The compressed scan runs to the next marker that is neither
                // a stuffed 0xFF00 nor a restart marker.
                var j = i
                while (j < src.size - 1) {
                    if (u(src, j) == 0xFF) {
                        val next = u(src, j + 1)
                        if (next == 0x00 || next in JPEG_RST0..JPEG_RST7) {
                            j += 2
                            continue
                        }
                        if (next != 0xFF) break
                    }
                    j++
                }
                if (j >= src.size - 1) {
                    // Cut off before its end marker: keep what there is and close it.
                    out.write(src, i, src.size - i)
                    out.write(0xFF)
                    out.write(JPEG_EOI)
                    return out.toByteArray()
                }
                out.write(src, i, j - i)
                i = j
            }
        }
        return null
    }

    private fun keepJpegSegment(marker: Int, src: ByteArray, at: Int, size: Int): Boolean = when (marker) {
        JPEG_APP0 -> startsWith(src, at, size, "JFIF\u0000") || startsWith(src, at, size, "JFXX\u0000")
        JPEG_APP2 -> startsWith(src, at, size, "ICC_PROFILE\u0000")
        JPEG_APP14 -> startsWith(src, at, size, "Adobe")
        in JPEG_APP0..JPEG_APP15 -> false
        JPEG_COM -> false
        else -> true
    }

    // ---- PNG -----------------------------------------------------------

    /**
     * Chunks that draw the picture or say how its colours and pixels are
     * meant; everything else (tEXt, zTXt, iTXt, eXIf, tIME, private chunks)
     * goes. APNG's animation chunks stay.
     */
    val PNG_KEEP: Set<String> = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs",
        "cICP", "mDCV", "cLLI", "acTL", "fcTL", "fdAT",
    )

    fun png(src: ByteArray): ByteArray? {
        if (sniff(src) != Format.PNG) return null
        val out = ByteArrayOutputStream(src.size)
        out.write(src, 0, PNG_SIGNATURE.size)
        var i = PNG_SIGNATURE.size
        while (i + PNG_CHUNK_OVERHEAD <= src.size) {
            val length = beInt(src, i)
            if (length < 0 || i.toLong() + PNG_CHUNK_OVERHEAD + length > src.size) return null
            val type = ascii(src, i + 4, 4)
            if (type in PNG_KEEP) out.write(src, i, PNG_CHUNK_OVERHEAD + length)
            i += PNG_CHUNK_OVERHEAD + length
            if (type == "IEND") return out.toByteArray()
        }
        return null
    }

    // ---- WebP ----------------------------------------------------------

    /** The chunks a WebP is drawn from; EXIF, XMP and anything unknown go. */
    val WEBP_KEEP: Set<String> = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")

    fun webp(src: ByteArray): ByteArray? {
        if (sniff(src) != Format.WEBP) return null
        val riffEnd = minOf(src.size.toLong(), 8L + leInt(src, 4).toLong().and(0xFFFFFFFFL)).toInt()
        val body = ByteArrayOutputStream(src.size)
        var i = 12
        while (i + 8 <= riffEnd) {
            val fourcc = ascii(src, i, 4)
            val size = leInt(src, i + 4)
            if (size < 0 || i.toLong() + 8 + size > src.size) return null
            val end = minOf(i + 8 + size + (size and 1), src.size)
            if (fourcc in WEBP_KEEP) {
                val chunk = src.copyOfRange(i, end)
                // VP8X's flags still say EXIF and XMP are there; they are not.
                if (fourcc == "VP8X" && chunk.size > 8) {
                    chunk[8] = (chunk[8].toInt() and (VP8X_EXIF or VP8X_XMP).inv()).toByte()
                }
                body.write(chunk)
            }
            i = end
        }
        val chunks = body.toByteArray()
        if (chunks.isEmpty()) return null
        val out = ByteArrayOutputStream(chunks.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le(4 + chunks.size))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(chunks)
        return out.toByteArray()
    }

    // ---- GIF -----------------------------------------------------------

    /** Application blocks that make a GIF loop; every other one (XMP among them) goes. */
    private val GIF_KEEP_APPS = setOf("NETSCAPE2.0", "ANIMEXTS1.0")

    /**
     * Keeps the frames, their timing and the loop count; drops comments and
     * every application block but the looping one.
     */
    fun gif(src: ByteArray): ByteArray? {
        if (sniff(src) != Format.GIF || src.size < GIF_HEADER) return null
        var i = GIF_HEADER
        val packed = u(src, 10)
        if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 0x07) + 1))
        if (i > src.size) return null
        val out = ByteArrayOutputStream(src.size)
        out.write(src, 0, i)
        while (i < src.size) {
            when (u(src, i)) {
                GIF_TRAILER -> {
                    out.write(GIF_TRAILER)
                    return out.toByteArray()
                }
                GIF_IMAGE -> {
                    if (i + 10 > src.size) return null
                    val local = u(src, i + 9)
                    var start = i + 10
                    if (local and 0x80 != 0) start += 3 * (1 shl ((local and 0x07) + 1))
                    val end = subBlocksEnd(src, start + 1) ?: return null // +1: LZW code size
                    out.write(src, i, end - i)
                    i = end
                }
                GIF_EXTENSION -> {
                    if (i + 2 > src.size) return null
                    val label = u(src, i + 1)
                    val end = subBlocksEnd(src, i + 2) ?: return null
                    val keep = when (label) {
                        GIF_GRAPHIC_CONTROL, GIF_PLAIN_TEXT -> true
                        GIF_APPLICATION -> i + 3 + 11 <= src.size && u(src, i + 2) == 11 &&
                            ascii(src, i + 3, 11) in GIF_KEEP_APPS
                        else -> false
                    }
                    if (keep) out.write(src, i, end - i)
                    i = end
                }
                else -> return null
            }
        }
        // No trailer: the frames are all there, so close it.
        out.write(GIF_TRAILER)
        return out.toByteArray()
    }

    /** Where a run of GIF sub-blocks starting at [start] ends, past its zero terminator. */
    private fun subBlocksEnd(src: ByteArray, start: Int): Int? {
        var j = start
        while (j < src.size) {
            val n = u(src, j)
            j += 1 + n
            if (n == 0) return j
        }
        return null
    }

    // ---- Bytes ---------------------------------------------------------

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val PNG_CHUNK_OVERHEAD = 12 // length, type, CRC

    private const val JPEG_TEM = 0x01
    private const val JPEG_RST0 = 0xD0
    private const val JPEG_RST7 = 0xD7
    private const val JPEG_EOI = 0xD9
    private const val JPEG_SOS = 0xDA
    private const val JPEG_APP0 = 0xE0
    private const val JPEG_APP2 = 0xE2
    private const val JPEG_APP14 = 0xEE
    private const val JPEG_APP15 = 0xEF
    private const val JPEG_COM = 0xFE

    private const val VP8X_EXIF = 0x08
    private const val VP8X_XMP = 0x04

    private const val GIF_HEADER = 13
    private const val GIF_TRAILER = 0x3B
    private const val GIF_IMAGE = 0x2C
    private const val GIF_EXTENSION = 0x21
    private const val GIF_GRAPHIC_CONTROL = 0xF9
    private const val GIF_PLAIN_TEXT = 0x01
    private const val GIF_APPLICATION = 0xFF

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun ascii(b: ByteArray, at: Int, n: Int): String =
        if (at + n > b.size) "" else String(b, at, n, Charsets.ISO_8859_1)

    private fun startsWith(b: ByteArray, at: Int, size: Int, prefix: String): Boolean =
        size >= prefix.length && ascii(b, at, prefix.length) == prefix

    private fun beInt(b: ByteArray, i: Int): Int =
        (u(b, i) shl 24) or (u(b, i + 1) shl 16) or (u(b, i + 2) shl 8) or u(b, i + 3)

    private fun leInt(b: ByteArray, i: Int): Int =
        u(b, i) or (u(b, i + 1) shl 8) or (u(b, i + 2) shl 16) or (u(b, i + 3) shl 24)

    private fun le(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}

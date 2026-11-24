package com.wasimaster.wmkeyboard.core.modules

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Random access to a file that is somewhere else, a few bytes at a time. */
interface RangeSource {
    /** The whole file's length. */
    val length: Long

    /** Exactly [count] bytes from [offset]. */
    fun read(offset: Long, count: Int): ByteArray

    /** A stream of [count] bytes from [offset]; the caller closes it. */
    fun open(offset: Long, count: Long): InputStream
}

/**
 * Reads one entry out of a zip (an AAR) without downloading the rest: the
 * end-of-central-directory record, then the central directory, then that
 * entry's local header and its data. What [NativeSource.Maven] needs to take
 * a 7 MB library out of a 75 MB artifact.
 *
 * Zip64 is not read: no artifact this is pointed at comes near 4 GB, and one
 * that did would fail here with a clear message rather than be misread.
 */
object ZipRangeReader {

    data class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
    )

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val CENTRAL_FIXED = 46
    private const val LOCAL_FIXED = 30
    private const val STORED = 0
    private const val DEFLATED = 8
    private const val ZIP64_MARK = 0xFFFFFFFFL

    /** The central directory entry named [name], or null when the zip has none. */
    fun find(source: RangeSource, name: String): Entry? = entries(source).firstOrNull { it.name == name }

    /** Every central directory entry. */
    fun entries(source: RangeSource): List<Entry> {
        val tailLength = minOf(source.length, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        val tail = le(source.read(source.length - tailLength, tailLength))
        var eocd = -1
        for (i in tailLength - EOCD_MIN downTo 0) {
            if (tail.getInt(i) == EOCD_SIGNATURE) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw IOException("not a zip: no end-of-central-directory record")
        val cdSize = tail.getInt(eocd + 12).toLong() and ZIP64_MARK
        val cdOffset = tail.getInt(eocd + 16).toLong() and ZIP64_MARK
        if (cdSize == ZIP64_MARK || cdOffset == ZIP64_MARK) throw IOException("zip64 is not supported")
        val cd = le(source.read(cdOffset, cdSize.toInt()))
        val out = ArrayList<Entry>()
        var p = 0
        while (p + CENTRAL_FIXED <= cd.limit() && cd.getInt(p) == CENTRAL_SIGNATURE) {
            val nameLength = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLength = cd.getShort(p + 30).toInt() and 0xFFFF
            val commentLength = cd.getShort(p + 32).toInt() and 0xFFFF
            // ByteBuffer.get(int, byte[]) requires API 34; minSdk is 24.
            val nameBytes = ByteArray(nameLength) { cd.get(p + CENTRAL_FIXED + it) }
            out += Entry(
                name = String(nameBytes, Charsets.UTF_8),
                method = cd.getShort(p + 10).toInt() and 0xFFFF,
                crc = cd.getInt(p + 16).toLong() and ZIP64_MARK,
                compressedSize = cd.getInt(p + 20).toLong() and ZIP64_MARK,
                size = cd.getInt(p + 24).toLong() and ZIP64_MARK,
                localHeaderOffset = cd.getInt(p + 42).toLong() and ZIP64_MARK,
            )
            p += CENTRAL_FIXED + nameLength + extraLength + commentLength
        }
        return out
    }

    /**
     * Writes [entry]'s uncompressed bytes to [out], checking its CRC. Calls
     * [onBytes] with the running count of bytes written.
     */
    fun extract(source: RangeSource, entry: Entry, out: OutputStream, onBytes: (Long) -> Unit = {}) {
        if (entry.method != STORED && entry.method != DEFLATED) {
            throw IOException("unsupported zip method ${entry.method} for ${entry.name}")
        }
        val header = le(source.read(entry.localHeaderOffset, LOCAL_FIXED))
        if (header.getInt(0) != LOCAL_SIGNATURE) throw IOException("bad local header for ${entry.name}")
        val nameLength = header.getShort(26).toInt() and 0xFFFF
        val extraLength = header.getShort(28).toInt() and 0xFFFF
        val dataOffset = entry.localHeaderOffset + LOCAL_FIXED + nameLength + extraLength
        val crc = CRC32()
        var written = 0L
        source.open(dataOffset, entry.compressedSize).use { raw ->
            val input = if (entry.method == DEFLATED) InflaterInputStream(raw, Inflater(true)) else raw
            input.use {
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    written += n
                    if (written > entry.size) throw IOException("${entry.name} is longer than its directory says")
                    crc.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    onBytes(written)
                }
            }
        }
        if (written != entry.size) throw IOException("${entry.name}: $written bytes, expected ${entry.size}")
        if (crc.value != entry.crc) throw IOException("${entry.name}: CRC mismatch")
    }

    private const val BUFFER = 64 * 1024

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}

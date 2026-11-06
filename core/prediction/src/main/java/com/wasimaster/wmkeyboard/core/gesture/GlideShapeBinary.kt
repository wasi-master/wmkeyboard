package com.wasimaster.wmkeyboard.core.gesture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** One stored shape: [GlideShapeStore.POINTS] quantised coordinates and its accept and reject counts. */
class GlideShapeRecord(val points: ByteArray, val accepted: Int, val rejected: Int)

/** One word's shapes on one layout, and when the word last learned or lost one. */
class GlideShapeWord(val tick: Long, val shapes: List<GlideShapeRecord>)

/**
 * A whole [GlideShapeStore] as it goes to disk, in either of its formats: what
 * the binary file and the JSON backup both decode to, so the two loads share
 * one set of checks ([GlideShapeStore] applies them).
 */
class GlideShapeImage(val tick: Long, val layouts: Map<Long, Map<String, GlideShapeWord>>)

/**
 * The learned-shape store's runtime file, binary format v1 (issue #1130).
 *
 * The JSON it replaces carried every point as two hex digits and had to be
 * parsed into objects whole before the decoder could read a shape; this is
 * the same data at its own size, read with no text in between. JSON stays the
 * backup format, so a backup still restores on an older build.
 *
 * All integers big-endian.
 *
 * ```
 * Header, 80 bytes
 *    0  magic "WMKS"
 *    4  u16 version (1)
 *    6  u16 header size (80)
 *    8  u32 flags (0)
 *   12  u8  digest algorithm (1 = SHA-256), 3 bytes reserved
 *   16  i64 store tick
 *   24  u32 layout count, 4 bytes reserved
 *   32  u64 payload length
 *   40  8 bytes reserved
 *   48  SHA-256 over header bytes 0..48 and the whole payload
 *
 * Payload: a directory, then each layout's word table and shape arena
 *   Directory, 24 bytes a layout, sorted by layout key (unsigned):
 *     i64 layout key | u32 word count | u32 word-table offset |
 *     u32 arena offset | u32 arena size          (offsets from payload start)
 *   Word table, sorted by word:
 *     u16 UTF-8 length | word bytes | i64 tick |
 *     u32 first shape's offset in the arena | u16 shape count
 *   Shape arena, 104 bytes a shape:
 *     u32 accepted | u32 rejected | 96 point bytes
 * ```
 *
 * The digest catches a file that was damaged, truncated or edited, not one
 * forged on purpose: the file is app-private, and a keyed MAC would need a
 * Keystore key that Auto Backup cannot carry to a new phone. An unknown
 * version, flag or digest algorithm is refused, never guessed at. Encoding is
 * canonical, so one store always writes the same bytes.
 *
 * Public only so the tests in :app can check the bytes; the store is the
 * one writer.
 */
object GlideShapeBinary {

    private val MAGIC = byteArrayOf('W'.code.toByte(), 'M'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte())
    const val VERSION = 1
    const val HEADER_SIZE = 80
    private const val DIGEST_OFFSET = 48
    private const val DIGEST_SHA256 = 1
    private const val DIRECTORY_ENTRY = 24
    private const val SHAPE_SIZE = 8 + GlideShapeStore.POINTS

    /** Largest file read at all, checked before anything is allocated. */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** Whether [bytes] claims to be this format, whatever its version: a file that does is never read as JSON. */
    fun isBinary(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && (MAGIC.indices).all { bytes[it] == MAGIC[it] }

    /** [image] as a v1 file. The caller has already held every shape to [GlideShapeStore.POINTS] bytes. */
    fun encode(image: GlideShapeImage): ByteArray {
        val layouts = image.layouts.entries
            .filter { it.value.isNotEmpty() }
            .sortedWith { a, b -> java.lang.Long.compareUnsigned(a.key, b.key) }
        // Each layout's table and arena, laid out first so the directory can point at them.
        val tables = layouts.map { (_, words) ->
            // A word key past a u16 cannot be written, and nothing a keyboard
            // learns comes near one; it is dropped rather than truncated.
            val sorted = words.entries
                .filter { it.key.isNotEmpty() && it.key.toByteArray(Charsets.UTF_8).size <= 0xFFFF }
                .sortedBy { it.key }
            val wordBytes = sorted.map { it.key.toByteArray(Charsets.UTF_8) }
            val tableSize = wordBytes.sumOf { 2 + it.size + 8 + 4 + 2 }
            val arenaSize = sorted.sumOf { it.value.shapes.size } * SHAPE_SIZE
            Triple(sorted, wordBytes, tableSize to arenaSize)
        }
        val payloadSize = DIRECTORY_ENTRY * layouts.size +
            tables.sumOf { (_, _, sizes) -> sizes.first + sizes.second }
        val out = ByteBuffer.allocate(HEADER_SIZE + payloadSize).order(ByteOrder.BIG_ENDIAN)
        out.put(MAGIC)
        out.putShort(VERSION.toShort())
        out.putShort(HEADER_SIZE.toShort())
        out.putInt(0)
        out.put(DIGEST_SHA256.toByte())
        out.put(ByteArray(3))
        out.putLong(image.tick)
        out.putInt(layouts.size)
        out.putInt(0)
        out.putLong(payloadSize.toLong())
        out.put(ByteArray(8))
        out.put(ByteArray(32))
        var offset = DIRECTORY_ENTRY * layouts.size
        for ((i, layout) in layouts.withIndex()) {
            val (sorted, _, sizes) = tables[i]
            out.putLong(layout.key)
            out.putInt(sorted.size)
            out.putInt(offset)
            out.putInt(offset + sizes.first)
            out.putInt(sizes.second)
            offset += sizes.first + sizes.second
        }
        for ((sorted, wordBytes, _) in tables) {
            var shapeOffset = 0
            for ((j, entry) in sorted.withIndex()) {
                out.putShort(wordBytes[j].size.toShort())
                out.put(wordBytes[j])
                out.putLong(entry.value.tick)
                out.putInt(shapeOffset)
                out.putShort(entry.value.shapes.size.toShort())
                shapeOffset += entry.value.shapes.size * SHAPE_SIZE
            }
            for (entry in sorted) {
                for (shape in entry.value.shapes) {
                    out.putInt(shape.accepted)
                    out.putInt(shape.rejected)
                    out.put(shape.points)
                }
            }
        }
        val bytes = out.array()
        System.arraycopy(digest(bytes), 0, bytes, DIGEST_OFFSET, 32)
        return bytes
    }

    /**
     * [bytes] as a store image, or null when they are not a v1 file this
     * build can trust: damaged, truncated, of another version or with a range
     * that points outside the file. Never throws.
     */
    fun decode(bytes: ByteArray): GlideShapeImage? = runCatching { decodeOrThrow(bytes) }.getOrNull()

    private fun decodeOrThrow(bytes: ByteArray): GlideShapeImage? {
        if (bytes.size < HEADER_SIZE || bytes.size > MAX_FILE_BYTES || !isBinary(bytes)) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buf.getShort(4).toInt() and 0xFFFF != VERSION) return null
        if (buf.getShort(6).toInt() and 0xFFFF != HEADER_SIZE) return null
        if (buf.getInt(8) != 0) return null
        if (bytes[12].toInt() != DIGEST_SHA256) return null
        val payloadLength = buf.getLong(32)
        if (payloadLength != (bytes.size - HEADER_SIZE).toLong()) return null
        if (!MessageDigest.isEqual(digest(bytes), bytes.copyOfRange(DIGEST_OFFSET, DIGEST_OFFSET + 32))) return null
        val tick = buf.getLong(16)
        val layoutCount = buf.getInt(24).toLong() and 0xFFFFFFFFL
        val payload = payloadLength.toInt()
        if (layoutCount * DIRECTORY_ENTRY > payload) return null
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val layouts = LinkedHashMap<Long, Map<String, GlideShapeWord>>()
        for (l in 0 until layoutCount.toInt()) {
            val at = HEADER_SIZE + l * DIRECTORY_ENTRY
            val layoutKey = buf.getLong(at)
            val wordCount = buf.getInt(at + 8).toLong() and 0xFFFFFFFFL
            val tableOffset = buf.getInt(at + 12).toLong() and 0xFFFFFFFFL
            val arenaOffset = buf.getInt(at + 16).toLong() and 0xFFFFFFFFL
            val arenaSize = buf.getInt(at + 20).toLong() and 0xFFFFFFFFL
            if (arenaOffset + arenaSize > payload || tableOffset > arenaOffset) return null
            if (tableOffset < layoutCount * DIRECTORY_ENTRY) return null
            if (arenaSize % SHAPE_SIZE != 0L || layoutKey in layouts) return null
            val arenaStart = HEADER_SIZE + arenaOffset.toInt()
            val tableEnd = arenaStart
            var pos = HEADER_SIZE + tableOffset.toInt()
            val words = LinkedHashMap<String, GlideShapeWord>()
            for (w in 0L until wordCount) {
                if (pos + 2 > tableEnd) return null
                val length = buf.getShort(pos).toInt() and 0xFFFF
                pos += 2
                if (length == 0 || pos + length + 14 > tableEnd) return null
                val word = try {
                    utf8.decode(ByteBuffer.wrap(bytes, pos, length)).toString()
                } catch (_: CharacterCodingException) {
                    return null
                }
                pos += length
                val wordTick = buf.getLong(pos)
                val shapeOffset = buf.getInt(pos + 8).toLong() and 0xFFFFFFFFL
                val shapeCount = buf.getShort(pos + 12).toInt() and 0xFFFF
                pos += 14
                if (shapeOffset + shapeCount.toLong() * SHAPE_SIZE > arenaSize) return null
                if (word in words) return null
                val shapes = ArrayList<GlideShapeRecord>(shapeCount)
                var s = arenaStart + shapeOffset.toInt()
                repeat(shapeCount) {
                    shapes.add(
                        GlideShapeRecord(
                            points = bytes.copyOfRange(s + 8, s + SHAPE_SIZE),
                            accepted = buf.getInt(s),
                            rejected = buf.getInt(s + 4),
                        ),
                    )
                    s += SHAPE_SIZE
                }
                words[word] = GlideShapeWord(wordTick, shapes)
            }
            if (pos != tableEnd) return null
            layouts[layoutKey] = words
        }
        return GlideShapeImage(tick, layouts)
    }

    /** SHA-256 over every header byte before the digest field, then the payload. */
    private fun digest(bytes: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(bytes, 0, DIGEST_OFFSET)
        sha.update(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        return sha.digest()
    }
}

package com.wasimaster.wmkeyboard.core.fonts

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * One design axis of a variable font, as its `fvar` table declares it.
 *
 * [tag] is the four-letter OpenType tag (`wght`, `wdth`, `slnt`, `ital`,
 * `opsz`, or a font's own uppercase one such as `GRAD`, `ROND`, `CASL`).
 * [hidden] is the font's own flag for an axis it would rather not have offered
 * in a plain UI; the editor keeps those behind Advanced rather than dropping
 * them, since the person who imported the font may want exactly that one.
 */
@Serializable
data class FontAxis(
    val tag: String,
    val name: String = "",
    val min: Float = 0f,
    val default: Float = 0f,
    val max: Float = 0f,
    val hidden: Boolean = false,
) {
    /** [value] held inside the axis. */
    fun clamp(value: Float): Float =
        if (value.isNaN()) default else value.coerceIn(minOf(min, max), maxOf(min, max))
}

/** A named instance of a variable font ("Bold Condensed"): one coordinate per axis. */
@Serializable
data class FontInstance(
    val name: String = "",
    val coords: Map<String, Float> = emptyMap(),
)

/**
 * What a font file says about its variations. An empty [axes] list is a static
 * font, which is a real answer and is stored as one, so a font is read once.
 */
@Serializable
data class FontVariations(
    val axes: List<FontAxis> = emptyList(),
    val instances: List<FontInstance> = emptyList(),
) {
    val isVariable: Boolean get() = axes.isNotEmpty()

    fun axis(tag: String): FontAxis? = axes.firstOrNull { it.tag == tag }

    /**
     * [values] cut down to what this font can draw: tags it has no axis for go
     * and every value is held inside its axis. A value equal to the axis's own
     * default is kept, since for weight it is not where an untuned label draws
     * (see [restValue]). Ordered by the font's own axis order, so the same
     * setting always produces the same string and the caches keyed on it hit.
     */
    fun clamp(values: Map<String, Float>): Map<String, Float> {
        if (values.isEmpty() || axes.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Float>()
        for (axis in axes) {
            val value = values[axis.tag] ?: continue
            out[axis.tag] = axis.clamp(value)
        }
        return out
    }

    /**
     * Where [tag] draws when nobody set it, for text asked for at [weight]:
     * the weight axis follows the weight asked for, the way CSS and Compose
     * both cut a variable font (Anybody's default instance is a hairline 100,
     * and nobody wants keys in it), every other axis rests at the font's own
     * default. Null for a tag the font has no axis for.
     */
    fun restValue(tag: String, weight: Int = 400): Float? {
        val axis = axis(tag) ?: return null
        return if (tag == WGHT_TAG) axis.clamp(weight.toFloat()) else axis.default
    }

    companion object {
        val STATIC = FontVariations()

        /** The weight axis, the one axis whose rest depends on the weight asked for. */
        const val WGHT_TAG = "wght"
    }
}

/** A stable text form of an axis map, for cache keys: `wdth=80.0,wght=700.0`. */
fun axesSignature(values: Map<String, Float>): String =
    if (values.isEmpty()) "" else values.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

/** The CSS-style form Android's `setFontVariationSettings` takes: `'wght' 700, 'wdth' 80`. */
fun axesCss(values: Map<String, Float>): String =
    values.entries.joinToString(", ") { "'${it.key}' ${it.value}" }

/**
 * Reads the `fvar` table of a TrueType / OpenType file (or the first face of a
 * collection), and the `name` records it points at for axis and instance names.
 *
 * Only the table directory, `fvar` and `name` are read, by position, so a CJK
 * face of tens of megabytes costs a few kilobytes of reads. Every offset is
 * bounds-checked: a truncated or hostile file reads as static, never throws.
 */
object FontAxesReader {

    /** The file's variations, [FontVariations.STATIC] for a static font, null if it is not a font. */
    fun read(file: File): FontVariations? = runCatching {
        RandomAccessFile(file, "r").use { raf -> read(raf.channel) }
    }.getOrNull()

    /** The same for a descriptor, such as the one the Google Fonts provider serves. */
    fun read(fd: FileDescriptor): FontVariations? = runCatching {
        // Not closed: the descriptor belongs to the caller.
        read(FileInputStream(fd).channel)
    }.getOrNull()

    /** For tests and small in-memory files. */
    fun read(bytes: ByteArray): FontVariations? = runCatching { parse(ByteSource(bytes)) }.getOrNull()

    private fun read(channel: FileChannel): FontVariations? = parse(ChannelSource(channel))

    private fun parse(source: Source): FontVariations? {
        val size = source.size
        if (size < HEADER_BYTES) return null
        var base = 0L
        var version = source.int(0) ?: return null
        if (version == TAG_TTCF) {
            base = (source.int(TTC_FIRST_OFFSET) ?: return null).toLong() and 0xFFFFFFFFL
            version = source.int(base) ?: return null
        }
        if (version != VERSION_1_0 && version != TAG_OTTO && version != TAG_TRUE) return null
        val count = source.u16(base + 4) ?: return null
        val dir = source.bytes(base + HEADER_BYTES, count * RECORD_BYTES) ?: return null
        var fvar: Pair<Long, Int>? = null
        var name: Pair<Long, Int>? = null
        for (i in 0 until count) {
            val r = i * RECORD_BYTES
            val tag = dir.getInt(r)
            val offset = dir.getInt(r + 8).toLong() and 0xFFFFFFFFL
            val length = dir.getInt(r + 12).toLong() and 0xFFFFFFFFL
            if (offset + length > size || length > MAX_TABLE_BYTES) continue
            when (tag) {
                TAG_FVAR -> fvar = offset to length.toInt()
                TAG_NAME -> name = offset to length.toInt()
            }
        }
        val (fvarAt, fvarLength) = fvar ?: return FontVariations.STATIC
        val table = source.bytes(fvarAt, fvarLength) ?: return FontVariations.STATIC
        val names = name?.let { (at, length) -> source.bytes(at, length) }?.let(::NameTable)
        return parseFvar(table, names)
    }

    private fun parseFvar(t: ByteBuffer, names: NameTable?): FontVariations {
        val limit = t.limit()
        if (limit < FVAR_HEADER_BYTES) return FontVariations.STATIC
        val axesAt = t.u16(4)
        val axisCount = t.u16(8)
        val axisSize = t.u16(10)
        val instanceCount = t.u16(12)
        val instanceSize = t.u16(14)
        if (axisCount == 0 || axisSize < AXIS_RECORD_BYTES) return FontVariations.STATIC
        val axes = ArrayList<FontAxis>(axisCount)
        for (i in 0 until minOf(axisCount, MAX_AXES)) {
            val at = axesAt + i * axisSize
            if (at + AXIS_RECORD_BYTES > limit) break
            val tag = tagString(t.getInt(at)) ?: continue
            val min = fixed(t.getInt(at + 4))
            val default = fixed(t.getInt(at + 8))
            val max = fixed(t.getInt(at + 12))
            val flags = t.u16(at + 16)
            val nameId = t.u16(at + 18)
            if (!min.isFinite() || !max.isFinite() || !default.isFinite() || min > max) continue
            axes += FontAxis(
                tag = tag,
                name = names?.string(nameId)?.takeIf { it.isNotBlank() } ?: fallbackAxisName(tag),
                min = min,
                default = default.coerceIn(min, max),
                max = max,
                hidden = flags and HIDDEN_AXIS != 0,
            )
        }
        if (axes.isEmpty()) return FontVariations.STATIC
        val instances = ArrayList<FontInstance>()
        val instancesAt = axesAt + axisCount * axisSize
        // Each record is a name id, flags, then one Fixed per axis.
        if (instanceSize >= 4 + axisCount * 4) {
            for (i in 0 until minOf(instanceCount, MAX_INSTANCES)) {
                val at = instancesAt + i * instanceSize
                if (at + 4 + axisCount * 4 > limit) break
                val label = names?.string(t.u16(at))?.takeIf { it.isNotBlank() } ?: continue
                // One Fixed per axis, in the axis order of the record above.
                // `axes` skipped any malformed axis, so the index is taken from
                // the tag's position among the records, not from `axes`.
                val coords = LinkedHashMap<String, Float>()
                for (axis in axes) {
                    val a = axisIndex(t, axesAt, axisSize, axisCount, axis.tag)
                    if (a < 0) continue
                    coords[axis.tag] = axis.clamp(fixed(t.getInt(at + 4 + a * 4)))
                }
                instances += FontInstance(label, coords)
            }
        }
        return FontVariations(axes, instances.distinctBy { it.name })
    }

    /** Names for the registered axes, for a font whose `name` table leaves one out. */
    fun fallbackAxisName(tag: String): String = when (tag) {
        "wght" -> "Weight"
        "wdth" -> "Width"
        "slnt" -> "Slant"
        "ital" -> "Italic"
        "opsz" -> "Optical size"
        "GRAD" -> "Grade"
        "ROND" -> "Roundness"
        "CASL" -> "Casual"
        "MONO" -> "Monospace"
        "CRSV" -> "Cursive"
        "XTRA" -> "Counter width"
        "YOPQ" -> "Thin stroke"
        "XOPQ" -> "Thick stroke"
        "YTUC" -> "Uppercase height"
        "YTLC" -> "Lowercase height"
        "YTAS" -> "Ascender height"
        "YTDE" -> "Descender depth"
        "YTFI" -> "Figure height"
        "FILL" -> "Fill"
        else -> tag
    }

    private fun axisIndex(t: ByteBuffer, axesAt: Int, axisSize: Int, axisCount: Int, tag: String): Int {
        for (i in 0 until minOf(axisCount, MAX_AXES)) {
            val at = axesAt + i * axisSize
            if (at + 4 > t.limit()) return -1
            if (tagString(t.getInt(at)) == tag) return i
        }
        return -1
    }

    private fun fixed(raw: Int): Float = raw / 65536f

    private fun tagString(raw: Int): String? {
        val chars = CharArray(4) { ((raw ushr (24 - it * 8)) and 0xFF).toChar() }
        if (chars.any { it < ' ' || it > '~' }) return null
        return String(chars)
    }

    private fun ByteBuffer.u16(at: Int): Int =
        if (at < 0 || at + 2 > limit()) 0 else getShort(at).toInt() and 0xFFFF

    /** The `name` table: platform 3 (Windows Unicode) English first, then any Unicode, then Mac Roman. */
    private class NameTable(private val t: ByteBuffer) {
        fun string(id: Int): String? {
            if (t.limit() < 6) return null
            val count = t.u16(2)
            val storage = t.u16(4)
            var best: String? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until count) {
                val r = 6 + i * 12
                if (r + 12 > t.limit()) break
                if (t.u16(r + 6) != id) continue
                val platform = t.u16(r)
                val encoding = t.u16(r + 2)
                val language = t.u16(r + 4)
                val rank = when {
                    platform == 3 && language == 0x409 -> 0
                    platform == 3 || platform == 0 -> 1
                    platform == 1 && encoding == 0 -> 2
                    else -> continue
                }
                if (rank >= bestRank) continue
                val length = t.u16(r + 8)
                val at = storage + t.u16(r + 10)
                if (at + length > t.limit() || length > 512) continue
                val bytes = ByteArray(length)
                for (b in 0 until length) bytes[b] = t.get(at + b)
                best = String(bytes, if (rank == 2) Charsets.ISO_8859_1 else Charsets.UTF_16BE).trim()
                bestRank = rank
            }
            return best
        }
    }

    private interface Source {
        val size: Long
        fun bytes(at: Long, length: Int): ByteBuffer?
        fun int(at: Long): Int? = bytes(at, 4)?.getInt(0)
        fun u16(at: Long): Int? = bytes(at, 2)?.let { it.getShort(0).toInt() and 0xFFFF }
    }

    private class ByteSource(private val data: ByteArray) : Source {
        override val size: Long get() = data.size.toLong()
        override fun bytes(at: Long, length: Int): ByteBuffer? {
            if (at < 0 || length < 0 || at + length > data.size) return null
            return ByteBuffer.wrap(data, at.toInt(), length).slice().order(ByteOrder.BIG_ENDIAN)
        }
    }

    private class ChannelSource(private val channel: FileChannel) : Source {
        override val size: Long = channel.size()
        override fun bytes(at: Long, length: Int): ByteBuffer? {
            if (at < 0 || length < 0 || at + length > size) return null
            val buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
            var read = 0
            while (read < length) {
                val n = channel.read(buffer, at + read)
                if (n <= 0) return null
                read += n
            }
            buffer.flip()
            return buffer
        }
    }

    private const val HEADER_BYTES = 12
    private const val RECORD_BYTES = 16
    private const val TTC_FIRST_OFFSET = 12L
    private const val FVAR_HEADER_BYTES = 16
    private const val AXIS_RECORD_BYTES = 20
    private const val HIDDEN_AXIS = 0x0001
    /** Real fonts carry a dozen at most; Roboto Flex, the widest common one, has 13. */
    private const val MAX_AXES = 32
    private const val MAX_INSTANCES = 128
    /** `fvar` and `name` are kilobytes; a megabyte bounds a hostile header. */
    private const val MAX_TABLE_BYTES = 1L shl 20
    private const val VERSION_1_0 = 0x00010000
    private const val TAG_OTTO = 0x4F54544F
    private const val TAG_TRUE = 0x74727565
    private const val TAG_TTCF = 0x74746366
    private const val TAG_FVAR = 0x66766172
    private const val TAG_NAME = 0x6E616D65
}

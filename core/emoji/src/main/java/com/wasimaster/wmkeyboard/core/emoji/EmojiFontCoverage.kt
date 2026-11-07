package com.wasimaster.wmkeyboard.core.emoji

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * What an emoji font can draw, read out of the font's own tables.
 *
 * The obvious way to ask this question on Android is [android.graphics.Paint.hasGlyph],
 * and it does not work for our case. `Typeface.createFromFile` builds its
 * `FontCollection` as *the file's family followed by the system fallback chain*
 * (`Typeface.Builder.build` → `CustomFallbackBuilder`, whose system fallback
 * cannot be switched off), and `hasGlyph` queries the whole collection. So for
 * any emoji the phone itself can draw — which is nearly all of them —
 * `hasGlyph` answers "yes" no matter how little the chosen font contains. Every
 * decision built on it silently became a no-op.
 *
 * Reading the tables is the only way to get an answer about *this font*:
 *
 *  - `cmap` formats 0/4/6/12/13 — which code points the font maps at all.
 *  - `cmap` format 14 — the variation sequences it declares, which is what
 *    decides whether U+FE0F may stay in the string (see [EmojiFontShaping]).
 *  - `GSUB` ligatures — which code-point sequences collapse into one glyph, so
 *    a ZWJ emoji the font does not actually have is known before it draws as
 *    its unjoined parts.
 *
 * Only `LigatureSubst` (and the extension wrapper around it) is read; a font
 * that assembles sequences with contextual substitutions instead will look like
 * it has no ligatures at all, which [hasLigatures] exists to let callers detect
 * rather than mistake for "this font is missing everything".
 *
 * All of it is a few dozen KB of table data even in an 11 MB colour font, and
 * it is pure byte work — no Android, so it is testable on the JVM.
 */
class EmojiFontCoverage internal constructor(
    private val glyphs: Map<Int, Int>,
    private val variationSequences: Set<Long>,
    private val ligatures: Set<String>,
    /**
     * False when this phone draws nothing for the font's glyphs at all: its
     * pictures are in a colour format Android does not render here (OpenType
     * SVG, `sbix`, COLRv1 before Android 13), so every emoji it maps comes out
     * as an empty cell (#1175). The tables still read fine, which is why this
     * is decided by drawing one, see [EmojiFontProbe].
     */
    val drawable: Boolean = true,
) {

    /** True when the font declares any ligature at all — see the class note. */
    val hasLigatures: Boolean get() = ligatures.isNotEmpty()

    /** The same coverage, marked as drawing nothing on this phone. */
    internal fun undrawable(): EmojiFontCoverage =
        EmojiFontCoverage(glyphs, variationSequences, ligatures, drawable = false)

    /**
     * The code point a draw test should try: the grinning face when the font
     * has it, else the first pictograph it maps, else anything past the Latin
     * blocks. Null when the font maps nothing worth trying.
     */
    fun sampleCodePoint(): Int? {
        if (covers(GRINNING_FACE)) return GRINNING_FACE
        return glyphs.keys.filter { it >= FIRST_PICTOGRAPH }.minOrNull()
            ?: glyphs.keys.filter { it >= FIRST_SYMBOL }.minOrNull()
    }

    /** True when the font maps [codePoint] to a glyph of its own. */
    fun covers(codePoint: Int): Boolean = glyphs.containsKey(codePoint)

    /**
     * True when the font's `cmap` format 14 subtable declares `base` +
     * `selector` — the only thing that makes Android's font itemiser hand a
     * `… U+FE0F` sequence to this font rather than to the system emoji font.
     */
    fun hasVariationSequence(base: Int, selector: Int): Boolean =
        variationSequences.contains(sequenceKey(base, selector))

    /**
     * True when [codePoints] substitute into a single glyph — the question
     * behind "will this ZWJ emoji draw as one picture or as its parts?".
     */
    fun ligates(codePoints: IntArray): Boolean {
        if (codePoints.size < 2) return false
        val key = StringBuilder(codePoints.size)
        for (cp in codePoints) {
            val glyph = glyphs[cp] ?: return false
            key.append(glyph.toChar())
        }
        return ligatures.contains(key.toString())
    }

    companion object {

        private const val GRINNING_FACE = 0x1F600
        private const val FIRST_PICTOGRAPH = 0x1F300
        private const val FIRST_SYMBOL = 0x2000

        private fun sequenceKey(base: Int, selector: Int): Long =
            (base.toLong() shl 32) or (selector.toLong() and 0xFFFFFFFFL)

        /** Coverage for [file], or null when it is not a font this can read. */
        fun read(file: File): EmojiFontCoverage? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length <= 0 || length > MAX_FONT_BYTES) return null
                val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
                read(buffer)
            }
        }.getOrNull()

        /** Coverage for an in-memory font, for tests and small files. */
        fun read(bytes: ByteArray): EmojiFontCoverage? =
            runCatching { read(ByteBuffer.wrap(bytes)) }.getOrNull()

        private fun read(buffer: ByteBuffer): EmojiFontCoverage? {
            buffer.order(ByteOrder.BIG_ENDIAN)
            val font = FontTables.of(buffer) ?: return null
            val glyphs = LinkedHashMap<Int, Int>()
            val sequences = HashSet<Long>()
            font.table(TAG_CMAP)?.let { readCmap(font, it, glyphs, sequences) }
            val ligatures = font.table(TAG_GSUB)?.let { readLigatures(font, it) }.orEmpty()
            if (glyphs.isEmpty()) return null
            return EmojiFontCoverage(glyphs, sequences, ligatures)
        }

        // A colour emoji font is a few MB; anything far past that is not one,
        // and mapping it would be the expensive way to find that out.
        private const val MAX_FONT_BYTES = 64L * 1024 * 1024

        private const val TAG_CMAP = 0x636D6170 // 'cmap'
        private const val TAG_GSUB = 0x47535542 // 'GSUB'
    }
}

/**
 * The table directory of an sfnt file, plus bounds-checked reads into it.
 *
 * Every accessor returns 0 rather than throwing when an offset runs off the end
 * of the file, so a truncated or hostile font yields empty coverage instead of
 * an exception mid-composition.
 */
private class FontTables(private val buffer: ByteBuffer, private val tables: Map<Int, Int>) {

    fun table(tag: Int): Int? = tables[tag]

    val size: Int get() = buffer.limit()

    fun u8(at: Int): Int = if (at < 0 || at + 1 > size) 0 else buffer.get(at).toInt() and 0xFF

    fun u16(at: Int): Int = if (at < 0 || at + 2 > size) 0 else buffer.getShort(at).toInt() and 0xFFFF

    fun i16(at: Int): Int = if (at < 0 || at + 2 > size) 0 else buffer.getShort(at).toInt()

    fun u24(at: Int): Int = (u8(at) shl 16) or (u8(at + 1) shl 8) or u8(at + 2)

    fun u32(at: Int): Long =
        if (at < 0 || at + 4 > size) 0L else buffer.getInt(at).toLong() and 0xFFFFFFFFL

    fun int32(at: Int): Int = u32(at).toInt()

    companion object {
        fun of(buffer: ByteBuffer): FontTables? {
            if (buffer.limit() < HEADER_BYTES) return null
            var base = 0
            var version = buffer.getInt(0)
            if (version == TAG_TTCF) {
                // A collection: the first face is the one a Typeface would use.
                if (buffer.limit() < TTC_FIRST_OFFSET + 4) return null
                base = buffer.getInt(TTC_FIRST_OFFSET)
                if (base < 0 || base + HEADER_BYTES > buffer.limit()) return null
                version = buffer.getInt(base)
            }
            if (version != VERSION_1_0 && version != TAG_OTTO && version != TAG_TRUE) return null
            val count = buffer.getShort(base + 4).toInt() and 0xFFFF
            val records = base + HEADER_BYTES
            if (records + count * RECORD_BYTES > buffer.limit()) return null
            val tables = HashMap<Int, Int>(count)
            for (i in 0 until count) {
                val record = records + i * RECORD_BYTES
                val offset = buffer.getInt(record + 8)
                if (offset >= 0 && offset < buffer.limit()) tables[buffer.getInt(record)] = offset
            }
            return FontTables(buffer, tables)
        }

        private const val HEADER_BYTES = 12
        private const val RECORD_BYTES = 16
        private const val TTC_FIRST_OFFSET = 12
        private const val VERSION_1_0 = 0x00010000
        private const val TAG_OTTO = 0x4F54544F // 'OTTO'
        private const val TAG_TRUE = 0x74727565 // 'true'
        private const val TAG_TTCF = 0x74746366 // 'ttcf'
    }
}

// ---- cmap ----------------------------------------------------------------

/**
 * Merges every `cmap` subtable into one code-point → glyph map.
 *
 * Merging rather than picking a "best" subtable is deliberate: the formats
 * disagree only about how they encode the same mapping, and taking the union
 * means an unusual encoding order can never hide coverage the font really has.
 */
private fun readCmap(
    font: FontTables,
    cmap: Int,
    glyphs: MutableMap<Int, Int>,
    sequences: MutableSet<Long>,
) {
    val count = font.u16(cmap + 2)
    for (i in 0 until count) {
        val record = cmap + 4 + i * 8
        val subtable = cmap + font.int32(record + 4)
        when (font.u16(subtable)) {
            0 -> readCmap0(font, subtable, glyphs)
            4 -> readCmap4(font, subtable, glyphs)
            6 -> readCmap6(font, subtable, glyphs)
            12 -> readCmap12(font, subtable, glyphs, sameGlyph = false)
            13 -> readCmap12(font, subtable, glyphs, sameGlyph = true)
            14 -> readCmap14(font, subtable, sequences)
        }
    }
}

private fun readCmap0(font: FontTables, at: Int, glyphs: MutableMap<Int, Int>) {
    for (code in 0 until 256) {
        val glyph = font.u8(at + 6 + code)
        if (glyph != 0) glyphs.putIfAbsent(code, glyph)
    }
}

/** Format 4: the BMP encoding, segments with an optional indirection array. */
private fun readCmap4(font: FontTables, at: Int, glyphs: MutableMap<Int, Int>) {
    val segments = font.u16(at + 6) / 2
    if (segments == 0) return
    val ends = at + 14
    val starts = ends + segments * 2 + 2
    val deltas = starts + segments * 2
    val rangeOffsets = deltas + segments * 2
    for (i in 0 until segments) {
        val start = font.u16(starts + i * 2)
        val end = font.u16(ends + i * 2)
        if (start > end || start == 0xFFFF) continue
        val delta = font.i16(deltas + i * 2)
        val rangeOffsetAt = rangeOffsets + i * 2
        val rangeOffset = font.u16(rangeOffsetAt)
        for (code in start..end) {
            val glyph = if (rangeOffset == 0) {
                (code + delta) and 0xFFFF
            } else {
                val glyphAt = rangeOffsetAt + rangeOffset + (code - start) * 2
                val raw = font.u16(glyphAt)
                if (raw == 0) 0 else (raw + delta) and 0xFFFF
            }
            if (glyph != 0) glyphs.putIfAbsent(code, glyph)
        }
    }
}

private fun readCmap6(font: FontTables, at: Int, glyphs: MutableMap<Int, Int>) {
    val first = font.u16(at + 6)
    val entries = font.u16(at + 8)
    for (i in 0 until entries) {
        val glyph = font.u16(at + 10 + i * 2)
        if (glyph != 0) glyphs.putIfAbsent(first + i, glyph)
    }
}

/** Formats 12 and 13: the full-range encoding every colour emoji font uses. */
private fun readCmap12(
    font: FontTables,
    at: Int,
    glyphs: MutableMap<Int, Int>,
    sameGlyph: Boolean,
) {
    val groups = font.u32(at + 12).toInt()
    if (groups <= 0 || groups > MAX_GROUPS) return
    for (i in 0 until groups) {
        val group = at + 16 + i * 12
        val start = font.int32(group)
        val end = font.int32(group + 4)
        val startGlyph = font.int32(group + 8)
        if (start > end || end - start > MAX_GROUP_SPAN) continue
        for (code in start..end) {
            val glyph = if (sameGlyph) startGlyph else startGlyph + (code - start)
            if (glyph != 0) glyphs.putIfAbsent(code, glyph)
        }
    }
}

/**
 * Format 14, the variation-selector subtable.
 *
 * Both halves count as support. A *non-default* mapping names its own glyph; a
 * *default* one says "use the glyph the base already has", and HarfBuzz
 * resolves that to the nominal glyph — so either way the font answers yes when
 * Android asks whether it covers the sequence, which is the question that
 * decides which font draws it.
 */
private fun readCmap14(font: FontTables, at: Int, sequences: MutableSet<Long>) {
    val records = font.u32(at + 6).toInt()
    if (records <= 0 || records > MAX_GROUPS) return
    for (i in 0 until records) {
        val record = at + 10 + i * 11
        val selector = font.u24(record)
        val defaults = font.int32(record + 3)
        val nonDefaults = font.int32(record + 7)
        if (defaults != 0) {
            val ranges = font.u32(at + defaults).toInt()
            if (ranges in 1..MAX_GROUPS) {
                for (r in 0 until ranges) {
                    val range = at + defaults + 4 + r * 4
                    val start = font.u24(range)
                    val extra = font.u8(range + 3)
                    for (code in start..start + extra) {
                        sequences.add(variationKey(code, selector))
                    }
                }
            }
        }
        if (nonDefaults != 0) {
            val mappings = font.u32(at + nonDefaults).toInt()
            if (mappings in 1..MAX_GROUPS) {
                for (m in 0 until mappings) {
                    val mapping = at + nonDefaults + 4 + m * 5
                    sequences.add(variationKey(font.u24(mapping), selector))
                }
            }
        }
    }
}

private fun variationKey(base: Int, selector: Int): Long =
    (base.toLong() shl 32) or (selector.toLong() and 0xFFFFFFFFL)

// ---- GSUB ----------------------------------------------------------------

/**
 * Every ligature the font declares, keyed by its glyph-id sequence.
 *
 * The whole lookup list is walked rather than the `liga`/`ccmp` features that
 * name it: emoji builds file these lookups under whichever feature their
 * toolchain prefers, and the set of ligature *rules* is the same either way.
 */
private fun readLigatures(font: FontTables, gsub: Int): Set<String> {
    val lookupList = gsub + font.u16(gsub + 8)
    val lookups = font.u16(lookupList)
    if (lookups <= 0 || lookups > MAX_LOOKUPS) return emptySet()
    val out = HashSet<String>()
    for (i in 0 until lookups) {
        val lookup = lookupList + font.u16(lookupList + 2 + i * 2)
        readLookup(font, lookup, out, depth = 0)
    }
    return out
}

private fun readLookup(font: FontTables, lookup: Int, out: MutableSet<String>, depth: Int) {
    if (depth > MAX_EXTENSION_DEPTH) return
    val type = font.u16(lookup)
    if (type != LOOKUP_LIGATURE && type != LOOKUP_EXTENSION) return
    val subtables = font.u16(lookup + 4)
    if (subtables > MAX_SUBTABLES) return
    for (i in 0 until subtables) {
        val subtable = lookup + font.u16(lookup + 6 + i * 2)
        if (type == LOOKUP_EXTENSION) {
            // An extension subtable is a jump to a real one, so the tables it
            // points at can live past the 64 KB an Offset16 can reach.
            if (font.u16(subtable + 2) != LOOKUP_LIGATURE) continue
            readLigatureSubtable(font, subtable + font.int32(subtable + 4), out)
        } else {
            readLigatureSubtable(font, subtable, out)
        }
    }
}

private fun readLigatureSubtable(font: FontTables, at: Int, out: MutableSet<String>) {
    if (font.u16(at) != 1) return
    val coverage = readCoverage(font, at + font.u16(at + 2))
    val sets = font.u16(at + 4)
    if (sets > MAX_LIGATURE_SETS) return
    for (i in 0 until minOf(sets, coverage.size)) {
        val first = coverage[i]
        val ligatureSet = at + font.u16(at + 6 + i * 2)
        val ligatures = font.u16(ligatureSet)
        if (ligatures > MAX_LIGATURES_PER_SET) continue
        for (j in 0 until ligatures) {
            val ligature = ligatureSet + font.u16(ligatureSet + 2 + j * 2)
            val components = font.u16(ligature + 2)
            if (components < 2 || components > MAX_COMPONENTS) continue
            val key = StringBuilder(components)
            key.append(first.toChar())
            for (c in 0 until components - 1) {
                key.append(font.u16(ligature + 4 + c * 2).toChar())
            }
            out.add(key.toString())
        }
    }
}

/** The glyphs a lookup applies to, in the order its subtables index them. */
private fun readCoverage(font: FontTables, at: Int): IntArray = when (font.u16(at)) {
    1 -> {
        val count = font.u16(at + 2)
        if (count > MAX_COVERAGE) IntArray(0) else IntArray(count) { font.u16(at + 4 + it * 2) }
    }
    2 -> {
        val ranges = font.u16(at + 2)
        val glyphs = ArrayList<Int>(ranges)
        for (i in 0 until minOf(ranges, MAX_COVERAGE)) {
            val range = at + 4 + i * 6
            val start = font.u16(range)
            val end = font.u16(range + 2)
            if (start <= end && end - start <= MAX_COVERAGE) {
                for (glyph in start..end) glyphs.add(glyph)
            }
        }
        glyphs.toIntArray()
    }
    else -> IntArray(0)
}

// Sanity ceilings. A real emoji font sits orders of magnitude below all of
// these; they exist so a corrupt offset can't spin the parser.
private const val MAX_GROUPS = 100_000
private const val MAX_GROUP_SPAN = 0x110000
private const val MAX_LOOKUPS = 4_096
private const val MAX_SUBTABLES = 4_096
private const val MAX_LIGATURE_SETS = 65_535
private const val MAX_LIGATURES_PER_SET = 65_535
private const val MAX_COMPONENTS = 64
private const val MAX_COVERAGE = 65_535
private const val MAX_EXTENSION_DEPTH = 4
private const val LOOKUP_LIGATURE = 4
private const val LOOKUP_EXTENSION = 7

package com.wasimaster.wmkeyboard.core.emoji

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How to draw one emoji in the chosen font: the spelling that font understands,
 * or the instruction to leave this one to the system font.
 */
data class EmojiSpelling(val text: String, val systemFont: Boolean)

/**
 * Spells an emoji the way the *chosen font* wants to see it, and says when that
 * font cannot draw it at all.
 *
 * The catalog stores fully-qualified sequences, which is what the standard says
 * to send: `❤️` is `2764 FE0F`, with U+FE0F asking for the emoji-coloured form
 * of a character that would otherwise be plain text. That is what gets
 * committed to the field. It is frequently the wrong thing to *draw*.
 *
 * Android picks a font per character before shaping anything, and a variation
 * selector dominates that choice (minikin `FontCollection::calcCoverageScore`):
 * a font that declares the sequence in a `cmap` format-14 subtable scores 3, a
 * font that merely has the base character scores 1 — even when it is the
 * requested font and comes first in the collection. Converted colour emoji
 * fonts essentially never ship a format-14 subtable, so `❤️` is handed to the
 * *system* emoji font while the chosen font sits there holding a perfectly good
 * heart. Dropping the U+FE0F hands it back: with no selector the requested
 * font is first in the collection and wins outright.
 *
 * Which spelling to use is therefore a question about the font's own tables,
 * answered by [EmojiFontCoverage] — not by `Paint.hasGlyph`, which consults the
 * system fallback chain and so answers "yes" for almost anything.
 *
 * When no spelling draws as a single glyph, [EmojiSpelling.systemFont] asks the
 * caller to draw that one emoji in the system font. That is better than what
 * happens otherwise: a ZWJ sequence the font half-covers renders as its
 * unjoined parts — 😶‍🌫️ as a face and a separate cloud — because Android keeps
 * the run in the requested font and finds no ligature to collapse it with.
 */
class EmojiShaper internal constructor(source: (() -> EmojiFontCoverage?)?) {

    private val cache = ConcurrentHashMap<String, EmojiSpelling>()

    /**
     * Parsed on first use rather than when the theme is built: reading the
     * tables is cheap but not free, and a keyboard that never opens the emoji
     * panel should not pay for it.
     */
    private val coverage: EmojiFontCoverage? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        source?.invoke()
    }

    private val identity = source == null

    /** [emoji] respelled for this font, or unchanged when it is already right. */
    fun shape(emoji: String): String = spelling(emoji).text

    /** True when this one emoji should be drawn in the system emoji font. */
    fun drawsWithSystemFont(emoji: String): Boolean = spelling(emoji).systemFont

    /** Both answers at once, computed once per emoji and cached. */
    fun spelling(emoji: String): EmojiSpelling {
        if (identity || emoji.isEmpty()) return EmojiSpelling(emoji, systemFont = false)
        return cache.getOrPut(emoji) {
            val tables = coverage ?: return@getOrPut EmojiSpelling(emoji, systemFont = false)
            pick(tables, emoji)
        }
    }

    private fun pick(tables: EmojiFontCoverage, emoji: String): EmojiSpelling {
        // A font this phone cannot draw at all gives every emoji back to the
        // system font, or the panel is a grid of empty cells (#1175).
        if (!tables.drawable) return EmojiSpelling(emoji, systemFont = true)
        val authored = EmojiFontShaping.codePoints(emoji)
        val stripped = EmojiFontShaping.stripSelectors(authored, tables)
        if (EmojiFontShaping.draws(tables, stripped)) {
            return EmojiSpelling(EmojiFontShaping.toText(stripped), systemFont = false)
        }
        // The authored spelling is only worth trying when the font would
        // actually be given the run — which the *leading* selector decides, and
        // stripping has already dealt with. A later selector rides along inside
        // the run, so a font whose ligatures are keyed on the qualified
        // sequence (most of them) still gets its chance here.
        if (!EmojiFontShaping.leadsWithUndeclaredSelector(authored, tables) &&
            EmojiFontShaping.draws(tables, authored)
        ) {
            return EmojiSpelling(emoji, systemFont = false)
        }
        return EmojiSpelling(emoji, systemFont = true)
    }
}

object EmojiFontShaping {

    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F

    /** The identity shaper: every emoji is drawn exactly as the catalog spells it. */
    val Identity = EmojiShaper(null)

    /** Explicit form of "no shaping needed", for readability at call sites. */
    fun forSystemFont(): EmojiShaper = Identity

    /**
     * A shaper for the font in [file], or [Identity] when it is null.
     *
     * Null means the system emoji font — the one font guaranteed to want the
     * standard spelling, and the one we cannot read the tables of — so the
     * whole mechanism switches off for the default configuration.
     */
    fun forFontFile(file: File?): EmojiShaper {
        if (file == null || !file.exists()) return Identity
        return EmojiShaper { coverageFor(file) }
    }

    /**
     * Reads [file]'s tables into the shared cache ahead of time.
     *
     * Reading them is a handful of milliseconds, which is a handful of
     * milliseconds too many on the frame that draws the first emoji — so the
     * IME calls this off the main thread whenever the chosen font changes, and
     * the shaper the theme builds finds the answer already waiting.
     */
    fun warm(file: File?) {
        if (file != null && file.exists()) coverageFor(file)
    }

    /**
     * One font is in use at a time and a theme rebuild makes a fresh shaper, so
     * the cache exists to stop the same file being read again and again. It is
     * keyed on the file's identity *and* its stamp: replacing an installed font
     * keeps the path and has to invalidate.
     */
    private val coverageCache = HashMap<String, EmojiFontCoverage?>()

    private fun coverageFor(file: File): EmojiFontCoverage? {
        val key = "${file.path}:${file.lastModified()}:${file.length()}"
        synchronized(coverageCache) {
            if (coverageCache.containsKey(key)) return coverageCache[key]
        }
        // Deliberately outside the lock: two threads racing to read the same
        // font waste a few milliseconds, holding a lock across file IO on the
        // main thread would cost rather more.
        // Then one emoji drawn from it: nothing in the tables says whether
        // this phone's renderer supports the font's colour format (#1175).
        val read = EmojiFontCoverage.read(file)?.let { tables ->
            if (EmojiFontProbe.draws(file, tables)) tables else tables.undrawable()
        }
        synchronized(coverageCache) {
            if (coverageCache.size >= MAX_CACHED_FONTS) coverageCache.clear()
            coverageCache[key] = read
        }
        return read
    }

    private const val MAX_CACHED_FONTS = 4

    /** A shaper over already-read [coverage]; null gives [Identity]. */
    fun forCoverage(coverage: EmojiFontCoverage?): EmojiShaper {
        if (coverage == null) return Identity
        return EmojiShaper { coverage }
    }

    /**
     * [codePoints] with every variation selector the font does not declare
     * removed, and the declared ones kept.
     *
     * Keeping a declared selector matters as much as dropping an undeclared
     * one: a font with a format-14 subtable has said which sequences it draws,
     * and stripping would ask it for the wrong one.
     */
    internal fun stripSelectors(codePoints: IntArray, tables: EmojiFontCoverage): IntArray {
        val out = IntArray(codePoints.size)
        var n = 0
        for (i in codePoints.indices) {
            val cp = codePoints[i]
            if (isSelector(cp) && i > 0 && !tables.hasVariationSequence(codePoints[i - 1], cp)) {
                continue
            }
            out[n++] = cp
        }
        return if (n == codePoints.size) codePoints else out.copyOf(n)
    }

    /**
     * True when the font can draw [codePoints] as one glyph.
     *
     * A font with no ligatures at all gets the benefit of the doubt: it may be
     * assembling sequences with contextual substitutions, which are not read,
     * and refusing to draw its whole catalog would be the worse mistake.
     */
    internal fun draws(tables: EmojiFontCoverage, codePoints: IntArray): Boolean {
        if (codePoints.isEmpty()) return false
        // A base plus a selector the font itself declares: the format-14
        // subtable maps that pair straight to a glyph, so neither the
        // selector's own cmap entry nor a ligature comes into it. Without this
        // a font that declares its sequences but does not also map U+FE0F on
        // its own would fail every one of them.
        if (codePoints.size == 2 && isSelector(codePoints[1]) &&
            tables.hasVariationSequence(codePoints[0], codePoints[1])
        ) {
            return true
        }
        for (cp in codePoints) if (!tables.covers(cp)) return false
        if (codePoints.size == 1) return true
        return !tables.hasLigatures || tables.ligates(codePoints)
    }

    /**
     * True when the sequence opens with `base + selector` that the font has not
     * declared — the shape that loses the run to the system emoji font, and so
     * the one spelling never worth handing to this font.
     */
    internal fun leadsWithUndeclaredSelector(
        codePoints: IntArray,
        tables: EmojiFontCoverage,
    ): Boolean = codePoints.size >= 2 && isSelector(codePoints[1]) &&
        !tables.hasVariationSequence(codePoints[0], codePoints[1])

    private fun isSelector(cp: Int) = cp == VS15 || cp == VS16

    internal fun codePoints(text: String): IntArray {
        val out = IntArray(text.length)
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out[n++] = cp
            i += Character.charCount(cp)
        }
        return out.copyOf(n)
    }

    internal fun toText(codePoints: IntArray): String = buildString(codePoints.size + 2) {
        for (cp in codePoints) appendCodePoint(cp)
    }
}

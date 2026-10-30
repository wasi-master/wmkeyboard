package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.ComposerType

/**
 * Digit and symbol alternates for a letter grid that was authored without them.
 *
 * QWERTY and its Latin siblings put a digit first on every top-row key's hold
 * and a symbol first on the keys below, Samsung- and Gboard-style, so the
 * corner hint shows it and a hold types it with the number row hidden. Most of
 * the layouts that came from other keyboards, and a few of the built-in
 * non-Latin grids, carried only their own letters there, so on Ukrainian or
 * Greek the corners were bare and a digit was a page switch away.
 *
 * This fills them in the same scheme rather than hand-editing six hundred
 * files: the first row with at least [MIN_ROW_KEYS] plain keys takes 1–0, the
 * two rows after it QWERTY's symbol order. A layout whose letter grid already
 * offers a digit anywhere is left exactly as authored, since that author chose
 * where the digits go; so is anything whose keys feed a rules engine that may
 * read a digit as something else (Keyman, a system input method, the Chinese
 * and Japanese composers, VNI's tone digits).
 */
object DigitHints {

    private const val MIN_ROW_KEYS = 7

    private val Digits = "1234567890".map { it.toString() }

    /** The row under the digits, QWERTY's order, longer for wider grids. */
    private val SecondRow = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/", "=", "%")

    /** The row under that, QWERTY's order, longer for wider grids. */
    private val ThirdRow = listOf("*", "\"", "'", ":", ";", "!", "?", "~", "%", "^", "=", "/")

    /** Composers whose keys commit or compose letters, so a held digit just types. */
    private val PlainComposers = setOf(
        null, ComposerType.NONE, ComposerType.DEAD_KEY, ComposerType.TRANSLITERATE, ComposerType.BORNO_PHONETIC,
        ComposerType.INDIC_CLUSTER, ComposerType.HANGUL, ComposerType.TELEX,
    )

    /** [spec] with digit and symbol alternates on its letters layer, when it has none. */
    fun fill(spec: LayoutSpec): LayoutSpec {
        if (spec.keyman != null || spec.inputMethod != null) return spec
        if (spec.composer !in PlainComposers) return spec
        val layer = spec.layers[LayoutLayer.LETTERS.key] ?: return spec
        val rows = fill(layer.rows)
        if (rows === layer.rows) return spec
        return spec.copy(layers = spec.layers + (LayoutLayer.LETTERS.key to layer.copy(rows = rows)))
    }

    /**
     * [rows] with digits on the first wide row and symbols on the two below
     * it, or [rows] itself when the grid already has a digit or no row wide
     * enough to carry them.
     */
    fun fill(rows: List<List<Key>>): List<List<Key>> {
        val keys = rows.flatten()
        if (keys.any { it.letters != null || it.offersDigit() }) return rows
        val first = rows.indexOfFirst { row -> row.count { it.takesHint() } >= MIN_ROW_KEYS }
        if (first < 0) return rows
        return rows.mapIndexed { index, row ->
            when (index - first) {
                0 -> row.withAlternates(Digits)
                1 -> row.withAlternates(SecondRow)
                2 -> row.withAlternates(ThirdRow)
                else -> row
            }
        }
    }

    private fun List<Key>.withAlternates(pool: List<String>): List<Key> {
        val left = pool.toMutableList()
        return map { key ->
            if (!key.takesHint() || left.isEmpty()) return@map key
            val pick = left.firstOrNull { it != key.label } ?: return@map key
            left.remove(pick)
            key.copy(longPress = listOf(pick) + (key.longPress - pick))
        }
    }

    /** A plain character key whose corner is free for a hint. */
    private fun Key.takesHint(): Boolean =
        action == KeyAction.Text && role == null && hint == null && !hideHint &&
            flick.isEmpty() && letters == null && label.length in 1..4 && label.isLetterish()

    /**
     * Starts with a letter or a vowel sign (ा, ি), not punctuation or a music
     * note: the music and notation grids are symbols already.
     */
    private fun String.isLetterish(): Boolean {
        val first = codePointAt(0)
        return Character.isLetter(first) || Character.getType(first).toByte().let {
            it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK
        }
    }

    private fun Key.offersDigit(): Boolean =
        label.isDigit() || hint?.isDigit() == true || longPress.any { it.isDigit() }

    private fun String.isDigit(): Boolean = length == 1 && this[0].isDigit()
}

/** [this] with [DigitHints] filled in on its letters layer. */
fun LayoutSpec.withDigitHints(): LayoutSpec = DigitHints.fill(this)

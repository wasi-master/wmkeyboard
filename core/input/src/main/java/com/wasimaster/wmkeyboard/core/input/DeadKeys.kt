package com.wasimaster.wmkeyboard.core.input

import java.text.Normalizer

/**
 * Dead-key accents: press an accent, then a letter, and the two fuse into
 * one precomposed character (´ then e → é).
 *
 * A key is a dead key purely by what it emits — a Unicode combining mark
 * (U+0300–U+036F). That means a mark behaves identically whether it sits on
 * the symbols layout or inside a long-press popup, with no separate key
 * action to keep in sync. The spacing accents (´ ^ ~ ¨) stay ordinary
 * characters: people type those literally, and stealing them would break
 * writing `a^2` or a shell backtick.
 *
 * Combining happens through NFC normalization rather than a hand-written
 * table, so every precomposed pair Unicode defines is reachable: the Latin
 * accents, but also ǹ, ẍ, ṽ and the rest of the long tail. Pairs Unicode
 * has no single character for (there is no precomposed "q with tilde")
 * fall back to the spacing accent followed by the letter, which is what
 * the user would otherwise have typed by hand.
 */
object DeadKeys {

    private const val COMBINING_FIRST = '\u0300'
    private const val COMBINING_LAST = '\u036F'

    /**
     * Spacing forms of the common accents, used when a mark is typed on its
     * own — a bare combining mark would otherwise attach itself to whatever
     * character happens to precede it in the field.
     */
    private val SPACING = mapOf(
        '\u0300' to "`",   // grave
        '\u0301' to "´", // acute
        '\u0302' to "^",   // circumflex
        '\u0303' to "~",   // tilde
        '\u0304' to "¯", // macron
        '\u0306' to "˘", // breve
        '\u0307' to "˙", // dot above
        '\u0308' to "¨", // diaeresis
        '\u030A' to "˚", // ring above
        '\u030B' to "˝", // double acute
        '\u030C' to "ˇ", // caron
        '\u0327' to "¸", // cedilla
        '\u0328' to "˛", // ogonek
    )

    /** True when [text] is a lone combining mark, i.e. a dead-key press. */
    fun isDeadKey(text: String): Boolean = markOf(text) != null

    /** The combining mark [text] arms, or null when it is not a dead key. */
    fun markOf(text: String): Char? =
        text.singleOrNull()?.takeIf { it in COMBINING_FIRST..COMBINING_LAST }

    /**
     * What a dead key types when nothing combines with it: the spacing
     * accent where one exists, otherwise the mark on a dotted circle so it
     * stays visible rather than silently modifying the preceding character.
     */
    fun standalone(mark: Char): String = SPACING[mark] ?: "◌$mark"

    /**
     * [base] with [mark] applied, or null when Unicode has no single
     * character for that pair.
     */
    fun combine(base: Char, mark: Char): String? {
        val composed = Normalizer.normalize("$base$mark", Normalizer.Form.NFC)
        return composed.takeIf { it.length == 1 && it[0] != base }
    }

    /**
     * What to output when [mark] is armed and the user types [text].
     * Combines when Unicode allows, otherwise emits the standalone accent
     * ahead of the typed text so nothing is lost.
     */
    fun apply(mark: Char, text: String): String {
        val base = text.singleOrNull() ?: return standalone(mark) + text
        return combine(base, mark) ?: (standalone(mark) + text)
    }

    /**
     * What to output when several accents are armed, in the order they were
     * pressed, and the user types [text] (ˆ ´ a → ấ, ˛ ¯ o → ǭ). The letter
     * and its marks are NFC-composed, so Unicode's precomposed letter comes
     * out where one exists and the letter with its combining marks where not.
     * One armed mark keeps [apply]'s fallback to the spacing accent.
     */
    fun applyAll(marks: String, text: String): String {
        if (marks.length == 1) return apply(marks[0], text)
        val base = text.singleOrNull()
        if (base == null || !base.isLetter()) return marks.map(::standalone).joinToString("") + text
        return Normalizer.normalize(base + marks, Normalizer.Form.NFC)
    }

    /** The strip chip's text for a stack of armed accents. */
    fun standaloneAll(marks: String): String = marks.map(::standalone).joinToString("")
}

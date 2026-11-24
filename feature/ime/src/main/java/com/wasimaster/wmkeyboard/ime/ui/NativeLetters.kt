package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.LatinAccents
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * The letters a Latin-script language writes with beyond a-z, lowercased, from
 * [NativeLetterTable] (CLDR's exemplar sets, baked in because Android hides
 * ICU's own accessor). German is ä ö ü ß; English has no entry, which is what
 * keeps [nativeLettersFirst] a no-op there.
 *
 * Looked up by the full locale tag, then by any tag sharing its primary
 * subtag, so a language registered as "de-AT" still finds German's letters.
 * Cached per tag because [currentLayout] asks on every rebuild of the grid.
 */
internal object NativeLetters {
    private val cache = ConcurrentHashMap<String, Set<String>>()

    fun of(localeTag: String): Set<String> = cache.getOrPut(localeTag) {
        val primary = localeTag.substringBefore('-')
        val letters = NativeLetterTable[localeTag]
            ?: NativeLetterTable.entries.firstOrNull { it.key.substringBefore('-') == primary }?.value
            ?: ""
        letters.map { it.toString() }.toSet()
    }

    private val byBaseCache = ConcurrentHashMap<String, Map<Char, List<String>>>()

    /**
     * [of], grouped by the a-z key each letter is typed from: Portuguese's ã,
     * á, à and â under a, ç under c. The base is the letter the accent sits
     * on, or for a letter that does not come apart that way (ß, æ, ı) the
     * key [LatinAccents] already lists it under. A letter with neither is
     * left out, since there is no key to hang it on.
     */
    fun byBase(localeTag: String): Map<Char, List<String>> = byBaseCache.getOrPut(localeTag) {
        of(localeTag).mapNotNull { letter -> baseOf(letter)?.let { it to letter } }
            .groupBy({ it.first }, { it.second })
    }

    private fun baseOf(letter: String): Char? {
        val bare = Normalizer.normalize(letter, Normalizer.Form.NFD).firstOrNull()
        if (bare != null && bare in 'a'..'z') return bare
        return LatinAccents.entries.firstOrNull { (_, accents) -> letter in accents }?.key
    }
}

/**
 * Discussion #382: the key with its language's own letters moved to the front
 * of its long-press popup, ahead of the digit or symbol hint that leads it.
 *
 * Every Latin layout puts a hint first (`7` on u, `@` on a) and the accents
 * after it, which is right for English and wrong for German, where a hold on u
 * is for ü far more often than for 7. With hold-to-select, entry 0 is what a
 * plain hold commits and what the corner hint draws, so the umlaut has to be
 * there, not one slide along.
 *
 * Only letters in [native] move, in the order the layout listed them; the hint
 * and any foreign accents follow in their own order, so nothing leaves the
 * popup. A key whose popup does not start with a hint is left as authored —
 * the layout already chose what comes first — and so are the keys of an
 * ambiguous grid, whose leading digit is the keypad's own.
 */
internal fun nativeLettersFirst(key: Key, native: Set<String>): Key {
    if (native.isEmpty() || key.action != KeyAction.Text || key.letters != null) return key
    val lead = key.longPress.firstOrNull() ?: return key
    if (!lead.isHintGlyph()) return key
    val own = (key.output ?: key.label).lowercase()
    val (mine, rest) = key.longPress.partition {
        val letter = it.lowercase()
        letter != own && letter in native
    }
    return if (mine.isEmpty()) key else key.copy(longPress = mine + rest)
}

/**
 * A digit or a symbol: no letter and no combining mark anywhere in it. The
 * mark test keeps the Indic layouts out, whose popups lead with a vowel sign
 * that is as much the language's own as the letters after it.
 */
private fun String.isHintGlyph(): Boolean = isNotEmpty() && codePoints().noneMatch {
    Character.isLetter(it) || Character.getType(it).toByte() in MarkTypes
}

private val MarkTypes = setOf(
    Character.NON_SPACING_MARK,
    Character.COMBINING_SPACING_MARK,
    Character.ENCLOSING_MARK,
)

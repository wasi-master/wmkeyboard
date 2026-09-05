package com.wasimaster.wmkeyboard.app

import android.content.res.Resources
import com.wasimaster.wmkeyboard.R
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How settings search matches what you type against what the app calls things.
 *
 * The index in `SettingsSearch.kt` names the rows; this file decides which of
 * them a query means, and in what order. Four things it has to survive:
 *
 *  1. The settings screens keep one glossary. They say "press and hold", never
 *     "long press"; "glide typing", never "swipe typing"; "turn on", never
 *     "enable". People type the other word. `search_word_groups` joins the two.
 *  2. Words are typed with a thumb. "hapitc" and "vibraton" have to land, so a
 *     word that is one or two edits away from a word of the row still matches,
 *     below anything spelt right.
 *  3. Half a query is better than none. When no row carries every word, the
 *     rows carrying most of them answer instead of an empty screen.
 *  4. Two rows match a word equally well and are not equally wanted. "Haptics"
 *     and "Vibrate on repeat" both match "vibration"; one is the setting, the
 *     other is a detail of it. The words alone cannot tell them apart, so the
 *     ranking adds what the entry *is* ([EntryWeight]) and what this person
 *     opened before ([SearchBoost]), each capped so the words still come first.
 *
 * The order of the results is then: rows carrying the most query words; among
 * them, by score; among equal scores, the shorter name. Rows the query only
 * reaches through a subtitle or a breadcrumb are listed after the rest, under
 * their own heading ([SearchResults.mentions]).
 */

/**
 * Which part of an entry a word was found in, what a hit there is worth, and
 * whether a misspelling counts as a hit at all.
 *
 * Only the short fields spell-correct. A subtitle is a sentence, and in a
 * sentence of twenty words something is always one edit away from what you
 * typed: "mode" finds "more", "dark" finds "mark". On a name of three words
 * that same rule is what rescues a typo.
 */
internal enum class MatchField(val percent: Int, val correctsSpelling: Boolean) {
    /** The row's own name. */
    TITLE(100, true),

    /**
     * Words the row is searched by and never draws. High, because each was put
     * there on purpose for the query that types it: a screen's own keyword
     * beats a row whose title merely contains a synonym of the word.
     */
    KEYWORDS(85, true),

    /** The breadcrumb: the screen, and the tool, the row sits on. */
    SCREEN(35, false),

    /** The line under the name. */
    SUBTITLE(22, false),
}

/** One field of an entry, in the form the matching below compares against. */
internal class SearchText(val field: MatchField, raw: String) {
    val text: String = normalizeForSearch(raw)
    val words: List<String> = text.split(' ').filter { it.isNotEmpty() }

    /**
     * The words run together. It is what lets "2d" find "2-D cursor touchpad"
     * and "onehanded" find "One-handed mode": a query drops the punctuation
     * that the name keeps.
     */
    val compact: String = words.joinToString("")
}

/** A hit on the whole field: the row is called exactly this. */
private const val TIER_FIELD_EXACT = 100

/** The field starts with the word: "auto" on "Autocorrect". */
private const val TIER_FIELD_PREFIX = 80

/** One whole word of the field, in any position. */
private const val TIER_WORD_EXACT = 70

/** A word of the field starts with it: "sugg" on "Suggestions". */
private const val TIER_WORD_PREFIX = 60

/** A word of the field with the plural or the -y/-ies ending taken off. */
private const val TIER_WORD_STEM = 65

/** The word is inside the field, across word boundaries and all. */
private const val TIER_CONTAINS = 40

/** The word is in the field once the field's punctuation is dropped. */
private const val TIER_COMPACT = 34

/** A word of the field, misspelt. Below every spelt-right hit above. */
private const val TIER_FUZZY = 26

/**
 * What a hit through [SettingsSearchVocabulary] keeps of its score.
 *
 * Low enough that a row whose name starts with the word you typed beats a row
 * named exactly after a word that only means the same: "skin" is the emoji
 * tone before it is a theme, even with the theme screen's own bonus on top.
 * High enough that the [EntryWeight.PRIMARY] bonus can lift a row called by the
 * synonym over rows that merely start with your word: "vibrate" is Haptics
 * before it is "Vibrate on space bar".
 */
private const val ALIAS_PERCENT = 75

/**
 * Points for a title that contains the query's words next to each other, in
 * order. "number row" is the row called that before it is a row with "number"
 * in one place and "row" in another.
 */
private const val PHRASE_TITLE_BONUS = 15

/** The same, for a phrase found among the entry's search keywords. */
private const val PHRASE_KEYWORD_BONUS = 9

/**
 * Below this many letters a word inside another word is not a hit, whether in
 * the text or in the text with its spaces removed. "row" is in "arrow",
 * "narrow", "browser" and "grow the bubble"; "key" is in "monkey". Four letters
 * is where an inner match starts to mean the compound it sits in ("correct" in
 * "autocorrect", "board" in "keyboard") more often than an accident.
 */
private const val CONTAINS_MIN_LENGTH = 4

/**
 * Short words are not spell-corrected. Under five letters one edit joins words
 * that have nothing to do with each other ("mode" and "more", "dark" and
 * "mark"), and a short word is cheap to type again.
 */
private const val FUZZY_MIN_LENGTH = 5

/** Two edits are allowed only once a word is long enough for two to be a slip. */
private const val FUZZY_TWO_EDITS_LENGTH = 8

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * Lower case, no accents, and one space between runs of letters and digits.
 *
 * Both sides of every comparison go through this, so "Émoji"/"emoji" and
 * "One-handed"/"one handed" are the same words, and a query needs no
 * punctuation to match a name that has some.
 */
internal fun normalizeForSearch(raw: String): String {
    val folded = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
    val out = StringBuilder(folded.length)
    var pendingSpace = false
    for (ch in folded) {
        if (ch.isLetterOrDigit()) {
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(ch)
        } else {
            pendingSpace = true
        }
    }
    return out.toString()
}

/**
 * The words a query and a row can be looked for by, beyond the ones the screens
 * draw: the groups of same-meaning words, and the words too common to narrow
 * anything down.
 *
 * Built once per settings-search screen, from resources, so it speaks the
 * language the rest of the index was resolved in.
 */
internal class SettingsSearchVocabulary(
    groups: List<List<String>>,
    private val stopWords: Set<String>,
) {
    private val alternatives: Map<String, Set<String>> =
        buildMap<String, MutableSet<String>> {
            for (group in groups) {
                for (word in group) {
                    getOrPut(word) { mutableSetOf() } += group.filterNot { it == word }
                }
            }
        }

    /** The other words of every group [word] is in. Empty for an unknown word. */
    fun alternativesOf(word: String): Set<String> = alternatives[word].orEmpty()

    fun isStopWord(word: String): Boolean = word in stopWords

    companion object {
        /** No groups and no stop words: what the ranking tests rank against. */
        val NONE = SettingsSearchVocabulary(emptyList(), emptySet())
    }
}

/** Reads the vocabulary out of [strings]. */
internal fun settingsSearchVocabulary(strings: SearchStrings): SettingsSearchVocabulary =
    SettingsSearchVocabulary(
        groups = strings.getStringArray(R.array.search_word_groups)
            .map { group -> normalizeForSearch(group).split(' ').filter { it.isNotEmpty() } }
            .filter { it.size > 1 },
        stopWords = normalizeForSearch(strings.getString(R.string.search_stop_words))
            .split(' ')
            .filterTo(mutableSetOf()) { it.isNotEmpty() },
    )

/** The vocabulary in the language [res] is configured for. */
internal fun settingsSearchVocabulary(res: Resources): SettingsSearchVocabulary =
    settingsSearchVocabulary(ResourceSearchStrings(res))

/**
 * The plural taken off, so "themes" finds "Theme" and "dictionaries" finds
 * "Dictionary". Deliberately only the plural: an -ing or -ed rule turns real
 * words into each other and costs more accuracy than it buys.
 */
private fun stem(word: String): String = when {
    word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
    word.length > 4 && (word.endsWith("ses") || word.endsWith("xes") || word.endsWith("hes")) ->
        word.dropLast(2)
    word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
    else -> word
}

/**
 * True when [a] and [b] are at most [maxEdits] insertions, deletions,
 * substitutions or swaps of neighbours apart.
 *
 * Damerau rather than plain Levenshtein because the typo a thumb makes most
 * often is two letters in the wrong order, and that is one slip, not two.
 */
private fun withinEdits(a: String, b: String, maxEdits: Int): Boolean {
    if (abs(a.length - b.length) > maxEdits) return false
    if (a == b) return true
    var beforePrevious = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var rowBest = current[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var value = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                value = min(value, beforePrevious[j - 2] + 1)
            }
            current[j] = value
            rowBest = min(rowBest, value)
        }
        // Every path through this row already costs too much, so every path
        // through the rows below it does too.
        if (rowBest > maxEdits) return false
        val spare = beforePrevious
        beforePrevious = previous
        previous = current
        current = spare
    }
    return previous[b.length] <= maxEdits
}

private fun fuzzyMatches(word: String, token: String): Boolean {
    val maxEdits = if (token.length >= FUZZY_TWO_EDITS_LENGTH) 2 else 1
    return withinEdits(word, token, maxEdits)
}

/**
 * How well [token] matches this field, from [TIER_FUZZY] up to
 * [TIER_FIELD_EXACT]. [spellCorrect] is off for a word the user did not type:
 * a word group already rescues one word with another, and spell-correcting the
 * rescue costs a lot of work for a hit nobody asked for.
 */
private fun SearchText.tier(token: String, spellCorrect: Boolean = true): Int {
    if (text.isEmpty()) return 0
    if (text == token) return TIER_FIELD_EXACT
    if (text.startsWith(token)) return TIER_FIELD_PREFIX
    var best = 0
    for (word in words) {
        if (word == token) return TIER_WORD_EXACT
        // Both rules run: "themes" starts with "theme" and is its plural, and
        // the plural is the better reason.
        if (word.startsWith(token)) best = max(best, TIER_WORD_PREFIX)
        if (stem(word) == stem(token)) best = max(best, TIER_WORD_STEM)
    }
    if (best > 0) return best
    if (token.length >= CONTAINS_MIN_LENGTH && text.contains(token)) return TIER_CONTAINS
    if (token.length >= CONTAINS_MIN_LENGTH && compact.contains(token)) return TIER_COMPACT
    val fuzzy = spellCorrect && field.correctsSpelling && token.length >= FUZZY_MIN_LENGTH
    if (fuzzy && words.any { fuzzyMatches(it, token) }) return TIER_FUZZY
    return 0
}

/** Where one query word landed on an entry, and what that is worth. */
private class Hit(val points: Int, val where: MatchField?, val direct: Boolean) {
    /** A hit on what the row is called, or on the words it is searched by. */
    val onName: Boolean get() = where == MatchField.TITLE || where == MatchField.KEYWORDS

    companion object {
        val NONE = Hit(0, null, direct = true)
    }
}

/** The best hit [token] gets anywhere in [entry], already weighted by field. */
private fun rawHit(entry: SettingsSearchEntry, token: String, spellCorrect: Boolean = true): Hit {
    var best = Hit.NONE
    for (text in entry.searchText) {
        val points = text.tier(token, spellCorrect) * text.field.percent / 100
        if (points > best.points) best = Hit(points, text.field, direct = spellCorrect)
    }
    return best
}

/**
 * What [token] is worth against [entry]: the word as typed, or one that means
 * the same thing at a discount, whichever is higher.
 */
private fun tokenHit(
    entry: SettingsSearchEntry,
    token: String,
    vocabulary: SettingsSearchVocabulary,
): Hit {
    val direct = rawHit(entry, token)
    // Nothing an alias can find beats the word itself spelt right on a title.
    if (direct.points >= TIER_WORD_EXACT) return direct
    var alias = Hit.NONE
    for (other in vocabulary.alternativesOf(token)) {
        val hit = rawHit(entry, other, spellCorrect = false)
        if (hit.points > alias.points) alias = hit
    }
    val aliasPoints = alias.points * ALIAS_PERCENT / 100
    return if (aliasPoints > direct.points) Hit(aliasPoints, alias.where, direct = false) else direct
}

/**
 * The words of [query] worth searching for: normalized, and with the words that
 * are in half the settings dropped. A query made only of those keeps them,
 * because dropping every word answers nothing.
 */
internal fun searchTokens(query: String, vocabulary: SettingsSearchVocabulary): List<String> {
    val all = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
    val meaningful = all.filterNot { vocabulary.isStopWord(it) }
    return meaningful.ifEmpty { all }
}

/**
 * What the ranking knows about a person's own history with an entry, on top
 * of the words: the points to add for [SettingsSearchEntry.key] given the query
 * being typed. Zero for everyone on a fresh install; see `SearchPicks`.
 */
internal fun interface SearchBoost {
    fun pointsFor(key: String, normalizedQuery: String): Int

    companion object {
        val NONE = SearchBoost { _, _ -> 0 }
    }
}

private class Ranked(
    val entry: SettingsSearchEntry,
    val matched: Int,
    val score: Int,
    val onName: Boolean,
    /** Every word of a query of several words landed on the title. */
    val spelledOut: Boolean,
)

/**
 * The answer to a query, in two parts. [hits] are the rows the query names: a
 * word of the query landed on their title or their search words. [mentions]
 * only carry the words somewhere in a subtitle or a breadcrumb; they are drawn
 * after the hits, under their own heading, so that a search for "space" is
 * answered by the space bar rows and not by every subtitle that says "press
 * space".
 */
internal class SearchResults(val hits: List<SettingsSearchEntry>, val mentions: List<SettingsSearchEntry>) {
    val isEmpty: Boolean get() = hits.isEmpty() && mentions.isEmpty()

    /** Every result, best first. */
    val all: List<SettingsSearchEntry> get() = hits + mentions

    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList())
    }
}

/**
 * What an entry scores against the query's [tokens]: the sum of what each word
 * is worth, plus what the entry is (a screen or a primary switch named what you
 * typed), plus the query as a phrase, plus what the person's own history says,
 * all scaled by the entry's [EntryWeight.percent].
 *
 * The screen bonus wants the *word as typed* on the title: a screen whose
 * search words merely include your word is not more relevant than the row
 * named after it. The primary bonus takes a synonym too, because that is the
 * case it is for.
 */
private fun rank(
    entry: SettingsSearchEntry,
    tokens: List<String>,
    phrase: String,
    vocabulary: SettingsSearchVocabulary,
    boost: SearchBoost,
): Ranked? {
    var matched = 0
    var points = 0
    var onName = false
    var titleDirect = false
    var onTitle = 0
    for (token in tokens) {
        val hit = tokenHit(entry, token, vocabulary)
        if (hit.points == 0) continue
        matched++
        points += hit.points
        onName = onName || hit.onName
        if (hit.where == MatchField.TITLE) {
            onTitle++
            titleDirect = titleDirect || hit.direct
        }
    }
    if (matched == 0) return null
    val titleAny = onTitle > 0
    val bonusApplies = when (entry.weight) {
        EntryWeight.SECTION -> titleDirect
        EntryWeight.PRIMARY -> titleAny
        else -> false
    }
    if (bonusApplies) points += entry.weight.titleBonus
    if (tokens.size > 1) {
        val fields = entry.searchText
        if (fields[0].text.contains(phrase) || fields[0].compact.contains(phrase.replace(" ", ""))) {
            points += PHRASE_TITLE_BONUS
        } else if (fields[1].text.contains(phrase)) {
            points += PHRASE_KEYWORD_BONUS
        }
    }
    points += boost.pointsFor(entry.key, phrase)
    return Ranked(entry, matched, points * entry.weight.percent, onName, spelledOut = tokens.size > 1 && onTitle == tokens.size)
}

/**
 * Ranked matches for [query] over [index].
 *
 * Rows that carry every word of the query win outright. When none does, the
 * rows carrying the most words answer, as long as that is most of the query:
 * "emoji row" with no such row is better answered by the emoji rows than by an
 * empty screen, while one word out of four is a different question.
 *
 * Among those, a query of several words that a title contains whole comes
 * first: "clipboard history" names the row called that before the Clipboard
 * screen, whose search words happen to include it, and "swipe typing" names
 * Glide typing before the Typing screen. Then the score decides (see [rank]).
 * Ties break towards the kind of entry a search for settings means (a screen
 * before a row, a row before a tool page), then towards the deeper, more
 * specific screen, then towards the shorter title, so the plainest setting
 * with a matching name floats above the wordier ones.
 */
internal fun rankSettings(
    query: String,
    index: List<SettingsSearchEntry>,
    vocabulary: SettingsSearchVocabulary = SettingsSearchVocabulary.NONE,
    boost: SearchBoost = SearchBoost.NONE,
): SearchResults {
    val tokens = searchTokens(query, vocabulary)
    if (tokens.isEmpty()) return SearchResults.EMPTY
    val phrase = tokens.joinToString(" ")
    val scored = index.mapNotNull { rank(it, tokens, phrase, vocabulary, boost) }
    if (scored.isEmpty()) return SearchResults.EMPTY
    val bestMatched = scored.maxOf { it.matched }
    if (bestMatched * 2 < tokens.size) return SearchResults.EMPTY
    val ordered = scored
        .filter { it.matched == bestMatched }
        .sortedWith(
            compareByDescending<Ranked> { it.spelledOut }
                .thenByDescending { it.score }
                .thenBy { it.entry.weight.ordinal }
                .thenByDescending { it.entry.route.count { c -> c == '/' } }
                .thenBy { it.entry.title.length },
        )
    val (hits, mentions) = ordered.partition { it.onName }
    return SearchResults(hits.map { it.entry }, mentions.map { it.entry })
}

/** [rankSettings] flattened: every result, best first. */
internal fun searchSettings(
    query: String,
    index: List<SettingsSearchEntry>,
    vocabulary: SettingsSearchVocabulary = SettingsSearchVocabulary.NONE,
    boost: SearchBoost = SearchBoost.NONE,
): List<SettingsSearchEntry> = rankSettings(query, index, vocabulary, boost).all

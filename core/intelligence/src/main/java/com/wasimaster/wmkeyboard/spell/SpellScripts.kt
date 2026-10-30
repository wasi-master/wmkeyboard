package com.wasimaster.wmkeyboard.spell

import java.lang.Character.UnicodeScript

/**
 * The scripts [words] are written in, for telling what a word list covers from
 * a sample of its own words. Asking the list rather than a table of languages
 * gets the ones written in several right (Japanese, or Serbian in both
 * alphabets) and leaves out a stray foreign entry too rare to be sampled.
 */
fun scriptsOf(words: Iterable<String>): Set<UnicodeScript> =
    words.flatMapTo(HashSet()) { lettersScripts(it) }

/**
 * Whether a list written in [scripts] can say anything about [word]. A list
 * holding no Cyrillic has no opinion on a Cyrillic word, which is not the same
 * as the word being wrong. No [scripts] means the list could not be sampled, so
 * nothing is ruled out.
 */
fun isInScripts(word: String, scripts: Set<UnicodeScript>): Boolean =
    scripts.isEmpty() || lettersScripts(word).any { it in scripts }

private fun lettersScripts(word: String): List<UnicodeScript> =
    word.codePoints().toArray().map(UnicodeScript::of).filter {
        it != UnicodeScript.COMMON && it != UnicodeScript.INHERITED && it != UnicodeScript.UNKNOWN
    }

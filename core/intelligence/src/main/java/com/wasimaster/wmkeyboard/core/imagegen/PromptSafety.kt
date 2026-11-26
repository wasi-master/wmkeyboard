package com.wasimaster.wmkeyboard.core.imagegen

import java.text.Normalizer
import java.util.Locale

/**
 * The prompt half of the generator's safety filter: sexual content is refused
 * before anything is drawn. Checked on the prompt as typed and on its English
 * translation, since the model only ever sees English. A word list, so it
 * errs on the side of refusing a harmless prompt that happens to contain one
 * of these words; the picture classifier ([NsfwFilter]) is the second check
 * for what a list cannot catch.
 */
object PromptSafety {

    /** Whole words, lowercased, accents folded. */
    private val BLOCKED = setOf(
        "nsfw", "nude", "nudes", "nudity", "naked", "topless", "bottomless", "undressed", "unclothed",
        "porn", "porno", "pornographic", "pornography", "hentai", "ecchi", "xxx", "erotic", "erotica",
        "sex", "sexy", "sexual", "sexually", "intercourse", "orgasm", "orgy", "masturbation", "masturbating",
        "genitals", "genital", "penis", "vagina", "vulva", "nipple", "nipples", "breasts", "boobs", "tits",
        "areola", "buttocks", "lingerie", "fetish", "bdsm", "bondage", "stripper", "striptease", "cum",
        "loli", "lolicon", "shota", "shotacon", "upskirt", "nudist",
    )

    /** Pairs that are fine apart and not together. */
    private val BLOCKED_PHRASES = listOf(
        "without clothes", "no clothes", "no clothing", "see through", "see-through clothes", "wet t-shirt",
    )

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 42 KB across this file's patterns.
    private val WORD by lazy { Regex("[\\p{L}\\p{N}]+") }

    /** The first blocked word or phrase in any of [texts], or null when they may be drawn. */
    fun blocked(vararg texts: String?): String? {
        for (text in texts) {
            if (text.isNullOrBlank()) continue
            val folded = fold(text)
            BLOCKED_PHRASES.firstOrNull { folded.contains(it) }?.let { return it }
            WORD.findAll(folded).map { it.value }.firstOrNull { it in BLOCKED }?.let { return it }
        }
        return null
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
}

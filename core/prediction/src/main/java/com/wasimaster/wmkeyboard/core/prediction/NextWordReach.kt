package com.wasimaster.wmkeyboard.core.prediction

/**
 * How far the strip reaches for the word after the one just written: what
 * [SuggestionEngine] offers on an empty buffer, and how hard those followers
 * press into the strip once the next word's letters start.
 *
 * Every level is a setting of knobs the engine already had, never a second
 * scoring path. [FEWER] reads only the sources [SuggestionEngine] already
 * calls personal (the user's own pairs and triples, contacts and the context
 * model), the same split the follower carry has always used. [NORMAL] is what
 * the keyboard always did. [MORE] lets the corpus pack's followers ride into
 * a typed word's strip as well, and carries more of them.
 */
enum class NextWordReach(
    /** How many followers carry into the strip of a word being typed. */
    val carry: Int,
    /** Whether only the personal sources are read. */
    val personalOnly: Boolean,
    /** Whether the carry reads the personal sources alone. */
    val carryPersonalOnly: Boolean,
) {
    /** Nothing predicted after a space; the strip waits for letters. */
    OFF(carry = 0, personalOnly = true, carryPersonalOnly = true),

    /** The user's own habits, contacts and the context model only. */
    FEWER(carry = 1, personalOnly = true, carryPersonalOnly = true),

    /** Every source, the user's own first; the shipped behaviour. */
    NORMAL(carry = 3, personalOnly = false, carryPersonalOnly = true),

    /** Every source, and corpus followers carried into typed words too. */
    MORE(carry = 5, personalOnly = false, carryPersonalOnly = false),
}

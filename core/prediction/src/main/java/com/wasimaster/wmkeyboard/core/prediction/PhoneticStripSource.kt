package com.wasimaster.wmkeyboard.core.prediction

/**
 * What fills the strip of a phonetic layout (Avro, Hindi phonetic) after its
 * two fixed chips, when the strip is set to keep them: the buffer as typed in
 * Latin letters first, the rules' reading of it second, and then these.
 *
 * The point of the fixed chips is that they never move: the English is always
 * one tap away on the left, the phonetic reading always beside it, and the eye
 * does not have to hunt for either.
 */
enum class PhoneticStripSource {
    /**
     * Both languages, led by the one the word reads as: the layout's own words
     * first while the text around it is in that language, English first once
     * it has turned English. The other language's best still gets the last
     * chip on screen, the way the ordinary mixed strip pins it.
     */
    SMART,

    /** The layout's own language only: dictionary words and the spelling map. */
    NATIVE,

    /** English only: completions of the buffer read as an English word. */
    ENGLISH,
}

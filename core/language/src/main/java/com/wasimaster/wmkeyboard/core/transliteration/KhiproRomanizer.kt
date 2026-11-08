package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Khipro run backwards: the keys that spell a Bangla word on the Khipro
 * layout, for decoding a swipe over its grid (#541). The pieces are read off
 * Khipro itself, once, in the Rust engine
 * (`native/wm-engine/src/khipro_romanizer.rs`); this is the call into it.
 *
 * Each edition spells with its own rules ([dark]): Classic joins `hb` into
 * হ্ব, so হবে is `hobe`, while Dark leaves consonants apart until `/` joins
 * them, so হবে is `hbe` and হচ্ছে is `hccf/e`.
 *
 * Letters and the slicer `/`, which a swipe over the Khipro grid passes
 * through (see [KEYS]). A word that needs any other key (the separator `;`,
 * the blinder) has no spelling here and is typed.
 */
object KhiproRomanizer {

    /**
     * The keys a swipe over the Khipro grid can pass through: the letters and
     * the slicer, which is how চন্দ্রবিন্দু and খণ্ড-ত are typed (`cand/` is চাঁদ).
     */
    const val KEYS = "abcdefghijklmnopqrstuvwxyz/"

    /**
     * Builds the pieces of Khipro Dark if [dark], Classic if not (about 55,000
     * Khipro conversions), off the thread that will first need them.
     */
    fun warm(dark: Boolean = false) {
        // The pieces are read off the spec in use, a downloaded one included.
        KhiproSpecs.ensureLoaded()
        if (dark) EngineNative.khiproDarkRomanizerWarm() else EngineNative.khiproRomanizerWarm()
    }

    /** The fewest letters that Khipro turns into [word], or null when no cut of it converts back exactly. */
    fun romanize(word: String, dark: Boolean = false): String? = spellings(word, dark).firstOrNull()

    /**
     * Every way of spelling [text] worth decoding a swipe against: the fewest
     * letters, and the same with the inherent `o` written out between
     * consonants where Khipro still reads the word (`ekhn` and `ekhon` for
     * এখন), in Khipro Dark if [dark], Classic if not. Empty when there is none.
     */
    fun spellings(text: String, dark: Boolean = false): List<String> {
        KhiproSpecs.ensureLoaded()
        return (if (dark) EngineNative.khiproDarkSpellings(text) else EngineNative.khiproSpellings(text)).toList()
    }
}

package com.wasimaster.wmkeyboard.core.voice.punctuation

/**
 * Which listed model Add punctuation and capitals may use (#1005). A model the
 * user imported comes before any of these for its languages.
 */
object PunctuationChoice {
    /** The multilingual model where it can read the language, Sherpa's English one over or under it. */
    const val AUTO = "auto"

    /** The multilingual model alone, even with the English one on the phone. */
    const val MULTILINGUAL = "multilingual"

    /** Sherpa's English model alone: 8 MB rather than 151, and English only. */
    const val ENGLISH = "english"

    val CHOICES = setOf(AUTO, MULTILINGUAL, ENGLISH)
}

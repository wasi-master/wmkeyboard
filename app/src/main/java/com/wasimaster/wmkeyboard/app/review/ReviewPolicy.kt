package com.wasimaster.wmkeyboard.app.review

/**
 * When the Play build may ask for a rating.
 *
 * Here in `main`, pure arithmetic, so it can be tested without a Play install.
 * It looks at how much the keyboard has been used and never at how it went:
 * Play's rules forbid sorting people by what they are likely to say, and a
 * prompt shown only to the happy ones would not be a fair rating anyway.
 */
internal object ReviewPolicy {

    /** Enough typing to have an opinion of the keyboard rather than the setup. */
    const val MIN_WORDS = 500L

    /** Enough days for it to be a keyboard they live with. */
    const val MIN_INSTALLED_MILLIS = 7L * 24 * 60 * 60 * 1000

    /**
     * How long before asking again. Play's own quota may refuse sooner than
     * this, and it never says whether the card appeared, so this also counts
     * asks nobody saw.
     */
    const val REPEAT_MILLIS = 120L * 24 * 60 * 60 * 1000

    /**
     * [lastAskedAt] is 0 when it has never asked. A time in the future counts
     * as expired, the same rule as [com.wasimaster.wmkeyboard.app.updates.UpdatePrefs]:
     * a restored backup or a moved clock must not silence this forever.
     */
    fun shouldAsk(words: Long, installedAt: Long, lastAskedAt: Long, now: Long): Boolean {
        if (words < MIN_WORDS) return false
        if (now - installedAt < MIN_INSTALLED_MILLIS) return false
        if (lastAskedAt == 0L || now < lastAskedAt) return true
        return now - lastAskedAt >= REPEAT_MILLIS
    }
}

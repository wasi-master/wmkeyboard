package com.wasimaster.wmkeyboard.core.vocab

import android.content.Context
import androidx.core.content.edit

/**
 * The little state the Vocabulary tool keeps outside the learning record:
 * which day's word of the day has been offered on the keyboard, and which
 * words have already nudged today when the cooldown is "once a day".
 *
 * Its own `SharedPreferences` file rather than fields on `KeyboardSettings`
 * (the same reasoning as `EggPrefs`): none of this is a setting, none of it
 * belongs in a backup, and the settings class is near its argument ceiling.
 * The keyboard and the settings app share a process, so both read the same
 * file. The markers are days, not booleans, so they re-arm at midnight
 * without anyone clearing them.
 */
class VocabPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The local epoch day whose word-of-the-day chip has been shown, or 0. */
    var dailyClaimedDay: Int
        get() = prefs.getInt(KEY_DAILY_CLAIMED, 0)
        set(value) = prefs.edit { putInt(KEY_DAILY_CLAIMED, value) }

    /** The local epoch day whose chip the user dismissed, or 0. */
    var dailyDismissedDay: Int
        get() = prefs.getInt(KEY_DAILY_DISMISSED, 0)
        set(value) = prefs.edit { putInt(KEY_DAILY_DISMISSED, value) }

    /** Claims [day]'s chip; true exactly once per day. */
    fun claimDaily(day: Int): Boolean {
        if (dailyClaimedDay == day) return false
        dailyClaimedDay = day
        return true
    }

    /** The typed words that have already nudged on [day]. */
    fun nudgedOn(day: Int): Set<String> {
        if (prefs.getInt(KEY_NUDGED_DAY, 0) != day) return emptySet()
        return prefs.getStringSet(KEY_NUDGED_WORDS, null).orEmpty()
    }

    fun markNudged(day: Int, word: String) {
        val current = if (prefs.getInt(KEY_NUDGED_DAY, 0) == day) {
            prefs.getStringSet(KEY_NUDGED_WORDS, null).orEmpty()
        } else {
            emptySet()
        }
        prefs.edit {
            putInt(KEY_NUDGED_DAY, day)
            putStringSet(KEY_NUDGED_WORDS, current + word)
        }
    }

    private companion object {
        const val FILE_NAME = "vocab_prefs"
        const val KEY_DAILY_CLAIMED = "daily_claimed_day"
        const val KEY_DAILY_DISMISSED = "daily_dismissed_day"
        const val KEY_NUDGED_DAY = "nudged_day"
        const val KEY_NUDGED_WORDS = "nudged_words"
    }
}

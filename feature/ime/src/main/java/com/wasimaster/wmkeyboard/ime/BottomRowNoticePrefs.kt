package com.wasimaster.wmkeyboard.ime

import android.content.Context
import androidx.core.content.edit
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot

/**
 * The layouts already told that their own 🌐 key now opens emoji, since
 * "Emoji key instead of 🌐" reaches a bottom row laid out by hand
 * (Telegram, 2026-11-27).
 *
 * On disk for [WordListOfferPrefs]' reason: the promise is "once per layout",
 * and the system stops the IME process soon after the keyboard goes away. Its
 * own `SharedPreferences` file, because this records what the user has been
 * shown rather than anything they set, and has no business in a backup.
 * Device-protected storage, because the keyboard types on the lock screen.
 */
class BottomRowNoticePrefs(context: Context) {

    private val prefs = DirectBoot.deviceContext(context)
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val told: MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_GLOBE_OPENS_EMOJI, emptySet()).orEmpty())

    /** Records [layoutId] as told and answers whether that was news, as `MutableSet.add` does. */
    fun add(layoutId: String): Boolean {
        if (!told.add(layoutId)) return false
        prefs.edit { putStringSet(KEY_GLOBE_OPENS_EMOJI, HashSet(told)) }
        return true
    }

    private companion object {
        const val FILE_NAME = "bottom_row_notices"
        const val KEY_GLOBE_OPENS_EMOJI = "globe_opens_emoji"
    }
}

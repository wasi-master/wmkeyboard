package com.wasimaster.wmkeyboard.ime.wmtranslate

import android.content.Context

/**
 * Whether the translate setup is shared with WM Translate: not at all, once (the next read
 * takes it and sharing turns itself off), or kept in step. Kept on this phone only, outside the
 * settings and their backups: an import elsewhere must never start sending keys to another app.
 */
object TranslateShare {
    private const val FILE = "wm_translate_link"
    private const val KEY = "share"

    fun mode(context: Context): String =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, TranslateWire.SHARE_NONE)
            ?.takeIf { it in MODES } ?: TranslateWire.SHARE_NONE

    fun set(context: Context, mode: String) {
        if (mode !in MODES) return
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
        WmTranslateBridge.notifyConfig(context)
    }

    private val MODES = setOf(TranslateWire.SHARE_NONE, TranslateWire.SHARE_ONCE, TranslateWire.SHARE_FOLLOW)
}

package com.wasimaster.wmkeyboard.ime.vault

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the notes vault is (#1211): the folder Android's picker granted, and
 * the file or folder quick captures go to. Plain preferences of the phone's
 * own, not settings: a folder grant belongs to this phone and means nothing
 * in a backup restored on another.
 */
object VaultPrefs {
    private const val PREFS = "notes_vault"
    const val KEY_TREE = "tree"
    private const val KEY_WRITABLE = "writable"
    private const val KEY_CAPTURE_PATH = "capture_path"
    private const val KEY_CAPTURE_NAME = "capture_name"
    private const val KEY_CAPTURE_FOLDER = "capture_folder"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tree(context: Context): String? = prefs(context).getString(KEY_TREE, null)

    fun writable(context: Context): Boolean = prefs(context).getBoolean(KEY_WRITABLE, true)

    /** A new vault; the capture spot, which was inside the old one, goes with it. */
    fun setTree(context: Context, tree: String, writable: Boolean) {
        prefs(context).edit()
            .putString(KEY_TREE, tree)
            .putBoolean(KEY_WRITABLE, writable)
            .remove(KEY_CAPTURE_PATH)
            .remove(KEY_CAPTURE_NAME)
            .remove(KEY_CAPTURE_FOLDER)
            .apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun capture(context: Context): VaultCaptureSpot? {
        val p = prefs(context)
        val path = p.getString(KEY_CAPTURE_PATH, null) ?: return null
        return VaultCaptureSpot(path, p.getString(KEY_CAPTURE_NAME, null).orEmpty(), p.getBoolean(KEY_CAPTURE_FOLDER, false))
    }

    fun setCapture(context: Context, spot: VaultCaptureSpot?) {
        val edit = prefs(context).edit()
        if (spot == null) {
            edit.remove(KEY_CAPTURE_PATH).remove(KEY_CAPTURE_NAME).remove(KEY_CAPTURE_FOLDER)
        } else {
            edit.putString(KEY_CAPTURE_PATH, spot.path).putString(KEY_CAPTURE_NAME, spot.name).putBoolean(KEY_CAPTURE_FOLDER, spot.folder)
        }
        edit.apply()
    }
}

/** Where a quick capture goes: added to the end of a note, or a new note in a folder. */
data class VaultCaptureSpot(val path: String, val name: String, val folder: Boolean)

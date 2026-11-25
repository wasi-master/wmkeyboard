package com.wasimaster.wmkeyboard.core.files

import android.content.Context

/** A folder remembered by the file manager: where it is and what to call it (a document has no name in its path). */
data class FilePin(val path: String, val label: String)

/**
 * The file manager's own memory on this phone: folders pinned to Places, the
 * folders opened lately and the searches made lately. Paths mean nothing on
 * another device, so this lives in plain preferences of its own, outside
 * settings, sync and backups, as [FileTrees] does.
 */
object FilePins {

    private const val PREFS = "file_manager_places"
    private const val PINS = "pins"
    private const val RECENT = "recent_folders"
    private const val SEARCHES = "searches"
    const val RECENT_MAX = 12
    const val SEARCHES_MAX = 10

    private const val SEP = '\u001F'

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): List<FilePin> =
        prefs(context).getString(key, null).orEmpty().lines().mapNotNull { line ->
            val path = line.substringBefore(SEP)
            if (path.isEmpty()) null else FilePin(path, line.substringAfter(SEP, path.substringAfterLast('/')))
        }

    private fun write(context: Context, key: String, pins: List<FilePin>) {
        prefs(context).edit().putString(key, pins.joinToString("\n") { it.path + SEP + it.label.replace('\n', ' ') }).apply()
    }

    fun pins(context: Context): List<FilePin> = read(context, PINS)

    fun isPinned(context: Context, path: String): Boolean = pins(context).any { it.path == path }

    /** Pins [pin], or takes it off when it is already there. True when it is pinned now. */
    fun toggle(context: Context, pin: FilePin): Boolean {
        val all = pins(context)
        val there = all.any { it.path == pin.path }
        write(context, PINS, if (there) all.filterNot { it.path == pin.path } else all + pin)
        return !there
    }

    fun recentFolders(context: Context): List<FilePin> = read(context, RECENT)

    /** [pin] to the front of the recent folders, once. */
    fun visited(context: Context, pin: FilePin) {
        write(context, RECENT, (listOf(pin) + recentFolders(context).filterNot { it.path == pin.path }).take(RECENT_MAX))
    }

    fun forgetFolder(context: Context, path: String) {
        write(context, RECENT, recentFolders(context).filterNot { it.path == path })
        write(context, PINS, pins(context).filterNot { it.path == path })
    }

    fun searches(context: Context): List<String> = read(context, SEARCHES).map { it.path }

    fun searched(context: Context, text: String) {
        val query = text.trim().replace('\n', ' ')
        if (query.isEmpty()) return
        val rest = searches(context).filterNot { it.equals(query, ignoreCase = true) }
        write(context, SEARCHES, (listOf(query) + rest).take(SEARCHES_MAX).map { FilePin(it, it) })
    }

    fun forgetSearch(context: Context, text: String) {
        write(context, SEARCHES, searches(context).filterNot { it == text }.map { FilePin(it, it) })
    }

    /**
     * The "View with" choice last made for files ending in [extension] (lower
     * case, no dot): the built-in view, or a plugin preview's key. Null when
     * none was made. Kept on this device only.
     */
    fun previewChoice(context: Context, extension: String): String? =
        prefs(context).getString(CHOICE_PREFIX + extension.lowercase(), null)

    fun setPreviewChoice(context: Context, extension: String, choice: String) {
        if (extension.isEmpty()) return
        prefs(context).edit().putString(CHOICE_PREFIX + extension.lowercase(), choice).apply()
    }

    private const val CHOICE_PREFIX = "preview_choice."
}

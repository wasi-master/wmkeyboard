package com.wasimaster.wmkeyboard.core.settings

/**
 * The Files tool's settings (#1267), one nested family so the keyboard's
 * settings class stays under its field ceiling. Stored under flat keys
 * (`files_two_panes`, `files_grid`, …) like every other setting.
 */
data class FilesToolSettings(
    /**
     * Two lists side by side, so Copy and Move go straight from one to the
     * other. Set from the panel's header or the tool's page.
     */
    val twoPanes: Boolean = false,
    /** Thumbnails in a grid rather than rows; the header's view button switches it too. */
    val grid: Boolean = true,
    /** A tap on a file previews it in the keyboard rather than opening the app for it; Open with is a button there. */
    val tapPreviews: Boolean = true,
    /** Pictures and videos show their own thumbnails rather than a kind icon. */
    val thumbnails: Boolean = true,
    /** Files and folders whose names start with a dot are listed. */
    val showHidden: Boolean = false,
    /** Folders come before files whatever the sort; off, they sort among the files. */
    val foldersFirst: Boolean = true,
)

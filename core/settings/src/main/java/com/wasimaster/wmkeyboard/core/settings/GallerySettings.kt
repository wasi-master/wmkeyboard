package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryQuality

/** What a tap on a photo in the grid does; the round check in its corner always selects. */
enum class GalleryTapAction {
    /** Opens the photo full size, Telegram's way. */
    OPEN,

    /** Selects it, Signal's way; a long press still peeks. */
    SELECT,
}

/** What happens when the field takes neither pictures nor videos through the keyboard. */
enum class GalleryFallback {
    /** Shares them to the app the field belongs to, through Android's share screen for that app. */
    SHARE_TO_APP,

    /** Copies the first one to the clipboard to paste. */
    CLIPBOARD,

    /** Asks which of the two. */
    ASK,
}

/** Where a caption typed in the send bar goes. */
enum class GalleryCaption {
    /** Into the message box after the pictures, ready to send with them or after them. */
    AFTER,

    /** Into the message box before the pictures are sent. */
    BEFORE,

    /** No caption box at all. */
    OFF,
}

/**
 * The gallery tool's settings (#708), one nested bag for the reason
 * [NotesSettings] gives. Every field is its own `gallery_*` DataStore key,
 * read and written by [GalleryPrefs] so the repository carries one line for
 * the lot.
 */
data class GallerySettings(
    val tapAction: GalleryTapAction = GalleryTapAction.OPEN,
    /** Grid columns; a pinch on the grid changes it too. */
    val columns: Int = 4,
    /** A live camera tile first in the grid, opening the Camera tool. */
    val cameraTile: Boolean = true,
    val quality: GalleryQuality = GalleryQuality.HD,
    /** Clears the location from what is sent. */
    val stripLocation: Boolean = true,
    /** Clears the camera, owner and date tags too. */
    val stripAllMetadata: Boolean = true,
    /** Sends copies under a random name rather than the photo's own. */
    val randomFileNames: Boolean = true,
    /** How many can be picked at once. */
    val maxSelection: Int = 20,
    val fallback: GalleryFallback = GalleryFallback.SHARE_TO_APP,
    val caption: GalleryCaption = GalleryCaption.AFTER,
    /** The panel closes once everything is on its way. */
    val closeAfterSend: Boolean = true,
    /** Opens at full height rather than the keyboard's. */
    val startExpanded: Boolean = false,
    /** Opens on the album last looked at, rather than Recents. */
    val rememberAlbum: Boolean = true,
    /** Lists videos beside the photos. */
    val showVideos: Boolean = true,
    /** The month bubble on the grid's scroll handle. */
    val dateScrubber: Boolean = true,
    /** A tick under the finger for each pick. */
    val haptics: Boolean = true,
    /** Keeps an edited picture in the phone's gallery as well as sending it. */
    val saveEdited: Boolean = false,
    /** Remembers what was sent, for the Sent album. */
    val keepHistory: Boolean = true,
    /** The album last open, for [rememberAlbum]: an album id. Not a preference anyone sets by hand. */
    val lastAlbum: String = "",
    /**
     * Folders left out of the gallery altogether (#970), by MediaStore bucket
     * id: their photos are in no album, Recents included. A folder of
     * screenshots of receipts, a messaging app's saved media.
     */
    val hiddenFolders: Set<String> = emptySet(),
    /**
     * The user's Immich server (#1205), as typed: its address, with or
     * without `/api`. Blank, the gallery is the phone's alone.
     */
    val immichUrl: String = "",
    /** An Immich API key for [immichUrl]; in [SettingsBackup.SECRET_KEYS], so it stays out of backups. */
    val immichKey: String = "",
) {
    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 6
        const val MIN_SELECTION = 1
        const val MAX_SELECTION = 100

        val SELECTION_CHOICES: List<Int> = listOf(1, 5, 10, 20, 50, 100)
    }
}

/** The DataStore half of [GallerySettings]. */
object GalleryPrefs {
    private val TAP = stringPreferencesKey("gallery_tap_action")
    private val COLUMNS = intPreferencesKey("gallery_columns")
    private val CAMERA_TILE = booleanPreferencesKey("gallery_camera_tile")
    private val QUALITY = stringPreferencesKey("gallery_quality")
    private val STRIP_LOCATION = booleanPreferencesKey("gallery_strip_location")
    private val STRIP_ALL = booleanPreferencesKey("gallery_strip_all_metadata")
    private val RANDOM_NAMES = booleanPreferencesKey("gallery_random_file_names")
    private val MAX_SELECTION = intPreferencesKey("gallery_max_selection")
    private val FALLBACK = stringPreferencesKey("gallery_fallback")
    private val CAPTION = stringPreferencesKey("gallery_caption")
    private val CLOSE_AFTER_SEND = booleanPreferencesKey("gallery_close_after_send")
    private val START_EXPANDED = booleanPreferencesKey("gallery_start_expanded")
    private val REMEMBER_ALBUM = booleanPreferencesKey("gallery_remember_album")
    private val SHOW_VIDEOS = booleanPreferencesKey("gallery_show_videos")
    private val DATE_SCRUBBER = booleanPreferencesKey("gallery_date_scrubber")
    private val HAPTICS = booleanPreferencesKey("gallery_haptics")
    private val SAVE_EDITED = booleanPreferencesKey("gallery_save_edited")
    private val KEEP_HISTORY = booleanPreferencesKey("gallery_keep_history")
    private val LAST_ALBUM = stringPreferencesKey("gallery_last_album")
    private val HIDDEN_FOLDERS = stringSetPreferencesKey("gallery_hidden_folders")
    private val IMMICH_URL = stringPreferencesKey("gallery_immich_url")
    private val IMMICH_KEY = stringPreferencesKey(IMMICH_KEY_NAME)

    /** The API key's DataStore name, for [SettingsBackup.SECRET_KEYS]. */
    const val IMMICH_KEY_NAME = "gallery_immich_key"

    private inline fun <reified E : Enum<E>> enumOf(name: String?): E? =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    fun read(p: Preferences, d: GallerySettings): GallerySettings = GallerySettings(
        tapAction = enumOf<GalleryTapAction>(p[TAP]) ?: d.tapAction,
        columns = p[COLUMNS]?.coerceIn(GallerySettings.MIN_COLUMNS, GallerySettings.MAX_COLUMNS) ?: d.columns,
        cameraTile = p[CAMERA_TILE] ?: d.cameraTile,
        quality = GalleryQuality.of(p[QUALITY]) ?: d.quality,
        stripLocation = p[STRIP_LOCATION] ?: d.stripLocation,
        stripAllMetadata = p[STRIP_ALL] ?: d.stripAllMetadata,
        randomFileNames = p[RANDOM_NAMES] ?: d.randomFileNames,
        maxSelection = p[MAX_SELECTION]?.coerceIn(GallerySettings.MIN_SELECTION, GallerySettings.MAX_SELECTION)
            ?: d.maxSelection,
        fallback = enumOf<GalleryFallback>(p[FALLBACK]) ?: d.fallback,
        caption = enumOf<GalleryCaption>(p[CAPTION]) ?: d.caption,
        closeAfterSend = p[CLOSE_AFTER_SEND] ?: d.closeAfterSend,
        startExpanded = p[START_EXPANDED] ?: d.startExpanded,
        rememberAlbum = p[REMEMBER_ALBUM] ?: d.rememberAlbum,
        showVideos = p[SHOW_VIDEOS] ?: d.showVideos,
        dateScrubber = p[DATE_SCRUBBER] ?: d.dateScrubber,
        haptics = p[HAPTICS] ?: d.haptics,
        saveEdited = p[SAVE_EDITED] ?: d.saveEdited,
        keepHistory = p[KEEP_HISTORY] ?: d.keepHistory,
        lastAlbum = p[LAST_ALBUM] ?: d.lastAlbum,
        hiddenFolders = p[HIDDEN_FOLDERS] ?: d.hiddenFolders,
        immichUrl = p[IMMICH_URL] ?: d.immichUrl,
        immichKey = p[IMMICH_KEY] ?: d.immichKey,
    )

    /** Writes only what differs from [old], so a one-field change is a one-key edit. */
    fun write(p: MutablePreferences, old: GallerySettings, new: GallerySettings) {
        if (new.tapAction != old.tapAction) p[TAP] = new.tapAction.name
        if (new.columns != old.columns) {
            p[COLUMNS] = new.columns.coerceIn(GallerySettings.MIN_COLUMNS, GallerySettings.MAX_COLUMNS)
        }
        if (new.cameraTile != old.cameraTile) p[CAMERA_TILE] = new.cameraTile
        if (new.quality != old.quality) p[QUALITY] = new.quality.name
        if (new.stripLocation != old.stripLocation) p[STRIP_LOCATION] = new.stripLocation
        if (new.stripAllMetadata != old.stripAllMetadata) p[STRIP_ALL] = new.stripAllMetadata
        if (new.randomFileNames != old.randomFileNames) p[RANDOM_NAMES] = new.randomFileNames
        if (new.maxSelection != old.maxSelection) {
            p[MAX_SELECTION] = new.maxSelection.coerceIn(GallerySettings.MIN_SELECTION, GallerySettings.MAX_SELECTION)
        }
        if (new.fallback != old.fallback) p[FALLBACK] = new.fallback.name
        if (new.caption != old.caption) p[CAPTION] = new.caption.name
        if (new.closeAfterSend != old.closeAfterSend) p[CLOSE_AFTER_SEND] = new.closeAfterSend
        if (new.startExpanded != old.startExpanded) p[START_EXPANDED] = new.startExpanded
        if (new.rememberAlbum != old.rememberAlbum) p[REMEMBER_ALBUM] = new.rememberAlbum
        if (new.showVideos != old.showVideos) p[SHOW_VIDEOS] = new.showVideos
        if (new.dateScrubber != old.dateScrubber) p[DATE_SCRUBBER] = new.dateScrubber
        if (new.haptics != old.haptics) p[HAPTICS] = new.haptics
        if (new.saveEdited != old.saveEdited) p[SAVE_EDITED] = new.saveEdited
        if (new.keepHistory != old.keepHistory) p[KEEP_HISTORY] = new.keepHistory
        if (new.lastAlbum != old.lastAlbum) p[LAST_ALBUM] = new.lastAlbum
        if (new.hiddenFolders != old.hiddenFolders) p[HIDDEN_FOLDERS] = new.hiddenFolders
        if (new.immichUrl != old.immichUrl) p[IMMICH_URL] = new.immichUrl.trim()
        if (new.immichKey != old.immichKey) p[IMMICH_KEY] = new.immichKey.trim()
    }
}

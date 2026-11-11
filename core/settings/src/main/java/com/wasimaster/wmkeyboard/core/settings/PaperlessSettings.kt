package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** What the document scanner does with a finished scan once a Paperless server is set up (#1198). */
enum class PaperlessAfterScan {
    /** As before: the pages go into the text box, and Paperless is left out. */
    FIELD,

    /** A card asks: Paperless, the text box, or both, with the title and tags for this scan. */
    ASK,

    /** Straight to Paperless with the default tags; nothing goes into the text box. */
    PAPERLESS,

    /** To Paperless with the default tags, and into the text box as well. */
    BOTH,
}

/**
 * The scanner's Paperless-ngx settings (#1198), nested inside
 * [ScannerSettings] so they cost [KeyboardSettings] no field of its own. Every
 * field is its own `paperless_*` DataStore key, read and written by
 * [PaperlessPrefs].
 *
 * The defaults for a scan (tags, correspondent, document type) are the
 * server's own ids, as the server's lists gave them; 0 means none.
 */
data class PaperlessSettings(
    /** The server's address, as typed: with or without `/api`. Blank, Paperless is off. */
    val url: String = "",
    /** An API token from the user's profile page in Paperless. In [SettingsBackup.SECRET_KEYS]. */
    val token: String = "",
    val afterScan: PaperlessAfterScan = PaperlessAfterScan.ASK,
    /** Tag ids every scan starts with. */
    val tags: Set<Int> = emptySet(),
    val correspondent: Int = 0,
    val documentType: Int = 0,
    /** All the pages of one scan as one PDF document, rather than one document per page. */
    val onePdf: Boolean = true,
    /** Uploads wait for Wi-Fi (an unmetered network); off, any connection will do. */
    val wifiOnly: Boolean = false,
) {
    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()
}

/** The DataStore half of [PaperlessSettings]. */
object PaperlessPrefs {
    private val URL = stringPreferencesKey("paperless_url")
    private val TOKEN = stringPreferencesKey(TOKEN_NAME)
    private val AFTER_SCAN = stringPreferencesKey("paperless_after_scan")
    private val TAGS = stringSetPreferencesKey("paperless_tags")
    private val CORRESPONDENT = intPreferencesKey("paperless_correspondent")
    private val DOCUMENT_TYPE = intPreferencesKey("paperless_document_type")
    private val ONE_PDF = booleanPreferencesKey("paperless_one_pdf")
    private val WIFI_ONLY = booleanPreferencesKey("paperless_wifi_only")

    /** The token's DataStore name, for [SettingsBackup.SECRET_KEYS]. */
    const val TOKEN_NAME = "paperless_token"

    fun read(p: Preferences, d: PaperlessSettings): PaperlessSettings = PaperlessSettings(
        url = p[URL] ?: d.url,
        token = p[TOKEN] ?: d.token,
        afterScan = p[AFTER_SCAN]?.let { n -> PaperlessAfterScan.entries.firstOrNull { it.name == n } } ?: d.afterScan,
        tags = p[TAGS]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.tags,
        correspondent = p[CORRESPONDENT] ?: d.correspondent,
        documentType = p[DOCUMENT_TYPE] ?: d.documentType,
        onePdf = p[ONE_PDF] ?: d.onePdf,
        wifiOnly = p[WIFI_ONLY] ?: d.wifiOnly,
    )

    /** Writes only what differs from [old]. */
    fun write(p: MutablePreferences, old: PaperlessSettings, new: PaperlessSettings) {
        if (new.url != old.url) p[URL] = new.url.trim()
        if (new.token != old.token) p[TOKEN] = new.token.trim()
        if (new.afterScan != old.afterScan) p[AFTER_SCAN] = new.afterScan.name
        if (new.tags != old.tags) p[TAGS] = new.tags.map { it.toString() }.toSet()
        if (new.correspondent != old.correspondent) p[CORRESPONDENT] = new.correspondent
        if (new.documentType != old.documentType) p[DOCUMENT_TYPE] = new.documentType
        if (new.onePdf != old.onePdf) p[ONE_PDF] = new.onePdf
        if (new.wifiOnly != old.wifiOnly) p[WIFI_ONLY] = new.wifiOnly
    }
}

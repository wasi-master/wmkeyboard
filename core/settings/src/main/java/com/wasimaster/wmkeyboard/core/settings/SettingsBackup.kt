package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Whole-settings backup: every DataStore preference, written out as JSON.
 *
 * Deliberately generic rather than a field-by-field mapping of
 * [KeyboardSettings] — the settings list grows most weeks, and a codec that
 * had to be edited in step with it would silently start dropping whatever
 * the author forgot to add. Walking the raw preference map means a new
 * setting is backed up the day it lands.
 *
 * The type of each value has to be carried explicitly: DataStore keys are
 * typed, JSON numbers are not, and restoring an Int as a Long would make
 * the setting unreadable.
 *
 * Not included: themes, snippets and imported word lists, which are
 * separate files with their own import flows, and the learned dictionary,
 * which is personal rather than configuration.
 */
object SettingsBackup {

    const val FORMAT = "wmkeyboard-settings"
    const val VERSION = 1
    const val FILE_EXTENSION = "wmsettings.json"
    const val MIME_TYPE = "application/json"

    /**
     * The automatic backup's passphrase key name, shared with
     * [SettingsRepository] so the two cannot drift apart.
     */
    const val AUTO_BACKUP_PASSPHRASE = "auto_backup_passphrase"

    /** The WebDAV server password. A credential, so it follows the same rules. */
    const val AUTO_BACKUP_WEBDAV_PASSWORD = "auto_backup_webdav_password"

    /**
     * Every backup location, as one JSON list; see [BackupLocation]. Holds
     * passwords and refresh tokens, so it is a secret: out of device-protected
     * storage, and out of an export unless "Include API keys" is on. With it
     * on, a phone restored from that file gets its sign-ins back. The switch
     * that arms the automatic backup stays transient, so nothing starts
     * writing until the user turns it on, and a folder grant that means
     * nothing on the new phone shows up as a folder to choose again.
     */
    const val AUTO_BACKUP_LOCATIONS = "auto_backup_locations"

    /**
     * The credentials every tool's provider list shares: API keys, server
     * addresses, tokens; see [ProviderSettings]. A secret for the same
     * reasons as [AUTO_BACKUP_LOCATIONS]. The lists themselves
     * (`provider_chains`) hold no secret and travel as usual.
     */
    const val PROVIDER_CREDENTIALS = "provider_credentials"

    /** How the last run went at each location; see [LocationStatus]. */
    const val AUTO_BACKUP_LOCATION_STATUS = "auto_backup_location_status"

    /**
     * Sync's own switches. Transient, all of them: whether this phone syncs,
     * how often and where is a decision about this phone, and a restored
     * bundle or a synced file must never switch sync on somewhere else.
     */
    const val SYNC_ENABLED = "sync_enabled"
    const val SYNC_MODE = "sync_mode"
    const val SYNC_INTERVAL_HOURS = "sync_interval_hours"
    const val SYNC_SCOPE = "sync_scope"
    /** Read once, for the move to [SYNC_LOCATION_IDS]; the single choice it was. */
    const val SYNC_LOCATION_ID = "sync_location_id"
    const val SYNC_LOCATION_IDS = "sync_location_ids"
    const val SYNC_SECTIONS = "sync_sections"
    const val SYNC_INCLUDE_SECRETS = "sync_include_secrets"
    const val SYNC_WITH_PAIRED_DEVICES = "sync_with_paired_devices"
    const val SYNC_WIFI_ONLY = "sync_wifi_only"
    const val SYNC_KEEP_LOCAL = "sync_keep_local"
    const val SYNC_LAST_RUN_AT = "sync_last_run_at"
    const val SYNC_LAST_ERROR = "sync_last_error"

    /**
     * Until when scheduled syncs wait; see [BackupPause]. Transient like the
     * rest of sync: a pause is about this phone, today.
     */
    const val SYNC_PAUSED_UNTIL = "sync_paused_until"

    /**
     * Which sync group this phone is in at a location several people share;
     * see `SyncNaming`. Transient: it says whose phone this is, and a bundle
     * restored onto someone else's must not put that phone into the first
     * owner's group, where it would merge the two people's keyboards.
     */
    const val SYNC_GROUP = "sync_group"

    /** S3 secret access key. */
    const val AUTO_BACKUP_S3_SECRET = "auto_backup_s3_secret"

    /** FTP account password. */
    const val AUTO_BACKUP_FTP_PASSWORD = "auto_backup_ftp_password"

    /**
     * The two OAuth refresh tokens. A refresh token is a standing grant on the
     * user's account until they revoke it, so it is a credential in every
     * sense that matters here.
     */
    const val AUTO_BACKUP_DROPBOX_TOKEN = "auto_backup_dropbox_token"
    const val AUTO_BACKUP_ONEDRIVE_TOKEN = "auto_backup_onedrive_token"

    const val AUTO_BACKUP_ENABLED = "auto_backup_enabled"
    const val AUTO_BACKUP_FOLDER_URI = "auto_backup_folder_uri"
    const val AUTO_BACKUP_KDF_SALT = "auto_backup_kdf_salt"
    const val AUTO_BACKUP_LAST_RUN_AT = "auto_backup_last_run_at"
    const val AUTO_BACKUP_LAST_ERROR = "auto_backup_last_error"

    /** The frequent schedule's switch and record (#970): per install, as the main one's are. */
    const val FREQUENT_BACKUP_ENABLED = "frequent_backup_enabled"
    const val FREQUENT_BACKUP_LAST_RUN_AT = "frequent_backup_last_run_at"
    const val FREQUENT_BACKUP_LAST_ERROR = "frequent_backup_last_error"

    /**
     * Until when scheduled backups wait; see [BackupPause]. Per install: a
     * restored bundle that paused the new phone's backups would be the quiet
     * failure [TRANSIENT_KEYS] exists to prevent.
     */
    const val AUTO_BACKUP_PAUSED_UNTIL = "auto_backup_paused_until"

    /** A folder grant and a record of this phone's exports; neither means anything on another. */
    const val AUTOMATION_EXPORT_FOLDER = "automation_export_folder"
    const val AUTOMATION_EXPORT_LOG = "automation_export_log"

    /**
     * Credentials, kept out of an export unless explicitly asked for. A
     * settings file is the kind of thing people mail to themselves or drop
     * in a shared folder, and these grant real spend on the user's account.
     *
     * The same set is what [LockedSettings] refuses to copy into
     * device-protected storage, where a credential would sit outside the
     * user's own encryption. Add new credentials here and both follow.
     */
    val SECRET_KEYS = setOf(
        AUTO_BACKUP_PASSPHRASE,
        AUTO_BACKUP_WEBDAV_PASSWORD,
        AUTO_BACKUP_S3_SECRET,
        AUTO_BACKUP_FTP_PASSWORD,
        AUTO_BACKUP_DROPBOX_TOKEN,
        AUTO_BACKUP_ONEDRIVE_TOKEN,
        "translate_api_key",
        "deepl_api_key",
        "translate_server_key",
        "klipy_api_key",
        "brave_api_key",
        "tavily_api_key",
        LookupPrefs.FORVO_KEY_NAME,
        "ocr_online_key",
        "giphy_api_key",
        "telegram_bot_token",
        "ai_anthropic_key",
        "ai_openai_key",
        "ai_gemini_key",
        "ai_xai_key",
        "ai_deepseek_key",
        "ai_brave_key",
        "ai_compatible_key",
        "ai_mcp_headers",
        "ai_provider_headers",
        "voice_server_key",
        "voice_server_key_by_lang",
        "hf_token",
        "photo_unsplash_key",
        "photo_pexels_key",
        // A hash, not the PIN itself, but a four-digit PIN falls to a brute
        // force of its hash in minutes. Left behind, the lock reads as the
        // fingerprint on the new phone; see AppLockSettings.secret.
        APP_LOCK_SECRET,
        AUTO_BACKUP_LOCATIONS,
        PROVIDER_CREDENTIALS,
        "self_hosted_libretranslate_key",
    )

    /** The app lock's stored PIN, password or pattern hash. */
    const val APP_LOCK_SECRET = "app_lock_secret"

    /**
     * Custom themes are stored as one JSON string preference. The full-config
     * bundle ([ConfigBackup]) carries them as their own section so they can be
     * toggled independently, so it excludes these keys from the settings
     * section to avoid exporting them twice. The standalone settings backup
     * leaves them in, unchanged.
     */
    val THEME_KEYS = setOf("custom_themes")

    /**
     * Settings that describe *this install* rather than the user's choices, and
     * so must not travel in a bundle.
     *
     * [encodeSettings] walks the whole preference map on purpose, so a new
     * setting is backed up the day it lands. That is right for nearly
     * everything and wrong for these. Restore a bundle onto a new phone and
     * without this the automatic backup comes back switched on, pointing at a
     * folder URI whose grant belongs to a device that is not this one, carrying
     * a last-run time from another clock. It then fails every run, silently,
     * while the user believes they are covered. The one failure mode this
     * feature must not have is the quiet one.
     *
     * The user's actual preferences — the interval, how many to keep, which
     * sections, whether to encrypt — are not here, and do travel.
     */
    val TRANSIENT_KEYS = setOf(
        AUTO_BACKUP_ENABLED,
        AUTO_BACKUP_FOLDER_URI,
        // Per install, and only ever used to write new files: every file
        // carries the salt it was made with, so dropping this loses nothing.
        AUTO_BACKUP_KDF_SALT,
        AUTO_BACKUP_LAST_RUN_AT,
        AUTO_BACKUP_LAST_ERROR,
        AUTO_BACKUP_LOCATION_STATUS,
        AUTO_BACKUP_PAUSED_UNTIL,
        AUTOMATION_EXPORT_FOLDER,
        AUTOMATION_EXPORT_LOG,
        FREQUENT_BACKUP_ENABLED,
        FREQUENT_BACKUP_LAST_RUN_AT,
        FREQUENT_BACKUP_LAST_ERROR,
        SYNC_ENABLED,
        SYNC_MODE,
        SYNC_INTERVAL_HOURS,
        SYNC_SCOPE,
        SYNC_LOCATION_ID,
        SYNC_LOCATION_IDS,
        SYNC_SECTIONS,
        SYNC_INCLUDE_SECRETS,
        SYNC_WITH_PAIRED_DEVICES,
        SYNC_WIFI_ONLY,
        SYNC_KEEP_LOCAL,
        SYNC_LAST_RUN_AT,
        SYNC_LAST_ERROR,
        SYNC_PAUSED_UNTIL,
        SYNC_GROUP,
    )

    private val json = Json { prettyPrint = true }
    private val parser = Json { ignoreUnknownKeys = true }

    /** A decoded backup, ready to be written into DataStore. */
    data class Parsed(
        val appVersion: Int,
        val entries: List<Entry>,
        /** Keys skipped because their type tag was not recognized. */
        val skipped: Int,
    ) {
        val containsSecrets: Boolean get() = entries.any { it.name in SECRET_KEYS }
    }

    data class Entry(val name: String, val type: String, val value: Any)

    fun encode(
        prefs: Preferences,
        includeSecrets: Boolean,
        appVersion: Int,
        appVersionName: String,
    ): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("appVersion", appVersion)
            put("appVersionName", appVersionName)
            put("settings", encodeSettings(prefs, includeSecrets, exclude = TRANSIENT_KEYS))
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * The typed `{ key: { type, value } }` map on its own, without the backup
     * envelope. Shared by [encode] and by [ConfigBackup], which nests it as
     * one section of a larger bundle. [exclude] drops keys handled elsewhere.
     */
    fun encodeSettings(
        prefs: Preferences,
        includeSecrets: Boolean,
        exclude: Set<String> = emptySet(),
    ): JsonObject = buildJsonObject {
        for ((key, value) in prefs.asMap().entries.sortedBy { it.key.name }) {
            if (!includeSecrets && key.name in SECRET_KEYS) continue
            if (key.name in exclude) continue
            val encoded = encodeValue(value) ?: continue
            put(key.name, encoded)
        }
    }

    private fun encodeValue(value: Any): JsonObject? {
        val (type, encoded) = when (value) {
            is Boolean -> "boolean" to JsonPrimitive(value)
            is Int -> "int" to JsonPrimitive(value)
            is Long -> "long" to JsonPrimitive(value)
            is Float -> "float" to JsonPrimitive(value)
            is Double -> "double" to JsonPrimitive(value)
            is String -> "string" to JsonPrimitive(value)
            is Set<*> -> "stringSet" to buildJsonArray {
                for (item in value) add(JsonPrimitive(item as? String ?: return null))
            }
            else -> return null
        }
        return buildJsonObject {
            put("type", type)
            put("value", encoded)
        }
    }

    /**
     * Parses [text], or returns null when it is not a settings backup at
     * all. Individual entries that fail to parse are counted in
     * [Parsed.skipped] rather than failing the whole file, so one bad line
     * does not cost the user the other two hundred settings.
     */
    fun decode(text: String): Parsed? {
        val root = runCatching { parser.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        // `as? JsonPrimitive` instead of `.jsonPrimitive`: the latter throws on
        // a JsonObject/JsonArray value, which would break decode()'s null-on-
        // bad-input contract and crash the import path on a crafted file.
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) return null
        val settings = runCatching { root.getValue("settings").jsonObject }.getOrNull() ?: return null
        val (entries, skipped) = decodeSettings(settings)
        return Parsed(
            appVersion = (root["appVersion"] as? JsonPrimitive)?.intOrNull ?: 0,
            entries = entries,
            skipped = skipped,
        )
    }

    /**
     * Parses the typed `{ key: { type, value } }` map into entries, counting
     * (not failing on) unreadable ones. Counterpart of [encodeSettings]; used
     * both by [decode] and by [ConfigBackup]'s settings section.
     */
    fun decodeSettings(settings: JsonObject): Pair<List<Entry>, Int> {
        val entries = ArrayList<Entry>()
        var skipped = 0
        for ((name, element) in settings) {
            val parsed = runCatching { decodeEntry(name, element.jsonObject) }.getOrNull()
            if (parsed == null) skipped++ else entries.add(parsed)
        }
        return entries to skipped
    }

    private fun decodeEntry(name: String, obj: JsonObject): Entry? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val raw = obj["value"] ?: return null
        val value: Any = when (type) {
            "boolean" -> raw.jsonPrimitive.booleanOrNull ?: return null
            "int" -> raw.jsonPrimitive.intOrNull ?: return null
            "long" -> raw.jsonPrimitive.longOrNull ?: return null
            "float" -> raw.jsonPrimitive.floatOrNull ?: return null
            "double" -> raw.jsonPrimitive.doubleOrNull ?: return null
            "string" -> raw.jsonPrimitive.contentOrNull ?: return null
            "stringSet" -> raw.jsonArray.map { it.jsonPrimitive.contentOrNull ?: return null }.toSet()
            else -> return null
        }
        return Entry(name, type, value)
    }
}

/**
 * Writes one decoded [SettingsBackup.Entry] back into a preference set, under
 * the key type its tag names. Shared by the backup import, the full-config
 * restore, and [LockedSettings] — all three turn the same typed JSON back into
 * DataStore preferences.
 */
fun MutablePreferences.put(entry: SettingsBackup.Entry) {
    when (entry.type) {
        "boolean" -> set(booleanPreferencesKey(entry.name), entry.value as Boolean)
        "int" -> set(intPreferencesKey(entry.name), entry.value as Int)
        "long" -> set(longPreferencesKey(entry.name), entry.value as Long)
        "float" -> set(floatPreferencesKey(entry.name), entry.value as Float)
        "double" -> set(doublePreferencesKey(entry.name), entry.value as Double)
        "string" -> set(stringPreferencesKey(entry.name), entry.value as String)
        "stringSet" -> {
            @Suppress("UNCHECKED_CAST")
            set(stringSetPreferencesKey(entry.name), entry.value as Set<String>)
        }
    }
}

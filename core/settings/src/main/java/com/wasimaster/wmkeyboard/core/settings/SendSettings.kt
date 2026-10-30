package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Whether an upload gets a password, and who thinks of it. */
enum class SendPasswordMode {
    /** No password unless the Password chip is pressed for this upload. */
    OFF,

    /** The password box opens on every upload, empty. */
    ASK,

    /** Every upload gets a generated password, shown beside the link. */
    GENERATE,
}

/** What happens to the link the moment the upload finishes. */
enum class SendAfterUpload {
    /** Typed into the field. */
    INSERT,

    /** Copied to the clipboard. */
    COPY,

    /** Shown in the panel with its QR code, nothing else. */
    SHOW,

    /** The panel asks. */
    ASK,
}

/** The two ways the Send files tool moves a file. */
enum class SendMode {
    /** Encrypted upload to a Send instance, shared as a link. */
    LINK,

    /** Straight to another device: the same Wi-Fi, or a direct link between the two phones. */
    NEARBY,
}

/**
 * The Send files tool (#729), one nested bag for the reason [GallerySettings]
 * gives, with flat `send_*` DataStore keys read and written by [SendPrefs].
 *
 * What is *not* here: the uploads themselves (their links carry the secret and
 * the owner token), which live in `filesDir/send` and stay out of backups.
 */
data class SendSettings(
    /** The instance uploads go to. The maintainer of Send runs the default. */
    val instanceUrl: String = DEFAULT_INSTANCE,
    /** Instances the user added by address, shown first in the picker. */
    val ownInstances: List<String> = emptyList(),
    /** How long a link lives, in seconds; the server may cap it lower. */
    val expireSeconds: Long = 86_400L,
    /** How many times a link may be downloaded; the server may cap it lower. */
    val downloads: Int = 1,
    val password: SendPasswordMode = SendPasswordMode.OFF,
    val afterUpload: SendAfterUpload = SendAfterUpload.INSERT,
    /** A Send link typed, pasted or copied gets a chip offering to download it. */
    val detectLinks: Boolean = true,
    /** "Send with WM Keyboard" in other apps' share sheets. */
    val shareSheet: Boolean = true,
    /** The Nearby mode: devices on the same Wi-Fi and the direct link. */
    val nearby: Boolean = true,
    /** Leave a direct Wi-Fi link up after the transfer, for the next one. */
    val keepDirectLink: Boolean = false,
    /** Nearby also reaches LocalSend apps on this network. */
    val localSend: Boolean = true,
    /** Nearby also reaches PairDrop pages on this network, through [pairDropServer]. */
    val pairDrop: Boolean = false,
    /** The PairDrop server to meet on; blank means the public one. */
    val pairDropServer: String = "",
    /** Open a received file as soon as it has been saved. */
    val openAfterDownload: Boolean = false,
    /** Remember what was sent and received, for the history sheet. */
    val keepHistory: Boolean = true,
    /** The mode the panel last showed. Remembered, never shown as a setting. */
    val lastMode: SendMode = SendMode.LINK,
) {
    companion object {
        const val DEFAULT_INSTANCE = "https://send.vis.ee"
        const val MAX_OWN_INSTANCES = 12
        const val MIN_EXPIRE_SECONDS = 60L
        const val MAX_EXPIRE_SECONDS = 30L * 24 * 3600
        const val MAX_DOWNLOADS = 1_000_000
    }
}

/** The DataStore half of [SendSettings]. */
object SendPrefs {
    private val INSTANCE = stringPreferencesKey("send_instance_url")
    private val OWN_INSTANCES = stringPreferencesKey("send_own_instances")
    private val EXPIRE = longPreferencesKey("send_expire_seconds")
    private val DOWNLOADS = intPreferencesKey("send_downloads")
    private val PASSWORD = stringPreferencesKey("send_password")
    private val AFTER_UPLOAD = stringPreferencesKey("send_after_upload")
    private val DETECT_LINKS = booleanPreferencesKey("send_detect_links")
    private val SHARE_SHEET = booleanPreferencesKey("send_share_sheet")
    private val NEARBY = booleanPreferencesKey("send_nearby")
    private val KEEP_DIRECT = booleanPreferencesKey("send_keep_direct_link")
    private val LOCAL_SEND = booleanPreferencesKey("send_local_send")
    private val PAIR_DROP = booleanPreferencesKey("send_pair_drop")
    private val PAIR_DROP_SERVER = stringPreferencesKey("send_pair_drop_server")
    private val OPEN_AFTER = booleanPreferencesKey("send_open_after_download")
    private val KEEP_HISTORY = booleanPreferencesKey("send_keep_history")
    private val LAST_MODE = stringPreferencesKey("send_last_mode")

    private const val LIST_SEPARATOR = "\n"

    private inline fun <reified E : Enum<E>> enumOf(name: String?): E? =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    fun read(p: Preferences, d: SendSettings): SendSettings = SendSettings(
        instanceUrl = p[INSTANCE]?.takeIf { it.isNotBlank() } ?: d.instanceUrl,
        ownInstances = p[OWN_INSTANCES]?.split(LIST_SEPARATOR)?.filter { it.isNotBlank() }?.take(SendSettings.MAX_OWN_INSTANCES)
            ?: d.ownInstances,
        expireSeconds = p[EXPIRE]?.coerceIn(SendSettings.MIN_EXPIRE_SECONDS, SendSettings.MAX_EXPIRE_SECONDS) ?: d.expireSeconds,
        downloads = p[DOWNLOADS]?.coerceIn(1, SendSettings.MAX_DOWNLOADS) ?: d.downloads,
        password = enumOf<SendPasswordMode>(p[PASSWORD]) ?: d.password,
        afterUpload = enumOf<SendAfterUpload>(p[AFTER_UPLOAD]) ?: d.afterUpload,
        detectLinks = p[DETECT_LINKS] ?: d.detectLinks,
        shareSheet = p[SHARE_SHEET] ?: d.shareSheet,
        nearby = p[NEARBY] ?: d.nearby,
        keepDirectLink = p[KEEP_DIRECT] ?: d.keepDirectLink,
        localSend = p[LOCAL_SEND] ?: d.localSend,
        pairDrop = p[PAIR_DROP] ?: d.pairDrop,
        pairDropServer = p[PAIR_DROP_SERVER] ?: d.pairDropServer,
        openAfterDownload = p[OPEN_AFTER] ?: d.openAfterDownload,
        keepHistory = p[KEEP_HISTORY] ?: d.keepHistory,
        lastMode = enumOf<SendMode>(p[LAST_MODE]) ?: d.lastMode,
    )

    /** Writes only what differs from [old], so a one-field change is a one-key edit. */
    fun write(p: MutablePreferences, old: SendSettings, new: SendSettings) {
        if (new.instanceUrl != old.instanceUrl) p[INSTANCE] = new.instanceUrl
        if (new.ownInstances != old.ownInstances) {
            p[OWN_INSTANCES] = new.ownInstances.take(SendSettings.MAX_OWN_INSTANCES).joinToString(LIST_SEPARATOR)
        }
        if (new.expireSeconds != old.expireSeconds) {
            p[EXPIRE] = new.expireSeconds.coerceIn(SendSettings.MIN_EXPIRE_SECONDS, SendSettings.MAX_EXPIRE_SECONDS)
        }
        if (new.downloads != old.downloads) p[DOWNLOADS] = new.downloads.coerceIn(1, SendSettings.MAX_DOWNLOADS)
        if (new.password != old.password) p[PASSWORD] = new.password.name
        if (new.afterUpload != old.afterUpload) p[AFTER_UPLOAD] = new.afterUpload.name
        if (new.detectLinks != old.detectLinks) p[DETECT_LINKS] = new.detectLinks
        if (new.shareSheet != old.shareSheet) p[SHARE_SHEET] = new.shareSheet
        if (new.nearby != old.nearby) p[NEARBY] = new.nearby
        if (new.keepDirectLink != old.keepDirectLink) p[KEEP_DIRECT] = new.keepDirectLink
        if (new.localSend != old.localSend) p[LOCAL_SEND] = new.localSend
        if (new.pairDrop != old.pairDrop) p[PAIR_DROP] = new.pairDrop
        if (new.pairDropServer != old.pairDropServer) p[PAIR_DROP_SERVER] = new.pairDropServer
        if (new.openAfterDownload != old.openAfterDownload) p[OPEN_AFTER] = new.openAfterDownload
        if (new.keepHistory != old.keepHistory) p[KEEP_HISTORY] = new.keepHistory
        if (new.lastMode != old.lastMode) p[LAST_MODE] = new.lastMode.name
    }
}

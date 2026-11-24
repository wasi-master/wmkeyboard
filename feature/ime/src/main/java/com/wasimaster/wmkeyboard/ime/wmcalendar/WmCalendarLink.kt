package com.wasimaster.wmkeyboard.ime.wmcalendar

import android.content.Context
import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.ProviderChain
import com.wasimaster.wmkeyboard.core.providers.ProviderCodec
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.aiChoice
import com.wasimaster.wmkeyboard.core.settings.aiRows
import com.wasimaster.wmkeyboard.core.tools.calendar.CalendarBackend
import com.wasimaster.wmkeyboard.core.tools.calendar.DeviceCalendarBackend
import com.wasimaster.wmkeyboard.core.tools.calendar.WmCalendarBackend
import com.wasimaster.wmkeyboard.core.tools.calendar.WmCalendarWire
import com.wasimaster.wmkeyboard.ime.suite.SuiteTrust
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The keyboard's side of the link with WM Calendar: which calendar the Calendar tool reads and
 * adds to, and whether WM Calendar may read the keyboard's AI setup for its quick add.
 *
 * Both choices are kept on this phone only, outside the settings and their backups, as WM
 * Translate's sharing is: a backup restored elsewhere must never start sending keys to another
 * app, and whether WM Calendar is installed is this phone's business.
 */
object WmCalendarLink {
    /** Served by the keyboard, asked by WM Calendar. */
    const val KEYBOARD_AUTHORITY = "com.wasimaster.wmkeyboard.calendar"
    const val APP_PACKAGE = WmCalendarWire.PACKAGE

    const val AI_CONFIG = "ai_config"
    const val READ_IMAGE = "read_image"

    const val SHARE_NONE = "none"
    const val SHARE_ONCE = "once"
    const val SHARE_FOLLOW = "follow"

    private const val FILE = "wm_calendar_link"
    private const val SHARE = "share"
    private const val BACKEND = "backend"
    private const val READ_IMAGES = "read_images"
    private val SHARES = setOf(SHARE_NONE, SHARE_ONCE, SHARE_FOLLOW)
    private val BACKENDS = setOf(CalendarBackend.DEVICE, CalendarBackend.WM_CALENDAR)

    /** WM Calendar, asked only while it is the real one: the family's package name and certificate. */
    val wmCalendar = WmCalendarBackend { SuiteTrust.trusted(it, APP_PACKAGE) == true }

    @Volatile
    private var repository: SettingsRepository? = null

    /** Once a process, from the application: the settings the AI share reads. */
    fun start(repository: SettingsRepository) {
        this.repository = repository
    }

    fun installed(context: Context): Boolean = SuiteTrust.trusted(context, APP_PACKAGE) == true

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun share(context: Context): String = prefs(context).getString(SHARE, SHARE_NONE)?.takeIf { it in SHARES } ?: SHARE_NONE

    fun setShare(context: Context, mode: String) {
        if (mode in SHARES) prefs(context).edit().putString(SHARE, mode).apply()
    }

    /** Whether WM Calendar may have pictures read into text by the keyboard's reader on the phone. */
    fun readsImages(context: Context): Boolean = prefs(context).getBoolean(READ_IMAGES, false)

    fun setReadsImages(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(READ_IMAGES, on).apply()
    }

    /** The calendar the tool was set to use: the phone's (the start) or WM Calendar. */
    fun backendChoice(context: Context): String =
        prefs(context).getString(BACKEND, CalendarBackend.DEVICE)?.takeIf { it in BACKENDS } ?: CalendarBackend.DEVICE

    fun setBackendChoice(context: Context, id: String) {
        if (id in BACKENDS) prefs(context).edit().putString(BACKEND, id).apply()
    }

    /**
     * The backend to use now. WM Calendar when it was chosen and answers; the phone's calendar
     * otherwise, so an uninstalled WM Calendar leaves the tool working as before. Blocking:
     * it asks the package manager.
     */
    fun backend(context: Context): CalendarBackend =
        if (backendChoice(context) == CalendarBackend.WM_CALENDAR && wmCalendar.reachable(context)) wmCalendar else DeviceCalendarBackend

    /** The AI setup WM Calendar may read, or null before the settings can be read. */
    internal fun aiConfig(): String? = settings()?.let(::aiValues)

    /** The settings now; null when they cannot be read in time. Blocking. */
    internal fun settings(): KeyboardSettings? {
        val repo = repository ?: return null
        // A call can arrive as the process starts, before anything has read the settings.
        return runBlocking { withTimeoutOrNull(SETTINGS_WAIT_MS) { repo.settings.first() } }
    }

    private const val SETTINGS_WAIT_MS = 3_000L
}

/**
 * The AI list as WM Calendar reads it: `provider_credentials` and `provider_chains`, in the
 * keyboard's own settings shapes, as the WM Translate share writes them. Only the AI rows that
 * can answer are in it, with the key each one sends, the row in use first: WM Calendar asks the
 * first it knows. The on-device model is left out (it runs only inside the keyboard), and so is
 * every other tool's list.
 */
internal fun aiValues(s: KeyboardSettings): String = aiValues(s.aiRows(), s.aiChoice())

internal fun aiValues(rows: List<ResolvedProvider>, chosen: ResolvedProvider?): String {
    val ordered = listOfNotNull(chosen?.takeIf { c -> rows.any { it.key == c.key } }) + rows.filter { it.key != chosen?.key }
    val shared = ordered.filter { it.type?.onDevice != true }
    val credentials = shared.map { r ->
        ProviderCredential(
            id = r.key,
            typeId = r.typeId,
            name = r.customName,
            apiKey = r.apiKey,
            url = r.url,
            model = r.model,
            extra = r.credential?.extra.orEmpty(),
        )
    }
    val chain = ProviderChain(entries = shared.map { ProviderEntry(it.typeId, credentialId = it.key) }, mode = ChainMode.PICK)
    return buildJsonObject {
        put("provider_credentials", ProviderCodec.encodeCredentials(credentials))
        put("provider_chains", ProviderCodec.encodeChains(mapOf(ProviderTool.AI to chain)))
    }.toString()
}

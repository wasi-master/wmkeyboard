package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/** The services a calendar event can be published to as an online invitation (#1274). */
enum class InviteService { MOBILIZON, PRETIX }

/**
 * The Calendar tool's online invitations (#1274): the user's Mobilizon
 * account and Pretix organizer, nested inside [CalendarToolSettings] so they
 * cost [KeyboardSettings] no field of its own. Every field is its own
 * `invite_*` DataStore key, read and written by [InvitePrefs].
 *
 * Mobilizon is signed in to from settings with an email and a password; only
 * the tokens its `login` mutation hands back are kept, never the password.
 * The tokens and the Pretix API token are in [SettingsBackup.SECRET_KEYS],
 * stored the way every other provider key is.
 */
data class InviteSettings(
    /** The Mobilizon instance, as typed (`mobilizon.fr`, `https://events.example.org`). Blank, Mobilizon is off. */
    val mobilizonUrl: String = "",
    /** The account's email, shown on the settings page and offered again to sign back in. */
    val mobilizonEmail: String = "",
    /** The JWT the `login` mutation returned, renewed through `refreshToken`. In [SettingsBackup.SECRET_KEYS]. */
    val mobilizonAccessToken: String = "",
    /** In [SettingsBackup.SECRET_KEYS]. */
    val mobilizonRefreshToken: String = "",
    /** The Pretix server, as typed (`pretix.eu`, `https://tickets.example.org`). Blank, Pretix is off. */
    val pretixUrl: String = "",
    /** A team API token with the right to create events. In [SettingsBackup.SECRET_KEYS]. */
    val pretixToken: String = "",
    /** The organizer's short form, as in `pretix.eu/<organizer>/`. */
    val pretixOrganizer: String = "",
    /** The currency a Pretix event is set up in. Its one ticket is free, but Pretix asks for one. */
    val pretixCurrency: String = "",
    /** The service the invitation card opens on: the last one used. */
    val lastService: InviteService = InviteService.MOBILIZON,
) {
    val mobilizonSignedIn: Boolean get() = mobilizonUrl.isNotBlank() && mobilizonAccessToken.isNotBlank()
    val pretixConfigured: Boolean get() = pretixUrl.isNotBlank() && pretixToken.isNotBlank() && pretixOrganizer.isNotBlank()

    /** The services ready to publish to, in the order the card offers them. */
    val ready: List<InviteService>
        get() = buildList {
            if (mobilizonSignedIn) add(InviteService.MOBILIZON)
            if (pretixConfigured) add(InviteService.PRETIX)
        }
}

/** The DataStore half of [InviteSettings]. */
object InvitePrefs {
    private val MOBILIZON_URL = stringPreferencesKey("invite_mobilizon_url")
    private val MOBILIZON_EMAIL = stringPreferencesKey("invite_mobilizon_email")
    private val MOBILIZON_ACCESS = stringPreferencesKey(MOBILIZON_ACCESS_NAME)
    private val MOBILIZON_REFRESH = stringPreferencesKey(MOBILIZON_REFRESH_NAME)
    private val PRETIX_URL = stringPreferencesKey("invite_pretix_url")
    private val PRETIX_TOKEN = stringPreferencesKey(PRETIX_TOKEN_NAME)
    private val PRETIX_ORGANIZER = stringPreferencesKey("invite_pretix_organizer")
    private val PRETIX_CURRENCY = stringPreferencesKey("invite_pretix_currency")
    private val LAST_SERVICE = stringPreferencesKey("invite_last_service")

    /** The secret keys' DataStore names, for [SettingsBackup.SECRET_KEYS]. */
    const val MOBILIZON_ACCESS_NAME = "invite_mobilizon_access"
    const val MOBILIZON_REFRESH_NAME = "invite_mobilizon_refresh"
    const val PRETIX_TOKEN_NAME = "invite_pretix_token"

    fun read(p: Preferences, d: InviteSettings): InviteSettings = InviteSettings(
        mobilizonUrl = p[MOBILIZON_URL] ?: d.mobilizonUrl,
        mobilizonEmail = p[MOBILIZON_EMAIL] ?: d.mobilizonEmail,
        mobilizonAccessToken = p[MOBILIZON_ACCESS] ?: d.mobilizonAccessToken,
        mobilizonRefreshToken = p[MOBILIZON_REFRESH] ?: d.mobilizonRefreshToken,
        pretixUrl = p[PRETIX_URL] ?: d.pretixUrl,
        pretixToken = p[PRETIX_TOKEN] ?: d.pretixToken,
        pretixOrganizer = p[PRETIX_ORGANIZER] ?: d.pretixOrganizer,
        pretixCurrency = p[PRETIX_CURRENCY] ?: d.pretixCurrency,
        lastService = p[LAST_SERVICE]?.let { n -> InviteService.entries.firstOrNull { it.name == n } } ?: d.lastService,
    )

    /** Writes only what differs from [old]. */
    fun write(p: MutablePreferences, old: InviteSettings, new: InviteSettings) {
        if (new.mobilizonUrl != old.mobilizonUrl) p[MOBILIZON_URL] = new.mobilizonUrl.trim()
        if (new.mobilizonEmail != old.mobilizonEmail) p[MOBILIZON_EMAIL] = new.mobilizonEmail.trim()
        if (new.mobilizonAccessToken != old.mobilizonAccessToken) p[MOBILIZON_ACCESS] = new.mobilizonAccessToken
        if (new.mobilizonRefreshToken != old.mobilizonRefreshToken) p[MOBILIZON_REFRESH] = new.mobilizonRefreshToken
        if (new.pretixUrl != old.pretixUrl) p[PRETIX_URL] = new.pretixUrl.trim()
        if (new.pretixToken != old.pretixToken) p[PRETIX_TOKEN] = new.pretixToken.trim()
        if (new.pretixOrganizer != old.pretixOrganizer) p[PRETIX_ORGANIZER] = new.pretixOrganizer.trim()
        if (new.pretixCurrency != old.pretixCurrency) p[PRETIX_CURRENCY] = new.pretixCurrency.trim().uppercase()
        if (new.lastService != old.lastService) p[LAST_SERVICE] = new.lastService.name
    }
}

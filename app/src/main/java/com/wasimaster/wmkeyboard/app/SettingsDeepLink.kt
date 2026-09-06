package com.wasimaster.wmkeyboard.app

import android.net.Uri
import java.net.URI
import java.net.URLDecoder

/**
 * `wmkeyboard://` links into the settings app, so a launcher shortcut, a
 * documentation page, a support reply or another app on the device can open
 * one exact screen — or one exact switch — instead of the home list.
 *
 * | Link | Lands on |
 * |---|---|
 * | `wmkeyboard://settings` | the settings home list |
 * | `wmkeyboard://settings/<route>` | that screen, e.g. `themes`, `typing/corrections` |
 * | `wmkeyboard://settings/<route>/<id>` | a screen that names one of the user's own things, e.g. `language/en_US`, `tool/CLIPBOARD` |
 * | `wmkeyboard://settings/<route>?setting=<name>` | that screen, with one row scrolled to and flashed |
 * | `wmkeyboard://setting/<name>` | the same row, on whichever screen holds it |
 *
 * [SettingsRoutes] is the allowlist for the first half and the settings search
 * index is the allowlist for the second, which is why this parses rather than
 * trusting what it was handed. `NavController.navigate` throws on a route it
 * does not know, and the throw would land in an activity that has just been
 * resumed by a stranger's intent, so nothing reaches it unchecked. A link that
 * names no screen navigates nowhere and nothing else happens.
 *
 * **A link only ever navigates.** It cannot flip a switch, press a button, or
 * change a value: `setting=` scrolls a row into view and pulses it once, and
 * the user is the one who then touches it. The screens that show what the
 * keyboard has learned are guarded by the fingerprint lock, which gates by
 * route ([com.wasimaster.wmkeyboard.app.lock.AppLockTargets.screen]) and so
 * covers a link exactly as it covers a tap.
 *
 * Parsing works on the raw string rather than [Uri] so it is testable off
 * device; [Uri] is a framework class with no behaviour in a JVM unit test.
 * Raw path and raw query throughout, never the decoded ones: an argument
 * segment is handed to `navigate()` still encoded, because that is the form it
 * decodes from, and decoding here would turn a `%2F` inside a repository URL
 * into a path separator.
 */
object SettingsDeepLink {

    const val SCHEME = "wmkeyboard"

    /** The host that marks a link to a screen. */
    const val SCREEN_HOST = "settings"

    /** The host that marks a link to one row, wherever it lives. */
    const val SETTING_HOST = "setting"

    /** The query parameter naming one row on the screen the link opens. */
    const val SETTING_PARAM = "setting"

    /**
     * Where a link points.
     *
     * [route] is a real NavHost route, ready for `navigate()`, or empty when
     * the link named only a [setting] and the screen holding it has yet to be
     * looked up in the search index.
     *
     * [setting] is the *resource name* of a row's title — `typing_autocorrect_title`,
     * not the words "Autocorrect". A name survives translation and an app
     * update; the drawn text survives neither, and the numeric id the compiler
     * assigns survives even less.
     */
    data class Target(val route: String, val setting: String = "")

    /** Convenience for the intent's data. */
    fun parse(uri: Uri?): Target? = parse(uri?.toString())

    /**
     * The screen and row [link] names, or null when it isn't one of ours or
     * names no screen at all.
     */
    fun parse(link: String?): Target? {
        val text = link?.trim().orEmpty()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null

        // "wmkeyboard://settings/themes" puts the host and path apart; the
        // opaque "wmkeyboard:settings/themes" form carries both in one string.
        // Accept either rather than making the XML care which it wrote.
        val host: String
        val path: String
        if (uri.isOpaque) {
            val body = uri.rawSchemeSpecificPart.orEmpty().substringBefore('?')
            host = body.substringBefore('/')
            path = body.substringAfter('/', "")
        } else {
            host = uri.host.orEmpty()
            path = uri.rawPath.orEmpty()
        }
        val query = text.substringAfter('?', "")

        return when {
            host.equals(SETTING_HOST, ignoreCase = true) -> {
                // The row is the whole address here; the screen it sits on is
                // whatever the index says, so the route is filled in later.
                settingName(path.trim('/'))?.let { Target(route = "", setting = it) }
            }

            host.equals(SCREEN_HOST, ignoreCase = true) -> {
                val setting = settingName(query.param(SETTING_PARAM)).orEmpty()
                val body = path.trim('/')
                // A bare "wmkeyboard://settings" is the app's front door, and
                // "wmkeyboard://settings?setting=…" is the same address as the
                // setting host: no screen named, so the index names one.
                if (body.isEmpty()) {
                    when {
                        setting.isNotEmpty() -> Target(route = "", setting = setting)
                        else -> Target(route = "home")
                    }
                } else {
                    SettingsRoutes.resolve(body)?.let { Target(it, setting) }
                }
            }

            else -> null
        }
    }

    /**
     * The route for [target], resolving the screen from [index] when the link
     * named a row and no screen, and null when the row is not one the app has.
     *
     * A row can be indexed on more than one screen — the backup screen carries
     * a toggle named after nearly every feature — so the entry with the
     * strongest claim wins, which is exactly the order
     * [EntryWeight] already declares for search results.
     */
    internal fun resolve(target: Target, index: () -> List<SettingsSearchEntry>): SettingsSearchEntry? {
        if (target.setting.isEmpty()) return null
        val suffix = "#${target.setting}"
        return index()
            .filter { entry -> entry.key.endsWith(suffix) }
            .filter { entry -> target.route.isEmpty() || entry.route == target.route }
            .minByOrNull { entry -> entry.weight.ordinal }
    }

    /**
     * [name] if it looks like a string resource's name, else null.
     *
     * The shape `aapt` gives them, so nothing else can be smuggled through the
     * parameter — the value reaches a lookup by name, and a lookup is a place
     * where "whatever the caller sent" has never been the right input.
     */
    private fun settingName(name: String?): String? = name
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,127}")) }

    /** One query parameter out of a raw `a=1&b=2` string. */
    private fun String.param(name: String): String? = split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotBlank() }
}

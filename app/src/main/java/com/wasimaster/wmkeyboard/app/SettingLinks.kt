package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.HelpOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.filled.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StarBorder
import com.wasimaster.wmkeyboard.core.settings.SettingLinkMode
import com.wasimaster.wmkeyboard.core.settings.SettingsDepth
import com.wasimaster.wmkeyboard.core.ui.DisabledReason
import java.net.URLEncoder

/*
 * Links to one setting, copied from the setting itself (#646).
 *
 * The documentation site's link builder already writes these, but only for
 * someone who goes there and finds the row in a list. Here the row the user is
 * looking at hands over its own address: on a long press, or from a small
 * button beside its reset and its "?", after **About › Documentation ›
 * Setting links**. What is copied is the site's `/open/` web address around a
 * `wmkeyboard://` link, the form the builder calls "Web address (https)": a
 * chat app makes that tappable where it would leave the bare scheme as text,
 * and this app claims the page as an App Link, so the tap lands here.
 */

/** Where a row's link points: the screen, when it has one a link can name, and the row's resource name. */
@Immutable
internal data class SettingLinkTarget(val route: String?, val name: String)

internal object SettingLinks {

    /**
     * The `wmkeyboard://` link to the row [name] on [route].
     *
     * A row whose screen no link can name, or whose screen is not known, gets
     * the setting host instead, which finds the screen through the search
     * index. [version] becomes `since=`, so a copy of the app too old to have
     * the row can say which version does rather than open nothing. A copy
     * passes the release the row arrived in, from [SettingsSince]. It is left
     * out when empty or not a plain version number, as a debug build's may
     * not be, because [SettingsDeepLink] would refuse it anyway.
     */
    fun appLink(route: String?, name: String, version: String): String {
        val since = version.takeIf { it.matches(VERSION) }
            ?.let { "${SettingsDeepLink.SINCE_PARAM}=$it" }
        val screen = route?.takeIf { it.isNotEmpty() && !it.contains('{') }
            ?.let { SettingsRoutes.resolve(it) }
            ?.let(::portable)
        return if (screen != null) {
            val params = listOfNotNull("${SettingsDeepLink.SETTING_PARAM}=$name", since)
            "${SettingsDeepLink.SCHEME}://${SettingsDeepLink.SCREEN_HOST}/$screen?${params.joinToString("&")}"
        } else {
            "${SettingsDeepLink.SCHEME}://${SettingsDeepLink.SETTING_HOST}/$name" + since?.let { "?$it" }.orEmpty()
        }
    }

    /**
     * The link that opens the way out of [reason], or null when it names
     * none. A row name goes through [appLink], which falls back to the
     * settings index when the screen is unknown; a bare screen opens on its
     * top.
     */
    fun reasonLink(reason: DisabledReason): String? {
        val name = reason.setting.orEmpty()
        if (name.isNotEmpty()) return appLink(reason.route, name, "")
        val screen = reason.route?.takeIf { !it.contains('{') }?.let { SettingsRoutes.resolve(it) } ?: return null
        return "${SettingsDeepLink.SCHEME}://${SettingsDeepLink.SCREEN_HOST}/$screen"
    }

    /** [appLink] inside the documentation site's `/open/` page; see [WebOpenLink]. */
    /**
     * The theme id a copied link puts in place of the sender's own: a custom
     * theme's id (`custom_1793115110139`) exists on one phone only, so a link
     * to its editor opened anywhere else said the theme did not exist. The
     * editor reads this as "the theme this keyboard is wearing"; see
     * [themeEditTarget].
     */
    const val CURRENT_THEME = "current"

    private val PER_THEME = Regex("^(theme_edit|theme_assets)/[^/?]+")

    /** [route] with anything that names one phone's own theme made to name the reader's. */
    fun portable(route: String): String = PER_THEME.replace(route) { "${it.groupValues[1]}/$CURRENT_THEME" }

    /**
     * The theme the editor at `theme_edit/[requested]` opens: [requested]
     * when it is one of [customThemeIds], else the [appliedId] theme when
     * that one is, else [requested] as it was, which the editor answers by
     * saying it is gone. A link copied on another phone, or one written
     * before links said [CURRENT_THEME], lands on the reader's own theme
     * rather than a dead end.
     */
    fun themeEditTarget(requested: String, appliedId: String, isCustom: (String) -> Boolean): String = when {
        requested != CURRENT_THEME && isCustom(requested) -> requested
        isCustom(appliedId) -> appliedId
        else -> requested
    }

    fun webLink(appLink: String): String = "$DOCS_URL${WebOpenLink.PATH}/#$appLink"

    /**
     * The route a back stack entry is showing, with its arguments filled in:
     * `tool/CLIPBOARD` for `tool/{tool}`. [argument] reads one argument by
     * name. Null when an argument is missing, since a route with a hole in it
     * names no screen.
     *
     * Arguments are percent-encoded, which is the form [SettingsRoutes.resolve]
     * keeps and `navigate()` decodes from.
     */
    fun concreteRoute(pattern: String?, argument: (String) -> String?): String? {
        if (pattern.isNullOrEmpty()) return null
        var missing = false
        val route = ARGUMENT.replace(pattern) { match ->
            val value = argument(match.groupValues[1])
            if (value == null) {
                missing = true
                ""
            } else {
                URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            }
        }
        return route.takeUnless { missing }
    }

    /**
     * The settings path down to one row, as the breadcrumb strip names it:
     * every screen on [trail] up to and including [entryId]'s, then [row]
     * unless the screen is already called that (a group heading on a page of
     * the same name). The whole trail when [entryId] is not on it yet.
     */
    fun path(trail: List<SettingsCrumb>, entryId: String?, row: String?): List<String> {
        val at = trail.indexOfFirst { it.entryId == entryId }
        val screens = (if (at < 0) trail else trail.subList(0, at + 1)).map { it.title }.filter { it.isNotBlank() }
        return if (row.isNullOrBlank() || screens.lastOrNull() == row) screens else screens + row
    }

    /**
     * What a copy puts on the clipboard: the path on a line of its own above
     * the link, so whoever it is sent to can walk there by hand when the link
     * will not open, or when they would rather not tap a link they were sent.
     */
    fun clipText(path: List<String>, link: String): String =
        if (path.isEmpty()) link else path.joinToString(PATH_SEPARATOR) + "\n" + link

    private const val PATH_SEPARATOR = " › "
    private val VERSION = Regex("[0-9]{1,4}(\\.[0-9]{1,6}){0,3}")
    private val ARGUMENT = Regex("\\{([^}]+)\\}")
}

/**
 * What the settings app needs to answer "what is this row's link": the mode
 * the user picked, and a way to name a row that only has its drawn title.
 *
 * Published at the settings root as [LocalSettingLinks], whatever the mode:
 * favourites (#898) name a row by the same link, and they are on while links
 * are off. Nothing here costs anything until a row is actually asked about.
 */
@Stable
internal class SettingLinkContext(
    val mode: SettingLinkMode,
    private val resources: Resources,
) {
    /**
     * The search index by title, built the first time a row without its own
     * resource asks. A group heading is the usual one: most groups are named
     * with a plain string, and the index is the only place that knows which
     * resource that string came from.
     */
    private val byTitle: Map<String, List<SettingsSearchEntry>> by lazy {
        settingsSearchIndex(resources).groupBy { it.title }
    }

    /**
     * A screen's name by its route, from the index's own destination entries,
     * so a favourite screen reads in the language the app is in now rather
     * than the one it was pinned in. Tool pages first, screens over them.
     */
    private val screenTitles: Map<String, String> by lazy {
        val all = byTitle.values.flatten()
        all.filter { it.weight == EntryWeight.TOOL }.associate { it.route to it.title } +
            all.filter { it.weight == EntryWeight.SECTION }.associate { it.route to it.title }
    }

    /** The name the index gives the screen at [route], or null when it has none. */
    fun screenTitle(route: String): String? = screenTitles[route]

    /** The screen the index puts the row [name] on, or null when it lists it nowhere or in several places. */
    fun routeOf(name: String): String? =
        byTitle.values.flatten().filter { it.key.substringAfter('#') == name }.map { it.route }.distinct().singleOrNull()

    /** Every row name the index knows, for a link that has no screen to name. */
    private val indexed: Set<String> by lazy {
        byTitle.values.flatten().mapTo(HashSet()) { it.key.substringAfter('#') }
    }

    /**
     * The link target of a row on [route], named by its title resource [key]
     * or, failing that, by its drawn [title]. Null for a row nothing can name:
     * a language, a layout, anything the user made.
     */
    fun target(route: String?, title: String?, @StringRes key: Int): SettingLinkTarget? {
        val screen = route?.takeIf { SettingsRoutes.resolve(it) != null }
        if (key != 0) {
            val name = runCatching { resources.getResourceEntryName(key) }.getOrNull() ?: return null
            // Without a screen the link leans on the index, so the row has to
            // be in it, or the link would open a "needs a newer version" prompt
            // for a row this very version has.
            if (screen == null && name !in indexed) return null
            return SettingLinkTarget(screen, name)
        }
        if (title.isNullOrEmpty()) return null
        val entries = byTitle[title].orEmpty()
        val pattern = SettingsRoutes.patternOf(screen)
        val here = entries.firstOrNull { it.route == screen || (it.screenPattern != null && it.screenPattern == pattern) }
        if (here != null) return SettingLinkTarget(screen, here.key.substringAfter('#'))
        // Indexed somewhere else under one name: the setting host finds it.
        val names = entries.map { it.key.substringAfter('#') }.distinct()
        return names.singleOrNull()?.let { SettingLinkTarget(null, it) }
    }
}

/** See [SettingLinkContext]. Null outside the settings host. */
internal val LocalSettingLinks = compositionLocalOf<SettingLinkContext?> { null }

/** The [SettingLinkContext] the settings root publishes for [mode]. */
@Composable
internal fun rememberSettingLinks(mode: SettingLinkMode): SettingLinkContext {
    val resources = LocalContext.current.resources
    return remember(mode, resources) { SettingLinkContext(mode, resources) }
}

/**
 * The row a link button or a long press below this point belongs to, as
 * [HighlightableRow] knows it: the drawn title and its resource. Null where
 * no row has claimed the space.
 */
@Immutable
internal data class RowLinkKey(val title: String?, @StringRes val key: Int)

internal val LocalRowLinkKey = compositionLocalOf<RowLinkKey?> { null }

/** The route the screen being drawn is showing, arguments filled in. */
@Composable
internal fun currentLinkRoute(): String? {
    val entry = currentCrumbEntry() ?: return null
    return remember(entry) {
        val args: Bundle? = entry.arguments
        SettingLinks.concreteRoute(entry.destination.route) { name ->
            @Suppress("DEPRECATION")
            args?.get(name)?.toString()
        }
    }
}

/**
 * The link target of the row around the caller, or null when it has none or
 * links are off. Worked out while composing, so only for the link button:
 * a row without its own resource builds the whole search index to answer.
 */
@Composable
internal fun currentRowLink(): SettingLinkTarget? {
    val links = LocalSettingLinks.current?.takeIf { it.mode != SettingLinkMode.OFF } ?: return null
    val row = LocalRowLinkKey.current ?: return null
    val route = currentLinkRoute()
    return remember(links, row, route) { links.target(route, row.title, row.key) }
}

/**
 * The settings path to the row around the caller, read when it is asked for
 * rather than while composing: the trail is still settling on a screen's first
 * frames, and a copy is only ever made long after them.
 */
@Composable
internal fun rememberSettingPath(): () -> List<String> {
    val trail = LocalSettingsCrumbTrail.current
    val entryId = currentCrumbEntry()?.id
    val row = LocalRowLinkKey.current
    val resources = LocalContext.current.resources
    return remember(trail, entryId, row, resources) {
        {
            val title = row?.title ?: row?.key?.takeIf { it != 0 }?.let { runCatching { resources.getString(it) }.getOrNull() }
            SettingLinks.path(trail?.path.orEmpty(), entryId, title)
        }
    }
}

/**
 * Puts [target]'s web link on the clipboard, under the settings [path] that
 * leads to it, and says so where the system does not.
 */
internal fun copySettingLink(context: Context, target: SettingLinkTarget, path: List<String> = emptyList()) {
    // The release the row arrived in, not this build's version: a 0.5 row
    // copied on 0.6.6 would otherwise tell a 0.6.0 reader to update for
    // nothing.
    val since = SettingsSince.versionFor(context, target.route, target.name, BuildConfig.VERSION_NAME)
    val link = SettingLinks.webLink(SettingLinks.appLink(target.route, target.name, since))
    val text = SettingLinks.clipText(path, link)
    val copied = runCatching {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(link, text))
    }.isSuccess
    // Android 13 and later show their own confirmation for every copy.
    if (copied && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.about_setting_link_copied, Toast.LENGTH_SHORT).show()
    }
}

/** The link button's tap target: the reset control's size, for the same reason. */
internal val SettingLinkTargetSize = RowExtraSize

/**
 * The small link button a row shows beside its reset and its "?" while
 * **Setting links** is on Button. Draws nothing in any other mode, or on a row
 * that has no link, or inside a [RowExtras] at the Simple depth, where a row
 * is its name and its control and the long press copies the link instead
 * (see [holdCopiesLink]). [name] is the row's own name, for the content
 * description.
 */
@Composable
internal fun SettingLinkButton(name: String) {
    if (LocalSettingLinks.current?.mode != SettingLinkMode.BUTTON || LocalRowExtrasBare.current) return
    val target = currentRowLink() ?: return
    val context = LocalContext.current
    val path = rememberSettingPath()
    IconButton(
        onClick = { copySettingLink(context, target, path()) },
        modifier = Modifier.size(SettingLinkTargetSize),
    ) {
        Icon(
            Icons.Outlined.Link,
            contentDescription = stringResource(R.string.about_setting_link_desc, name),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Whether the link button is drawn on headings, for the headings that budget their own width. */
@Composable
internal fun settingLinkButtonShown(): Boolean = LocalSettingLinks.current?.mode == SettingLinkMode.BUTTON

/**
 * Whether the link button is drawn on rows, for the rows that budget their own
 * width: on Button, and not at the Simple depth, where [RowExtras] leaves it
 * out along with the "?".
 */
@Composable
internal fun rowLinkButtonShown(): Boolean =
    settingLinkButtonShown() && LocalSettingsDepth.current.depth != SettingsDepth.SIMPLE

/**
 * Whether a long press copies a row's link: **Setting links** on Hold, or on
 * Button at the Simple depth, where the button has left the row and the hold
 * is what is left to reach the link by.
 */
@Composable
private fun holdCopiesLink(): Boolean {
    val mode = LocalSettingLinks.current?.mode ?: return false
    return mode == SettingLinkMode.HOLD ||
        (mode == SettingLinkMode.BUTTON && LocalSettingsDepth.current.depth == SettingsDepth.SIMPLE)
}

/**
 * A row's explanation, the one its "?" opens, carried by its hold at the Simple
 * depth where the "?" is not drawn (see [RowExtras]).
 */
@Immutable
internal class RowInfo(val title: String, val detail: String)

/**
 * A row's long press, open as a small menu: the row it was for, and where
 * under the finger the menu opens. One per row that can be held, made by
 * [rememberRowHold]; [settingLinkHold] fills it and [RowHoldMenu] draws it.
 */
@Stable
internal class RowHold {
    /** The row the menu is open for, or null while it is closed or the row has no link. */
    var held by mutableStateOf<SettingLinkTarget?>(null)

    /** Why the row is greyed out, while the menu is open on a disabled row. */
    var reason by mutableStateOf<DisabledReason?>(null)

    /** The row's explanation, while the menu is open on a row whose "?" is not drawn. */
    var info by mutableStateOf<RowInfo?>(null)

    /** The explanation "What is this?" was chosen for, while its dialog is showing. */
    var explain by mutableStateOf<RowInfo?>(null)

    /** Whether the menu is showing. */
    val open: Boolean get() = held != null || reason != null || info != null

    fun close() {
        held = null
        reason = null
        info = null
    }

    /** The menu's offset from the row's bottom left: where the finger was. */
    var at by mutableStateOf(DpOffset.Zero)
}

/**
 * The [RowHold] for the row around the caller, or null when a long press has
 * nothing to offer: no favourites (#898), no link to copy ([holdCopiesLink]),
 * no [disabledReason] for a greyed-out row to give, and no [info] whose "?"
 * has left the row.
 */
@Composable
internal fun rememberRowHold(disabledReason: DisabledReason? = null, info: RowInfo? = null): RowHold? {
    val copies = holdCopiesLink()
    if (!copies && LocalSettingsFavourites.current == null && disabledReason == null && info == null) return null
    return remember { RowHold() }
}

/**
 * Opens [hold]'s menu on a long press: add the row to favourites, and copy its
 * link while **Setting links** is on Hold. With favourites off there is
 * nothing to choose between, and the hold copies the link straight away, as
 * it always has.
 *
 * The row's own click stays its own. The press is watched in the main pass,
 * after the row's controls have seen it, and given up the moment one of them
 * takes it — a slider starting to drag, a field selecting text — or the
 * finger moves or lifts. Only once the hold has fired does this step in front
 * of the row, taking the rest of the gesture in the initial pass so the lift
 * that ends a hold is not also read as a tap.
 *
 * Which row it is gets worked out when the hold fires, not while composing:
 * a row named only by its drawn title is looked up in the search index, and
 * building that for every row of every screen to answer a press that almost
 * never comes would be most of the cost of a screen.
 *
 * [disabledReason] is a greyed-out row's answer to "why can't I use this"
 * (see [DisabledReason]). With one, the hold always opens the menu, reason
 * first, even on a row nothing can link to, and the greyed-out controls
 * inside take no part: a disabled switch or button does not consume the
 * press, so the hold sees it through them.
 *
 * [info] is the row's explanation while its "?" is not drawn (the Simple
 * depth, see [RowExtras]); like a reason, it makes the hold open the menu,
 * with "What is this?" first.
 */
@Composable
internal fun Modifier.settingLinkHold(
    hold: RowHold?,
    disabledReason: DisabledReason? = null,
    info: RowInfo? = null,
): Modifier {
    if (hold == null) return this
    val links = LocalSettingLinks.current
    val row = LocalRowLinkKey.current
    if ((links == null || row == null) && disabledReason == null && info == null) return this
    val route = currentLinkRoute()
    val menu = LocalSettingsFavourites.current != null
    val context = LocalContext.current
    val view = LocalView.current
    val config = LocalViewConfiguration.current
    val path = rememberSettingPath()
    return pointerInput(hold, links, row, route, menu, path, disabledReason, info) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // The scope's own timeout: the gesture scope restricts suspension,
            // so the coroutines one cannot be called from in here.
            val held = withTimeoutOrNull(config.longPressTimeoutMillis) {
                var givenUp = false
                while (!givenUp) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    givenUp = change == null || !change.pressed || change.isConsumed ||
                        (change.position - down.position).getDistance() > config.touchSlop
                }
                false
            } == null
            if (!held) return@awaitEachGesture
            // A language, a layout, anything the user made: nothing to name.
            val target = if (links != null && row != null) links.target(route, row.title, row.key) else null
            if (target == null && disabledReason == null && info == null) return@awaitEachGesture
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (menu || disabledReason != null || info != null) {
                hold.at = DpOffset(down.position.x.toDp(), (down.position.y - size.height).toDp())
                hold.reason = disabledReason
                hold.info = info
                hold.held = target
            } else if (target != null) {
                copySettingLink(context, target, path())
            }
            // The rest of this gesture is the hold's, not the row's.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/**
 * [hold]'s menu, drawn by the row that owns it once a long press has opened
 * it, and the explanation dialog after "What is this?" was chosen from it.
 * Composes nothing the rest of the time.
 */
@Composable
internal fun RowHoldMenu(hold: RowHold?) {
    if (hold == null) return
    val explain = hold.explain
    if (explain != null) {
        // The same dialog the "?" opens where it is drawn.
        InfoDialog(explain.title, explain.detail) { hold.explain = null }
    }
    if (!hold.open) return
    val target = hold.held
    val reason = hold.reason
    val info = hold.info
    val favourites = LocalSettingsFavourites.current
    val copies = holdCopiesLink()
    val context = LocalContext.current
    val path = rememberSettingPath()
    val openLink = LocalOpenSettingLink.current
    val links = LocalSettingLinks.current
    val here = currentLinkRoute()
    val close = { hold.close() }
    DropdownMenu(expanded = true, onDismissRequest = close, offset = hold.at) {
        if (info != null) {
            // First, above a reason: what the row is comes before why it is off.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_hold_what_is_this)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) },
                onClick = {
                    close()
                    hold.explain = info
                },
            )
            if (reason != null || (target != null && (favourites != null || copies))) HorizontalDivider()
        }
        if (reason != null) {
            DisabledReasonMenuItems(reason, divider = target != null && (favourites != null || copies)) { link ->
                close()
                // A switch on this same screen is scrolled to and pulsed
                // rather than opened again on top of it, which would leave
                // the user a Back away from where they were.
                val name = reason.setting.orEmpty()
                val onScreen = name.isNotEmpty() && here != null &&
                    (reason.route ?: links?.routeOf(name)) == here
                @Suppress("DiscouragedApi")
                val row = if (onScreen) context.resources.getIdentifier(name, "string", context.packageName) else 0
                if (row != 0) SettingsHighlight.request(row) else openLink?.invoke(link)
            }
        }
        if (target == null) return@DropdownMenu
        if (favourites != null) {
            val link = remember(target) { SettingsFavourites.rowLink(target) }
            val pinned = favourites.contains(link)
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.favourites_remove else R.string.favourites_add)) },
                leadingIcon = { Icon(if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder, null) },
                onClick = {
                    close()
                    val steps = path()
                    favourites.toggle(SettingsFavourite(link, steps.lastOrNull().orEmpty(), steps.dropLast(1)))
                },
            )
        }
        if (copies) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.favourites_copy_link)) },
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                onClick = {
                    close()
                    copySettingLink(context, target, path())
                },
            )
        }
    }
}

/**
 * The top of a greyed-out row's long-press menu: why it is greyed out, and the
 * way to the setting that lifts it when [reason] names one. [divider] sets it
 * apart from the favourite and link items under it.
 */
@Composable
private fun DisabledReasonMenuItems(reason: DisabledReason, divider: Boolean, onOpen: (String) -> Unit) {
    Text(
        reason.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .widthIn(max = DisabledReasonMaxWidth)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
    val link = remember(reason) { SettingLinks.reasonLink(reason) }
    if (link != null) {
        DropdownMenuItem(
            text = { Text(stringResource(CommonR.string.common_disabled_open_setting)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
            onClick = { onOpen(link) },
        )
    }
    if (divider) HorizontalDivider()
}

/** How wide a reason may run before it wraps: a menu, not a dialog. */
private val DisabledReasonMaxWidth = 280.dp

/**
 * A [DisabledReason] for a settings row, its way out named by the title
 * resource of the switch that lifts it: [setting], on [route] when the caller
 * knows the screen, or wherever the settings index lists that row when not.
 * Pass no [setting] for a block nothing in settings can lift.
 */
@Composable
internal fun rememberDisabledReason(text: String, @StringRes setting: Int = 0, route: String? = null): DisabledReason {
    val resources = LocalContext.current.resources
    return remember(text, setting, route, resources) {
        DisabledReason(
            text,
            route = route,
            setting = setting.takeIf { it != 0 }?.let { runCatching { resources.getResourceEntryName(it) }.getOrNull() },
        )
    }
}

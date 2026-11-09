package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DocumentScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Draw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.School
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewCarousel
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Widgets
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.extensionOn
import com.wasimaster.wmkeyboard.core.settings.extensionsResolved
import com.wasimaster.wmkeyboard.core.settings.liveTools
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import kotlinx.coroutines.launch

/*
 * Extensions (SETTINGS_REDESIGN.md §3): the far-fetched features, on or off.
 *
 * The bundles themselves, what each owns and the stored set live in
 * `core/settings` ([Extension]); this file is the settings app's side: the
 * screen, a bundle's own page, the names and glyphs, and the two helpers the
 * six choke points read. Those are [LocalExtensions], published by the nav
 * host for Home, Advanced, the Tools list and every picker, and
 * [extensionRedirect], which the nav graph asks before opening any route so
 * that a link to a screen a switched-off bundle owns lands on this screen
 * with that bundle flashed instead. [ExtensionGate] is the same check at the
 * destination, for the hundred navigate() calls the graph makes directly.
 */

/** The screen's route, as the nav graph and [SettingsRoutes] declare it. */
internal const val ExtensionsRoute = "extensions"

/** One bundle's page. Filled with the enum name, as a tool page is. */
internal const val ExtensionRoutePattern = "extension/{extension}"

/** The filled route of [extension]'s own page. */
internal fun extensionRoute(extension: Extension): String = "extension/${extension.name}"

/** The bundles switched on, for every settings screen. All of them until the nav host says otherwise. */
internal val LocalExtensions = compositionLocalOf { Extension.All }

/** Whether [extension] is on where this is composed; see [LocalExtensions]. */
@Composable
internal fun extensionOn(extension: Extension): Boolean = extension in LocalExtensions.current

@StringRes
internal fun extensionTitle(extension: Extension): Int = when (extension) {
    Extension.STICKERS -> R.string.extension_stickers_title
    Extension.SCANNERS -> R.string.extension_scanners_title
    Extension.HANDWRITING -> R.string.extension_handwriting_title
    Extension.NOTES -> R.string.extension_notes_title
    Extension.VOCABULARY -> R.string.extension_vocabulary_title
    Extension.CALCULATORS -> R.string.extension_calculators_title
    Extension.WEB_SEARCH -> R.string.extension_web_search_title
    Extension.CAMERA_AND_GALLERY -> R.string.extension_camera_gallery_title
    Extension.CALENDAR_AND_CLOCK -> R.string.extension_calendar_clock_title
    Extension.GENERATORS -> R.string.extension_generators_title
    Extension.TYPING_TEST -> R.string.extension_typing_test_title
    Extension.READ_BACK -> R.string.extension_read_back_title
    Extension.LEARN_FROM_TEXT -> R.string.extension_learn_from_text_title
    Extension.FIND_REPLACE -> R.string.extension_find_replace_title
    Extension.MODES -> R.string.extension_modes_title
    Extension.MEDIA_CONTROLS -> R.string.extension_media_controls_title
    Extension.APP_LAUNCHER -> R.string.extension_app_launcher_title
    Extension.WEATHER_AND_LOCATION -> R.string.extension_weather_location_title
    Extension.INSTRUMENTS -> R.string.extension_instruments_title
    Extension.MUSIC_ID -> R.string.extension_music_id_title
    Extension.SPEED_TEST -> R.string.extension_speed_test_title
    Extension.KDE_CONNECT -> R.string.extension_kde_connect_title
    Extension.SEND_FILES -> R.string.extension_send_files_title
    Extension.SMART_HOME -> R.string.extension_smart_home_title
}

/** One line on what the bundle adds, drawn under its row and its heading. */
@StringRes
internal fun extensionDescription(extension: Extension): Int = when (extension) {
    Extension.STICKERS -> R.string.extension_stickers_desc
    Extension.SCANNERS -> R.string.extension_scanners_desc
    Extension.HANDWRITING -> R.string.extension_handwriting_desc
    Extension.NOTES -> R.string.extension_notes_desc
    Extension.VOCABULARY -> R.string.extension_vocabulary_desc
    Extension.CALCULATORS -> R.string.extension_calculators_desc
    Extension.WEB_SEARCH -> R.string.extension_web_search_desc
    Extension.CAMERA_AND_GALLERY -> R.string.extension_camera_gallery_desc
    Extension.CALENDAR_AND_CLOCK -> R.string.extension_calendar_clock_desc
    Extension.GENERATORS -> R.string.extension_generators_desc
    Extension.TYPING_TEST -> R.string.extension_typing_test_desc
    Extension.READ_BACK -> R.string.extension_read_back_desc
    Extension.LEARN_FROM_TEXT -> R.string.extension_learn_from_text_desc
    Extension.FIND_REPLACE -> R.string.extension_find_replace_desc
    Extension.MODES -> R.string.extension_modes_desc
    Extension.MEDIA_CONTROLS -> R.string.extension_media_controls_desc
    Extension.APP_LAUNCHER -> R.string.extension_app_launcher_desc
    Extension.WEATHER_AND_LOCATION -> R.string.extension_weather_location_desc
    Extension.INSTRUMENTS -> R.string.extension_instruments_desc
    Extension.MUSIC_ID -> R.string.extension_music_id_desc
    Extension.SPEED_TEST -> R.string.extension_speed_test_desc
    Extension.KDE_CONNECT -> R.string.extension_kde_connect_desc
    Extension.SEND_FILES -> R.string.extension_send_files_desc
    Extension.SMART_HOME -> R.string.extension_smart_home_desc
}

/** The bundle's glyph: the one its Home row or its first tool already wears, where it has one. */
internal fun extensionIcon(extension: Extension): ImageVector = when (extension) {
    Extension.STICKERS -> Icons.Outlined.EmojiEmotions
    Extension.SCANNERS -> Icons.Outlined.DocumentScanner
    Extension.HANDWRITING -> Icons.Outlined.Draw
    Extension.NOTES -> Icons.Outlined.StickyNote2
    Extension.VOCABULARY -> Icons.Outlined.School
    Extension.MODES -> Icons.Outlined.ViewCarousel
    // The rest wear their first tool's own glyph, the one the toolbar shows.
    else -> extension.tools.firstOrNull()?.let(::toolIconFor) ?: Icons.Outlined.Widgets
}

/**
 * The bundle's accent, for its page's heading and its search result. The
 * same hue as the Home row or the Advanced row it owns, where it has one, so
 * the row and the page it stands for are visibly one place.
 */
internal fun extensionAccent(extension: Extension): Color = when (extension) {
    Extension.STICKERS -> Color(0xFFFFA726)
    Extension.SCANNERS -> Color(0xFF29B6F6)
    Extension.HANDWRITING -> Color(0xFF9CCC65)
    Extension.NOTES -> Color(0xFFFFCA28)
    Extension.VOCABULARY -> Color(0xFF7CB342)
    Extension.CALCULATORS -> Color(0xFF26A69A)
    Extension.WEB_SEARCH -> Color(0xFF42A5F5)
    Extension.CAMERA_AND_GALLERY -> Color(0xFFEC407A)
    Extension.CALENDAR_AND_CLOCK -> Color(0xFF5C6BC0)
    Extension.GENERATORS -> Color(0xFF8D6E63)
    Extension.TYPING_TEST -> Color(0xFF66BB6A)
    Extension.READ_BACK -> Color(0xFFAB47BC)
    Extension.LEARN_FROM_TEXT -> Color(0xFF7CB342)
    Extension.FIND_REPLACE -> Color(0xFF78909C)
    Extension.MODES -> Color(0xFF7E57C2)
    Extension.MEDIA_CONTROLS -> Color(0xFFEF5350)
    Extension.APP_LAUNCHER -> Color(0xFF26C6DA)
    Extension.WEATHER_AND_LOCATION -> Color(0xFF29B6F6)
    Extension.INSTRUMENTS -> Color(0xFFFF7043)
    Extension.MUSIC_ID -> Color(0xFFAB47BC)
    Extension.SPEED_TEST -> Color(0xFF42A5F5)
    Extension.KDE_CONNECT -> Color(0xFF5C6BC0)
    Extension.SEND_FILES -> Color(0xFF26A69A)
    Extension.SMART_HOME -> Color(0xFFFFCA28)
}

/**
 * The settings screens a bundle's page lists, as route and heading: the
 * doors into the bundle that are not a tool's own page. Only screens that
 * open with no argument, since a page cannot list the user's own things.
 */
internal fun extensionScreens(extension: Extension): List<Pair<String, Int>> = when (extension) {
    Extension.STICKERS -> listOf(
        "sticker_packs" to R.string.home_screen_sticker_packs_title,
        "emoji/mixes" to R.string.emojimix_row_title,
    )
    Extension.NOTES -> listOf("notes" to R.string.notes_title)
    Extension.VOCABULARY -> listOf("vocab/packs" to R.string.vocab_packs_title, "vocab/lists" to R.string.vocab_lists_title)
    Extension.MODES -> listOf("modes" to R.string.home_modes_title)
    Extension.MEDIA_CONTROLS -> listOf("musicapps" to R.string.musicapps_title)
    Extension.APP_LAUNCHER -> listOf("launchercombos" to R.string.launchercombos_title)
    Extension.KDE_CONNECT -> listOf("kdeconnect/devices" to R.string.kdeconnect_devices_title)
    Extension.SEND_FILES -> listOf("send/instances" to R.string.send_instances_screen_title)
    Extension.SMART_HOME -> listOf("homeassistant/entities" to R.string.ha_entities_title)
    else -> emptyList()
}

/**
 * The switched-off bundle that owns [route], or null when the route is free
 * to open. [route] is a filled route as navigate() takes it ("tool/AI",
 * "mode_edit/mode_chat"); its pattern is looked up through the allowlist,
 * and a tool page is asked about its tool.
 */
internal fun extensionRedirect(route: String, enabled: Set<Extension>): Extension? {
    val pattern = SettingsRoutes.patternOf(route) ?: return null
    val owner = if (pattern == "tool/{toolName}") {
        ToolbarTool.entries.firstOrNull { it.name == route.substringAfter('/') }?.let(Extension::owning)
    } else {
        Extension.owningRoute(pattern)
    }
    return owner?.takeIf { it !in enabled }
}

/**
 * Where a navigation to [route] goes: [route] itself, or the Extensions
 * screen with the owning bundle's row armed to flash, when that bundle is
 * off. Called right before navigate(), as a search result arms its flash,
 * so the row reads it on its first composition.
 */
internal fun routeWithExtensions(route: String, enabled: Set<Extension>): String {
    val off = extensionRedirect(route, enabled) ?: return route
    SettingsHighlight.request(extensionTitle(off))
    return ExtensionsRoute
}

/**
 * The search index less every entry a switched-off bundle owns: its screens,
 * the rows on them, and its tools' pages. The index itself is built once per
 * language and cached, so the bundles are applied where it is read.
 */
@JvmName("searchEntriesWithExtensions")
internal fun List<SettingsSearchEntry>.withExtensions(enabled: Set<Extension>): List<SettingsSearchEntry> =
    if (enabled.size == Extension.entries.size) this
    else filter { entry ->
        extensionRedirect(entry.route, enabled) == null &&
            (entry.screenPattern?.let { Extension.owningRoute(it)?.let { ext -> ext in enabled } } ?: true) &&
            (entry.tool?.let { Extension.owning(it)?.let { ext -> ext in enabled } } ?: true)
    }

/**
 * The Tools screen's groups less the tools of every switched-off bundle, a
 * group left empty going with them. Every tool picker reads the groups
 * through this, so a hidden tool is hidden from all of them at once.
 */
@JvmName("toolGroupsWithExtensions")
internal fun List<Pair<Int, List<ToolbarTool>>>.withExtensions(
    enabled: Set<Extension>,
): List<Pair<Int, List<ToolbarTool>>> =
    if (enabled.size == Extension.entries.size) this
    else map { (title, tools) -> title to tools.filter { Extension.owning(it)?.let { ext -> ext in enabled } ?: true } }
        .filter { it.second.isNotEmpty() }

/**
 * Draws [content] unless [route] belongs to a bundle that is off, in which
 * case a line saying so and the way to the Extensions screen stand in for
 * it. The backstop behind [routeWithExtensions]: the graph navigates from a
 * hundred places, and a screen reached by one that was not redirected still
 * must not show a feature the user switched off.
 */
@Composable
internal fun ExtensionGate(route: String, content: @Composable () -> Unit) {
    val off = extensionRedirect(route, LocalExtensions.current)
    if (off == null) {
        content()
        return
    }
    val navigate = LocalSettingsNavigate.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            stringResource(R.string.extension_off_notice, stringResource(extensionTitle(off))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = {
                SettingsHighlight.request(extensionTitle(off))
                navigate(ExtensionsRoute)
            },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(stringResource(R.string.extension_off_action)) }
    }
}

/**
 * What a bundle's switch covers, as names: its tools this build has, then the
 * settings screens it owns. Its row lists them once there are two or more,
 * since a switch over one thing is already named by its title (#1067).
 */
internal fun extensionMembers(extension: Extension): List<Int> =
    extension.liveTools().map(::toolTitle) + extensionScreens(extension).map { it.second }

/**
 * Switches one bundle; the master switch a bundle carries moves inside
 * [SettingsRepository.setExtensionEnabled], and so do its tools when every one
 * of them had been switched off on the Tools page (#1067). The other way, the
 * last of its tools switched off there switches the bundle off with it.
 */
internal suspend fun setExtensionEnabled(repository: SettingsRepository, extension: Extension, enabled: Boolean) {
    repository.setExtensionEnabled(extension, enabled)
}

/** The Extensions screen: one switch per bundle, and a page behind each. */
@Composable
internal fun ExtensionsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpen: (Extension) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val enabled = settings.watch { it.extensionsResolved() }
    val total = Extension.entries.size
    SettingsGroup(
        stringResource(R.string.extensions_group_title),
        info = stringResource(R.string.extensions_intro),
    ) {
        for (extension in Extension.entries) {
            item {
                // A bundle of several things says which (#1067): a switch for
                // "App launcher" that also took split-screen pairs away, and
                // stayed on after the app launcher was switched off on the
                // Tools page, read as two settings disagreeing.
                val members = extensionMembers(extension).map { stringResource(it) }
                val description = stringResource(extensionDescription(extension))
                ToggleNavRow(
                    title = extensionTitle(extension),
                    subtitle = if (members.size < 2) {
                        description
                    } else {
                        description + "\n" + stringResource(R.string.extension_covers, members.joinToString(", "))
                    },
                    checked = extension in enabled,
                    route = extensionRoute(extension),
                    onChange = { on -> scope.launch { setExtensionEnabled(repository, extension, on) } },
                    onOpen = { onOpen(extension) },
                )
            }
        }
    }
    CaptionText(stringResource(R.string.extensions_count_summary, enabled.size, total))
}

/**
 * One bundle's page: its switch, then the tools and the settings screens it
 * carries. While the bundle is off the two lists are drawn greyed and inert,
 * since the pages they would open are the ones the switch took away.
 */
@Composable
internal fun ExtensionDetailSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    extension: Extension,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val on = settings.watch { it.extensionOn(extension) }
    SettingsGroup {
        item {
            ToggleSetting(
                title = R.string.extension_enabled_title,
                subtitle = stringResource(R.string.extension_enabled_subtitle),
                checked = on,
                icon = extensionIcon(extension),
                onChange = { value -> scope.launch { setExtensionEnabled(repository, extension, value) } },
            )
        }
    }
    val tools = extension.liveTools()
    if (tools.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.extension_tools_title),
            info = stringResource(R.string.extension_tools_off_subtitle).takeIf { !on },
        ) {
            for (tool in tools) {
                item {
                    val paint = settings.watch { toolAccentPaint(tool, it) }
                    WmRow(
                        title = stringResource(toolTitle(tool)),
                        subtitle = stringResource(toolDescription(tool)),
                        leading = {
                            SlotIcon(
                                IconSlots.forTool(tool),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                brush = paint?.brush,
                            )
                        },
                        enabled = on,
                        onClick = if (on) ({ onNavigate(toolRoute(tool)) }) else null,
                    )
                }
            }
        }
    }
    val screens = extensionScreens(extension)
    if (screens.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.extension_screens_title),
            info = stringResource(R.string.extension_screens_off_subtitle).takeIf { !on },
        ) {
            for ((route, title) in screens) {
                item {
                    WmRow(
                        title = stringResource(title),
                        icon = SettingsRouteIcons[route] ?: Icons.Outlined.Widgets,
                        accent = routeAccent(route),
                        enabled = on,
                        onClick = if (on) ({ onNavigate(route) }) else null,
                    )
                }
            }
        }
    }
}

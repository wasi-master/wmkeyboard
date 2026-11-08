package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dashboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.ime.ui.pluginActionIcon
import com.wasimaster.wmkeyboard.core.settings.SelectionMenuItem
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import com.wasimaster.wmkeyboard.core.settings.effectiveTimeZones
import com.wasimaster.wmkeyboard.core.settings.SelectionMacroPlacement
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.coroutines.launch

/** The route this screen is reached at, and its deep link. */
internal const val SelectionMacroRoute = "selection_macros"

/**
 * Settings › Advanced › Selection actions: the bar of one-tap actions the
 * keyboard offers for whatever is selected.
 *
 * Three questions, in the order somebody meets them: whether the bar exists at
 * all, where it draws, and which actions it may put on it. The detection switch
 * sits with the actions rather than at the top because it is what decides
 * whether the entity half of that list can ever appear. Last, the entries in
 * other apps' selection menus (#916), which offer those same actions outside
 * the keyboard.
 */
@Composable
internal fun SelectionMacroSettingsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the groups hold; each row reads its own value.
    val macrosOn = settings.watch { it.selectionMacros.enabled }
    val ownRow = settings.watch { it.selectionMacros.placement == SelectionMacroPlacement.OWN_ROW }

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.selection_macros_title,
                stringResource(R.string.selection_macros_subtitle),
                macrosOn,
                info = stringResource(R.string.selection_macros_info),
                default = SettingsDefaults.selectionMacros.enabled,
            ) { scope.launch { repository.setSelectionMacrosEnabled(it) } }
        }
        // Where the bar draws, and everything below, only mean something once
        // the bar exists: the service clears the offer outright while the
        // feature is off, so these would be settings for nothing.
        item(visible = macrosOn, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.selection_macros_placement_title,
                subtitle = stringResource(R.string.selection_macros_placement_subtitle),
                options = listOf(
                    SelectionMacroPlacement.OWN_ROW to stringResource(R.string.selection_macros_placement_row),
                    SelectionMacroPlacement.STRIP to stringResource(R.string.selection_macros_placement_strip),
                ),
                selected = settings.watch { it.selectionMacros.placement },
                default = SettingsDefaults.selectionMacros.placement,
                detail = { placement ->
                    ChoiceDetail(
                        stringResource(placementDescRes(placement)),
                        if (placement == SelectionMacroPlacement.STRIP) {
                            Icons.Outlined.ViewStream
                        } else {
                            Icons.Outlined.Dashboard
                        },
                    )
                },
            ) { scope.launch { repository.setSelectionMacroPlacement(it) } }
        }
        // Reads as a second question about where the bar appears rather than
        // as one about the actions: it decides whether there is a bar at all
        // on the longest selections, not which chips go on it.
        item(visible = macrosOn, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.selection_macros_every_title,
                stringResource(R.string.selection_macros_every_subtitle),
                settings.watch { it.selectionMacros.inEverySelection },
                info = stringResource(R.string.selection_macros_every_info),
                default = SettingsDefaults.selectionMacros.inEverySelection,
            ) { scope.launch { repository.setSelectionMacrosInEverySelection(it) } }
        }
        // The always-shown row (#763). Hidden on the strip placement, which it
        // does not apply to: there it would take the suggestions for good.
        item(visible = macrosOn && ownRow, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.selection_macros_caret_title,
                stringResource(R.string.selection_macros_caret_subtitle),
                settings.watch { it.selectionMacros.withoutSelection },
                info = stringResource(R.string.selection_macros_caret_info),
                default = SettingsDefaults.selectionMacros.withoutSelection,
            ) { scope.launch { repository.setSelectionMacrosWithoutSelection(it) } }
        }
    }

    // The whole group goes with the switch above it — SettingsGroup draws
    // nothing when its builder adds no rows, heading included.
    SettingsGroup(stringResource(R.string.selection_macros_actions_group)) {
        if (!macrosOn) return@SettingsGroup
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.selection_macros_detect_title,
                stringResource(R.string.selection_macros_detect_subtitle),
                settings.watch { it.selectionMacros.detectEntities },
                info = stringResource(R.string.selection_macros_detect_info),
                default = SettingsDefaults.selectionMacros.detectEntities,
            ) { scope.launch { repository.setSelectionMacroDetectEntities(it) } }
        }
        // Forty-odd actions are a screen, not a row of chips.
        item {
            NavRow(
                R.string.selection_macros_actions_title,
                subtitle = stringResource(R.string.selection_macros_actions_subtitle),
                value = stringResource(
                    R.string.selection_macros_actions_count,
                    settings.watch { s -> s.selectionMacros.macros.count { it in SelectionMacros.configurable } },
                    SelectionMacros.configurable.size,
                ),
                route = SelectionMacroActionsRoute,
            ) { onNavigate(SelectionMacroActionsRoute) }
        }
        item(level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.selection_macros_icons_title,
                subtitle = stringResource(R.string.selection_macros_icons_subtitle),
                value = settings.watch { s -> s.selectionMacros.iconOnly.count { it in s.selectionMacros.macros } }.toString(),
                route = SelectionMacroIconsRoute,
            ) { onNavigate(SelectionMacroIconsRoute) }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.selection_macros_ai_title,
                subtitle = stringResource(R.string.selection_macros_ai_subtitle),
                value = settings.watch { it.selectionMacros.aiDirectActions.size }.toString(),
                route = SelectionMacroAiRoute,
            ) { onNavigate(SelectionMacroAiRoute) }
        }
        item(level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.selection_macros_zones_title,
                subtitle = stringResource(R.string.selection_macros_zones_subtitle),
                value = settings.watch {
                    it.selectionMacros.effectiveTimeZones(java.util.TimeZone.getDefault().id).size
                }.toString(),
                route = SelectionMacroZonesRoute,
            ) { onNavigate(SelectionMacroZonesRoute) }
        }
    }

    PluginActionsGroup(repository, settings, visible = macrosOn, onNavigate = onNavigate)

    // The keyboard's actions in other apps' selection menus (#916). Not
    // behind the bar's switch: the menu is a different way to the same
    // actions, and works with the bar off. Ask AI and Look up are their own
    // tools' switches, repeated here so every entry is in one place.
    val context = LocalContext.current
    val translateOn = settings.watch { ToolbarTool.TRANSLATE in it.enabledTools }
    val aiOn = settings.watch { ToolbarTool.AI in it.enabledTools }
    SettingsGroup(
        stringResource(R.string.text_actions_settings_group),
        info = stringResource(R.string.text_actions_settings_info),
    ) {
        item {
            ToggleSetting(
                R.string.text_actions_settings_actions_title,
                stringResource(R.string.text_actions_settings_actions_subtitle),
                settings.watch { it.selectionMacros.systemMenu },
                info = stringResource(R.string.text_actions_settings_actions_info),
                default = SettingsDefaults.selectionMacros.systemMenu,
            ) { scope.launch { repository.setSelectionMenuEntry(it) } }
        }
        item(visible = translateOn, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.text_actions_settings_translate_title,
                stringResource(R.string.text_actions_settings_translate_subtitle),
                settings.watch { it.selectionMacros.systemMenuTranslate },
                default = SettingsDefaults.selectionMacros.systemMenuTranslate,
            ) { scope.launch { repository.setSelectionMenuTranslate(it) } }
        }
        for ((id, title) in listOf(
            SelectionMenuItem.TRANSLATE to R.string.text_actions_settings_show_translate,
            SelectionMenuItem.ASK_AI to R.string.text_actions_settings_show_ask_ai,
            SelectionMenuItem.LOOK_UP to R.string.text_actions_settings_show_look_up,
            SelectionMenuItem.SAVE_NOTE to R.string.text_actions_settings_show_note,
        )) {
            item(key = "menu-item:$id", level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    title,
                    stringResource(R.string.text_actions_settings_show_subtitle),
                    settings.watch { id !in it.selectionMacros.systemMenuOff },
                    default = true,
                ) { on ->
                    scope.launch { repository.setSelectionMenuItem(id, on) }
                }
            }
        }
        item(visible = aiOn, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_ai_selection_menu_title,
                stringResource(R.string.toolai_ai_selection_menu_subtitle),
                settings.watch { it.ai.selectionMenu },
                default = SettingsDefaults.ai.selectionMenu,
            ) { scope.launch { repository.setAiSelectionMenu(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.lookup_settings_selection_title,
                stringResource(R.string.lookup_settings_selection_subtitle),
                settings.watch { it.lookup.selectionMenu },
                default = SettingsDefaults.lookup.selectionMenu,
            ) { on ->
                LookupSelectionMenu.apply(context, on)
                scope.launch { repository.updateLookup { it.copy(selectionMenu = on) } }
            }
        }
    }
}

/**
 * Actions that installed plugins add to the bar, one switch each, after the
 * built-in ones. With none installed, one row says where they come from.
 * Hidden with the bar's own switch, like the actions group above it.
 */
@Composable
private fun PluginActionsGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
    visible: Boolean,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val actions = remember(revision) { store.selectionActions() }
    val pluginsOn = remember(revision) { store.subsystemEnabled() }
    SettingsGroup(
        stringResource(R.string.selection_macros_plugins_group),
        info = stringResource(R.string.selection_macros_plugins_info),
    ) {
        if (!visible) return@SettingsGroup
        if (actions.isEmpty()) {
            item(level = SettingLevel.STANDARD) {
                NavRow(
                    R.string.selection_macros_plugins_none_title,
                    subtitle = stringResource(
                        if (pluginsOn) {
                            R.string.selection_macros_plugins_none_subtitle
                        } else {
                            R.string.selection_macros_plugins_off_subtitle
                        },
                    ),
                    route = "plugins",
                ) { onNavigate("plugins") }
            }
            return@SettingsGroup
        }
        for (ref in actions) {
            item(key = ref.key, level = SettingLevel.STANDARD) {
                ToggleSetting(
                    title = ref.action.name,
                    subtitle = if (ref.action.summary.isBlank()) {
                        ref.plugin.name
                    } else {
                        "${ref.action.summary} · ${ref.plugin.name}"
                    },
                    checked = settings.watch { ref.key !in it.selectionMacros.pluginOff },
                    icon = pluginActionIcon(ref.action.icon),
                    default = true,
                ) { on -> scope.launch { repository.setSelectionPluginAction(ref.key, on) } }
            }
        }
    }
}

/** What each placement actually does, under its name in the choice sheet. */
@StringRes
private fun placementDescRes(placement: SelectionMacroPlacement): Int = when (placement) {
    SelectionMacroPlacement.OWN_ROW -> R.string.selection_macros_placement_row_desc
    SelectionMacroPlacement.STRIP -> R.string.selection_macros_placement_strip_desc
}

package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SelectionMacroPlacement
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
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
 * whether the entity half of that list can ever appear.
 */
@Composable
internal fun SelectionMacroSettingsScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val macros = settings.selectionMacros

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.selection_macros_title,
                stringResource(R.string.selection_macros_subtitle),
                macros.enabled,
                info = stringResource(R.string.selection_macros_info),
                default = SettingsDefaults.selectionMacros.enabled,
            ) { scope.launch { repository.setSelectionMacrosEnabled(it) } }
        }
        item {
            ChoiceSetting(
                R.string.selection_macros_placement_title,
                subtitle = stringResource(R.string.selection_macros_placement_subtitle),
                options = listOf(
                    SelectionMacroPlacement.OWN_ROW to stringResource(R.string.selection_macros_placement_row),
                    SelectionMacroPlacement.STRIP to stringResource(R.string.selection_macros_placement_strip),
                ),
                selected = macros.placement,
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
    }

    SettingsGroup(stringResource(R.string.selection_macros_actions_group)) {
        item {
            ToggleSetting(
                R.string.selection_macros_detect_title,
                stringResource(R.string.selection_macros_detect_subtitle),
                macros.detectEntities,
                info = stringResource(R.string.selection_macros_detect_info),
                default = SettingsDefaults.selectionMacros.detectEntities,
            ) { scope.launch { repository.setSelectionMacroDetectEntities(it) } }
        }
        item {
            MultiChoiceSetting(
                R.string.selection_macros_actions_title,
                subtitle = stringResource(R.string.selection_macros_actions_subtitle),
                info = stringResource(R.string.selection_macros_actions_info),
                options = SelectionMacros.configurable.map { it to stringResource(it.labelRes) },
                selected = macros.macros,
                default = SettingsDefaults.selectionMacros.macros,
            ) { scope.launch { repository.setSelectionMacros(it) } }
        }
    }
}

/** What each placement actually does, under its name in the choice sheet. */
@StringRes
private fun placementDescRes(placement: SelectionMacroPlacement): Int = when (placement) {
    SelectionMacroPlacement.OWN_ROW -> R.string.selection_macros_placement_row_desc
    SelectionMacroPlacement.STRIP -> R.string.selection_macros_placement_strip_desc
}

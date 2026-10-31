package com.wasimaster.wmkeyboard.app

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.settings.PerAppLanguageSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The Per-app language group: the memory switch, and a rule per app (#1040)
 * that opens the app on a chosen layout, or remembers its last one whatever
 * the switch says.
 */
@Composable
internal fun PerAppLanguageGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rules = settings.watch { it.perAppLanguage.ruleByPackage }
    val enabledIds = settings.watch { it.enabledLayoutIds }
    val customLayouts = settings.watch { it.customLayouts }
    val displayNames = settings.watch { it.layoutDisplayNames }
    val labels = remember(rules.keys) {
        val pm = context.packageManager
        rules.keys.associateWith { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }
    var picking by remember { mutableStateOf(false) }
    var choosingFor by remember { mutableStateOf<String?>(null) }
    val remember = stringResource(R.string.langemoji_lang_app_rule_remember)
    @Composable
    fun ruleName(rule: String): String =
        if (rule == PerAppLanguageSettings.REMEMBER) remember else layoutLabel(customLayouts, displayNames, rule)
    SettingsGroup(
        stringResource(R.string.langemoji_lang_per_app_title),
        info = stringResource(R.string.langemoji_lang_app_rule_info),
        level = SettingLevel.ADVANCED,
    ) {
        item {
            ToggleSetting(
                R.string.langemoji_lang_per_app_toggle_title,
                stringResource(R.string.langemoji_lang_per_app_toggle_subtitle),
                settings.watch { it.perAppLanguage.enabled },
                info = stringResource(R.string.langemoji_lang_per_app_toggle_info),
                default = SettingsDefaults.perAppLanguage.enabled,
            ) { scope.launch { repository.setRememberLayoutPerApp(it) } }
        }
        for ((pkg, rule) in rules.entries.sortedBy { labels[it.key]?.lowercase() }) {
            item(key = "app-rule:$pkg") {
                WmRow(
                    title = labels[pkg] ?: pkg,
                    subtitle = ruleName(rule),
                    trailing = {
                        IconButton(onClick = { scope.launch { repository.setAppLayoutRule(pkg, null) } }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.langemoji_lang_app_rule_remove_desc),
                            )
                        }
                    },
                    onClick = { choosingFor = pkg },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.langemoji_lang_app_rule_add),
                icon = Icons.Outlined.Add,
                onClick = { picking = true },
            )
        }
    }
    if (picking) {
        AppPickerDialog(
            exclude = rules.keys.toList(),
            onPick = { pkg ->
                picking = false
                choosingFor = pkg
            },
            onDismiss = { picking = false },
        )
    }
    choosingFor?.let { pkg ->
        val options = (enabledIds + PerAppLanguageSettings.REMEMBER).map { it to ruleName(it) }
        ChoiceSheet(
            title = stringResource(R.string.langemoji_lang_app_rule_add),
            subtitle = labels[pkg],
            options = options,
            selected = rules[pkg].orEmpty(),
            detail = null,
            onPick = { rule ->
                choosingFor = null
                scope.launch { repository.setAppLayoutRule(pkg, rule) }
            },
            onDismiss = { choosingFor = null },
        )
    }
}

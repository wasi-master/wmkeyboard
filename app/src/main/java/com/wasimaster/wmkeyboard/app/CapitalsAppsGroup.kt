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
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Apps whose own capitals are overruled (#1341). An app decides per field
 * which capitals it wants, and some ask for every word capitalized, so the
 * keyboard arms Shift after each space there and the user has to turn it off
 * again for every word.
 *
 * Each app's row says which way it goes, sentence capitals only or none, and
 * a tap turns it the other way. An app comes in as sentence capitals only,
 * the reason most people come here. Same shape as [IncognitoAppsGroup].
 */
@Composable
internal fun CapitalsAppsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val apps = settings.watch { it.autoText.capitalsByApp }
    // Names, not package ids, wherever the app is still installed.
    val labels = remember(apps.keys) {
        val pm = context.packageManager
        apps.keys.associateWith { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }
    var picking by remember { mutableStateOf(false) }
    SettingsGroup(
        stringResource(R.string.typing_capitals_apps_title),
        info = stringResource(R.string.typing_capitals_apps_info),
    ) {
        for ((pkg, sentencesOnly) in apps.entries.sortedBy { labels[it.key]?.lowercase() }) {
            item(key = "capitals:$pkg", level = SettingLevel.STANDARD) {
                WmRow(
                    title = labels[pkg] ?: pkg,
                    subtitle = stringResource(
                        if (sentencesOnly) R.string.typing_capitals_apps_sentences else R.string.typing_capitals_apps_none,
                    ),
                    trailing = {
                        IconButton(onClick = { scope.launch { repository.setCapitalsForApp(pkg, null) } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.typing_capitals_apps_remove_desc))
                        }
                    },
                    onClick = { scope.launch { repository.setCapitalsForApp(pkg, !sentencesOnly) } },
                )
            }
        }
        item(level = SettingLevel.STANDARD) {
            WmRow(
                title = stringResource(R.string.typing_capitals_apps_add),
                icon = Icons.Outlined.Add,
                onClick = { picking = true },
            )
        }
    }
    if (picking) {
        AppPickerDialog(
            exclude = apps.keys.toList(),
            onPick = { pkg ->
                picking = false
                scope.launch { repository.setCapitalsForApp(pkg, true) }
            },
            onDismiss = { picking = false },
        )
    }
}

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
 * Tools › Privacy shade › Turn on in these apps (#1242): the chats and the
 * banking app where the shade should already be up when the keyboard opens,
 * with nothing to press. The same rows as [IncognitoAppsGroup].
 */
@Composable
internal fun PrivacyShadeAppsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val apps = settings.watch { it.privacyShade.apps }
    val labels = remember(apps) {
        val pm = context.packageManager
        apps.associateWith { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }
    var picking by remember { mutableStateOf(false) }
    SettingsGroup(
        stringResource(R.string.tooldetail_privacy_shade_apps_title),
        info = stringResource(R.string.tooldetail_privacy_shade_apps_info),
    ) {
        for (pkg in apps.sortedBy { labels[it]?.lowercase() }) {
            item(key = "shade:$pkg") {
                WmRow(
                    title = labels[pkg] ?: pkg,
                    trailing = {
                        IconButton(onClick = { scope.launch { repository.setPrivacyShadeApp(pkg, false) } }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.tooldetail_privacy_shade_apps_remove_desc),
                            )
                        }
                    },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.tooldetail_privacy_shade_apps_add),
                icon = Icons.Outlined.Add,
                highlightKey = R.string.tooldetail_privacy_shade_apps_add,
                onClick = { picking = true },
            )
        }
    }
    if (picking) {
        AppPickerDialog(
            exclude = apps.toList(),
            onPick = { pkg ->
                picking = false
                scope.launch { repository.setPrivacyShadeApp(pkg, true) }
            },
            onDismiss = { picking = false },
        )
    }
}

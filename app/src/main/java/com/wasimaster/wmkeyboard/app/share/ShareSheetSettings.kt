package com.wasimaster.wmkeyboard.app.share

import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.app.ControlSetting
import com.wasimaster.wmkeyboard.app.ReorderableColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/** The route of Advanced › Share sheet (#970). */
internal const val ShareSheetRoute = "share_sheet"

/**
 * Advanced › Share sheet (#970): whether shares go through the keyboard's
 * own sheet or Android's, the recent row, and a way to clear what the sheet
 * remembers. Pinning happens on the sheet itself, by holding an app.
 */
@Composable
internal fun ShareSheetSettingsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val share = settings.watch { it.share }
    val defaults = SettingsDefaults.share
    val none = stringResource(R.string.share_sheet_list_empty)
    fun cleared() = Toast.makeText(context, R.string.share_sheet_cleared_toast, Toast.LENGTH_SHORT).show()

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.share_sheet_enabled_title,
                stringResource(R.string.share_sheet_enabled_subtitle),
                share.enabled,
                info = stringResource(R.string.share_sheet_enabled_info),
                default = defaults.enabled,
            ) { on -> scope.launch { repository.setShareSheetEnabled(on) } }
        }
        item(visible = share.enabled) {
            ToggleSetting(
                R.string.share_sheet_recents_title,
                stringResource(R.string.share_sheet_recents_subtitle),
                share.recentsEnabled,
                default = defaults.recentsEnabled,
            ) { on -> scope.launch { repository.setShareSheetRecentsEnabled(on) } }
        }
    }

    SettingsGroup(info = stringResource(R.string.share_sheet_pins_info)) {
        // The order they sit in on the sheet (#970).
        item(visible = share.enabled && share.pinned.size > 1) {
            val labels = remember(share.pinned) {
                val pm = context.packageManager
                share.pinned.associateWith { key ->
                    runCatching {
                        pm.getActivityInfo(ComponentName.unflattenFromString(key)!!, 0).loadLabel(pm).toString()
                    }.getOrDefault(key.substringBefore('/'))
                }
            }
            ControlSetting(
                R.string.share_sheet_pins_order_title,
                subtitle = stringResource(R.string.share_sheet_pins_order_subtitle),
            ) {
                ReorderableColumn(
                    items = share.pinned,
                    label = { labels[it].orEmpty() },
                    onReorder = { scope.launch { repository.setSharePins(it) } },
                    modifier = Modifier.padding(top = 8.dp),
                ) { key ->
                    Text(labels[key].orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        item(visible = share.enabled) {
            NavRow(
                R.string.share_sheet_pins_title,
                value = if (share.pinned.isEmpty()) none else share.pinned.size.toString(),
            ) {
                if (share.pinned.isNotEmpty()) {
                    scope.launch { repository.clearSharePins() }
                    cleared()
                }
            }
        }
        item(visible = share.enabled && share.recentsEnabled) {
            NavRow(
                R.string.share_sheet_recents_clear_title,
                value = if (share.recents.isEmpty()) none else share.recents.size.toString(),
            ) {
                if (share.recents.isNotEmpty()) {
                    scope.launch { repository.clearShareRecents() }
                    cleared()
                }
            }
        }
    }
}

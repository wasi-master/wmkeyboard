package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.displayDescription
import com.wasimaster.wmkeyboard.core.plugins.displayName
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState

/** The plugins a key, a flick or a finger swipe can open: those with a panel, while plugins are on. */
@Composable
internal fun rememberKeyPlugins(): List<InstalledPlugin> {
    val context = LocalContext.current
    return remember {
        val store = PluginStore.get(context)
        if (store.subsystemEnabled()) store.panelPlugins() else emptyList()
    }
}

/** The name a plugin key shows for [id], or null when that plugin is gone. */
internal fun List<InstalledPlugin>.keyName(id: String): String? = firstOrNull { it.id == id }?.displayName

/**
 * Which plugin a key opens (plugin API 5), the way [SecondaryLayoutPickerDialog]
 * picks a layout. The plugin's name is offered as the key's label.
 */
@Composable
internal fun PluginKeyPickerDialog(
    current: String?,
    onDismiss: () -> Unit,
    onPick: (InstalledPlugin) -> Unit,
) {
    val options = rememberKeyPlugins()
    val rail = rememberScrollRailState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_plugin_picker_title)) },
        text = {
            ScrollRail(state = rail, modifier = Modifier.heightIn(max = 380.dp)) {
                if (options.isEmpty()) {
                    Text(stringResource(R.string.layout_editor_plugin_picker_empty))
                }
                for (option in options) {
                    WmRow(
                        title = option.displayName,
                        subtitle = option.displayDescription.takeIf { it.isNotBlank() },
                        leading = {
                            RadioButton(selected = option.id == current, onClick = { onPick(option) })
                        },
                        onClick = { onPick(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
        },
    )
}

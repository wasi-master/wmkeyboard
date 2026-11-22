package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PluginActionSpec
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginProviderSpec
import com.wasimaster.wmkeyboard.core.plugins.PluginSettingSpec
import com.wasimaster.wmkeyboard.core.plugins.PythonTooling
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.wasimaster.wmkeyboard.common.R as CommonR

private val listJson = Json { ignoreUnknownKeys = true }

/** A list of manifest rows that survives the dialog being recreated, kept as its JSON. */
@Composable
internal fun <T> rememberSaveableList(initial: List<T>, serializer: KSerializer<T>): MutableState<List<T>> {
    val list = ListSerializer(serializer)
    return rememberSaveable(
        saver = Saver(
            save = { listJson.encodeToString(list, it.value) },
            restore = { mutableStateOf(runCatching { listJson.decodeFromString(list, it) }.getOrDefault(initial)) },
        ),
    ) { mutableStateOf(initial) }
}

/** A heading over a group of rows, with an Add button. */
@Composable
private fun FieldGroup(title: Int, canAdd: Boolean, onAdd: () -> Unit, content: @Composable () -> Unit) {
    HorizontalDivider(Modifier.padding(top = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd, enabled = canAdd) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.plugin_ide_details_add_action))
        }
    }
    content()
}

@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    IconButton(onClick = onRemove) {
        Icon(Icons.Outlined.Delete, contentDescription = stringResource(CommonR.string.common_delete))
    }
}

/** A choice of one of [options], shown as a button that opens them. */
@Composable
private fun Choice(value: String, options: List<Pair<String, String>>, onChoose: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == value }?.second ?: value) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, label) in options) {
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onChoose(key)
                })
            }
        }
    }
}

/** The lists a provider can answer, by their names in the keyboard. */
@Composable
private fun toolOptions(): List<Pair<String, String>> {
    val tools = remember { PythonTooling.providerTools }
    return if (tools.isNotEmpty()) {
        tools.map { it.tool to "${it.label} (${it.tool})" }
    } else {
        PluginProviderSpec.TOOLS.sorted().map { it to it }
    }
}

/** plugin.json's `providers`: which provider lists the plugin answers, each as one row. */
@Composable
internal fun ProviderFields(providers: List<PluginProviderSpec>, onChange: (List<PluginProviderSpec>) -> Unit) {
    val options = toolOptions()
    FieldGroup(R.string.plugin_ide_details_providers_title, providers.size < PluginManifestCodec.MAX_PROVIDERS, {
        val taken = providers.map { it.tool }.toSet()
        val tool = options.map { it.first }.firstOrNull { it !in taken } ?: options.first().first
        onChange(providers + PluginProviderSpec(tool = tool))
    }) {
        providers.forEachIndexed { index, p ->
            fun update(changed: PluginProviderSpec) = onChange(providers.toMutableList().also { it[index] = changed })
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Choice(p.tool, options, { update(p.copy(tool = it, images = it == "search" && p.images)) }, Modifier.weight(1f))
                    RemoveButton { onChange(providers.filterIndexed { i, _ -> i != index }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        p.id,
                        { update(p.copy(id = it)) },
                        label = { Text(stringResource(R.string.plugin_ide_details_provider_id_label)) },
                        placeholder = { Text(p.tool) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        p.name,
                        { update(p.copy(name = it)) },
                        label = { Text(stringResource(R.string.plugin_ide_details_provider_name_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (p.tool == "search") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = p.images, onCheckedChange = { update(p.copy(images = it)) })
                        Text(stringResource(R.string.plugin_ide_details_provider_images_label))
                    }
                }
            }
        }
    }
}

/** plugin.json's `actions`: the buttons the plugin adds to the bar over selected text. */
@Composable
internal fun ActionFields(actions: List<PluginActionSpec>, onChange: (List<PluginActionSpec>) -> Unit) {
    FieldGroup(R.string.plugin_ide_details_actions_title, actions.size < PluginManifestCodec.MAX_ACTIONS, {
        val taken = actions.map { it.id }.toSet()
        var n = actions.size + 1
        while ("action$n" in taken) n++
        onChange(actions + PluginActionSpec(id = "action$n", name = "Action $n"))
    }) {
        actions.forEachIndexed { index, a ->
            fun update(changed: PluginActionSpec) = onChange(actions.toMutableList().also { it[index] = changed })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    a.id,
                    { update(a.copy(id = it)) },
                    label = { Text(stringResource(R.string.plugin_ide_details_action_id_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    a.name,
                    { update(a.copy(name = it)) },
                    label = { Text(stringResource(R.string.plugin_ide_details_action_name_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                RemoveButton { onChange(actions.filterIndexed { i, _ -> i != index }) }
            }
        }
    }
}

/** plugin.json's `settings`: the rows of the plugin's settings page. */
@Composable
internal fun SettingFields(settings: List<PluginSettingSpec>, onChange: (List<PluginSettingSpec>) -> Unit) {
    val types = listOf(
        PluginSettingSpec.TYPE_TEXT to stringResource(R.string.plugin_ide_details_setting_text),
        PluginSettingSpec.TYPE_SWITCH to stringResource(R.string.plugin_ide_details_setting_switch),
        PluginSettingSpec.TYPE_NUMBER to stringResource(R.string.plugin_ide_details_setting_number),
        PluginSettingSpec.TYPE_CHOICE to stringResource(R.string.plugin_ide_details_setting_choice),
        PluginSettingSpec.TYPE_HOST to stringResource(R.string.plugin_ide_details_setting_host),
        PluginSettingSpec.TYPE_SECRET to stringResource(R.string.plugin_ide_details_setting_secret),
    )
    FieldGroup(R.string.plugin_ide_details_settings_title, settings.size < PluginManifestCodec.MAX_SETTINGS, {
        val taken = settings.map { it.key }.toSet()
        var n = settings.size + 1
        while ("setting$n" in taken) n++
        onChange(settings + PluginSettingSpec(key = "setting$n", type = PluginSettingSpec.TYPE_TEXT, label = "Setting $n"))
    }) {
        settings.forEachIndexed { index, s ->
            fun update(changed: PluginSettingSpec) = onChange(settings.toMutableList().also { it[index] = changed })
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        s.key,
                        { update(s.copy(key = it)) },
                        label = { Text(stringResource(R.string.plugin_ide_details_setting_key_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Choice(s.type, types, { update(s.copy(type = it)) })
                    RemoveButton { onChange(settings.filterIndexed { i, _ -> i != index }) }
                }
                OutlinedTextField(
                    s.label,
                    { update(s.copy(label = it)) },
                    label = { Text(stringResource(R.string.plugin_ide_details_setting_label_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (s.type == PluginSettingSpec.TYPE_CHOICE) {
                    OutlinedTextField(
                        s.options.joinToString(", "),
                        { update(s.copy(options = it.split(',').map(String::trim).filter(String::isNotEmpty))) },
                        label = { Text(stringResource(R.string.plugin_ide_details_setting_options_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

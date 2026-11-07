package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PluginBudget
import com.wasimaster.wmkeyboard.core.plugins.PluginDates
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginStorage
import com.wasimaster.wmkeyboard.core.plugins.PluginTargets
import com.wasimaster.wmkeyboard.core.plugins.PythonSession
import com.wasimaster.wmkeyboard.core.plugins.PythonTooling
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many draws the sparkline beside the budget meter remembers. */
internal const val MAX_BUDGET_HISTORY = 20

/** An event the author can send by hand, for a widget the last draw had. */
@Immutable
internal data class InjectableEvent(val event: PluginEvent, val widgetEnabled: Boolean = true)

/**
 * Every event the last draw's widgets could send, in the order they came:
 * presses, switch flips, a pick of each page, and for each date the next day
 * (or the first date, when it has none) and a clear. A button that is turned
 * off is listed too, because a handler has to cope with an event the user
 * cannot send. Text boxes are typed into separately.
 */
internal fun injectableEvents(
    targets: PluginTargets,
    now: Long = System.currentTimeMillis(),
): List<InjectableEvent> = buildList {
    for (button in targets.buttons) add(InjectableEvent(PluginEvent.Click(button.id), button.enabled))
    for (toggle in targets.toggles) add(InjectableEvent(PluginEvent.ToggleChanged(toggle.id, !toggle.checked)))
    for (tabs in targets.tabs) {
        for (index in tabs.pages.indices) add(InjectableEvent(PluginEvent.TabSelected(tabs.id, index)))
    }
    for (date in targets.dates) {
        val next = date.value?.let { PluginDates.addDays(it, 1) } ?: PluginDates.initial(now, date.time)
        add(InjectableEvent(PluginEvent.DateChanged(date.id, next)))
        add(InjectableEvent(PluginEvent.DateChanged(date.id, null)))
    }
}

/** Each of [values] as a share of [limit], from 0 to 1. */
internal fun budgetShares(values: List<Long>, limit: Long): List<Float> =
    values.map { if (limit <= 0) 0f else (it.toFloat() / limit).coerceIn(0f, 1f) }

/** Whether a pause in typing starts a run: the switch is on, the text parses, and the last run did not have this text. */
internal fun shouldRunAsTyped(enabled: Boolean, text: String, lastRun: String?, parses: Boolean): Boolean =
    enabled && parses && text != lastRun

/**
 * The event an author built by hand, or null without an id. A switch is on for
 * `true`, a page is a number counting from 0, and a date is seconds since the
 * epoch, with an empty or unreadable value clearing it.
 */
internal fun customEvent(type: String, id: String, value: String): PluginEvent? {
    val name = id.trim()
    if (name.isEmpty()) return null
    return when (type) {
        "click" -> PluginEvent.Click(name)
        "toggle" -> PluginEvent.ToggleChanged(name, value.trim().equals("true", ignoreCase = true))
        "input_changed" -> PluginEvent.InputChanged(name, value)
        "tab_selected" -> PluginEvent.TabSelected(name, value.trim().toIntOrNull() ?: 0)
        "date_changed" -> PluginEvent.DateChanged(name, value.trim().toLongOrNull())
        else -> null
    }
}

/** The instructions the last draw used against its budget, with a sparkline of the draws before it. */
@Composable
internal fun BudgetMeter(usage: PluginBudget.Usage, history: List<Long>) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val colors = rememberCodeColors()
    val share = budgetShares(listOf(usage.instructions), usage.instructionLimit).first()
    val bar = if (share > WARN_SHARE) colors.warning else MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.plugin_ide_budget_history_desc)
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.plugin_ide_usage_label, numbers.format(usage.instructions), numbers.format(usage.instructionLimit)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { share }, color = bar, modifier = Modifier.weight(1f).height(4.dp))
            val shares = budgetShares(history, usage.instructionLimit)
            Canvas(Modifier.padding(start = 8.dp).width(80.dp).height(20.dp).semantics { contentDescription = description }) {
                if (shares.size < 2) return@Canvas
                val step = size.width / (MAX_BUDGET_HISTORY - 1)
                val path = Path()
                shares.forEachIndexed { index, value ->
                    val x = index * step
                    val y = size.height * (1f - value)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, bar, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

private const val WARN_SHARE = 0.8f

/** Sends events by hand: one for each widget of the last draw, text for each box, and any event at all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EventsPane(targets: PluginTargets, earlier: List<PluginEvent>, onSend: (PluginEvent) -> Unit) {
    val events = remember(targets) { injectableEvents(targets) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (targets.isEmpty) {
            Text(
                stringResource(R.string.plugin_ide_events_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (item in events) {
                AssistChip(onClick = { onSend(item.event) }, label = { Text(eventLabel(item), fontSize = 12.sp) })
            }
        }
        for (input in targets.inputs) {
            var text by rememberSaveable(input.id) { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.plugin_ide_event_input_label, input.id)) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onSend(PluginEvent.InputChanged(input.id, text)) }) {
                    Text(stringResource(R.string.plugin_ide_event_send))
                }
            }
        }
        if (earlier.isNotEmpty()) {
            val context = LocalContext.current
            TextButton(onClick = { earlier.forEach(onSend) }) {
                Text(context.resources.getQuantityString(R.plurals.plugin_ide_event_replay, earlier.size, earlier.size))
            }
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.plugin_ide_event_custom_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        // The API model's list, which is what on_event is documented to receive.
        val eventTypes = remember { PythonTooling.api.eventTypes.ifEmpty { PythonSession.EVENT_TYPES } }
        var type by rememberSaveable { mutableStateOf(eventTypes.first()) }
        var id by rememberSaveable { mutableStateOf("") }
        var value by rememberSaveable { mutableStateOf("") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in eventTypes) {
                FilterChip(
                    selected = type == option,
                    onClick = { type = option },
                    label = { Text(option, fontFamily = CodeFontFamily, fontSize = 12.sp) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = id,
                onValueChange = { id = it },
                singleLine = true,
                label = { Text(stringResource(R.string.plugin_ide_event_id_label)) },
                modifier = Modifier.weight(1f),
            )
            if (type != "click") {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.plugin_ide_event_value_label)) },
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
            }
            TextButton(onClick = { customEvent(type, id, value)?.let(onSend) }, enabled = id.isNotBlank()) {
                Text(stringResource(R.string.plugin_ide_event_send))
            }
        }
    }
}

@Composable
private fun eventLabel(item: InjectableEvent): String = when (val event = item.event) {
    is PluginEvent.Click -> stringResource(
        if (item.widgetEnabled) R.string.plugin_ide_event_click else R.string.plugin_ide_event_click_disabled,
        event.id,
    )
    is PluginEvent.ToggleChanged -> stringResource(
        if (event.value) R.string.plugin_ide_event_toggle_on else R.string.plugin_ide_event_toggle_off,
        event.id,
    )
    is PluginEvent.TabSelected -> stringResource(R.string.plugin_ide_event_tab, event.id, event.index + 1)
    is PluginEvent.InputChanged -> event.id
    is PluginEvent.DateChanged -> event.value?.let { seconds ->
        val shown = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(seconds * 1000L))
        stringResource(R.string.plugin_ide_event_date, event.id, shown)
    } ?: stringResource(R.string.plugin_ide_event_date_clear, event.id)
}

/**
 * What the preview run of this draft keeps in storage, apart from what the
 * installed plugin keeps. Values are saved through the storage itself, so a
 * refused one shows the same reason the plugin would get. Changes wait while
 * the plugin is busy.
 */
@Composable
internal fun StoragePane(storage: PluginStorage?, declared: Boolean, busy: Boolean, installed: PluginStorage? = null) {
    val words = when {
        !declared -> R.string.plugin_ide_storage_undeclared
        storage == null -> R.string.plugin_ide_storage_unavailable
        else -> null
    }
    if (words != null || storage == null) {
        Text(
            stringResource(words ?: R.string.plugin_ide_storage_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    // The installed plugin's storage is only ever read: the keyboard can be writing it.
    var showInstalled by rememberSaveable { mutableStateOf(false) }
    val readOnly = showInstalled && installed != null
    val shown = if (readOnly) installed ?: storage else storage
    val entries by produceState(emptyList<Pair<String, String>>(), shown, revision, busy) {
        value = withContext(Dispatchers.IO) { shown.keys().sorted().map { it to shown.get(it).orEmpty() } }
    }
    var key by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var refusal by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        if (installed != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !showInstalled,
                        onClick = { showInstalled = false },
                        label = { Text(stringResource(R.string.plugin_ide_storage_scope_preview)) },
                    )
                    FilterChip(
                        selected = showInstalled,
                        onClick = { showInstalled = true },
                        label = { Text(stringResource(R.string.plugin_ide_storage_scope_installed)) },
                    )
                }
            }
        }
        if (readOnly) {
            item {
                Text(
                    stringResource(R.string.plugin_ide_storage_installed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = !busy && entries.isNotEmpty(),
                    onClick = {
                        val copied = entries
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                storage.clear()
                                for ((name, stored) in copied) storage.set(name, stored)
                            }
                            showInstalled = false
                            revision++
                        }
                    },
                ) { Text(stringResource(R.string.plugin_ide_storage_copy_action)) }
            }
        }
        if (busy) {
            item { Text(stringResource(R.string.plugin_ide_storage_busy), style = MaterialTheme.typography.bodySmall) }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.plugin_ide_storage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(entries, key = { it.first }) { (name, stored) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontFamily = CodeFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(stored, fontFamily = CodeFontFamily, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                IconButton(
                    enabled = !busy && !readOnly,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { storage.remove(name) }
                            revision++
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.plugin_ide_storage_remove_desc, name))
                }
            }
        }
        if (!readOnly) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.plugin_ide_storage_key_label)) },
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.plugin_ide_storage_value_label)) },
                    modifier = Modifier.weight(3f).padding(start = 6.dp),
                )
            }
            refusal?.let {
                Text(
                    stringResource(R.string.plugin_ide_storage_refused, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                enabled = !busy && key.isNotEmpty(),
                onClick = {
                    val writing = key to value
                    scope.launch {
                        val refused = withContext(Dispatchers.IO) { storage.set(writing.first, writing.second) }
                        refusal = refused
                        if (refused == null) {
                            key = ""
                            value = ""
                        }
                        revision++
                    }
                },
            ) { Text(stringResource(R.string.plugin_ide_storage_save)) }
        }
    }
}

package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.PaperlessAfterScan
import com.wasimaster.wmkeyboard.core.settings.PaperlessSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.paperless.PaperlessClient
import com.wasimaster.wmkeyboard.ime.paperless.PaperlessLists
import com.wasimaster.wmkeyboard.ime.paperless.PaperlessQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Tools → Document scanner → Paperless-ngx (#1198): the server and its token,
 * what a finished scan does, the tags, correspondent and document type a scan
 * starts with (from the server's own lists), and the scans still waiting to
 * go up.
 */
@Composable
internal fun PaperlessSettingsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val d = SettingsDefaults.scanner.paperless
    fun update(change: (PaperlessSettings) -> PaperlessSettings) {
        scope.launch { repository.updatePaperless(change) }
    }
    val p = settings.watch { it.scanner.paperless }
    var lists by remember(p.url, p.token) { mutableStateOf(PaperlessLists.last) }
    var status by remember(p.url, p.token) { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            val client = PaperlessClient(p.url, p.token)
            status = if (client.base == null) {
                context.getString(R.string.paperless_check_bad_url)
            } else {
                withContext(Dispatchers.IO) { runCatching { client.lists() } }.fold(
                    onSuccess = { got ->
                        lists = got
                        PaperlessLists.remember(got)
                        context.getString(R.string.paperless_check_ok, got.tags.size, got.correspondents.size, got.documentTypes.size)
                    },
                    onFailure = { e ->
                        val failure = e as? PaperlessClient.Failure
                        if (failure?.refused == true) {
                            context.getString(R.string.paperless_check_refused, failure.code)
                        } else {
                            context.getString(R.string.paperless_check_failed, e.message ?: e.javaClass.simpleName)
                        }
                    },
                )
            }
            checking = false
        }
    }
    // The lists, once, for the pickers below, when the page opens with a server set up.
    LaunchedEffect(p.url, p.token) { if (p.configured && lists == null) check() }

    SettingsGroup(stringResource(R.string.paperless_group), info = stringResource(R.string.paperless_info)) {
        item {
            TextFieldSetting(
                label = stringResource(R.string.paperless_url_title),
                value = p.url,
                hint = stringResource(R.string.paperless_url_hint),
                default = "",
            ) { v -> repository.updatePaperless { it.copy(url = v) } }
        }
        item {
            ApiKeyField(
                label = stringResource(R.string.paperless_token_title),
                value = p.token,
                builtInAvailable = false,
                emptyHint = stringResource(R.string.paperless_token_hint),
                wmPassTitle = "Paperless-ngx",
            ) { v -> repository.updatePaperless { it.copy(token = v) } }
        }
        if (p.configured) {
            item {
                ActionRow(
                    R.string.paperless_check_title,
                    subtitle = status ?: stringResource(R.string.paperless_check_subtitle),
                    action = stringResource(R.string.paperless_check_action),
                ) { check() }
            }
            item {
                ChoiceSetting(
                    R.string.paperless_after_title,
                    subtitle = stringResource(R.string.paperless_after_subtitle),
                    options = listOf(
                        PaperlessAfterScan.ASK to stringResource(R.string.paperless_after_ask),
                        PaperlessAfterScan.PAPERLESS to stringResource(R.string.paperless_after_paperless),
                        PaperlessAfterScan.BOTH to stringResource(R.string.paperless_after_both),
                        PaperlessAfterScan.FIELD to stringResource(R.string.paperless_after_field),
                    ),
                    selected = p.afterScan,
                    default = d.afterScan,
                ) { v -> update { it.copy(afterScan = v) } }
            }
            item {
                val known = lists
                var picking by remember { mutableStateOf(false) }
                NavRow(
                    R.string.paperless_tags_title,
                    subtitle = stringResource(R.string.paperless_tags_subtitle),
                    value = when {
                        p.tags.isEmpty() -> stringResource(R.string.paperless_none)
                        known == null -> context.resources.getQuantityString(R.plurals.paperless_tags_count, p.tags.size, p.tags.size)
                        else -> known.tags.filter { it.id in p.tags }.joinToString(", ") { it.name }
                            .ifEmpty { context.resources.getQuantityString(R.plurals.paperless_tags_count, p.tags.size, p.tags.size) }
                    },
                ) { if (known != null) picking = true else check() }
                if (picking && known != null) {
                    TagsDialog(known.tags, p.tags, onChange = { next -> update { it.copy(tags = next) } }) { picking = false }
                }
            }
            lists?.let { known ->
                if (known.correspondents.isNotEmpty()) {
                    item {
                        ChoiceSetting(
                            R.string.paperless_correspondent_title,
                            subtitle = stringResource(R.string.paperless_correspondent_subtitle),
                            options = listOf(0 to stringResource(R.string.paperless_none)) + known.correspondents.map { it.id to it.name },
                            selected = p.correspondent.takeIf { id -> id == 0 || known.correspondents.any { it.id == id } } ?: 0,
                            default = 0,
                        ) { v -> update { it.copy(correspondent = v) } }
                    }
                }
                if (known.documentTypes.isNotEmpty()) {
                    item {
                        ChoiceSetting(
                            R.string.paperless_type_title,
                            subtitle = stringResource(R.string.paperless_type_subtitle),
                            options = listOf(0 to stringResource(R.string.paperless_none)) + known.documentTypes.map { it.id to it.name },
                            selected = p.documentType.takeIf { id -> id == 0 || known.documentTypes.any { it.id == id } } ?: 0,
                            default = 0,
                        ) { v -> update { it.copy(documentType = v) } }
                    }
                }
            }
            item {
                ToggleSetting(
                    R.string.paperless_one_pdf_title,
                    stringResource(R.string.paperless_one_pdf_subtitle),
                    p.onePdf,
                    default = d.onePdf,
                ) { v -> update { it.copy(onePdf = v) } }
            }
            item {
                ToggleSetting(
                    R.string.paperless_wifi_only_title,
                    stringResource(R.string.paperless_wifi_only_subtitle),
                    p.wifiOnly,
                    default = d.wifiOnly,
                ) { v -> update { it.copy(wifiOnly = v) } }
            }
        }
        item { PaperlessQueueRow() }
    }
}

/** The scans still on the phone: how many, the reason any of them stopped, and Retry or Delete. */
@Composable
private fun PaperlessQueueRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PaperlessQueue.refresh(context) } }
    val waiting by PaperlessQueue.entries.collectAsState()
    if (waiting.isEmpty()) return
    val failed = waiting.filter { it.error != null }
    var clearing by remember { mutableStateOf(false) }
    ActionRow(
        R.string.paperless_queue_title,
        subtitle = buildString {
            append(context.resources.getQuantityString(R.plurals.paperless_queue_waiting, waiting.size, waiting.size))
            failed.firstOrNull()?.error?.let { append('\n').append(context.getString(R.string.paperless_queue_error, it)) }
        },
        action = stringResource(R.string.paperless_queue_retry),
    ) {
        scope.launch { withContext(Dispatchers.IO) { PaperlessQueue.retryAll(context) } }
    }
    ActionRow(
        R.string.paperless_queue_clear_title,
        subtitle = stringResource(R.string.paperless_queue_clear_subtitle),
        action = stringResource(CommonR.string.common_delete),
        confirm = stringResource(R.string.paperless_queue_clear_confirm),
    ) {
        if (clearing) return@ActionRow
        clearing = true
        scope.launch {
            withContext(Dispatchers.IO) { waiting.forEach { PaperlessQueue.remove(context, it.id) } }
            clearing = false
        }
    }
}

/** The server's tags, each with a tick for whether a scan starts with it. */
@Composable
private fun TagsDialog(tags: List<PaperlessClient.Named>, picked: Set<Int>, onChange: (Set<Int>) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paperless_tags_title)) },
        text = {
            if (tags.isEmpty()) {
                Text(stringResource(R.string.paperless_tags_empty))
            } else {
                Column {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(tags, key = { it.id }) { tag ->
                            val on = tag.id in picked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onChange(if (on) picked - tag.id else picked + tag.id) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Text(tag.name, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) } },
    )
}

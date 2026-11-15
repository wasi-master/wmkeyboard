package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.emoji.mix.EmojiMixer
import com.wasimaster.wmkeyboard.core.settings.EmojiMixCellSizeRange
import com.wasimaster.wmkeyboard.core.settings.EmojiMixOrder
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSource
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSourceKind
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.StickerSuggestStyle
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.emojimix.EmojiMixStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Emoji › Emoji mixes: the sources the user added and where the keyboard
 * offers their mixes. No source ships and the screen suggests none; it only
 * takes what the user brings.
 */
@Composable
internal fun EmojiMixSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { EmojiMixStore(context) }
    val sources = settings.watch { it.emoji.mix.sources }
    val decision = rememberDownloadDecision(settings)
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    // A network step waiting on the metered question, and the blocked notice.
    var pendingNetwork by remember { mutableStateOf<(() -> Unit)?>(null) }
    var blocked by remember { mutableStateOf(false) }

    /** Runs [work] now, or after the metered question, or not at all when data saving forbids it. */
    fun online(work: () -> Unit) {
        when (decision()) {
            MeteredDecision.ALLOWED -> work()
            MeteredDecision.ASK -> pendingNetwork = work
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    fun refresh(source: EmojiMixSource) {
        busy = busy + source.id
        scope.launch {
            refreshSource(store, repository, source)
            busy = busy - source.id
        }
    }

    fun add(address: String) {
        val kind = EmojiMixer.detect(address)
        val source = EmojiMixSource(EmojiMixStore.newId(), kind, address.trim())
        scope.launch {
            repository.setEmojiMixSources(settings.value.emoji.mix.sources + source)
            if (sources.isEmpty()) repository.setToolEnabled(ToolbarTool.EMOJI_MIX, true)
            if (kind == EmojiMixSourceKind.IMAGE_ADDRESS) {
                refresh(source)
            } else {
                online { refresh(source) }
            }
        }
    }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val id = EmojiMixStore.newId()
        scope.launch { importSource(context, store, repository, settings, id, uri, folder = false, firstSource = sources.isEmpty()) }
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val id = EmojiMixStore.newId()
        scope.launch { importSource(context, store, repository, settings, id, uri, folder = true, firstSource = sources.isEmpty()) }
    }

    SettingsGroup(stringResource(R.string.emojimix_sources_title)) {
        if (sources.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.emojimix_sources_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        for (source in sources) {
            item {
                SourceRow(
                    source = source,
                    busy = source.id in busy,
                    onToggle = { on ->
                        scope.launch {
                            repository.updateEmojiMixSource(source.id) { it.copy(enabled = on) }
                            repository.bumpEmojiMixIndexVersion()
                        }
                    },
                    onOpen = { editing = source.id },
                )
            }
        }
        item {
            NavRow(
                title = R.string.emojimix_add_title,
                subtitle = stringResource(R.string.emojimix_add_subtitle),
            ) { adding = true }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.emojimix_import_file_action))
                }
                OutlinedButton(onClick = { importFolder.launch(null) }) {
                    Text(stringResource(R.string.emojimix_import_folder_action))
                }
            }
        }
    }

    val typing = settings.watch { it.emoji.mix.typingSuggest }
    SettingsGroup(stringResource(R.string.emojimix_where_title)) {
        item {
            ToggleSetting(
                R.string.emojimix_panel_strip_title,
                stringResource(R.string.emojimix_panel_strip_subtitle),
                settings.watch { it.emoji.mix.panelStrip },
                default = SettingsDefaults.emoji.mix.panelStrip,
                info = stringResource(R.string.emojimix_panel_strip_info),
            ) { scope.launch { repository.setEmojiMixPanelStrip(it) } }
        }
        item {
            ToggleSetting(
                R.string.emojimix_typing_title,
                stringResource(R.string.emojimix_typing_subtitle),
                typing,
                default = SettingsDefaults.emoji.mix.typingSuggest,
                info = stringResource(R.string.emojimix_typing_info),
            ) { scope.launch { repository.setEmojiMixTypingSuggest(it) } }
        }
        item(visible = typing, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.emojimix_style_title,
                options = listOf(
                    StickerSuggestStyle.STRIP to stringResource(R.string.emojimix_style_strip_label),
                    StickerSuggestStyle.CHIP to stringResource(R.string.emojimix_style_chip_label),
                    StickerSuggestStyle.TRAY to stringResource(R.string.emojimix_style_tray_label),
                ),
                selected = settings.watch { it.emoji.mix.suggestStyle },
                default = SettingsDefaults.emoji.mix.suggestStyle,
                info = stringResource(R.string.emojimix_style_info),
            ) { scope.launch { repository.setEmojiMixSuggestStyle(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.emojimix_replace_title,
                stringResource(R.string.emojimix_replace_subtitle),
                settings.watch { it.emoji.mix.replaceTypedEmoji },
                default = SettingsDefaults.emoji.mix.replaceTypedEmoji,
                info = stringResource(R.string.emojimix_replace_info),
            ) { scope.launch { repository.setEmojiMixReplaceTyped(it) } }
        }
        item {
            ToggleSetting(
                R.string.emojimix_panel_title,
                stringResource(R.string.emojimix_panel_subtitle),
                settings.watch { ToolbarTool.EMOJI_MIX in it.enabledTools },
                info = stringResource(R.string.emojimix_panel_info),
            ) { scope.launch { repository.setToolEnabled(ToolbarTool.EMOJI_MIX, it) } }
        }
    }

    SettingsGroup(stringResource(R.string.emojimix_behaviour_title)) {
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.emojimix_order_title,
                options = listOf(
                    EmojiMixOrder.YOURS_FIRST to stringResource(R.string.emojimix_order_yours_label),
                    EmojiMixOrder.SOURCE to stringResource(R.string.emojimix_order_source_label),
                ),
                selected = settings.watch { it.emoji.mix.order },
                default = SettingsDefaults.emoji.mix.order,
                info = stringResource(R.string.emojimix_order_info),
            ) { scope.launch { repository.setEmojiMixOrder(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.emojimix_send_title,
                subtitle = stringResource(R.string.emojimix_send_subtitle),
                options = listOf(
                    MediaSendMode.STICKER to stringResource(R.string.emojimix_send_sticker_label),
                    MediaSendMode.IMAGE to stringResource(R.string.emojimix_send_image_label),
                ),
                selected = settings.watch { it.emoji.mix.sendMode },
                default = SettingsDefaults.emoji.mix.sendMode,
                info = stringResource(R.string.emojimix_send_info),
            ) { scope.launch { repository.setEmojiMixSendMode(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            val dpFormat = stringResource(R.string.typing_value_dp)
            SliderSetting(
                R.string.emojimix_cell_size_title,
                value = settings.watch { it.emoji.mix.cellSize }.toFloat(),
                range = EmojiMixCellSizeRange.first.toFloat()..EmojiMixCellSizeRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.emoji.mix.cellSize.toFloat(),
                info = stringResource(R.string.emojimix_cell_size_info),
            ) { scope.launch { repository.setEmojiMixCellSize(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.emojimix_auto_refresh_title,
                stringResource(R.string.emojimix_auto_refresh_subtitle),
                settings.watch { it.emoji.mix.autoRefresh },
                default = SettingsDefaults.emoji.mix.autoRefresh,
                info = stringResource(R.string.emojimix_auto_refresh_info),
            ) { scope.launch { repository.setEmojiMixAutoRefresh(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            var size by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(Unit) { size = withContext(Dispatchers.IO) { store.imagesSize() } }
            NavRow(
                title = R.string.emojimix_clear_cache_title,
                subtitle = size?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) { store.clearImages() }
                    size = 0L
                }
            }
        }
    }

    if (adding) {
        AddSourceDialog(
            onDismiss = { adding = false },
            onAdd = { address ->
                adding = false
                add(address)
            },
        )
    }
    editing?.let { id ->
        val source = sources.firstOrNull { it.id == id }
        if (source == null) {
            editing = null
        } else {
            SourceDialog(
                source = source,
                canMoveUp = sources.indexOf(source) > 0,
                onDismiss = { editing = null },
                onRefresh = {
                    editing = null
                    if (source.kind == EmojiMixSourceKind.INDEX || source.kind == EmojiMixSourceKind.MIXER) {
                        online { refresh(source) }
                    } else {
                        refresh(source)
                    }
                },
                onMoveUp = {
                    editing = null
                    scope.launch {
                        val list = settings.value.emoji.mix.sources.toMutableList()
                        val at = list.indexOfFirst { it.id == id }
                        if (at > 0) {
                            list.add(at - 1, list.removeAt(at))
                            repository.setEmojiMixSources(list)
                            repository.bumpEmojiMixIndexVersion()
                        }
                    }
                },
                onRemove = {
                    editing = null
                    scope.launch {
                        repository.setEmojiMixSources(settings.value.emoji.mix.sources.filter { it.id != id })
                        withContext(Dispatchers.IO) { store.remove(id) }
                        repository.bumpEmojiMixIndexVersion()
                    }
                },
            )
        }
    }
    pendingNetwork?.let { work ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                pendingNetwork = null
                work()
            },
            onDismiss = { pendingNetwork = null },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/** Fetches what [source] needs and writes the outcome back onto its row. */
private suspend fun refreshSource(store: EmojiMixStore, repository: SettingsRepository, source: EmojiMixSource) {
    val outcome = withContext(Dispatchers.IO) { store.refresh(source) }
    repository.updateEmojiMixSource(source.id) { it.withOutcome(outcome) }
    repository.bumpEmojiMixIndexVersion()
}

@Suppress("LongParameterList")
private suspend fun importSource(
    context: Context,
    store: EmojiMixStore,
    repository: SettingsRepository,
    settings: LiveSettings,
    id: String,
    uri: Uri,
    folder: Boolean,
    firstSource: Boolean,
) {
    val name = if (folder) uri.lastPathSegment?.substringAfterLast(':').orEmpty() else displayName(context, uri)
    val outcome = withContext(Dispatchers.IO) {
        if (folder) store.importFolder(uri, id) else store.importFile(uri, id)
    }
    if (outcome !is EmojiMixStore.Outcome.Done) {
        withContext(Dispatchers.IO) { store.remove(id) }
        val message = when (outcome) {
            is EmojiMixStore.Outcome.Failed -> context.getString(R.string.emojimix_import_failed, outcome.message)
            else -> context.getString(R.string.emojimix_nothing_found)
        }
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val kind = if (outcome.pack) EmojiMixSourceKind.PACK else EmojiMixSourceKind.INDEX
    val source = EmojiMixSource(id, kind, name.ifBlank { id }).withOutcome(outcome)
    repository.setEmojiMixSources(settings.value.emoji.mix.sources + source)
    if (firstSource) repository.setToolEnabled(ToolbarTool.EMOJI_MIX, true)
    repository.bumpEmojiMixIndexVersion()
}

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()

private fun EmojiMixSource.withOutcome(outcome: EmojiMixStore.Outcome): EmojiMixSource = when (outcome) {
    is EmojiMixStore.Outcome.Done -> copy(
        pairCount = outcome.pairs,
        refreshedAt = System.currentTimeMillis(),
        placeholderMd5 = outcome.placeholderMd5.ifEmpty { placeholderMd5 },
        error = "",
    )
    EmojiMixStore.Outcome.NothingFound -> copy(error = NOTHING_FOUND)
    is EmojiMixStore.Outcome.Failed -> copy(error = outcome.message.ifBlank { NOTHING_FOUND })
}

/** Stored in place of a message when the address answered with nothing usable; the row words it. */
private const val NOTHING_FOUND = "nothing_found"

@Composable
private fun SourceRow(
    source: EmojiMixSource,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    WmRow(
        title = sourceTitle(source),
        subtitle = sourceSummary(source),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                }
                Switch(checked = source.enabled, onCheckedChange = onToggle)
            }
        },
        onClick = onOpen,
    )
}

/** The host for an address, the file or folder name for an import. */
private fun sourceTitle(source: EmojiMixSource): String =
    runCatching { Uri.parse(source.address).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: source.address

@Composable
private fun sourceSummary(source: EmojiMixSource): String {
    val kind = stringResource(
        when (source.kind) {
            EmojiMixSourceKind.INDEX -> R.string.emojimix_kind_index
            EmojiMixSourceKind.IMAGE_ADDRESS -> R.string.emojimix_kind_image_address
            EmojiMixSourceKind.MIXER -> R.string.emojimix_kind_mixer
            EmojiMixSourceKind.PACK -> R.string.emojimix_kind_pack
        },
    )
    val detail = when {
        source.error == NOTHING_FOUND -> stringResource(R.string.emojimix_nothing_found)
        source.error.isNotEmpty() -> stringResource(R.string.emojimix_failed, source.error)
        source.pairCount > 0 -> pluralStringResource(R.plurals.emojimix_pairs, source.pairCount, source.pairCount)
        source.refreshedAt == 0L && source.kind == EmojiMixSourceKind.INDEX -> stringResource(R.string.emojimix_not_fetched)
        else -> null
    }
    return if (detail == null) kind else stringResource(R.string.emojimix_summary, kind, detail)
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var address by remember { mutableStateOf("") }
    val valid = address.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emojimix_add_title)) },
        text = {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                label = { Text(stringResource(R.string.emojimix_address_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(address) }, enabled = valid) {
                Text(stringResource(R.string.emojimix_add_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun SourceDialog(
    source: EmojiMixSource,
    canMoveUp: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onMoveUp: () -> Unit,
    onRemove: () -> Unit,
) {
    val refreshable = source.kind != EmojiMixSourceKind.PACK &&
        (source.kind != EmojiMixSourceKind.INDEX || source.address.startsWith("http", ignoreCase = true))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sourceTitle(source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sourceSummary(source), style = MaterialTheme.typography.bodyMedium)
                Text(
                    source.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (refreshable) {
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.emojimix_refresh_action)) }
                }
                if (canMoveUp) {
                    TextButton(onClick = onMoveUp) { Text(stringResource(R.string.emojimix_move_up_action)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.emojimix_remove_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
        },
    )
}

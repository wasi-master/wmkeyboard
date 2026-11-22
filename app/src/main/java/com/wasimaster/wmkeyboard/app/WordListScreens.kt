package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
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
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import android.provider.OpenableColumns
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictStore
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPack
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPacks
import com.wasimaster.wmkeyboard.core.emoji.EmojiSearchExamples
import com.wasimaster.wmkeyboard.core.input.composer.ConversionWordLists
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import kotlinx.coroutines.launch

// ---- custom dictionaries ----

/** Human name for a language id, used as the word-list group header. */
private fun languageLabel(langId: String): String =
    LanguageRegistry.byId(langId).englishName
/**
 * One imported list: the file, how many words it parsed to, and the word pairs
 * and shortcuts an imported dictionary left beside it.
 */
private data class WordListEntry(val file: java.io.File, val words: Int, val pairs: Int, val shortcuts: Int)

/** Dictionaries found in a pick that holds more than words, waiting for the user to choose. */
private class PendingDictionaryImport(val langId: String, val candidates: List<CustomDictionaries.ImportCandidate>)

private operator fun CustomDictionaries.Written.plus(other: CustomDictionaries.Written) =
    CustomDictionaries.Written(words + other.words, pairs + other.pairs, shortcuts + other.shortcuts)
@Composable
internal fun CustomDictionarySettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lists by remember {
        mutableStateOf<Map<String, List<WordListEntry>>>(emptyMap())
    }
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var urlDialogFor by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<PendingDictionaryImport?>(null) }

    // Counting words means reading every list, so it never runs on the main
    // thread — the screen draws empty for a moment and fills in.
    suspend fun refresh() {
        lists = withContext(Dispatchers.IO) {
            // The enabled languages plus any language that still has lists on
            // disk. Walking only the enabled ones meant that switching a
            // language off took its lists out of the one screen that manages
            // them, while the files stayed on disk and in Storage.
            val ids = LinkedHashSet<String>()
            settings.value.enabledLanguages.mapTo(ids) { it.id }
            ids.addAll(CustomDictionaries.languagesWithLists(context.filesDir))
            ids.associateWith { langId ->
                // allLists, not lists: a switched-off list still has to be
                // shown, or there is no way to switch it back on.
                // A converting language counts the lines that carry a reading,
                // since only those reach its candidates (#633).
                val reading = ConversionWordLists.readingFor(langId)
                CustomDictionaries.allLists(context.filesDir, langId).map { file ->
                    WordListEntry(
                        file,
                        if (reading == null) {
                            CustomDictionaries.wordsOf(file).size
                        } else {
                            runCatching { file.bufferedReader().useLines { ConversionWordLists.count(it, reading) } }
                                .getOrDefault(0)
                        },
                        CustomDictionaries.pairCount(file),
                        CustomDictionaries.shortcutCount(file),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    suspend fun finishImport(langId: String, written: CustomDictionaries.Written) {
        busy = false
        val res = context.resources
        message = when {
            written.total == 0 -> context.getString(R.string.customdict_import_empty_error)
            written.pairs == 0 && written.shortcuts == 0 -> res.getQuantityString(
                R.plurals.customdict_import_added_words,
                written.words,
                written.words,
                languageLabel(langId),
            )
            else -> context.getString(
                R.string.customdict_import_added_parts,
                listOfNotNull(
                    written.words.takeIf { it > 0 }
                        ?.let { res.getQuantityString(R.plurals.customdict_word_count, it, it) },
                    written.pairs.takeIf { it > 0 }
                        ?.let { res.getQuantityString(R.plurals.customdict_pair_count, it, it) },
                    written.shortcuts.takeIf { it > 0 }
                        ?.let { res.getQuantityString(R.plurals.customdict_shortcut_count, it, it) },
                ).joinToString(", "),
                languageLabel(langId),
            )
        }
        if (written.total > 0) {
            refresh()
            repository.bumpCustomDictVersion()
        }
    }

    fun refusalMessage(reason: CustomDictionaries.Refusal): String = when (reason) {
        CustomDictionaries.Refusal.NothingReadable -> context.getString(R.string.customdict_import_empty_error)
        CustomDictionaries.Refusal.TooLarge -> context.getString(R.string.customdict_import_too_large_error)
        CustomDictionaries.Refusal.MissingBody -> context.getString(R.string.customdict_import_missing_body_error)
        CustomDictionaries.Refusal.MissingHeader -> context.getString(R.string.customdict_import_missing_header_error)
        CustomDictionaries.Refusal.MissingAff -> context.getString(R.string.customdict_import_missing_aff_error)
        CustomDictionaries.Refusal.MissingDic -> context.getString(R.string.customdict_import_missing_dic_error)
        is CustomDictionaries.Refusal.UnsupportedVersion ->
            context.getString(R.string.customdict_import_unsupported_error, reason.version)
    }

    // What was picked is read before anything is written. Words alone import
    // straight away, as they always did; a dictionary that brings word pairs
    // or shortcuts too asks which of them to keep.
    suspend fun importPicked(langId: String, files: List<CustomDictionaries.ImportFile>) {
        val inspection = withContext(Dispatchers.IO) { CustomDictionaries.inspect(files) }
        when (inspection) {
            is CustomDictionaries.Inspection.Refused -> {
                busy = false
                message = refusalMessage(inspection.reason)
            }
            is CustomDictionaries.Inspection.Found -> {
                val found = inspection.dictionaries
                if (found.none { it.hasExtras }) {
                    val written = withContext(Dispatchers.IO) {
                        found.map {
                            CustomDictionaries.write(
                                context.filesDir,
                                langId,
                                it,
                                CustomDictionaries.ImportParts.ALL,
                            )
                        }.reduce { a, b -> a + b }
                    }
                    finishImport(langId, written)
                } else {
                    busy = false
                    choosing = PendingDictionaryImport(langId, found)
                }
            }
        }
    }

    fun importFromUrl(langId: String, url: String) {
        busy = true
        scope.launch {
            val uri = android.net.Uri.parse(url.trim())
            if (uri.scheme != "http" && uri.scheme != "https") {
                busy = false
                message = context.getString(R.string.customdict_url_scheme_error)
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                        ?: "wordlist"
                    val temp = java.io.File.createTempFile("dict_url_", ".tmp", context.cacheDir)
                    try {
                        ToolHttp.download(
                            url.trim(), temp, maxBytes = CustomDictionaries.MAX_BYTES,
                            source = NetSource.DOWNLOAD_WORDLIST, route = NetLog.pathOf(url.trim()),
                        )
                        CustomDictionaries.ImportFile(name, temp.readBytes())
                    } finally {
                        temp.delete()
                    }
                }.getOrNull()
            }
            if (file == null) {
                busy = false
                message = context.getString(R.string.customdict_url_download_error)
                return@launch
            }
            importPicked(langId, listOf(file))
        }
    }

    // Several files at once: a version 4 dictionary is a .header and a .body,
    // and they have to be picked together.
    val importList = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val language = pending
        pending = null
        if (uris.isEmpty() || language == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            // Null for a file past the size cap; an empty list for one that
            // could not be read at all.
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    uris.map { uri ->
                        val name = context.contentResolver
                            .query(uri, null, null, null, null)?.use { cursor ->
                                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                            } ?: "wordlist"
                        val size = context.contentResolver
                            .query(uri, null, null, null, null)?.use { cursor ->
                                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
                            } ?: -1L
                        if (size > CustomDictionaries.MAX_BYTES) return@runCatching null
                        val bytes = context.contentResolver.openInputStream(uri)
                            ?.use { CustomDictionaries.readCapped(it) }
                            ?: return@runCatching null
                        CustomDictionaries.ImportFile(name, bytes)
                    }
                }.getOrDefault(emptyList())
            }
            when {
                files == null -> {
                    busy = false
                    message = context.getString(R.string.customdict_import_too_large_error)
                }
                files.isEmpty() -> {
                    busy = false
                    message = context.getString(R.string.customdict_import_empty_error)
                }
                else -> importPicked(language, files)
            }
        }
    }

    AddonStoreGroup(AddonType.Dictionary, onNavigate)

    // The enabled languages in their own order, then any language switched off
    // that still has lists on disk. Those used to disappear from this screen
    // entirely while their files stayed, so the only way to reach a list again
    // was to work out which language it belonged to and re-enable that.
    val enabledIds = settings.watch { s -> s.enabledLanguages.map { it.id } }
    val strandedIds = lists.keys.filter { it !in enabledIds && lists[it]?.isNotEmpty() == true }
    val offHeader = stringResource(R.string.customdict_language_off_header)
    // Languages with a list, plus one the user just asked for; the rest sit
    // behind one "add" row instead of an empty card each.
    var revealed by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = (enabledIds + strandedIds).filter {
        lists[it].orEmpty().isNotEmpty() || it in strandedIds || it == revealed
    }
    val hidden = enabledIds.filter { it !in shown }
    for (langId in shown) {
        val entries = lists[langId].orEmpty()
        val languageOff = langId in strandedIds
        val header = languageLabel(langId)
        // Decides whether the empty-lists warning is a row of the group.
        val shipped = settings.watch { it.suggestionStrip.shippedDictionaryEnabledFor(langId) }
        SettingsGroup(if (languageOff) offHeader.format(header) else header) {
            for (entry in entries) {
                // Keyed on the name rather than the path: switching a list off
                // renames its file, and a key that changed with it would close
                // the row and open it again for a flick of the switch.
                item(key = CustomDictionaries.displayName(entry.file)) {
                    // A downloaded word list is recorded by its path, which is
                    // what an addon's Use button hands over.
                    HighlightableItem(entry.file.absolutePath) {
                        val enabled = CustomDictionaries.isEnabled(entry.file)
                        val listName = CustomDictionaries.displayName(entry.file)
                            .substringBeforeLast('.')
                        WmRow(
                            title = listName,
                            subtitle = if (!enabled) {
                                stringResource(R.string.customdict_list_off_subtitle)
                            } else {
                                // A list imported for its pairs alone has no
                                // words, and "0 words" first would say it is empty.
                                listOfNotNull(
                                    entry.words.takeIf { it > 0 || (entry.pairs == 0 && entry.shortcuts == 0) }
                                        ?.let { pluralStringResource(R.plurals.customdict_word_count, it, it) },
                                    entry.pairs.takeIf { it > 0 }
                                        ?.let { pluralStringResource(R.plurals.customdict_pair_count, it, it) },
                                    entry.shortcuts.takeIf { it > 0 }
                                        ?.let { pluralStringResource(R.plurals.customdict_shortcut_count, it, it) },
                                ).joinToString(" · ")
                            },
                            swipeActions = listOf(
                                deleteSwipeAction(
                                    stringResource(R.string.customdict_delete_list_desc, listName),
                                ) {
                                    if (!busy) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                CustomDictionaries.remove(entry.file)
                                            }
                                            refresh()
                                            repository.bumpCustomDictVersion()
                                        }
                                    }
                                },
                            ),
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                // Switching off renames the file rather than
                                // deleting it: working out whether a bad import
                                // is polluting suggestions used to cost a delete
                                // and a re-import.
                                Switch(
                                    checked = enabled,
                                    enabled = !busy,
                                    onCheckedChange = { on ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                CustomDictionaries.setEnabled(entry.file, on)
                                            }
                                            refresh()
                                            repository.bumpCustomDictVersion()
                                        }
                                    },
                                )
                                IconButton(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                CustomDictionaries.remove(entry.file)
                                            }
                                            refresh()
                                            repository.bumpCustomDictVersion()
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(
                                            R.string.customdict_delete_list_desc,
                                            listName,
                                        ),
                                    )
                                }
                                }
                            },
                        )
                    }
                }
            }
            // No import buttons for a language that is switched off: a list
            // imported there would not be read by anything. The rows above stay
            // live, so the lists can still be switched off or deleted, which is
            // what someone reaching this group came for.
            if (languageOff) {
                item {
                    StateBanner(
                        stringResource(R.string.customdict_language_off_caption),
                        action = stringResource(R.string.home_languages_title),
                    ) { onNavigate("languages") }
                }
            } else {
                // Only where there is a list to fall back on: switching a
                // language to "my lists only" with nothing imported leaves it
                // with no words at all, and a row that can do that is not worth
                // offering next to an empty group (#28).
                if (entries.isNotEmpty()) {
                    item {
                        ToggleSetting(
                            R.string.customdict_only_my_lists_title,
                            stringResource(R.string.customdict_only_my_lists_subtitle),
                            checked = !shipped,
                            info = stringResource(R.string.customdict_only_my_lists_info),
                            enabled = !busy,
                            default = !SettingsDefaults.suggestionStrip
                                .shippedDictionaryEnabledFor(langId),
                        ) { onlyMine ->
                            scope.launch {
                                repository.setShippedDictionaryEnabled(langId, !onlyMine)
                            }
                        }
                    }
                    // Said out loud rather than quietly ignored: the setting is
                    // honoured exactly as asked, so a language whose every list
                    // is switched off really does go silent, and the reason has
                    // to be on the screen that caused it.
                    if (!shipped && entries.none { CustomDictionaries.isEnabled(it.file) }) {
                        item { StateBanner(stringResource(R.string.customdict_only_my_lists_empty)) }
                    }
                }
                item {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                pending = langId
                                importList.launch(arrayOf("*/*"))
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (entries.isEmpty()) R.string.customdict_import_action
                                    else R.string.customdict_import_another_action,
                                ),
                            )
                        }
                        // A download, which the offline build cannot make.
                        if (!OfflineBuild.isThisBuild) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { urlDialogFor = langId },
                            ) { Text(stringResource(R.string.customdict_from_url_action)) }
                        }
                    }
                }
            }
        }
    }
    if (hidden.isNotEmpty()) {
        var pickOpen by rememberSaveable { mutableStateOf(false) }
        SettingsGroup {
            item {
                NavRow(
                    R.string.customdict_add_language_title,
                    pluralStringResource(R.plurals.customdict_add_language_subtitle, hidden.size, hidden.size),
                    icon = Icons.Outlined.Add,
                ) { pickOpen = true }
            }
        }
        if (pickOpen) {
            AlertDialog(
                onDismissRequest = { pickOpen = false },
                title = { Text(stringResource(R.string.customdict_add_language_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        for (langId in hidden) {
                            Text(
                                languageLabel(langId),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        revealed = langId
                                        pickOpen = false
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pickOpen = false }) {
                        Text(stringResource(CommonR.string.common_cancel))
                    }
                },
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    val pendingImport = choosing
    if (pendingImport != null) {
        ImportChoiceDialog(
            candidates = pendingImport.candidates,
            onDismiss = { choosing = null },
        ) { parts ->
            choosing = null
            busy = true
            scope.launch {
                val written = withContext(Dispatchers.IO) {
                    pendingImport.candidates.zip(parts).map { (candidate, chosen) ->
                        CustomDictionaries.write(context.filesDir, pendingImport.langId, candidate, chosen)
                    }.reduce { a, b -> a + b }
                }
                finishImport(pendingImport.langId, written)
            }
        }
    }

    val urlLanguage = urlDialogFor
    if (urlLanguage != null) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { urlDialogFor = null },
            title = { Text(stringResource(R.string.customdict_url_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("https://…") },
                    placeholder = { Text(stringResource(R.string.customdict_url_dialog_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        urlDialogFor = null
                        importFromUrl(urlLanguage, url)
                    },
                ) { Text(stringResource(CommonR.string.common_download)) }
            },
            dismissButton = {
                TextButton(onClick = { urlDialogFor = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/**
 * Asks which parts of each dictionary in a pick to import: its words, its
 * word pairs, its shortcuts. Only the parts a dictionary has are offered, all
 * checked, and Import stays off while nothing is.
 */
@Composable
private fun ImportChoiceDialog(
    candidates: List<CustomDictionaries.ImportCandidate>,
    onDismiss: () -> Unit,
    onImport: (List<CustomDictionaries.ImportParts>) -> Unit,
) {
    val parts = remember(candidates) {
        mutableStateListOf(
            *candidates.map {
                CustomDictionaries.ImportParts(
                    words = it.words.isNotEmpty(),
                    pairs = it.ngrams.isNotEmpty(),
                    shortcuts = it.shortcuts.isNotEmpty(),
                )
            }.toTypedArray(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.customdict_choose_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                candidates.forEachIndexed { index, candidate ->
                    if (index > 0) Spacer(Modifier.height(16.dp))
                    Text(
                        candidate.name.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    candidate.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    candidate.locale?.let {
                        Text(
                            stringResource(R.string.customdict_choose_locale, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val chosen = parts[index]
                    if (candidate.words.isNotEmpty()) {
                        ImportPartRow(
                            stringResource(R.string.customdict_choose_words),
                            pluralStringResource(
                                R.plurals.customdict_choose_words_detail,
                                candidate.words.size,
                                candidate.words.size,
                            ),
                            chosen.words,
                        ) { parts[index] = chosen.copy(words = it) }
                    }
                    if (candidate.ngrams.isNotEmpty()) {
                        ImportPartRow(
                            stringResource(R.string.customdict_choose_pairs),
                            pluralStringResource(
                                R.plurals.customdict_choose_pairs_detail,
                                candidate.ngrams.size,
                                candidate.ngrams.size,
                            ),
                            chosen.pairs,
                        ) { parts[index] = chosen.copy(pairs = it) }
                    }
                    if (candidate.shortcuts.isNotEmpty()) {
                        ImportPartRow(
                            stringResource(R.string.customdict_choose_shortcuts),
                            pluralStringResource(
                                R.plurals.customdict_choose_shortcuts_detail,
                                candidate.shortcuts.size,
                                candidate.shortcuts.size,
                            ),
                            chosen.shortcuts,
                        ) { parts[index] = chosen.copy(shortcuts = it) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parts.any { it.words || it.pairs || it.shortcuts },
                onClick = { onImport(parts.toList()) },
            ) { Text(stringResource(CommonR.string.common_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** One checkbox of [ImportChoiceDialog]: the whole row toggles it. */
@Composable
private fun ImportPartRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- emoji keyword packs ----

/** One imported emoji pack: the file plus how many emoji it names. */
private data class EmojiPackEntry(val file: java.io.File, val emoji: Int)
/**
 * Per-language emoji keyword packs: the downloadable dictionaries from the
 * data repo, and the user's own imports.
 *
 * Deliberately the same shape as [CustomDictionarySettings] — per-language
 * groups, a download row, import from a file or a URL, delete a row — because
 * it solves the same problem: the app can only bundle so many languages, and
 * everything past that has to arrive from somewhere else.
 */
@Composable
internal fun EmojiKeywordSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var packs by remember { mutableStateOf<Map<String, List<EmojiPackEntry>>>(emptyMap()) }
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var urlDialogFor by remember { mutableStateOf<String?>(null) }

    // Enabled languages are the ones worth *offering* an import for, but a pack
    // can arrive for a language that isn't enabled — an addon repository
    // installs by langId, and languages get turned off again. Those groups
    // still have to appear or the pack would be uninstallable from here.
    val enabledIds = settings.watch { s -> s.enabledLanguages.map { it.id } }
    val languageIds = remember(enabledIds, packs.keys) {
        (enabledIds + packs.keys).distinct()
    }

    // Counting emoji means parsing every pack, so it never runs on the main
    // thread — the screen draws empty for a moment and fills in.
    suspend fun refresh() {
        packs = withContext(Dispatchers.IO) {
            // Enabled languages are the ones worth offering a download for,
            // but a pack can outlive the language being on — an addon repo
            // installs by langId, and languages get turned off again. Those
            // groups still have to appear or the pack is unreachable.
            val ids = (
                settings.value.enabledLanguages.map { it.id } +
                    EmojiKeywordPacks.languages(context.filesDir) +
                    EmojiDictStore.downloadedLanguageIds(context.filesDir)
                ).distinct()
            ids.associateWith { id ->
                EmojiKeywordPacks.packs(context.filesDir, id).map { file ->
                    val count = runCatching {
                        file.inputStream().use { EmojiKeywordPack.load(it).size }
                    }.getOrDefault(0)
                    EmojiPackEntry(file, count)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    suspend fun finish(langId: String, result: Int) {
        busy = false
        message = when {
            result == -2 -> context.getString(R.string.customdict_url_scheme_error)
            result == -1 -> context.getString(R.string.customdict_import_too_large_error)
            result == 0 -> context.getString(R.string.customdict_emoji_import_empty_error)
            else -> context.resources.getQuantityString(
                R.plurals.customdict_emoji_import_added,
                result,
                result,
                languageLabel(langId),
            )
        }
        if (result > 0) {
            refresh()
            repository.bumpEmojiKeywordPackVersion()
        }
    }

    fun importFromUrl(langId: String, url: String) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = android.net.Uri.parse(url.trim())
                    if (uri.scheme != "http" && uri.scheme != "https") {
                        return@runCatching -2
                    }
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                        ?: "emoji"
                    val temp = java.io.File.createTempFile("emoji_url_", ".tmp", context.cacheDir)
                    try {
                        ToolHttp.download(
                            url.trim(), temp, maxBytes = EmojiKeywordPack.MAX_BYTES,
                            source = NetSource.DOWNLOAD_EMOJI, route = NetLog.pathOf(url.trim()),
                        )
                        temp.inputStream().use {
                            EmojiKeywordPacks.import(context.filesDir, langId, name, it)
                        }
                    } finally {
                        temp.delete()
                    }
                }.getOrElse { -1 }
            }
            finish(langId, result)
        }
    }

    val importPack = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val language = pending
        pending = null
        if (uri == null || language == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver
                        .query(uri, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        } ?: "emoji"
                    val size = context.contentResolver
                        .query(uri, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
                        } ?: -1L
                    if (size > EmojiKeywordPack.MAX_BYTES) return@runCatching -1
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: return@runCatching 0
                    stream.use {
                        EmojiKeywordPacks.import(context.filesDir, language, name, it)
                    }
                }.getOrDefault(0)
            }
            finish(language, result)
        }
    }

    // The examples are drawn from the languages this user actually types, so
    // the line demonstrates the feature instead of demonstrating three scripts
    // they may not read.
    val packExamples = EmojiSearchExamples
        .pick(EmojiSearchExamples.money, enabledIds, limit = 3)
        .joinToString(", ")
    Text(
        stringResource(R.string.customdict_emoji_info, packExamples),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )

    // The app-wide switch decides whether any language data is fetched at
    // all, and it returns before this one is read.
    val autoDownloads = settings.watch { it.autoDownloadLanguageData }
    SettingsGroup(stringResource(R.string.customdict_emoji_downloads_title)) {
        item(visible = autoDownloads) {
            ToggleSetting(
                R.string.customdict_emoji_auto_download_title,
                stringResource(R.string.customdict_emoji_auto_download_subtitle),
                settings.watch { it.emoji.autoDownloadKeywords },
                info = stringResource(R.string.customdict_emoji_auto_download_info),
                default = SettingsDefaults.emoji.autoDownloadKeywords,
            ) { scope.launch { repository.setEmojiAutoDownloadKeywords(it) } }
        }
        item(visible = !OfflineBuild.isThisBuild, waitsOnSetting = false) { AddonStoreRow(AddonType.EmojiKeywords, onNavigate) }
    }

    for (languageId in languageIds) {
        val entries = packs[languageId].orEmpty()
        val dict = EmojiDictCatalog.forLanguage(languageId)
        SettingsGroup(languageLabel(languageId)) {
            if (dict != null) {
                item { EmojiDictRow(dict) }
            }
            for (entry in entries) {
                item(key = entry.file.absolutePath) {
                    HighlightableItem(entry.file.absolutePath) {
                        WmRow(
                            title = entry.file.nameWithoutExtension,
                            subtitle = pluralStringResource(
                                R.plurals.customdict_emoji_count,
                                entry.emoji,
                                entry.emoji,
                            ),
                            swipeActions = listOf(
                                deleteSwipeAction(
                                    stringResource(
                                        R.string.customdict_delete_pack_desc,
                                        entry.file.nameWithoutExtension,
                                    ),
                                ) {
                                    if (!busy) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                EmojiKeywordPacks.remove(entry.file)
                                            }
                                            refresh()
                                            repository.bumpEmojiKeywordPackVersion()
                                        }
                                    }
                                },
                            ),
                            trailing = {
                                IconButton(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                EmojiKeywordPacks.remove(entry.file)
                                            }
                                            refresh()
                                            repository.bumpEmojiKeywordPackVersion()
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(
                                            R.string.customdict_delete_pack_desc,
                                            entry.file.nameWithoutExtension,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            pending = languageId
                            importPack.launch(arrayOf("*/*"))
                        },
                    ) {
                        Text(
                            stringResource(
                                if (entries.isEmpty()) R.string.customdict_emoji_import_action
                                else R.string.customdict_import_another_action,
                            ),
                        )
                    }
                    if (!OfflineBuild.isThisBuild) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { urlDialogFor = languageId },
                        ) { Text(stringResource(R.string.customdict_from_url_action)) }
                    }
                }
            }
        }
    }

    ExpandableCard(title = stringResource(R.string.customdict_emoji_format_title)) {
        Text(
            stringResource(R.string.customdict_emoji_format_info),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    Spacer(Modifier.height(16.dp))

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    val urlLanguage = urlDialogFor
    if (urlLanguage != null) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { urlDialogFor = null },
            title = { Text(stringResource(R.string.customdict_emoji_url_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("https://…") },
                    placeholder = {
                        Text(stringResource(R.string.customdict_emoji_url_dialog_hint))
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        urlDialogFor = null
                        importFromUrl(urlLanguage, url)
                    },
                ) { Text(stringResource(CommonR.string.common_download)) }
            },
            dismissButton = {
                TextButton(onClick = { urlDialogFor = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

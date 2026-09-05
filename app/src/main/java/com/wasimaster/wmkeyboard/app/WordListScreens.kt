package com.wasimaster.wmkeyboard.app

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
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import android.provider.OpenableColumns
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictStore
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPack
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPacks
import com.wasimaster.wmkeyboard.core.emoji.EmojiSearchExamples
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import kotlinx.coroutines.launch

// ---- custom dictionaries ----

/** Human name for a language id, used as the word-list group header. */
private fun languageLabel(langId: String): String =
    LanguageRegistry.byId(langId).englishName
/** One imported list: the file plus how many words it parsed to. */
private data class WordListEntry(val file: java.io.File, val words: Int)
@Composable
internal fun CustomDictionarySettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
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

    // Counting words means reading every list, so it never runs on the main
    // thread — the screen draws empty for a moment and fills in.
    suspend fun refresh() {
        lists = withContext(Dispatchers.IO) {
            // The enabled languages plus any language that still has lists on
            // disk. Walking only the enabled ones meant that switching a
            // language off took its lists out of the one screen that manages
            // them, while the files stayed on disk and in Storage.
            val ids = LinkedHashSet<String>()
            settings.enabledLanguages.mapTo(ids) { it.id }
            ids.addAll(CustomDictionaries.languagesWithLists(context.filesDir))
            ids.associateWith { langId ->
                // allLists, not lists: a switched-off list still has to be
                // shown, or there is no way to switch it back on.
                CustomDictionaries.allLists(context.filesDir, langId).map { file ->
                    val words = runCatching {
                        file.inputStream().use { DictionaryLoader.loadEntries(it).size }
                    }.getOrDefault(0)
                    WordListEntry(file, words)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

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
                        ?: "wordlist"
                    val temp = java.io.File.createTempFile("dict_url_", ".tmp", context.cacheDir)
                    try {
                        ToolHttp.download(url.trim(), temp, maxBytes = CustomDictionaries.MAX_BYTES)
                        temp.inputStream().use { CustomDictionaries.import(context.filesDir, langId, name, it) }
                    } finally {
                        temp.delete()
                    }
                }.getOrElse { -1 }
            }
            busy = false
            message = when {
                result == -2 -> context.getString(R.string.customdict_url_scheme_error)
                result < 0 -> context.getString(R.string.customdict_url_download_error)
                result == 0 -> context.getString(R.string.customdict_import_empty_error)
                else -> context.resources.getQuantityString(
                    R.plurals.customdict_import_added_words,
                    result,
                    result,
                    languageLabel(langId),
                )
            }
            if (result > 0) {
                refresh()
                repository.bumpCustomDictVersion()
            }
        }
    }

    val importList = rememberLauncherForActivityResult(
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
                        } ?: "wordlist"
                    val size = context.contentResolver
                        .query(uri, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
                        } ?: -1L
                    if (size > CustomDictionaries.MAX_BYTES) return@runCatching -1
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: return@runCatching 0
                    stream.use { CustomDictionaries.import(context.filesDir, language, name, it) }
                }.getOrDefault(0)
            }
            busy = false
            message = when {
                result < 0 -> context.getString(R.string.customdict_import_too_large_error)
                result == 0 -> context.getString(R.string.customdict_import_empty_error)
                else -> context.resources.getQuantityString(
                    R.plurals.customdict_import_added_words,
                    result,
                    result,
                    languageLabel(language),
                )
            }
            if (result > 0) {
                refresh()
                repository.bumpCustomDictVersion()
            }
        }
    }

    Text(
        stringResource(R.string.customdict_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    AddonStoreGroup(AddonType.Dictionary, onNavigate)

    // The enabled languages in their own order, then any language switched off
    // that still has lists on disk. Those used to disappear from this screen
    // entirely while their files stayed, so the only way to reach a list again
    // was to work out which language it belonged to and re-enable that.
    val enabledIds = settings.enabledLanguages.map { it.id }
    val strandedIds = lists.keys.filter { it !in enabledIds && lists[it]?.isNotEmpty() == true }
    val offHeader = stringResource(R.string.customdict_language_off_header)
    for (langId in enabledIds + strandedIds) {
        val entries = lists[langId].orEmpty()
        val languageOff = langId in strandedIds
        val header = languageLabel(langId)
        SettingsGroup(if (languageOff) offHeader.format(header) else header) {
            for (entry in entries) {
                item {
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
                                pluralStringResource(
                                    R.plurals.customdict_word_count,
                                    entry.words,
                                    entry.words,
                                )
                            },
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
                item { CaptionText(stringResource(R.string.customdict_language_off_caption)) }
            } else {
                // Only where there is a list to fall back on: switching a
                // language to "my lists only" with nothing imported leaves it
                // with no words at all, and a row that can do that is not worth
                // offering next to an empty group (#28).
                if (entries.isNotEmpty()) {
                    val shipped = settings.suggestionStrip.shippedDictionaryEnabledFor(langId)
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
                        item { CaptionText(stringResource(R.string.customdict_only_my_lists_empty)) }
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
    settings: KeyboardSettings,
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
    val languageIds = remember(settings.enabledLanguages, packs.keys) {
        (settings.enabledLanguages.map { it.id } + packs.keys).distinct()
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
                settings.enabledLanguages.map { it.id } +
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
                        ToolHttp.download(url.trim(), temp, maxBytes = EmojiKeywordPack.MAX_BYTES)
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
        .pick(EmojiSearchExamples.money, settings.enabledLanguages.map { it.id }, limit = 3)
        .joinToString(", ")
    Text(
        stringResource(R.string.customdict_emoji_info, packExamples),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )

    SettingsGroup(stringResource(R.string.customdict_emoji_downloads_title)) {
        item {
            ToggleSetting(
                R.string.customdict_emoji_auto_download_title,
                stringResource(R.string.customdict_emoji_auto_download_subtitle),
                settings.emoji.autoDownloadKeywords,
                info = stringResource(R.string.customdict_emoji_auto_download_info),
                default = SettingsDefaults.emoji.autoDownloadKeywords,
            ) { scope.launch { repository.setEmojiAutoDownloadKeywords(it) } }
        }
        item { AddonStoreRow(AddonType.EmojiKeywords, onNavigate) }
    }

    for (languageId in languageIds) {
        val entries = packs[languageId].orEmpty()
        val dict = EmojiDictCatalog.forLanguage(languageId)
        SettingsGroup(languageLabel(languageId)) {
            if (dict != null) {
                item { EmojiDictRow(dict) }
            }
            for (entry in entries) {
                item {
                    HighlightableItem(entry.file.absolutePath) {
                        WmRow(
                            title = entry.file.nameWithoutExtension,
                            subtitle = pluralStringResource(
                                R.plurals.customdict_emoji_count,
                                entry.emoji,
                                entry.emoji,
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
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { urlDialogFor = languageId },
                    ) { Text(stringResource(R.string.customdict_from_url_action)) }
                }
            }
        }
    }

    Text(
        stringResource(R.string.customdict_emoji_format_info),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
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

package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryEntry
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.ContextModelCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.ContextModelEntry
import com.wasimaster.wmkeyboard.core.dictionaries.ContextModelVariant
import com.wasimaster.wmkeyboard.core.dictionaries.ContextModelDownloadManager
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackDownloadManager
import com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPacks
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.keywordsEnabledFor
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * The Dictionaries group on a language's screen: one row each for what the
 * language can read (its word list, its emoji keywords, its word pairs), and a
 * row to the user's own imported lists.
 *
 * Every row works the same way. Something on the device has a checkbox, which
 * is the "use it" switch, and a Delete. Something that is not has a Download.
 * The word list asks which size (and, where a language has more than one
 * list, which source and which region) in a dialog, because that is the one
 * choice a download here has. A downloaded word list keeps that choice open:
 * the arrows beside its Delete reopen the same dialog on the list it is now,
 * so trading a 50,000-word list for a 300,000-word one, or an AOSP list for
 * the counted one, is one press rather than a delete and a fresh download.
 * The heading carries a Download all while anything is still missing, and
 * loses it once nothing is.
 *
 * A row the language has nothing for is left out, rather than shown to say so.
 */
@Composable
internal fun DictionariesGroup(
    lang: LanguageDef,
    settings: LiveSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val langId = lang.id
    val notifyDownload = rememberDownloadNotifier()

    val wordlists = remember(langId) { DictionaryCatalog.forLanguage(langId) }
    val emojiEntry = remember(langId) { EmojiDictCatalog.forLanguage(langId) }
    val pairsEntry = remember(langId) { NgramPackCatalog.forLanguage(langId) }
    val modelEntries = remember(langId) { ContextModelCatalog.variantsFor(langId) }

    val wordStates by WordlistDownloadManager.states.collectAsState()
    val emojiStates by EmojiDictDownloadManager.states.collectAsState()
    val pairStates by NgramPackDownloadManager.states.collectAsState()
    val modelStates by ContextModelDownloadManager.states.collectAsState()
    LaunchedEffect(langId) {
        WordlistDownloadManager.refresh(filesDir)
        EmojiDictDownloadManager.refresh(filesDir)
        NgramPackDownloadManager.refresh(filesDir)
        ContextModelDownloadManager.refresh(filesDir)
    }

    // A language can have several lists in one slot (an AOSP and a counted
    // one, and for some two regions of each): the row speaks for whichever is
    // doing something, else whichever is on the device, else the preferred one.
    val wordEntry = wordlists.firstOrNull { wordStates[it.id].isActive() }
        ?: wordlists.firstOrNull { wordStates[it.id] is WordlistDownloadManager.DownloadStatus.Downloaded }
        ?: DictionaryCatalog.preferred(langId)
    val wordStatus = wordEntry?.let { wordStates[it.id] }
        ?: WordlistDownloadManager.DownloadStatus.NotDownloaded
    val emojiStatus = emojiEntry?.let { emojiStates[it.languageId] }
        ?: EmojiDictDownloadManager.DownloadStatus.NotDownloaded
    val pairStatus = pairsEntry?.let { pairStates[it.languageId] }
        ?: NgramPackDownloadManager.DownloadStatus.NotDownloaded
    val modelStatus = modelEntries.firstOrNull()?.let { modelStates[it.languageId] }
        ?: ContextModelDownloadManager.DownloadStatus.NotDownloaded
    val emojiImported = remember(langId, emojiStatus) {
        EmojiKeywordPacks.packs(filesDir, langId).isNotEmpty()
    }
    val hasHunspell = rememberHasHunspell(langId)

    // Sizes the Download all on the heading, so it is read here.
    val size = settings.watch { it.appUi.defaultWordlistSize }
    // Only what is neither on the device nor on its way: a second press, or a
    // press while one row is already downloading, must not fetch it twice.
    val missing = LanguageData(
        wordlist = wordEntry?.takeIf { wordStatus.isMissing() },
        emojiDict = emojiEntry?.takeIf { emojiStatus.isMissing() },
        ngram = pairsEntry?.takeIf { pairStatus.isMissing() },
        bytes = 0L,
    ).let { it.copy(bytes = it.approxBytes(size)) }

    var dialogOpen by remember { mutableStateOf(false) }
    // The same dialog, opened from the arrows on a downloaded row: it starts on
    // the size that list is at rather than on the one the next download would
    // use, and says what replacing it costs.
    var dialogIsResize by remember { mutableStateOf(false) }
    var confirmMetered by remember { mutableStateOf(false) }
    var blockedMetered by remember { mutableStateOf(false) }
    val downloadDecision = rememberDownloadDecision(settings)
    val downloadAll = { startLanguageDataDownload(context, missing, notifyDownload, size) }

    SettingsGroup(
        stringResource(R.string.languages_dictionaries_title),
        info = stringResource(R.string.languages_dictionaries_info),
        action = if (missing.isEmpty) {
            null
        } else {
            {
                TextButton(
                    onClick = {
                        when (downloadDecision()) {
                            MeteredDecision.ASK -> confirmMetered = true
                            MeteredDecision.BLOCKED -> blockedMetered = true
                            MeteredDecision.ALLOWED -> downloadAll()
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(
                            R.string.languages_dictionaries_download_all_action,
                            formatBytes(missing.bytes),
                        ),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        },
    ) {
        if (wordEntry != null || lang.bundledDictionary) {
            item {
                WordListRow(
                    lang = lang,
                    entry = wordEntry,
                    alternatives = wordlists.size > 1,
                    status = wordStatus,
                    checked = settings.watch { it.suggestionStrip.shippedDictionaryEnabledFor(langId) },
                    onChecked = { scope.launch { repository.setShippedDictionaryEnabled(langId, it) } },
                    onDownload = {
                        dialogIsResize = false
                        dialogOpen = true
                    },
                    onResize = {
                        dialogIsResize = true
                        dialogOpen = true
                    },
                )
            }
        }
        if (emojiEntry != null || emojiImported) {
            item {
                EmojiKeywordsRow(
                    langId = langId,
                    status = emojiStatus,
                    available = emojiEntry != null,
                    imported = emojiImported,
                    checked = settings.watch { it.emoji.keywordsEnabledFor(langId) },
                    onChecked = { scope.launch { repository.setEmojiKeywordsEnabled(langId, it) } },
                )
            }
        }
        if (pairsEntry != null) {
            item {
                WordPairsRow(
                    langId = langId,
                    approxBytes = pairsEntry.approxGzBytes,
                    status = pairStatus,
                    checked = settings.watch { it.suggestionStrip.wordPairsEnabledFor(langId) },
                    onChecked = { scope.launch { repository.setWordPairsEnabled(langId, it) } },
                )
            }
        }
        // The neural context model: one file, one switch (Typing › Context
        // model) shared by every language that has one.
        if (modelEntries.isNotEmpty()) {
            item {
                ContextModelRow(langId = langId, entries = modelEntries, status = modelStatus)
            }
        }
        // For a phone that cannot download: the same files, as one language
        // pack fetched elsewhere and imported here.
        if (wordlists.isNotEmpty() || emojiEntry != null || pairsEntry != null) {
            item {
                val links = remember(langId) { LanguagePacks.links(context, langId) }
                OfflineImportRow(
                    title = stringResource(R.string.offline_import_language_pack_title),
                    subtitle = stringResource(R.string.offline_import_language_pack_subtitle),
                    links = links,
                    wordlistSize = size,
                )
            }
        }
        // Only for a language with an imported Hunspell dictionary that kept
        // its .aff and .dic: that is all the library checks against.
        if (hasHunspell) {
            item(key = "hunspell") { HunspellRow(langId, settings) }
        }
        // For a language GiellaLT builds a speller for: download where a
        // versioned release is pinned, import otherwise.
        if (offersSpeller(langId)) {
            item(key = "speller") { SpellerRow(langId, settings) }
        }
        // Finnish: Voikko, which checks it in Hunspell's place once its
        // dictionary is on the phone.
        if (offersVoikko(langId)) {
            item(key = "voikko") { VoikkoRow(langId, settings) }
        }
        item {
            NavRow(
                R.string.languages_custom_dictionaries_title,
                stringResource(R.string.languages_custom_dictionaries_subtitle),
                route = "customdictionaries",
            ) { onNavigate("customdictionaries") }
        }
    }

    if (dialogOpen && wordlists.isNotEmpty()) {
        val downloaded = wordStatus as? WordlistDownloadManager.DownloadStatus.Downloaded
        val resizing = dialogIsResize && downloaded != null
        WordListDownloadDialog(
            entries = wordlists,
            initial = wordEntry ?: wordlists.first(),
            initialSize = if (resizing) downloaded.size else size,
            pairsBytes = pairsEntry?.takeIf { pairStatus.isMissing() }?.approxGzBytes,
            resizing = resizing,
            onDismiss = { dialogOpen = false },
            onDownload = { entry, chosen ->
                dialogOpen = false
                scope.launch { repository.setDefaultWordlistSize(chosen) }
                startLanguageDataDownload(
                    context,
                    LanguageData(
                        wordlist = entry,
                        emojiDict = null,
                        ngram = pairsEntry?.takeIf { pairStatus.isMissing() },
                        bytes = 0L,
                    ),
                    notifyDownload,
                    chosen,
                )
            },
        )
    }
    if (confirmMetered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.languages_metered_confirm_body, formatBytes(missing.bytes)),
            onConfirm = {
                confirmMetered = false
                downloadAll()
            },
            onDismiss = { confirmMetered = false },
        )
    }
    if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }
}

/**
 * Fetches, for each of [langIds], whatever of its word list, emoji names,
 * word pairs and conversion packs is not on the device and not already on its way: what a restore
 * calls after it switched languages on, since a restored list of languages is
 * no use without their dictionaries. The word list at the size a language
 * added in the wizard gets.
 */
internal suspend fun startMissingLanguageData(
    context: android.content.Context,
    langIds: Collection<String>,
    notify: DownloadNotifier?,
) {
    val filesDir = context.filesDir
    withContext(Dispatchers.IO) {
        WordlistDownloadManager.refresh(filesDir)
        NgramPackDownloadManager.refresh(filesDir)
    }
    EmojiDictDownloadManager.refresh(filesDir)
    for (langId in langIds.distinct()) {
        val all = languageData(langId)
        val missing = LanguageData(
            wordlist = all.wordlist?.takeIf {
                (WordlistDownloadManager.states.value[it.id] ?: WordlistDownloadManager.DownloadStatus.NotDownloaded)
                    .isMissing()
            },
            emojiDict = all.emojiDict?.takeIf {
                (EmojiDictDownloadManager.states.value[it.languageId] ?: EmojiDictDownloadManager.DownloadStatus.NotDownloaded)
                    .isMissing()
            },
            ngram = all.ngram?.takeIf {
                (NgramPackDownloadManager.states.value[it.languageId] ?: NgramPackDownloadManager.DownloadStatus.NotDownloaded)
                    .isMissing()
            },
            bytes = 0L,
            // Filtered against the disk by the download itself.
            cjkPacks = all.cjkPacks,
        )
        if (!missing.isEmpty) startLanguageDataDownload(context, missing, notify)
    }
}

private fun WordlistDownloadManager.DownloadStatus?.isActive(): Boolean =
    this is WordlistDownloadManager.DownloadStatus.Downloading ||
        this == WordlistDownloadManager.DownloadStatus.Processing

private fun WordlistDownloadManager.DownloadStatus.isMissing(): Boolean =
    this is WordlistDownloadManager.DownloadStatus.NotDownloaded ||
        this is WordlistDownloadManager.DownloadStatus.Failed

private fun EmojiDictDownloadManager.DownloadStatus.isMissing(): Boolean =
    this is EmojiDictDownloadManager.DownloadStatus.NotDownloaded ||
        this is EmojiDictDownloadManager.DownloadStatus.Failed

private fun NgramPackDownloadManager.DownloadStatus.isMissing(): Boolean =
    this == NgramPackDownloadManager.DownloadStatus.NotDownloaded ||
        this == NgramPackDownloadManager.DownloadStatus.Failed

/**
 * One row of the group: the checkbox when [checked] is not null (the item is
 * on the device), the title and [supporting] line, and [trailing] actions. A
 * press anywhere on a row with a checkbox flips it, the way a settings row
 * with a switch does.
 */
@Composable
private fun DictionaryItemRow(
    title: String,
    supporting: String,
    checked: Boolean?,
    onChecked: (Boolean) -> Unit,
    error: Boolean = false,
    swipeActions: List<SwipeAction> = emptyList(),
    trailing: @Composable () -> Unit,
) {
    WmRow(
        title = title,
        swipeActions = swipeActions,
        leading = checked?.let { { Checkbox(checked = it, onCheckedChange = null) } },
        supporting = {
            Text(
                supporting,
                color = if (error) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        trailing = trailing,
        onClick = checked?.let { { onChecked(!it) } },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * The word list row.
 *
 * [onResize] reopens the download dialog on the list that is on the device. It
 * is offered only while one actually is, and only when there is something to
 * trade it for: another size, or another list ([alternatives]). A language
 * with one list whose whole vocabulary fits inside Small has neither.
 */
@Composable
private fun WordListRow(
    lang: LanguageDef,
    entry: DictionaryEntry?,
    alternatives: Boolean,
    status: WordlistDownloadManager.DownloadStatus,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onResize: () -> Unit,
) {
    val filesDir = LocalContext.current.filesDir
    val onDevice = lang.bundledDictionary ||
        status is WordlistDownloadManager.DownloadStatus.Downloaded ||
        (status as? WordlistDownloadManager.DownloadStatus.Failed)?.kept == true
    val title = entry
        ?.takeIf { alternatives && (status is WordlistDownloadManager.DownloadStatus.Downloaded || status.isActive()) }
        ?.let { stringResource(R.string.languages_words_title_variant, entryLabel(it)) }
        ?: stringResource(R.string.languages_words_title)
    val supporting = when (status) {
        is WordlistDownloadManager.DownloadStatus.Downloaded -> pluralStringResource(
            R.plurals.languages_wordlist_downloaded,
            status.wordCount,
            status.wordCount,
            formatBytes(status.sizeBytes),
        )
        WordlistDownloadManager.DownloadStatus.Processing ->
            stringResource(R.string.languages_wordlist_preparing)
        is WordlistDownloadManager.DownloadStatus.Downloading ->
            stringResource(R.string.languages_wordlist_downloaded_bytes, formatBytes(status.bytes))
        is WordlistDownloadManager.DownloadStatus.Failed ->
            if (status.messageArg.isEmpty()) stringResource(status.messageRes)
            else stringResource(status.messageRes, status.messageArg)
        WordlistDownloadManager.DownloadStatus.NotDownloaded -> when {
            lang.bundledDictionary && entry != null -> stringResource(R.string.languages_words_builtin_more)
            lang.bundledDictionary -> stringResource(R.string.languages_words_builtin)
            else -> stringResource(R.string.languages_words_missing)
        }
    }
    // A failure that kept the old list keeps its furniture too: the error says
    // the new size did not arrive, and the list already there is still the
    // one in use.
    val kept = (status as? WordlistDownloadManager.DownloadStatus.Failed)?.kept == true
    val deleteLabel = stringResource(R.string.languages_wordlist_delete_desc)
    DictionaryItemRow(
        title = title,
        supporting = supporting,
        checked = if (onDevice) checked else null,
        onChecked = onChecked,
        error = status is WordlistDownloadManager.DownloadStatus.Failed,
        swipeActions = if (entry != null && (status is WordlistDownloadManager.DownloadStatus.Downloaded || kept)) {
            listOf(deleteSwipeAction(deleteLabel) { WordlistDownloadManager.delete(filesDir, entry) })
        } else {
            emptyList()
        },
    ) {
        when (status) {
            is WordlistDownloadManager.DownloadStatus.Downloaded ->
                if (entry != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ResizeButton(entry, status.size, alternatives, onResize)
                        DeleteWordListButton(filesDir, entry)
                    }
                }
            is WordlistDownloadManager.DownloadStatus.Downloading,
            WordlistDownloadManager.DownloadStatus.Processing,
            -> IconButton(onClick = { WordlistDownloadManager.cancel(entry?.id) }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.languages_cancel_download_desc),
                )
            }
            WordlistDownloadManager.DownloadStatus.NotDownloaded,
            is WordlistDownloadManager.DownloadStatus.Failed,
            -> if (entry != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!offlineBuild) TextButton(onClick = onDownload, enabled = !WordlistDownloadManager.isBusy) {
                        Text(
                            stringResource(
                                if (status is WordlistDownloadManager.DownloadStatus.Failed) {
                                    CommonR.string.common_retry
                                } else {
                                    CommonR.string.common_download
                                },
                            ),
                        )
                    }
                    // Retry reopens the size dialog, and the list the failed
                    // download was going to replace is still there to keep or
                    // to delete.
                    if (kept) DeleteWordListButton(filesDir, entry)
                }
            }
        }
    }
    // Indeterminate on purpose: a capped download stops early, so
    // bytes-of-total would count to a total it never reaches.
    if (status.isActive()) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DeleteWordListButton(filesDir: File, entry: DictionaryEntry) {
    IconButton(onClick = { WordlistDownloadManager.delete(filesDir, entry) }) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.languages_wordlist_delete_desc),
        )
    }
}

/**
 * Trade the downloaded list for another size, or for another of the language's
 * lists when it has [alternatives]. The arrows say which way a size trade can
 * go from [current]: up at the smallest size, down at the largest, both in
 * between — so the icon answers "is there a bigger one?" without opening the
 * dialog. Nothing is drawn when there is nothing to trade: one list, whose
 * whole vocabulary fits inside Small.
 */
@Composable
private fun ResizeButton(
    entry: DictionaryEntry,
    current: DictionaryCatalog.DictionarySize,
    alternatives: Boolean,
    onClick: () -> Unit,
) {
    val tiers = remember(entry) {
        DictionaryCatalog.DictionarySize.entries.distinctBy { DictionaryCatalog.wordCap(entry, it) }
    }
    if (tiers.size >= 2 || alternatives) {
        // The tier on disk may be one the collapse dropped (ALL and Large keep
        // the same words on a 200,000-word list), so match on what it keeps.
        val index = tiers.indexOfFirst {
            DictionaryCatalog.wordCap(entry, it) == DictionaryCatalog.wordCap(entry, current)
        }.coerceAtLeast(0)
        val icon = when {
            tiers.size < 2 -> Icons.Outlined.SwapVert
            index == 0 -> Icons.Outlined.KeyboardDoubleArrowUp
            index == tiers.lastIndex -> Icons.Outlined.KeyboardDoubleArrowDown
            else -> Icons.Outlined.SwapVert
        }
        val description = when {
            alternatives -> R.string.languages_wordlist_change_desc
            index == 0 -> R.string.languages_wordlist_resize_bigger_desc
            index == tiers.lastIndex -> R.string.languages_wordlist_resize_smaller_desc
            else -> R.string.languages_wordlist_resize_desc
        }
        IconButton(onClick = onClick, enabled = !WordlistDownloadManager.isBusy) {
            Icon(icon, contentDescription = stringResource(description))
        }
    }
}

@Composable
private fun EmojiKeywordsRow(
    langId: String,
    status: EmojiDictDownloadManager.DownloadStatus,
    available: Boolean,
    imported: Boolean,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val notifyDownload = rememberDownloadNotifier()
    val downloadName = stringResource(
        R.string.notify_download_emoji_names,
        LanguageRegistry.byId(langId).displayName,
    )
    val entry = EmojiDictCatalog.forLanguage(langId)
    val downloaded = status is EmojiDictDownloadManager.DownloadStatus.Downloaded
    val supporting = when (status) {
        is EmojiDictDownloadManager.DownloadStatus.Downloaded -> pluralStringResource(
            R.plurals.languages_emoji_dict_downloaded,
            status.emojiCount,
            status.emojiCount,
            formatBytes(status.sizeBytes),
        )
        EmojiDictDownloadManager.DownloadStatus.Queued -> stringResource(R.string.languages_download_queued)
        is EmojiDictDownloadManager.DownloadStatus.Downloading ->
            stringResource(CommonR.string.common_downloading)
        is EmojiDictDownloadManager.DownloadStatus.Failed ->
            if (status.messageArg.isEmpty()) stringResource(status.messageRes)
            else stringResource(status.messageRes, status.messageArg)
        EmojiDictDownloadManager.DownloadStatus.NotDownloaded -> when {
            entry != null -> pluralStringResource(
                R.plurals.languages_emoji_dict_download_size,
                entry.emojiCount,
                entry.emojiCount,
                formatBytes(entry.approxGzBytes),
            )
            else -> stringResource(R.string.languages_emoji_keywords_imported)
        }
    }
    DictionaryItemRow(
        title = stringResource(R.string.languages_emoji_keywords_title),
        supporting = supporting,
        checked = if (downloaded || imported) checked else null,
        onChecked = onChecked,
        error = status is EmojiDictDownloadManager.DownloadStatus.Failed,
        swipeActions = if (downloaded) {
            listOf(
                deleteSwipeAction(stringResource(R.string.languages_emoji_dict_delete_desc)) {
                    EmojiDictDownloadManager.delete(filesDir, langId)
                },
            )
        } else {
            emptyList()
        },
    ) {
        when (status) {
            is EmojiDictDownloadManager.DownloadStatus.Downloaded ->
                IconButton(onClick = { EmojiDictDownloadManager.delete(filesDir, langId) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.languages_emoji_dict_delete_desc),
                    )
                }
            EmojiDictDownloadManager.DownloadStatus.Queued,
            is EmojiDictDownloadManager.DownloadStatus.Downloading,
            -> IconButton(onClick = { EmojiDictDownloadManager.cancel(langId) }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.languages_cancel_download_desc),
                )
            }
            EmojiDictDownloadManager.DownloadStatus.NotDownloaded,
            is EmojiDictDownloadManager.DownloadStatus.Failed,
            -> if (available && entry != null && !offlineBuild) {
                TextButton(
                    onClick = {
                        EmojiDictDownloadManager.start(filesDir, entry)
                        notifyDownload(langId, downloadName, DownloadProgressFlows.emojiDict(context, langId))
                    },
                ) {
                    Text(
                        stringResource(
                            if (status is EmojiDictDownloadManager.DownloadStatus.Failed) CommonR.string.common_retry
                            else CommonR.string.common_download,
                        ),
                    )
                }
            }
        }
    }
    val downloading = status as? EmojiDictDownloadManager.DownloadStatus.Downloading
    if (downloading != null) {
        LinearProgressIndicator(
            progress = {
                if (downloading.totalBytes > 0) {
                    (downloading.bytes.toFloat() / downloading.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun variantLabel(variant: ContextModelVariant): String = stringResource(
    when (variant) {
        ContextModelVariant.STANDARD -> R.string.languages_context_model_variant_standard
        ContextModelVariant.LARGE -> R.string.languages_context_model_variant_large
    },
)

/**
 * The language's context model: one download, or a choice of sizes when
 * the catalog lists more than one. The Download button opens the choice;
 * once a model is on disk, the overflow menu switches to the other size
 * (the file is replaced) or deletes it.
 */
@Composable
private fun ContextModelRow(
    langId: String,
    entries: List<ContextModelEntry>,
    status: ContextModelDownloadManager.DownloadStatus,
) {
    val filesDir = LocalContext.current.filesDir
    val standard = entries.first()
    val several = entries.size > 1
    var menu by remember { mutableStateOf(false) }
    // The build with no internet can't download one; the Language pack row's
    // Get the files lists it and Import takes it, so the row says that rather
    // than offering a download that can only fail.
    val offline = offlineBuild
    val supporting = when (status) {
        is ContextModelDownloadManager.DownloadStatus.Downloaded ->
            if (several) {
                stringResource(
                    R.string.languages_context_model_downloaded_variant,
                    variantLabel(status.variant), formatBytes(status.sizeBytes),
                )
            } else {
                stringResource(R.string.languages_context_model_downloaded, formatBytes(status.sizeBytes))
            }
        is ContextModelDownloadManager.DownloadStatus.Downloading -> stringResource(CommonR.string.common_downloading)
        ContextModelDownloadManager.DownloadStatus.Failed,
        ContextModelDownloadManager.DownloadStatus.NotDownloaded,
        -> when {
            offline -> stringResource(R.string.languages_context_model_offline)
            status == ContextModelDownloadManager.DownloadStatus.Failed ->
                stringResource(R.string.languages_context_model_failed)
            else -> stringResource(R.string.languages_context_model_download_size, formatBytes(standard.approxBytes))
        }
    }
    DictionaryItemRow(
        title = stringResource(R.string.languages_context_model_title),
        supporting = supporting,
        checked = null,
        onChecked = {},
        error = status == ContextModelDownloadManager.DownloadStatus.Failed && !offline,
        swipeActions = if (status is ContextModelDownloadManager.DownloadStatus.Downloaded) {
            listOf(
                deleteSwipeAction(stringResource(R.string.languages_context_model_delete_desc)) {
                    ContextModelDownloadManager.delete(filesDir, langId)
                },
            )
        } else {
            emptyList()
        },
    ) {
        when (status) {
            is ContextModelDownloadManager.DownloadStatus.Downloaded -> Box {
                if (several) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(CommonR.string.common_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (entry in entries) {
                            if (entry.variant == status.variant) continue
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.languages_context_model_switch,
                                            variantLabel(entry.variant), formatBytes(entry.approxBytes),
                                        ),
                                    )
                                },
                                onClick = {
                                    menu = false
                                    ContextModelDownloadManager.start(filesDir, langId, entry.variant)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.languages_context_model_delete_desc)) },
                            onClick = {
                                menu = false
                                ContextModelDownloadManager.delete(filesDir, langId)
                            },
                        )
                    }
                } else {
                    IconButton(onClick = { ContextModelDownloadManager.delete(filesDir, langId) }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.languages_context_model_delete_desc),
                        )
                    }
                }
            }
            is ContextModelDownloadManager.DownloadStatus.Downloading ->
                IconButton(onClick = { ContextModelDownloadManager.cancel(langId) }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_cancel))
                }
            ContextModelDownloadManager.DownloadStatus.NotDownloaded,
            ContextModelDownloadManager.DownloadStatus.Failed,
            -> if (offline) Unit else Box {
                TextButton(onClick = {
                    if (several) menu = true else ContextModelDownloadManager.start(filesDir, langId)
                }) {
                    Text(
                        stringResource(
                            if (status == ContextModelDownloadManager.DownloadStatus.Failed) CommonR.string.common_retry
                            else CommonR.string.common_download,
                        ),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (entry in entries) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.languages_context_model_variant_size,
                                        variantLabel(entry.variant), formatBytes(entry.approxBytes),
                                    ),
                                )
                            },
                            onClick = {
                                menu = false
                                ContextModelDownloadManager.start(filesDir, langId, entry.variant)
                            },
                        )
                    }
                }
            }
        }
    }
    if (status is ContextModelDownloadManager.DownloadStatus.Downloading) {
        LinearProgressIndicator(
            progress = { status.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WordPairsRow(
    langId: String,
    approxBytes: Long,
    status: NgramPackDownloadManager.DownloadStatus,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val filesDir = LocalContext.current.filesDir
    val supporting = when (status) {
        is NgramPackDownloadManager.DownloadStatus.Downloaded ->
            stringResource(R.string.languages_word_pairs_downloaded, formatBytes(status.sizeBytes))
        NgramPackDownloadManager.DownloadStatus.Downloading -> stringResource(CommonR.string.common_downloading)
        NgramPackDownloadManager.DownloadStatus.Failed -> stringResource(R.string.languages_word_pairs_failed)
        NgramPackDownloadManager.DownloadStatus.NotDownloaded ->
            stringResource(R.string.languages_word_pairs_download_size, formatBytes(approxBytes))
    }
    DictionaryItemRow(
        title = stringResource(R.string.languages_word_pairs_title),
        supporting = supporting,
        checked = if (status is NgramPackDownloadManager.DownloadStatus.Downloaded) checked else null,
        onChecked = onChecked,
        error = status == NgramPackDownloadManager.DownloadStatus.Failed,
        swipeActions = if (status is NgramPackDownloadManager.DownloadStatus.Downloaded) {
            listOf(
                deleteSwipeAction(stringResource(R.string.languages_word_pairs_delete_desc)) {
                    NgramPackDownloadManager.delete(filesDir, langId)
                },
            )
        } else {
            emptyList()
        },
    ) {
        when (status) {
            is NgramPackDownloadManager.DownloadStatus.Downloaded ->
                IconButton(onClick = { NgramPackDownloadManager.delete(filesDir, langId) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.languages_word_pairs_delete_desc),
                    )
                }
            // No cancel: the pack compiles in one blocking pass that a
            // cancel cannot stop, and a button that does nothing is worse
            // than none. It is a megabyte or two.
            NgramPackDownloadManager.DownloadStatus.Downloading -> Unit
            NgramPackDownloadManager.DownloadStatus.NotDownloaded,
            NgramPackDownloadManager.DownloadStatus.Failed,
            -> if (!offlineBuild) TextButton(onClick = { NgramPackDownloadManager.start(filesDir, langId) }) {
                Text(
                    stringResource(
                        if (status == NgramPackDownloadManager.DownloadStatus.Failed) CommonR.string.common_retry
                        else CommonR.string.common_download,
                    ),
                )
            }
        }
    }
    if (status == NgramPackDownloadManager.DownloadStatus.Downloading) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * What Download on the word list asks: which size, and for a language with
 * more than one list, which source (AOSP or counted) and which region. Each
 * size says how many words it keeps and roughly what it costs to fetch.
 * [pairsBytes] is the word-pair data that comes along with it, when the
 * language has some that is not on the device.
 *
 * [resizing] is the same question asked of a list already on the device: same
 * choices, opened on the list and size it is at, and a line saying the old
 * list stays until the new one has finished.
 */
@Composable
private fun WordListDownloadDialog(
    entries: List<DictionaryEntry>,
    initial: DictionaryEntry,
    initialSize: DictionaryCatalog.DictionarySize,
    pairsBytes: Long?,
    resizing: Boolean = false,
    onDismiss: () -> Unit,
    onDownload: (DictionaryEntry, DictionaryCatalog.DictionarySize) -> Unit,
) {
    var entry by remember { mutableStateOf(initial) }
    val sources = remember(entries) { entries.map { it.source }.distinct().sorted() }
    val variants = entries.filter { it.source == entry.source }
    // Tiers past the end of a short list all keep the same words, so only the
    // first one that reaches the whole list is worth offering.
    val sizes = remember(entry) {
        DictionaryCatalog.DictionarySize.entries.distinctBy { DictionaryCatalog.wordCap(entry, it) }
    }
    var size by remember { mutableStateOf(initialSize) }
    val chosen = sizes.firstOrNull { DictionaryCatalog.wordCap(entry, it) == DictionaryCatalog.wordCap(entry, size) }
        ?: sizes.last()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when {
                        !resizing -> R.string.languages_words_download_title
                        entries.size > 1 -> R.string.languages_words_change_title
                        else -> R.string.languages_words_resize_title
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (sources.size > 1) {
                    DialogLabel(stringResource(R.string.languages_words_download_source_label))
                    for (source in sources) {
                        ChoiceLine(
                            selected = source == entry.source,
                            label = stringResource(source.labelRes),
                            detail = stringResource(source.detailRes),
                        ) {
                            // Keep the region across the switch where the other
                            // source has it: Brazil stays Brazil.
                            if (source != entry.source) {
                                entry = entries.firstOrNull { it.source == source && it.variantRes == entry.variantRes }
                                    ?: entries.first { it.source == source }
                            }
                        }
                    }
                }
                if (variants.size > 1) {
                    DialogLabel(stringResource(R.string.languages_words_download_variant_label))
                    for (option in variants) {
                        ChoiceLine(
                            selected = option == entry,
                            label = option.variantRes?.let { stringResource(it) }
                                ?: LanguageRegistry.byId(option.languageId).displayName,
                        ) { entry = option }
                    }
                }
                if (entries.size > 1) {
                    DialogLabel(stringResource(R.string.languages_words_download_size_label))
                }
                for (option in sizes) {
                    val words = DictionaryCatalog.wordCap(entry, option)
                    ChoiceLine(
                        selected = option == chosen,
                        label = stringResource(option.labelRes),
                        detail = pluralStringResource(
                            R.plurals.languages_words_download_size_detail,
                            words,
                            words,
                            formatBytes(entryBytes(entry, option)),
                        ),
                    ) { size = option }
                }
                if (pairsBytes != null) {
                    Text(
                        stringResource(R.string.languages_words_download_pairs_note, formatBytes(pairsBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (resizing) {
                    Text(
                        stringResource(R.string.languages_words_resize_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(entry, chosen) }) {
                Text(stringResource(CommonR.string.common_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * What tells one of a language's lists from its others: the source, and the
 * region where that source has more than one. "AOSP, Brazil"; "Frequency".
 */
@Composable
internal fun entryLabel(entry: DictionaryEntry): String {
    val source = stringResource(entry.source.labelRes)
    val variant = entry.variantRes
        ?.takeIf { DictionaryCatalog.forLanguage(entry.languageId).count { it.source == entry.source } > 1 }
        ?: return source
    return stringResource(R.string.languages_words_label_source_variant, source, stringResource(variant))
}

@Composable
private fun DialogLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceLine(selected: Boolean, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

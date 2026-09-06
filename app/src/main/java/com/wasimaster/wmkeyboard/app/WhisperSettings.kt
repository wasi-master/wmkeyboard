package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager.DownloadStatus
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperLanguages
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperModel
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperSize
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import com.wasimaster.wmkeyboard.voice.R as VoiceR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Memory

/** Downloads above this size ask before using mobile data. */
private const val WHISPER_METERED_CONFIRM_BYTES = 150_000_000L

/**
 * The offline-Whisper model manager. Two things are on this screen: which model
 * transcribes each language you type in, and what you can download.
 *
 * There is deliberately no global "use this model" switch. Dictation resolves the
 * model from the language of the active layout — the same way the system
 * recognizer follows the layout's locale — so the meaningful choice is per
 * language, and that is what [WhisperRoutingCard] edits.
 *
 * Download state lives in [WhisperDownloadManager], so navigating away or
 * rotating never loses an in-flight download. Shown only in the full flavor
 * (gated by the caller).
 */
@Composable
internal fun WhisperModelManager(repository: SettingsRepository, settings: KeyboardSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filesDir = context.filesDir
    val states by WhisperDownloadManager.states.collectAsState()
    var storageUsed by remember { mutableLongStateOf(0L) }
    var orphanBytes by remember { mutableLongStateOf(0L) }
    var meteredPending by remember { mutableStateOf<WhisperModel?>(null) }
    var meteredBlocked by remember { mutableStateOf(false) }
    var routingFor by remember { mutableStateOf<LanguageDef?>(null) }
    var browseOpen by remember { mutableStateOf(false) }
    var sizeFilter by remember { mutableStateOf<WhisperSize?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) { WhisperDownloadManager.refresh(filesDir) }
    LaunchedEffect(states) {
        storageUsed = withContext(Dispatchers.IO) { WhisperStore.totalBytesUsed(filesDir) }
        orphanBytes = withContext(Dispatchers.IO) { WhisperStore.orphanBytes(filesDir) }
        // The only model on disk needs no choosing: adopt it as the fallback —
        // covers both "first download just finished" and "the fallback was deleted".
        if (WhisperStore.selectedModel(filesDir, settings.whisper.modelId) == null) {
            WhisperStore.soleDownloadedId(filesDir)?.let { repository.setWhisperModelId(it) }
        }
    }

    fun startDownload(model: WhisperModel) {
        WhisperDownloadManager.start(filesDir, model)
    }

    fun requestDownload(model: WhisperModel) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = cm.isActiveNetworkMetered
        when (downloadDecisionNow(context, settings)) {
            MeteredDecision.BLOCKED -> meteredBlocked = true
            MeteredDecision.ASK -> meteredPending = model
            // Data saving is not holding this one, so the old size threshold
            // is still the guard: a 400 MB model over mobile data is worth a
            // word even from someone who never set any of this up.
            MeteredDecision.ALLOWED ->
                if (metered && model.sizeBytes >= WHISPER_METERED_CONFIRM_BYTES) {
                    meteredPending = model
                } else {
                    startDownload(model)
                }
        }
    }

    // Which Whisper languages the user's enabled layouts actually amount to.
    val enabledCodes = remember(settings.enabledLanguages) {
        settings.enabledLanguages.mapNotNullTo(LinkedHashSet()) {
            WhisperLanguages.codeForLanguage(it.id)
        }
    }

    val onDisk = WhisperCatalog.models.filter {
        (states[it.id] ?: DownloadStatus.NotDownloaded) is DownloadStatus.Downloaded
    }
    // Language id → the model that will actually transcribe it, so both the
    // routing card and the "used for" chips read from one answer.
    val routing = settings.enabledLanguages.associate { language ->
        language.id to WhisperStore.pickForLanguage(
            onDisk, language.id, settings.whisper.modelId, settings.whisper.modelByLang,
        )
    }
    val suggestions = WhisperCatalog.recommendedFor(enabledCodes) - onDisk.toSet()

    @Composable
    fun modelRow(model: WhisperModel) {
        WhisperModelRow(
            model = model,
            status = states[model.id] ?: DownloadStatus.NotDownloaded,
            downloadBusy = WhisperDownloadManager.isBusy,
            enabledCodes = enabledCodes,
            usedFor = settings.enabledLanguages
                .filter { routing[it.id]?.id == model.id }
                .map { it.englishName },
            isFallback = model.id == settings.whisper.modelId,
            expanded = expanded[model.id] == true,
            onToggleExpand = { expanded[model.id] = expanded[model.id] != true },
            onDownload = { requestDownload(model) },
            onCancel = { WhisperDownloadManager.cancel() },
            onDelete = {
                WhisperDownloadManager.delete(filesDir, model)
                scope.launch {
                    repository.clearWhisperModelAssignments(model.id)
                    if (settings.whisper.modelId == model.id) repository.setWhisperModelId("")
                }
            },
        )
    }

    WhisperRoutingCard(
        languages = settings.enabledLanguages,
        routing = routing,
        pinned = settings.whisper.modelByLang,
        anyDownloaded = onDisk.isNotEmpty(),
        onEdit = { routingFor = it },
    )

    WhisperSectionHeader(
        stringResource(R.string.models_whisper_yours_title),
        if (onDisk.isEmpty()) "" else formatBytes(onDisk.sumOf { it.sizeBytes }),
    )
    if (onDisk.isEmpty()) {
        CaptionText(stringResource(R.string.models_whisper_empty))
    } else {
        SettingsGroup {
            for (model in onDisk) item { modelRow(model) }
        }
    }

    // Which model answers for any language without a per-language pin. The
    // value was only ever written by adopting a sole download or clearing a
    // deleted one, so the docs listed a setting that no control wrote.
    if (onDisk.size > 1) {
        SettingsGroup(stringResource(R.string.models_whisper_fallback_title)) {
            item {
                val autoLabel = stringResource(R.string.models_whisper_fallback_auto)
                ChoiceSetting(
                    title = R.string.models_whisper_fallback_title,
                    subtitle = stringResource(R.string.models_whisper_fallback_subtitle),
                    options = listOf<Pair<String, String>>("" to autoLabel) +
                        onDisk.map { it.id to it.displayName },
                    selected = settings.whisper.modelId,
                    info = stringResource(R.string.models_whisper_fallback_info),
                    default = SettingsDefaults.whisper.modelId,
                    // "" is the automatic pick; everything after it is a model
                    // sitting on this device.
                    detail = { id ->
                        ChoiceDetail(
                            icon = if (id.isEmpty()) Icons.Outlined.AutoMode
                            else Icons.Outlined.Memory,
                        )
                    },
                ) { id -> scope.launch { repository.setWhisperModelId(id) } }
            }
        }
    }

    if (suggestions.isNotEmpty()) {
        WhisperSectionHeader(stringResource(R.string.models_whisper_suggested_title), "")
        SettingsGroup {
            for (model in suggestions) item { modelRow(model) }
        }
    }

    WhisperBrowseSection(
        open = browseOpen,
        onToggle = { browseOpen = !browseOpen },
        visible = WhisperCatalog.visibleFor(enabledCodes),
        sizeFilter = sizeFilter,
        onSizeFilter = { sizeFilter = it },
        row = { modelRow(it) },
    )

    if (storageUsed > 0) {
        CaptionText(
            stringResource(R.string.models_whisper_storage_info, formatBytes(storageUsed)),
        )
    }
    if (orphanBytes > 0) {
        CaptionText(
            stringResource(R.string.models_whisper_orphan_info, formatBytes(orphanBytes)),
        )
        val freeUpLabel = stringResource(R.string.models_free_up_action, formatBytes(orphanBytes))
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { WhisperStore.deleteOrphans(filesDir) }
                    orphanBytes = 0
                    storageUsed = withContext(Dispatchers.IO) { WhisperStore.totalBytesUsed(filesDir) }
                }
            }) { Text(freeUpLabel) }
        }
    }

    routingFor?.let { language ->
        WhisperRoutingDialog(
            language = language,
            downloaded = onDisk,
            resolved = routing[language.id],
            pinnedId = settings.whisper.modelByLang[language.id],
            onPick = { id ->
                scope.launch { repository.setWhisperModelForLanguage(language.id, id) }
                routingFor = null
            },
            onDismiss = { routingFor = null },
        )
    }

    meteredPending?.let { model ->
        AlertDialog(
            onDismissRequest = { meteredPending = null },
            title = { Text(stringResource(R.string.models_metered_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.models_metered_body,
                        model.displayName,
                        formatBytes(model.sizeBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    startDownload(model)
                    meteredPending = null
                }) { Text(stringResource(R.string.models_metered_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { meteredPending = null }) {
                    Text(stringResource(R.string.models_metered_dismiss_action))
                }
            },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog { meteredBlocked = false }
}

/**
 * One row per enabled language, each naming the model that will transcribe it.
 * This replaces the old single "use this model" selection: dictation picks the
 * model from the language of the active layout, so the choice only means anything
 * per language.
 *
 * The failure this surfaces is a model transcribing a language it was not built
 * for — that produces confident wrong words rather than an error, so a language
 * its model cannot handle is called out in the error colour.
 */
@Composable
private fun WhisperRoutingCard(
    languages: List<LanguageDef>,
    routing: Map<String, WhisperModel?>,
    pinned: Map<String, String>,
    anyDownloaded: Boolean,
    onEdit: (LanguageDef) -> Unit,
) {
    if (languages.isEmpty()) return
    SettingsGroup(
        stringResource(R.string.models_whisper_per_language_title),
        info = stringResource(R.string.models_whisper_routing_info),
    ) {
        for (language in languages) {
            item {
                val code = WhisperLanguages.codeForLanguage(language.id)
                val model = routing[language.id]
                val covered = code != null && model != null && model.covers(code)
                val detail = when {
                    code == null -> stringResource(R.string.models_whisper_language_none)
                    model == null -> stringResource(R.string.models_whisper_no_model_yet)
                    !covered -> stringResource(
                        R.string.models_whisper_language_not_covered,
                        model.displayName,
                    )
                    pinned[language.id] == model.id -> stringResource(
                        R.string.models_whisper_language_pinned,
                        model.displayName,
                    )
                    else -> stringResource(
                        R.string.models_whisper_language_auto,
                        model.displayName,
                    )
                }
                // The catalogue's language lists are compiled into the graphs, so
                // a language absent from all of them can only ever be guessed at.
                // That failure is silent and total, which makes it worth a line of
                // its own rather than leaving it to be discovered in use.
                val detectOnly = code != null && WhisperCatalog.autoDetectOnly(code)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .then(
                            if (code != null && anyDownloaded) {
                                Modifier.clickable { onEdit(language) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(language.englishName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (code != null && model != null && !covered) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (detectOnly && model != null) {
                            Text(
                                stringResource(
                                    R.string.models_whisper_language_detect_only,
                                    language.englishName,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (code != null && anyDownloaded) {
                        Text(
                            stringResource(R.string.models_whisper_change_action),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Picks the model for one language: automatic, or a specific downloaded one. */
@Composable
private fun WhisperRoutingDialog(
    language: LanguageDef,
    downloaded: List<WhisperModel>,
    resolved: WhisperModel?,
    pinnedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val code = WhisperLanguages.codeForLanguage(language.id) ?: return
    // Ones that can actually do this language first; the rest stay pickable but
    // are labelled, since choosing one is a mistake worth naming rather than hiding.
    val ordered = WhisperCatalog.rankedFor(code, downloaded) +
        downloaded.filterNot { it.covers(code) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.models_whisper_routing_dialog_title,
                    language.englishName,
                ),
            )
        },
        text = {
            val currentDetail = if (resolved == null) {
                stringResource(R.string.models_whisper_no_model_yet)
            } else {
                stringResource(R.string.models_whisper_routing_current, resolved.displayName)
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                WhisperRoutingOption(
                    title = stringResource(CommonR.string.common_auto),
                    detail = currentDetail,
                    selected = pinnedId == null,
                    onClick = { onPick("") },
                )
                for (model in ordered) {
                    WhisperRoutingOption(
                        title = model.displayName,
                        detail = when {
                            !model.covers(code) -> stringResource(
                                R.string.models_whisper_option_not_covered,
                                language.englishName,
                            )
                            model.fixedLang == code -> stringResource(
                                R.string.models_whisper_option_single,
                                language.englishName,
                            )
                            model.selectableLang -> stringResource(
                                R.string.models_whisper_option_forced,
                                language.englishName,
                            )
                            else -> stringResource(R.string.models_whisper_option_auto_detect)
                        },
                        selected = pinnedId == model.id,
                        warn = !model.covers(code),
                        onClick = { onPick(model.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) }
        },
    )
}

@Composable
private fun WhisperRoutingOption(
    title: String,
    detail: String,
    selected: Boolean,
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (warn) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The catalog behind an expander, split by whether a model handles any language
 * or exactly one. Single-language entries only appear for languages that are
 * actually enabled — see [WhisperCatalog.visibleFor] — so this list grows with
 * the Languages screen instead of listing graphs nothing would ever route to.
 */
// `row` is a per-model renderer, not a single content slot: it is invoked once
// per item across two disjoint lists, so there is no instance whose state could
// move between call sites. SlotReused targets the single-instance case.
@Suppress("SlotReused")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhisperBrowseSection(
    open: Boolean,
    onToggle: () -> Unit,
    visible: List<WhisperModel>,
    sizeFilter: WhisperSize?,
    onSizeFilter: (WhisperSize?) -> Unit,
    row: @Composable (WhisperModel) -> Unit,
) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "whisperBrowseChevron")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            pluralStringResource(
                if (open) R.plurals.models_whisper_catalog_open
                else R.plurals.models_whisper_catalog_closed,
                visible.size,
                visible.size,
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (open) {
                stringResource(R.string.models_catalog_hide_desc)
            } else {
                stringResource(R.string.models_catalog_show_desc)
            },
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).rotate(turn),
        )
    }
    if (!open) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        for (size in WhisperSize.entries) {
            FilterChip(
                selected = sizeFilter == size,
                onClick = { onSizeFilter(if (sizeFilter == size) null else size) },
                label = { Text(stringResource(size.labelRes)) },
            )
        }
    }

    val shown = visible.filter { sizeFilter == null || it.size == sizeFilter }
    val anyLanguage = shown.filter { it.fixedLang == null }
    val oneLanguage = shown.filter { it.fixedLang != null }
    if (shown.isEmpty()) {
        CaptionText(stringResource(R.string.models_whisper_no_size_match))
    }
    if (anyLanguage.isNotEmpty()) {
        WhisperSectionHeader(
            stringResource(R.string.models_whisper_group_any_language_title),
            "",
        )
        SettingsGroup {
            for (model in anyLanguage) item { row(model) }
        }
    }
    if (oneLanguage.isNotEmpty()) {
        WhisperSectionHeader(
            stringResource(R.string.models_whisper_group_one_language_title),
            "",
        )
        SettingsGroup {
            for (model in oneLanguage) item { row(model) }
        }
    }
    CaptionText(stringResource(R.string.models_whisper_sizes_info))
}

@Composable
private fun WhisperSectionHeader(title: String, trailing: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (trailing.isNotEmpty()) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One catalog entry: name, a chip row carrying the facts that used to run
 * together in a paragraph-long subtitle, and details on tap. Which languages a
 * downloaded model serves is shown here but chosen in [WhisperRoutingCard].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhisperModelRow(
    model: WhisperModel,
    status: DownloadStatus,
    downloadBusy: Boolean,
    enabledCodes: Set<String>,
    usedFor: List<String>,
    isFallback: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val inUse = status is DownloadStatus.Downloaded && usedFor.isNotEmpty()
    val background by animateColorAsState(
        if (inUse) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else Color.Transparent,
        animationSpec = tween(300),
        label = "whisperRowBackground",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onToggleExpand)
            .animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (inUse) FontWeight.SemiBold else null,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (inUse) {
                        WhisperChip(
                            stringResource(
                                R.string.models_whisper_chip_used_for,
                                usedFor.joinToString(", "),
                            ),
                            WhisperChipTone.PRIMARY,
                        )
                    }
                    val badgeRes = model.tier.badgeRes
                    if (badgeRes != null && !inUse) {
                        WhisperChip(stringResource(badgeRes), WhisperChipTone.PRIMARY)
                    }
                    WhisperChip(stringResource(model.sizeLabelRes), WhisperChipTone.NEUTRAL)
                    // A model built for one language names it; the rest count.
                    // Language names are proper nouns and are not translated.
                    val singleName = model.singleLanguageName
                    WhisperChip(
                        singleName ?: pluralStringResource(
                            VoiceR.plurals.core_voice_model_language_count,
                            model.languageCount,
                            model.languageCount,
                        ),
                        WhisperChipTone.NEUTRAL,
                    )
                    if (model.selectableLang) {
                        WhisperChip(
                            stringResource(R.string.models_whisper_chip_language_forced),
                            WhisperChipTone.NEUTRAL,
                        )
                    }
                    WhisperChip(formatBytes(model.sizeBytes), WhisperChipTone.NEUTRAL)
                }
            }
            when (status) {
                is DownloadStatus.Downloaded -> IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(
                            R.string.models_delete_model_desc,
                            model.displayName,
                        ),
                    )
                }
                is DownloadStatus.Downloading -> IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.models_cancel_download_desc),
                    )
                }
                is DownloadStatus.Paused -> TextButton(onClick = onDownload, enabled = !downloadBusy) {
                    Text(stringResource(R.string.models_resume_action))
                }
                is DownloadStatus.NotDownloaded, is DownloadStatus.Failed ->
                    TextButton(onClick = onDownload, enabled = !downloadBusy) {
                        Text(
                            if (status is DownloadStatus.Failed) {
                                stringResource(CommonR.string.common_retry)
                            } else {
                                stringResource(CommonR.string.common_download)
                            },
                        )
                    }
            }
        }

        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
                Text(
                    if (model.descriptionArg.isEmpty()) stringResource(model.descriptionRes)
                    else stringResource(model.descriptionRes, model.descriptionArg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WhisperLanguageDetail(model, enabledCodes, isFallback)
            }
        }

        when (status) {
            is DownloadStatus.NotDownloaded, is DownloadStatus.Downloaded -> Unit
            is DownloadStatus.Downloading -> WhisperDownloadProgress(status.bytes, status.total)
            is DownloadStatus.Paused -> CaptionText(
                stringResource(R.string.models_paused_progress, formatBytes(status.bytes)),
            )
            is DownloadStatus.Failed -> CaptionText(
                if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                else stringResource(status.messageRes, status.messageArg),
                error = true,
            )
        }
    }
}

/** The expanded row's language line: what it covers, and how that lands against your set. */
@Composable
private fun WhisperLanguageDetail(
    model: WhisperModel,
    enabledCodes: Set<String>,
    isFallback: Boolean,
) {
    val missed = enabledCodes.filterNot { model.covers(it) }
    // Resolved before the list is built: buildList's lambda is not composable.
    val coversLine = stringResource(
        R.string.models_whisper_detail_covers,
        WhisperLanguages.labels(model.langCodes).joinToString(", "),
    )
    val allYoursLine = stringResource(R.string.models_whisper_detail_all_yours)
    val missingLine = stringResource(
        R.string.models_whisper_detail_missing,
        WhisperLanguages.labels(missed).joinToString(", "),
    )
    val translateLine = stringResource(R.string.models_whisper_detail_translate)
    val fallbackLine = stringResource(R.string.models_whisper_detail_fallback)
    val lines = buildList {
        if (model.langCodes.size > 1) add(coversLine)
        if (enabledCodes.isNotEmpty()) {
            if (missed.isEmpty()) add(allYoursLine) else add(missingLine)
        }
        if (model.supportsTranslate) add(translateLine)
        if (isFallback) add(fallbackLine)
    }
    if (lines.isEmpty()) return
    Text(
        lines.joinToString(" "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

private enum class WhisperChipTone { NEUTRAL, PRIMARY }

/** A small fact chip — the row's metadata reads as chips instead of a run-on subtitle. */
@Composable
private fun WhisperChip(text: String, tone: WhisperChipTone) {
    val container = when (tone) {
        WhisperChipTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        WhisperChipTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when (tone) {
        WhisperChipTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        WhisperChipTone.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun WhisperDownloadProgress(bytes: Long, total: Long) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    R.string.models_download_progress,
                    formatBytes(bytes),
                    formatBytes(total),
                    bytes * 100 / total,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                formatBytes(bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

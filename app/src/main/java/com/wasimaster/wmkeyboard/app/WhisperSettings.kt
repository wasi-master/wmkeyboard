package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.content.Context
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
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
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCompute
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCppGpu
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager.DownloadStatus
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperEngine
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperLanguages
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperModel
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperSize
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import com.wasimaster.wmkeyboard.voice.R as VoiceR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Memory
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speed
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState

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
 * rotating never loses an in-flight download. Shown only where Whisper can
 * run (gated by the caller).
 */
@Composable
internal fun WhisperModelManager(repository: SettingsRepository, settings: LiveSettings) {
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

    val notifyDownload = rememberDownloadNotifier()

    LaunchedEffect(Unit) { WhisperDownloadManager.refresh(filesDir) }
    LaunchedEffect(states) {
        storageUsed = withContext(Dispatchers.IO) { WhisperStore.totalBytesUsed(filesDir) }
        orphanBytes = withContext(Dispatchers.IO) { WhisperStore.orphanBytes(filesDir) }
        // The only model on disk needs no choosing: adopt it as the fallback —
        // covers both "first download just finished" and "the fallback was deleted".
        if (WhisperStore.selectedModel(filesDir, settings.value.whisper.modelId) == null) {
            WhisperStore.soleDownloadedId(filesDir)?.let { repository.setWhisperModelId(it) }
        }
    }

    fun startDownload(model: WhisperModel) {
        WhisperDownloadManager.start(filesDir, model)
        // These are the app's slowest downloads by far, and nobody watches a
        // 400 MB transfer on the screen that started it.
        notifyDownload(
            model.id,
            context.getString(R.string.notify_download_voice_model, model.displayName),
            DownloadProgressFlows.whisper(context, model.id),
        )
    }

    fun requestDownload(model: WhisperModel) {
        val metered = isMeteredNow(context)
        when (downloadDecisionNow(context, settings.value)) {
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

    // The routing below answers for every language at once, and the routing
    // card, the model rows and the dialog all read that one answer, so what it
    // is worked out from is read here.
    val enabledLanguages = settings.watch { it.enabledLanguages }
    val fallbackId = settings.watch { it.whisper.modelId }
    val pinnedByLang = settings.watch { it.whisper.modelByLang }

    // Which Whisper languages the user's enabled layouts actually amount to.
    val enabledCodes = remember(enabledLanguages) {
        enabledLanguages.mapNotNullTo(LinkedHashSet()) {
            WhisperLanguages.codeForLanguage(it.id)
        }
    }

    val onDisk = WhisperCatalog.models.filter {
        (states[it.id] ?: DownloadStatus.NotDownloaded) is DownloadStatus.Downloaded
    }
    // Language id → the model that will actually transcribe it, so both the
    // routing card and the "used for" chips read from one answer.
    val routing = enabledLanguages.associate { language ->
        language.id to WhisperStore.pickForLanguage(
            onDisk, language.id, fallbackId, pinnedByLang,
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
            usedFor = enabledLanguages
                .filter { routing[it.id]?.id == model.id }
                .map { it.englishName },
            isFallback = model.id == fallbackId,
            expanded = expanded[model.id] == true,
            onToggleExpand = { expanded[model.id] = expanded[model.id] != true },
            onDownload = { requestDownload(model) },
            onCancel = { WhisperDownloadManager.cancel() },
            onDelete = {
                WhisperDownloadManager.delete(filesDir, model)
                scope.launch {
                    repository.clearWhisperModelAssignments(model.id)
                    if (settings.value.whisper.modelId == model.id) repository.setWhisperModelId("")
                }
            },
        )
    }

    // A Play install carries the LiteRT interpreter as an on-demand module.
    // The LiteRT graphs below can be fetched without it, but none transcribes
    // until it is here, so it is the first thing on the screen while it is
    // not. whisper.cpp is in the app itself, so where it runs the module only
    // matters once a LiteRT graph is on the device.
    val module by WhisperEngine.moduleState.collectAsState()
    var moduleMetered by remember { mutableStateOf(false) }
    val needsModule = !WhisperCatalog.cppAvailable || onDisk.any { !it.ggml }
    if (module != ModuleState.Installed && needsModule) {
        WhisperModuleBanner(
            module = module,
            gateId = "litert",
            onDownload = {
                when (downloadDecisionNow(context, settings.value)) {
                    MeteredDecision.ALLOWED -> WhisperEngine.requestModule()
                    MeteredDecision.ASK -> moduleMetered = true
                    MeteredDecision.BLOCKED -> meteredBlocked = true
                }
            },
        )
        if (moduleMetered) {
            MeteredDownloadDialog(
                detail = stringResource(R.string.models_whisper_module_metered_body),
                onConfirm = {
                    moduleMetered = false
                    WhisperEngine.requestModule()
                },
                onDismiss = { moduleMetered = false },
            )
        }
    }

    // whisper.cpp's own libraries are an on-demand part too on Play, shared
    // with the ExecuTorch engines, so they are asked for once a GGML model is
    // on the device.
    if (onDisk.any { it.ggml }) SpeechModuleBanner(settings)

    WhisperRoutingCard(
        languages = enabledLanguages,
        routing = routing,
        pinned = pinnedByLang,
        anyDownloaded = onDisk.isNotEmpty(),
        onEdit = { routingFor = it },
    )

    SectionHeader(
        stringResource(R.string.models_whisper_yours_title),
        trailing = if (onDisk.isEmpty()) null else formatBytes(onDisk.sumOf { it.sizeBytes }),
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
        SettingsGroup(stringResource(R.string.models_whisper_fallback_title), level = SettingLevel.ADVANCED) {
            item {
                val autoLabel = stringResource(R.string.models_whisper_fallback_auto)
                ChoiceSetting(
                    title = R.string.models_whisper_fallback_title,
                    subtitle = stringResource(R.string.models_whisper_fallback_subtitle),
                    options = listOf<Pair<String, String>>("" to autoLabel) +
                        onDisk.map { it.id to it.displayName },
                    selected = settings.watch { it.whisper.modelId },
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

    // Where the GGML models run. Only once one is on the phone: the LiteRT
    // graphs always run on the CPU.
    if (onDisk.any { it.ggml }) WhisperComputeGroup(repository, settings)

    if (suggestions.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.models_whisper_suggested_title)) {
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

    // For a phone that cannot fetch a model itself: the same two files per
    // model, fetched elsewhere and imported. Suggested models first, then the
    // rest of what the browse list shows, never one already on the device.
    val offlineLinks = remember(enabledCodes, onDisk) {
        val wanted = (suggestions + WhisperCatalog.visibleFor(enabledCodes)).distinct() - onDisk.toSet()
        val vocabSeen = HashSet<String>()
        wanted.flatMap { model ->
            listOfNotNull(
                OfflineLink(
                    model.displayName,
                    WhisperCatalog.downloadUrl(model.repo, model.modelFile, model.revision),
                ),
                // A whisper.cpp model is one file; a LiteRT graph needs its vocab.
                if (!model.ggml && vocabSeen.add(model.vocabFile)) {
                    OfflineLink(
                        context.getString(R.string.offline_import_whisper_vocab_label, model.vocabFile),
                        WhisperCatalog.downloadUrl(model.vocabRepo, model.vocabFile, model.revision),
                    )
                } else {
                    null
                },
            )
        }
    }
    SettingsGroup {
        item(level = offlineImportLevel) {
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_whisper_subtitle),
                links = offlineLinks,
                onImported = { WhisperDownloadManager.refresh(filesDir) },
            )
        }
    }

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
            pinnedId = pinnedByLang[language.id],
            onPick = { id ->
                scope.launch { repository.setWhisperModelForLanguage(language.id, id) }
                routingFor = null
            },
            onDismiss = { routingFor = null },
        )
    }

    meteredPending?.let { model ->
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.models_metered_body,
                model.displayName,
                formatBytes(model.sizeBytes),
            ),
            onConfirm = {
                startDownload(model)
                meteredPending = null
            },
            onDismiss = { meteredPending = null },
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
/**
 * Compute for the GGML models: Automatic, CPU or GPU. The subtitle says what
 * this phone's GPU amounts to; choosing GPU forgets an earlier failure there,
 * so the next dictation tries it again.
 */
@Composable
private fun WhisperComputeGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val selected = settings.watch { it.whisper.compute }
    // Read again whenever the choice changes: choosing GPU clears a failure.
    val support = remember(selected) { WhisperCppGpu.support }
    val subtitle = when (support) {
        WhisperCppGpu.Support.NO_GPU -> stringResource(R.string.models_whisper_compute_no_gpu)
        WhisperCppGpu.Support.FAILED -> stringResource(R.string.models_whisper_compute_failed)
        WhisperCppGpu.Support.READY -> WhisperCppGpu.deviceName
            ?.let { stringResource(R.string.models_whisper_compute_subtitle_named, it) }
            ?: stringResource(R.string.models_whisper_compute_subtitle)
    }
    SettingsGroup(stringResource(R.string.models_whisper_compute_title), level = SettingLevel.ADVANCED) {
        item {
            ChoiceSetting(
                title = R.string.models_whisper_compute_title,
                subtitle = subtitle,
                options = listOf(
                    WhisperCompute.AUTO to stringResource(R.string.models_whisper_compute_auto),
                    WhisperCompute.CPU to stringResource(R.string.models_whisper_compute_cpu),
                    WhisperCompute.GPU to stringResource(R.string.models_whisper_compute_gpu),
                ),
                selected = selected,
                info = stringResource(R.string.models_whisper_compute_info),
                default = SettingsDefaults.whisper.compute,
                detail = { choice ->
                    ChoiceDetail(
                        icon = when (choice) {
                            WhisperCompute.CPU -> Icons.Outlined.Memory
                            WhisperCompute.GPU -> Icons.Outlined.Speed
                            else -> Icons.Outlined.AutoMode
                        },
                    )
                },
            ) { choice ->
                if (choice == WhisperCompute.GPU) WhisperCppGpu.forgetFailures()
                scope.launch { repository.setWhisperCompute(choice) }
            }
        }
    }
}

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
        level = SettingLevel.STANDARD,
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
                val editable = code != null && anyDownloaded
                WmRow(
                    title = language.englishName,
                    supporting = {
                        Column {
                            Text(
                                detail,
                                color = if (code != null && model != null && !covered) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (detectOnly && model != null) {
                                Text(
                                    stringResource(
                                        R.string.models_whisper_language_detect_only,
                                        language.englishName,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    },
                    trailing = if (editable) {
                        {
                            Text(
                                stringResource(R.string.models_whisper_change_action),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = if (editable) {
                        { onEdit(language) }
                    } else {
                        null
                    },
                )
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

    val rail = rememberScrollRailState()
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
            ScrollRail(state = rail, modifier = Modifier.heightIn(max = 420.dp)) {
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
        SettingsGroup(stringResource(R.string.models_whisper_group_any_language_title)) {
            for (model in anyLanguage) item { row(model) }
        }
    }
    if (oneLanguage.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.models_whisper_group_one_language_title)) {
            for (model in oneLanguage) item { row(model) }
        }
    }
    CaptionText(stringResource(R.string.models_whisper_sizes_info))
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
        WmRow(
            title = model.displayName,
            titleContent = {
                Text(model.displayName, fontWeight = if (inUse) FontWeight.SemiBold else null)
            },
            swipeActions = if (status is DownloadStatus.Downloaded) {
                listOf(
                    deleteSwipeAction(
                        stringResource(R.string.models_delete_model_desc, model.displayName),
                        onDelete,
                    ),
                )
            } else {
                emptyList()
            },
            supporting = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp),
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
            },
            trailing = {
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
                        if (InternetPermission.granted) TextButton(onClick = onDownload, enabled = !downloadBusy) {
                            Text(
                                if (status is DownloadStatus.Failed) {
                                    stringResource(CommonR.string.common_retry)
                                } else {
                                    stringResource(CommonR.string.common_download)
                                },
                            )
                        }
                }
            },
        )

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
    val cppLine = stringResource(R.string.models_whisper_detail_cpp)
    val fallbackLine = stringResource(R.string.models_whisper_detail_fallback)
    val lines = buildList {
        if (model.langCodes.size > 1) add(coversLine)
        if (enabledCodes.isNotEmpty()) {
            if (missed.isEmpty()) add(allYoursLine) else add(missingLine)
        }
        if (model.supportsTranslate) add(translateLine)
        if (model.ggml) add(cppLine)
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

/**
 * A native library part's own download, on a build that fetches it rather than
 * carrying it: [gate] is `:feature:speech` (whisper.cpp and Whistle) by
 * default, or `:feature:executorch` (the ExecuTorch runtime) for Asr4All,
 * Parakeet, Moonshine and the FUTO Swipe glide engine. Nothing where the
 * libraries are in the APK. Asks the way the interpreter's banner does.
 * [gateId] is [gate]'s id in `native/gates.json`, which names the files the
 * offline build's banner sends the user for.
 */
@Composable
internal fun SpeechModuleBanner(
    settings: LiveSettings,
    gate: ModuleGate = FeatureModules.speech,
    gateId: String = "speech",
) {
    val context = LocalContext.current
    val module by gate.state.collectAsState()
    if (module == ModuleState.Installed) return
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    WhisperModuleBanner(module, gateId) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> gate.requestInstall()
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.models_whisper_module_metered_body),
            onConfirm = {
                metered = false
                gate.requestInstall()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/**
 * ONNX Runtime and the sherpa-onnx bridge (`:feature:onnx` on Play, the `onnx`
 * gate elsewhere), on an install that has not fetched them. Nothing where the
 * APK carries them. The same banner and questions as [SpeechModuleBanner].
 */
@Composable
internal fun OnnxModuleBanner(settings: LiveSettings) {
    val context = LocalContext.current
    val module by SherpaNative.moduleState.collectAsState()
    if (module == ModuleState.Installed) return
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    WhisperModuleBanner(module, "onnx") {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> SherpaNative.requestModule()
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.models_whisper_module_metered_body),
            onConfirm = {
                metered = false
                SherpaNative.requestModule()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/**
 * The interpreter's own download, on a Play install that has not fetched it.
 * Live state with the button that fixes it, the same shape as the translate
 * screen's module banner. [gateId] is the gate whose missing files the offline
 * build's banner lists: the library this banner is about, not always LiteRT
 * (#946).
 */
@Composable
private fun WhisperModuleBanner(module: ModuleState, gateId: String, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (module) {
        is ModuleState.Installing -> StateBanner(
            if (module.totalBytes > 0L) {
                stringResource(
                    R.string.models_whisper_module_installing_sized_info,
                    Formatter.formatShortFileSize(context, module.bytes),
                    Formatter.formatShortFileSize(context, module.totalBytes),
                )
            } else {
                stringResource(R.string.models_whisper_module_installing_info)
            },
        )
        // F-Droid, before the user has opted in to library downloads: a
        // retry would only stop at the same place, so say where to turn it on.
        is ModuleState.Missing if module.consent != null -> NativeConsentBanner(module.consent.orEmpty(), gateId)
        is ModuleState.Missing -> StateBanner(
            stringResource(
                if (module.failed) {
                    R.string.models_whisper_module_failed_info
                } else {
                    R.string.models_whisper_module_missing_info
                },
            ),
            action = stringResource(
                if (module.failed) CommonR.string.common_retry else CommonR.string.common_download,
            ),
            tone = if (module.failed) BannerTone.WARNING else BannerTone.INFO,
            onAction = onDownload,
        )
        ModuleState.Installed -> Unit
    }
}

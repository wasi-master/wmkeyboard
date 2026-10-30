package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.app.ActivityManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.localllm.GgufFile
import com.wasimaster.wmkeyboard.core.localllm.LlamaCpp
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager.DownloadStatus
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager.FailReason
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmModel
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmRuntime
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.localllm.ModelFormat
import com.wasimaster.wmkeyboard.core.localllm.ModelTier
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads above this size ask before using mobile data. */
private const val METERED_CONFIRM_BYTES = 500_000_000L

/**
 * The On-device provider's section of the AI tool settings: Hugging Face
 * token, the downloadable model catalog with live progress, imported custom
 * models, and the compute backend. Download state lives in
 * [LocalLlmDownloadManager], so navigating away or rotating never loses an
 * in-flight download.
 */
@Composable
internal fun LocalLlmModelManager(repository: SettingsRepository, settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val filesDir = context.filesDir
    val states by LocalLlmDownloadManager.states.collectAsState()
    var customModels by remember { mutableStateOf(LocalLlmStore.customModels(filesDir)) }
    var storageUsed by remember { mutableLongStateOf(0L) }
    var orphanBytes by remember { mutableLongStateOf(0L) }
    var meteredPending by remember { mutableStateOf<Pair<LocalLlmModel, String?>?>(null) }
    var meteredBlocked by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    // This phone's NPU target, or null; the NPU chip and builds only exist with one (#804).
    val npuSoc = NpuSupport.soc
    // Which builds of each catalog model are on disk, as (standard, NPU):
    // a model can have one, the other or both, and a row offers the missing
    // one when the Compute choice wants it.
    var builds by remember { mutableStateOf<Map<String, Pair<Boolean, Boolean>>>(emptyMap()) }

    LaunchedEffect(Unit) { LocalLlmDownloadManager.refresh(filesDir) }
    LaunchedEffect(states, customModels, importing) {
        // Runs after every delete: what the engine built for a model is gone with it.
        withContext(Dispatchers.IO) { LocalLlmStore.pruneEngineCache(filesDir, context.cacheDir) }
        storageUsed = withContext(Dispatchers.IO) { LocalLlmStore.totalBytesUsed(filesDir) }
        orphanBytes = withContext(Dispatchers.IO) { LocalLlmStore.orphanBytes(filesDir) }
        builds = withContext(Dispatchers.IO) {
            LocalLlmCatalog.models.associate { model ->
                val npuOnDisk = LocalLlmStore.npuFile(filesDir, model, npuSoc) != null
                model.id to (LocalLlmStore.hasStandard(filesDir, model) to npuOnDisk)
            }
        }
        // The only model on disk needs no selection step: adopt it — covers
        // both "first download just finished" and "selection was deleted".
        if (LocalLlmStore.selectedModelFile(filesDir, settings.value.ai.localModelId) == null) {
            LocalLlmStore.soleDownloadedId(filesDir)?.let { repository.setAiLocalModelId(it) }
        }
    }

    val notifyDownload = rememberDownloadNotifier()

    val totalRamMb = remember {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt()
    }

    // A download with Compute on NPU fetches the model's NPU build for this
    // chip, when it has one; the second-build buttons below pass their own.
    fun npuTarget(): String? = npuSoc?.takeIf { settings.value.ai.localBackend == LocalLlmBackend.NPU }

    fun startDownload(model: LocalLlmModel, target: String?) {
        // A GGUF needs llama.cpp to run: fetched alongside, where the build
        // delivers it on demand, so both are there when the model is.
        if (model.runtime == LocalLlmRuntime.LLAMA_CPP) LlamaCpp.requestModule()
        LocalLlmDownloadManager.start(filesDir, model, settings.value.ai.hfToken, target)
        notifyDownload(
            model.id,
            context.getString(R.string.notify_download_ai_model, model.displayName),
            DownloadProgressFlows.localLlm(context, model.id),
        )
    }

    fun requestDownload(model: LocalLlmModel, target: String? = npuTarget()) {
        val metered = isMeteredNow(context)
        val size = LocalLlmCatalog.downloadSize(model, target)
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.BLOCKED -> meteredBlocked = true
            MeteredDecision.ASK -> meteredPending = model to target
            // Not held by data saving, so the old size threshold still stands:
            // these are the largest downloads the app makes.
            MeteredDecision.ALLOWED ->
                if (metered && size >= METERED_CONFIRM_BYTES) {
                    meteredPending = model to target
                } else {
                    startDownload(model, target)
                }
        }
    }

    SettingsGroup(stringResource(R.string.models_llm_account_title)) {
        item {
            ApiKeyField(
                label = stringResource(R.string.models_llm_token_label),
                value = settings.watch { it.ai.hfToken },
                builtInAvailable = false,
                emptyHint = stringResource(R.string.models_llm_token_hint),
            ) { repository.setHfToken(it) }
        }
        item {
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { uriHandler.openUri(LocalLlmCatalog.TOKEN_URL) }) {
                    Text(stringResource(R.string.models_llm_get_token_action))
                }
            }
        }
    }

    // Which model is in use decides the order the rows below are listed in;
    // each row reads its own value.
    val selectedId = settings.watch { it.ai.localModelId }
    // On disk (ready to use) vs still on Hugging Face — two questions the
    // one mixed list used to answer badly. Anything mid-download counts as
    // "available" so its progress bar stays with the thing being fetched.
    // A model fetching its second build stays where it is.
    val onDisk = LocalLlmCatalog.models.filter {
        (states[it.id] ?: DownloadStatus.NotDownloaded) is DownloadStatus.Downloaded ||
            builds[it.id]?.let { (standard, npu) -> standard || npu } == true
    }
    // Selected model to the very top of its section — it's the one answer
    // "which model am I using?" needs, and scrolling for it is silly.
    val yours = onDisk.sortedByDescending { it.id == selectedId }
    // Two lists, one per runtime: LiteRT-LM's first, as the default, and the
    // GGUF models llama.cpp runs below it, on a build that has llama.cpp.
    val available = LocalLlmCatalog.models - onDisk.toSet()
    val availableLitert = available.filter { it.runtime == LocalLlmRuntime.LITERT_LM }
    val availableGguf = if (LlamaCpp.shipped) available.filter { it.runtime == LocalLlmRuntime.LLAMA_CPP } else emptyList()

    val selectedBackend = settings.watch { it.ai.localBackend }

    @Composable
    fun catalogRow(model: LocalLlmModel) {
        val (hasStandard, hasNpu) = builds[model.id] ?: (false to false)
        val npuBuild = LocalLlmCatalog.npuBuild(model, npuSoc)
        val wantsNpu = selectedBackend == LocalLlmBackend.NPU && npuBuild != null
        // The build a row is missing, when the Compute choice needs it: the
        // NPU build with Compute on NPU, the standard one otherwise (only the
        // NPU can run an NPU build). Null when there is nothing to add.
        val secondBuild = when {
            !hasStandard && !hasNpu -> null
            wantsNpu && !hasNpu -> stringResource(
                R.string.models_llm_get_npu_build_action,
                formatBytes(npuBuild!!.sizeBytes),
            ) to { requestDownload(model, npuSoc) }
            !wantsNpu && !hasStandard -> stringResource(
                R.string.models_llm_get_standard_build_action,
                formatBytes(model.downloadBytes),
            ) to { requestDownload(model, null) }
            else -> null
        }
        CatalogModelRow(
            model = model,
            sizeBytes = if (wantsNpu) npuBuild!!.sizeBytes else model.downloadBytes,
            npuBuildOnDisk = hasNpu,
            secondBuild = secondBuild,
            status = states[model.id] ?: DownloadStatus.NotDownloaded,
            selected = settings.watch { it.ai.localModelId == model.id },
            hasToken = settings.watch { it.ai.hfToken.isNotBlank() },
            downloadBusy = LocalLlmDownloadManager.isBusy,
            tooBigForRam = model.minRamMb > totalRamMb,
            onDownload = { requestDownload(model) },
            onCancel = { LocalLlmDownloadManager.cancel() },
            onSelect = { scope.launch { repository.setAiLocalModelId(model.id) } },
            onDelete = {
                LocalLlmDownloadManager.delete(filesDir, model)
                if (settings.value.ai.localModelId == model.id) {
                    scope.launch { repository.setAiLocalModelId("") }
                }
            },
            onOpenLicense = { uriHandler.openUri(LocalLlmCatalog.licenseUrl(model)) },
        )
    }

    // Combined size of what's on disk, shown beside the header so "how much
    // is this costing me?" is answerable at a glance. Sums the same per-row
    // numbers the user sees (catalog sizes + custom file lengths), unlike the
    // storage caption below which also counts partial and orphaned files.
    if (yours.isNotEmpty() || customModels.isNotEmpty()) {
        val downloadedBytes = yours.sumOf { it.downloadBytes } +
            customModels.sumOf { it.length() } +
            remember(customModels) { LocalLlmStore.customProjectors(filesDir).sumOf { it.length() } }
        SectionHeader(
            stringResource(R.string.models_llm_yours_title),
            trailing = formatBytes(downloadedBytes),
        )
    }
    SettingsGroup {
        for (model in yours) item(key = model.id) { catalogRow(model) }
        for (file in customModels.sortedByDescending {
            selectedId == LocalLlmStore.CUSTOM_PREFIX + it.name
        }) {
            item(key = file.name) {
                val id = LocalLlmStore.CUSTOM_PREFIX + file.name
                CustomModelRow(
                    file = file,
                    selected = settings.watch { it.ai.localModelId == id },
                    onSelect = { scope.launch { repository.setAiLocalModelId(id) } },
                    onDelete = {
                        LocalLlmStore.deleteCustom(filesDir, file.name)
                        customModels = LocalLlmStore.customModels(filesDir)
                        if (settings.value.ai.localModelId == id) {
                            scope.launch { repository.setAiLocalModelId("") }
                        }
                    },
                )
            }
        }
    }
    if (yours.isEmpty() && customModels.isEmpty()) {
        CaptionText(stringResource(R.string.models_llm_empty))
    }

    SettingsGroup(
        stringResource(R.string.models_llm_available_title),
        info = stringResource(R.string.models_llm_catalog_info),
    ) {
        for (model in availableLitert) item { catalogRow(model) }
    }

    if (availableGguf.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.models_llm_gguf_title),
            info = stringResource(R.string.models_llm_gguf_info),
        ) {
            for (model in availableGguf) item { catalogRow(model) }
        }
        LlamaModuleBanner(settings)
    }

    SettingsGroup(
        stringResource(R.string.models_llm_import_title),
        info = stringResource(R.string.models_llm_compute_info),
    ) {
        item {
            ImportModelButton(
                importing = importing,
                onStart = { importing = true; importError = null },
                onDone = { error ->
                    importing = false
                    importError = error
                    customModels = LocalLlmStore.customModels(filesDir)
                },
            )
        }
    }
    importError?.let { CaptionText(it, error = true) }
    CaptionText(stringResource(R.string.models_llm_import_info))

    SectionHeader(stringResource(R.string.models_llm_compute_title))
    var askQualcomm by remember { mutableStateOf(false) }
    if (askQualcomm) {
        QualcommLicenceDialog { accepted ->
            askQualcomm = false
            if (accepted) scope.launch { repository.setAiLocalBackend(LocalLlmBackend.NPU) }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        // NPU only where this build has a runtime for this phone's chip.
        for (backend in LocalLlmBackend.entries) {
            if (backend == LocalLlmBackend.NPU && npuSoc == null) continue
            FilterChip(
                selected = selectedBackend == backend,
                onClick = {
                    // A build with native delivery fetches the runtime from
                    // Qualcomm's own repository, under Qualcomm's licence: the
                    // user accepts it before NPU is picked.
                    if (backend == LocalLlmBackend.NPU && NativeDelivery.gate("npu") != null &&
                        !NativeConsent.has(NativeConsent.QUALCOMM)
                    ) {
                        askQualcomm = true
                        return@FilterChip
                    }
                    // On Play the runtime is its own download; start it now
                    // rather than at the first answer.
                    if (backend == LocalLlmBackend.NPU) FeatureModules.npu.requestInstall()
                    // GGUF models on an Adreno phone get llama.cpp's GPU
                    // build, a download of its own: started here too, when
                    // there is a GGUF model to use it.
                    if (backend != LocalLlmBackend.CPU &&
                        (yours.any { it.runtime == LocalLlmRuntime.LLAMA_CPP } || customModels.any(GgufFile::runsOnLlamaCpp))
                    ) {
                        LlamaCpp.prepareGpu()
                    }
                    scope.launch { repository.setAiLocalBackend(backend) }
                },
                label = { Text(stringResource(backend.labelRes)) },
            )
        }
    }
    if (npuSoc != null) {
        val withBuilds = LocalLlmCatalog.models.filter { LocalLlmCatalog.npuBuild(it, npuSoc) != null }
        CaptionText(
            stringResource(R.string.models_llm_compute_npu_info, withBuilds.joinToString { it.displayName }),
        )
    }

    if (storageUsed > 0) {
        CaptionText(stringResource(R.string.models_llm_storage_info, formatBytes(storageUsed)))
    }
    // A model dropped from the catalog leaves a directory no row can reach.
    // Report it and let the user decide rather than deleting a multi-GB file
    // they paid the bandwidth for.
    if (orphanBytes > 0) {
        CaptionText(stringResource(R.string.models_llm_orphan_info, formatBytes(orphanBytes)))
        val freeUpLabel = stringResource(R.string.models_free_up_action, formatBytes(orphanBytes))
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LocalLlmStore.deleteOrphans(filesDir)
                        LocalLlmStore.pruneEngineCache(filesDir, context.cacheDir)
                    }
                    orphanBytes = 0
                    storageUsed = withContext(Dispatchers.IO) {
                        LocalLlmStore.totalBytesUsed(filesDir)
                    }
                }
            }) { Text(freeUpLabel) }
        }
    }

    meteredPending?.let { (model, target) ->
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.models_metered_body,
                model.displayName,
                formatBytes(LocalLlmCatalog.downloadSize(model, target)),
            ),
            onConfirm = {
                startDownload(model, target)
                meteredPending = null
            },
            onDismiss = { meteredPending = null },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog { meteredBlocked = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogModelRow(
    model: LocalLlmModel,
    /** The size of the build a download fetches now (#804). */
    sizeBytes: Long,
    /** Whether the NPU build is among the files on disk. */
    npuBuildOnDisk: Boolean,
    /** Label and action for fetching the other build, when the row offers one. */
    secondBuild: Pair<String, () -> Unit>?,
    status: DownloadStatus,
    selected: Boolean,
    hasToken: Boolean,
    downloadBusy: Boolean,
    tooBigForRam: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onOpenLicense: () -> Unit,
) {
    val downloaded = status is DownloadStatus.Downloaded
    val facts = stringResource(
        R.string.models_llm_row_subtitle,
        model.params,
        formatBytes(sizeBytes),
        stringResource(model.descriptionRes),
    )
    val tooBigNote = stringResource(R.string.models_llm_row_too_big)
    val subtitle = if (tooBigForRam) "$facts $tooBigNote" else facts
    // Tapping the row selects a downloaded model — the "Use this model"
    // button stays for discoverability, but the whole row is the target.
    val rowClick = if (downloaded && !selected) onSelect else null
    SelectionHighlight(selected = selected && downloaded, onClick = rowClick) {
        WmRow(
            title = model.displayName,
            titleContent = {
                // A flow, not a row: a long name leaves the badge no width in a
                // row, and it wrapped a letter a line. Here it drops under the name.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model.displayName,
                        fontWeight = if (selected && downloaded) FontWeight.SemiBold else null,
                    )
                    if (model.gated && !downloaded) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = stringResource(R.string.models_llm_gated_desc),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TierBadge(model.tier)
                }
            },
            supporting = {
                Column {
                    if (selected && downloaded) {
                        Text(
                            stringResource(R.string.models_in_use_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(subtitle)
                    if (model.runtime == LocalLlmRuntime.LLAMA_CPP) RuntimeLabel(model.runtime)
                    if (downloaded && npuBuildOnDisk) {
                        Text(
                            stringResource(R.string.models_llm_npu_build_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The terms in view before the download, gated or not:
                    // Gemma 3 has its own, the rest are Apache-2.0.
                    Text(
                        stringResource(R.string.models_llm_row_license, model.license),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            leading = if (selected && downloaded) {
                {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.models_selected_desc),
                    )
                }
            } else null,
            swipeActions = if (downloaded) {
                listOf(
                    deleteSwipeAction(
                        stringResource(R.string.models_delete_model_desc, model.displayName),
                        onDelete,
                    ),
                )
            } else {
                emptyList()
            },
            // The primary action for a row lives on its right edge, where a
            // list's actions belong — not on a button underneath it.
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
                            contentDescription = stringResource(
                                R.string.models_cancel_download_desc,
                            ),
                        )
                    }
                    is DownloadStatus.Paused -> TextButton(
                        onClick = onDownload,
                        enabled = !downloadBusy,
                    ) { Text(stringResource(R.string.models_resume_action)) }
                    is DownloadStatus.NotDownloaded, is DownloadStatus.Failed ->
                        if (InternetPermission.granted && (!model.gated || hasToken)) {
                            TextButton(
                                onClick = onDownload,
                                enabled = !downloadBusy,
                            ) {
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
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        // Everything that only sometimes has something to say sits below the
        // row and animates its height, so appearing progress or an error
        // doesn't snap the list.
        Column(modifier = Modifier.animateContentSize()) {
            when (status) {
                is DownloadStatus.NotDownloaded -> {
                    if (model.gated && !hasToken) {
                        CaptionText(stringResource(R.string.models_llm_needs_token))
                    }
                    // Where the licence text lives, one tap from the Download
                    // button that accepts it by downloading.
                    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                        TextButton(onClick = onOpenLicense) {
                            Text(stringResource(R.string.models_llm_license_action))
                        }
                    }
                }
                is DownloadStatus.Downloading -> DownloadProgress(status.bytes, status.total)
                is DownloadStatus.Paused -> CaptionText(
                    stringResource(R.string.models_paused_progress, formatBytes(status.bytes)),
                )
                is DownloadStatus.Downloaded -> if (!selected || secondBuild != null) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                        if (!selected) {
                            TextButton(onClick = onSelect) {
                                Text(stringResource(R.string.models_use_action))
                            }
                        }
                        secondBuild?.let { (label, action) ->
                            TextButton(onClick = action, enabled = !downloadBusy) { Text(label) }
                        }
                    }
                }
                is DownloadStatus.Failed -> {
                    CaptionText(
                        if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                        else stringResource(status.messageRes, status.messageArg),
                        error = true,
                    )
                    if (status.reason == FailReason.LICENSE_NOT_ACCEPTED) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                            TextButton(onClick = onOpenLicense) {
                                Text(stringResource(R.string.models_llm_open_model_page_action))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which engine runs a model, under its row, for the llama.cpp ones: LiteRT-LM
 * is the default and goes unsaid.
 */
@Composable
private fun RuntimeLabel(runtime: LocalLlmRuntime) {
    Text(
        stringResource(R.string.models_llm_runtime_label, runtime.label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * llama.cpp's own download, on a build that fetches it (a Play split, or a
 * GitHub build's native library) and has not yet. Nothing where it is in the
 * APK. The same questions about mobile data as the speech engines' banner.
 */
@Composable
private fun LlamaModuleBanner(settings: LiveSettings) {
    val context = LocalContext.current
    val module by LlamaCpp.moduleState.collectAsState()
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    when (val state = module) {
        ModuleState.Installed -> Unit
        is ModuleState.Installing -> StateBanner(stringResource(R.string.models_llm_llama_module_installing_info))
        is ModuleState.Missing if state.consent != null -> NativeConsentBanner(state.consent.orEmpty(), "llama")
        is ModuleState.Missing -> StateBanner(
            stringResource(
                if (state.failed) R.string.models_whisper_module_failed_info else R.string.models_llm_llama_module_missing_info,
            ),
            action = stringResource(if (state.failed) CommonR.string.common_retry else CommonR.string.common_download),
            tone = if (state.failed) BannerTone.WARNING else BannerTone.INFO,
            onAction = {
                when (downloadDecisionNow(context, settings.value)) {
                    MeteredDecision.ALLOWED -> LlamaCpp.requestModule()
                    MeteredDecision.ASK -> metered = true
                    MeteredDecision.BLOCKED -> blocked = true
                }
            },
        )
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.models_llm_llama_module_metered_body),
            onConfirm = {
                metered = false
                LlamaCpp.requestModule()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/** An imported .litertlm/.task/.gguf file, styled like a catalog row. */
@Composable
private fun CustomModelRow(
    file: File,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    SelectionHighlight(selected = selected, onClick = if (selected) null else onSelect) {
        WmRow(
            title = file.name,
            titleContent = {
                Text(file.name, fontWeight = if (selected) FontWeight.SemiBold else null)
            },
            supporting = {
                Column {
                    if (selected) {
                        Text(
                            stringResource(R.string.models_in_use_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(formatBytes(file.length()))
                    if (file.extension.equals(ModelFormat.GGUF.extension, ignoreCase = true)) {
                        RuntimeLabel(LocalLlmRuntime.LLAMA_CPP)
                    }
                }
            },
            leading = if (selected) {
                {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.models_selected_desc),
                    )
                }
            } else null,
            swipeActions = listOf(
                deleteSwipeAction(stringResource(R.string.models_delete_model_desc, file.name), onDelete),
            ),
            trailing = {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(
                            R.string.models_delete_model_desc,
                            file.name,
                        ),
                    )
                }
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (!selected) {
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = onSelect) {
                    Text(stringResource(R.string.models_use_action))
                }
            }
        }
    }
}

/**
 * Tints the model that's actually in use. The colour is animated so moving
 * the selection (and with it, the row's jump to the top of the section)
 * reads as a change rather than a redraw.
 */
@Composable
private fun SelectionHighlight(
    selected: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else Color.Transparent,
        animationSpec = tween(300),
        label = "modelRowBackground",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .animateContentSize(),
        content = content,
    )
}

/** "Recommended" / "Untested" / "Experimental" chip next to a model's name. */
@Composable
private fun TierBadge(tier: ModelTier) {
    val badgeRes = tier.badgeRes ?: return
    val label = stringResource(badgeRes)
    val container = if (tier == ModelTier.RECOMMENDED) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = if (tier == ModelTier.RECOMMENDED) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = onContainer,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun DownloadProgress(bytes: Long, total: Long) {
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
 * SAF picker that copies the chosen .litertlm/.task/.gguf files into the
 * custom models directory via a .part + rename, so a killed copy never leaves
 * a file that looks importable. A GGUF is also told by its content, so one
 * whose name lost its extension still imports, and gets it back. Several
 * files at once, so a GGUF vision model and its `mmproj` projector come in
 * together; the projector then pairs with the model beside it.
 */
@Composable
private fun ImportModelButton(
    importing: Boolean,
    onStart: () -> Unit,
    onDone: (error: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        onStart()
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                // Every file is tried; the first failure is the one reported.
                uris.mapNotNull { uri -> runCatching { importModel(context, uri) }.exceptionOrNull()?.message }
                    .firstOrNull()
            }
            onDone(error)
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        TextButton(
            onClick = { launcher.launch(arrayOf("*/*")) },
            enabled = !importing,
        ) {
            Text(
                if (importing) {
                    stringResource(R.string.models_llm_importing_label)
                } else {
                    stringResource(R.string.models_llm_import_action)
                },
            )
        }
    }
}

// The provider-supplied DISPLAY_NAME *is* sanitised below — reduced to its last
// path segment with File(..).name, then required to carry a known extension — so
// no directory component of it can reach the destination path. Lint matches the
// raw column read flowing into a File(), and cannot see that reduction, so the
// check is silenced here and left on everywhere else.
@Suppress("UnsanitizedFilenameFromContentProvider")
private fun importModel(context: Context, uri: android.net.Uri) {
    var name: String? = null
    var size = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 }?.let { name = cursor.getString(it) }
            cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
        }
    }
    // DISPLAY_NAME comes straight from the provider and may contain path
    // separators, so it is reduced to its last segment before it is ever used
    // to build a path. File(..).name is that sanitisation step.
    val reportedName = name
        ?: throw IllegalArgumentException(
            context.getString(R.string.models_llm_import_error_no_name),
        )
    val fileName = File(reportedName).name
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val known = ModelFormat.entries.any { it.extension == extension }
    require(!known || extension == ModelFormat.GGUF.extension || BuildConfig.ENABLE_LITERT_LM) {
        context.getString(R.string.models_llm_import_error_gguf_only)
    }
    val dir = LocalLlmStore.customDir(context.filesDir).apply { mkdirs() }
    val free = android.os.StatFs(dir.path).availableBytes
    require(size < 0 || free > size + 64L * 1024 * 1024) {
        context.getString(R.string.models_llm_import_error_no_space, formatBytes(size))
    }
    val part = File(dir, "$fileName.part")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException(
            context.getString(R.string.models_llm_import_error_open),
        )
        val target = when {
            known -> File(dir, fileName)
            GgufFile.isGguf(part) -> File(dir, "$fileName.${ModelFormat.GGUF.extension}")
            else -> throw IllegalArgumentException(context.getString(R.string.models_llm_import_error_not_a_model))
        }
        check(part.renameTo(target)) {
            context.getString(R.string.models_llm_import_error_move)
        }
    } finally {
        part.delete()
    }
}

/** Shared with the AI panel, which draws the same readout on the keyboard. */
internal fun formatBytes(bytes: Long): String = LocalLlmDownloadManager.formatBytes(bytes)

package com.wasimaster.wmkeyboard.app

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
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
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager.DownloadStatus
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager.FailReason
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmModel
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.localllm.ModelTier
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
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
internal fun LocalLlmModelManager(repository: SettingsRepository, settings: KeyboardSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val filesDir = context.filesDir
    val states by LocalLlmDownloadManager.states.collectAsState()
    var customModels by remember { mutableStateOf(LocalLlmStore.customModels(filesDir)) }
    var storageUsed by remember { mutableLongStateOf(0L) }
    var orphanBytes by remember { mutableLongStateOf(0L) }
    var meteredPending by remember { mutableStateOf<LocalLlmModel?>(null) }
    var meteredBlocked by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { LocalLlmDownloadManager.refresh(filesDir) }
    LaunchedEffect(states, customModels, importing) {
        storageUsed = withContext(Dispatchers.IO) { LocalLlmStore.totalBytesUsed(filesDir) }
        orphanBytes = withContext(Dispatchers.IO) { LocalLlmStore.orphanBytes(filesDir) }
        // The only model on disk needs no selection step: adopt it — covers
        // both "first download just finished" and "selection was deleted".
        if (LocalLlmStore.selectedModelFile(filesDir, settings.ai.localModelId) == null) {
            LocalLlmStore.soleDownloadedId(filesDir)?.let { repository.setAiLocalModelId(it) }
        }
    }

    val totalRamMb = remember {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt()
    }

    fun startDownload(model: LocalLlmModel) {
        LocalLlmDownloadManager.start(filesDir, model, settings.ai.hfToken)
    }

    fun requestDownload(model: LocalLlmModel) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = cm.isActiveNetworkMetered
        when (downloadDecisionNow(context, settings)) {
            MeteredDecision.BLOCKED -> meteredBlocked = true
            MeteredDecision.ASK -> meteredPending = model
            // Not held by data saving, so the old size threshold still stands:
            // these are the largest downloads the app makes.
            MeteredDecision.ALLOWED ->
                if (metered && model.sizeBytes >= METERED_CONFIRM_BYTES) {
                    meteredPending = model
                } else {
                    startDownload(model)
                }
        }
    }

    SettingsGroup(stringResource(R.string.models_llm_account_title)) {
        item {
            ApiKeyField(
                label = stringResource(R.string.models_llm_token_label),
                value = settings.ai.hfToken,
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

    // On disk (ready to use) vs still on Hugging Face — two questions the
    // one mixed list used to answer badly. Anything mid-download counts as
    // "available" so its progress bar stays with the thing being fetched.
    val onDisk = LocalLlmCatalog.models.filter {
        (states[it.id] ?: DownloadStatus.NotDownloaded) is DownloadStatus.Downloaded
    }
    // Selected model to the very top of its section — it's the one answer
    // "which model am I using?" needs, and scrolling for it is silly.
    val yours = onDisk.sortedByDescending { it.id == settings.ai.localModelId }
    val available = LocalLlmCatalog.models - onDisk.toSet()

    @Composable
    fun catalogRow(model: LocalLlmModel) {
        CatalogModelRow(
            model = model,
            status = states[model.id] ?: DownloadStatus.NotDownloaded,
            selected = settings.ai.localModelId == model.id,
            hasToken = settings.ai.hfToken.isNotBlank(),
            downloadBusy = LocalLlmDownloadManager.isBusy,
            tooBigForRam = model.minRamMb > totalRamMb,
            onDownload = { requestDownload(model) },
            onCancel = { LocalLlmDownloadManager.cancel() },
            onSelect = { scope.launch { repository.setAiLocalModelId(model.id) } },
            onDelete = {
                LocalLlmDownloadManager.delete(filesDir, model)
                if (settings.ai.localModelId == model.id) {
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
        val downloadedBytes = yours.sumOf { it.sizeBytes } +
            customModels.sumOf { it.length() }
        ModelsSectionHeader(
            stringResource(R.string.models_llm_yours_title),
            formatBytes(downloadedBytes),
        )
    }
    SettingsGroup {
        for (model in yours) item { catalogRow(model) }
        for (file in customModels.sortedByDescending {
            settings.ai.localModelId == LocalLlmStore.CUSTOM_PREFIX + it.name
        }) {
            item {
                val id = LocalLlmStore.CUSTOM_PREFIX + file.name
                CustomModelRow(
                    file = file,
                    selected = settings.ai.localModelId == id,
                    onSelect = { scope.launch { repository.setAiLocalModelId(id) } },
                    onDelete = {
                        LocalLlmStore.deleteCustom(filesDir, file.name)
                        customModels = LocalLlmStore.customModels(filesDir)
                        if (settings.ai.localModelId == id) {
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
        for (model in available) item { catalogRow(model) }
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        for (backend in LocalLlmBackend.entries) {
            FilterChip(
                selected = settings.ai.localBackend == backend,
                onClick = { scope.launch { repository.setAiLocalBackend(backend) } },
                label = { Text(stringResource(backend.labelRes)) },
            )
        }
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
                    withContext(Dispatchers.IO) { LocalLlmStore.deleteOrphans(filesDir) }
                    orphanBytes = 0
                    storageUsed = withContext(Dispatchers.IO) {
                        LocalLlmStore.totalBytesUsed(filesDir)
                    }
                }
            }) { Text(freeUpLabel) }
        }
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
 * A section header with a muted, right-aligned trailing value — used to hang
 * the combined download size off the "Your models" title. Padding mirrors
 * [SectionHeader] so the label and trailing text line up with row content.
 */
@Composable
private fun ModelsSectionHeader(title: String, trailing: String) {
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
        Text(
            trailing,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CatalogModelRow(
    model: LocalLlmModel,
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
        formatBytes(model.sizeBytes),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.displayName,
                        fontWeight = if (selected && downloaded) FontWeight.SemiBold else null,
                    )
                    if (model.gated && !downloaded) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = stringResource(R.string.models_llm_gated_desc),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(14.dp),
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
                        if (!model.gated || hasToken) {
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
                is DownloadStatus.NotDownloaded -> if (model.gated && !hasToken) {
                    CaptionText(stringResource(R.string.models_llm_needs_token))
                }
                is DownloadStatus.Downloading -> DownloadProgress(status.bytes, status.total)
                is DownloadStatus.Paused -> CaptionText(
                    stringResource(R.string.models_paused_progress, formatBytes(status.bytes)),
                )
                is DownloadStatus.Downloaded -> if (!selected) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                        TextButton(onClick = onSelect) {
                            Text(stringResource(R.string.models_use_action))
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

/** An imported .litertlm/.task file, styled like a catalog row. */
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
        modifier = Modifier
            .padding(start = 8.dp)
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
 * SAF picker that copies the chosen .litertlm/.task into the custom models
 * directory via a .part + rename, so a killed copy never leaves a file that
 * looks importable.
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
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onStart()
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { importModel(context, uri) }.exceptionOrNull()?.message
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
    require(extension == "litertlm" || extension == "task") {
        context.getString(R.string.models_llm_import_error_not_a_model)
    }
    val dir = LocalLlmStore.customDir(context.filesDir).apply { mkdirs() }
    val free = android.os.StatFs(dir.path).availableBytes
    require(size < 0 || free > size + 64L * 1024 * 1024) {
        context.getString(R.string.models_llm_import_error_no_space, formatBytes(size))
    }
    val part = File(dir, "$fileName.part")
    val target = File(dir, fileName)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException(
            context.getString(R.string.models_llm_import_error_open),
        )
        check(part.renameTo(target)) {
            context.getString(R.string.models_llm_import_error_move)
        }
    } finally {
        part.delete()
    }
}

/** Shared with the AI panel, which draws the same readout on the keyboard. */
internal fun formatBytes(bytes: Long): String = LocalLlmDownloadManager.formatBytes(bytes)

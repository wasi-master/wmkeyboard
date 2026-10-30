package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel

/** Downloads above this size ask before using mobile data. */
private const val ET_METERED_CONFIRM_BYTES = 150_000_000L

/**
 * The models of one engine whose models download file by file: Asr4All's
 * three sizes (#289), Parakeet's and Moonshine's one each, or Sherpa's one per
 * language and its punctuation model. Download, progress, delete. There is no
 * setting for which one dictates: the largest one on the device that knows
 * the language does, so keeping a model is choosing it, and the screen marks
 * the ones in use. Shown only while [engine] is listed and the device can run it.
 */
@Composable
internal fun EtModelManager(engine: EtEngine, settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val states by EtDownloadManager.states.collectAsState()
    var meteredAsk by remember { mutableStateOf<EtModel?>(null) }
    var meteredBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { EtDownloadManager.refresh(filesDir) }
    val models = remember(engine) { EtCatalog.forEngine(engine) }

    fun requestDownload(model: EtModel) {
        when (downloadDecisionNow(context, settings.value)) {
            // Data saving is not holding it, but Parakeet is most of a
            // gigabyte: over mobile data that still deserves a word, as the
            // Whisper screen gives its big models.
            MeteredDecision.ALLOWED ->
                if (isMeteredNow(context) && model.bytes >= ET_METERED_CONFIRM_BYTES) {
                    meteredAsk = model
                } else {
                    EtDownloadManager.start(filesDir, model)
                }
            MeteredDecision.ASK -> meteredAsk = model
            MeteredDecision.BLOCKED -> meteredBlocked = true
        }
    }

    // Read from the states, so the mark moves the moment a download lands.
    // A model is in use unless a bigger one on the device covers all its
    // languages; for the single-language engines that leaves the largest.
    val downloaded = models.filter { !it.punctuation && states[it.id] is Status.Downloaded }
    val inUse = downloaded.filter { model ->
        val languages = model.languages ?: model.engine.languages
        downloaded.none { other ->
            other !== model && other.millions > model.millions && languages.all(other::supports)
        }
    }.toSet()

    // The runtime itself is an on-demand part off the APK: ONNX Runtime for
    // Sherpa and Omnilingual, ExecuTorch (shared with the FUTO Swipe glide
    // engine) for the others.
    if (engine.onnx) OnnxModuleBanner(settings) else SpeechModuleBanner(settings, FeatureModules.executorch, "executorch")

    SettingsGroup(
        stringResource(
            when (engine) {
                EtEngine.ASR4ALL -> R.string.voice_asr4all_group
                EtEngine.PARAKEET -> R.string.voice_parakeet_group
                EtEngine.MOONSHINE -> R.string.voice_moonshine_group
                EtEngine.SHERPA -> R.string.voice_sherpa_group
                EtEngine.OMNILINGUAL -> R.string.voice_omnilingual_group
            },
        ),
        info = stringResource(
            when (engine) {
                EtEngine.ASR4ALL -> R.string.voice_asr4all_model_info
                EtEngine.PARAKEET -> R.string.voice_parakeet_model_info
                EtEngine.MOONSHINE -> R.string.voice_moonshine_model_info
                EtEngine.SHERPA -> R.string.voice_sherpa_model_info
                EtEngine.OMNILINGUAL -> R.string.voice_omnilingual_model_info
            },
        ),
    ) {
        // For a phone that cannot download them, above all the offline build
        // (#954): every file of each model not on the device, imported here.
        item(level = offlineImportLevel) {
            val missing = models.filter { states[it.id] !is Status.Downloaded }
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_et_subtitle),
                links = remember(missing) {
                    missing.flatMap { model ->
                        model.files.map { f -> OfflineLink("${model.label} · ${f.name}", model.url(f)) }
                    }.distinctBy { it.url }
                },
                onImported = { EtDownloadManager.refresh(filesDir) },
            )
        }
        for (model in models) {
            item {
                val status = states[model.id] ?: Status.NotDownloaded
                // A model's own name, which is not translated.
                val title = model.label
                Column(modifier = Modifier.fillMaxWidth()) {
                    WmRow(
                        title = title,
                        subtitle = when (status) {
                            Status.Downloaded -> stringResource(
                                when {
                                    model.punctuation -> R.string.voice_sherpa_punctuation_kept
                                    model in inUse -> R.string.voice_et_model_in_use
                                    else -> R.string.voice_et_model_kept
                                },
                            )
                            is Status.Paused -> stringResource(R.string.models_paused_progress, formatBytes(status.bytes))
                            else -> stringResource(R.string.voice_et_model_missing, formatBytes(model.bytes))
                        },
                        swipeActions = if (status == Status.Downloaded) {
                            listOf(
                                deleteSwipeAction(stringResource(R.string.models_delete_model_desc, title)) {
                                    EtDownloadManager.delete(filesDir, model)
                                },
                            )
                        } else {
                            emptyList()
                        },
                        trailing = {
                            when (status) {
                                Status.Downloaded -> IconButton(
                                    onClick = { EtDownloadManager.delete(filesDir, model) },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.models_delete_model_desc, title),
                                    )
                                }
                                is Status.Downloading -> IconButton(onClick = { EtDownloadManager.cancel() }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.models_cancel_download_desc),
                                    )
                                }
                                is Status.Paused -> TextButton(
                                    onClick = { requestDownload(model) },
                                    enabled = !EtDownloadManager.isBusy,
                                ) { Text(stringResource(R.string.models_resume_action)) }
                                Status.NotDownloaded, is Status.Failed -> if (InternetPermission.granted) TextButton(
                                    onClick = { requestDownload(model) },
                                    enabled = !EtDownloadManager.isBusy,
                                ) {
                                    Text(
                                        stringResource(
                                            if (status is Status.Failed) CommonR.string.common_retry
                                            else CommonR.string.common_download,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    when (status) {
                        is Status.Downloading -> Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { (status.bytes.toFloat() / status.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.models_download_progress,
                                    formatBytes(status.bytes),
                                    formatBytes(status.total),
                                    status.bytes * 100 / status.total,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        is Status.Failed -> CaptionText(
                            if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                            else stringResource(status.messageRes, status.messageArg),
                            error = true,
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
    meteredAsk?.let { model ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                meteredAsk = null
                EtDownloadManager.start(filesDir, model)
            },
            onDismiss = { meteredAsk = null },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog(onDismiss = { meteredBlocked = false })
}

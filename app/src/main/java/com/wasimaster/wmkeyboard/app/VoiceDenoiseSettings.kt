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
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixHigh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixNormal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Block
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.voice.StrongDenoise
import com.wasimaster.wmkeyboard.core.voice.VoiceDenoise
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reduce background noise: Off, Light (RNNoise) or Strong (DPDFNet on ONNX
 * Runtime). Strong is listed only where the build has the runtime; picked,
 * it shows its model's download row, and runs as Light until the model is
 * here.
 */
@Composable
internal fun VoiceDenoiseSetting(repository: SettingsRepository, settings: LiveSettings, scope: CoroutineScope) {
    val level = settings.watch { it.whisper.denoise }
    val strongShipped = StrongDenoise.runtimeShipped
    Column(modifier = Modifier.fillMaxWidth()) {
        ChoiceSetting(
            R.string.voice_denoise_title,
            subtitle = stringResource(R.string.voice_denoise_subtitle),
            info = stringResource(R.string.voice_denoise_info),
            options = listOf(
                VoiceDenoise.OFF to stringResource(R.string.voice_denoise_off),
                VoiceDenoise.LIGHT to stringResource(R.string.voice_denoise_light),
                VoiceDenoise.STRONG to stringResource(R.string.voice_denoise_strong),
            ).filter { (option, _) -> option != VoiceDenoise.STRONG || strongShipped || option == level },
            selected = level,
            default = SettingsDefaults.whisper.denoise,
            detail = { option ->
                when (option) {
                    VoiceDenoise.LIGHT -> ChoiceDetail(stringResource(R.string.voice_denoise_light_desc), Icons.Outlined.AutoFixNormal)
                    VoiceDenoise.STRONG -> ChoiceDetail(stringResource(R.string.voice_denoise_strong_desc), Icons.Outlined.AutoFixHigh)
                    else -> ChoiceDetail(stringResource(R.string.voice_denoise_off_desc), Icons.Outlined.Block)
                }
            },
        ) { scope.launch { repository.setVoiceDenoise(it) } }
        if (level == VoiceDenoise.STRONG) {
            if (strongShipped) {
                OnnxModuleBanner(settings)
                StrongDenoiseModelRow(settings)
            } else {
                CaptionText(stringResource(R.string.voice_denoise_strong_not_in_build))
            }
        }
    }
}

/** The Strong level's model: download, progress, delete. */
@Composable
private fun StrongDenoiseModelRow(settings: LiveSettings) {
    val filesDir = LocalContext.current.filesDir
    val status by StrongDenoise.status.collectAsState()
    LaunchedEffect(Unit) { StrongDenoise.refresh(filesDir) }
    OnnxModelRow(
        settings = settings,
        title = stringResource(R.string.voice_denoise_model_title),
        status = status,
        ready = stringResource(R.string.voice_denoise_model_ready, formatBytes(StrongDenoise.BYTES)),
        missing = stringResource(R.string.voice_denoise_model_missing, formatBytes(StrongDenoise.BYTES)),
        bytes = StrongDenoise.BYTES,
        onDownload = { StrongDenoise.download(filesDir) },
        onCancel = StrongDenoise::cancel,
        onDelete = { StrongDenoise.delete(filesDir) },
    )
}

/**
 * One downloadable model of a voice feature that runs on ONNX Runtime, as a
 * row: download, progress, delete. Downloading also asks for the runtime's
 * module, since a model with nothing to run it on would leave the feature
 * off without saying why. A download of [bytes] over mobile data asks first
 * past [ONNX_METERED_CONFIRM_BYTES], as the engines' own models do.
 */
@Composable
internal fun OnnxModelRow(
    settings: LiveSettings,
    title: String,
    status: Status,
    ready: String,
    missing: String,
    bytes: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var meteredAsk by remember { mutableStateOf(false) }
    var meteredBlocked by remember { mutableStateOf(false) }

    fun start() {
        if (!SherpaNative.moduleReady) SherpaNative.requestModule()
        onDownload()
    }

    fun download() {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED ->
                if (isMeteredNow(context) && bytes >= ONNX_METERED_CONFIRM_BYTES) meteredAsk = true else start()
            MeteredDecision.ASK -> meteredAsk = true
            MeteredDecision.BLOCKED -> meteredBlocked = true
        }
    }

    WmRow(
        title = title,
        subtitle = when (status) {
            Status.Downloaded -> ready
            is Status.Paused -> stringResource(R.string.models_paused_progress, formatBytes(status.bytes))
            else -> missing
        },
        swipeActions = if (status == Status.Downloaded) {
            listOf(deleteSwipeAction(stringResource(R.string.models_delete_model_desc, title)) { onDelete() })
        } else {
            emptyList()
        },
        trailing = {
            when (status) {
                Status.Downloaded -> IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.models_delete_model_desc, title))
                }
                is Status.Downloading -> IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.models_cancel_download_desc))
                }
                is Status.Paused -> TextButton(onClick = { download() }) { Text(stringResource(R.string.models_resume_action)) }
                Status.NotDownloaded, is Status.Failed -> if (InternetPermission.granted) TextButton(onClick = { download() }) {
                    Text(
                        stringResource(if (status is Status.Failed) CommonR.string.common_retry else CommonR.string.common_download),
                    )
                }
            }
        },
    )
    if (!InternetPermission.granted && status != Status.Downloaded) CaptionText(stringResource(R.string.offline_no_download_info))
    when (status) {
        is Status.Downloading -> Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
            if (status.messageArg.isEmpty()) stringResource(status.messageRes) else stringResource(status.messageRes, status.messageArg),
            error = true,
        )
        else -> Unit
    }
    if (meteredAsk) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                meteredAsk = false
                start()
            },
            onDismiss = { meteredAsk = false },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog(onDismiss = { meteredBlocked = false })
}

/** Downloads above this size ask before using mobile data. */
private const val ONNX_METERED_CONFIRM_BYTES = 100_000_000L

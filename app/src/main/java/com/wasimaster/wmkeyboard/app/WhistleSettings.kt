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
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelStore

/**
 * The one Whistle model (#548, first drafted by @Guillermode20): download,
 * progress, delete. Whistle has no catalog and no per-language routing, so
 * this is a single row where [WhisperModelManager] is a whole screen.
 * Shown only while Whistle is the engine and the device can run it.
 */
@Composable
internal fun WhistleModelManager(settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val status by WhistleModelDownloadManager.status.collectAsState()
    var meteredAsk by remember { mutableStateOf(false) }
    var meteredBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { WhistleModelDownloadManager.refresh(filesDir) }

    fun requestDownload() {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> WhistleModelDownloadManager.start(filesDir)
            MeteredDecision.ASK -> meteredAsk = true
            MeteredDecision.BLOCKED -> meteredBlocked = true
        }
    }

    // Play: the engine itself is an on-demand part, shared with whisper.cpp.
    SpeechModuleBanner(settings)

    val title = stringResource(R.string.voice_whistle_model_title)
    SettingsGroup(
        stringResource(R.string.voice_whistle_group),
        info = stringResource(R.string.voice_whistle_model_info),
    ) {
        item(level = offlineImportLevel) {
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_whistle_subtitle),
                links = listOf(OfflineLink(title, WhistleModelStore.MODEL_URL)),
                onImported = { WhistleModelDownloadManager.refresh(filesDir) },
            )
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                WmRow(
                    title = title,
                    subtitle = when (val s = status) {
                        Status.Downloaded -> stringResource(R.string.voice_whistle_model_ready)
                        is Status.Paused -> stringResource(R.string.models_paused_progress, formatBytes(s.bytes))
                        else -> stringResource(
                            R.string.voice_whistle_model_missing,
                            formatBytes(WhistleModelStore.MODEL_BYTES),
                        )
                    },
                    swipeActions = if (status == Status.Downloaded) {
                        listOf(
                            deleteSwipeAction(stringResource(R.string.models_delete_model_desc, title)) {
                                WhistleModelDownloadManager.delete(filesDir)
                            },
                        )
                    } else {
                        emptyList()
                    },
                    trailing = {
                        when (status) {
                            Status.Downloaded -> IconButton(
                                onClick = { WhistleModelDownloadManager.delete(filesDir) },
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.models_delete_model_desc, title),
                                )
                            }
                            is Status.Downloading -> IconButton(onClick = { WhistleModelDownloadManager.cancel() }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.models_cancel_download_desc),
                                )
                            }
                            is Status.Paused -> TextButton(onClick = ::requestDownload) {
                                Text(stringResource(R.string.models_resume_action))
                            }
                            Status.NotDownloaded, is Status.Failed -> if (InternetPermission.granted) TextButton(onClick = ::requestDownload) {
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
                when (val s = status) {
                    is Status.Downloading -> Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        LinearProgressIndicator(
                            progress = { (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.models_download_progress,
                                formatBytes(s.bytes),
                                formatBytes(s.total),
                                s.bytes * 100 / s.total,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    is Status.Failed -> CaptionText(
                        if (s.messageArg.isEmpty()) stringResource(s.messageRes)
                        else stringResource(s.messageRes, s.messageArg),
                        error = true,
                    )
                    else -> Unit
                }
            }
        }
    }
    if (meteredAsk) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                meteredAsk = false
                WhistleModelDownloadManager.start(filesDir)
            },
            onDismiss = { meteredAsk = false },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog(onDismiss = { meteredBlocked = false })
}

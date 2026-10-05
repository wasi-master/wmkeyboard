package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelDownloadManager.Status

/** One downloadable, on-device Whistle model (English, German, French, Spanish, Italian, Dutch, Polish). */
@Composable
internal fun WhistleModelManager(settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val status by WhistleModelDownloadManager.status.collectAsState()
    var meteredAsk by remember { mutableStateOf(false) }
    var meteredBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { java.io.File(filesDir, "whisper").deleteRecursively() }
        WhistleModelDownloadManager.refresh(filesDir)
    }

    SettingsGroup(stringResource(R.string.voice_offline_group)) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.whistle_model_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (val current = status) {
                    Status.Missing -> Text(stringResource(R.string.whistle_model_missing))
                    Status.Ready -> Text(stringResource(R.string.whistle_model_ready))
                    is Status.Paused -> Text(
                        stringResource(
                            R.string.whistle_model_paused,
                            formatBytes(current.bytes),
                            formatBytes(current.total),
                        ),
                    )
                    is Status.Downloading -> {
                        val progress = if (current.total > 0) {
                            (current.bytes.toFloat() / current.total).coerceIn(0f, 1f)
                        } else 0f
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.whistle_model_downloading,
                                formatBytes(current.bytes),
                                formatBytes(current.total),
                            ),
                        )
                    }
                    is Status.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (status) {
                        Status.Ready -> OutlinedButton(onClick = {
                            WhistleModelDownloadManager.delete(filesDir)
                        }) { Text(stringResource(R.string.whistle_model_delete)) }
                        is Status.Downloading -> OutlinedButton(onClick = {
                            WhistleModelDownloadManager.cancel()
                        }) { Text(stringResource(R.string.whistle_model_cancel)) }
                        else -> Button(onClick = {
                            when (downloadDecisionNow(context, settings.value)) {
                                MeteredDecision.ALLOWED -> WhistleModelDownloadManager.start(filesDir)
                                MeteredDecision.ASK -> meteredAsk = true
                                MeteredDecision.BLOCKED -> meteredBlocked = true
                            }
                        }) { Text(stringResource(R.string.whistle_model_download)) }
                    }
                }
            }
        }
    }
    if (meteredAsk) MeteredDownloadDialog(
        detail = stringResource(R.string.whistle_model_metered_detail),
        onConfirm = {
            meteredAsk = false
            WhistleModelDownloadManager.start(filesDir)
        },
        onDismiss = { meteredAsk = false },
    )
    if (meteredBlocked) MeteredBlockedDialog(onDismiss = { meteredBlocked = false })
}

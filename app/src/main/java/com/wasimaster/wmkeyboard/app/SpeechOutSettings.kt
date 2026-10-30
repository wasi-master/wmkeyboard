package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
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
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.speech.MmsSpeech
import com.wasimaster.wmkeyboard.core.voice.speech.MmsVoice
import com.wasimaster.wmkeyboard.core.voice.speech.MmsVoiceDownloads
import com.wasimaster.wmkeyboard.core.voice.speech.MmsVoices

/** The Read back tool's page: what it reads, and the offline voices it reads with. */
@Composable
internal fun ReadBackToolSettings(settings: LiveSettings) {
    CaptionText(stringResource(R.string.read_back_tool_caption))
    SpeechVoicesGroup(settings)
}

/**
 * Meta's MMS voices for the keyboard's languages: download, progress, delete.
 * A downloaded voice reads its language for Read back and the speaker buttons
 * (vocabulary cards, Look up) instead of the phone's own synthesiser, offline.
 * The weights are CC-BY-NC-4.0, so the first download asks the user to agree.
 */
@Composable
internal fun SpeechVoicesGroup(settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    if (!MmsSpeech.runtimeShipped) {
        CaptionText(stringResource(R.string.speech_voices_not_in_build))
        return
    }
    val languages = settings.watch { it.enabledLanguages }
    // One row per voice: two keyboards of one language share it.
    val rows = remember(languages) {
        languages.mapNotNull { lang -> MmsVoices.forLanguage(context, lang.localeTag.ifEmpty { lang.id })?.let { lang to it } }
            .distinctBy { it.second.code }
    }
    val missing = remember(languages, rows) {
        val covered = rows.map { it.first.id }.toSet()
        languages.filter { it.id !in covered }
    }
    val states by MmsVoiceDownloads.states.collectAsState()
    val agreements by NativeConsent.agreements.collectAsState()
    var askLicence by remember { mutableStateOf<MmsVoice?>(null) }
    var meteredAsk by remember { mutableStateOf<MmsVoice?>(null) }
    var meteredBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(rows) { MmsVoiceDownloads.refresh(filesDir, rows.map { it.second }) }

    fun download(voice: MmsVoice) {
        if (NativeConsent.MMS !in agreements) {
            askLicence = voice
            return
        }
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> MmsVoiceDownloads.start(filesDir, voice)
            MeteredDecision.ASK -> meteredAsk = voice
            MeteredDecision.BLOCKED -> meteredBlocked = true
        }
    }

    // The runtime is an on-demand part off the APK on Play and GitHub builds.
    OnnxModuleBanner(settings)
    if (!InternetPermission.granted) StateBanner(stringResource(R.string.speech_voices_offline_build_info))
    SettingsGroup(
        stringResource(R.string.speech_voices_group),
        info = stringResource(R.string.speech_voices_info),
    ) {
        for ((lang, voice) in rows) {
            item {
                val status = states[voice.code] ?: Status.NotDownloaded
                val title = lang.displayName
                Column(modifier = Modifier.fillMaxWidth()) {
                    WmRow(
                        title = title,
                        subtitle = when (status) {
                            Status.Downloaded -> stringResource(R.string.speech_voice_ready)
                            is Status.Paused -> stringResource(R.string.models_paused_progress, formatBytes(status.bytes))
                            else -> stringResource(R.string.speech_voice_missing, formatBytes(voice.bytes))
                        },
                        swipeActions = if (status == Status.Downloaded) {
                            listOf(
                                deleteSwipeAction(stringResource(R.string.models_delete_model_desc, title)) {
                                    MmsVoiceDownloads.delete(filesDir, voice)
                                },
                            )
                        } else {
                            emptyList()
                        },
                        trailing = {
                            when (status) {
                                Status.Downloaded -> IconButton(onClick = { MmsVoiceDownloads.delete(filesDir, voice) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.models_delete_model_desc, title),
                                    )
                                }
                                is Status.Downloading -> IconButton(onClick = { MmsVoiceDownloads.cancel() }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.models_cancel_download_desc),
                                    )
                                }
                                is Status.Paused -> TextButton(
                                    onClick = { download(voice) },
                                    enabled = !MmsVoiceDownloads.isBusy,
                                ) { Text(stringResource(R.string.models_resume_action)) }
                                Status.NotDownloaded, is Status.Failed -> if (InternetPermission.granted) TextButton(
                                    onClick = { download(voice) },
                                    enabled = !MmsVoiceDownloads.isBusy,
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
    if (missing.isNotEmpty()) {
        CaptionText(
            stringResource(R.string.speech_voices_missing_languages, missing.joinToString(", ") { it.displayName }),
        )
    }
    // CC-BY asks for the credit wherever the voices are offered.
    CaptionText(stringResource(R.string.speech_voices_credit))

    askLicence?.let { voice ->
        MmsLicenceDialog { accepted ->
            askLicence = null
            if (accepted) download(voice)
        }
    }
    meteredAsk?.let { voice ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                meteredAsk = null
                MmsVoiceDownloads.start(filesDir, voice)
            },
            onDismiss = { meteredAsk = null },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog(onDismiss = { meteredBlocked = false })
}

/**
 * CC-BY-NC-4.0, before the phone fetches the first voice. The voices come
 * straight from Hugging Face, so the user receives them under Meta's licence
 * rather than from this project, and agrees here. [onDone] hears whether they
 * accepted.
 */
@Composable
private fun MmsLicenceDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.speech_voices_licence_title)) },
        text = { Text(stringResource(R.string.speech_voices_licence_body)) },
        confirmButton = {
            TextButton(onClick = {
                NativeConsent.set(NativeConsent.MMS, true)
                onDone(true)
            }) { Text(stringResource(R.string.speech_voices_licence_accept)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    NativeConsent.licenceUrls[NativeConsent.MMS]?.let { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }) { Text(stringResource(R.string.speech_voices_licence_view)) }
                TextButton(onClick = { onDone(false) }) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

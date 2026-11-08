package com.wasimaster.wmkeyboard.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GraphicEq
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Waves
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.VoiceSilenceStopRange
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.core.voice.vad.VadModels
import com.wasimaster.wmkeyboard.core.voice.vad.VoiceVad
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The end of a phrase on a live engine's own page (#1174): **Stop after a
 * pause** and the [VoicePhraseEndSetting] under it. The same settings as on
 * the Voice typing screen, shown where someone setting up Parakeet or Sherpa
 * looks for why a phrase does not end.
 */
@Composable
internal fun VoicePhraseEndGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    SettingsGroup(
        stringResource(R.string.voice_phrase_end_group),
        info = stringResource(R.string.voice_phrase_end_group_info),
    ) {
        item {
            val offLabel = stringResource(R.string.voice_silence_stop_off)
            val secondsFormat = stringResource(R.string.voice_silence_stop_seconds)
            SliderSetting(
                R.string.voice_silence_stop_title,
                subtitle = stringResource(R.string.voice_silence_stop_subtitle),
                value = settings.watch { it.voiceBar.silenceStopMs }.toFloat(),
                range = 0f..VoiceSilenceStopRange.last.toFloat(),
                display = { picked ->
                    val ms = (picked / PAUSE_STEP_MS).roundToInt() * PAUSE_STEP_MS.toInt()
                    if (ms == 0) offLabel else secondsFormat.format(ms / 1000f)
                },
                info = stringResource(R.string.voice_silence_stop_info),
                default = SettingsDefaults.voiceBar.silenceStopMs.toFloat(),
            ) { picked ->
                scope.launch { repository.setVoiceSilenceStopMs((picked / PAUSE_STEP_MS).roundToInt() * PAUSE_STEP_MS.toInt()) }
            }
        }
        item { VoicePhraseEndSetting(repository, settings, scope) }
    }
}

/** The pause slider's step, as on the Voice typing screen. */
private const val PAUSE_STEP_MS = 250f

/**
 * Phrase end detector (#976), for the engines that decode while the user
 * speaks: Auto, Silero, FireRedVAD, loudness only, or a model the user
 * imported, with the speech threshold and Smart Turn under it. The model
 * choices are listed only where the build has ONNX Runtime; picked, each shows
 * the row that fetches (or imports) what it needs, and until then dictation
 * runs on what Auto would pick.
 */
@Composable
internal fun VoicePhraseEndSetting(repository: SettingsRepository, settings: LiveSettings, scope: CoroutineScope) {
    val choice = settings.watch { it.whisper.vad }
    val onnx = SherpaNative.shipped
    val modelChoices = setOf(VoiceVad.SILERO, VoiceVad.FIRERED, VoiceVad.CUSTOM)
    Column(modifier = Modifier.fillMaxWidth()) {
        ChoiceSetting(
            R.string.voice_vad_title,
            subtitle = stringResource(R.string.voice_vad_subtitle),
            info = stringResource(R.string.voice_vad_info),
            options = listOf(
                VoiceVad.AUTO to stringResource(R.string.voice_vad_auto),
                VoiceVad.SILERO to stringResource(R.string.voice_vad_silero),
                VoiceVad.FIRERED to stringResource(R.string.voice_vad_firered),
                VoiceVad.LOUDNESS to stringResource(R.string.voice_vad_loudness),
                VoiceVad.CUSTOM to stringResource(R.string.voice_vad_custom),
            ).filter { (option, _) -> onnx || option !in modelChoices || option == choice },
            selected = choice,
            default = SettingsDefaults.whisper.vad,
            detail = { option ->
                when (option) {
                    VoiceVad.SILERO -> ChoiceDetail(stringResource(R.string.voice_vad_silero_desc), Icons.Outlined.GraphicEq)
                    VoiceVad.FIRERED -> ChoiceDetail(stringResource(R.string.voice_vad_firered_desc), Icons.Outlined.Waves)
                    VoiceVad.LOUDNESS -> ChoiceDetail(stringResource(R.string.voice_vad_loudness_desc), Icons.Outlined.VolumeUp)
                    VoiceVad.CUSTOM -> ChoiceDetail(stringResource(R.string.voice_vad_custom_desc), Icons.Outlined.FileOpen)
                    else -> ChoiceDetail(stringResource(R.string.voice_vad_auto_desc), Icons.Outlined.AutoMode)
                }
            },
        ) { scope.launch { repository.setVoiceVad(it) } }
        if (choice in modelChoices) {
            if (onnx) {
                OnnxModuleBanner(settings)
                when (choice) {
                    VoiceVad.SILERO -> VadDownloadRow(
                        settings,
                        VadModels.SILERO,
                        stringResource(R.string.voice_vad_silero_model_title),
                        stringResource(R.string.voice_vad_silero_model_missing, formatBytes(VadModels.SILERO.bytes)),
                    )
                    VoiceVad.FIRERED -> VadDownloadRow(
                        settings,
                        VadModels.FIRERED,
                        stringResource(R.string.voice_vad_firered_model_title),
                        stringResource(R.string.voice_vad_model_missing, formatBytes(VadModels.FIRERED.bytes)),
                    )
                    else -> CustomVadRow()
                }
            } else {
                CaptionText(stringResource(R.string.voice_vad_not_in_build))
            }
        }
        val percent = stringResource(R.string.voice_vad_threshold_value)
        SliderSetting(
            R.string.voice_vad_threshold_title,
            subtitle = stringResource(R.string.voice_vad_threshold_subtitle),
            value = settings.watch { it.whisper.vadThreshold },
            range = VoiceVad.THRESHOLD_RANGE,
            display = { percent.format((it * 20f).roundToInt() * 5) },
            info = stringResource(R.string.voice_vad_threshold_info),
            default = SettingsDefaults.whisper.vadThreshold,
        ) { picked -> scope.launch { repository.setVoiceVadThreshold((picked * 20f).roundToInt() / 20f) } }
        if (onnx) {
            val smartTurn = settings.watch { it.whisper.smartTurn }
            ToggleSetting(
                R.string.voice_smart_turn_title,
                stringResource(R.string.voice_smart_turn_subtitle),
                smartTurn,
                info = stringResource(R.string.voice_smart_turn_info),
                default = SettingsDefaults.whisper.smartTurn,
            ) { scope.launch { repository.setVoiceSmartTurn(it) } }
            if (smartTurn) {
                VadDownloadRow(
                    settings,
                    VadModels.SMART_TURN,
                    stringResource(R.string.voice_smart_turn_model_title),
                    stringResource(R.string.voice_smart_turn_model_missing, formatBytes(VadModels.SMART_TURN.bytes)),
                )
            }
        }
    }
}

/** One detector's download: fetch, progress, delete. */
@Composable
private fun VadDownloadRow(settings: LiveSettings, download: VadModels.Download, title: String, missing: String) {
    val filesDir = LocalContext.current.filesDir
    val status by download.status.collectAsState()
    LaunchedEffect(download) { download.refresh(filesDir) }
    OnnxModelRow(
        settings = settings,
        title = title,
        status = status,
        ready = stringResource(R.string.voice_vad_model_ready, formatBytes(download.bytes)),
        missing = missing,
        bytes = download.bytes,
        onDownload = { download.download(filesDir) },
        onCancel = download::cancel,
        onDelete = { download.delete(filesDir) },
    )
}

/**
 * The model of the user's own: pick an `.onnx` file, which is copied in only
 * once ONNX Runtime has found it to be a Silero or TEN model sherpa-onnx takes.
 */
@Composable
private fun CustomVadRow() {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val scope = rememberCoroutineScope()
    // Bumped after an import or a delete, so the row reads the disk again.
    var version by remember { mutableIntStateOf(0) }
    val kind = remember(version) { VadModels.customKind(filesDir) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    ?.use { VadModels.import(filesDir, it) }
                    ?: VadModels.ImportResult.FAILED
            }
            version++
            val message = when (result) {
                VadModels.ImportResult.SILERO -> R.string.voice_vad_custom_imported_silero
                VadModels.ImportResult.TEN -> R.string.voice_vad_custom_imported_ten
                VadModels.ImportResult.NOT_A_VAD -> R.string.voice_vad_custom_not_vad
                VadModels.ImportResult.TOO_LARGE -> R.string.voice_vad_custom_too_large
                VadModels.ImportResult.NO_RUNTIME -> R.string.voice_vad_custom_no_runtime
                VadModels.ImportResult.FAILED -> R.string.voice_vad_custom_failed
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val title = stringResource(R.string.voice_vad_custom_model_title)
    WmRow(
        title = title,
        subtitle = when (kind) {
            SherpaNative.VAD_SILERO -> stringResource(R.string.voice_vad_custom_have_silero)
            SherpaNative.VAD_TEN -> stringResource(R.string.voice_vad_custom_have_ten)
            else -> stringResource(R.string.voice_vad_custom_none)
        },
        swipeActions = if (kind != null) {
            listOf(
                deleteSwipeAction(stringResource(R.string.models_delete_model_desc, title)) {
                    VadModels.deleteCustom(filesDir)
                    version++
                },
            )
        } else {
            emptyList()
        },
        trailing = {
            if (kind != null) {
                IconButton(onClick = {
                    VadModels.deleteCustom(filesDir)
                    version++
                }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.models_delete_model_desc, title))
                }
            } else {
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.voice_vad_custom_pick)) }
            }
        },
    )
    // Nothing to download says the runtime is still on its way, so say it here.
    if (kind != null && !SherpaNative.moduleReady) CaptionText(stringResource(R.string.voice_vad_runtime_pending))
}

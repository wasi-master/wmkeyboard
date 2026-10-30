package com.wasimaster.wmkeyboard.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation
import com.wasimaster.wmkeyboard.core.voice.punctuation.PunctuationChoice
import com.wasimaster.wmkeyboard.core.voice.punctuation.PunctuationCustomModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Add punctuation and capitals, for the offline engines that write bare
 * words (Sherpa, Omnilingual). On, it shows the model's download row where
 * the build has ONNX Runtime; without the model, English dictation still
 * gets Sherpa's English punctuation model if that is downloaded.
 */
@Composable
internal fun VoicePunctuationSetting(repository: SettingsRepository, settings: LiveSettings, scope: CoroutineScope) {
    val on = settings.watch { it.whisper.punctuate }
    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleSetting(
            R.string.voice_auto_punctuation_title,
            stringResource(R.string.voice_auto_punctuation_subtitle),
            on,
            info = stringResource(R.string.voice_auto_punctuation_info),
            default = SettingsDefaults.whisper.punctuate,
        ) { scope.launch { repository.setVoicePunctuate(it) } }
        if (on) {
            if (DictationPunctuation.runtimeShipped) {
                OnnxModuleBanner(settings)
                val choice = settings.watch { it.whisper.punctuationModel }
                ChoiceSetting(
                    R.string.voice_punctuation_choice_title,
                    subtitle = stringResource(R.string.voice_punctuation_choice_subtitle),
                    options = listOf(
                        PunctuationChoice.AUTO to stringResource(R.string.voice_punctuation_choice_auto),
                        PunctuationChoice.MULTILINGUAL to stringResource(R.string.voice_punctuation_choice_multilingual),
                        PunctuationChoice.ENGLISH to stringResource(R.string.voice_punctuation_choice_english),
                    ),
                    selected = choice,
                    default = SettingsDefaults.whisper.punctuationModel,
                ) { scope.launch { repository.setVoicePunctuationModel(it) } }
                if (choice != PunctuationChoice.ENGLISH) {
                    PunctuationModelRow(settings)
                } else if (!EtStore.isDownloaded(LocalContext.current.filesDir, EtCatalog.sherpaPunctuation)) {
                    CaptionText(stringResource(R.string.voice_punctuation_choice_english_missing))
                }
                PunctuationCustomModelRows(settings, scope)
            } else {
                CaptionText(stringResource(R.string.voice_auto_punctuation_not_in_build))
            }
        }
    }
}

/** The punctuation models the user brought (#1005): what is here, and the import. */
@Composable
private fun PunctuationCustomModelRows(settings: LiveSettings, scope: CoroutineScope) {
    val filesDir = LocalContext.current.filesDir
    var models by remember { mutableStateOf(PunctuationCustomModels.load(filesDir)) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        problem = null
        picked = uris
    }
    for (model in models) {
        WmRow(
            title = model.name,
            subtitle = model.languages.joinToString(", "),
            trailing = {
                IconButton(onClick = {
                    PunctuationCustomModels.delete(filesDir, model)
                    models = PunctuationCustomModels.models
                }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.models_delete_model_desc, model.name),
                    )
                }
            },
        )
    }
    WmRow(
        title = stringResource(R.string.voice_punctuation_custom_add_title),
        subtitle = stringResource(R.string.voice_punctuation_custom_add_subtitle),
        leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
        onClick = { if (!busy) pick.launch(arrayOf("*/*")) },
    )
    problem?.let { CaptionText(stringResource(it), error = true) }
    if (picked.isEmpty()) return
    ModelImportDialog(
        settings = settings,
        title = stringResource(R.string.voice_punctuation_custom_add_title),
        picked = picked,
        scope = scope,
        onDismiss = { picked = emptyList() },
        onBusy = { busy = it },
        install = { files, name, languages -> PunctuationCustomModels.install(filesDir, files, name, languages) },
    ) { outcome ->
        models = PunctuationCustomModels.models
        problem = when (outcome) {
            PunctuationCustomModels.Result.INSTALLED -> null
            PunctuationCustomModels.Result.MISSING_FILES -> R.string.voice_punctuation_custom_missing_files
            PunctuationCustomModels.Result.NOT_PUNCTUATION -> R.string.voice_punctuation_custom_not_punctuation
            null -> R.string.voice_sherpa_custom_failed
        }
    }
}

/** The multilingual punctuation model: download, progress, delete. */
@Composable
private fun PunctuationModelRow(settings: LiveSettings) {
    val filesDir = LocalContext.current.filesDir
    val status by DictationPunctuation.status.collectAsState()
    LaunchedEffect(Unit) { DictationPunctuation.refresh(filesDir) }
    OnnxModelRow(
        settings = settings,
        title = stringResource(R.string.voice_auto_punctuation_model_title),
        status = status,
        ready = stringResource(R.string.voice_auto_punctuation_model_ready, formatBytes(DictationPunctuation.BYTES)),
        missing = stringResource(R.string.voice_auto_punctuation_model_missing, formatBytes(DictationPunctuation.BYTES)),
        bytes = DictationPunctuation.BYTES,
        onDownload = { DictationPunctuation.download(filesDir) },
        onCancel = DictationPunctuation::cancel,
        onDelete = { DictationPunctuation.delete(filesDir) },
    )
}

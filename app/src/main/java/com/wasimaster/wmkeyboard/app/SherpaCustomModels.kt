package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCustomModels
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The models the user brought to [engine] (#1005 for Sherpa, #1437 for
 * Qwen3-ASR): what is here, the import from files picked on the phone, and the
 * one from a Hugging Face repository.
 */
@Composable
internal fun EtCustomModelsGroup(engine: EtEngine, settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val scope = rememberCoroutineScope()
    var models by remember(engine) { mutableStateOf(EtCustomModels.forEngine(filesDir, engine)) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        problem = null
        picked = uris
    }
    val qwen3 = engine == EtEngine.QWEN3
    SettingsGroup(
        stringResource(R.string.voice_sherpa_custom_group),
        info = stringResource(if (qwen3) R.string.voice_qwen3_custom_info else R.string.voice_sherpa_custom_info),
    ) {
        for (model in models) {
            item {
                WmRow(
                    title = model.label,
                    subtitle = model.languages.orEmpty().joinToString(", "),
                    trailing = {
                        IconButton(onClick = {
                            EtCustomModels.delete(filesDir, model)
                            models = EtCustomModels.forEngine(filesDir, engine)
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.models_delete_model_desc, model.label),
                            )
                        }
                    },
                )
            }
        }
        item {
            Column {
                WmRow(
                    title = stringResource(R.string.voice_sherpa_custom_add_title),
                    subtitle = stringResource(
                        if (qwen3) R.string.voice_qwen3_custom_add_subtitle else R.string.voice_sherpa_custom_add_subtitle,
                    ),
                    leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = { if (!busy) pick.launch(arrayOf("*/*")) },
                )
                problem?.let { CaptionText(stringResource(it), error = true) }
            }
        }
        // The row draws nothing on the offline build; no empty card for it either.
        item(visible = !OfflineBuild.isThisBuild, waitsOnSetting = false) {
            HuggingFaceImportRow(
                if (qwen3) HfTarget.QWEN3 else HfTarget.SHERPA,
                settings,
                onInstalled = { models = EtCustomModels.forEngine(filesDir, engine) },
            )
        }
    }
    if (picked.isEmpty()) return
    ModelImportDialog(
        settings = settings,
        title = stringResource(R.string.voice_sherpa_custom_add_title),
        picked = picked,
        scope = scope,
        onDismiss = { picked = emptyList() },
        onBusy = { busy = it },
        install = { files, name, languages -> EtCustomModels.install(filesDir, files, name, languages) },
    ) { outcome ->
        models = EtCustomModels.forEngine(filesDir, engine)
        problem = customModelProblem(outcome)
    }
}

/**
 * Asks what to call the [picked] files and which languages they are for, then
 * copies them to the cache, since a picker hands out addresses rather than paths,
 * and gives them to [install]. [onDone] has null when that failed. [scope] is the
 * caller's: this dialog is gone before the copying ends.
 */
@Composable
internal fun <T> ModelImportDialog(
    settings: LiveSettings,
    title: String,
    picked: List<Uri>,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onBusy: (Boolean) -> Unit,
    install: (files: List<File>, name: String, languages: Set<String>) -> T,
    onDone: (T?) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var languages by remember {
        mutableStateOf(settings.value.enabledLanguages.firstOrNull()?.id.orEmpty().substringBefore('_').substringBefore('-'))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.voice_sherpa_custom_name)) },
                )
                OutlinedTextField(
                    value = languages,
                    onValueChange = { languages = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.voice_sherpa_custom_languages)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && languages.isNotBlank(),
                onClick = {
                    val uris = picked
                    val chosenName = name.trim()
                    val chosenLanguages = languages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                    onDismiss()
                    onBusy(true)
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            val temp = File(context.cacheDir, "model-import").apply { deleteRecursively(); mkdirs() }
                            runCatching {
                                val files = uris.mapNotNull { uri ->
                                    val shown = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                                        ?: return@mapNotNull null
                                    val file = File(temp, File(shown).name)
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        file.outputStream().use { input.copyTo(it) }
                                    } ?: return@mapNotNull null
                                    file
                                }
                                install(files, chosenName, chosenLanguages)
                            }.also { temp.deleteRecursively() }.getOrNull()
                        }
                        onBusy(false)
                        onDone(outcome)
                    }
                },
            ) { Text(stringResource(CommonR.string.common_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

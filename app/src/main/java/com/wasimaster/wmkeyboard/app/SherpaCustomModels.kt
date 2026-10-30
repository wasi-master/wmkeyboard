package com.wasimaster.wmkeyboard.app

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
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Sherpa models the user brought (#1005): what is here, and the import. The
 * picked files are copied to the cache to be read, since a picker hands out
 * addresses rather than paths.
 */
@Composable
internal fun SherpaCustomModels(settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(EtCustomModels.load(filesDir)) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        problem = null
        picked = uris
    }
    SettingsGroup(
        stringResource(R.string.voice_sherpa_custom_group),
        info = stringResource(R.string.voice_sherpa_custom_info),
    ) {
        for (model in models) {
            item {
                WmRow(
                    title = model.label,
                    subtitle = model.languages.orEmpty().joinToString(", "),
                    trailing = {
                        IconButton(onClick = {
                            EtCustomModels.delete(filesDir, model)
                            models = EtCustomModels.models
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
                    subtitle = stringResource(R.string.voice_sherpa_custom_add_subtitle),
                    leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = { if (!busy) pick.launch(arrayOf("*/*")) },
                )
                problem?.let { CaptionText(stringResource(it), error = true) }
            }
        }
    }
    if (picked.isEmpty()) return
    var name by remember { mutableStateOf("") }
    var languages by remember {
        mutableStateOf(settings.value.enabledLanguages.firstOrNull()?.id.orEmpty().substringBefore('_').substringBefore('-'))
    }
    AlertDialog(
        onDismissRequest = { picked = emptyList() },
        title = { Text(stringResource(R.string.voice_sherpa_custom_add_title)) },
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
                enabled = name.isNotBlank() && languages.isNotBlank() && !busy,
                onClick = {
                    val uris = picked
                    picked = emptyList()
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            val temp = File(context.cacheDir, "sherpa-import").apply { deleteRecursively(); mkdirs() }
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
                                EtCustomModels.install(
                                    filesDir,
                                    files,
                                    name.trim(),
                                    languages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                                )
                            }.also { temp.deleteRecursively() }.getOrNull()
                        }
                        busy = false
                        models = EtCustomModels.models
                        problem = when (outcome) {
                            EtCustomModels.Result.INSTALLED -> null
                            EtCustomModels.Result.NOT_TRANSDUCER -> R.string.voice_sherpa_custom_not_transducer
                            EtCustomModels.Result.NOT_STREAMING -> R.string.voice_sherpa_custom_not_streaming
                            EtCustomModels.Result.NEEDS_VAD -> R.string.voice_sherpa_custom_needs_vad
                            null -> R.string.voice_sherpa_custom_failed
                        }
                    }
                },
            ) { Text(stringResource(CommonR.string.common_import)) }
        },
        dismissButton = {
            TextButton(onClick = { picked = emptyList() }) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

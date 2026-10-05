package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.VoiceServerOverride
import com.wasimaster.wmkeyboard.core.settings.VoiceServerTarget
import com.wasimaster.wmkeyboard.core.settings.serverFor
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.TranscriptionClient
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.core.voice.WavEncoder
import com.wasimaster.wmkeyboard.core.voice.VoiceClipFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A model, or a whole other server, for each language the user types in
 * (#389). Some models are far better at one language than another, and
 * dictation already follows the layout's language, so the choice is made per
 * language the same way offline Whisper's model routing is.
 *
 * Only drawn with two or more languages enabled: with one, the main server is
 * that language's server, and the group would repeat it.
 */
@Composable
internal fun VoiceServerLanguages(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val languages = settings.watch { it.enabledLanguages }
    if (languages.size < 2) return
    val byLang = settings.watch { it.whisper.serverByLang }
    var editing by remember { mutableStateOf<LanguageDef?>(null) }
    SettingsGroup(
        stringResource(R.string.voice_server_languages_group),
        info = stringResource(R.string.voice_server_languages_info),
    ) {
        for (language in languages) {
            item {
                val own = byLang[language.id]
                WmRow(
                    title = language.englishName,
                    subtitle = when {
                        own == null || own.isEmpty() -> stringResource(R.string.voice_server_lang_main)
                        own.url.isNotBlank() -> stringResource(R.string.voice_server_lang_server, own.url)
                        else -> stringResource(R.string.voice_server_lang_model, own.model)
                    },
                    trailing = {
                        Text(
                            stringResource(R.string.voice_server_lang_change),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { editing = language },
                )
            }
        }
    }
    editing?.let { language ->
        VoiceServerLanguageDialog(
            language = language,
            current = byLang[language.id] ?: VoiceServerOverride(),
            resolve = { draft ->
                settings.value.whisper.copy(serverByLang = mapOf(language.id to draft)).serverFor(language.id)
            },
            onSave = { value ->
                scope.launch { repository.setVoiceServerForLanguage(language.id, value) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * Edits one language's entry. The route and key only show once an address is
 * typed: without one the language rides the main server, whose route and key
 * are the main ones.
 */
@Composable
private fun VoiceServerLanguageDialog(
    language: LanguageDef,
    current: VoiceServerOverride,
    resolve: (VoiceServerOverride) -> VoiceServerTarget,
    onSave: (VoiceServerOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(current.url) }
    var path by remember { mutableStateOf(current.path) }
    var model by remember { mutableStateOf(current.model) }
    var key by remember { mutableStateOf(current.key) }
    val ownServer = url.isNotBlank()
    fun draft() = if (ownServer) {
        VoiceServerOverride(url, path, model, key)
    } else {
        VoiceServerOverride(model = model)
    }
    val rail = rememberScrollRailState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_server_lang_dialog_title, language.englishName)) },
        text = {
            ScrollRail(state = rail, modifier = Modifier.heightIn(max = 480.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogField(
                        stringResource(R.string.voice_server_url_label),
                        url,
                        stringResource(R.string.voice_server_lang_url_hint),
                        keyboardType = KeyboardType.Uri,
                    ) { url = it }
                    if (ownServer) {
                        DialogField(
                            stringResource(R.string.voice_server_path_label),
                            path,
                            stringResource(R.string.voice_server_path_hint),
                            keyboardType = KeyboardType.Uri,
                        ) { path = it }
                    }
                    DialogField(
                        stringResource(R.string.voice_server_model_label),
                        model,
                        stringResource(
                            if (ownServer) R.string.voice_server_lang_own_model_hint
                            else R.string.voice_server_lang_model_hint,
                        ),
                    ) { model = it }
                    if (ownServer) {
                        DialogField(
                            stringResource(R.string.voice_server_key_label),
                            key,
                            stringResource(R.string.voice_server_key_hint),
                            secret = true,
                        ) { key = it }
                    }
                    VoiceServerLanguageTest { resolve(draft()) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft()) }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            Row {
                if (!current.isEmpty()) {
                    TextButton(onClick = { onSave(VoiceServerOverride()) }) {
                        Text(stringResource(R.string.voice_server_lang_reset))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun DialogField(
    label: String,
    value: String,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else
            androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The main screen's test, for what the dialog holds right now: one second of
 * silence to where this language's clips would go, before anything is saved.
 */
@Composable
private fun VoiceServerLanguageTest(target: () -> VoiceServerTarget) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val testing = stringResource(R.string.voice_server_test_running)
    val ok = stringResource(R.string.voice_server_test_ok)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            status ?: stringResource(R.string.voice_server_test_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = !running,
            onClick = {
                val t = target()
                running = true
                status = testing
                scope.launch {
                    status = runCatching {
                        withContext(Dispatchers.IO) {
                            TranscriptionClient.transcribe(
                                t.url, t.key, t.model, null,
                                WavEncoder.encode(FloatArray(VoiceClipFormat.SAMPLE_RATE)),
                                path = t.path,
                            )
                        }
                    }.fold(onSuccess = { ok }, onFailure = { ToolHttp.friendlyMessage(context, it) })
                    running = false
                }
            },
        ) { Text(stringResource(R.string.voice_server_test_action)) }
    }
}

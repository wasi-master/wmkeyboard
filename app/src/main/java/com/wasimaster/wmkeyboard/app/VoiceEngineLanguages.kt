package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.RomanizedSpeech
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.VoiceChoiceRef
import com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled
import com.wasimaster.wmkeyboard.core.settings.serverFor
import com.wasimaster.wmkeyboard.core.voice.VoiceChoice
import com.wasimaster.wmkeyboard.core.voice.VoiceChoices
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import kotlinx.coroutines.launch

/**
 * [languages] as dictation hears them: Hinglish as Hindi, Banglish as Bangla
 * (#948), each once. The keyboard looks a language's engine, model and server
 * up under the spoken language, so a screen that listed `hi_rom` stored a
 * choice nothing read, and offered a Hinglish-only user no Hindi models at all
 * (Telegram 6331).
 */
internal fun voiceLanguages(languages: List<LanguageDef>): List<LanguageDef> =
    languages.map { language -> RomanizedSpeech.spokenAs(language.id)?.let(LanguageRegistry::byId) ?: language }
        .distinctBy { it.id }

/**
 * The engine, and the model, for each of the user's languages: English on the
 * system recognizer and Bangla on a Whisper model, say. A language left on
 * the default follows the Recognition engine row. The voice panel's engine
 * chip, when it is switched on, edits the same choice.
 */
@Composable
internal fun VoiceEngineLanguagesRow(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val byLang = settings.watch { it.whisper.voiceByLang }
    val languages = settings.watch { voiceLanguages(it.enabledLanguages) }
    var open by remember { mutableStateOf(false) }
    NavRow(
        R.string.voice_engine_languages_title,
        subtitle = stringResource(R.string.voice_engine_languages_subtitle),
        // map, not joinToString's transform: only an inline lambda may call
        // the composable label.
        value = languages.filter { byLang.containsKey(it.id) }
            .map { language ->
                "${language.englishName}: ${voiceChoiceLabel(VoiceChoiceRef.parse(byLang.getValue(language.id)))}"
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", "),
    ) { open = true }
    if (open) {
        VoiceEngineLanguagesDialog(
            languageIds = languages.map { it.id },
            byLang = byLang,
            globalEngine = settings.value.whisper.engine,
            serverConfigured = { id -> settings.value.whisper.serverFor(id).url.isNotBlank() },
            onPick = { id, choice -> scope.launch { repository.setVoiceForLanguage(id, choice) } },
            onDismiss = { open = false },
        )
    }
}

/**
 * The languages, each with its engine; a tap on one swaps the list for that
 * language's choices, and a pick goes back to the list.
 */
@Composable
private fun VoiceEngineLanguagesDialog(
    languageIds: List<String>,
    byLang: Map<String, String>,
    globalEngine: String,
    serverConfigured: (String) -> Boolean,
    onPick: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<String?>(null) }
    val defaultLabel = stringResource(
        R.string.voice_engine_languages_default,
        voiceChoiceLabel(VoiceChoiceRef(globalEngine, null)),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                editing?.let {
                    stringResource(R.string.voice_engine_languages_dialog_title, LanguageRegistry.byId(it).englishName)
                } ?: stringResource(R.string.voice_engine_languages_title),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val id = editing
                if (id == null) {
                    CaptionText(stringResource(R.string.voice_engine_languages_info))
                    for (languageId in languageIds) {
                        val own = byLang[languageId]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editing = languageId }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(LanguageRegistry.byId(languageId).englishName)
                            Text(
                                own?.let { voiceChoiceLabel(VoiceChoiceRef.parse(it)) } ?: defaultLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (own == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                } else {
                    val choices = remember(id) {
                        VoiceChoices.choices(
                            context.filesDir, id,
                            whisperEnabled = isWhisperEnabled(),
                            serverConfigured = serverConfigured(id),
                        )
                    }
                    val current = byLang[id].orEmpty()
                    ChoiceRadioRow(defaultLabel, current.isEmpty()) {
                        onPick(id, "")
                        editing = null
                    }
                    for (choice in choices) {
                        ChoiceRadioRow(voiceChoiceLabel(choice), choice.key == current) {
                            onPick(id, choice.key)
                            editing = null
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editing == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_ok)) }
            } else {
                TextButton(onClick = { editing = null }) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun ChoiceRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun voiceChoiceLabel(choice: VoiceChoice): String {
    val name = voiceEngineLabel(choice.engine)
    return choice.modelLabel?.let { "$name · $it" } ?: name
}

/** A stored choice: the engine's name, and the model's own name when one is pinned. */
@Composable
private fun voiceChoiceLabel(ref: VoiceChoiceRef): String {
    val name = voiceEngineLabel(ref.engine)
    val model = ref.modelId?.let { id ->
        WhisperCatalog.byId(id)?.displayName ?: EtCatalog.byId(id)?.label ?: id
    }
    return model?.let { "$name · $it" } ?: name
}

@Composable
private fun voiceEngineLabel(engine: String): String = stringResource(
    when (engine) {
        "whistle" -> R.string.voice_engine_whistle
        "whisper" -> R.string.voice_engine_whisper
        "asr4all" -> R.string.voice_engine_asr4all
        "parakeet" -> R.string.voice_engine_parakeet
        "moonshine" -> R.string.voice_engine_moonshine
        "sherpa" -> R.string.voice_engine_sherpa
        "omnilingual" -> R.string.voice_engine_omnilingual
        "vosk" -> R.string.voice_engine_vosk
        "server" -> R.string.voice_engine_server
        else -> R.string.voice_engine_system
    },
)

package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled
import com.wasimaster.wmkeyboard.core.settings.serverFor
import com.wasimaster.wmkeyboard.core.voice.VoiceChoices
import kotlinx.coroutines.launch

/**
 * Which engines the voice tool's engine chip, and its pills, offer (#1477):
 * a tick per engine that can take one of the user's languages on this phone,
 * the same list the chip builds, so an engine nobody uses can be left out of
 * it. The value names the ones left out.
 */
@Composable
internal fun VoiceEnginesShownRow(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hidden = settings.watch { it.voiceBar.hiddenEngines }
    val languageIds = settings.watch { voiceLanguages(it.enabledLanguages).map { language -> language.id } }
    var open by remember { mutableStateOf(false) }
    // map, not joinToString's transform: only an inline lambda may call the composable name.
    val hiddenNames = VoiceChoices.ENGINE_ORDER.filter { it in hidden }.map { voiceEngineTitle(it) }
    NavRow(
        R.string.voice_engines_shown_title,
        subtitle = stringResource(R.string.voice_engines_shown_subtitle),
        value = if (hiddenNames.isEmpty()) {
            stringResource(R.string.voice_engines_shown_all_value)
        } else {
            stringResource(R.string.voice_engines_shown_hidden_value, hiddenNames.joinToString(", "))
        },
    ) { open = true }
    if (!open) return
    // What the chip could list for any of the languages, and whatever is left
    // out already, so an engine can always be put back.
    val engines = remember(languageIds, hidden) {
        val offered = languageIds.flatMap { id ->
            VoiceChoices.choices(
                context.filesDir, id,
                whisperEnabled = isWhisperEnabled(),
                serverConfigured = settings.value.whisper.serverFor(id).url.isNotBlank(),
            ).map { it.engine }
        }.toSet() + hidden
        VoiceChoices.ENGINE_ORDER.filter { it in offered } + (offered - VoiceChoices.ENGINE_ORDER.toSet())
    }
    var picked by remember { mutableStateOf(hidden) }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.voice_engines_shown_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                CaptionText(stringResource(R.string.voice_engines_shown_info))
                for (engine in engines) {
                    val shown = engine !in picked
                    val toggle = { picked = if (shown) picked + engine else picked - engine }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = toggle),
                    ) {
                        Checkbox(checked = shown, onCheckedChange = { toggle() })
                        Text(voiceEngineTitle(engine))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    open = false
                    scope.launch { repository.setVoiceHiddenEngines(picked) }
                },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

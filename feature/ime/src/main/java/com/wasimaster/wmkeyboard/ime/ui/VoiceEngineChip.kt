package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.voice.VoiceChoice
import com.wasimaster.wmkeyboard.core.voice.VoiceChoices
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.VoiceBarAction

/**
 * The voice panel's engine chip: shown only when "Change engine from the
 * voice tool" is on. It names the engine the language being dictated uses,
 * and a tap lists what else can take that language on this phone, downloaded
 * models only. A pick is that language's own engine from then on, the same
 * choice as Settings → Voice typing → Engine for each language.
 */
internal fun KeyboardUiState.voiceEngineChipVisible(): Boolean =
    settings.voiceBar.enginePicker && !secureField && voice.engineChoices.isNotEmpty()

@Composable
internal fun VoiceEngineChip(
    state: KeyboardUiState,
    focused: Boolean,
    onAction: (VoiceBarAction) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val voice = state.voice
    val shape = RoundedCornerShape(kb.toolRadiusDp.dp)
    val engine = voice.engine.ifEmpty { state.settings.whisper.engine }
    val description = stringResource(R.string.ime_voice_engine_desc)
    Box(modifier = modifier) {
        Text(
            text = voiceChoiceName(engine, voice.engineServerName),
            color = if (voice.engineChoice.isEmpty()) kb.secondaryText else kb.toolbarIcon,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .padding(4.dp)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                .focusRing(focused, shape)
                .clickable(onClickLabel = description) {
                    feedback()
                    onMenuOpen(true)
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (menuOpen) {
            StripMenuScrim(onDismiss = { onMenuOpen(false) })
            VoiceEngineMenu(
                languageId = voice.engineLanguage,
                choices = voice.engineChoices,
                serverName = voice.engineServerName,
                current = voice.engineChoice,
                onDismiss = { onMenuOpen(false) },
                onPick = { key ->
                    onMenuOpen(false)
                    onAction(VoiceBarAction.PickEngine(voice.engineLanguage, key))
                },
                onMore = {
                    onMenuOpen(false)
                    feedback()
                    onOpenSettings()
                },
            )
        }
    }
}

/**
 * The engine chip as pills (#1477): every engine that can take the language
 * being dictated, minus the ones left out in settings, side by side to tap
 * between, the one in use filled. A tap is the same pick the chip's list
 * makes. Scrolls sideways when they do not fit.
 */
@Composable
internal fun VoiceEnginePills(
    state: KeyboardUiState,
    focused: Boolean,
    onAction: (VoiceBarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val voice = state.voice
    val shape = RoundedCornerShape(kb.toolRadiusDp.dp)
    val choices = voice.engineChoices
    val current = voice.currentEngineKey()
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .focusRing(focused, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (choice in choices) {
            val on = choice.key == current
            val name = voiceChoiceName(choice.engine, voice.engineServerName)
            // A second model of the same engine is told apart by its model's name.
            val label = if (choice.modelLabel != null && choices.count { it.engine == choice.engine } > 1) {
                "$name · ${choice.modelLabel}"
            } else {
                name
            }
            Text(
                text = label,
                color = if (on) kb.chipActiveText else kb.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(if (on) kb.chipActive else kb.chip)
                    .semantics { selected = on }
                    .clickable {
                        if (on) return@clickable
                        feedback()
                        onAction(VoiceBarAction.PickEngine(voice.engineLanguage, choice.key))
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The pill for the engine dictation uses now: the language's own pick when it
 * has one, else the first choice on the engine it follows from settings.
 */
internal fun com.wasimaster.wmkeyboard.ime.VoiceUi.currentEngineKey(): String =
    engineChoice.ifEmpty {
        engineChoices.firstOrNull { it.engine == engine }?.key.orEmpty()
    }

/**
 * The focus ring's press on the pills: the next engine along, round to the
 * first after the last, so the keys can walk them as a tap would.
 */
internal fun com.wasimaster.wmkeyboard.ime.VoiceUi.nextEngineKey(): String? {
    if (engineChoices.isEmpty()) return null
    val at = engineChoices.indexOfFirst { it.key == currentEngineKey() }
    return engineChoices[(at + 1).mod(engineChoices.size)].key
}

@Composable
private fun VoiceEngineMenu(
    languageId: String,
    choices: List<VoiceChoice>,
    serverName: String,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onMore: () -> Unit,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        properties = MenuPopupProperties,
    ) {
        Text(
            text = stringResource(
                R.string.ime_voice_engine_menu_title,
                LanguageRegistry.byId(languageId).displayName,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val entries = buildList {
            add("" to stringResource(R.string.ime_voice_engine_default))
            for (choice in choices) {
                val name = voiceChoiceName(choice.engine, serverName)
                add(choice.key to (choice.modelLabel?.let { "$name · $it" } ?: name))
            }
        }
        for ((key, label) in entries) {
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = {
                    if (key == current) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    } else {
                        Box(Modifier.size(18.dp))
                    }
                },
                onClick = { onPick(key) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ime_voice_engine_more)) },
            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = onMore,
        )
    }
}

/**
 * What the chip, its list and the pills call [engine]: the name the user gave
 * the transcription server when it is that and has one (#1477), else
 * [voiceEngineName].
 */
@Composable
internal fun voiceChoiceName(engine: String, serverName: String): String =
    if (engine == VoiceChoices.SERVER && serverName.isNotBlank()) serverName.trim() else voiceEngineName(engine)

/** The engine's own short name, as the chip and the menu show it. */
@Composable
internal fun voiceEngineName(engine: String): String = stringResource(
    when (engine) {
        "whistle" -> R.string.ime_voice_engine_whistle
        "whisper" -> R.string.ime_voice_engine_whisper
        "asr4all" -> R.string.ime_voice_engine_asr4all
        "parakeet" -> R.string.ime_voice_engine_parakeet
        "moonshine" -> R.string.ime_voice_engine_moonshine
        "sherpa" -> R.string.ime_voice_engine_sherpa
        "omnilingual" -> R.string.ime_voice_engine_omnilingual
        "qwen3" -> R.string.ime_voice_engine_qwen3
        "vosk" -> R.string.ime_voice_engine_vosk
        "server" -> R.string.ime_voice_engine_server
        else -> R.string.ime_voice_engine_system
    },
)

package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaLanguages
import com.wasimaster.wmkeyboard.core.settings.PersonaPrivacy
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.util.PlayServices
import kotlinx.coroutines.launch

/**
 * The persona quiz. Three questions whose answers trim the rest of the wizard
 * and, on a fresh run, apply a matching batch of defaults on the spot (the
 * option subtitles say exactly what). On a replay only the stored answers
 * change: re-gating pages is fine, silently rewriting settings is not.
 */
@Composable
internal fun PersonaPage(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    replay: Boolean,
    onToolsSeeded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val persona = settings.onboarding
    // Recomputed from the answers as a whole after each one, since the depth
    // answer picks the set and the privacy answer adds to it — see
    // [starterTools]. Held here so both call sites read the same device facts.
    val playServices = remember { PlayServices.available }
    val applyTools: suspend (OnboardingSettings) -> Unit = { next ->
        starterTools(next, playServices, ::isSupportedTool)?.let { repository.setEnabledTools(it) }
    }

    OnboardingSectionTitle(stringResource(R.string.onboarding_persona_q_languages))
    PersonaOption(
        title = stringResource(R.string.onboarding_persona_lang_one_title),
        subtitle = stringResource(R.string.onboarding_persona_lang_one_subtitle),
        selected = persona.personaLanguages == PersonaLanguages.ONE,
    ) {
        scope.launch {
            repository.setPersonaLanguages(PersonaLanguages.ONE)
            if (!replay) applyPresets(repository, presetsFor(PersonaLanguages.ONE))
        }
    }
    PersonaOption(
        title = stringResource(R.string.onboarding_persona_lang_many_title),
        subtitle = stringResource(R.string.onboarding_persona_lang_many_subtitle),
        selected = persona.personaLanguages == PersonaLanguages.MANY,
    ) {
        scope.launch {
            repository.setPersonaLanguages(PersonaLanguages.MANY)
            if (!replay) applyPresets(repository, presetsFor(PersonaLanguages.MANY))
        }
    }
    // Grows out of the answer it belongs to rather than snapping in and
    // shoving the questions below it down a notch.
    AnimatedVisibility(
        visible = persona.personaLanguages == PersonaLanguages.MANY,
        enter = if (settings.reduceMotion) fadeIn(snap()) else fadeIn() + expandVertically(),
        exit = if (settings.reduceMotion) fadeOut(snap()) else fadeOut() + shrinkVertically(),
    ) {
        OnboardingNotice(stringResource(R.string.onboarding_persona_many_notice))
    }

    OnboardingSectionTitle(stringResource(R.string.onboarding_persona_q_depth))
    val depthOptions = listOf(
        Triple(
            PersonaDepth.MINIMAL,
            R.string.onboarding_persona_depth_minimal_title,
            R.string.onboarding_persona_depth_minimal_subtitle,
        ),
        Triple(
            PersonaDepth.BALANCED,
            R.string.onboarding_persona_depth_balanced_title,
            R.string.onboarding_persona_depth_balanced_subtitle,
        ),
        Triple(
            PersonaDepth.POWER,
            R.string.onboarding_persona_depth_power_title,
            R.string.onboarding_persona_depth_power_subtitle,
        ),
    )
    for ((depth, titleRes, subtitleRes) in depthOptions) {
        PersonaOption(
            title = stringResource(titleRes),
            subtitle = stringResource(subtitleRes),
            selected = persona.personaDepth == depth,
        ) {
            scope.launch {
                repository.setPersonaDepth(depth)
                if (!replay) {
                    applyTools(persona.copy(personaDepth = depth))
                    // The answer just landed a tool set; the tools page must
                    // not seed its own over it.
                    onToolsSeeded()
                }
            }
        }
    }

    OnboardingSectionTitle(stringResource(R.string.onboarding_persona_q_privacy))
    PersonaOption(
        title = stringResource(R.string.onboarding_persona_privacy_standard_title),
        subtitle = stringResource(R.string.onboarding_persona_privacy_standard_subtitle),
        selected = persona.personaPrivacy == PersonaPrivacy.STANDARD,
    ) {
        scope.launch {
            repository.setPersonaPrivacy(PersonaPrivacy.STANDARD)
            // Answered after "extra strict" and then changed back: the set
            // still has incognito in it, so recompute rather than leave it.
            if (!replay) applyTools(persona.copy(personaPrivacy = PersonaPrivacy.STANDARD))
        }
    }
    PersonaOption(
        title = stringResource(R.string.onboarding_persona_privacy_strict_title),
        subtitle = stringResource(R.string.onboarding_persona_privacy_strict_subtitle),
        selected = persona.personaPrivacy == PersonaPrivacy.STRICT,
    ) {
        scope.launch {
            repository.setPersonaPrivacy(PersonaPrivacy.STRICT)
            if (!replay) {
                applyPresets(repository, presetsFor(PersonaPrivacy.STRICT))
                applyTools(persona.copy(personaPrivacy = PersonaPrivacy.STRICT))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** Performs the writes a persona answer stands for. */
private suspend fun applyPresets(repository: SettingsRepository, writes: List<PresetWrite>) {
    for (write in writes) when (write) {
        is PresetWrite.SpaceSwipes -> {
            repository.setSpaceShortSwipe(write.short)
            repository.setSpaceLongSwipe(write.long)
        }
        is PresetWrite.GlobeAsEmoji -> repository.setGlobeAsEmoji(write.value)
        is PresetWrite.LearnFromTyping -> repository.setLearnFromTyping(write.value)
        is PresetWrite.ClipboardHistory -> repository.setClipboardHistory(write.value)
        is PresetWrite.TypingStats -> repository.setTypingStatsEnabled(write.value)
    }
}

/**
 * One selectable answer. The whole card is the click target (a second target
 * on the radio would double-toggle under TalkBack), and the subtitle carries
 * the answer's consequences, so picking blind is impossible.
 */
@Composable
private fun PersonaOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp),
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

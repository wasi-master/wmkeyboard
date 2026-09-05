package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaPrivacy
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import kotlinx.coroutines.launch

/** How a discover card behaves. */
internal enum class DiscoverKind {
    /** A switch on the card turns the feature on or off right here. */
    TOGGLE,

    /** Nothing to flip mid-wizard; the card says what it is and where it lives. */
    EXPLORE,
}

/**
 * One feature worth showing off during setup. The catalog deliberately skips
 * what every keyboard has (glide, plain voice input, one-handed mode): a
 * discovery page full of table stakes teaches nobody anything.
 */
internal data class DiscoverFeature(
    val id: String,
    @StringRes val title: Int,
    @StringRes val pitch: Int,
    /** Extra caption: a permission note, or where an EXPLORE feature lives. */
    @StringRes val hint: Int = 0,
    val icon: ImageVector,
    val accent: Color,
    val kind: DiscoverKind,
    /** Filtered out where the tool is not in this build flavour. */
    val requiresTool: ToolbarTool? = null,
    val fullFlavorOnly: Boolean = false,
    val isOn: (KeyboardSettings) -> Boolean = { false },
    val setOn: (suspend (SettingsRepository, Boolean) -> Unit)? = null,
)

private val Chips = DiscoverFeature(
    id = "chips",
    title = R.string.onboarding_discover_chips_title,
    pitch = R.string.onboarding_discover_chips_pitch,
    icon = Icons.Outlined.Calculate,
    accent = Color(0xFF42A5F5),
    kind = DiscoverKind.TOGGLE,
    isOn = { it.smartSuggestions },
    setOn = { repo, on -> repo.setSmartSuggestions(on) },
)

private val Hotwords = DiscoverFeature(
    id = "hotwords",
    title = R.string.onboarding_discover_hotwords_title,
    pitch = R.string.onboarding_discover_hotwords_pitch,
    icon = Icons.Outlined.Bolt,
    accent = Color(0xFF26C6DA),
    kind = DiscoverKind.TOGGLE,
    isOn = { it.smartToolKeywords },
    setOn = { repo, on -> repo.setSmartToolKeywords(on) },
)

private val Toolbox = DiscoverFeature(
    id = "toolbox",
    title = R.string.onboarding_discover_toolbox_title,
    pitch = R.string.onboarding_discover_toolbox_pitch,
    hint = R.string.onboarding_discover_toolbox_hint,
    icon = Icons.Outlined.Widgets,
    accent = Color(0xFFFF7043),
    kind = DiscoverKind.EXPLORE,
)

private val Photos = DiscoverFeature(
    id = "photos",
    title = R.string.onboarding_discover_photos_title,
    pitch = R.string.onboarding_discover_photos_pitch,
    hint = R.string.onboarding_discover_photos_hint,
    icon = Icons.Outlined.Wallpaper,
    accent = Color(0xFFEC407A),
    kind = DiscoverKind.EXPLORE,
)

private val Clipboard = DiscoverFeature(
    id = "clipboard",
    title = R.string.onboarding_discover_clipboard_title,
    pitch = R.string.onboarding_discover_clipboard_pitch,
    icon = Icons.Outlined.ContentPaste,
    accent = Color(0xFF66BB6A),
    kind = DiscoverKind.TOGGLE,
    isOn = { it.clipboard.history },
    setOn = { repo, on -> repo.setClipboardHistory(on) },
)

private val Snippets = DiscoverFeature(
    id = "snippets",
    title = R.string.onboarding_discover_snippets_title,
    pitch = R.string.onboarding_discover_snippets_pitch,
    hint = R.string.onboarding_discover_snippets_hint,
    icon = Icons.AutoMirrored.Outlined.TextSnippet,
    accent = Color(0xFF7E57C2),
    kind = DiscoverKind.EXPLORE,
    requiresTool = ToolbarTool.SNIPPETS,
)

private val Otp = DiscoverFeature(
    id = "otp",
    title = R.string.onboarding_discover_otp_title,
    pitch = R.string.onboarding_discover_otp_pitch,
    hint = R.string.onboarding_discover_otp_hint,
    icon = Icons.Outlined.Pin,
    accent = Color(0xFFEF5350),
    kind = DiscoverKind.TOGGLE,
    isOn = { it.otp.enabled },
    setOn = { repo, on -> repo.setOtpChipEnabled(on) },
)

private val Fancy = DiscoverFeature(
    id = "fancy",
    title = R.string.onboarding_discover_fancy_title,
    pitch = R.string.onboarding_discover_fancy_pitch,
    icon = Icons.Outlined.TextFormat,
    accent = Color(0xFFFFB300),
    kind = DiscoverKind.TOGGLE,
    requiresTool = ToolbarTool.FANCY,
    isOn = { ToolbarTool.FANCY in it.enabledTools },
    setOn = { repo, on -> repo.setToolEnabled(ToolbarTool.FANCY, on) },
)

private val Ai = DiscoverFeature(
    id = "ai",
    title = R.string.onboarding_discover_ai_title,
    pitch = R.string.onboarding_discover_ai_pitch,
    hint = R.string.onboarding_discover_ai_hint,
    icon = Icons.Outlined.AutoAwesome,
    accent = Color(0xFF00ACC1),
    kind = DiscoverKind.TOGGLE,
    requiresTool = ToolbarTool.AI,
    isOn = { ToolbarTool.AI in it.enabledTools },
    setOn = { repo, on -> repo.setToolEnabled(ToolbarTool.AI, on) },
)

private val Whisper = DiscoverFeature(
    id = "whisper",
    title = R.string.onboarding_discover_whisper_title,
    pitch = R.string.onboarding_discover_whisper_pitch,
    hint = R.string.onboarding_discover_whisper_hint,
    icon = Icons.Outlined.Mic,
    accent = Color(0xFF26A69A),
    kind = DiscoverKind.EXPLORE,
    fullFlavorOnly = true,
)

/**
 * A switch rather than a tour stop, and shown to everyone (issue #41).
 *
 * Modes are the one feature here that changes the keyboard *without being
 * asked* — walk into another app and the emoji row and pinned tools are
 * different. That reads as the keyboard having lost the settings you just
 * chose unless you already know the feature exists, so setup says what it is
 * and offers the switch, rather than filing it under "power users only" where
 * the people it surprises never see it.
 */
private val Modes = DiscoverFeature(
    id = "modes",
    title = R.string.onboarding_discover_modes_title,
    pitch = R.string.onboarding_discover_modes_pitch,
    hint = R.string.onboarding_discover_modes_hint,
    icon = Icons.Outlined.Apps,
    accent = Color(0xFF7E57C2),
    kind = DiscoverKind.TOGGLE,
    isOn = { it.modesEnabled },
    setOn = { repo, on -> repo.setModesEnabled(on) },
)

/**
 * Incognito and the autocorrect toggle in one card: the point is not either
 * switch but that both live on the toolbar, flippable while typing, without a
 * settings trip.
 */
private val QuickToggles = DiscoverFeature(
    id = "quick_toggles",
    title = R.string.onboarding_discover_quick_toggles_title,
    pitch = R.string.onboarding_discover_quick_toggles_pitch,
    icon = Icons.Outlined.ToggleOn,
    accent = Color(0xFFEF5350),
    kind = DiscoverKind.TOGGLE,
    isOn = {
        ToolbarTool.INCOGNITO in it.enabledTools && ToolbarTool.AUTOCORRECT in it.enabledTools
    },
    setOn = { repo, on ->
        repo.setToolEnabled(ToolbarTool.INCOGNITO, on)
        repo.setToolEnabled(ToolbarTool.AUTOCORRECT, on)
    },
)

/**
 * How many cards each depth answer gets; enough to be a tour, never a wall.
 *
 * Both went up by one when the modes card moved to the head of the list
 * (issue #41): it is an insertion, not a replacement, and the cards it would
 * otherwise have pushed off the end are ones every persona was already seeing.
 */
private const val DISCOVER_CAP_MINIMAL = 6
private const val DISCOVER_CAP_BALANCED = 8

/**
 * The card list for a persona, in pitch order. Power users see everything,
 * strict-privacy users see the keyboard-side toggles first (theirs is the
 * persona that reaches for incognito) with the network-adjacent AI card moved
 * last, and everyone else gets the head of the list.
 */
internal fun discoverFeatures(
    persona: OnboardingSettings,
    whisperAvailable: Boolean,
    isToolSupported: (ToolbarTool) -> Boolean,
): List<DiscoverFeature> {
    // Modes sits second, right behind the chips: it is the one card here that
    // exists to warn as much as to sell, so every persona gets it and it is
    // never the one a cap trims off.
    val base = mutableListOf(
        Chips, Modes, Hotwords, Toolbox, Photos, Clipboard, Snippets, Otp, Fancy, Ai,
    )
    if (persona.personaDepth == PersonaDepth.POWER) {
        base += Whisper
    }
    if (persona.personaPrivacy == PersonaPrivacy.STRICT) {
        base.remove(Ai)
        base += Ai
        base.add(0, QuickToggles)
    }
    val available = base.filter { feature ->
        (feature.requiresTool?.let(isToolSupported) != false) &&
            (!feature.fullFlavorOnly || whisperAvailable)
    }
    return when (persona.personaDepth) {
        PersonaDepth.MINIMAL -> available.take(DISCOVER_CAP_MINIMAL)
        PersonaDepth.POWER -> available
        PersonaDepth.BALANCED, PersonaDepth.UNSET -> available.take(DISCOVER_CAP_BALANCED)
    }
}

/**
 * The feature tour, two cards to a row, each one showing the feature rather
 * than only describing it. Cards either flip a feature on the spot or, for the
 * ones with nothing to flip mid-wizard, say where to find them later; nothing
 * here navigates away, because there is nowhere to come back to mid-setup.
 *
 * A grid rather than a column of list rows: this is the only page of the
 * wizard that is a shop window, and eleven full-width rows read as a settings
 * screen — which is exactly the thing nobody scrolls to the bottom of.
 */
@Composable
internal fun DiscoverPage(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val features = remember(settings.onboarding) {
        discoverFeatures(
            persona = settings.onboarding,
            whisperAvailable = BuildConfig.ENABLE_WHISPER,
            isToolSupported = ::isSupportedTool,
        )
    }
    // The one card here that needs a grant the app cannot give itself. Asked
    // as the switch goes on, disclosure first, and never when the grant is
    // already there.
    val codesAccess = rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATION_CODES)
    val onToggle: (DiscoverFeature, Boolean) -> Unit = { feature, on ->
        feature.setOn?.let { write -> scope.launch { write(repository, on) } }
        if (feature.id == OTP_FEATURE_ID && on && !hasNotificationAccess(context)) codesAccess()
    }
    // Rows of two, built by hand: the wizard scrolls its pages in a plain
    // Column, so a lazy grid inside one would nest two vertical scrolls.
    for (pair in features.chunked(2)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 16.dp, vertical = 5.dp),
        ) {
            for (feature in pair) {
                DiscoverCard(
                    feature,
                    settings,
                    modifier = Modifier.weight(1f),
                    onToggle = { on -> onToggle(feature, on) },
                )
            }
            // Keeps a lone last card at half width instead of letting it
            // stretch across the row and read as a different kind of thing.
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** The discover card whose switch needs notification access. */
private const val OTP_FEATURE_ID = "otp"

@Composable
private fun DiscoverCard(
    feature: DiscoverFeature,
    settings: KeyboardSettings,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(10.dp),
    ) {
        DiscoverPreview(
            id = feature.id,
            accent = feature.accent,
            animate = !settings.reduceMotion,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                feature.icon,
                contentDescription = null,
                tint = feature.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(feature.title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(feature.pitch),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (feature.hint != 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(feature.hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // The switch is pushed to the bottom edge, so the two cards in a row
        // line their controls up however far apart their text runs.
        Spacer(Modifier.weight(1f))
        if (feature.kind == DiscoverKind.TOGGLE) {
            Spacer(Modifier.height(4.dp))
            Switch(
                checked = feature.isOn(settings),
                onCheckedChange = onToggle,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

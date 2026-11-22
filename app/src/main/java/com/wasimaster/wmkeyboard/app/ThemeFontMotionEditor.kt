package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.fonts.FontAxis
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.fonts.FontVariations
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_DECAY_RANGE
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_RADIUS_RANGE
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_SPEED_RANGE
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_SCALE_RANGE
import com.wasimaster.wmkeyboard.core.theme.FONT_MOTION_WOBBLE_RANGE
import com.wasimaster.wmkeyboard.core.theme.FontAxesSpec
import com.wasimaster.wmkeyboard.core.theme.FontMotionSpec
import com.wasimaster.wmkeyboard.core.theme.FontMotionTarget
import com.wasimaster.wmkeyboard.core.theme.FontMotionTrigger
import com.wasimaster.wmkeyboard.core.theme.KeyLightingPattern
import com.wasimaster.wmkeyboard.core.theme.MAX_FONT_MOTIONS
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.findThemeFamily
import com.wasimaster.wmkeyboard.core.theme.findThemeSpec
import com.wasimaster.wmkeyboard.core.theme.fontMotionTargetOrDefault
import com.wasimaster.wmkeyboard.core.theme.fontMotionTriggerOrNull
import com.wasimaster.wmkeyboard.core.theme.keyLightingPatternOrNull
import com.wasimaster.wmkeyboard.core.theme.replacingMember
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal fun themeFontsRoute(themeId: String): String = "theme_fonts/$themeId"

/** The font ids a theme draws with: its key font (the system font when it names none) and each script's. */
internal fun themeFontIds(theme: ThemeSpec): List<String> =
    listOf(theme.fontId ?: KeyboardFonts.DEFAULT_ID) + theme.scriptFontIds.values

/**
 * Whether the theme editor's Font axes and motion row has anything to offer:
 * one of the theme's own fonts is variable, or the library holds a variable
 * font the theme could switch to. Cheap, from what is already known; a Google
 * face only counts once the Fonts screen has asked about it.
 */
internal fun themeFontsAvailable(context: Context, theme: ThemeSpec): Boolean =
    fontAxesSupported &&
        (themeFontIds(theme).any { KeyboardFonts.isVariable(context, it) } || FontStore.get(context).hasVariableFont())

/**
 * Theme editor > Font axes and motion: the axis values this theme gives its
 * fonts ([ThemeSpec.fontAxes], [ThemeSpec.scriptFontAxes]) and how the key
 * labels move ([ThemeSpec.fontMotions]).
 */
@Composable
internal fun ThemeFontsScreen(repository: SettingsRepository, settings: LiveSettings, themeId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme = settings.watch { s ->
        findThemeSpec(themeId, s.customThemes)?.takeIf { s.customThemes.findThemeFamily(themeId) != null }
    }
    if (theme == null) {
        StateBanner(stringResource(R.string.theme_fonts_missing_body))
        return
    }
    fun update(change: (ThemeSpec) -> ThemeSpec) {
        scope.launch {
            val family = settings.value.customThemes.findThemeFamily(themeId) ?: return@launch
            repository.upsertCustomTheme(family.replacingMember(themeId, change))
        }
    }
    val store = remember { FontStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { store.backfillVariations() } }

    // Which of the theme's fonts the axis sliders are for: "" is the key font,
    // a script's name one of its own.
    var target by rememberSaveable(themeId) { mutableStateOf("") }
    val scripts = theme.scriptFontIds.keys.sorted()
    if (target.isNotEmpty() && target !in scripts) target = ""
    val keyFontId = theme.fontId ?: KeyboardFonts.DEFAULT_ID
    val fontId = if (target.isEmpty()) keyFontId else theme.scriptFontIds.getValue(target)

    if (scripts.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.theme_fonts_font_group_title)) {
            item {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = target.isEmpty(),
                        onClick = { target = "" },
                        label = { Text(stringResource(R.string.theme_fonts_key_font_chip)) },
                    )
                    for (script in scripts) {
                        val choices = ScriptId.entries.firstOrNull { it.name == script }
                            ?.let(KeyboardFonts::scriptFontChoices)
                        FilterChip(
                            selected = target == script,
                            onClick = { target = script },
                            label = { Text(choices?.let { stringResource(it.labelRes) } ?: script) },
                        )
                    }
                }
            }
        }
    }

    val lookup = rememberFontAxes(fontId, revision)
    val sample = ScriptId.entries.firstOrNull { it.name == target }
        ?.let(KeyboardFonts::scriptFontChoices)?.sample ?: LATIN_AXES_SAMPLE
    when (lookup) {
        AxesLookup.Checking -> StateBanner(stringResource(R.string.fonts_axes_checking_body))
        AxesLookup.Static -> StaticThemeFont(store, revision) { id ->
            update { t ->
                if (target.isEmpty()) {
                    t.copy(fontId = id)
                } else {
                    t.copy(scriptFontIds = t.scriptFontIds + (target to id))
                }
            }
        }
        is AxesLookup.Variable -> {
            if (settings.watch { it.fontAxes[fontId] } != null) {
                StateBanner(stringResource(R.string.theme_fonts_user_tuned_body))
            }
            FontAxesEditor(
                fontId = fontId,
                variations = lookup.variations,
                value = (if (target.isEmpty()) theme.fontAxes else theme.scriptFontAxes[target]) ?: FontAxesSpec(),
                sample = sample,
                info = stringResource(R.string.theme_fonts_axes_info),
                onChange = { next ->
                    val kept = next.takeIf { !it.isEmpty }
                    update { t ->
                        if (target.isEmpty()) {
                            t.copy(fontAxes = kept)
                        } else {
                            t.copy(
                                scriptFontAxes = if (kept == null) t.scriptFontAxes - target else t.scriptFontAxes + (target to kept),
                            )
                        }
                    }
                },
            )
        }
    }

    // The labels move in the key font, which on a script board is the face
    // the board draws in; the editor offers the key font's axes, or the first
    // variable script face's when the key font has none.
    val keyLookup = rememberFontAxes(keyFontId, revision)
    val motionAxes = (keyLookup as? AxesLookup.Variable)?.variations
        ?: theme.scriptFontIds.values.firstNotNullOfOrNull { KeyboardFonts.variations(context, it) }
    FontMotionsEditor(
        motions = theme.fontMotions,
        variations = motionAxes,
        onChange = { next -> update { t -> t.copy(fontMotions = next.take(MAX_FONT_MOTIONS)) } },
    )
    Spacer(Modifier.height(24.dp))
}

/** The theme's font has no axes: say so, and offer the library's variable fonts in its place. */
@Composable
private fun StaticThemeFont(store: FontStore, revision: Int, onUse: (String) -> Unit) {
    StateBanner(stringResource(R.string.theme_fonts_static_body))
    val variable = remember(revision) { store.textFonts().filter { it.variations?.isVariable == true } }
    if (variable.isEmpty()) return
    SettingsGroup {
        for (font in variable) {
            item(key = font.id) {
                WmRow(
                    title = stringResource(R.string.theme_fonts_use_font_title, font.name),
                    subtitle = stringResource(R.string.theme_fonts_use_font_body),
                    onClick = { onUse(FontStore.fontIdFor(font.id)) },
                )
            }
        }
    }
}

/**
 * The theme's font motions, one card each. [variations] are the axes the
 * labels can move along; null is a static key font, and the section says so
 * instead of offering motions that would draw nothing.
 */
@Composable
private fun FontMotionsEditor(
    motions: List<FontMotionSpec>,
    variations: FontVariations?,
    onChange: (List<FontMotionSpec>) -> Unit,
) {
    SettingsGroup(
        stringResource(R.string.theme_motion_section_title),
        foldKey = "theme/font_motion",
        info = stringResource(R.string.theme_motion_section_body),
    ) {
        if (variations == null) {
            item {
                WmRow(title = stringResource(R.string.theme_motion_static_body))
            }
        }
    }
    if (variations == null) return
    motions.forEachIndexed { index, motion ->
        FontMotionCard(
            index = index,
            motion = motion,
            variations = variations,
            onChange = { next -> onChange(motions.toMutableList().also { it[index] = next }) },
            onRemove = { onChange(motions.filterIndexed { i, _ -> i != index }) },
        )
    }
    if (motions.size < MAX_FONT_MOTIONS) {
        OutlinedButton(
            onClick = { onChange(motions + newFontMotion(variations)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.theme_motion_add_action)) }
    }
}

/**
 * A first motion that already shows something: the letters under the finger
 * swelling well heavier and a third bigger, or, on a font with no weight axis,
 * its first axis moved to the far end. A single weight step read as barely
 * moving on the phone.
 */
internal fun newFontMotion(variations: FontVariations): FontMotionSpec {
    val wght = variations.axis("wght")
    val axes = if (wght != null) {
        mapOf("wght" to wght.clamp((variations.restValue("wght", 500) ?: wght.default) + 400f))
    } else {
        variations.axes.firstOrNull { !it.hidden }?.let { mapOf(it.tag to it.max) }.orEmpty()
    }
    return FontMotionSpec(
        trigger = FontMotionTrigger.TOUCH.name,
        axes = axes,
        radiusDp = 110f,
        wobble = 0.45f,
        scale = 1.3f,
    )
}

@Composable
private fun FontMotionCard(
    index: Int,
    motion: FontMotionSpec,
    variations: FontVariations,
    onChange: (FontMotionSpec) -> Unit,
    onRemove: () -> Unit,
) {
    val trigger = fontMotionTriggerOrNull(motion.trigger) ?: FontMotionTrigger.TOUCH
    val pattern = keyLightingPatternOrNull(motion.pattern) ?: KeyLightingPattern.WAVE
    val moving = variations.axes.filter { it.tag in motion.axes }
    val addable = variations.axes.filter { it.tag !in motion.axes }
    SettingsGroup(stringResource(R.string.theme_motion_item_title, index + 1)) {
        item {
            ChoiceSetting(
                title = R.string.theme_motion_trigger_title,
                options = FontMotionTrigger.entries.map { it to stringResource(triggerLabel(it)) },
                selected = trigger,
                detail = { t -> ChoiceDetail(stringResource(triggerBody(t))) },
            ) { t ->
                onChange(
                    motion.copy(
                        trigger = t.name,
                        pattern = if (t == FontMotionTrigger.PATTERN) motion.pattern ?: KeyLightingPattern.WAVE.name else null,
                    ),
                )
            }
        }
        if (trigger == FontMotionTrigger.PATTERN) {
            item {
                ChoiceSetting(
                    title = R.string.theme_motion_pattern_title,
                    options = KeyLightingPattern.entries.map { it to stringResource(lightingPatternLabel(it)) },
                    selected = pattern,
                ) { p -> onChange(motion.copy(pattern = p.name)) }
            }
        }
        for (axis in moving) {
            item(key = "axis:${axis.tag}") {
                MotionAxisRow(axis, motion.axes.getValue(axis.tag), onSet = { v ->
                    onChange(motion.copy(axes = motion.axes + (axis.tag to axis.clamp(v))))
                }, onRemove = { onChange(motion.copy(axes = motion.axes - axis.tag)) })
            }
        }
        if (addable.isNotEmpty()) {
            item {
                WmRow(
                    title = stringResource(R.string.theme_motion_axes_title),
                    subtitle = stringResource(R.string.theme_motion_axes_body),
                    supporting = {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (axis in addable) {
                                AssistChip(
                                    onClick = {
                                        // Starts at the far end from rest, so it shows at once.
                                        val peak = if (axis.max - axis.default >= axis.default - axis.min) axis.max else axis.min
                                        onChange(motion.copy(axes = motion.axes + (axis.tag to peak)))
                                    },
                                    label = { Text(axis.name.ifBlank { axis.tag }) },
                                )
                            }
                        }
                    },
                )
            }
        }
        item {
            ToggleSetting(
                R.string.theme_motion_all_keys_title,
                stringResource(R.string.theme_motion_all_keys_body),
                checked = fontMotionTargetOrDefault(motion.target) == FontMotionTarget.ALL,
            ) { all -> onChange(motion.copy(target = if (all) FontMotionTarget.ALL.name else null)) }
        }
        if (trigger == FontMotionTrigger.PATTERN && !pattern.reactive) {
            item {
                SliderRow(
                    R.string.theme_motion_speed_title,
                    motion.speed,
                    FONT_MOTION_SPEED_RANGE,
                    { "×" + String.format(java.util.Locale.getDefault(), "%.2f", it) },
                    live = false,
                ) { onChange(motion.copy(speed = it)) }
            }
        }
        if (trigger == FontMotionTrigger.TOUCH || trigger == FontMotionTrigger.GLIDE) {
            item {
                SliderRow(
                    R.string.theme_motion_reach_title,
                    motion.radiusDp,
                    FONT_MOTION_RADIUS_RANGE,
                    { "${it.roundToInt()} dp" },
                    live = false,
                ) { onChange(motion.copy(radiusDp = it)) }
            }
        }
        val settles = trigger != FontMotionTrigger.SHIFT && !(trigger == FontMotionTrigger.PATTERN && !pattern.reactive)
        if (settles) {
            item {
                SliderRow(
                    R.string.theme_motion_settle_title,
                    motion.decayMs.toFloat(),
                    FONT_MOTION_DECAY_RANGE.first.toFloat()..FONT_MOTION_DECAY_RANGE.last.toFloat(),
                    { "${it.roundToInt()} ms" },
                    live = false,
                ) { onChange(motion.copy(decayMs = it.roundToInt())) }
            }
        }
        if (trigger == FontMotionTrigger.TOUCH || trigger == FontMotionTrigger.GLIDE || trigger == FontMotionTrigger.SHIFT) {
            item {
                SliderRow(
                    R.string.theme_motion_wobble_title,
                    motion.wobble,
                    FONT_MOTION_WOBBLE_RANGE,
                    { String.format(java.util.Locale.getDefault(), "%.2f", it) },
                    info = stringResource(R.string.theme_motion_wobble_body),
                    live = false,
                ) { onChange(motion.copy(wobble = it)) }
            }
        }
        item {
            SliderRow(
                R.string.theme_motion_scale_title,
                motion.scale,
                FONT_MOTION_SCALE_RANGE,
                { "×" + String.format(java.util.Locale.getDefault(), "%.2f", it) },
                info = stringResource(R.string.theme_motion_scale_body),
                live = false,
            ) { onChange(motion.copy(scale = it)) }
        }
        item {
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.theme_motion_remove_action)) }
        }
    }
}

@Composable
private fun MotionAxisRow(axis: FontAxis, peak: Float, onSet: (Float) -> Unit, onRemove: () -> Unit) {
    val name = axis.name.ifBlank { axis.tag }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            SliderRow(
                title = stringResource(R.string.theme_motion_axis_peak, name),
                value = peak,
                range = axis.min..axis.max,
                display = { formatAxisValue(axis, it) },
                live = false,
            ) { onSet(it) }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.theme_motion_axis_remove_desc, name))
        }
    }
}

private fun triggerLabel(trigger: FontMotionTrigger): Int = when (trigger) {
    FontMotionTrigger.PATTERN -> R.string.theme_motion_trigger_pattern
    FontMotionTrigger.TOUCH -> R.string.theme_motion_trigger_touch
    FontMotionTrigger.GLIDE -> R.string.theme_motion_trigger_glide
    FontMotionTrigger.SHIFT -> R.string.theme_motion_trigger_shift
    FontMotionTrigger.ENERGY -> R.string.theme_motion_trigger_energy
}

private fun triggerBody(trigger: FontMotionTrigger): Int = when (trigger) {
    FontMotionTrigger.PATTERN -> R.string.theme_motion_trigger_pattern_body
    FontMotionTrigger.TOUCH -> R.string.theme_motion_trigger_touch_body
    FontMotionTrigger.GLIDE -> R.string.theme_motion_trigger_glide_body
    FontMotionTrigger.SHIFT -> R.string.theme_motion_trigger_shift_body
    FontMotionTrigger.ENERGY -> R.string.theme_motion_trigger_energy_body
}

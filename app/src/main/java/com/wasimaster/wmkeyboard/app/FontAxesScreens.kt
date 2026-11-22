package com.wasimaster.wmkeyboard.app

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.fonts.FontAxis
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.fonts.FontVariations
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.theme.FontAxesSpec
import com.wasimaster.wmkeyboard.core.theme.FontRole
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The route of a font's axis screen; [fontId] carries a colon, so it travels encoded. */
internal fun fontAxesRoute(script: ScriptId, fontId: String): String =
    "fonts/axes/${script.name}/${Uri.encode(fontId)}"

/** Whether this phone can draw variation settings at all: Android 8 and later. */
internal val fontAxesSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

/** What a font says about its axes, as a screen waits for the answer. */
internal sealed interface AxesLookup {
    data object Checking : AxesLookup
    data object Static : AxesLookup
    data class Variable(val variations: FontVariations) : AxesLookup
}

/**
 * [fontId]'s axes: answered at once where they are known, else looked up off
 * the main thread. A Google face is asked of the provider once (its variable
 * file), and a library font imported before axes were recorded is read then.
 */
@Composable
internal fun rememberFontAxes(fontId: String, revision: Int = 0): AxesLookup {
    val context = LocalContext.current
    val known = remember(fontId, revision) { KeyboardFonts.variations(context, fontId) }
    val lookup by produceState<AxesLookup>(
        known?.let { AxesLookup.Variable(it) } ?: AxesLookup.Checking,
        fontId,
        revision,
    ) {
        if (known != null) {
            value = AxesLookup.Variable(known)
            return@produceState
        }
        value = AxesLookup.Checking
        val found = withContext(Dispatchers.IO) {
            if (fontId.startsWith(GOOGLE_ID_PREFIX)) {
                KeyboardFonts.probeGoogleAxes(context, fontId.removePrefix(GOOGLE_ID_PREFIX))
            } else {
                FontStore.get(context).backfillVariations()
                KeyboardFonts.variations(context, fontId)
            }
        }
        value = found?.let { AxesLookup.Variable(it) } ?: AxesLookup.Static
    }
    return lookup
}

private const val GOOGLE_ID_PREFIX = "google:"

/**
 * Appearance > Fonts > a font > Font axes: the user's own values for one
 * variable font. Kept per font, and above any theme's values for it.
 */
@Composable
internal fun FontAxesScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    scriptArg: String,
    fontId: String,
) {
    val scope = rememberCoroutineScope()
    val script = ScriptId.entries.firstOrNull { it.name == scriptArg } ?: ScriptId.LATIN
    val sample = KeyboardFonts.scriptFontChoices(script)?.sample ?: LATIN_AXES_SAMPLE
    if (!fontAxesSupported) {
        StateBanner(stringResource(R.string.fonts_axes_old_android_body))
        return
    }
    when (val lookup = rememberFontAxes(fontId)) {
        AxesLookup.Checking -> StateBanner(stringResource(R.string.fonts_axes_checking_body))
        AxesLookup.Static -> StateBanner(stringResource(R.string.fonts_axes_static_body))
        is AxesLookup.Variable -> FontAxesEditor(
            fontId = fontId,
            variations = lookup.variations,
            value = settings.watch { it.fontAxes[fontId] } ?: FontAxesSpec(),
            sample = sample,
            info = stringResource(R.string.fonts_axes_info),
            onChange = { next -> scope.launch { repository.setFontAxes(fontId, next) } },
        )
    }
    Spacer(Modifier.height(16.dp))
}

/** The line the axis screens preview a Latin font with: every letter, and the digits. */
internal const val LATIN_AXES_SAMPLE = "Sphinx of black quartz, judge my vow 0123456789"

/**
 * The axis sliders for one font, per place on the board ([FontRole]): shared by
 * the Fonts screen and the theme editor, which differ only in where [value]
 * is kept.
 *
 * [onChange] gets the whole spec back, each role held to the font's axes and
 * with values at the font's own default dropped, so what is stored stays as
 * small as what was actually changed.
 */
@Composable
internal fun FontAxesEditor(
    fontId: String,
    variations: FontVariations,
    value: FontAxesSpec,
    sample: String,
    info: String?,
    onChange: (FontAxesSpec) -> Unit,
    /** Whether to offer the places apart from the keys; a single key's own style has only the one. */
    roles: Boolean = true,
) {
    val context = LocalContext.current
    var role by rememberSaveable(fontId) { mutableStateOf(FontRole.KEYS) }
    val owns = value.ownsRole(role)
    val current = value.role(role)
    // The values under a finger mid-drag, ahead of the store: the sliders
    // write once, on release, and the preview follows this meanwhile. Dropped
    // as soon as anything new comes back from the store, the write's own echo
    // included.
    var draft by remember(fontId, role) { mutableStateOf<Map<String, Float>?>(null) }
    LaunchedEffect(current) { draft = null }
    val shown = draft ?: current
    val family = rememberAxesFamily(fontId, shown, variations)
    fun write(values: Map<String, Float>) = onChange(value.withRole(role, variations.clamp(values)))
    val visible = variations.axes.filter { !it.hidden }
    val hidden = variations.axes.filter { it.hidden }

    SettingsGroup(stringResource(R.string.fonts_axes_title), info = info) {
        if (roles) {
            item {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (r in FontRole.entries) {
                        FilterChip(
                            selected = r == role,
                            onClick = { role = r },
                            label = { Text(stringResource(roleLabel(r))) },
                        )
                    }
                    InfoButton(
                        title = stringResource(R.string.fonts_axes_title),
                        detail = stringResource(R.string.fonts_axes_role_info),
                    )
                }
            }
        }
        item {
            // The preview is the whole point of the screen: the sample drawn in
            // the font at exactly the values the sliders below hold.
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    sample,
                    fontFamily = family,
                    fontSize = 26.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!owns) {
            item {
                WmRow(
                    title = stringResource(R.string.fonts_axes_follow_keys_title),
                    subtitle = stringResource(R.string.fonts_axes_follow_keys_body),
                    trailing = {
                        TextButton(onClick = { onChange(value.withRole(role, value.keys)) }) {
                            Text(stringResource(R.string.fonts_axes_own_values_action))
                        }
                    },
                )
            }
        }
    }
    if (!owns) return

    if (variations.instances.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.fonts_axes_instances_title)) {
            item {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (instance in variations.instances) {
                        val held = variations.clamp(instance.coords)
                        FilterChip(
                            selected = held == variations.clamp(shown),
                            onClick = { write(instance.coords) },
                            label = { Text(instance.name) },
                        )
                    }
                }
            }
        }
    }

    SettingsGroup {
        for (axis in visible) {
            item(key = axis.tag) { AxisSlider(axis, variations, shown, { draft = it }, ::write) }
        }
        item {
            WmRow(
                title = stringResource(R.string.fonts_axes_reset_title),
                enabled = current.isNotEmpty(),
                onClick = {
                    onChange(value.withRole(role, if (role == FontRole.KEYS) emptyMap() else null))
                },
            )
        }
        if (role != FontRole.KEYS) {
            item {
                WmRow(
                    title = stringResource(R.string.fonts_axes_follow_keys_action),
                    onClick = { onChange(value.withRole(role, null)) },
                )
            }
        }
    }
    if (hidden.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.fonts_axes_hidden_group_title),
            foldKey = "fonts/axes/hidden",
            info = stringResource(R.string.fonts_axes_hidden_group_info),
        ) {
            for (axis in hidden) {
                item(key = axis.tag) { AxisSlider(axis, variations, shown, { draft = it }, ::write) }
            }
        }
    }
}

@Composable
private fun AxisSlider(
    axis: FontAxis,
    variations: FontVariations,
    current: Map<String, Float>,
    drag: (Map<String, Float>) -> Unit,
    write: (Map<String, Float>) -> Unit,
) {
    // Where the axis draws untouched, which for weight is the regular 400
    // rather than wherever the font's own default instance happens to sit.
    val rest = variations.restValue(axis.tag) ?: axis.default
    val defaultText = formatAxisValue(axis, rest)
    val valueFormat = stringResource(R.string.fonts_axes_value)
    // Not live: a write is a settings emission the whole app and the keyboard
    // answer, and a new cut of the font. The preview follows [drag] instead.
    SliderSetting(
        title = axis.name.ifBlank { axis.tag },
        value = current[axis.tag] ?: rest,
        range = axis.min..axis.max,
        display = { v -> String.format(valueFormat, formatAxisValue(axis, v), defaultText) },
        preview = { v -> drag(current + (axis.tag to v)) },
    ) { v -> write(current + (axis.tag to v)) }
}

/**
 * [fontId] cut at [axes], for a preview that follows a slider: built off the
 * main thread, its typefaces loaded there too, and only ever the newest value
 * asked for once the last build is done, so a fast drag skips steps rather
 * than queueing them. The values are snapped to [PREVIEW_STEPS] steps across
 * each axis, so dragging back over a stretch reuses the cuts already made.
 * Until the first build lands it is the one built here and now.
 */
@Composable
internal fun rememberAxesFamily(fontId: String, axes: Map<String, Float>, variations: FontVariations): FontFamily? {
    val context = LocalContext.current
    val resolver = LocalFontFamilyResolver.current
    val wanted by rememberUpdatedState(snapAxes(variations.clamp(axes), variations))
    var family by remember(fontId) { mutableStateOf(KeyboardFonts.family(context, fontId, wanted)) }
    LaunchedEffect(fontId, resolver) {
        snapshotFlow { wanted }
            .conflate()
            .collect { values ->
                family = withContext(Dispatchers.Default) {
                    KeyboardFonts.family(context, fontId, values)?.also { built ->
                        // Loads every weight's typeface here, so laying the
                        // sample out on the main thread finds them ready.
                        runCatching { resolver.preload(built) }
                    }
                }
            }
    }
    return family
}

/** [axes] on a grid of [PREVIEW_STEPS] steps per axis: fine enough to read as smooth. */
private fun snapAxes(axes: Map<String, Float>, variations: FontVariations): Map<String, Float> =
    axes.mapValues { (tag, value) ->
        val axis = variations.axis(tag) ?: return@mapValues value
        val span = axis.max - axis.min
        if (span <= 0f) value else axis.min + (((value - axis.min) / span) * PREVIEW_STEPS).roundToInt() * span / PREVIEW_STEPS
    }

private const val PREVIEW_STEPS = 120

/**
 * An axis value as people read one: whole numbers on the wide axes (weight
 * 650, width 85), two places on the narrow ones (italic 0.50, casual 0.25).
 */
internal fun formatAxisValue(axis: FontAxis, value: Float): String {
    val span = abs(axis.max - axis.min)
    return if (span <= NARROW_AXIS_SPAN) {
        String.format(java.util.Locale.getDefault(), "%.2f", value)
    } else {
        value.roundToInt().toString()
    }
}

private const val NARROW_AXIS_SPAN = 2f

internal fun roleLabel(role: FontRole): Int = when (role) {
    FontRole.KEYS -> R.string.fonts_axes_role_keys
    FontRole.HINTS -> R.string.fonts_axes_role_hints
    FontRole.STRIP -> R.string.fonts_axes_role_strip
    FontRole.POPUP -> R.string.fonts_axes_role_popup
}

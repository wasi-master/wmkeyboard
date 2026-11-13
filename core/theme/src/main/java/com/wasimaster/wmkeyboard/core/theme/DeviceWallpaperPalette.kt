package com.wasimaster.wmkeyboard.core.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The device's Material You palette, in the shape a FlorisBoard stylesheet
 * names its colours.
 *
 * A stylesheet may write `dynamic-dark-color(surfaceContainerLow)` instead of a
 * hex value, and the two largest packs in circulation write every colour that
 * way. FlorisBoard resolves those against the wallpaper palette; so does this,
 * against the same system colours, so an imported theme lands on the colours
 * the original drew rather than on nothing.
 *
 * Below Android 12 there is no wallpaper palette to read, so the Material
 * baseline stands in. That is not what FlorisBoard would draw — but FlorisBoard
 * has the same problem on the same devices, and it falls back the same way.
 *
 * Kept apart from the conversion, which is pure JVM on purpose: the parser and
 * the mapper are unit-tested without a `Context`, and reading one here is the
 * whole reason this is a separate file.
 */
fun dynamicSnyggPalette(context: Context): SnyggPalette {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return SnyggPalette.Baseline
    return runCatching {
        SnyggPalette(
            light = dynamicLightColorScheme(context).asRoles(),
            dark = dynamicDarkColorScheme(context).asRoles(),
            fromDevice = true,
        )
    }.getOrDefault(SnyggPalette.Baseline)
}

/**
 * The wallpaper palette a theme with [ThemeSpec.followWallpaper] is moved onto,
 * or null below Android 12, where there is none and the theme keeps its stored
 * colours.
 *
 * Read from resources each call — forty scheme roles and sixty-five tonal
 * shades — so callers remember it against the configuration, which is what
 * changes when the wallpaper does.
 */
fun deviceWallpaperPalette(context: Context): WallpaperPalette? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching {
        WallpaperPalette(
            roles = dynamicSnyggPalette(context),
            tones = systemTones(context),
        )
    }.getOrNull()
}

/**
 * [spec] as it should be drawn right now: moved onto the wallpaper when it
 * follows it, and the same instance otherwise.
 *
 * For the surfaces that draw a theme straight from its spec, such as the
 * gallery's miniatures, so a theme that follows the wallpaper is not shown in
 * yesterday's colours beside a keyboard that is already in today's.
 */
@Composable
fun rememberWallpaperFollowed(spec: ThemeSpec): ThemeSpec {
    // The theme's rule sheet is part of how it is drawn, here as on the keyboard.
    val sheeted = remember(spec) { spec.withRuleSheet() }
    if (!sheeted.followWallpaper) return sheeted
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(sheeted, context, configuration) {
        sheeted.followingWallpaper(deviceWallpaperPalette(context))
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun systemTones(context: Context): WallpaperTones {
    fun read(ids: IntArray) = ids.map { context.getColor(it).toLong() and 0xFFFFFFFFL }
    return WallpaperTones(
        primary = read(ACCENT1),
        secondary = read(ACCENT2),
        tertiary = read(ACCENT3),
        neutral = read(NEUTRAL1),
        neutralVariant = read(NEUTRAL2),
    )
}

// In WallpaperTones.TONES order: _0 is white, _1000 black.
@RequiresApi(Build.VERSION_CODES.S)
private val ACCENT1 = intArrayOf(
    android.R.color.system_accent1_0, android.R.color.system_accent1_10,
    android.R.color.system_accent1_50, android.R.color.system_accent1_100,
    android.R.color.system_accent1_200, android.R.color.system_accent1_300,
    android.R.color.system_accent1_400, android.R.color.system_accent1_500,
    android.R.color.system_accent1_600, android.R.color.system_accent1_700,
    android.R.color.system_accent1_800, android.R.color.system_accent1_900,
    android.R.color.system_accent1_1000,
)

@RequiresApi(Build.VERSION_CODES.S)
private val ACCENT2 = intArrayOf(
    android.R.color.system_accent2_0, android.R.color.system_accent2_10,
    android.R.color.system_accent2_50, android.R.color.system_accent2_100,
    android.R.color.system_accent2_200, android.R.color.system_accent2_300,
    android.R.color.system_accent2_400, android.R.color.system_accent2_500,
    android.R.color.system_accent2_600, android.R.color.system_accent2_700,
    android.R.color.system_accent2_800, android.R.color.system_accent2_900,
    android.R.color.system_accent2_1000,
)

@RequiresApi(Build.VERSION_CODES.S)
private val ACCENT3 = intArrayOf(
    android.R.color.system_accent3_0, android.R.color.system_accent3_10,
    android.R.color.system_accent3_50, android.R.color.system_accent3_100,
    android.R.color.system_accent3_200, android.R.color.system_accent3_300,
    android.R.color.system_accent3_400, android.R.color.system_accent3_500,
    android.R.color.system_accent3_600, android.R.color.system_accent3_700,
    android.R.color.system_accent3_800, android.R.color.system_accent3_900,
    android.R.color.system_accent3_1000,
)

@RequiresApi(Build.VERSION_CODES.S)
private val NEUTRAL1 = intArrayOf(
    android.R.color.system_neutral1_0, android.R.color.system_neutral1_10,
    android.R.color.system_neutral1_50, android.R.color.system_neutral1_100,
    android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
    android.R.color.system_neutral1_400, android.R.color.system_neutral1_500,
    android.R.color.system_neutral1_600, android.R.color.system_neutral1_700,
    android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
    android.R.color.system_neutral1_1000,
)

@RequiresApi(Build.VERSION_CODES.S)
private val NEUTRAL2 = intArrayOf(
    android.R.color.system_neutral2_0, android.R.color.system_neutral2_10,
    android.R.color.system_neutral2_50, android.R.color.system_neutral2_100,
    android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
    android.R.color.system_neutral2_400, android.R.color.system_neutral2_500,
    android.R.color.system_neutral2_600, android.R.color.system_neutral2_700,
    android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
    android.R.color.system_neutral2_1000,
)

private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

/**
 * A Compose scheme as the role map the resolver reads.
 *
 * Every role is named explicitly rather than reflected over, so a role
 * Material adds later is a deliberate line here instead of arriving unnoticed
 * under whatever name the library happened to give it.
 */
private fun ColorScheme.asRoles(): Map<String, Long> = SnyggPalette.buildRoles(
    primary = primary.argb(),
    onPrimary = onPrimary.argb(),
    primaryContainer = primaryContainer.argb(),
    onPrimaryContainer = onPrimaryContainer.argb(),
    secondary = secondary.argb(),
    onSecondary = onSecondary.argb(),
    secondaryContainer = secondaryContainer.argb(),
    onSecondaryContainer = onSecondaryContainer.argb(),
    tertiary = tertiary.argb(),
    onTertiary = onTertiary.argb(),
    tertiaryContainer = tertiaryContainer.argb(),
    onTertiaryContainer = onTertiaryContainer.argb(),
    background = background.argb(),
    onBackground = onBackground.argb(),
    surface = surface.argb(),
    onSurface = onSurface.argb(),
    surfaceVariant = surfaceVariant.argb(),
    onSurfaceVariant = onSurfaceVariant.argb(),
    surfaceDim = surfaceDim.argb(),
    surfaceBright = surfaceBright.argb(),
    surfaceContainerLowest = surfaceContainerLowest.argb(),
    surfaceContainerLow = surfaceContainerLow.argb(),
    surfaceContainer = surfaceContainer.argb(),
    surfaceContainerHigh = surfaceContainerHigh.argb(),
    surfaceContainerHighest = surfaceContainerHighest.argb(),
    outline = outline.argb(),
    outlineVariant = outlineVariant.argb(),
    inverseSurface = inverseSurface.argb(),
    inverseOnSurface = inverseOnSurface.argb(),
    inversePrimary = inversePrimary.argb(),
    error = error.argb(),
    onError = onError.argb(),
    errorContainer = errorContainer.argb(),
    onErrorContainer = onErrorContainer.argb(),
    scrim = scrim.argb(),
)

/**
 * The other direction: a role map back into a Compose scheme, for the Default
 * theme, which is drawn from a scheme rather than from stored colours. Used
 * when the palette did not come from the device — the app being typed into
 * ([appPalette]) — so the Default theme can wear it the way it wears the
 * wallpaper's.
 */
fun SnyggPalette.colorScheme(dark: Boolean): ColorScheme {
    val roles = if (dark) this.dark else light
    val base = if (dark) SnyggPalette.Baseline.dark else SnyggPalette.Baseline.light
    fun c(role: String): Color {
        val key = SnyggPalette.normalizeRole(role)
        return Color((roles[key] ?: base.getValue(key)).toInt())
    }
    // Named rather than positional: the parameter order of these builders has
    // moved between Material releases, and a role landing in its neighbour's
    // slot would still compile.
    return if (dark) {
        darkColorScheme(
            primary = c("primary"),
            onPrimary = c("onPrimary"),
            primaryContainer = c("primaryContainer"),
            onPrimaryContainer = c("onPrimaryContainer"),
            inversePrimary = c("inversePrimary"),
            secondary = c("secondary"),
            onSecondary = c("onSecondary"),
            secondaryContainer = c("secondaryContainer"),
            onSecondaryContainer = c("onSecondaryContainer"),
            tertiary = c("tertiary"),
            onTertiary = c("onTertiary"),
            tertiaryContainer = c("tertiaryContainer"),
            onTertiaryContainer = c("onTertiaryContainer"),
            background = c("background"),
            onBackground = c("onBackground"),
            surface = c("surface"),
            onSurface = c("onSurface"),
            surfaceVariant = c("surfaceVariant"),
            onSurfaceVariant = c("onSurfaceVariant"),
            surfaceTint = c("surfaceTint"),
            inverseSurface = c("inverseSurface"),
            inverseOnSurface = c("inverseOnSurface"),
            error = c("error"),
            onError = c("onError"),
            errorContainer = c("errorContainer"),
            onErrorContainer = c("onErrorContainer"),
            outline = c("outline"),
            outlineVariant = c("outlineVariant"),
            scrim = c("scrim"),
            surfaceBright = c("surfaceBright"),
            surfaceContainer = c("surfaceContainer"),
            surfaceContainerHigh = c("surfaceContainerHigh"),
            surfaceContainerHighest = c("surfaceContainerHighest"),
            surfaceContainerLow = c("surfaceContainerLow"),
            surfaceContainerLowest = c("surfaceContainerLowest"),
            surfaceDim = c("surfaceDim"),
        )
    } else {
        lightColorScheme(
            primary = c("primary"),
            onPrimary = c("onPrimary"),
            primaryContainer = c("primaryContainer"),
            onPrimaryContainer = c("onPrimaryContainer"),
            inversePrimary = c("inversePrimary"),
            secondary = c("secondary"),
            onSecondary = c("onSecondary"),
            secondaryContainer = c("secondaryContainer"),
            onSecondaryContainer = c("onSecondaryContainer"),
            tertiary = c("tertiary"),
            onTertiary = c("onTertiary"),
            tertiaryContainer = c("tertiaryContainer"),
            onTertiaryContainer = c("onTertiaryContainer"),
            background = c("background"),
            onBackground = c("onBackground"),
            surface = c("surface"),
            onSurface = c("onSurface"),
            surfaceVariant = c("surfaceVariant"),
            onSurfaceVariant = c("onSurfaceVariant"),
            surfaceTint = c("surfaceTint"),
            inverseSurface = c("inverseSurface"),
            inverseOnSurface = c("inverseOnSurface"),
            error = c("error"),
            onError = c("onError"),
            errorContainer = c("errorContainer"),
            onErrorContainer = c("onErrorContainer"),
            outline = c("outline"),
            outlineVariant = c("outlineVariant"),
            scrim = c("scrim"),
            surfaceBright = c("surfaceBright"),
            surfaceContainer = c("surfaceContainer"),
            surfaceContainerHigh = c("surfaceContainerHigh"),
            surfaceContainerHighest = c("surfaceContainerHighest"),
            surfaceContainerLow = c("surfaceContainerLow"),
            surfaceContainerLowest = c("surfaceContainerLowest"),
            surfaceDim = c("surfaceDim"),
        )
    }
}

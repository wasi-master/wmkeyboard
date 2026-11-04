package com.wasimaster.wmkeyboard.ime.ui

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.bottomPaddingOr

/**
 * How the keyboard asks the window it lives in to colour the system
 * navigation bar, and to pick icons that stay visible on it.
 *
 * Why this exists at all, when [NavigationBarBackground] already paints the
 * band: the keyboard's window is edge to edge on every version and the board
 * — gradient, image and all — runs under the bar, but below Android 15 the
 * system still draws the bar's own background over it in the window's
 * `navigationBarColor`. Left unset that is the platform default, which on
 * some light-mode OEM builds is opaque white under a coloured keyboard
 * (issue #255).
 *
 * The default implementation does nothing, which is what the settings app's
 * theme previews want: they run in an Activity whose own window owns the bar.
 */
fun interface SystemNavBarPainter {
    /**
     * @param color the opaque colour to paint the bar, or null to hand it back
     *   to whatever the window had before the keyboard touched it.
     */
    fun paint(color: Color?)
}

/** The painter the docked keyboard drives; see [SystemNavBarPainter]. */
val LocalSystemNavBarPainter = staticCompositionLocalOf { SystemNavBarPainter { } }

/**
 * Relative luminance at or above which the bar counts as light, so the
 * system's hide-keyboard and language-switch icons have to be drawn dark.
 */
private const val LIGHT_NAV_BAR_LUMINANCE = 0.5f

/**
 * True when [color] needs dark icons drawn on it — the value
 * `WindowInsetsControllerCompat.isAppearanceLightNavigationBars` takes, whose
 * "light" means the bar, not the icons.
 */
fun navigationBarWantsDarkIcons(color: Color): Boolean =
    color.luminance() >= LIGHT_NAV_BAR_LUMINANCE

/**
 * The one flat colour that best stands in for the bottom edge of the board.
 *
 * A gradient's last stop is that edge for the default top-left to
 * bottom-right sweep, so it beats the flat board colour a gradient theme also
 * carries. A theme that names [KbTheme.navigationBar] has said outright what
 * the band should be and wins over both.
 *
 * The result is forced opaque: the system re-enables its own contrast scrim
 * over a translucent navigation-bar colour, which would wash out whatever we
 * asked for. A theme's translucent band is composited over the board first, so
 * it still reads as the tint it was written to be.
 */
fun navigationBandColor(kb: KbTheme): Color {
    val board = kb.boardGradient?.colors?.lastOrNull()?.let { Color(it.toInt()) } ?: kb.board
    val opaqueBoard = board.compositeOver(kb.board.copy(alpha = 1f))
    val band = kb.navigationBar ?: opaqueBoard
    return band.compositeOver(opaqueBoard).copy(alpha = 1f)
}

/**
 * Keeps the system navigation bar in [kb]'s colour for as long as the docked
 * keyboard is on screen, and hands it back on the way out — floating mode and
 * the collapsed voice bar do not call this, and neither should own the bar.
 */
@Composable
fun SystemNavigationBarColor(kb: KbTheme) {
    val painter = LocalSystemNavBarPainter.current
    val color = navigationBandColor(kb)
    // Two effects rather than one keyed DisposableEffect: that would hand the
    // bar back to the default and re-take it on every theme change, and the
    // frame in between flashes the platform colour.
    DisposableEffect(painter) {
        onDispose { painter.paint(null) }
    }
    LaunchedEffect(painter, color) {
        painter.paint(color)
    }
}

/**
 * True when the window's bottom edge carries a gesture handle rather than a
 * row of navigation buttons, on Android 15 and up. Always false below 15:
 * the 32dp over a gesture handle was measured against Gboard on 15, and below
 * it the keyboard has always sat 8dp over the bar's inset, which the keys
 * still pad for now that the window is edge to edge there too (#569). False
 * as well for a three-button bar turned into a side rail in landscape.
 *
 * The kind of bar comes from the system's navigation mode, not from the
 * insets: in gesture mode the system draws its own hide-keyboard and
 * switch-keyboard buttons into the keyboard's bar, and on ColorOS 15 those
 * made the bar count as tappable, so the keyboard took it for three-button
 * navigation and dropped its padding onto the buttons. Tappable insets are
 * only the fallback for a build that does not publish the mode.
 */
@Composable
fun gestureBarAtBottom(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
    val density = LocalDensity.current
    if (navigationBarInsets().getBottom(density) == 0) return false
    val resources = LocalContext.current.resources
    // Switching the navigation mode swaps a framework overlay, which arrives
    // as a configuration change.
    val mode = remember(resources, LocalConfiguration.current) { navigationMode(resources) }
    return if (mode != null) {
        mode == NAV_MODE_GESTURAL
    } else {
        WindowInsets.tappableElement.getBottom(density) == 0
    }
}

/** `config_navBarInteractionMode` for fully gestural navigation. */
private const val NAV_MODE_GESTURAL = 2

/**
 * The system's navigation mode — 0 three buttons, 1 two buttons, 2 gestures —
 * or null when this build does not publish it.
 */
@SuppressLint("DiscouragedApi")
private fun navigationMode(resources: Resources): Int? = runCatching {
    val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
    if (id == 0) null else resources.getInteger(id)
}.getOrNull()

/**
 * True when this phone draws its keyboard bar (hide chevron and switch
 * button) inside the keyboard's own window, the only case where a keyboard
 * can hide it (#582): Android 16 or later, gesture navigation, and a build
 * that leaves `config_imeDrawsImeNavBar` on. With three-button navigation, or
 * on a build that turns that flag off, the bar belongs to the system's own
 * navigation bar and no keyboard can take it away (#681).
 */
fun keyboardCanHideSystemKeyboardBar(resources: Resources): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
    if (navigationMode(resources)?.let { it != NAV_MODE_GESTURAL } == true) return false
    return imeDrawsImeNavBar(resources) != false
}

/**
 * What decided [keyboardCanHideSystemKeyboardBar] on this phone, for the
 * Diagnostics log (#891): a bar that stays after the keyboard asked to hide it
 * says whether the build gave the keyboard the bar at all.
 */
fun systemKeyboardBarFacts(resources: Resources): String =
    "sdk ${Build.VERSION.SDK_INT}, navigation mode ${navigationMode(resources) ?: "unknown"}, " +
        "keyboard draws bar ${imeDrawsImeNavBar(resources) ?: "unknown"}"

/** `config_imeDrawsImeNavBar`, or null when this build does not publish it. */
@SuppressLint("DiscouragedApi")
private fun imeDrawsImeNavBar(resources: Resources): Boolean? = runCatching {
    val id = resources.getIdentifier("config_imeDrawsImeNavBar", "bool", "android")
    if (id == 0) null else resources.getBoolean(id)
}.getOrNull()

/**
 * The bottom padding [settings] asks for here, the automatic one while unset
 * (#343). The automatic 32dp over a gesture handle is there to clear the
 * system's keyboard bar, so once that bar is hidden it is the small one (#681).
 */
@Composable
fun bottomPaddingDp(settings: KeyboardSettings): Int {
    val barHidden = LocalImeNavigationBars.current?.keyboardBarHidden == true
    return settings.bottomPaddingOr(!barHidden && gestureBarAtBottom())
}

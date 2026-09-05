package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.ScaleToBounds
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.wasimaster.wmkeyboard.core.ui.ToolPaint
import com.wasimaster.wmkeyboard.common.R as CommonR
import kotlin.math.roundToInt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable

/*
 * The house style. Every settings surface is built from the handful of
 * components in this file rather than from Material's own — so the look can be
 * moved in one place instead of across twenty screens, and so the app stops
 * reading as a stock Material sample.
 */

// ---- icon tiles ----

/** The tile a settings icon sits on. Bigger than the glyph so colour reads. */
internal val WmIconTileSize = 40.dp
private val IconTileSize = WmIconTileSize
private val IconTileRadius = 13.dp

/** The glyph size inside a tile, for callers drawing their own icon into one. */
internal val WmIconTileGlyph = 22.dp

/**
 * The glyph colour on a tile. The stored accents are Material 400/500 mid-tones
 * picked to survive a dark toolbar; on a light tile they are washed out next to
 * the label, so they get darkened rather than kept literal.
 */
private fun tileGlyphColor(accent: Color, dark: Boolean): Color =
    if (dark) accent else androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.28f)

/**
 * A tool's paint with the same treatment [tileGlyphColor] gives a flat accent,
 * applied to both ends of the gradient.
 *
 * Without it, switching the gradients on would wash out every tool icon on a
 * light theme — the stored accents are dark-toolbar mid-tones, which is exactly
 * what the darkening exists to fix.
 */
@Composable
internal fun tileToolPaint(paint: ToolPaint?): ToolPaint? {
    val dark = isSystemInDarkTheme()
    return paint?.map { tileGlyphColor(it, dark) }
}

/**
 * A rounded, tinted tile behind a settings icon. The single most recognisable
 * piece of the app's chrome — a bare tinted glyph is what every stock Material
 * settings list looks like.
 */
@Composable
internal fun WmIconTile(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    WmIconTile(accent, modifier) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(WmIconTileGlyph),
        )
    }
}

/**
 * The tile with an arbitrary glyph inside — for icon-pack slots and anything
 * else that draws its own icon. [LocalContentColor] is set to the tile's glyph
 * colour, so a plain `Icon` inside picks it up without being told.
 *
 * [brush] washes the tile with a gradient instead of one flat tint, for the
 * tools when "Gradient tool colours" is on. The glyph colour still comes from
 * [accent] — the near end of that same gradient — because a glyph this small
 * has nowhere to show a second colour.
 */
@Composable
internal fun WmIconTile(
    accent: Color,
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    size: Dp = WmIconTileSize,
    radius: Dp = IconTileRadius,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val wash = if (dark) 0.24f else 0.15f
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .then(
                if (brush != null) Modifier.background(brush, alpha = wash)
                else Modifier.background(accent.copy(alpha = wash)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides tileGlyphColor(accent, dark),
            content = content,
        )
    }
}

/**
 * The accent each settings destination is drawn with, keyed by the same routes
 * as [SettingsRouteIcons]. Neighbouring rows never share a hue, so the home
 * list reads as a set of distinct places rather than one column of primary.
 * Screens that are not home rows fall back to [defaultRouteAccent].
 */
internal val SettingsRouteColors: Map<String, Color> = mapOf(
    "typing" to Color(0xFF42A5F5),
    "keypress" to Color(0xFF7E57C2),
    "languages" to Color(0xFF66BB6A),
    "appearance" to Color(0xFFEC407A),
    "themes" to Color(0xFFEC407A),
    "photos" to Color(0xFF00ACC1),
    "photo_browse" to Color(0xFF00ACC1),
    "photo_library" to Color(0xFF00ACC1),
    "photo_rotation" to Color(0xFF00ACC1),
    "fonts" to Color(0xFFAB47BC),
    "icons" to Color(0xFFFF7043),
    "layout" to Color(0xFF5C6BC0),
    "keymaps" to Color(0xFF26C6DA),
    "rows" to Color(0xFF26A69A),
    "ai_actions" to Color(0xFF7E57C2),
    "ai_history" to Color(0xFF7E57C2),
    "ai_chat" to Color(0xFF7E57C2),
    "modes" to Color(0xFFFFA726),
    "emoji" to Color(0xFFFFB300),
    "voice" to Color(0xFF7E57C2),
    "clipboard" to Color(0xFF42A5F5),
    // The Snippets tool's own teal: the screen and the tool page it is opened
    // from are the same feature.
    "expander" to Color(0xFF26A69A),
    "tools" to Color(0xFFFF7043),
    "sticker_packs" to Color(0xFFF06292),
    // The editor is opened from a pack, so it keeps the pack screens' pink.
    "sticker_editor" to Color(0xFFF06292),
    "plugins" to Color(0xFF06B6D4),
    "addons" to Color(0xFF7986CB),
    "accessibility" to Color(0xFF29B6F6),
    "privacy" to Color(0xFFEF5350),
    // Children of Privacy, so they keep the parent's red.
    "permissions" to Color(0xFFEF5350),
    "applock" to Color(0xFFEF5350),
    "datasaver" to Color(0xFF00897B),
    "advanced" to Color(0xFF8D6E63),
    "backup" to Color(0xFF78909C),
    "about" to Color(0xFF90A4AE),
    "licenses" to Color(0xFF90A4AE),
    // Behind the version row's seven taps, so it keeps About's grey.
    "egg_game" to Color(0xFF90A4AE),
    "debug_log" to Color(0xFF607D8B),
    "storage" to Color(0xFF546E7A),
    "statistics" to Color(0xFF9CCC65),
    "dictionary" to Color(0xFF26A69A),
    "customdictionaries" to Color(0xFF26A69A),
    "blacklist" to Color(0xFFEF5350),
    // A child of Clipboard, so it keeps the parent's blue.
    "phoneformats" to Color(0xFF42A5F5),
    "hwshortcuts" to Color(0xFF5C6BC0),
    "emojikeywords" to Color(0xFFFFB300),
    // A child of the media control tool, so it keeps that tool's purple.
    "musicapps" to Color(0xFFAB47BC),
)

/** The accent for a route with no colour of its own. */
@Composable
internal fun defaultRouteAccent(): Color = MaterialTheme.colorScheme.primary

/** The accent to paint a settings destination's icon with. */
@Composable
internal fun routeAccent(route: String): Color =
    SettingsRouteColors[route] ?: defaultRouteAccent()

/**
 * The accent for the screen being drawn.
 *
 * A row's tile wears its screen's colour rather than one colour for the whole
 * app: the home list already teaches which hue means Typing and which means
 * Appearance, and a screen full of tiles in that hue reads as one place. Rows
 * on a screen with no colour of its own (a tool's page, an addon) fall back to
 * the theme's primary.
 */
@Composable
internal fun currentRouteAccent(): Color = routeAccent(LocalScreenRoute.current.orEmpty())

// ---- shared elements ----

/*
 * A settings section's icon and name are the same object on both sides of a
 * navigation: the row on the home list, and the heading of the screen it opens.
 * These two locals are how the two ends find each other — the transition scope
 * is published once around the whole graph, and each destination publishes its
 * own animation scope, so any row or heading deep inside can opt in without
 * being handed anything.
 */

/** The transition scope the settings graph runs in; null when it is off. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }

/** The current destination's own animation scope; null outside the graph. */
internal val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The folds the user has opened, by "<route>/<key>", and the way to change
 * that. Published by the nav host so [SettingsGroup] can be a fold without
 * every screen handing it a repository.
 */
internal class AdvancedFolds(val open: Set<String>, val toggle: (String, Boolean) -> Unit)

internal val LocalAdvancedFolds = compositionLocalOf<AdvancedFolds?> { null }

/**
 * What a screen's body hands up to its frame: a floating action button and a
 * block pinned under the bar. The frame draws both, but the add action and
 * the dialog it opens live inside the screen, so the screen registers them
 * from where they are rather than the nav graph threading them through.
 */
@Stable
internal class ScreenSlots {
    var fab: (@Composable () -> Unit)? by mutableStateOf(null)
    var pinned: (@Composable () -> Unit)? by mutableStateOf(null)
}

internal val LocalScreenSlots = compositionLocalOf<ScreenSlots?> { null }

/** Puts [content] in the frame's FAB slot for as long as the caller is composed. */
@Composable
internal fun RegisterFab(content: @Composable () -> Unit) {
    val slots = LocalScreenSlots.current ?: return
    SideEffect { slots.fab = content }
    DisposableEffect(slots) { onDispose { slots.fab = null } }
}

/** The one-icon FAB every list screen's "add" is: [label] is its content description. */
@Composable
internal fun RegisterAddFab(label: String, onClick: () -> Unit) {
    RegisterFab {
        FloatingActionButton(onClick = onClick) {
            Icon(Icons.Outlined.Add, contentDescription = label)
        }
    }
}

/** Pins [content] under the bar, above the scrolling body, while the caller is composed. */
@Composable
internal fun RegisterPinned(content: @Composable () -> Unit) {
    val slots = LocalScreenSlots.current ?: return
    SideEffect { slots.pinned = content }
    DisposableEffect(slots) { onDispose { slots.pinned = null } }
}

/**
 * False while this screen is still animating in, true from the frame it lands.
 *
 * [WmScreen] scrolls a `Column` rather than a lazy list, so a screen composes
 * every row it has before it can draw one — and a screen with sixty of them
 * does that inside its own opening animation, which is exactly when the frame
 * budget is already spent. A long screen draws enough rows to fill the window
 * and asks this before composing the rest, so the wait moves off the opening
 * and behind it.
 *
 * Outside the navigation graph, and with animations off, this is true from the
 * start: there is no transition to stay out of the way of.
 */
@Composable
internal fun rememberScreenSettled(): Boolean {
    val anim = LocalNavAnimatedScope.current ?: return true
    var settled by remember(anim) { mutableStateOf(false) }
    LaunchedEffect(anim) {
        // Both halves matter. `isRunning` is still false on the frame the
        // destination first composes — the transition has not started yet —
        // so waiting on it alone would settle immediately and defer nothing.
        // The states differing is what says an entrance is under way, and
        // with animations turned off they converge on the next frame.
        snapshotFlow {
            val transition = anim.transition
            transition.currentState == transition.targetState && !transition.isRunning
        }.first { it }
        settled = true
    }
    return settled
}

// ---- deferred group reveal ----

/**
 * Rows a screen composes before its entrance has settled — enough to fill a
 * tall phone window, and no more. See [ScreenReveal].
 */
private const val EagerRowBudget = 12

/**
 * The ledger behind [rememberGroupRevealed]: which groups compose during the
 * entrance, and the queue the rest wait in.
 *
 * [WmScreen] scrolls a `Column`, so a screen with seventy rows composes all
 * seventy before it can draw one — inside its own opening animation, which is
 * exactly when the frame budget is already spent, and on a slow phone that is
 * most of what "the settings are laggy" is. [rememberScreenSettled] alone
 * moves the wait behind the entrance but then pays it in one piece; this
 * spreads it out as well, letting one group in per frame so the late rows
 * never pile into a single long frame either.
 *
 * Groups claim eagerly, in composition order, until the budget is spent; the
 * rest take a place in the queue. Anything composing after the screen has
 * settled — a group behind a toggle flipped later — goes straight through.
 */
internal class ScreenReveal {
    /** Rows claimed by the groups composing during the entrance. */
    var claimedRows = 0

    /** How many groups are waiting; a joining group takes the next place. */
    var deferredCount = 0

    /** Set once the entrance lands, before the wave starts advancing. */
    var isSettled = false

    /** How many queued groups have been let in, advanced one per frame. */
    var wave by mutableIntStateOf(0)
}

/** Published by [WmScreen] around its column; null anywhere else. */
internal val LocalScreenReveal = compositionLocalOf<ScreenReveal?> { null }

/**
 * Whether a group of [rowCount] rows should compose yet. True everywhere
 * outside a [WmScreen] body, and inside one from the frame the screen's
 * entrance can spare it. A group asks once — the answer's only moving part is
 * the wave, so a deferred group recomposes exactly when it is let in and a
 * revealed one never recomposes for the wave again.
 */
@Composable
internal fun rememberGroupRevealed(rowCount: Int): Boolean {
    val reveal = LocalScreenReveal.current ?: return true
    val queuePlace = remember {
        when {
            reveal.isSettled -> null
            reveal.claimedRows < EagerRowBudget -> {
                reveal.claimedRows += rowCount
                null
            }
            else -> reveal.deferredCount++
        }
    }
    return queuePlace == null || reveal.wave > queuePlace
}

/**
 * Where a flight took off from.
 *
 * A screen can be opened from more than one place — Key layouts from the home
 * list and from Languages, a tool's page from Tools and from a search result —
 * so keying a flight on its destination alone would hang the same key on two
 * rows at once, and a push between their screens would fly one row into the
 * other rather than into the heading. Keys name the row's own screen as well,
 * and a destination adopts whichever screen launched it.
 */
internal object FlightOrigin {
    /** The screen the last tagged row navigated away from. */
    var pending: String? = null
        private set

    fun leaving(screen: String?) {
        pending = screen
    }
}

/** The screen being drawn, as the flights taking off from it name it. */
internal val LocalScreenRoute = compositionLocalOf<String?> { null }

/** The screen this one was opened from, fixed at its first composition. */
internal val LocalFlightOrigin = compositionLocalOf<String?> { null }

/** One end of a flight: which part of a row, going where, from where. */
private fun flightKey(part: String, destination: String, from: String?): String =
    "$part|$destination|${from ?: "-"}"

/** The key a row on this screen uses for its flight into [destination]. */
@Composable
internal fun takeOffKey(part: String, destination: String): String =
    flightKey(part, destination, LocalScreenRoute.current)

/** The key this screen uses for the flight that opened it. */
@Composable
internal fun landingKey(part: String): String =
    flightKey(part, LocalScreenRoute.current.orEmpty(), LocalFlightOrigin.current)

/**
 * Holds a page still while a flight is in the air.
 *
 * A flying element is placed at an animated position in the *window*: each
 * frame it is offset by the difference between where the animation has reached
 * and where its own layout currently sits. Scroll the page under it and that
 * difference grows, so the element hangs in place while the row it belongs to
 * slides away — the "floating over the card" effect. Nothing in the animation
 * can fix that; the page simply doesn't scroll for the third of a second the
 * flight takes.
 *
 * Consumed here rather than by disabling the scroll, because the flag is read
 * inside the callback: turning it into a composition read would recompose every
 * settings screen twice per navigation.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberFlightScrollLock(): NestedScrollConnection {
    val shared = LocalSharedTransition.current
    return remember(shared) {
        object : NestedScrollConnection {
            private val locked: Boolean get() = shared?.isTransitionActive == true

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (locked) available else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (locked) available else Velocity.Zero
        }
    }
}

/**
 * Wraps a click so the screen it opens learns where it came from. [WmRow] does
 * this itself when given a `flightTo`; anything tagging its own parts — an
 * addon card, a grid cell — wraps its own click with this.
 */
@Composable
internal fun takeOffClick(onClick: () -> Unit): () -> Unit {
    val screen = LocalScreenRoute.current
    return {
        FlightOrigin.leaving(screen)
        onClick()
    }
}

/**
 * The navigation push's timing. Shared with the flights that ride on it, which
 * have to land on the same frame the screen behind them does.
 */
internal const val NavTransitionMs = 340

/** Material's emphasized-decelerate curve: leaves fast, lands slowly. */
internal val NavTransitionEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * A flight's timing: Material's standard curve, unaltered, over the same clock
 * the screens move on.
 *
 * A flight that is still running while the list under it scrolls looks loose —
 * it is placed at an animated position in the window, so the row it is heading
 * for slides out from under it. That is fixed where it starts rather than here,
 * by [WmScreen] refusing to scroll until the flight has landed; the curve's job
 * is only to look right.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val NavBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = NavTransitionMs, easing = FastOutSlowInEasing)
}

/**
 * Tags this element as one end of a flight between screens. A no-op when
 * either local is missing — off the graph, or with reduced motion asked for,
 * in which case the element simply draws where it is.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.wmSharedElement(key: Any): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@wmSharedElement.sharedElement(
            rememberSharedContentState(key),
            anim,
            boundsTransform = NavBoundsTransform,
            zIndexInOverlay = 1f,
        )
    }
}

/**
 * [wmSharedElement] for content that is the same thing at two different sizes —
 * a section's name as a row headline and as a page heading. The glyphs are
 * scaled between the two bounds rather than re-laid out, so the word grows
 * instead of reflowing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.wmSharedBounds(key: Any): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@wmSharedBounds.sharedBounds(
            rememberSharedContentState(key),
            anim,
            boundsTransform = NavBoundsTransform,
            resizeMode = ScaleToBounds(ContentScale.FillWidth, Alignment.CenterStart),
            zIndexInOverlay = 1f,
        )
    }
}

// ---- rows ----

/**
 * The one settings row. Everything with a title, an optional subtitle and
 * optional leading/trailing furniture goes through here, so row metrics, the
 * icon treatment and the transparent container that lets the group card show
 * through are decided once.
 *
 * Pass [icon] for the house tile treatment, or [leading] to draw the slot
 * yourself. [onClick] makes the whole row the target. A row whose headline is
 * more than a string — a badge beside the name, a lock glyph — passes
 * [titleContent] and keeps [title] as its plain-text equivalent.
 *
 * [flightTo] names the screen the row opens. It tags the row's name — and its
 * tile, when it has one — as the take-off end of the flight into that screen's
 * heading, and tells the screen where it was opened from.
 *
 * [highlightKey] is the string resource behind [title]. Pass it on a row that
 * search or an addon's Use button can land on, and the row wraps itself in a
 * [HighlightableRow] that matches on the resource rather than on the words. The
 * rows built by `NavRow`, `ToggleSetting` and their siblings are wrapped by
 * those helpers instead, so they leave this at 0 and take the argument
 * themselves.
 */
@Composable
internal fun WmRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color? = null,
    titleStyle: TextStyle? = null,
    titleContent: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    flightTo: String? = null,
    subtitleFlies: Boolean = false,
    enabled: Boolean = true,
    @StringRes highlightKey: Int = 0,
    onClick: (() -> Unit)? = null,
) {
    if (highlightKey != 0) {
        HighlightableRow(title, highlightKey) {
            WmRow(
                title = title,
                modifier = modifier,
                subtitle = subtitle,
                icon = icon,
                accent = accent,
                titleStyle = titleStyle,
                titleContent = titleContent,
                leading = leading,
                trailing = trailing,
                supporting = supporting,
                flightTo = flightTo,
                subtitleFlies = subtitleFlies,
                enabled = enabled,
                onClick = onClick,
            )
        }
        return
    }
    val tileAccent = accent ?: currentRouteAccent()
    val titleKey = flightTo?.let { takeOffKey("title", it) }
    val iconKey = flightTo?.let { takeOffKey("icon", it) }
    val screen = LocalScreenRoute.current
    ListItem(
        headlineContent = titleContent ?: {
            val tag = if (titleKey == null) Modifier else Modifier.wmSharedBounds(titleKey)
            if (titleStyle != null) Text(title, modifier = tag, style = titleStyle)
            else Text(title, modifier = tag)
        },
        supportingContent = when {
            supporting != null -> supporting
            subtitle != null -> {
                {
                    Text(
                        subtitle,
                        modifier = if (!subtitleFlies || flightTo == null) Modifier
                        else Modifier.wmSharedBounds(takeOffKey("subtitle", flightTo)),
                    )
                }
            }
            else -> null
        },
        leadingContent = when {
            leading != null -> leading
            icon != null -> {
                {
                    WmIconTile(
                        icon,
                        tileAccent,
                        modifier = if (iconKey == null) Modifier
                        else Modifier.wmSharedElement(iconKey),
                    )
                }
            }
            else -> null
        },
        trailingContent = trailing,
        colors = transparentListColors(),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(enabled = enabled) {
                    if (flightTo != null) FlightOrigin.leaving(screen)
                    onClick()
                },
            ),
    )
}

// ---- reset to default ----

/**
 * The tap target of the reset control. Smaller than Material's 48 dp
 * `IconButton`, because it shares a row's trailing edge with a switch and the
 * two together would push the row's own words off a narrow phone. 36 dp is
 * still comfortably past the 24 dp the accessibility guidance floors at, and
 * the row it sits in is 56 dp tall, so the finger has vertical room to spare.
 */
private val ResetTargetSize = 36.dp

/** The glyph inside that target. */
private val ResetGlyphSize = 18.dp

/**
 * The control a settings row grows once its value stops matching the default
 * it shipped with: one tap puts it back.
 *
 * Drawn only while the value differs, which is what makes it worth the width.
 * A reset button on every row is a row of dead controls on a screen where
 * nothing has been touched, and — worse — it stops carrying information. Shown
 * only on the changed rows, it doubles as the answer to "what have I actually
 * changed on this screen", which is otherwise a question the settings app
 * cannot answer at all.
 *
 * [name] is the setting's own name, and goes into the content description
 * rather than into anything drawn: the glyph is the same on every row, so
 * "Reset" alone would leave a screen reader with a dozen identical buttons.
 */
@Composable
internal fun ResetSetting(name: String, changed: Boolean, onReset: () -> Unit) {
    if (!changed) return
    IconButton(
        onClick = onReset,
        modifier = Modifier.size(ResetTargetSize),
    ) {
        Icon(
            Icons.Outlined.Restore,
            contentDescription = stringResource(CommonR.string.common_reset_setting_desc, name),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ResetGlyphSize),
        )
    }
}

/** [ColorSetting] for a row named by a string resource. */
@Composable
internal fun ColorSetting(
    @StringRes title: Int,
    subtitle: String? = null,
    color: Long?,
    fallback: Long,
    info: String? = null,
    icon: ImageVector? = SettingsRowIcons[title],
    supportsAlpha: Boolean = false,
    onChange: (Long?) -> Unit,
) = ColorSetting(
    title = stringResource(title),
    subtitle = subtitle,
    color = color,
    fallback = fallback,
    info = info,
    icon = icon,
    highlightKey = title,
    supportsAlpha = supportsAlpha,
    onChange = onChange,
)

/**
 * A settings row that opens the colour picker, showing the colour it holds.
 *
 * [color] is null while the setting follows something else, a keyboard theme
 * usually, and the row says "Automatic" rather than drawing a swatch: the
 * settings app cannot paint the keyboard's palette without also repainting
 * itself in it, so a swatch there would be a guess, and a wrong swatch reads
 * as a wrong setting. [fallback] only seeds the picker, giving the wheel
 * somewhere to open from on a row that has never been set.
 *
 * Picking a colour makes the setting explicit; the reset control clears it back
 * to null, which is why the row needs no separate `default`.
 *
 * The theme editor has its own colour rows. This one is for the settings
 * screens, where a row has to carry the icon, the info button and the reset
 * control that every other settings row carries.
 */
@Composable
internal fun ColorSetting(
    title: String,
    subtitle: String? = null,
    color: Long?,
    fallback: Long,
    info: String? = null,
    icon: ImageVector? = null,
    @StringRes highlightKey: Int = 0,
    supportsAlpha: Boolean = false,
    onChange: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HighlightableRow(title, highlightKey) {
        WmRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (info != null) InfoButton(title, info)
                    ResetSetting(title, color != null) { onChange(null) }
                    if (color == null) {
                        Text(
                            stringResource(CommonR.string.common_auto),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Swatch(color)
                    }
                }
            },
            onClick = { open = true },
        )
    }
    if (open) {
        ColorPickerDialog(
            title = title,
            initial = color ?: fallback,
            supportsAlpha = supportsAlpha,
            showReset = color != null,
            onPick = { onChange(it); open = false },
            onReset = { onChange(null); open = false },
            onDismiss = { open = false },
        )
    }
}

// ---- collapsing app bar ----

/** Bar geometry. Material's large-bar heights, so the rest of the layout still fits. */
private val CollapsedBarHeight = 64.dp
private val ExpandedBarHeight = 152.dp

/** Title inset: 16dp on its own, cleared past the navigation icon when there is one. */
private val TitleInset = 16.dp
private val TitleInsetWithNav = 56.dp

/** The gap between a heading icon and the title beside it. */
private val HeaderIconGap = 12.dp

/**
 * How far into the collapse the heading icon has finished fading. The icon
 * belongs to the heading, not to the bar, so it is gone long before the bar
 * strip it would otherwise land in.
 */
private const val HeaderIconFadeSpan = 0.35f

/** What a heading icon shrinks to when it is one of the ones that stays. */
private const val BarIconScale = 0.65f

/** A heading icon drawn without a tile — the glyph alone, in its own colour. */
internal val HeaderGlyphSize = 30.dp
private val HeaderBareIconSize = HeaderGlyphSize

/** The badge above a centred heading — the app's own icon, on the root screen. */
internal val HeaderBadgeSize = 68.dp

/** What a badge that travels into the bar shrinks to on the way. */
private const val BadgeBarScale = 0.44f

/** The lane such a badge takes in the collapsed bar, badge plus gap. */
private val BadgeBarLane = HeaderBadgeSize * BadgeBarScale + 8.dp

/** How far the title and its subtitle part either side of a two-line strip. */
private val BarStackSplit = 12.dp

/** What a subtitle that stays shrinks to, so the two lines clear each other. */
private const val BarSubtitleScale = 0.85f

/** The room a badge needs above the heading, added to the bar's own height. */
private val BadgeBand = 28.dp

/**
 * How much of the section's colour the heading carries. The bar strip is the
 * band that has to read as chrome; the heading is the page's own top, so it
 * only warms towards the section rather than announcing it.
 */
private const val HeadingTintDark = 0.07f
private const val HeadingTintLight = 0.05f

/** How much of the section's own colour the collapsed bar is tinted with. */
private const val BarTintDark = 0.18f
private const val BarTintLight = 0.13f

/**
 * The two colours a section paints its chrome with: [expanded] across the
 * heading at the top of the page, and [collapsed] on the strip a scrolled page
 * leaves behind. The bar crossfades between them as it collapses; the path
 * strip under it wears the second one throughout, so it reads as a band while
 * the heading is open and merges with the bar once the page is scrolled.
 */
@Immutable
internal data class BarTints(val expanded: Color, val collapsed: Color)

/** [BarTints] for a destination, or for an explicit [barTint] that overrides it. */
@Composable
internal fun barTints(route: String?, barTint: Color? = null): BarTints {
    val sectionTint = barTint ?: route?.let { SettingsRouteColors[it] }
    val dark = isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.surface
    val container = MaterialTheme.colorScheme.surfaceContainer
    if (sectionTint == null) return BarTints(surface, container)
    return BarTints(
        expanded = androidx.compose.ui.graphics.lerp(
            surface,
            sectionTint,
            if (dark) HeadingTintDark else HeadingTintLight,
        ),
        collapsed = androidx.compose.ui.graphics.lerp(
            container,
            sectionTint,
            if (dark) BarTintDark else BarTintLight,
        ),
    )
}

/** How far above the expanded bar's bottom edge the title's centre line sits. */
private val ExpandedTitleCenterFromBottom = 40.dp

/**
 * The band a subtitle adds below the bar. Added to both the bar's height and
 * the title's distance from its bottom, so the title itself does not move:
 * the bar simply grows a strip underneath it for the line to sit in.
 */
private val SubtitleBand = 24.dp

/** Width the collapsed pose keeps clear for the navigation icon and actions. */
private val CollapsedFurnitureWidth = 112.dp

/** The size the title settles at once the bar is collapsed. */
private const val CollapsedTitleSp = 20f

/** Tracking as a fraction of the title's size; negative, so it tightens. */
private const val TitleTrackingRatio = -0.018f

/**
 * The expanded title size. Long titles start smaller so the heading stays on
 * one line — the title is a single moving `Text`, not two cross-faded ones, so
 * it cannot wrap in one state and not the other.
 *
 * The steps are the app's display face at its display sizes (see
 * [WmTypography]): a short title gets the full 33sp, and each band below it
 * trades size for room. Manrope Bold sets wider than the Roboto these numbers
 * were first chosen for, hence the extra band rather than a straight scale-up.
 */
private fun expandedTitleSp(title: String): Float = when {
    title.length <= 14 -> 33f
    title.length <= 20 -> 28f
    title.length <= 26 -> 24f
    else -> 21f
}

/**
 * Where the heading's parts sit at the bar's current degree of collapse.
 *
 * All of them ride the title's line: the icon beside it, the badge above, the
 * subtitle below, each named by how far it hangs off that line. Poses are
 * reached by *placing* — never by translating a drawn layer — because a shared
 * element measures where its layout lands, and a heading that flew in from a
 * row has to land where it is actually laid out.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class HeadingPose(
    private val scrollBehavior: TopAppBarScrollBehavior,
    private val centerTitle: Boolean,
    private val startX: Float,
    private val endX: Float,
    private val expandedY: Float,
    private val centeredExpandedY: Float,
) {
    val fraction: Float get() = scrollBehavior.state.collapsedFraction

    val alignment: Alignment get() = if (centerTitle) Alignment.TopCenter else Alignment.TopStart

    val contentAlignment: Alignment
        get() = if (centerTitle) Alignment.Center else Alignment.CenterStart

    val origin: TransformOrigin
        get() = if (centerTitle) TransformOrigin(0.5f, 0.5f) else TransformOrigin(0f, 0.5f)

    /**
     * Where a part sits: [drop] below the title's line while expanded, and
     * [collapsedDrop] below the bar's own centre once collapsed — which is 0
     * for everything except a title and subtitle that both stay, and have to
     * stack in the strip rather than land on each other.
     */
    fun offsetAt(drop: Float, collapsedDrop: Float = 0f): IntOffset {
        val f = fraction
        val y = (if (centerTitle) centeredExpandedY else expandedY) + drop
        return IntOffset(
            if (centerTitle) 0 else lerp(startX, endX, f).roundToInt(),
            lerp(y, collapsedDrop, f).roundToInt(),
        )
    }
}

/**
 * A part of the heading that belongs to the heading alone: it rides the title
 * up and is gone before the bar has finished becoming a strip.
 */
@Composable
private fun BoxScope.HeadingFader(
    pose: HeadingPose,
    drop: Float,
    maxWidth: Dp = Dp.Unspecified,
    scaleTo: Float = 1f,
    keep: Boolean = false,
    collapsedDrop: Float = 0f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(pose.alignment)
            .height(CollapsedBarHeight)
            .then(
                if (maxWidth == Dp.Unspecified) Modifier
                else Modifier.widthIn(max = maxWidth.coerceAtLeast(0.dp)),
            )
            .offset { pose.offsetAt(drop, collapsedDrop) }
            .graphicsLayer {
                if (!keep) {
                    alpha = (1f - pose.fraction / HeaderIconFadeSpan).coerceIn(0f, 1f)
                }
                if (scaleTo != 1f) {
                    val s = lerp(1f, scaleTo, pose.fraction)
                    scaleX = s
                    scaleY = s
                }
            },
        contentAlignment = pose.contentAlignment,
    ) {
        content()
    }
}

/**
 * The badge above a centred heading — the app's own icon on the root screen.
 * [keep] walks it out of the middle and into the bar's left edge as the bar
 * collapses, shrinking as it goes, the same trip the title makes.
 */
@Composable
private fun BoxScope.HeadingBadge(
    pose: HeadingPose,
    rise: Float,
    expandedX: Float,
    collapsedX: Float,
    keep: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .height(CollapsedBarHeight)
            .offset {
                val f = pose.fraction
                IntOffset(
                    lerp(expandedX, if (keep) collapsedX else expandedX, f).roundToInt(),
                    pose.offsetAt(-rise).y,
                )
            }
            .graphicsLayer {
                val f = pose.fraction
                if (keep) {
                    val s = lerp(1f, BadgeBarScale, f)
                    scaleX = s
                    scaleY = s
                    // Its left edge is where it is going, so it shrinks onto
                    // the inset rather than away from it.
                    transformOrigin = TransformOrigin(0f, 0.5f)
                } else {
                    alpha = (1f - f / HeaderIconFadeSpan).coerceIn(0f, 1f)
                    val s = lerp(1f, 0.6f, f)
                    scaleX = s
                    scaleY = s
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/**
 * The heading's icon. [keepIcon] is the one that comes along into the collapsed
 * bar — shrunk to something the strip can hold beside the title — rather than
 * fading out with the rest of the heading.
 */
@Composable
private fun BoxScope.HeadingIcon(
    pose: HeadingPose,
    keepIcon: Boolean,
    startX: Float,
    endX: Float,
    accent: Color,
    tile: Boolean,
    content: @Composable () -> Unit,
) {
    val moving = Modifier
        .wmSharedElement(landingKey("icon"))
        .graphicsLayer {
            val f = pose.fraction
            if (keepIcon) {
                val s = lerp(1f, BarIconScale, f)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            } else {
                alpha = (1f - f / HeaderIconFadeSpan).coerceIn(0f, 1f)
            }
        }
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .height(CollapsedBarHeight)
            .offset {
                val f = pose.fraction
                IntOffset(
                    lerp(startX, if (keepIcon) endX else startX, f).roundToInt(),
                    pose.offsetAt(0f).y,
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (tile) {
            WmIconTile(accent, moving, content = content)
        } else {
            // No tile, no wash: the glyph itself, in its own colour. It is then
            // the same object the row it flew from was drawing, only bigger.
            CompositionLocalProvider(LocalContentColor provides accent) {
                Box(modifier = moving, contentAlignment = Alignment.Center, content = { content() })
            }
        }
    }
}

/**
 * The app bar. Material's `LargeTopAppBar` draws the title twice and cross-fades
 * between them, which is why every Material app's heading blinks as you scroll.
 * This one draws it once and moves it: the same glyphs slide up to the bar and
 * shrink into place, so the heading and the bar title are visibly the same
 * object.
 *
 * Both poses are reached by *placing* the title, not by translating a drawn
 * layer: a shared element measures where its layout lands, so a heading that
 * flew in from the home list has to land where it is actually laid out.
 *
 * [route] names the settings destination this bar heads, which is what gives
 * the heading its icon, tints the collapsed strip with the section's own
 * colour, and keys the flight from the row that opened it. [icon] and [accent]
 * override the lookup for screens whose subject is not a fixed destination — a
 * tool, an addon — and those leave the strip its usual colour, since a page per
 * tool would otherwise repaint the bar a dozen ways.
 *
 * [iconInBar] keeps the icon through the collapse, shrunk in beside the title,
 * rather than dropping it with the heading. [centerTitle] is the root screen's
 * pose: the app's own name, centred in the bar both ways rather than hung off
 * its left edge. [subtitle] and [badge] belong to the heading rather than to
 * the bar, so like the icon they are gone by the time the bar has collapsed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WmCollapsingTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    route: String? = null,
    icon: (@Composable () -> Unit)? = null,
    accent: Color? = null,
    iconTile: Boolean = true,
    iconInBar: Boolean = false,
    barTint: Color? = null,
    centerTitle: Boolean = false,
    subtitle: String? = null,
    subtitleIcon: ImageVector? = null,
    subtitleIconTint: Color? = null,
    subtitleInBar: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
    badgeInBar: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val collapsedPx = with(density) { CollapsedBarHeight.toPx() }
    // The subtitle's band is added below the title and taken off its distance
    // from the bottom, so the title itself does not move. A badge sits above
    // instead, so its band is pure extra height: the bar grows downward from a
    // fixed top, which is what makes room over the heading.
    val barHeight = ExpandedBarHeight +
        (if (subtitle != null) SubtitleBand else 0.dp) +
        (if (badge != null) BadgeBand else 0.dp)
    val expandedPx = with(density) { barHeight.toPx() }
    // Material owns the scroll maths but never learns how far this bar may
    // collapse, because this bar is not one of its own.
    SideEffect {
        scrollBehavior.state.heightOffsetLimit = collapsedPx - expandedPx
    }

    val routeIcon = route?.let { SettingsRouteIcons[it] }
    val headerIcon: (@Composable () -> Unit)? = icon
        ?: routeIcon?.let { glyph ->
            { Icon(glyph, contentDescription = null, modifier = Modifier.size(WmIconTileGlyph)) }
        }
    val headerAccent = accent ?: routeAccent(route.orEmpty())
    val keepIcon = headerIcon != null && iconInBar

    val expandedSp = expandedTitleSp(title)
    val collapsedScale = CollapsedTitleSp / expandedSp
    // Where the expanded title's left edge sits: past the heading icon when
    // there is one, at the plain inset otherwise.
    val iconSize = if (iconTile) IconTileSize else HeaderBareIconSize
    val startXDp = TitleInset + if (headerIcon != null) iconSize + HeaderIconGap else 0.dp
    val startX = with(density) { startXDp.toPx() }
    // And where it settles: past the navigation icon, and past the heading icon
    // as well when that one is staying.
    val barIconLane = iconSize * BarIconScale + 8.dp
    val endXDp = (if (onBack != null) TitleInsetWithNav else TitleInset) +
        (if (keepIcon) barIconLane else 0.dp) +
        (if (badge != null && badgeInBar) BadgeBarLane else 0.dp)
    val barStackSplitPx = with(density) { BarStackSplit.toPx() }
    val endX = with(density) { endXDp.toPx() }
    // The title box is the collapsed bar's height and centres its text, so the
    // collapsed pose needs no offset at all and only the expanded one is
    // computed: drop the centre line to sit above the expanded bar's bottom,
    // clear of the subtitle band when there is one.
    val expandedY = expandedPx -
        with(density) {
            (
                ExpandedTitleCenterFromBottom +
                    (if (subtitle != null) SubtitleBand else 0.dp) +
                    (if (badge != null) BadgeBand else 0.dp)
                ).toPx()
        } -
        collapsedPx / 2f
    // Far enough below the title's centre line to clear its descenders: half a
    // line of the title, then the subtitle's own half-line and a gap.
    val titleHalfPx = with(density) { (expandedSp * 1.25f / 2f).dp.toPx() }
    val subtitleDropPx = titleHalfPx + with(density) { 13.dp.toPx() }
    val badgeRisePx = with(density) { (HeaderBadgeSize / 2 + 10.dp).toPx() } + titleHalfPx
    // The centred pose sits in the middle of whatever height the bar currently
    // has — and it is the whole stack that is centred, badge through subtitle,
    // so the title alone rides off the middle by half the stack's imbalance.
    val above = if (badge != null) badgeRisePx + with(density) { (HeaderBadgeSize / 2).toPx() }
    else titleHalfPx
    val below = if (subtitle != null) subtitleDropPx + with(density) { 9.dp.toPx() }
    else titleHalfPx
    val centeredExpandedY = (expandedPx - collapsedPx) / 2f - (below - above) / 2f
    val iconStartX = with(density) { TitleInset.toPx() }
    val iconEndX = with(density) { (if (onBack != null) TitleInsetWithNav else TitleInset).toPx() }

    // A section wears its own colour twice over: a wash across the heading at
    // the top of the page, and a stronger one on the strip a scrolled page
    // leaves behind. The heading's is the quieter of the two — it is the page's
    // own top, not chrome, and at full strength it reads as a coloured block.
    val tints = barTints(route, barTint)
    val base = tints.expanded
    val scrolled = tints.collapsed
    val titleColor = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // Reading the scroll state here (rather than in composition) keeps
            // a scroll to the draw and layout phases — the settings screens are
            // heavy enough that recomposing them per frame is visible.
            .drawBehind {
                drawRect(
                    androidx.compose.ui.graphics.lerp(
                        base,
                        scrolled,
                        scrollBehavior.state.collapsedFraction,
                    ),
                )
            }
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .layout { measurable, constraints ->
                val h = (expandedPx + scrollBehavior.state.heightOffset)
                    .roundToInt()
                    .coerceIn(collapsedPx.roundToInt(), expandedPx.roundToInt())
                val placeable = measurable.measure(
                    constraints.copy(minHeight = h, maxHeight = h),
                )
                layout(placeable.width, h) { placeable.place(0, 0) }
            },
    ) {
        val barWidth: Dp = maxWidth
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(CollapsedBarHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(CommonR.string.common_back),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        val pose = HeadingPose(
            scrollBehavior = scrollBehavior,
            centerTitle = centerTitle,
            startX = startX,
            endX = endX,
            expandedY = expandedY,
            centeredExpandedY = centeredExpandedY,
        )
        if (headerIcon != null) {
            HeadingIcon(pose, keepIcon, iconStartX, iconEndX, headerAccent, iconTile, headerIcon)
        }
        if (badge != null) {
            HeadingBadge(
                pose = pose,
                rise = badgeRisePx,
                expandedX = with(density) { ((barWidth - HeaderBadgeSize) / 2).toPx() },
                collapsedX = with(density) { TitleInset.toPx() },
                keep = badgeInBar,
                content = badge,
            )
        }
        // Room for the navigation icon and one or two actions in the collapsed
        // pose, and for the icon lane in the expanded one, so a long title
        // ellipsises instead of sliding under either. The collapsed budget is
        // measured before the shrink, hence the divide.
        val titleMax = minOf(
            barWidth - startXDp - TitleInset,
            (barWidth - CollapsedFurnitureWidth - if (keepIcon) barIconLane else 0.dp) /
                collapsedScale,
        ).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .align(pose.alignment)
                .height(CollapsedBarHeight)
                .widthIn(max = titleMax)
                // Placement, not translation: a shared heading has to land
                // where the layout says it is, and reading the scroll state in
                // a lambda still keeps this off the recomposition path.
                .offset { pose.offsetAt(0f, if (subtitleInBar) -barStackSplitPx else 0f) }
                .graphicsLayer {
                    val s = lerp(1f, collapsedScale, pose.fraction)
                    scaleX = s
                    scaleY = s
                    // The anchor both poses share: the centred title keeps its
                    // middle, every other one its left edge.
                    transformOrigin = pose.origin
                },
            contentAlignment = pose.contentAlignment,
        ) {
            Text(
                title,
                modifier = if (route == null) Modifier
                else Modifier.wmSharedBounds(landingKey("title")),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = expandedSp.sp,
                    lineHeight = (expandedSp * 1.25f).sp,
                    // Tracking is an absolute size, so a heading that picked a
                    // smaller step would otherwise keep the tightening meant
                    // for the largest one. Held proportional instead.
                    letterSpacing = (expandedSp * TitleTrackingRatio).sp,
                ),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (subtitle != null) {
            HeadingFader(
                pose = pose,
                drop = subtitleDropPx,
                maxWidth = barWidth - startXDp - TitleInset,
                scaleTo = if (subtitleInBar) BarSubtitleScale else 1f,
                keep = subtitleInBar,
                collapsedDrop = if (subtitleInBar) barStackSplitPx else 0f,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subtitleIcon != null) {
                        Icon(
                            subtitleIcon,
                            contentDescription = null,
                            tint = subtitleIconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        subtitle,
                        modifier = if (route == null) Modifier
                        else Modifier.wmSharedBounds(landingKey("subtitle")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The frame every settings destination sits in: the moving-title bar over a
 * scrolling column. [onBack] is null on the home screen, which has no parent to
 * return to. [route], [centerTitle] and [subtitle] are the bar's, and mean what
 * they mean on [WmCollapsingTopBar]. [crumbTitle] is what this screen is called
 * in the path strip of the screens below it, for the one screen whose heading
 * is not its name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WmScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    route: String? = null,
    icon: (@Composable () -> Unit)? = null,
    accent: Color? = null,
    iconTile: Boolean = true,
    iconInBar: Boolean = false,
    barTint: Color? = null,
    centerTitle: Boolean = false,
    subtitle: String? = null,
    subtitleIcon: ImageVector? = null,
    subtitleIconTint: Color? = null,
    subtitleInBar: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
    badgeInBar: Boolean = false,
    crumbTitle: String? = null,
    anim: AnimatedVisibilityScope? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * A floating action button, bottom right. The one place a screen's
     * "add" lives: a list that can grow gets a FAB, not a button row.
     */
    fab: (@Composable () -> Unit)? = null,
    /**
     * Content pinned under the bar, above the scrolling body — a live
     * preview that must stay in view while the rows below it change.
     */
    pinned: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    WmScreenFrame(
        title = title,
        onBack = onBack,
        route = route,
        icon = icon,
        accent = accent,
        iconTile = iconTile,
        iconInBar = iconInBar,
        barTint = barTint,
        centerTitle = centerTitle,
        subtitle = subtitle,
        subtitleIcon = subtitleIcon,
        subtitleIconTint = subtitleIconTint,
        subtitleInBar = subtitleInBar,
        badge = badge,
        badgeInBar = badgeInBar,
        crumbTitle = crumbTitle,
        anim = anim,
        actions = actions,
        fab = fab,
        pinned = pinned,
    ) { padding ->
        val scrollLock = rememberFlightScrollLock()
        val scrollState = rememberScrollState()
        // A screen coming back off the back stack restores its scroll offset,
        // and ScrollState clamps that offset to whatever is actually there —
        // so deferring rows on the way back would throw the user's place away.
        // Deferral is for fresh opens, which always start at the top.
        val reveal = remember {
            ScreenReveal().apply { if (scrollState.value > 0) isSettled = true }
        }
        val settled = rememberScreenSettled()
        LaunchedEffect(settled) {
            if (!settled) return@LaunchedEffect
            // Anything asking from here on composes straight away; the queue
            // built during the entrance is drained one group per frame.
            reveal.isSettled = true
            while (reveal.wave < reveal.deferredCount) {
                withFrameNanos { }
                reveal.wave++
            }
        }
        CompositionLocalProvider(LocalScreenReveal provides reveal) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .nestedScroll(scrollLock)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.Top,
            ) {
                content()
                // Room for a FAB to float over the last row rather than on it:
                // the button, its margin, and a little air.
                val hasFab = fab != null || LocalScreenSlots.current?.fab != null
                Spacer(Modifier.height(if (hasFab) FAB_TAIL else 24.dp))
            }
        }
    }
}

/** Height a scrolling body leaves free under a floating action button. */
private val FAB_TAIL = 88.dp

/**
 * [WmScreen] for a destination whose body is a lazy grid.
 *
 * [WmScreen] puts its content in a `verticalScroll`, and a lazy grid cannot
 * nest inside one — it has no bounded height to work with. A photo browser
 * needs a real lazy container to page through thousands of results, so it gets
 * the same frame with the scrolling column swapped for the grid itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WmLazyScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    route: String? = null,
    accent: Color? = null,
    subtitle: String? = null,
    subtitleInBar: Boolean = false,
    anim: AnimatedVisibilityScope? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: (@Composable () -> Unit)? = null,
    pinned: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    WmScreenFrame(
        title = title,
        onBack = onBack,
        route = route,
        accent = accent,
        subtitle = subtitle,
        subtitleInBar = subtitleInBar,
        anim = anim,
        actions = actions,
        fab = fab,
        pinned = pinned,
        content = content,
    )
}

/** The frame both screen shapes share: the moving-title bar over a body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WmScreenFrame(
    title: String,
    onBack: (() -> Unit)? = null,
    route: String? = null,
    icon: (@Composable () -> Unit)? = null,
    accent: Color? = null,
    iconTile: Boolean = true,
    iconInBar: Boolean = false,
    barTint: Color? = null,
    centerTitle: Boolean = false,
    subtitle: String? = null,
    subtitleIcon: ImageVector? = null,
    subtitleIconTint: Color? = null,
    subtitleInBar: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
    badgeInBar: Boolean = false,
    crumbTitle: String? = null,
    anim: AnimatedVisibilityScope? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: (@Composable () -> Unit)? = null,
    pinned: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Which screen this is, and which screen opened it. Read once: the origin
    // is whatever the row that navigated here left behind, and it has to stay
    // put for as long as this destination lives, including the way back.
    val origin = remember { FlightOrigin.pending }
    // The path strip's two halves: the trail the graph keeps, and the back
    // stack entry this screen is drawn for. Both are null when a house screen
    // is drawn outside the graph, and then there is no strip.
    val trail = LocalSettingsCrumbTrail.current
    val entry = currentCrumbEntry()
    RegisterSettingsCrumb(crumbTitle ?: title)
    val slots = remember { ScreenSlots() }
    // The destination's own animation scope, published for everything the
    // screen draws — the heading above, and any row below that flies somewhere.
    CompositionLocalProvider(
        LocalNavAnimatedScope provides (anim ?: LocalNavAnimatedScope.current),
        LocalScreenRoute provides route,
        LocalFlightOrigin provides origin,
        LocalScreenSlots provides slots,
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                Column {
                    WmCollapsingTopBar(
                        title = title,
                        scrollBehavior = scrollBehavior,
                        onBack = onBack,
                        route = route,
                        icon = icon,
                        accent = accent,
                        iconTile = iconTile,
                        iconInBar = iconInBar,
                        barTint = barTint,
                        centerTitle = centerTitle,
                        subtitle = subtitle,
                        subtitleIcon = subtitleIcon,
                        subtitleIconTint = subtitleIconTint,
                        subtitleInBar = subtitleInBar,
                        badge = badge,
                        badgeInBar = badgeInBar,
                        actions = actions,
                    )
                    // Below the bar rather than inside it: the bar measures
                    // itself to the collapse, and a second line in there would
                    // have to be written into that arithmetic. The strip draws
                    // nothing until the third level down, so most screens pay
                    // an empty layout node for it and no height.
                    if (trail != null && entry != null) {
                        SettingsBreadcrumbBar(
                            trail = trail,
                            entryId = entry.id,
                            tint = barTints(route, barTint).collapsed,
                        )
                    }
                    // Inside the bar's column rather than the body: the bar is
                    // what Scaffold measures for its content padding, so a
                    // pinned block costs no arithmetic here and stays put
                    // while the collapsing title above it does its thing.
                    (pinned ?: slots.pinned)?.invoke()
                }
            },
            floatingActionButton = { (fab ?: slots.fab)?.invoke() },
            content = content,
        )
    }
}

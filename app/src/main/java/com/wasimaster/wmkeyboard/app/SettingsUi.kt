package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.share.ShareSheetRoute
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.scaleToBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.wasimaster.wmkeyboard.core.settings.SettingsDepth
import kotlinx.coroutines.CoroutineScope
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animate
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.wasimaster.wmkeyboard.core.ui.DisabledReason
import com.wasimaster.wmkeyboard.core.ui.ToolPaint
import com.wasimaster.wmkeyboard.common.R as CommonR
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Chat
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

// ---- icon tiles, on and off ----

/** How long the tiles take to leave or come back when "Icons in settings" flips. */
private const val IconRevealMs = 320

/**
 * How much of its icon tile a settings row shows, from 1 (all of it) to 0 (none,
 * the plain rows the app had before the tiles), after **Icons in settings** on
 * the Accessibility screen.
 *
 * One animation for the whole app rather than one per row, published through
 * [LocalIconReveal]. Only the rows on screen when the switch moves are there to
 * see it travel, which is the screen the switch is on; anything opened later
 * composes after the animation has finished and simply draws the answer.
 *
 * [progress] is read in layout and draw only, so the tiles' travel re-lays-out
 * the rows it moves without recomposing them. [present] is the question
 * composition asks — is there a tile to draw at all — and it changes once at
 * each end of the travel.
 */
@Stable
internal class IconReveal(val progress: State<Float>) {
    val present: Boolean by derivedStateOf { progress.value > 0f }
}

/** The answer outside the settings host: every tile, always. */
private val AllIconsShown = IconReveal(mutableFloatStateOf(1f))

/** The app's [IconReveal]. Published at the settings root beside [LocalReduceMotion]. */
internal val LocalIconReveal = compositionLocalOf { AllIconsShown }

/** The [IconReveal] the settings root publishes: [shown] animated, unless [still]. */
@Composable
internal fun rememberIconReveal(shown: Boolean, still: Boolean): IconReveal {
    val progress = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (still) snap() else tween(IconRevealMs, easing = FastOutSlowInEasing),
        label = "settingsIcons",
    )
    return remember(progress) { IconReveal(progress) }
}

/**
 * Whether a row drawing [icon] currently gives it a tile. The rows that budget
 * their own width ask this, so a value that had to go under the title for want
 * of room can come back beside it once the tile has gone.
 */
@Composable
internal fun rowHasTile(icon: ImageVector?): Boolean = icon != null && LocalIconReveal.current.present

/**
 * Draws a tile at [reveal]'s share of its width, shrunk and faded to match, so
 * the lane it stands in closes as it goes rather than leaving a hole that snaps
 * shut at the end. It shrinks towards the row's outer edge, where it sits.
 */
internal fun Modifier.iconReveal(reveal: IconReveal): Modifier = layout { measurable, constraints ->
    val p = reveal.progress.value
    val placeable = measurable.measure(constraints)
    val width = (placeable.width * p).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
    val originX = if (layoutDirection == LayoutDirection.Ltr) 0f else 1f
    layout(width, placeable.height) {
        placeable.placeRelativeWithLayer(0, 0) {
            alpha = p
            scaleX = p
            scaleY = p
            transformOrigin = TransformOrigin(originX, 0.5f)
        }
    }
}

/** The gap `ListItem` keeps after its leading slot whenever anything is in it. */
private val ListItemLeadingGap = 16.dp

/**
 * Pays back `ListItem`'s leading gap as a tile leaves.
 *
 * The gap stays while the slot holds anything, however narrow, so a tile shrunk
 * to nothing still leaves 16 dp of air before the words — and taking the slot
 * away at the end would jerk them sideways by it. Instead the row is laid out
 * that much wider and slid back past its start edge, by as much of the gap as
 * the tile has lost. Only the leading side moves: the far end lands exactly
 * where it was, so a switch or a chevron stays put.
 */
private fun Modifier.leadingGapReveal(reveal: IconReveal): Modifier = layout { measurable, constraints ->
    val paid = if (!constraints.hasBoundedWidth) 0
    else (ListItemLeadingGap.toPx() * (1f - reveal.progress.value)).roundToInt()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + paid,
            maxWidth = constraints.maxWidth + paid,
        ),
    )
    layout(placeable.width - paid, placeable.height) { placeable.placeRelative(-paid, 0) }
}

/**
 * The accent each settings destination is drawn with, keyed by the same routes
 * as [SettingsRouteIcons]. Neighbouring rows never share a hue, so the home
 * list reads as a set of distinct places rather than one column of primary.
 * Screens that are not home rows fall back to [defaultRouteAccent].
 */
internal val SettingsRouteColors: Map<String, Color> = mapOf(
    "typing" to Color(0xFF42A5F5),
    "typing/corrections" to Color(0xFF42A5F5),
    "typing/writing" to Color(0xFF42A5F5),
    "typing/suggestions" to Color(0xFF42A5F5),
    "typing/autopilot" to Color(0xFF42A5F5),
    "typing/octopus" to Color(0xFF29B6F6),
    "typing/chips" to Color(0xFF42A5F5),
    "typing/codes" to Color(0xFF42A5F5),
    "typing/gestures" to Color(0xFF42A5F5),
    "typing/gestures/glide" to Color(0xFF42A5F5),
    "typing/gestures/glide/checkup" to Color(0xFF42A5F5),
    "typing/gestures/spacebar" to Color(0xFF42A5F5),
    "typing/gestures/flicks" to Color(0xFF42A5F5),
    "typing/hardware" to Color(0xFF42A5F5),
    "keypress" to Color(0xFF7E57C2),
    "keypress/haptics" to Color(0xFF7E57C2),
    "keypress/popup" to Color(0xFF7E57C2),
    "keypress/shortcuts" to Color(0xFF7E57C2),
    "languages" to Color(0xFF66BB6A),
    "appearance" to Color(0xFFEC407A),
    "appearance/toolbar" to Color(0xFFEC407A),
    "appearance/toolbox" to Color(0xFFEC407A),
    "themes" to Color(0xFFEC407A),
    "photos" to Color(0xFF00ACC1),
    "photo_browse" to Color(0xFF00ACC1),
    "photo_library" to Color(0xFF00ACC1),
    "photo_rotation" to Color(0xFF00ACC1),
    "fonts" to Color(0xFFAB47BC),
    "icons" to Color(0xFFFF7043),
    "layout" to Color(0xFF5C6BC0),
    "layout/size" to Color(0xFF5C6BC0),
    "layout/onehanded" to Color(0xFF5C6BC0),
    "keymaps" to Color(0xFF26C6DA),
    "rows" to Color(0xFF26A69A),
    "rows/symbol" to Color(0xFF26A69A),
    "ai" to Color(0xFF7E57C2),
    "ai_actions" to Color(0xFF7E57C2),
    "ai_history" to Color(0xFF7E57C2),
    "ai_memory" to Color(0xFF7E57C2),
    "ai_chat" to Color(0xFF7E57C2),
    "settings_assistant" to Color(0xFF7E57C2),
    "ai_mcp" to Color(0xFF7E57C2),
    "ai_tools" to Color(0xFF7E57C2),
    "modes" to Color(0xFFFFA726),
    "emoji" to Color(0xFFFFB300),
    "emoji/panel" to Color(0xFFFFB300),
    "emoji/mixes" to Color(0xFFFFCA28),
    "voice" to Color(0xFF7E57C2),
    "clipboard" to Color(0xFF42A5F5),
    "clipboard/history" to Color(0xFF42A5F5),
    "clipboard/chips" to Color(0xFF42A5F5),
    "clipboard/rules" to Color(0xFF42A5F5),
    // The Snippets tool's own teal: the screen and the tool page it is opened
    // from are the same feature.
    "expander" to Color(0xFF26A69A),
    "tools" to Color(0xFFFF7043),
    "sticker_packs" to Color(0xFFF06292),
    "vocab/packs" to Color(0xFF8E24AA),
    "vocab/lists" to Color(0xFF8E24AA),
    "vocab/review" to Color(0xFF8E24AA),
    "vocab/browse" to Color(0xFF8E24AA),
    // The Notes tool's own yellow, see ToolColors.
    // The Clock tool's own indigo, see ToolColors.
    "clock" to Color(0xFF5C6BC0),
    "homeassistant/entities" to Color(0xFF18BCF2),
    "notes" to Color(0xFFFBC02D),
    "notes/tags" to Color(0xFFFBC02D),
    "notes/trash" to Color(0xFFFBC02D),
    // The To-do tool's own green, see ToolColors.
    "todo" to Color(0xFF43A047),
    // The editor is opened from a pack, so it keeps the pack screens' pink.
    "sticker_editor" to Color(0xFFF06292),
    "plugins" to Color(0xFF06B6D4),
    "addons" to Color(0xFF7986CB),
    "accessibility" to Color(0xFF29B6F6),
    "privacy" to Color(0xFFEF5350),
    // Children of Privacy, so they keep the parent's red.
    "permissions" to Color(0xFFEF5350),
    "network_activity" to Color(0xFFEF5350),
    "applock" to Color(0xFFEF5350),
    "automation" to Color(0xFFEF5350),
    "datasaver" to Color(0xFF00897B),
    // A child of Advanced, so it keeps the parent's brown.
    "servers" to Color(0xFF8D6E63),
    "notifications" to Color(0xFF7E57C2),
    "selection_macros" to Color(0xFF5E6BC0),
    "selection_macros/actions" to Color(0xFF5E6BC0),
    "selection_macros/ai" to Color(0xFF5E6BC0),
    "selection_macros/zones" to Color(0xFF5E6BC0),
    "advanced" to Color(0xFF8D6E63),
    "extensions" to Color(0xFF26A69A),
    *Extension.entries.map { extensionRoute(it) to extensionAccent(it) }.toTypedArray(),
    // A child of Advanced, so it keeps the parent's brown.
    FloatingButtonRoute to Color(0xFF8D6E63),
    ShareSheetRoute to Color(0xFF8D6E63),
    "backup" to Color(0xFF78909C),
    "backup/auto" to Color(0xFF78909C),
    "backup/sync" to Color(0xFF78909C),
    "backup/contents" to Color(0xFF78909C),
    "about" to Color(0xFF90A4AE),
    "licenses" to Color(0xFF90A4AE),
    "supporters" to Color(0xFFFFB300),
    // Behind the version row's seven taps, so it keeps About's grey.
    "egg_game" to Color(0xFF90A4AE),
    "debug_log" to Color(0xFF607D8B),
    "storage" to Color(0xFF546E7A),
    "import_hub" to Color(0xFF546E7A),
    "statistics" to Color(0xFF9CCC65),
    "dictionary" to Color(0xFF26A69A),
    "customdictionaries" to Color(0xFF26A69A),
    "blacklist" to Color(0xFFEF5350),
    // A child of Typing → Corrections: the autocorrect family's amber.
    "learnedcorrections" to Color(0xFFFFA726),
    // A child of Clipboard, so it keeps the parent's blue.
    "phoneformats" to Color(0xFF42A5F5),
    "cliprules" to Color(0xFF42A5F5),
    "hwshortcuts" to Color(0xFF5C6BC0),
    "emojikeywords" to Color(0xFFFFB300),
    // A child of the emoji panel, so it keeps the emoji amber. The
    // per-category grid under it takes the default: its destination is
    // "emojiorder/{category}", and the screen names itself by the literal.
    "emojicategories" to Color(0xFFFFB300),
    "emojiadded" to Color(0xFFFFB300),
    // The kaomoji library and its two lists, children of the emoji panel.
    "kaomoji" to Color(0xFFFFB300),
    "kaomoji/faces" to Color(0xFFFFB300),
    "kaomoji/tags" to Color(0xFFFFB300),
    // A child of the media control tool, so it keeps that tool's purple.
    "musicapps" to Color(0xFFAB47BC),
    // A child of the app launcher tool, so it keeps that tool's violet.
    "launchercombos" to Color(0xFF7E57C2),
    "kdeconnect/devices" to Color(0xFF1D99F3),
    // Children of the Send files tool, so they keep that tool's teal.
    "send/instances" to Color(0xFF26A69A),
    "send/history" to Color(0xFF26A69A),
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
 * Whether the settings app is drawing itself still, from **Reduce motion** on
 * the Accessibility screen.
 *
 * Published at the root beside the shared-transition scope, because the small
 * animations far down the tree — a row growing a control, say — have no
 * settings object to read and no business collecting one per row. False
 * outside the settings host, which is what a preview or a test wants.
 */
internal val LocalReduceMotion = compositionLocalOf { false }

// LocalIconReveal, the other root-published look, sits with the icon tiles.

/**
 * The folds the user has opened, by "<route>/<key>", and the way to change
 * that. Published by the nav host so [SettingsGroup] can be a fold without
 * every screen handing it a repository.
 *
 * Holds the live settings rather than the set itself, so that opening one fold
 * recomposes that fold's group alone: a new set published through the local
 * would have reached every group on the screen, and every row in them.
 */
@Stable
internal class AdvancedFolds(private val settings: LiveSettings, val toggle: (String, Boolean) -> Unit) {
    /** Whether the fold [id] is open, subscribing the caller to that answer only. */
    @Composable
    fun isOpen(id: String): Boolean = settings.watch { id in it.appUi.advancedOpen }
}

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
    /**
     * Content docked along the bottom of the window, under the scrolling body
     * and above the navigation bar, the way a keyboard sits under an app. The
     * body's padding grows by its height, so nothing scrolls behind it.
     */
    var dock: (@Composable () -> Unit)? by mutableStateOf(null)
    var refresh: ScreenRefresh? by mutableStateOf(null)

    /**
     * How the body scrolls itself back to its top, for the path strip's last
     * pill. The body owns its scroll state; the strip is in the bar above it.
     * Null for a body that has no single scroll to speak of.
     */
    var toTop: (suspend () -> Unit)? by mutableStateOf(null)
}

/**
 * A screen's own refresh, as the frame needs it: whether one is running, and
 * how to start another.
 */
internal class ScreenRefresh(val refreshing: Boolean, val onRefresh: () -> Unit)

internal val LocalScreenSlots = compositionLocalOf<ScreenSlots?> { null }

/** Puts [content] in the frame's FAB slot for as long as the caller is composed. */
@Composable
internal fun RegisterFab(content: @Composable () -> Unit) {
    val slots = LocalScreenSlots.current ?: return
    SideEffect { slots.fab = content }
    DisposableEffect(slots) { onDispose { slots.fab = null } }
}

/**
 * Hangs the screen's refresh on the frame's pull gesture for as long as the
 * caller is composed. A screen that registers one should not also draw a
 * refresh button: the gesture is the affordance, and two of them is two ways
 * to do one thing.
 *
 * [refreshing] drives the spinner, so it has to be the screen's real "a fetch
 * is running" state and not a flag flipped by the pull. A screen whose refresh
 * finishes instantly can pass `false` throughout: the indicator then springs
 * back as the finger lifts, which is the honest answer.
 */
@Composable
internal fun RegisterPullRefresh(refreshing: Boolean, onRefresh: () -> Unit) {
    val slots = LocalScreenSlots.current ?: return
    SideEffect { slots.refresh = ScreenRefresh(refreshing, onRefresh) }
    DisposableEffect(slots) { onDispose { slots.refresh = null } }
}

/** The one-icon FAB every list screen's "add" is: [label] is its content description. */
@Composable
internal fun RegisterAddFab(label: String, onClick: () -> Unit) {
    RegisterFab {
        FloatingActionButton(
            onClick = onClick,
            // Every screen draws its own button, so without this the add
            // button of the screen being left is destroyed on the same frame
            // the next one's is created — it blinks out and back in half a
            // screen away. One key for all of them: two screens that both have
            // one are showing the same button, and it slides between them.
            modifier = Modifier.wmSharedElement(AddFabKey),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = label)
        }
    }
}

/**
 * The add button's flight key.
 *
 * Deliberately not keyed on the route: the point is that the button is the
 * same object on every screen that has one, so it travels rather than being
 * thrown away and rebuilt.
 */
private const val AddFabKey = "wm.fab.add"

/**
 * How a screen opens the AI chat, published at the settings root while
 * **Chat button on every screen** is on; null otherwise. The frame draws
 * [AiChatFab] from it on every screen but the few in [NoChatFabRoutes].
 */
internal val LocalAiChatFab = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Screens that never get the everywhere button: the chat itself and the
 * settings assistant, which are already a chat, and the AI screen, which
 * registers the same button as its own.
 */
private val NoChatFabRoutes = setOf("ai", "ai_chat", "settings_assistant")

/**
 * The button that opens the AI chat. Full size where it is the screen's one
 * action, as on the AI screen; [small] and in the tertiary colour where it
 * rides beside a screen's own "add", so the two read as one primary action and
 * one aside.
 */
@Composable
internal fun AiChatFab(onClick: () -> Unit, small: Boolean = false) {
    val label = stringResource(R.string.toolai_ai_chat_fab_desc)
    // A wrapper carries the flight key, as on the keyboard test button: the
    // button travels between screens rather than blinking out and back.
    Box(Modifier.wmSharedElement(AiChatFabKey)) {
        if (small) {
            SmallFloatingActionButton(
                onClick = onClick,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = label) }
        } else {
            FloatingActionButton(onClick = onClick) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = label)
            }
        }
    }
}

private const val AiChatFabKey = "wm.fab.ai_chat"

/** Pins [content] under the bar, above the scrolling body, while the caller is composed. */
@Composable
internal fun RegisterPinned(content: @Composable () -> Unit) {
    val slots = LocalScreenSlots.current ?: return
    SideEffect { slots.pinned = content }
    DisposableEffect(slots) { onDispose { slots.pinned = null } }
}

/**
 * Docks [content] along the bottom of the window while the caller is composed:
 * the theme editor's full-size keyboard (#148). The frame's FAB rides above it.
 */
@Composable
internal fun RegisterDock(content: @Composable () -> Unit) {
    val slots = LocalScreenSlots.current ?: return
    SideEffect { slots.dock = content }
    DisposableEffect(slots) { onDispose { slots.dock = null } }
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

/**
 * What a group that has not been let in yet draws in the meantime.
 *
 * [rememberGroupRevealed] used to answer "not yet" by drawing nothing at all,
 * which is the one thing worse than the wait it was added to spread out: the
 * screen opened at a third of its height and then grew under the reader, group
 * by group, and an empty page for three frames reads as jank rather than as
 * something arriving. The slabs below are the shape the rows will be, so the
 * screen opens at its real height and fills in.
 *
 * One node and one draw pass for a whole group, not a placeholder row per row —
 * this stands in for work that was deferred *because* composing rows is what
 * costs, so a skeleton that composed anything per row would spend the budget it
 * exists to save. Everything is drawn, nothing is laid out.
 *
 * Deliberately not animated. A shimmer is an infinite transition, which means a
 * recomposition or a redraw every frame of exactly the entrance this is keeping
 * clear.
 */
@Composable
internal fun GroupSkeleton(rowCount: Int, hasTitle: Boolean, modifier: Modifier = Modifier) {
    val slab = MaterialTheme.colorScheme.surfaceContainer
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = SkeletonInkAlpha)
    val density = LocalDensity.current
    val headingLane = if (hasTitle) SkeletonHeadingLane else 0.dp
    // No tile slab when the rows it stands in for will have no tile either,
    // and the words' bars move up to where the words will be.
    val tiles = LocalIconReveal.current.present
    val textStart = if (tiles) 72.dp else 16.dp
    val height = headingLane + SkeletonRowHeight * rowCount + SkeletonRowGap * (rowCount - 1)
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height + GroupTailSpace)
            .drawBehind {
                with(density) {
                    if (hasTitle) {
                        drawRoundRect(
                            color = ink,
                            topLeft = Offset(32.dp.toPx(), 6.dp.toPx()),
                            size = Size(110.dp.toPx(), 12.dp.toPx()),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                        )
                    }
                    val left = 16.dp.toPx()
                    val right = size.width - 16.dp.toPx()
                    val rowHeight = SkeletonRowHeight.toPx()
                    val gap = SkeletonRowGap.toPx()
                    var top = headingLane.toPx()
                    repeat(rowCount) { index ->
                        // The run's own corners: round on the outside, nearly
                        // square where two rows meet — the same slab shape
                        // [SettingsGroup] builds its cards out of.
                        val first = index == 0
                        val last = index == rowCount - 1
                        drawRoundRect(
                            color = slab,
                            topLeft = Offset(left, top),
                            size = Size(right - left, rowHeight),
                            cornerRadius = CornerRadius(
                                if (first || last) 24.dp.toPx() else 6.dp.toPx(),
                            ),
                        )
                        if (tiles) {
                            drawRoundRect(
                                color = ink,
                                topLeft = Offset(left + 16.dp.toPx(), top + 16.dp.toPx()),
                                size = Size(WmIconTileSize.toPx(), WmIconTileSize.toPx()),
                                cornerRadius = CornerRadius(13.dp.toPx()),
                            )
                        }
                        drawRoundRect(
                            color = ink,
                            topLeft = Offset(left + textStart.toPx(), top + 20.dp.toPx()),
                            size = Size((right - left) * 0.42f, 12.dp.toPx()),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                        )
                        drawRoundRect(
                            color = ink,
                            topLeft = Offset(left + textStart.toPx(), top + 40.dp.toPx()),
                            size = Size((right - left) * 0.62f, 10.dp.toPx()),
                            cornerRadius = CornerRadius(5.dp.toPx()),
                        )
                        top += rowHeight + gap
                    }
                }
            },
    )
}

/** How much of the text colour a placeholder bar carries. */
private const val SkeletonInkAlpha = 0.08f

/** A placeholder row's height: a name, a subtitle and the air around them. */
private val SkeletonRowHeight = 72.dp

/** The gap [SettingsGroup] leaves between two cards in a run. */
private val SkeletonRowGap = 3.dp

/** A heading's line plus the air above and below it. */
private val SkeletonHeadingLane = 32.dp

/** What a group leaves under its last card, matched to `GroupTail`. */
private val GroupTailSpace = 16.dp

/** Published by [WmScreen] around its column; null anywhere else. */
internal val LocalScreenReveal = compositionLocalOf<ScreenReveal?> { null }

/**
 * [WmScreen]'s scroll, published around its column so a row can tell the
 * user's own drag from the page moving under it; null anywhere else.
 */
internal val LocalScreenScroll = compositionLocalOf<ScrollState?> { null }

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
    val queuePlace = remember { reveal.claimGroup(rowCount) }
    return queuePlace == null || reveal.wave > queuePlace
}

/**
 * Whether a group of [rowCount] rows joins the entrance or the queue, moving
 * the ledger on for it: null to compose now, otherwise the place in the queue
 * to wait in.
 *
 * Three rules, in this order:
 *
 * - **The first group always composes**, however big it is. A screen whose
 *   first group is larger than the whole budget would otherwise open as
 *   nothing but skeletons, which is the failure the budget exists to prevent.
 * - **Once one group has queued, every later group queues too**, however
 *   small. Letting a small group in behind a deferred one fills the page
 *   around the gap the deferred one left rather than top to bottom.
 * - Otherwise a group composes only if it **fits** in what is left of the
 *   budget.
 *
 * That last rule is the whole point of the split. Testing the running total on
 * its own — `claimedRows < EagerRowBudget` — admitted a group *whole* whenever
 * everything before it was under the line, so one group bigger than the budget
 * sailed straight through and the budget never bit. The mode editor's second
 * group is eighteen rows of choice controls, reorderable lists and a flow row;
 * it was composing in the very frame the entrance had to draw in, and measured
 * 250-350 ms of main-thread work on a mid-range phone. That swallowed the
 * entrance whole, and every shared-element flight riding on it with it.
 *
 * Pure, and public to its module, so [ScreenRevealTest] can pin the rules
 * without a composition.
 */
internal fun ScreenReveal.claimGroup(rowCount: Int): Int? = when {
    isSettled -> null
    deferredCount > 0 -> deferredCount++
    claimedRows == 0 || claimedRows + rowCount <= EagerRowBudget -> {
        claimedRows += rowCount
        null
    }
    else -> deferredCount++
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

/**
 * Opens a settings route, for a part shared by many screens that has no
 * navigation of its own to be handed: a provider list opening one provider's
 * screen. A no-op outside the settings graph.
 */
internal val LocalSettingsNavigate = compositionLocalOf<(String) -> Unit> { {} }

/** One end of a flight: which part of a row, going where, from where. */
private fun flightKey(part: String, destination: String, from: String?): String =
    "$part|$destination|${from ?: "-"}"

/** The key a row on this screen uses for its flight into [destination]. */
@Composable
internal fun takeOffKey(part: String, destination: String): String =
    flightKey(part, destination, LocalScreenRoute.current)

/**
 * [takeOffKey] under an origin the row names itself, for a screen with several
 * rows into one destination — search results — where the screen's own name
 * would hang one key on all of them. The row must hand [from] to
 * [FlightOrigin.leaving] as it navigates, so the destination lands on it.
 */
internal fun takeOffKey(part: String, destination: String, from: String): String =
    flightKey(part, destination, from)

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
            resizeMode = scaleToBounds(ContentScale.FillWidth, Alignment.CenterStart),
            zIndexInOverlay = 1f,
        )
    }
}

/**
 * Hands this field the caret the first time it is really on screen.
 *
 * [FocusRequester.requestFocus] throws `FocusRequester is not initialized`
 * when no focus node has attached yet, and running the request from a
 * `LaunchedEffect` is no promise that one has: Material3's Scaffold
 * subcomposes its top bar inside the measure pass, so an effect that lands
 * between composition and the first layout finds no field to focus and takes
 * the app down with it (#237). Waiting for `onPlaced` asks at the one moment
 * the node is known to be there, and only once, so the keyboard does not come
 * back every time the field is moved.
 */
@Composable
internal fun Modifier.focusOncePlaced(
    requester: FocusRequester,
    enabled: Boolean = true,
): Modifier {
    var placed by remember(requester) { mutableStateOf(false) }
    LaunchedEffect(placed, enabled, requester) {
        if (placed && enabled) requester.requestFocus()
    }
    return focusRequester(requester).onPlaced { placed = true }
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
 * [trailingOnTitleLine] moves [trailing] out of `ListItem`'s trailing slot and
 * onto the end of the title's own line (#247). The slot is a column beside the
 * words for the row's whole height, so a switch and a "?" there carve their
 * width out of the subtitle too, and a two-line description becomes eight
 * lines down a 170 dp gutter with the rest of the row blank beside it. Pass
 * this on a row whose trailing furniture is fixed-width controls — a switch, a
 * "?", a reset, a chevron, a swatch — and the description gets the row's full
 * width under them. Leave it off where the slot holds the row's value as
 * *text*: those rows already budget its width with `fitsBesideTitle` and drop
 * it under the title themselves when it will not fit.
 *
 * [titleReflows] draws the name with [ReflowingText], for a row whose
 * trailing furniture changes width under the user — one that can grow a reset
 * control, in practice. It costs a layout node per word, so it is off by
 * default and the helpers that know a row has a default turn it on.
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
 *
 * [swipeActions] puts buttons behind the row that a swipe toward the start
 * shows, and [fullSwipe] one a long swipe runs outright — see
 * [SwipeActionsRow], including why a row with them must be keyed.
 *
 * [disabledReason] is what a long press on the row says while [enabled] is
 * off; see [DisabledReason]. Ignored while the row is enabled.
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
    trailingOnTitleLine: Boolean = false,
    titleReflows: Boolean = false,
    flightTo: String? = null,
    subtitleFlies: Boolean = false,
    enabled: Boolean = true,
    disabledReason: DisabledReason? = null,
    @StringRes highlightKey: Int = 0,
    swipeActions: List<SwipeAction> = emptyList(),
    fullSwipe: SwipeAction? = null,
    onClick: (() -> Unit)? = null,
) {
    if (swipeActions.isNotEmpty()) {
        // The caller's modifier goes on the outside, so one that has to sit
        // on an item's root (a lazy list's `animateItem`) still does, and
        // padding insets the buttons along with the row.
        // Not tied to [enabled]: a row that cannot be opened (an addon source
        // whose list did not load) is often exactly the one to remove.
        SwipeActionsRow(swipeActions, modifier = modifier, fullSwipe = fullSwipe) {
            WmRow(
                title = title,
                subtitle = subtitle,
                icon = icon,
                accent = accent,
                titleStyle = titleStyle,
                titleContent = titleContent,
                leading = leading,
                trailing = trailing,
                supporting = supporting,
                trailingOnTitleLine = trailingOnTitleLine,
                titleReflows = titleReflows,
                flightTo = flightTo,
                subtitleFlies = subtitleFlies,
                enabled = enabled,
                disabledReason = disabledReason,
                highlightKey = highlightKey,
                onClick = onClick,
            )
        }
        return
    }
    // A greyed-out row with a reason takes the long press of any named row,
    // so the hold can say why. Without a key it matches a search on its drawn
    // title, as every title-only row does.
    val reason = disabledReason.takeUnless { enabled }
    if (highlightKey != 0 || reason != null) {
        HighlightableRow(title, highlightKey, disabledReason = reason) {
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
                trailingOnTitleLine = trailingOnTitleLine,
                titleReflows = titleReflows,
                flightTo = flightTo,
                subtitleFlies = subtitleFlies,
                enabled = enabled,
                onClick = onClick,
            )
        }
        return
    }
    val tileAccent = accent ?: currentRouteAccent()
    // Null once "Icons in settings" has taken the tile away altogether; while
    // it is on its way out or back, the tile and the gap after it travel with
    // it. A caller's own [leading] is content rather than decoration, and stays.
    val reveal = LocalIconReveal.current.takeIf { leading == null && icon != null && it.present }
    val titleKey = flightTo?.let { takeOffKey("title", it) }
    val iconKey = flightTo?.let { takeOffKey("icon", it) }
    val screen = LocalScreenRoute.current
    val headline: @Composable () -> Unit = titleContent ?: {
        val tag = if (titleKey == null) Modifier else Modifier.wmSharedBounds(titleKey)
        if (titleReflows) ReflowingText(title, modifier = tag, style = titleStyle)
        else if (titleStyle != null) Text(title, modifier = tag, style = titleStyle)
        else Text(title, modifier = tag)
    }
    val onTitleLine = trailingOnTitleLine && trailing != null
    ListItem(
        headlineContent = if (!onTitleLine) headline else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { headline() }
                    // The trailing slot's own colour: moving the furniture up
                    // to the headline would otherwise repaint a chevron or a
                    // value in the headline's stronger `onSurface`.
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        content = trailing,
                    )
                }
            }
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
            icon != null && reveal != null -> {
                {
                    WmIconTile(
                        icon,
                        tileAccent,
                        modifier = Modifier.iconReveal(reveal).then(
                            if (iconKey == null) Modifier else Modifier.wmSharedElement(iconKey),
                        ),
                    )
                }
            }
            else -> null
        },
        trailingContent = if (onTitleLine) null else trailing,
        colors = transparentListColors(),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(enabled = enabled) {
                    if (flightTo != null) FlightOrigin.leaving(screen)
                    onClick()
                },
            )
            // Inside the click target, so the ripple keeps the row's own bounds.
            .then(if (reveal == null) Modifier else Modifier.leadingGapReveal(reveal)),
    )
}

/**
 * How wide text in a row's trailing slot may get before it ellipsises.
 *
 * Roughly a third of a phone, which leaves the title the two thirds it needs
 * to stay on one or two lines. `ListItem` hands the trailing slot whatever
 * width it asks for and gives the headline what is left, so uncapped text
 * there does not shrink itself — it shrinks the title, down to a letter a
 * line. Any row whose trailing text is longer than the words its author saw
 * (a value in another language, a trigger out of somebody else's pack) has to
 * cap it here. Text longer than two lines at this width was never going to be
 * read off a row anyway.
 */
internal val RowTrailingTextMaxWidth = 132.dp

// ---- haptics ----

/**
 * The settings app's touch feedback.
 *
 * A keyboard is the one app on the phone whose whole job is how a press feels,
 * and until this existed its own settings were the only surface in it that
 * answered a press with nothing at all. These are the two feelings a settings
 * screen has to give: something latched, and something stepped.
 *
 * Constants rather than Compose's [androidx.compose.ui.hapticfeedback.HapticFeedbackType],
 * because the types that mean *these* two things — `TOGGLE_ON`/`TOGGLE_OFF` and
 * `SEGMENT_TICK` — only exist on API 34 and up, and `minSdk` here is 24. The
 * fallbacks are the nearest thing each older platform actually has: a context
 * click for a latch, a clock tick for a step. Every one of them is routed
 * through [View.performHapticFeedback], which already declines when the user
 * has haptics off system-wide, so nothing here needs to read a setting.
 */
internal class SettingsHaptics(private val view: View) {
    /** A switch latching. The two directions feel different on API 34+. */
    fun toggle(on: Boolean) {
        val effect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(effect)
    }

    /**
     * One step passing under the finger: a slider's readout changing, a
     * stepper's arrow, a new option taking the selection.
     */
    fun tick() {
        val effect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                HapticFeedbackConstants.SEGMENT_TICK
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(effect)
    }
}

// ---- undo ----

/**
 * The bar at the foot of a settings screen that says what just happened and
 * offers to put it back.
 *
 * The app's lists — snippets, learnt words, blacklists, themes — were deleted
 * from with a trash button and nothing else: the row went, and that was the
 * whole of it. A confirmation dialog in front of every one of those is the
 * usual answer and the wrong one for a list somebody is tidying, because it
 * puts a modal between the user and each of the twenty rows they came to
 * remove. Deleting at once and offering the way back for a few seconds costs
 * nothing per row and still cannot lose anything.
 *
 * A screen reaches this through [LocalSettingsSnackbar]; the host itself lives
 * in `WmScreenFrame`, so every settings screen has one without asking.
 */
internal class SettingsSnackbar(
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /**
     * Says [message] and offers [undoLabel]; [onUndo] runs if it is pressed.
     *
     * A second call replaces the first rather than queueing behind it. Someone
     * clearing several rows should be offered the way back from the one they
     * just removed, not made to watch the last four announcements go by — and
     * the earlier deletes standing is exactly what dismissing those says.
     */
    fun undo(message: String, undoLabel: String, onUndo: () -> Unit) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }
}

/** The screen's undo bar. Null outside a settings screen's frame. */
internal val LocalSettingsSnackbar = compositionLocalOf<SettingsSnackbar?> { null }

/** The [SettingsHaptics] for the window this composable is drawn in. */
@Composable
internal fun rememberSettingsHaptics(): SettingsHaptics {
    val view = LocalView.current
    return remember(view) { SettingsHaptics(view) }
}

// ---- a name that re-wraps without jumping ----

/**
 * How a word travels to the line it has just been pushed onto, and how the
 * name's own height follows it.
 *
 * Slower and softer than [ResetSizeSpring]: the control's width is being
 * pushed by the finger that tapped, and reads as a direct response, while a
 * word changing lines is a consequence of it and wants to look like one.
 */
private val TitleReflowSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/** The same, for the height the wrapped name reports to its row. */
private val TitleHeightSpring = spring<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/**
 * Animates this element from wherever it was last placed to wherever its
 * parent has just put it.
 *
 * `onPlaced` reports the new position after layout has already happened, so
 * the element is offset *back* to where it was and allowed to travel forward
 * from there. Nothing about the layout changes — the parent has already
 * measured and placed everything, and this only paints the child somewhere
 * else on the way — so a word crossing to another line does not drag the words
 * after it along with it.
 */
@Composable
private fun Modifier.animatePlacement(): Modifier {
    val scope = rememberCoroutineScope()
    // Null until this element has been placed once. The two callbacks run in
    // that order within a frame — the offset is asked for while the parent is
    // placing the element, and `onPlaced` only answers afterwards — so on the
    // very first frame there is no position to have travelled from. Seeding
    // the animation with the zero that stands in for "not placed yet" is what
    // made a name arrive clumped in its own top-left corner and spread out
    // from there every time a screen was opened.
    var target by remember { mutableStateOf<IntOffset?>(null) }
    var travel by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    return this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            // Before that first answer, no offset at all: the element is drawn
            // exactly where the parent put it, which is where it belongs.
            val goal = target ?: return@offset IntOffset.Zero
            // Seeded at the first real position, so an element that has only
            // just appeared has nowhere to travel from and does not move.
            val moving = travel ?: Animatable(goal, IntOffset.VectorConverter).also { travel = it }
            if (moving.targetValue != goal) {
                scope.launch { moving.animateTo(goal, TitleReflowSpring) }
            }
            moving.value - goal
        }
}

/**
 * The width of one space in [style], which is what a name's words have to be
 * set apart by once they are laid out one at a time.
 *
 * Measured as the difference two letters make with and without a space between
 * them rather than by measuring `" "` on its own: a lone space is trailing
 * whitespace, and text measurement is entitled to give trailing whitespace no
 * width at all.
 */
@Composable
private fun rememberSpaceWidth(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, density) {
        val apart = measurer.measure("x x", style, maxLines = 1).size.width
        val together = measurer.measure("xx", style, maxLines = 1).size.width
        with(density) { (apart - together).coerceAtLeast(0).toDp() }
    }
}

/**
 * A row's name, drawn so that it re-wraps by moving rather than by jumping.
 *
 * A name shares its line with the row's furniture — the reset control, the
 * "?", a switch — and that furniture changes width while the row is being
 * used. Ordinary text answers a narrower line by re-breaking it, which is a
 * single frame in which a word is in one place and the next frame in another.
 * The width it is answering moves smoothly; only the answer jumps.
 *
 * So the words are laid out one at a time in a `FlowRow` — which breaks lines
 * exactly where `Text` would — and each one animates from its old place to its
 * new one. A word pushed onto a second line travels down and back to the left
 * instead of being redrawn there, and the block's own height follows it, so
 * the rows below slide down rather than snapping.
 *
 * Falls back to a plain [Text] when there is nothing to animate:
 *
 * - **Reduce motion** is on, and the point of the whole thing is the motion.
 * - The name has no spaces in it, which is one word in a script that writes
 *   them separately and a whole sentence in one that does not. Chinese,
 *   Japanese, Thai, Lao, Khmer and Burmese break lines between characters, not
 *   between words, and cutting those on spaces would produce one enormous
 *   "word" that never re-wraps at all. They get today's behaviour rather than
 *   a wrong one.
 *
 * Screen readers get the whole name as one node, not one per word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReflowingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
) {
    val resolved = style ?: LocalTextStyle.current
    val words = remember(text) { text.split(' ').filter { it.isNotEmpty() } }
    if (LocalReduceMotion.current || words.size < 2) {
        Text(text, modifier = modifier, style = resolved)
        return
    }
    FlowRow(
        modifier = modifier
            .animateContentSize(animationSpec = TitleHeightSpring)
            .clearAndSetSemantics { this.text = AnnotatedString(text) },
        horizontalArrangement = Arrangement.spacedBy(rememberSpaceWidth(resolved)),
    ) {
        for (word in words) {
            // A word is never broken across lines by `Text` either, so it is
            // not allowed to wrap inside itself here.
            Text(word, style = resolved, softWrap = false, modifier = Modifier.animatePlacement())
        }
    }
}

// ---- reset to default ----

/**
 * The tap target of the reset control, and of the "?" and link buttons that
 * share [RowExtras] with it. Material's `IconButton` pads every one of them to
 * 48 dp, which put 30 dp of nothing between three 18 dp glyphs and pushed the
 * row's own name onto three lines. 24 dp is the floor the accessibility
 * guidance sets, and [RowExtras] stacks them two to a column, so each keeps
 * its full 24 dp square without the row growing taller than its switch.
 */
internal val RowExtraSize = 24.dp

/** The reset control's target, which is [RowExtraSize] like its neighbours. */
private val ResetTargetSize = RowExtraSize

/** The glyph inside that target. */
private val ResetGlyphSize = 18.dp

/**
 * How the control grows into its row and how it leaves again.
 *
 * A spring rather than a duration: the width it animates is the row's, and the
 * title beside it re-wraps against whatever that width is on each frame. A
 * spring lands on the final width without overshooting past it, so the title
 * settles once instead of re-wrapping back and forth at the end of the move.
 */
private val ResetSizeSpring: FiniteAnimationSpec<IntSize> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** The same spring, for the glyph's own fade and scale. */
private val ResetGlyphSpring: FiniteAnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

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
 *
 * The control takes no width while it is not drawn. It used to hold an empty
 * 36 dp slot open (#136) so that nothing shifted when the glyph appeared, but
 * that spends the width of a control on every resettable row of every screen
 * in order to avoid one frame of movement — and on a row whose title is
 * already wrapping, that gap is what pushed it onto a second line. Instead the
 * width itself animates: the control expands from nothing to its target while
 * the glyph fades and scales up inside it, so the switch, the readout or the
 * value beside it slides to its new place rather than jumping there. Going
 * away is the same move backwards.
 *
 * The animation runs on a change, not on arrival: `AnimatedVisibility` does
 * not animate its first composition, so opening a screen that already has ten
 * changed rows draws ten reset controls, not ten of them growing at once.
 *
 * [possible] is false on a row that could never reset — one with no shipped
 * default — and such a row composes nothing at all.
 */
@Composable
internal fun ResetSetting(
    name: String,
    changed: Boolean,
    possible: Boolean = true,
    onReset: () -> Unit,
) {
    if (!possible) return
    val still = LocalReduceMotion.current
    AnimatedVisibility(
        visible = changed,
        // Anchored at the end: the control grows out of the edge it shares
        // with the switch or the value, which is the direction the row's other
        // furniture is being pushed in anyway.
        enter = if (still) {
            EnterTransition.None
        } else {
            expandHorizontally(ResetSizeSpring, Alignment.End) +
                fadeIn(ResetGlyphSpring) +
                scaleIn(ResetGlyphSpring, initialScale = 0.7f)
        },
        exit = if (still) {
            ExitTransition.None
        } else {
            shrinkHorizontally(ResetSizeSpring, Alignment.End) +
                fadeOut(ResetGlyphSpring) +
                scaleOut(ResetGlyphSpring, targetScale = 0.7f)
        },
    ) {
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
}

/**
 * The small controls at the end of a row's name: its "?", its link and its
 * reset. They are the row's furniture, and were each a full `IconButton` side
 * by side, which spent 144 dp of a phone's width on three glyphs.
 *
 * Here the 48 dp padding Material adds around each is dropped, and with
 * [stack] they sit two to a column, so three of them take 48 dp across and
 * no more height than the switch beside them. A row whose controls sit on a
 * line of their own above a slider or a segmented choice passes `stack =
 * false`: there the width is free and a second line of height is not.
 *
 * Only the children that draw anything are laid out, so a reset that is not
 * showing leaves no hole in the stack. Callers put the reset last: it is the
 * one that comes and goes, and last it appears in the free slot nearest the
 * switch instead of shuffling the "?" and the link into new places.
 *
 * At the Simple depth a row is its name and its control (SETTINGS_REDESIGN.md,
 * section 5): the "?" and the link button compose nothing under
 * [LocalRowExtrasBare], and the row's long press offers what they did, as
 * "What is this?" and the copied link (see `RowHoldMenu`). The reset stays,
 * since it is only there on a row that was changed.
 */
@Composable
internal fun RowExtras(stack: Boolean = true, content: @Composable () -> Unit) {
    val bare = LocalSettingsDepth.current.depth == SettingsDepth.SIMPLE
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides 0.dp,
        LocalRowExtrasBare provides bare,
    ) {
        Layout(content) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val all = measurables.map { it.measure(loose) }
            val shown = all.filter { it.width > 0 && it.height > 0 }
            val columns = shown.chunked(if (stack) ROW_EXTRAS_PER_COLUMN else 1)
            val widths = columns.map { column -> column.maxOf { it.width } }
            val height = columns.maxOfOrNull { column -> column.sumOf { it.height } } ?: 0
            layout(widths.sum(), height) {
                var x = 0
                columns.forEachIndexed { index, column ->
                    var y = (height - column.sumOf { it.height }) / 2
                    for (placeable in column) {
                        placeable.placeRelative(x + (widths[index] - placeable.width) / 2, y)
                        y += placeable.height
                    }
                    x += widths[index]
                }
                // The empty ones still need placing, for the animation that
                // brings a reset back to have somewhere to start from.
                all.filter { it.width == 0 || it.height == 0 }.forEach { it.place(0, 0) }
            }
        }
    }
}

private const val ROW_EXTRAS_PER_COLUMN = 2

/**
 * True inside a [RowExtras] at the Simple depth, where a row's "?" and link
 * button are not drawn: `InfoButton` and `SettingLinkButton` read it and
 * compose nothing. A heading's controls sit outside any RowExtras and keep
 * theirs.
 */
internal val LocalRowExtrasBare = compositionLocalOf { false }

/** Whether a row's "?" is drawn on the row at this depth, for the rows that budget their own width. */
@Composable
internal fun rowInfoButtonShown(): Boolean = LocalSettingsDepth.current.depth != SettingsDepth.SIMPLE

/** The width [RowExtras] takes for [count] controls, stacked, for the rows that budget their own width. */
internal fun rowExtrasWidth(count: Int): Dp =
    RowExtraSize * ((count + ROW_EXTRAS_PER_COLUMN - 1) / ROW_EXTRAS_PER_COLUMN)

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
    HighlightableRow(title, highlightKey, info = info) {
        WmRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            trailingOnTitleLine = true,
            titleReflows = true,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowExtras {
                        if (info != null) InfoButton(title, info)
                        SettingLinkButton(title)
                        ResetSetting(title, color != null) { onChange(null) }
                    }
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

/**
 * Bar geometry. The collapsed strip is Material's. The expanded bar is
 * Material's medium bar on every page below Home: a heading is one line and a
 * tile, and the 48 dp under the strip hold that; the 40 dp the large bar
 * added were air between the heading and the first row, and with the path
 * strip under it a second-level page spent 208 dp before its first setting
 * (SETTINGS_REDESIGN.md, section 5). Home keeps the large bar, since it has a
 * badge above its heading to make room for.
 */
private val CollapsedBarHeight = 64.dp
private val ExpandedBarHeight = 112.dp
private val BadgedBarHeight = 152.dp

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

/**
 * The most lines a subtitle may wrap to. Three lines of the body face still fit
 * the box the heading lays its parts out in ([CollapsedBarHeight]), which is
 * what keeps the arithmetic above one measurement rather than a layout pass.
 */
private const val SubtitleLineLimit = 3

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
 * trades size for room. The bands were fitted by measuring every screen title
 * in Manrope Bold against the 276 dp a 360 dp phone leaves beside a heading
 * tile: the widest title of each length sets where its band ends, so
 * "Phone number formats" (20) takes 24sp, "Image sizes and templates" (25)
 * 21sp and "One-handed, split and floating" (30) 19sp, each just inside the
 * width. Longer than 25 was 21sp before and overflowed; 19sp is the smallest
 * step, since a heading smaller than that reads as a row.
 */
private fun expandedTitleSp(title: String): Float = when {
    title.length <= 14 -> 33f
    title.length <= 18 -> 28f
    title.length <= 22 -> 24f
    title.length <= 25 -> 21f
    else -> 19f
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
            // The bar's own height, and taller when the content is: a subtitle
            // that wrapped must not be measured against a box one line deep,
            // or it would come back clipped to the height that made it wrap.
            // The caller works [drop] out against the same rule.
            .heightIn(min = CollapsedBarHeight)
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
 * fading out with the rest of the heading. [share] is how much of it
 * "Icons in settings" leaves showing — see [IconReveal].
 */
@Composable
private fun BoxScope.HeadingIcon(
    pose: HeadingPose,
    keepIcon: Boolean,
    startX: Float,
    endX: Float,
    accent: Color,
    tile: Boolean,
    share: Float,
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
            }
            // Leaving with "Icons in settings", towards the edge it sits on.
            .graphicsLayer {
                alpha = share
                scaleX = share
                scaleY = share
                transformOrigin = TransformOrigin(0f, 0.5f)
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
 *
 * [subtitleMaxLines] lets a subtitle wrap rather than ellipsise: the bar grows
 * a line at a time under the title to hold it, up to [SubtitleLineLimit]. A
 * subtitle that rides into the collapsed strip stays one line regardless,
 * because the strip has room for one.
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
    subtitleMaxLines: Int = 1,
    badge: (@Composable () -> Unit)? = null,
    badgeInBar: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val collapsedPx = with(density) { CollapsedBarHeight.toPx() }
    // A subtitle that stays through the collapse has to fit the strip, so it
    // is one line whatever the caller asked for.
    val subtitleLines = if (subtitleInBar) 1 else subtitleMaxLines.coerceIn(1, SubtitleLineLimit)
    // How tall the subtitle actually came out, and how much of that is its
    // first line, both measured as it draws. The band below the title grows by
    // the difference, so a description that needs two lines gets a bar two
    // lines deep instead of an ellipsis. Measured rather than guessed from the
    // line count because the font scale decides what a line is worth.
    var subtitleHeightPx by remember(subtitle, subtitleLines) { mutableIntStateOf(0) }
    var subtitleFirstLinePx by remember(subtitle, subtitleLines) { mutableIntStateOf(0) }
    val subtitleExtraPx = (subtitleHeightPx - subtitleFirstLinePx).coerceAtLeast(0)
    val subtitleExtra = with(density) { subtitleExtraPx.toDp() }
    // The box the subtitle is laid out in is the bar's own height until the
    // text is taller than that, which only a large font scale reaches.
    val subtitleBoxPx = maxOf(collapsedPx, subtitleHeightPx.toFloat())
    // The subtitle's band is added below the title and taken off its distance
    // from the bottom, so the title itself does not move. A badge sits above
    // instead, so its band is pure extra height: the bar grows downward from a
    // fixed top, which is what makes room over the heading.
    val barHeight = (if (badge != null) BadgedBarHeight else ExpandedBarHeight) +
        (if (subtitle != null) SubtitleBand + subtitleExtra else 0.dp) +
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
    // A tiled heading icon is the screen's own copy of its home row's tile, so
    // it goes with the rows' tiles when "Icons in settings" is off, and the
    // title slides back over the lane it leaves. A bare glyph names the page's
    // subject — a tool, an addon, a mode — and stays, like the list it flew from.
    // Read here rather than in a lambda: it only moves while the switch does.
    val iconShare = when {
        headerIcon == null -> 0f
        iconTile -> LocalIconReveal.current.progress.value
        else -> 1f
    }
    val keepIcon = iconShare > 0f && iconInBar

    val expandedSp = expandedTitleSp(title)
    // A title that started below the collapsed size stays its size: growing
    // into the strip would read as the bar pushing the heading about.
    val collapsedScale = minOf(CollapsedTitleSp, expandedSp) / expandedSp
    // Where the expanded title's left edge sits: past the heading icon when
    // there is one, at the plain inset otherwise.
    val iconSize = if (iconTile) IconTileSize else HeaderBareIconSize
    val startXDp = TitleInset + (iconSize + HeaderIconGap) * iconShare
    val startX = with(density) { startXDp.toPx() }
    // And where it settles: past the navigation icon, and past the heading icon
    // as well when that one is staying.
    val barIconLane = iconSize * BarIconScale + 8.dp
    val endXDp = (if (onBack != null) TitleInsetWithNav else TitleInset) +
        (if (keepIcon) barIconLane * iconShare else 0.dp) +
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
                    (if (subtitle != null) SubtitleBand + subtitleExtra else 0.dp) +
                    (if (badge != null) BadgeBand else 0.dp)
                ).toPx()
        } -
        collapsedPx / 2f
    // Far enough below the title's centre line to clear its descenders: half a
    // line of the title, then the subtitle's own half-line and a gap. The
    // subtitle is centred in its box, so the lines it grew by push it down by
    // half of their height to keep its first line where one line sat — less
    // half of whatever the box itself grew by, which moves the centre already.
    val titleHalfPx = with(density) { (expandedSp * 1.25f / 2f).dp.toPx() }
    val subtitleDropPx = titleHalfPx + with(density) { 13.dp.toPx() } +
        subtitleExtraPx / 2f - (subtitleBoxPx - collapsedPx) / 2f
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
        if (headerIcon != null && iconShare > 0f) {
            HeadingIcon(pose, keepIcon, iconStartX, iconEndX, headerAccent, iconTile, iconShare, headerIcon)
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
                        maxLines = subtitleLines,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            val firstLine = (result.getLineBottom(0) - result.getLineTop(0))
                                .roundToInt()
                            if (firstLine != subtitleFirstLinePx) subtitleFirstLinePx = firstLine
                            val height = result.size.height
                            if (height != subtitleHeightPx) subtitleHeightPx = height
                        },
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
    subtitleMaxLines: Int = 1,
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
        subtitleMaxLines = subtitleMaxLines,
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
        val slots = LocalScreenSlots.current
        SideEffect { slots?.toTop = { scrollState.animateScrollTo(0) } }
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
        CompositionLocalProvider(LocalScreenReveal provides reveal, LocalScreenScroll provides scrollState) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .nestedScroll(scrollLock)
                    .verticalScroll(scrollState)
                    .holdScrolledPlace(scrollState),
                verticalArrangement = Arrangement.Top,
            ) {
                // The same air under the bar whatever comes first. A group
                // heading brings a little of its own; a bare card brings none,
                // and used to sit hard against the strip.
                Spacer(Modifier.height(ScreenTopGap))
                content()
                // Room for a FAB to float over the last row rather than on it:
                // the button, its margin, and a little air. The keyboard test
                // button stacks above a screen's own, and needs its own room.
                val hasFab = fab != null || slots?.fab != null
                val hasPreview = LocalKeyboardPreview.current != null && slots?.dock == null
                val hasChat = LocalAiChatFab.current != null && LocalScreenRoute.current !in NoChatFabRoutes
                // Each small button stacked above the bottom one adds a step.
                val buttons = listOf(hasFab, hasPreview, hasChat).count { it }
                Spacer(
                    Modifier.height(
                        if (buttons == 0) 24.dp else FAB_TAIL + PREVIEW_FAB_STEP * (buttons - 1),
                    ),
                )
            }
        }
    }
}

/**
 * Keeps a scrolled page at least as tall as the part of it already scrolled
 * past plus the screen (#665).
 *
 * A row closing at the foot of a page scrolled to its end made the page
 * shorter than where the reader stood, the scroll offset was clamped, and
 * every card above was dragged down a row's height in a few frames, which
 * read as the whole list flashing. Held this way the rows above stay where
 * they are and the room the row left stays as empty space below, which goes
 * as soon as the reader scrolls up past it. Read in layout only, so a scroll
 * costs this node a relayout and recomposes nothing.
 */
private fun Modifier.holdScrolledPlace(scroll: ScrollState): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val floor = scroll.value + scroll.viewportSize
    layout(placeable.width, maxOf(placeable.height, floor)) { placeable.place(0, 0) }
}

/** Height a scrolling body leaves free under a floating action button. */
private val FAB_TAIL = 88.dp

/** What the keyboard test button adds above a screen's own: its size and the gap. */
private val PREVIEW_FAB_STEP = 56.dp

/** Gap between the keyboard test button and the screen's own button under it. */
private val PREVIEW_FAB_GAP = 16.dp

/** Air between the bar (or the path strip under it) and a screen's first row. */
private val ScreenTopGap = 12.dp

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
    subtitleMaxLines: Int = 1,
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
    val crumbOpened = rememberCrumbOpened()
    RegisterSettingsCrumb(crumbTitle ?: title, route)
    val slots = remember { ScreenSlots() }
    val preview = LocalKeyboardPreview.current?.takeIf { slots.dock == null }
    val chatFab = LocalAiChatFab.current
    val scope = rememberCoroutineScope()
    // One per screen, published for everything it draws — see [SettingsSnackbar].
    val snackbarHost = remember { SnackbarHostState() }
    val snackbar = remember(snackbarHost, scope) { SettingsSnackbar(snackbarHost, scope) }
    // The path strip's last pill: the body scrolls to its top, and the bar
    // opens back up. The scroll alone would leave the bar collapsed when the
    // body is shorter than the distance the bar folds over, so the bar is
    // opened by hand after it.
    val toTop: () -> Unit = {
        scope.launch {
            slots.toTop?.invoke()
            animate(scrollBehavior.state.heightOffset, 0f) { value, _ ->
                scrollBehavior.state.heightOffset = value
            }
        }
    }
    // The destination's own animation scope, published for everything the
    // screen draws — the heading above, and any row below that flies somewhere.
    CompositionLocalProvider(
        LocalNavAnimatedScope provides (anim ?: LocalNavAnimatedScope.current),
        LocalScreenRoute provides route,
        LocalFlightOrigin provides origin,
        LocalScreenSlots provides slots,
        LocalSettingsSnackbar provides snackbar,
    ) {
        // A settings row is a name on the left and a control on the right, and
        // on a window wider than a phone the two end up a hand's width apart
        // with nothing in between — the eye loses the line between the setting
        // and the switch that belongs to it. The whole frame is capped rather
        // than the body alone, so the heading, the path strip and the rows stay
        // on one left edge instead of the title hanging off the window while
        // the rows sit in the middle. The margins are painted in the same
        // colour the Scaffold paints itself, so there is no seam to see.
        //
        // Inert in the two-pane layout, where the detail pane is already
        // narrower than this — see [SettingsTwoPane].
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            Scaffold(
                modifier = Modifier
                    .widthIn(max = ScreenContentMaxWidth)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
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
                            subtitleMaxLines = subtitleMaxLines,
                            badge = badge,
                            badgeInBar = badgeInBar,
                            actions = {
                                // Ahead of the screen's own, so an overflow
                                // menu keeps the bar's last place (#898).
                                FavouritesButton(crumbTitle ?: title)
                                actions()
                            },
                        )
                        // Below the bar rather than inside it: the bar measures
                        // itself to the collapse, and a second line in there would
                        // have to be written into that arithmetic. The strip draws
                        // nothing on the home list, which pays an empty layout
                        // node for it and no height.
                        if (trail != null && entry != null) {
                            SettingsBreadcrumbBar(
                                trail = trail,
                                entryId = entry.id,
                                currentTitle = crumbTitle ?: title,
                                currentRoute = route,
                                opened = crumbOpened,
                                onCurrent = toTop,
                                // The heading's own colour, so the pill and the
                                // tile above it are visibly the same section.
                                accent = accent ?: routeAccent(route.orEmpty()),
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
                floatingActionButton = {
                    val own = fab ?: slots.fab
                    val chat = chatFab?.takeIf { route !in NoChatFabRoutes }
                    // The keyboard test (#412) rides above the screen's own
                    // button rather than in its place. Not on a screen that
                    // docks a keyboard of its own: two keyboards, one of them
                    // the real one, would be a test of nothing. The everywhere
                    // chat button sits between them, small when the screen
                    // has a button of its own and full size when it has not.
                    if (preview != null || (chat != null && own != null)) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(PREVIEW_FAB_GAP),
                        ) {
                            if (preview != null) KeyboardPreviewFab(preview)
                            if (chat != null) AiChatFab(chat, small = own != null)
                            own?.invoke()
                        }
                    } else if (chat != null) {
                        AiChatFab(chat)
                    } else {
                        own?.invoke()
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHost) },
                bottomBar = {
                    if (preview != null && preview.open) KeyboardPreviewBar(preview) else slots.dock?.invoke()
                },
                content = { padding ->
                    // Always wrapped, whether or not the screen has a refresh: the
                    // slot is filled by the content composing, so branching on it
                    // here would rebuild the whole screen one frame in.
                    val refresh = slots.refresh
                    val pullState = rememberPullToRefreshState()
                    Box(
                        modifier = Modifier.pullToRefresh(
                            isRefreshing = refresh?.refreshing == true,
                            state = pullState,
                            enabled = refresh != null,
                            onRefresh = { slots.refresh?.onRefresh?.invoke() },
                        ),
                    ) {
                        content(padding)
                        // Under the bar rather than at the top of the window, or a
                        // collapsing title lands on top of the spinner.
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = refresh?.refreshing == true,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = padding.calculateTopPadding()),
                        )
                    }
                },
            )
        }
    }
}

/**
 * How wide a settings screen's own column is allowed to get.
 *
 * Roughly two phones side by side. Past that a row's name and its control stop
 * reading as one thing, and a paragraph of subtitle stops being a line and
 * starts being a column of text. Windows wider than this get the two-pane
 * layout instead, which fills them with a second screen rather than with air.
 */
private val ScreenContentMaxWidth = 720.dp

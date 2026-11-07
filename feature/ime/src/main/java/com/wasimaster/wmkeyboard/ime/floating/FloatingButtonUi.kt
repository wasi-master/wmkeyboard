package com.wasimaster.wmkeyboard.ime.floating

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Block
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardHide
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridView
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notifications
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ToggleOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewCarousel
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.FloatingAction
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonShape
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonStyle
import com.wasimaster.wmkeyboard.core.settings.FloatingCategory
import com.wasimaster.wmkeyboard.core.settings.FloatingMenuAnimation
import com.wasimaster.wmkeyboard.core.settings.SystemButton
import com.wasimaster.wmkeyboard.core.settings.categoryById
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.IconDefaults
import com.wasimaster.wmkeyboard.ime.ui.ModeIcons
import com.wasimaster.wmkeyboard.ime.ui.toolLabelRes
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the floating button (#755) and its quick menu look like. Public because
 * the settings page draws its preview from these same pieces, so the preview
 * cannot drift from the button it previews.
 */
@Immutable
data class FloatingPalette(
    val container: Color,
    val content: Color,
    val outline: Color,
    /** The ring round a slot the finger is on, and the accent style's fill. */
    val accent: Color,
)

@Composable
fun floatingPalette(style: FloatingButtonStyle): FloatingPalette {
    val dark = isSystemInDarkTheme()
    val accent = systemAccent()
    val darkFace = FloatingPalette(Color(0xF0202124), Color.White, Color(0x33FFFFFF), accent)
    val lightFace = FloatingPalette(Color(0xF7FFFFFF), Color(0xFF1F1F1F), Color(0x1F000000), accent)
    return when (style) {
        FloatingButtonStyle.AUTO -> if (dark) darkFace.copy(container = Color(0xF02E3036)) else lightFace
        FloatingButtonStyle.DARK -> darkFace
        FloatingButtonStyle.LIGHT -> lightFace
        FloatingButtonStyle.ACCENT -> FloatingPalette(accent.copy(alpha = 0.96f), Color.White, Color(0x33FFFFFF), accent)
    }
}

/** The system's accent from Android 12, a calm indigo before it. */
@Composable
private fun systemAccent(): Color {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Color(context.getColor(android.R.color.system_accent1_600))
        } else {
            Color(0xFF3F51B5)
        }
    }
}

/**
 * The glyph an action is drawn with; the keyboard's swaps while the keyboard
 * is up. A category wears the icon it was given (#1138), out of [categories],
 * or the folder.
 */
fun floatingActionIcon(
    action: FloatingAction,
    keyboardUp: Boolean = false,
    categories: List<FloatingCategory> = emptyList(),
): ImageVector = when (action) {
    FloatingAction.None -> Icons.Outlined.Block
    FloatingAction.Keyboard -> if (keyboardUp) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard
    FloatingAction.AskAi -> Icons.Outlined.AutoAwesome
    FloatingAction.LookUp -> com.wasimaster.wmkeyboard.core.icons.SymbolIcons.Dictionary
    FloatingAction.Menu -> Icons.Outlined.Apps
    FloatingAction.Hide -> Icons.Outlined.VisibilityOff
    is FloatingAction.Tool -> IconDefaults.forTool(action.tool)
    // Drawn with the app's own icon where it can be; this is for an app that is gone.
    is FloatingAction.App -> Icons.Outlined.OpenInNew
    is FloatingAction.AppPair -> Icons.Outlined.VerticalSplit
    is FloatingAction.Applet -> IconDefaults.forTool(action.tool)
    is FloatingAction.System -> systemButtonIcon(action.button)
    is FloatingAction.Category -> ModeIcons.iconOrNull(categories.categoryById(action.id)?.icon) ?: Icons.Outlined.Folder
}

/** The glyph of one of Android's system buttons (#1170). */
fun systemButtonIcon(button: SystemButton): ImageVector = when (button) {
    SystemButton.BACK -> Icons.AutoMirrored.Outlined.ArrowBack
    SystemButton.RECENTS -> Icons.Outlined.ViewCarousel
    SystemButton.APP_DRAWER -> Icons.Outlined.GridView
    SystemButton.NOTIFICATIONS -> Icons.Outlined.Notifications
    SystemButton.QUICK_SETTINGS -> Icons.Outlined.ToggleOn
}

/** What one of Android's system buttons is called (#1170). */
fun systemButtonLabelRes(button: SystemButton): Int = when (button) {
    SystemButton.BACK -> R.string.ime_floating_system_back
    SystemButton.RECENTS -> R.string.ime_floating_system_recents
    SystemButton.APP_DRAWER -> R.string.ime_floating_system_app_drawer
    SystemButton.NOTIFICATIONS -> R.string.ime_floating_system_notifications
    SystemButton.QUICK_SETTINGS -> R.string.ime_floating_system_quick_settings
}

/**
 * What an action is called, on the button, in its menu and on the settings
 * page: an app by its own name (#922), a category by the one it was given
 * (#923), the rest by their fixed ones.
 */
@Composable
fun floatingActionLabel(
    action: FloatingAction,
    keyboardUp: Boolean = false,
    categories: List<FloatingCategory> = emptyList(),
): String {
    val context = LocalContext.current
    return remember(action, keyboardUp, categories, context) {
        floatingActionName(context, action, keyboardUp, categories)
    }
}

/** [floatingActionLabel] outside a composition. */
fun floatingActionName(
    context: Context,
    action: FloatingAction,
    keyboardUp: Boolean,
    categories: List<FloatingCategory>,
): String = when (action) {
    is FloatingAction.App -> FloatingAppIcons.label(context, action)
    // Told apart from the same tool opened in the keyboard (#1155).
    is FloatingAction.Applet -> context.getString(
        R.string.ime_floating_applet_name,
        context.getString(toolLabelRes(action.tool)),
    )
    // Both names, so a pair reads as what it opens (#1140).
    is FloatingAction.AppPair -> context.getString(
        R.string.ime_floating_pair_name,
        FloatingAppIcons.label(context, action.first) ?: action.first,
        FloatingAppIcons.label(context, action.second) ?: action.second,
    )
    is FloatingAction.Category -> categories.categoryById(action.id)?.name?.takeIf { it.isNotBlank() }
    else -> null
} ?: context.getString(floatingActionLabelRes(action, keyboardUp))

internal fun floatingActionLabelRes(action: FloatingAction, keyboardUp: Boolean): Int = when (action) {
    FloatingAction.None -> R.string.ime_floating_none_label
    FloatingAction.Keyboard -> if (keyboardUp) R.string.ime_summon_button_hide_label else R.string.ime_summon_button_label
    FloatingAction.AskAi -> R.string.ime_ai_button_label
    FloatingAction.LookUp -> R.string.ime_floating_lookup_label
    FloatingAction.Menu -> R.string.ime_floating_menu_label
    FloatingAction.Hide -> R.string.ime_floating_hide_label
    is FloatingAction.Tool -> toolLabelRes(action.tool)
    is FloatingAction.App -> R.string.ime_floating_app_label
    is FloatingAction.AppPair -> R.string.ime_floating_pair_label
    is FloatingAction.Applet -> toolLabelRes(action.tool)
    is FloatingAction.System -> systemButtonLabelRes(action.button)
    is FloatingAction.Category -> R.string.ime_floating_category_label
}

/**
 * The glyph or the picture inside a disc: an app's own icon (#922), which
 * fills most of the disc as a launcher's does, or a tinted glyph.
 */
@Composable
private fun FloatingGlyph(icon: ImageVector, image: ImageBitmap?, tint: Color, sizeDp: Float) {
    if (image != null) {
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size((sizeDp * APP_ICON_SCALE / ICON_SCALE).dp),
        )
    } else {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(sizeDp.dp))
    }
}

/** A glyph is drawn at [ICON_SCALE] of a round disc; an app's icon a little larger, as on a launcher. */
private const val ICON_SCALE = 0.46f
private const val APP_ICON_SCALE = 0.66f

/**
 * The button itself: a disc with a glyph, a soft shadow under it, and a press
 * that sinks it a little. [sizeDp] is the disc; the caller leaves room round it
 * for the shadow ([FLOATING_SHADOW_ROOM_DP] each side). [shape] is its outline
 * (#933), fitted to the same square; [image], an app's icon in place of the
 * glyph (#922).
 */
@Composable
fun FloatingButtonFace(
    icon: ImageVector,
    style: FloatingButtonStyle,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    contentDescription: String? = null,
    shape: FloatingButtonShape = FloatingButtonShape.CIRCLE,
    image: ImageBitmap? = null,
) {
    val palette = floatingPalette(style)
    val outline = remember(shape) { floatingShape(shape) }
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "floating-press",
    )
    Box(
        modifier
            .size(sizeDp.dp)
            .scale(scale)
            .shadow(if (pressed) 2.dp else 6.dp, outline)
            .background(palette.container, outline)
            .border(1.dp, palette.outline, outline)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        FloatingGlyph(icon, image, palette.content, sizeDp * floatingIconScale(shape))
    }
}

/**
 * The button as a thin pill against the screen's edge (#1137): [thicknessDp]
 * across and [lengthDp] tall, flat against the left edge with [onLeft] and the
 * right without. The caller lays it in a window as tall as the pill and as wide
 * as its touch area. Auto takes the colour of the system's own gesture handle,
 * dark over a light theme and light over a dark one, where a disc's light
 * face would vanish against most apps; the other styles keep their own.
 */
@Composable
fun FloatingEdgePill(
    style: FloatingButtonStyle,
    thicknessDp: Int,
    lengthDp: Int,
    onLeft: Boolean,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
) {
    val palette = floatingPalette(style)
    val fill = when (style) {
        FloatingButtonStyle.AUTO -> palette.content
        FloatingButtonStyle.ACCENT -> palette.accent
        FloatingButtonStyle.DARK, FloatingButtonStyle.LIGHT -> palette.container
    }
    val grow by animateFloatAsState(
        if (pressed) 1.25f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "floating-pill-press",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .size(thicknessDp.dp, lengthDp.dp)
            .graphicsLayer {
                // Grows away from the edge it sits on, never off the screen.
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (onLeft) 0f else 1f, 0.5f)
                scaleX = grow
            }
            .shadow(2.dp, shape)
            .background(fill, shape)
            .border(0.5.dp, palette.outline, shape),
    )
}

/** Room left round the disc for its shadow and its press, in dp. */
const val FLOATING_SHADOW_ROOM_DP = 8

/** A quick menu slot's disc, in dp. */
const val FLOATING_MENU_ITEM_DP = 52

/** How wide a slot's name may run under it, in dp. */
const val FLOATING_MENU_LABEL_DP = 84

/**
 * One quick menu slot: its disc, ringed while [hovered], and with [label] its
 * name under it. In the button's [shape] (#933), with an app's [image] in
 * place of the glyph (#922).
 */
@Composable
fun FloatingMenuItem(
    icon: ImageVector,
    label: String,
    style: FloatingButtonStyle,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
    hovered: Boolean = false,
    shape: FloatingButtonShape = FloatingButtonShape.CIRCLE,
    image: ImageBitmap? = null,
) {
    val palette = floatingPalette(style)
    val outline: Shape = remember(shape) { floatingShape(shape) }
    val grow by animateFloatAsState(
        if (hovered) 1.16f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 800f),
        label = "floating-hover",
    )
    Column(
        modifier.width(FLOATING_MENU_LABEL_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(FLOATING_MENU_ITEM_DP.dp)
                .scale(grow)
                .shadow(4.dp, outline)
                .background(palette.container, outline)
                .border(if (hovered) 2.5.dp else 1.dp, if (hovered) palette.accent else palette.outline, outline),
            contentAlignment = Alignment.Center,
        ) {
            FloatingGlyph(icon, image, palette.content, FLOATING_MENU_ITEM_DP * floatingIconScale(shape))
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = palette.content,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = if (hovered) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(palette.container, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The open quick menu, filling its window: a light scrim, the slots coming
 * out of the button as [FloatingMenuState.animation] has them (#923), and in
 * the button's place a close disc, or inside a category a back one.
 * [anchor] and the slots' [FloatingMenuState.centers] are in the window's own
 * pixels.
 */
@Composable
internal fun FloatingMenuContent(
    state: FloatingMenuState,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onClosed: () -> Unit,
) {
    val density = LocalDensity.current
    val opening = state.open
    // The speed set (#1138): every time over it, every spring that much stiffer
    // squared, which a spring's settling time goes as the inverse root of.
    val speed = state.speed.coerceAtLeast(0.1f)
    fun ms(base: Int) = (base / speed).roundToInt().coerceAtLeast(1)
    fun stiff(base: Float) = base * speed * speed
    val scrim = remember { Animatable(0f) }
    // The window composes before it is told to open; only a menu that opened
    // can close, or the first frame would take the window straight back down.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(opening) {
        if (opening) {
            wasOpen = true
            scrim.animateTo(1f, tween(ms(160)))
        } else if (wasOpen) {
            scrim.animateTo(0f, tween(ms(140)))
            onClosed()
        }
    }
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = scrim.value }
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose),
    ) {
        val origin = state.windowOrigin
        val anchor = state.anchor - origin
        val animation = state.animation
        state.items.forEachIndexed { index, item ->
            val travel = remember(item.key) { Animatable(0f) }
            LaunchedEffect(opening, item.key) {
                if (opening) {
                    when (animation) {
                        FloatingMenuAnimation.NONE -> travel.snapTo(1f)
                        FloatingMenuAnimation.FADE -> travel.animateTo(1f, tween(ms(180)))
                        FloatingMenuAnimation.SWEEP -> travel.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = stiff(260f)))
                        FloatingMenuAnimation.POP -> {
                            kotlinx.coroutines.delay(ms(index * 34).toLong())
                            travel.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = stiff(520f)))
                        }
                        FloatingMenuAnimation.SPRING -> {
                            kotlinx.coroutines.delay(ms(index * 22).toLong())
                            travel.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = stiff(420f)))
                        }
                    }
                } else if (animation == FloatingMenuAnimation.NONE) {
                    travel.snapTo(0f)
                } else {
                    travel.animateTo(0f, tween(ms(120)))
                }
            }
            val target = state.centers.getOrNull(index)?.minus(origin) ?: anchor
            val labelHalf = with(density) { (FLOATING_MENU_LABEL_DP.dp / 2).toPx() }
            val itemHalf = with(density) { (FLOATING_MENU_ITEM_DP.dp / 2).toPx() }
            FloatingMenuItem(
                icon = item.icon,
                image = item.image,
                label = item.label,
                style = state.style,
                shape = state.shape,
                showLabel = state.labels,
                hovered = state.hovered == index,
                modifier = Modifier
                    .offset {
                        val at = floatingSlotPosition(animation, anchor, target, travel.value)
                        IntOffset((at.x - labelHalf).roundToInt(), (at.y - itemHalf).roundToInt())
                    }
                    .graphicsLayer {
                        val p = travel.value.coerceIn(0f, 1f)
                        alpha = p
                        val s = floatingSlotScale(animation, travel.value)
                        scaleX = s
                        scaleY = s
                    }
                    .semantics { contentDescription = item.label }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onPick(index)
                    },
            )
        }
        // Without names under the slots, the one under the finger is named here.
        val hovered = state.items.getOrNull(state.hovered ?: -1)
        if (!state.labels && hovered != null) {
            val palette = floatingPalette(state.style)
            Text(
                hovered.label,
                color = palette.content,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .offset {
                        val gap = with(density) { (state.buttonSizeDp.dp / 2 + 22.dp).toPx() }
                        IntOffset((anchor.x - 60 * density.density).roundToInt(), (anchor.y - gap - 24 * density.density).roundToInt())
                    }
                    .width(120.dp)
                    .wrapContentSize()
                    .background(palette.container, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        val closeRotation by animateFloatAsState(if (opening) 0f else 90f, tween(ms(160)), label = "floating-close")
        // Inside a category the disc goes back a ring; the scrim still closes them all.
        val back = state.canGoBack
        FloatingButtonFace(
            icon = if (back) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Close,
            style = state.style,
            sizeDp = state.buttonSizeDp,
            shape = state.shape,
            contentDescription = stringResource(if (back) R.string.ime_floating_back_menu else R.string.ime_floating_close_menu),
            modifier = Modifier
                .offset {
                    val half = with(density) { (state.buttonSizeDp.dp / 2).toPx() }
                    IntOffset((anchor.x - half).roundToInt(), (anchor.y - half).roundToInt())
                }
                .graphicsLayer { rotationZ = if (back) 0f else closeRotation }
                .clickable(interactionSource = noRipple, indication = null, onClick = if (back) onBack else onClose),
        )
    }
}

/**
 * Where a slot is drawn [progress] of the way out (#923), from the button's
 * [anchor] to its [target]: along the straight line for [FloatingMenuAnimation.SPRING],
 * round the button on a widening circle for [FloatingMenuAnimation.SWEEP],
 * and at the target from the start for the rest, which grow or fade in place.
 */
internal fun floatingSlotPosition(
    animation: FloatingMenuAnimation,
    anchor: Offset,
    target: Offset,
    progress: Float,
): Offset = when (animation) {
    FloatingMenuAnimation.SPRING -> anchor + (target - anchor) * progress
    FloatingMenuAnimation.SWEEP -> {
        val radius = hypot(target.x - anchor.x, target.y - anchor.y)
        val angle = atan2(target.y - anchor.y, target.x - anchor.x)
        val left = 1f - progress
        val r = radius * (0.55f + 0.45f * progress)
        val a = angle - SWEEP_RADIANS * left
        Offset(anchor.x + r * cos(a), anchor.y + r * sin(a))
    }
    FloatingMenuAnimation.POP, FloatingMenuAnimation.FADE, FloatingMenuAnimation.NONE -> target
}

/** How large a slot is drawn [progress] of the way out; see [floatingSlotPosition]. */
internal fun floatingSlotScale(animation: FloatingMenuAnimation, progress: Float): Float = when (animation) {
    FloatingMenuAnimation.SPRING, FloatingMenuAnimation.SWEEP -> 0.4f + 0.6f * progress.coerceIn(0f, 1f)
    // Let the spring overshoot: the pop is the point.
    FloatingMenuAnimation.POP -> progress.coerceAtLeast(0f)
    FloatingMenuAnimation.FADE -> 0.92f + 0.08f * progress.coerceIn(0f, 1f)
    FloatingMenuAnimation.NONE -> 1f
}

/** How far round the button a swept slot starts, behind where it lands: 70°. */
private const val SWEEP_RADIANS = 1.22f

/**
 * The drop target a drag shows at the bottom of the screen: let go of the
 * button on it and the button is put away. Grows and fills once the button is
 * close enough to snap onto it.
 */
@Composable
internal fun FloatingDismissTarget(state: FloatingDismissState) {
    val shown by animateFloatAsState(if (state.shown) 1f else 0f, tween(150), label = "floating-target")
    val armed by animateFloatAsState(
        if (state.armed) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "floating-armed",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(60.dp)
                .graphicsLayer {
                    alpha = shown
                    val s = (0.6f + 0.4f * shown) * (1f + 0.25f * armed)
                    scaleX = s
                    scaleY = s
                }
                .shadow(6.dp, CircleShape)
                .background(Color(0xFF202124).copy(alpha = 0.92f), CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.35f + 0.65f * armed), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_floating_dismiss_target),
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * Look up waiting for its tap (#765): a light wash over the screen, so it is
 * plain that the next tap is not the app's, and a hint at the top saying what
 * it is for.
 */
@Composable
internal fun FloatingPickHint(label: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.08f)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 56.dp)
                .shadow(6.dp, RoundedCornerShape(50))
                .background(Color(0xFF202124).copy(alpha = 0.92f), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** What the open menu shows; written by [FloatingButton], read by [FloatingMenuContent]. */
internal class FloatingMenuState {
    var open by mutableStateOf(false)
    var items by mutableStateOf(emptyList<FloatingMenuSlot>())
    var centers by mutableStateOf(emptyList<Offset>())
    var anchor by mutableStateOf(Offset.Zero)

    /** Where the menu's window starts on screen, so screen points can be drawn in it. */
    var windowOrigin by mutableStateOf(Offset.Zero)
    var hovered by mutableStateOf<Int?>(null)
    var style by mutableStateOf(FloatingButtonStyle.AUTO)
    var shape by mutableStateOf(FloatingButtonShape.CIRCLE)
    var animation by mutableStateOf(FloatingMenuAnimation.SPRING)

    /** How fast the rings move, as a multiple of their own pace (#1138). */
    var speed by mutableStateOf(1f)
    var labels by mutableStateOf(true)
    var buttonSizeDp by mutableStateOf(52)

    /** A category opened from the quick menu (#923): the disc in the middle goes back to it. */
    var canGoBack by mutableStateOf(false)
}

@Immutable
internal class FloatingMenuSlot(
    val key: String,
    val action: FloatingAction,
    val icon: ImageVector,
    val label: String,
    val image: ImageBitmap? = null,
)

internal class FloatingDismissState {
    var shown by mutableStateOf(false)
    var armed by mutableStateOf(false)
}

/** What the button draws; written by [FloatingButton]. */
internal class FloatingFaceState {
    var icon by mutableStateOf<ImageVector>(Icons.Outlined.Keyboard)
    var label by mutableStateOf("")
    var image by mutableStateOf<ImageBitmap?>(null)
    var style by mutableStateOf(FloatingButtonStyle.AUTO)
    var shape by mutableStateOf(FloatingButtonShape.CIRCLE)
    var sizeDp by mutableStateOf(52)
    var pressed by mutableStateOf(false)

    /** While a swipe from the fixed button points at a bound direction (#1171), that action's glyph. */
    var flickIcon by mutableStateOf<ImageVector?>(null)

    /** Drawn as the edge pill (#1137) rather than the disc. */
    var pill by mutableStateOf(false)
    var pillThicknessDp by mutableStateOf(6)
    var pillLengthDp by mutableStateOf(72)

    /** The side the pill sits against, which it flattens onto. */
    var pillOnLeft by mutableStateOf(false)
}

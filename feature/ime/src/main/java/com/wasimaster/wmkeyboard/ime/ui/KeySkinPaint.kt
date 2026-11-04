package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.wasimaster.wmkeyboard.core.theme.PathCommand
import com.wasimaster.wmkeyboard.core.theme.SkinColorRef
import com.wasimaster.wmkeyboard.core.theme.SkinFit
import com.wasimaster.wmkeyboard.core.theme.SkinLayer
import com.wasimaster.wmkeyboard.core.theme.SkinPaint
import com.wasimaster.wmkeyboard.core.theme.SkinSlot
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.SkinSlotName
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.theme.fitted
import com.wasimaster.wmkeyboard.core.theme.parseSvgPath

/** The theme colours a skin can name, gathered from a resolved theme or a stored one. */
internal data class SkinPalette(
    val key: Color,
    val keyPressed: Color,
    val keySpecial: Color,
    val keySpecialText: Color,
    val keyAccent: Color,
    val label: Color,
    val labelSecondary: Color,
    val accent: Color,
    val board: Color,
    val border: Color,
    val shadow: Color,
    val previewFill: Color,
    val previewLabel: Color,
    val stripIcon: Color,
)

internal fun KbTheme.skinPalette() = SkinPalette(
    key = key,
    keyPressed = pressedKey,
    keySpecial = modifierKey,
    keySpecialText = modifierKeyText,
    keyAccent = enterKey,
    label = keyText,
    labelSecondary = hintText ?: keyText.copy(alpha = 0.55f),
    accent = accent,
    board = board,
    border = keyBorder ?: lerp(key, keyText, 0.3f),
    shadow = keyShadow ?: Color.Black.copy(alpha = 0.45f),
    previewFill = popup,
    previewLabel = popupText,
    stripIcon = toolbarIcon,
)

internal fun ThemeSpec.skinPalette(): SkinPalette {
    fun c(v: Long) = Color(v.toInt())
    val key = c(keyBackground)
    val text = c(keyText)
    return SkinPalette(
        key = key,
        keyPressed = pressedKeyBackground?.let(::c) ?: lerp(key, text, 0.25f),
        keySpecial = c(modifierKeyBackground),
        keySpecialText = modifierKeyText?.let(::c) ?: text,
        keyAccent = c(enterKeyBackground),
        label = text,
        labelSecondary = hintText?.let(::c) ?: text.copy(alpha = 0.55f),
        accent = c(accent),
        board = c(boardBackground),
        border = keyBorderColor?.let(::c) ?: lerp(key, text, 0.3f),
        shadow = keyShadowColor?.let(::c) ?: Color.Black.copy(alpha = 0.45f),
        previewFill = popupBackground?.let(::c) ?: lerp(c(boardBackground), text, 0.2f),
        previewLabel = popupText?.let(::c) ?: text,
        stripIcon = toolbarIcon?.let(::c) ?: text.copy(alpha = 0.65f),
    )
}

/**
 * The colours a skin's references name, for one key. [face] is the key's own
 * fill and [faceLabel] its label, which is what lets one drawing serve the
 * letter keys, the modifiers and enter in their own colours.
 */
internal class SkinRoles(
    private val palette: SkinPalette,
    private val face: Color,
    private val faceLabel: Color,
) {
    fun resolve(name: String): Long? = when (name) {
        "face" -> face
        "faceLabel" -> faceLabel
        "key" -> palette.key
        "keyPressed" -> palette.keyPressed
        "keySpecial" -> palette.keySpecial
        "keySpecialPressed" -> lerp(palette.keySpecial, palette.keySpecialText, 0.25f)
        "keyAccent" -> palette.keyAccent
        "label" -> palette.label
        "labelSecondary" -> palette.labelSecondary
        "accent", "signal" -> palette.accent
        "board" -> palette.board
        "chassis" -> lerp(palette.board, palette.keySpecial, 0.5f)
        "border" -> palette.border
        "shadow" -> palette.shadow
        "previewFill" -> palette.previewFill
        "previewLabel" -> palette.previewLabel
        "stripIcon" -> palette.stripIcon
        else -> null
    }?.let { it.toArgb().toLong() and 0xFFFFFFFFL }

    fun color(ref: String): Color? =
        SkinColorRef.parse(ref).resolve(::resolve)?.let { Color(it.toInt()) }
}

/**
 * One skin slot fitted to one key: the paths are parsed and stretched here,
 * once per size, in the key's draw cache, and the colours resolved against the
 * key. The press path then picks between the rest and held transforms and
 * draws.
 */
internal class KeySkinDrawing private constructor(private val layers: List<Prepared>) {

    private class Prepared(
        val path: Path,
        val fill: Brush?,
        val stroke: Brush?,
        val strokeWidth: Float,
        val opacity: Float,
        val glow: Pair<Color, Float>?,
        val press: PressTransform?,
    )

    /** A layer's held pose, already in pixels. */
    private class PressTransform(
        val translate: Offset,
        val opacityRest: Float,
        val opacityDown: Float,
        val scaleRest: Float,
        val scaleDown: Float,
        val rotateRest: Float,
        val rotateDown: Float,
        val squash: Offset,
        val pivot: Offset,
    )

    fun draw(scope: DrawScope, down: Boolean) {
        for (layer in layers) scope.drawLayer(layer, down)
    }

    private fun DrawScope.drawLayer(layer: Prepared, down: Boolean) {
        val press = layer.press
        val alpha = layer.opacity * when {
            press == null -> 1f
            down -> press.opacityDown
            else -> press.opacityRest
        }
        if (alpha <= 0f) return
        val body: DrawScope.() -> Unit = {
            layer.glow?.let { (color, width) ->
                for (step in 3 downTo 1) {
                    drawPath(layer.path, color.copy(alpha = color.alpha * alpha * 0.35f / step), style = Stroke(width = width * step * 1.6f))
                }
            }
            layer.fill?.let { drawPath(layer.path, it, alpha = alpha) }
            layer.stroke?.let { drawPath(layer.path, it, alpha = alpha, style = Stroke(width = layer.strokeWidth)) }
        }
        if (press == null) {
            body()
            return
        }
        val shift = if (down) press.translate else Offset.Zero
        val s = if (down) press.scaleDown else press.scaleRest
        val r = if (down) press.rotateDown else press.rotateRest
        val squash = if (down) press.squash else Offset(1f, 1f)
        translate(shift.x, shift.y) {
            rotate(r, press.pivot) {
                scale(s * squash.x, s * squash.y, press.pivot) { body() }
            }
        }
    }

    companion object {
        fun of(slot: SkinSlot, size: Size, roles: SkinRoles): KeySkinDrawing {
            val fit = SkinFit(slot, size.width, size.height)
            val u = fit.unit
            val layers = slot.layers.mapNotNull { layer -> prepare(layer, fit, u, roles) }
            return KeySkinDrawing(layers)
        }

        private fun prepare(layer: SkinLayer, fit: SkinFit, u: Float, roles: SkinRoles): Prepared? {
            val commands = parseSvgPath(layer.d)
            if (commands.isEmpty()) return null
            val path = commands.fitted(fit).toComposePath()
            if (layer.evenOdd) path.fillType = PathFillType.EvenOdd
            val fill = layer.fill?.let { brushOf(it, fit, roles) }
            val stroke = layer.stroke?.let { brushOf(it, fit, roles) }
            if (fill == null && stroke == null && layer.glow == null) return null
            val glow = layer.glow?.let { g ->
                roles.color(g.color)?.let { it.copy(alpha = it.alpha * g.alpha.coerceIn(0f, 1f)) to g.width * u }
            }
            val press = layer.press?.let { p ->
                PressTransform(
                    translate = Offset((p.translate?.getOrNull(0) ?: 0f) * u, (p.translate?.getOrNull(1) ?: 0f) * u),
                    opacityRest = p.opacity?.getOrNull(0) ?: 1f,
                    opacityDown = p.opacity?.getOrNull(1) ?: p.opacity?.getOrNull(0) ?: 1f,
                    scaleRest = p.scale?.getOrNull(0) ?: 1f,
                    scaleDown = p.scale?.getOrNull(1) ?: p.scale?.getOrNull(0) ?: 1f,
                    rotateRest = p.rotate?.getOrNull(0) ?: 0f,
                    rotateDown = p.rotate?.getOrNull(1) ?: p.rotate?.getOrNull(0) ?: 0f,
                    squash = Offset(p.squash?.getOrNull(0) ?: 1f, p.squash?.getOrNull(1) ?: 1f),
                    pivot = p.pivot?.let { Offset(fit.x(it.getOrNull(0) ?: 0f), fit.y(it.getOrNull(1) ?: 0f)) }
                        ?: Offset(fit.x(50f), fit.y(50f)),
                )
            }
            return Prepared(
                path = path,
                fill = fill,
                stroke = stroke,
                strokeWidth = layer.strokeWidth * u,
                opacity = layer.opacity.coerceIn(0f, 1f),
                glow = glow,
                press = press,
            )
        }

        private fun brushOf(paint: SkinPaint, fit: SkinFit, roles: SkinRoles): Brush? {
            paint.color?.let { ref -> return roles.color(ref)?.let { SolidColor(it) } }
            val stops = paint.stops.mapNotNull { stop -> roles.color(stop.color)?.let { stop.at.coerceIn(0f, 1f) to it } }
                .sortedBy { it.first }
            if (stops.isEmpty()) return null
            val padded = if (stops.size == 1) stops + (1f to stops.first().second) else stops
            paint.linear?.takeIf { it.size >= 4 }?.let { l ->
                return Brush.linearGradient(
                    *padded.toTypedArray(),
                    start = Offset(fit.x(l[0]), fit.y(l[1])),
                    end = Offset(fit.x(l[2]), fit.y(l[3])),
                )
            }
            paint.radial?.takeIf { it.size >= 3 }?.let { r ->
                return Brush.radialGradient(
                    *padded.toTypedArray(),
                    center = Offset(fit.x(r[0]), fit.y(r[1])),
                    radius = (r[2] * fit.unit).coerceAtLeast(0.5f),
                )
            }
            return SolidColor(padded.first().second)
        }
    }
}

private fun List<PathCommand>.toComposePath(): Path {
    val path = Path()
    for (c in this) {
        when (c) {
            is PathCommand.MoveTo -> path.moveTo(c.x, c.y)
            is PathCommand.LineTo -> path.lineTo(c.x, c.y)
            is PathCommand.CubicTo -> path.cubicTo(c.x1, c.y1, c.x2, c.y2, c.x, c.y)
            PathCommand.Close -> path.close()
        }
    }
    return path
}

/** The skin slot a key wears, by what it does; see [SkinSlotName]. */
internal fun skinSlotNameFor(key: Key): String = when (val action = key.action) {
    KeyAction.Text -> {
        val text = key.output ?: key.label
        if (text.isNotEmpty() && Character.isLetter(text.codePointAt(0))) SkinSlotName.LETTER else SkinSlotName.PUNCTUATION
    }
    KeyAction.Space -> SkinSlotName.SPACE
    KeyAction.Enter, KeyAction.Newline, KeyAction.EditorAction -> SkinSlotName.ENTER
    KeyAction.Delete, KeyAction.ForwardDelete -> SkinSlotName.DELETE
    KeyAction.Shift, KeyAction.CapsLock -> SkinSlotName.SHIFT
    KeyAction.Symbols, KeyAction.Letters, KeyAction.Numpad -> SkinSlotName.MODE
    is KeyAction.Mode, is KeyAction.LayerSwitch -> SkinSlotName.MODE
    KeyAction.Emoji -> SkinSlotName.EMOJI
    KeyAction.LanguageSwitch, KeyAction.InputMethodPicker -> SkinSlotName.GLOBE
    else -> if (action is KeyAction.KeymanKey) SkinSlotName.LETTER else SkinSlotName.SPECIAL
}

/** Whether the theme has popup art of its own: a skin's popup slot, or a key material. */
internal val KbTheme.popupDressed: Boolean
    get() = keySkin?.slots?.containsKey(SkinSlotName.POPUP) == true || material != null

/**
 * A popup's fill, dressed the way the keys are: the skin's own popup drawing
 * where it has one (an imported Kyng-style skin draws its callouts), else the
 * key material over the fill, so a wooden board's preview bubble is wood too.
 * With neither it is the plain fill it always was.
 */
internal fun Modifier.popupDressing(kb: KbTheme, shape: Shape, fill: Color, label: Color): Modifier {
    if (!kb.popupDressed) return background(fill, shape)
    val slot = kb.keySkin?.slots?.get(SkinSlotName.POPUP)
    val material = kb.material
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val skin = slot?.let { KeySkinDrawing.of(it, size, SkinRoles(kb.skinPalette(), fill, label)) }
        val paint = if (skin == null) {
            material?.let { MaterialPaint.of(it, shape, size, layoutDirection, this, fill, fill, kb.accent, hue = 0f) }
        } else {
            null
        }
        onDrawBehind {
            if (skin != null) {
                skin.draw(this, false)
            } else {
                paint?.drawUnder(this, false)
                drawOutline(outline, fill)
                paint?.drawOver(this, false)
            }
        }
    }
}

/**
 * The same for a popup drawn as a Surface: the dressing goes in the Surface's
 * own modifier, under its content, and [popupSurfaceColor] turns the Surface's
 * own fill off so it does not cover the dressing. A theme with nothing to
 * dress changes nothing.
 */
@androidx.compose.runtime.Composable
internal fun Modifier.popupDressingOnly(kb: KbTheme, shape: Shape): Modifier =
    if (kb.popupDressed) popupDressing(kb, shape, kb.popupFill(FrostSurface.POPUPS), kb.popupText) else this

@androidx.compose.runtime.Composable
internal fun KbTheme.popupSurfaceColor(): Color =
    if (popupDressed) Color.Transparent else popupFill(FrostSurface.POPUPS)

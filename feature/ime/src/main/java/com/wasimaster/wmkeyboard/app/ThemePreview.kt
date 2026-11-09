package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wasimaster.wmkeyboard.core.theme.BackgroundBitmapCache
import com.wasimaster.wmkeyboard.ime.ui.DecalsCanvas
import com.wasimaster.wmkeyboard.ime.ui.BackdropKind
import com.wasimaster.wmkeyboard.ime.ui.BoardFill
import com.wasimaster.wmkeyboard.ime.ui.FrostBackdrop
import com.wasimaster.wmkeyboard.ime.ui.KbFrost
import com.wasimaster.wmkeyboard.core.theme.WallpaperBackdrop
import com.wasimaster.wmkeyboard.ime.ui.frostLocalBoardBounds
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.resolveFrost
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Animation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.theme.KeyShapeKind
import com.wasimaster.wmkeyboard.core.theme.ThemeAnimation
import com.wasimaster.wmkeyboard.core.theme.ThemeBackgroundImage
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.SkinSlotName
import com.wasimaster.wmkeyboard.core.theme.resolveMaterial
import com.wasimaster.wmkeyboard.core.theme.slotFor
import com.wasimaster.wmkeyboard.ime.ui.BoardEffectLayer
import com.wasimaster.wmkeyboard.ime.ui.KeySkinDrawing
import com.wasimaster.wmkeyboard.ime.ui.MaterialPaint
import com.wasimaster.wmkeyboard.ime.ui.SkinRoles
import com.wasimaster.wmkeyboard.ime.ui.resolveBoardEffect
import com.wasimaster.wmkeyboard.ime.ui.skinPalette
import androidx.compose.ui.draw.drawWithCache
import com.wasimaster.wmkeyboard.core.theme.brush
import com.wasimaster.wmkeyboard.core.theme.keyShapeFor
import com.wasimaster.wmkeyboard.core.theme.keyShapeKindOrNull
import com.wasimaster.wmkeyboard.core.theme.rememberWallpaperFollowed

/**
 * Miniature keyboard drawn from the spec: toolbar, two key rows, bottom row.
 *
 * [imageOverride] shows a photo the theme has not been given yet, which is what
 * makes the picker's "how would this look" preview honest — the same
 * composable, the same key shapes and colours, a different image.
 * [landscape] previews the theme's separate landscape image where it has one.
 *
 * A theme that moves gets a badge in the corner. The preview itself is drawn at
 * phase 0 and never animates — a gallery of twenty live gradients is a gallery
 * that never stops recomposing — so without the badge there is nothing at all
 * to tell an animated theme from a still one until it is applied.
 */
@Composable
fun ThemePreview(
    theme: ThemeSpec,
    modifier: Modifier = Modifier,
    imageOverride: Any? = null,
    landscape: Boolean = false,
    animatedBadge: Boolean = true,
) = ThemePreviewContent(
    // In the wallpaper's colours when the theme follows it, so the card and
    // the keyboard never disagree about what the theme looks like today.
    theme = rememberWallpaperFollowed(theme),
    modifier = modifier,
    imageOverride = imageOverride,
    landscape = landscape,
    animatedBadge = animatedBadge,
)

@Composable
private fun ThemePreviewContent(
    theme: ThemeSpec,
    modifier: Modifier,
    imageOverride: Any?,
    landscape: Boolean,
    animatedBadge: Boolean,
) {
    // Half the 3 dp the rows space their keys by, so a leaning shape spills
    // into the gap here exactly as it does on the keyboard itself.
    val keyShape = remember(theme.keyShape, theme.keyCornerRadiusDp) {
        keyShapeFor(
            theme.keyShape,
            ((theme.keyCornerRadiusDp ?: 8) / 3f + 1).toInt(),
            bleedDp = 1.5f,
        )
    }
    // The theme's own tool shape where it has one; a theme that follows the
    // global setting is drawn with the shipped default, since the preview is
    // handed a spec and never sees the settings. The mock-up's tools are a
    // tenth the size of the real ones, so the radius rounds them fully either
    // way and the row still reads as circles unless the theme says otherwise.
    val toolShape = remember(theme.toolShape, theme.toolCircleRadiusDp) {
        keyShapeFor(
            keyShapeKindOrNull(theme.toolShape) ?: KeyShapeKind.ROUNDED,
            theme.toolCircleRadiusDp ?: 20,
        )
    }
    // The size the preview is really drawn at, so its photo is decoded for
    // that and not for the whole screen. A gallery card is half the screen
    // wide, and decoding every one of them at full width cost four times the
    // memory, which also pushed the shared bitmap cache into evicting what
    // the next card back up the list needed again. Nothing is decoded before
    // the first measure, which the old screen-width guess only hid: the
    // decode was asynchronous then too.
    var drawnWidth by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Shaped like a keyboard rather than like a letterbox. The old
            // fixed height made every preview a wide, flat strip that told the
            // user very little about how the real thing would look.
            .aspectRatio(if (landscape) PREVIEW_ASPECT_LANDSCAPE else PREVIEW_ASPECT_PORTRAIT)
            .clip(RoundedCornerShape(10.dp))
            .onSizeChanged { if (it.width != drawnWidth) drawnWidth = it.width },
    ) {
        val themeImage = if (landscape) {
            theme.backgroundImageLandscape ?: theme.backgroundImage
        } else {
            theme.backgroundImage
        }
        val targetW = drawnWidth
        val targetH = (targetW / PREVIEW_ASPECT_PORTRAIT).toInt()
        val measured = targetW > 0
        // The glass, as the keyboard draws it where the window is not blurred
        // (the gallery has no app behind it): the image, or the wallpaper's
        // band, baked blurred for the keys to sample, and the wallpaper as the
        // board's own picture. Without either the glass is the keys' own
        // translucency, their rim and their grain.
        val frost = theme.frost?.let { resolveFrost(it, theme.dark) }
        val backdrop = if (frost != null) remember { FrostBackdrop() } else null
        val glassImage = (imageOverride as? String)?.takeUnless { it.startsWith("http") } ?: themeImage
        val kind = frost?.backdropKind(hasImage = glassImage != null, blurLive = false)
        if (frost != null && backdrop != null && measured) {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            LaunchedEffect(backdrop, kind, frost.backdropBlur, frost.saturation, frost.brightness, frost.wallpaperBand, glassImage, targetW, targetH, configuration) {
                backdrop.bitmap = when (kind) {
                    null -> null
                    BackdropKind.IMAGE -> glassImage?.let {
                        BackgroundBitmapCache.loadFrosted(
                            it, frost.backdropBlur, frost.saturation, frost.brightness, targetW, targetH,
                        )?.asImageBitmap()
                    }
                    else -> WallpaperBackdrop.loadFrosted(
                        context, frost.wallpaperBand, frost.backdropBlur, frost.saturation, frost.brightness,
                        targetW, targetH, theme.dark,
                        fallbackColors = listOf(theme.boardBackground, theme.accent, theme.keyBackground),
                    )?.asImageBitmap()
                }
            }
        }
        // The board's picture frosted, as the keyboard draws it: the wallpaper
        // band always, a theme image when the theme frosts the board too.
        if (kind == BackdropKind.WALLPAPER || (kind == BackdropKind.IMAGE && frost?.blurBoard == true)) {
            backdrop?.bitmap?.let { picture ->
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // Nothing to stand on and no blur: the board at its fallback opacity,
        // as the keyboard draws it on a phone that cannot blur.
        val boardFloor = frost?.takeIf { it.windowBlur && kind == null }?.fallbackOpacity
        val remote = imageOverride as? String
        if (remote != null && remote.startsWith("http")) {
            // A photo being considered has not been downloaded yet, so it is
            // shown straight from the service. Without this the preview drew
            // whatever the theme already had, which answered the wrong question.
            AsyncImage(
                model = remote,
                contentDescription = null,
                imageLoader = rememberMediaImageLoader(),
                modifier = Modifier
                    .matchParentSize()
                    .alpha(theme.backgroundImageOpacity),
                contentScale = ContentScale.Crop,
            )
        } else if (measured) {
            ThemeBackgroundImage(
                path = remote ?: themeImage,
                blur = theme.backgroundImageBlur,
                opacity = theme.backgroundImageOpacity,
                targetW = targetW,
                targetH = targetH,
            )
        }
        // Static (phase 0) gradient — the preview doesn't animate.
        val boardGradient = theme.boardGradient
        val boardModifier = Modifier.matchParentSize()
            .then(if (backdrop != null) Modifier.frostLocalBoardBounds(backdrop) else Modifier)
        if (boardGradient != null) {
            Box(modifier = boardModifier.background(boardGradient.brush()))
        } else {
            val board = colorOf(theme.boardBackground).let { color ->
                if (boardFloor == null || color.alpha >= boardFloor) color else color.copy(alpha = boardFloor)
            }
            Box(modifier = boardModifier.background(board))
        }
        // The skin's board art and a still frame of the moving board, as the
        // keyboard layers them: over the board colour, under the keys.
        theme.keySkin?.board?.let { slot ->
            val palette = remember(theme) { theme.skinPalette() }
            Box(
                Modifier.matchParentSize().drawWithCache {
                    val art = KeySkinDrawing.of(slot, size, SkinRoles(palette, palette.board, palette.label))
                    onDrawBehind { art.draw(this, false) }
                },
            )
        }
        val boardEffect = theme.boardEffect?.let { resolveBoardEffect(it, colorOf(theme.accent), colorOf(theme.keyText)) }
        boardEffect?.takeUnless { it.kind.onPress }?.let { BoardEffectLayer(it, Modifier.matchParentSize(), still = true) }
        // The letter-key texture, drawn the way the real board draws it: over
        // the key colour, clipped to the key shape. One 128 px decode serves
        // every key of the mock-up.
        val texturePath = theme.keyTexture?.takeIf { theme.keyShape != KeyShapeKind.NONE }
        val texture by produceState<ImageBitmap?>(null, texturePath) {
            value = texturePath?.let {
                BackgroundBitmapCache.load(it, 0f, PREVIEW_TEXTURE_PX, PREVIEW_TEXTURE_PX)
                    ?.asImageBitmap()
            }
        }
        if (frost == null || backdrop == null) {
            // One node for the toolbar and every key. Drawn as composables,
            // each preview was some forty-five layout nodes, and a gallery of
            // forty themes composed and measured every one of them before it
            // could draw its first card.
            val grid = remember(theme, keyShape, toolShape, texture) {
                Modifier.previewKeyGrid(theme, keyShape, toolShape, texture)
            }
            Spacer(Modifier.matchParentSize().then(grid))
        } else {
            GlassPreviewKeys(theme, keyShape, toolShape, texture, PreviewGlass(frost, backdrop))
        }
        // Stickers over the mock-up keys, the same normalized placement the
        // real grid uses. The toolbar row is excluded there and included here;
        // at this scale the difference does not read.
        DecalsCanvas(theme.decals)
        // A press splash lands over the keys on the keyboard, so it does here,
        // caught part way as a picture.
        boardEffect?.takeIf { it.kind.onPress }?.let { BoardEffectLayer(it, Modifier.matchParentSize(), still = true) }
        if (animatedBadge && theme.animation != ThemeAnimation.NONE) {
            AnimatedThemeBadge(
                theme = theme,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }
    }
}

/**
 * The mock-up's toolbar and key rows as composables, for a glass theme: each
 * pane samples the backdrop through [frostGlass], which is a node of its own
 * per key. Every other theme is drawn in one pass by [previewKeyGrid].
 */
@Composable
private fun BoxScope.GlassPreviewKeys(
    theme: ThemeSpec,
    keyShape: Shape,
    toolShape: Shape,
    texture: ImageBitmap?,
    glass: PreviewGlass,
) {
    // Weighted rather than fixed heights, so the mock-up keeps its
    // proportions at whatever size it is drawn.
    Column(
        modifier = Modifier
            .matchParentSize()
            .padding(horizontal = PREVIEW_PAD_DP.dp, vertical = PREVIEW_PAD_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PREVIEW_ROW_GAP_DP.dp),
    ) {
        val barFill = theme.toolbarBackground ?: theme.suggestionBarBackground
        Row(
            modifier = Modifier
                .weight(TOOLBAR_WEIGHT)
                .fillMaxWidth()
                .then(if (barFill == null) Modifier else Modifier.background(colorOf(barFill))),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(TOOLBAR_CIRCLES) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight(TOOL_HEIGHT_FRACTION)
                        .aspectRatio(1f)
                        .background(colorOf(theme.effectiveToolCircle()), toolShape),
                )
            }
        }
        PreviewKeyRow {
            repeat(10) { LetterKey(theme, keyShape, glass, texture) }
        }
        PreviewKeyRow {
            Spacer(Modifier.weight(0.5f))
            repeat(9) { LetterKey(theme, keyShape, glass, texture) }
            Spacer(Modifier.weight(0.5f))
        }
        PreviewKeyRow {
            ModifierKey(theme, keyShape, glass, weight = 1.5f)
            repeat(7) { LetterKey(theme, keyShape, glass, texture) }
            ModifierKey(theme, keyShape, glass, weight = 1.5f)
        }
        PreviewKeyRow {
            ModifierKey(theme, keyShape, glass, weight = 1.5f)
            LetterKey(theme, keyShape, glass, texture, weight = 1f, dot = false)
            Box(
                modifier = Modifier
                    .weight(5f)
                    .fillMaxHeight()
                    .previewKeyFace(theme, theme.keyBackground, keyShape, glass.forSurface(FrostSurface.SPACE_BAR), glass.backdrop, SkinSlotName.SPACE),
            )
            LetterKey(theme, keyShape, glass, texture, weight = 1f, dot = false)
            Box(
                modifier = Modifier
                    .weight(1.5f)
                    .fillMaxHeight()
                    .previewKeyFace(theme, theme.enterKeyBackground, keyShape, glass.forSurface(FrostSurface.ENTER_KEY), glass.backdrop, SkinSlotName.ENTER),
            )
        }
    }
}

/** What one cell of the drawn mock-up is: its skin slot, and what it wears over its face. */
private enum class PreviewCell(val slot: String, val letter: Boolean, val dot: Boolean) {
    LETTER(SkinSlotName.LETTER, letter = true, dot = true),

    /** A letter-coloured key with no label dot: the two either side of the space bar. */
    BLANK(SkinSlotName.LETTER, letter = true, dot = false),
    MODIFIER(SkinSlotName.SPECIAL, letter = false, dot = false),
    SPACE(SkinSlotName.SPACE, letter = false, dot = false),
    ENTER(SkinSlotName.ENTER, letter = false, dot = false),

    /** Room taken in a row and nothing drawn there: the half-key inset of the second row. */
    GAP(SkinSlotName.LETTER, letter = false, dot = false),
}

/**
 * The mock-up's key rows, as weights. A real QWERTY narrows as it goes down:
 * ten letters, then nine inset by half a key, then seven between shift and
 * backspace. Drawing three identical rows of ten made the preview read as a
 * grid rather than as a keyboard.
 */
private val PreviewKeyRows: List<List<Pair<PreviewCell, Float>>> = listOf(
    List(10) { PreviewCell.LETTER to 1f },
    listOf(PreviewCell.GAP to 0.5f) + List(9) { PreviewCell.LETTER to 1f } + listOf(PreviewCell.GAP to 0.5f),
    listOf(PreviewCell.MODIFIER to 1.5f) + List(7) { PreviewCell.LETTER to 1f } + listOf(PreviewCell.MODIFIER to 1.5f),
    listOf(
        PreviewCell.MODIFIER to 1.5f,
        PreviewCell.BLANK to 1f,
        PreviewCell.SPACE to 5f,
        PreviewCell.BLANK to 1f,
        PreviewCell.ENTER to 1.5f,
    ),
)

/** One key of the drawn mock-up, fitted to its place once per size. */
private class DrawnKey(
    val cell: PreviewCell,
    val left: Float,
    val top: Float,
    val size: Size,
    val face: Color,
    /** The key's outline; null for a theme with no key shape, which draws no faces. */
    val outline: Outline?,
    val skin: KeySkinDrawing?,
    val material: MaterialPaint?,
    /** The outline the hairline border strokes, inset by half its width. */
    val border: Outline?,
    val sheen: Brush?,
    val textureClip: Path?,
    val textureSrc: Pair<IntOffset, IntSize>?,
)

/**
 * The toolbar and the four key rows in one draw node, laid out with the
 * arithmetic the composable rows used — padding, row gaps, key gaps and
 * weights — so a theme looks the same here as it did as forty-five boxes.
 * Everything that depends on the size is built once in the draw cache; a
 * scroll or a redraw only replays it.
 *
 * The layering is the old modifier chain's: the face colour, the material
 * over it, then the texture, the sheen and the label dot, and the border
 * over everything. A skin draws the whole face instead and takes no border.
 */
private fun Modifier.previewKeyGrid(
    theme: ThemeSpec,
    keyShape: Shape,
    toolShape: Shape,
    texture: ImageBitmap?,
): Modifier = drawWithCache {
    val faces = theme.keyShape != KeyShapeKind.NONE
    val pad = PREVIEW_PAD_DP.dp.toPx()
    val rowGap = PREVIEW_ROW_GAP_DP.dp.toPx()
    val keyGap = PREVIEW_KEY_GAP_DP.dp.toPx()
    val innerW = (size.width - pad * 2).coerceAtLeast(0f)
    val unit = ((size.height - pad * 2 - rowGap * PreviewKeyRows.size) /
        (TOOLBAR_WEIGHT + ROW_WEIGHT * PreviewKeyRows.size)).coerceAtLeast(0f)
    val barH = unit * TOOLBAR_WEIGHT
    val rowH = unit * ROW_WEIGHT

    // The toolbar: a strip of its own colour when the theme paints one, and
    // its tools spaced evenly along it.
    val barFill = (theme.toolbarBackground ?: theme.suggestionBarBackground)?.let(::colorOf)
    val toolD = barH * TOOL_HEIGHT_FRACTION
    val toolSpace = (innerW - toolD * TOOLBAR_CIRCLES) / (TOOLBAR_CIRCLES + 1)
    val toolOutline = toolShape.createOutline(Size(toolD, toolD), layoutDirection, this)
    val toolColor = colorOf(theme.effectiveToolCircle())

    val palette = if (faces && theme.keySkin != null) theme.skinPalette() else null
    val material = theme.keyMaterial?.let(::resolveMaterial)?.takeUnless { it.isPlain }
    val borderColor = theme.keyBorderColor?.takeIf { faces && theme.keyBorderWidthDp > 0f }?.let(::colorOf)
    val borderPx = PREVIEW_BORDER_DP.dp.toPx()
    val accent = colorOf(theme.accent)
    val keys = ArrayList<DrawnKey>(KEY_COUNT)
    var top = pad + barH + rowGap
    for (row in PreviewKeyRows) {
        val weights = row.fold(0f) { sum, (_, weight) -> sum + weight }
        val perWeight = (innerW - keyGap * (row.size - 1)).coerceAtLeast(0f) / weights
        var left = pad
        for ((cell, weight) in row) {
            val width = perWeight * weight
            if (cell != PreviewCell.GAP) {
                val keySize = Size(width, rowH)
                val face = colorOf(
                    when (cell) {
                        PreviewCell.MODIFIER -> theme.modifierKeyBackground
                        PreviewCell.ENTER -> theme.enterKeyBackground
                        else -> theme.keyBackground
                    },
                )
                val outline = if (faces) keyShape.createOutline(keySize, layoutDirection, this) else null
                val skin = palette?.let { p ->
                    theme.keySkin?.slotFor(cell.slot)?.let { art ->
                        KeySkinDrawing.of(art, keySize, SkinRoles(p, face, p.label))
                    }
                }
                val dressed = outline != null && skin == null
                val letterFace = faces && cell.letter
                keys += DrawnKey(
                    cell = cell,
                    left = left,
                    top = top,
                    size = keySize,
                    face = face,
                    outline = outline,
                    skin = skin,
                    material = if (dressed && material != null) {
                        MaterialPaint.of(
                            material, keyShape, keySize, layoutDirection, this,
                            face = face, pressedFace = face, accent = accent, hue = 0f,
                        )
                    } else {
                        null
                    },
                    border = if (dressed && borderColor != null) {
                        keyShape.createOutline(
                            Size((width - borderPx).coerceAtLeast(0f), (rowH - borderPx).coerceAtLeast(0f)),
                            layoutDirection,
                            this,
                        )
                    } else {
                        null
                    },
                    // A brush per key: a gradient brush rebuilds its shader
                    // whenever it is drawn at a different size, and the rows'
                    // keys are not all one width.
                    sheen = if (letterFace) theme.keyGradient?.brush() else null,
                    textureClip = if (letterFace && texture != null && outline != null) {
                        Path().apply { addOutline(outline) }
                    } else {
                        null
                    },
                    textureSrc = if (letterFace && texture != null) cropSource(texture, keySize) else null,
                )
            }
            left += width + keyGap
        }
        top += rowH + rowGap
    }
    val keyText = colorOf(theme.keyText)
    val textureAlpha = theme.keyTextureOpacity
    onDrawBehind {
        if (barFill != null) drawRect(barFill, Offset(pad, pad), Size(innerW, barH))
        val toolTop = pad + (barH - toolD) / 2f
        repeat(TOOLBAR_CIRCLES) { index ->
            translate(pad + toolSpace * (index + 1) + toolD * index, toolTop) {
                drawOutline(toolOutline, toolColor)
            }
        }
        for (key in keys) {
            translate(key.left, key.top) { drawKey(key, texture, textureAlpha, keyText, borderColor, borderPx) }
        }
    }
}

/** One [DrawnKey], with the origin already moved to its top-left. */
private fun DrawScope.drawKey(
    key: DrawnKey,
    texture: ImageBitmap?,
    textureAlpha: Float,
    keyText: Color,
    borderColor: Color?,
    borderPx: Float,
) {
    // Inset to the key's own size, so a skin or a material that reads the
    // scope's size sees the key and not the whole preview.
    inset(0f, 0f, size.width - key.size.width, size.height - key.size.height) {
        val outline = key.outline
        if (key.skin != null) {
            key.skin.draw(this, false)
        } else if (outline != null) {
            drawOutline(outline, key.face)
            key.material?.drawOver(this, false)
        }
        val clip = key.textureClip
        val src = key.textureSrc
        if (texture != null && clip != null && src != null) {
            clipPath(clip) {
                drawImage(
                    image = texture,
                    srcOffset = src.first,
                    srcSize = src.second,
                    dstSize = IntSize(key.size.width.roundToInt(), key.size.height.roundToInt()),
                    alpha = textureAlpha,
                )
            }
        }
        if (key.sheen != null && outline != null) drawOutline(outline, key.sheen)
        if (key.cell.dot) {
            drawCircle(keyText, radius = key.size.height * DOT_HEIGHT_FRACTION / 2f)
        }
        val border = key.border
        if (border != null && borderColor != null) {
            translate(borderPx / 2f, borderPx / 2f) {
                drawOutline(border, borderColor, style = Stroke(width = borderPx))
            }
        }
    }
}

/** The part of [image] a key of [dst] shows when the image is cropped to cover it. */
private fun cropSource(image: ImageBitmap, dst: Size): Pair<IntOffset, IntSize>? {
    if (image.width <= 0 || image.height <= 0 || dst.width <= 0f || dst.height <= 0f) return null
    val scale = max(dst.width / image.width, dst.height / image.height)
    val w = (dst.width / scale).roundToInt().coerceIn(1, image.width)
    val h = (dst.height / scale).roundToInt().coerceIn(1, image.height)
    return IntOffset((image.width - w) / 2, (image.height - h) / 2) to IntSize(w, h)
}

/**
 * The corner badge on an animated theme's preview.
 *
 * Drawn in the theme's own key colours rather than the app's, because it sits
 * on top of the mock-up: an accent from the settings palette would read as part
 * of the surrounding card and disappear over a light theme's board.
 */
@Composable
private fun AnimatedThemeBadge(theme: ThemeSpec, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colorOf(theme.keyBackground).copy(alpha = 0.92f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Animation,
            contentDescription = stringResource(R.string.ime_theme_animated_desc),
            modifier = Modifier.size(11.dp),
            tint = colorOf(theme.keyText),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            stringResource(R.string.ime_theme_animated_badge),
            fontSize = 8.sp,
            lineHeight = 10.sp,
            color = colorOf(theme.keyText),
        )
    }
}

/** One row of the mock-up keyboard. Every row is the same height. */
@Composable
private fun ColumnScope.PreviewKeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .weight(ROW_WEIGHT)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PREVIEW_KEY_GAP_DP.dp),
        content = content,
    )
}

/** A letter key, with a dot standing in for its label. */
@Composable
private fun RowScope.LetterKey(
    theme: ThemeSpec,
    keyShape: Shape,
    glass: PreviewGlass,
    /** The preview's one texture decode, shared by every letter key; null for none. */
    texture: ImageBitmap?,
    weight: Float = 1f,
    dot: Boolean = true,
) {
    // A theme with no key shape has no face to lay a texture or a sheen on.
    val faces = theme.keyShape != KeyShapeKind.NONE
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .previewKeyFace(theme, theme.keyBackground, keyShape, glass.forSurface(FrostSurface.KEYS), glass.backdrop),
        contentAlignment = Alignment.Center,
    ) {
        texture?.takeIf { faces }?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(keyShape)
                    .alpha(theme.keyTextureOpacity),
            )
        }
        // The key sheen, at phase 0 like the board gradient: without it the
        // editor said "no" to a gradient the keyboard was already drawing.
        theme.keyGradient?.takeIf { faces }?.let {
            Box(
                Modifier
                    .matchParentSize()
                    .background(it.brush(), keyShape),
            )
        }
        if (dot) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(DOT_HEIGHT_FRACTION)
                    .aspectRatio(1f)
                    .background(colorOf(theme.keyText), CircleShape),
            )
        }
    }
}

/** Decode edge for the mock-up's key texture; its keys are a few dp across. */
private const val PREVIEW_TEXTURE_PX = 128

/** The rounding a liquid lens bends around on a mock-up key, which is a tenth the size of a real one. */
private const val PREVIEW_LENS_RADIUS_DP = 3

/** Shift, backspace and the symbol key, which carry the modifier colour. */
@Composable
private fun RowScope.ModifierKey(theme: ThemeSpec, keyShape: Shape, glass: PreviewGlass, weight: Float) {
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .previewKeyFace(theme, theme.modifierKeyBackground, keyShape, glass.forSurface(FrostSurface.MODIFIER_KEYS), glass.backdrop, SkinSlotName.SPECIAL),
    )
}

/**
 * The mock-up's glass: the resolved frost and the backdrop its keys sample,
 * handed to each key with the surface it stands for. Null frost is a theme
 * with no glass, and [forSurface] then hands back nothing to draw.
 */
private class PreviewGlass(private val frost: KbFrost?, val backdrop: FrostBackdrop) {
    fun forSurface(surface: FrostSurface): KbFrost? = frost?.takeIf { it.has(surface) }
}



/**
 * A mock-up key's face in [color] with the theme's key border, or nothing at
 * all for a theme with no key shape, whose keyboard shows only the labels.
 */
private fun Modifier.previewKeyFace(
    theme: ThemeSpec,
    color: Long,
    shape: Shape,
    glass: KbFrost? = null,
    backdrop: FrostBackdrop? = null,
    slot: String = SkinSlotName.LETTER,
): Modifier {
    if (theme.keyShape == KeyShapeKind.NONE) return this
    // A skin draws the whole key; a material dresses the plain one.
    theme.keySkin?.slotFor(slot)?.let { art ->
        val palette = theme.skinPalette()
        val face = colorOf(color)
        return drawWithCache {
            val drawing = KeySkinDrawing.of(art, size, SkinRoles(palette, face, palette.label))
            onDrawBehind { drawing.draw(this, false) }
        }
    }
    val material = theme.keyMaterial?.let(::resolveMaterial)?.takeUnless { it.isPlain }
    return previewPlainFace(theme, color, shape, glass, backdrop).then(
        if (material == null) {
            Modifier
        } else {
            Modifier.drawWithCache {
                val paint = MaterialPaint.of(
                    material, shape, size, layoutDirection, this,
                    face = colorOf(color), pressedFace = colorOf(color), accent = colorOf(theme.accent), hue = 0f,
                )
                // Over the face only: the cartoon key's hard shadow belongs
                // under it, and this layer is already above the fill.
                onDrawBehind { paint.drawOver(this, false) }
            }
        },
    )
}

private fun Modifier.previewPlainFace(
    theme: ThemeSpec,
    color: Long,
    shape: Shape,
    glass: KbFrost?,
    backdrop: FrostBackdrop?,
): Modifier =
    run {
        // Glass under the colour, exactly as the key grid layers it.
        then(
            if (glass != null && backdrop != null) {
                // The mock-up's keys are a tenth the size, so the lens bends around a small radius.
                Modifier.frostGlass(
                    glass, backdrop, shape,
                    lensKind = theme.keyShape,
                    lensRadiusDp = PREVIEW_LENS_RADIUS_DP,
                    boardFill = BoardFill(Color(theme.boardBackground.toInt()), theme.boardGradient),
                )
            } else {
                Modifier
            },
        )
            .background(colorOf(color), shape)
            .previewKeyBorder(theme, shape)
    }

/**
 * The theme's key border on a mock-up key. Drawn at a fixed hairline rather
 * than the theme's width — the keys are a tenth of their real size, and a
 * 2 dp border at this scale is a filled key.
 */
private fun Modifier.previewKeyBorder(theme: ThemeSpec, shape: Shape): Modifier {
    val color = theme.keyBorderColor ?: return this
    if (theme.keyBorderWidthDp <= 0f) return this
    return border(PREVIEW_BORDER_DP.dp, colorOf(color), shape)
}

private fun colorOf(argb: Long): Color = Color(argb.toInt())

private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

// Mirrors the editor's fallback (ThemeScreens.effectiveToolCircle); kept
// private on both sides because it is presentation policy, not theme data.
private fun ThemeSpec.effectiveToolCircle(): Long =
    toolCircleBackground ?: colorOf(keyText).copy(alpha = 0.14f)
        .compositeOver(colorOf(boardBackground)).argb()

/**
 * Width to height of the preview. A real keyboard is about 360 dp across and
 * 220 dp tall with its toolbar, which is roughly 1.65:1; sideways it is much
 * wider for the same number of rows.
 */
private const val PREVIEW_ASPECT_PORTRAIT = 1.7f
private const val PREVIEW_ASPECT_LANDSCAPE = 3.2f

/** How the mock-up divides its height between the toolbar and the key rows. */
private const val TOOLBAR_WEIGHT = 0.75f
private const val ROW_WEIGHT = 1f

/** Tools shown in the mock-up's toolbar row. */
private const val TOOLBAR_CIRCLES = 5

/** How much of the toolbar's height a tool takes; it is as wide as it is tall. */
private const val TOOL_HEIGHT_FRACTION = 0.6f

/** How much of a letter key's height its label dot takes. */
private const val DOT_HEIGHT_FRACTION = 0.2f

/** The mock-up's inner padding, the gap between its rows and the gap between two keys, in dp. */
private const val PREVIEW_PAD_DP = 6
private const val PREVIEW_ROW_GAP_DP = 4
private const val PREVIEW_KEY_GAP_DP = 3

/** The hairline a mock-up key's border is drawn at, in dp. */
private const val PREVIEW_BORDER_DP = 0.7f

/** Keys in the drawn mock-up, for sizing its list. */
private const val KEY_COUNT = 40

package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.theme.FrostSurface

/**
 * The long-press alternates drawn as separate keys (#1461): no shared bubble
 * behind the grid, but a tile per entry in the popup's colour, shape, border
 * and material, a small gap apart.
 *
 * Only the drawing moves. Each tile is painted inside the entry's own laid-out
 * box, inset by half the gap, so the grid measures and places its entries
 * exactly as it does for the bubble and a finger is matched against the same
 * rects: the gap between two tiles still belongs to the nearer one.
 *
 * [rects] are the entries' rects in the grid, as the grid last placed them.
 * [highlight] is where the finger's highlight is drawn right now in that same
 * space, or null when nothing is lit (or no finger steers the popup). The
 * bubble draws the highlight once behind the whole grid; here that would sit
 * under the tiles, so each tile paints the part of it that crosses it, over
 * its own fill and under its glyph. Settled, that is the whole tile; sliding
 * between two, it crosses from one into the next and is hidden in the gap.
 */
internal class AlternateTiles(
    val rects: State<List<Rect>>,
    val highlight: (() -> Rect?)?,
)

/** The gap between two neighbouring tiles. */
private val AlternateTileGap = 4.dp

/**
 * A tile's lift. Lower than the bubble's 8 dp: a tile sits a gap from the
 * next, and a full popup shadow under each would smear into the gaps.
 */
private val AlternateTileElevation = 4.dp

/**
 * Dresses one alternate as its own key when [tiles] is set, and changes
 * nothing otherwise. Goes first in the entry's chain, outside its magnify, so
 * the tile stays put while the glyph under the finger grows.
 *
 * The layers are the bubble Surface's, in the Surface's order: glass, the
 * key material's dressing (which casts the shadow itself while dressed,
 * #1487), the shadow of a plain fill, the border, the fill. The shadow goes
 * through [elevationFor], so a concave popup shape casts none, as the bubble
 * does.
 */
@Composable
internal fun Modifier.alternateTile(index: Int, tiles: AlternateTiles?): Modifier {
    if (tiles == null) return this
    val kb = LocalKbTheme.current
    val inset = with(LocalDensity.current) { AlternateTileGap.toPx() } / 2f
    val shape = InsetShape(kb.popupShape(), inset)
    val elevation = elevationFor(kb.popupShapeKind, AlternateTileElevation)
    val dressed = this
        .frostGlass(kb, shape, FrostSurface.POPUPS)
        .popupDressingOnly(kb, shape, elevation)
        .shadow(kb.popupSurfaceElevation(elevation), shape, clip = false)
        .popupBorder(kb, shape)
        .background(kb.popupSurfaceColor(), shape)
    val highlight = tiles.highlight ?: return dressed
    val selected = kb.popupSelected
    return dressed.drawWithCache {
        val clip = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawBehind {
            val at = highlight() ?: return@onDrawBehind
            val mine = tiles.rects.value.getOrNull(index) ?: return@onDrawBehind
            if (at.width <= 0f || at.height <= 0f) return@onDrawBehind
            clipPath(clip) {
                drawRect(selected, topLeft = at.topLeft - mine.topLeft, size = at.size)
            }
        }
    }
}

/**
 * The shared bubble's glass and key-material dressing, as the alternates
 * popup's Surface wears them; nothing when the entries are [AlternateTiles],
 * which wear their own.
 */
@Composable
internal fun Modifier.alternatesBubble(kb: KbTheme, tiles: Boolean): Modifier =
    if (tiles) {
        this
    } else {
        frostGlass(kb, kb.popupShape(), FrostSurface.POPUPS)
            .popupDressingOnly(kb, kb.popupShape(), elevationFor(kb.popupShapeKind, 8.dp))
    }

/**
 * [base] drawn [inset] pixels in from every side of the box it is given, so a
 * tile keeps the theme's popup shape (the sampled Material shapes included)
 * at the size it is drawn, rather than the shape being cut from the full box.
 */
private data class InsetShape(val base: Shape, val inset: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val width = size.width - inset * 2f
        val height = size.height - inset * 2f
        if (width <= 0f || height <= 0f) return base.createOutline(size, layoutDirection, density)
        return when (val outline = base.createOutline(Size(width, height), layoutDirection, density)) {
            is Outline.Rectangle -> Outline.Rectangle(outline.rect.translate(inset, inset))
            is Outline.Rounded -> Outline.Rounded(outline.roundRect.translate(Offset(inset, inset)))
            is Outline.Generic -> Outline.Generic(Path().apply { addPath(outline.path, Offset(inset, inset)) })
        }
    }
}

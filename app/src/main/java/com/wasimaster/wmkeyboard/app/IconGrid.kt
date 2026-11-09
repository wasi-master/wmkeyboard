package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The narrowest an [IconGridCell] gets, for a grid's `GridCells.Adaptive`. */
internal val IconGridCellMinWidth = 68.dp

/**
 * One entry of an icon picker's grid: the glyph, with its name under it in small
 * type.
 *
 * The name is the thing a layout file, a search box or a bug report uses, so the
 * picker is where a user learns it (issue #187). Before, the grid drew bare
 * glyphs and a name was something to guess.
 */
@Composable
internal fun IconGridCell(
    vector: ImageVector,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The name underneath already says what this is, so the glyph adds
    // nothing a screen reader should repeat.
    IconGridCell(name, selected, onClick, modifier) {
        Icon(vector, contentDescription = null, modifier = Modifier.size(IconGridGlyph))
    }
}

/** The glyph size inside an [IconGridCell], for a cell that draws its own. */
internal val IconGridGlyph = 22.dp

/**
 * An [IconGridCell] that draws its own [glyph], for art that is not a plain
 * vector: a pack's picture, or a full-colour icon that must not take the tint.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IconGridCell(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A long press, for a cell that has more to offer than being picked. */
    onLongClick: (() -> Unit)? = null,
    glyph: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick == null) {
                    Modifier.selectable(selected = selected, onClick = onClick)
                } else {
                    Modifier
                        .semantics { this.selected = selected }
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Tab)
                },
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
        ) {
            glyph()
            Text(
                name,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

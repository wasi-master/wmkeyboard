package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent

/**
 * A sideways-scrolling row of choices that opens with the chosen one in the
 * middle, so a long row (fonts, frames) does not open at its start with the
 * choice somewhere out of sight. Only the first showing is moved: after that
 * the row stays where the user scrolled it.
 *
 * Put [viewport] on the row before its `horizontalScroll(scroll)`, and
 * [selected] on the chosen item.
 */
@Stable
class CentredRow internal constructor(val scroll: ScrollState) {
    private var width by mutableIntStateOf(0)
    private var start by mutableIntStateOf(-1)
    private var end by mutableIntStateOf(-1)
    private var done = false

    val viewport: Modifier = Modifier.onSizeChanged { width = it.width }

    val selected: Modifier = Modifier.onPlaced { coordinates ->
        val x = coordinates.positionInParent().x.toInt()
        start = x
        end = x + coordinates.size.width
    }

    internal suspend fun centre() {
        if (done || width == 0 || start < 0) return
        done = true
        scroll.scrollTo(((start + end) / 2 - width / 2).coerceAtLeast(0))
    }

    internal val ready: Boolean get() = width > 0 && start >= 0
}

/** A [CentredRow] remembered here, centred once its row has been laid out. */
@Composable
fun rememberCentredRow(): CentredRow {
    val scroll = rememberScrollState()
    val row = remember(scroll) { CentredRow(scroll) }
    val ready by remember(row) { derivedStateOf { row.ready } }
    LaunchedEffect(row, ready) { if (ready) row.centre() }
    return row
}

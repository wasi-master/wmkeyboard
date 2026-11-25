package com.wasimaster.wmkeyboard.ime.dynstickers

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.wasimaster.wmkeyboard.core.tools.GifItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A text sticker playing in its cell, drawn from the template itself.
 *
 * Nothing is encoded: the template is prepared once while the cell is on
 * screen, and each frame is drawn straight into one of two bitmaps the size
 * of the cell, off the main thread, a few dozen times a second. It goes over
 * the cell's still, which shows until the first frame is drawn and stays on
 * its own for a template that does not move. A cell scrolled away, or a
 * keyboard put away (no frames are asked for then), stops drawing; leaving
 * the screen frees the template.
 */
@Composable
internal fun DynLivePlayer(item: GifItem, modifier: Modifier = Modifier) {
    val controller = DynThumbs.source ?: return
    val id = controller.liveId(item) ?: return
    var px by remember { mutableIntStateOf(0) }
    var frame by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, px) {
        if (px <= 0) return@LaunchedEffect
        val size = px.coerceIn(LIVE_MIN_PX, LIVE_MAX_PX)
        val prepared = controller.livePrepared(id) ?: return@LaunchedEffect
        // Two bitmaps: one on screen while the next frame is drawn into the other.
        val buffers = arrayOf(
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888),
        )
        try {
            val length = prepared.info.durationMs.coerceAtLeast(1).toLong()
            // The loop is entered at the still, the frame the cell already shows.
            val offset = (prepared.posterProgress * length).toLong()
            val start = withFrameNanos { it } / NANOS_PER_MS
            var next = 0
            while (true) {
                val now = withFrameNanos { it } / NANOS_PER_MS
                val progress = ((now - start + offset) % length) / length.toFloat()
                val target = buffers[next]
                withContext(LiveDraw) { prepared.draw(progress, target) }
                frame = target.asImageBitmap()
                next = 1 - next
                delay(LIVE_FRAME_MS)
            }
        } finally {
            withContext(NonCancellable + LiveDraw) { prepared.close() }
        }
    }
    Box(modifier.onSizeChanged { px = maxOf(it.width, it.height) }) {
        frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
    }
}

/** At most two cells draw at the same moment, whatever else the keyboard is doing. */
private val LiveDraw = Dispatchers.Default.limitedParallelism(2)

private const val LIVE_MIN_PX = 96
private const val LIVE_MAX_PX = 320

/** About 20 frames a second: smooth in a cell, at a share of the cost of the full rate. */
private const val LIVE_FRAME_MS = 50L
private const val NANOS_PER_MS = 1_000_000L

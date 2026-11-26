package com.wasimaster.wmkeyboard.app

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete

/** One line drawn on the canvas: its points, ink and width in pixels. */
private class InkStroke(val points: MutableList<Offset>, val colour: Color, val width: Float)

/**
 * A drawing for a note (#1586): a white page the size of the screen, a pen
 * in six inks and three widths, Undo and Clear. Done hands back the page as
 * a bitmap the size of the canvas, white behind the ink so it reads on any
 * theme and in any other app; the caller stores it as one of the note's
 * pictures.
 */
@Composable
internal fun NoteDrawingDialog(onDismiss: () -> Unit, onSave: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<InkStroke>() }
    var colour by remember { mutableStateOf(INKS.first()) }
    val density = LocalDensity.current
    var width by remember { mutableFloatStateOf(with(density) { 3.dp.toPx() }) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Bumped on every point so the canvas redraws while a line is drawn.
    var tick by remember { mutableStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
                    Text(
                        stringResource(R.string.notes_rich_drawing),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = { strokes.removeLastOrNull(); tick++ }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.notes_rich_undo))
                    }
                    IconButton(onClick = { strokes.clear(); tick++ }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.notes_rich_drawing_clear))
                    }
                    TextButton(
                        onClick = { onSave(render(strokes, canvasSize)) },
                        enabled = strokes.isNotEmpty() && canvasSize.width > 0,
                    ) { Text(stringResource(CommonR.string.common_done)) }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                ) {
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { canvasSize = it }
                            .pointerInput(Unit) {
                                detectTapGestures { at ->
                                    strokes += InkStroke(mutableListOf(at, at + Offset(0.1f, 0.1f)), colour, width)
                                    tick++
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { at ->
                                        strokes += InkStroke(mutableListOf(at), colour, width)
                                        tick++
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        strokes.lastOrNull()?.points?.add(change.position)
                                        tick++
                                    },
                                )
                            },
                    ) {
                        // Read so each new point redraws the page.
                        if (tick < 0) return@Canvas
                        for (stroke in strokes) {
                            drawPath(
                                pathOf(stroke.points),
                                stroke.colour,
                                style = Stroke(width = stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val names = inkNames()
                    INKS.forEachIndexed { i, ink ->
                        val on = ink == colour
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .padding(if (on) 5.dp else 3.dp)
                                .clip(CircleShape)
                                .background(ink)
                                .clickable { colour = ink }
                                .semantics { contentDescription = names[i] },
                        )
                    }
                    for ((dp, label) in listOf(2.dp to R.string.notes_rich_pen_fine, 4.dp to R.string.notes_rich_pen_medium, 8.dp to R.string.notes_rich_pen_thick)) {
                        val px = with(density) { dp.toPx() }
                        val on = kotlin.math.abs(px - width) < 0.5f
                        val description = stringResource(label)
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { width = px }
                                .semantics { contentDescription = description },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(dp + 2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                        }
                    }
                }
            }
        }
    }
}

private fun pathOf(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    // Halfway points as the ends of quadratic curves: a smooth line through the samples.
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        quadraticTo(prev.x, prev.y, (prev.x + cur.x) / 2, (prev.y + cur.y) / 2)
    }
    lineTo(points.last().x, points.last().y)
}

/** The strokes on a white page the size of the canvas. */
private fun render(strokes: List<InkStroke>, size: IntSize): Bitmap {
    val bitmap = Bitmap.createBitmap(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (stroke in strokes) {
        paint.color = stroke.colour.toArgb()
        paint.strokeWidth = stroke.width
        val path = android.graphics.Path()
        val points = stroke.points
        if (points.isEmpty()) continue
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            path.quadTo(prev.x, prev.y, (prev.x + cur.x) / 2, (prev.y + cur.y) / 2)
        }
        path.lineTo(points.last().x, points.last().y)
        canvas.drawPath(path, paint)
    }
    return bitmap
}

/** Inks that read on the white page: black, red, blue, green, orange, purple. */
private val INKS = listOf(Color(0xFF212121), Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA))

@Composable
private fun inkNames(): List<String> = listOf(
    stringResource(R.string.notes_rich_ink_black),
    stringResource(R.string.notes_rich_ink_red),
    stringResource(R.string.notes_rich_ink_blue),
    stringResource(R.string.notes_rich_ink_green),
    stringResource(R.string.notes_rich_ink_orange),
    stringResource(R.string.notes_rich_ink_purple),
)

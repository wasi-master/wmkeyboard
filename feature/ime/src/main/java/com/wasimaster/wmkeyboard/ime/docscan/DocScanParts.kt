package com.wasimaster.wmkeyboard.ime.docscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.app.DocScanPages
import com.wasimaster.wmkeyboard.core.docscan.DocScanner
import com.wasimaster.wmkeyboard.core.docscan.PageCorners
import com.wasimaster.wmkeyboard.core.docscan.PageFilter
import com.wasimaster.wmkeyboard.ime.R
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// What the built-in document scanner's two faces share: the full screen
// (PageScanScreen, in PageScanActivity) and the keyboard's own panel
// (DocScanPanel). The same detector, corner editor, clean-up and saving, so
// a page comes out the same from either.

/**
 * The longest side a photo is scanned at: about 5 MP, A4 at some 220 dpi,
 * which keeps print sharp and three full-size bitmaps of it (photo, page,
 * filtered page) well inside an app's heap.
 */
internal const val MAX_PHOTO_SIDE = 2560

/** How often the viewfinder looks for the page, at most. */
private const val LIVE_INTERVAL_MS = 120L

/** A photo taken or picked, and where the page sits in it. */
internal class Shot(val photo: Bitmap, val detected: PageCorners?, val corners: PageCorners) {
    fun withCorners(corners: PageCorners) = Shot(photo, detected, corners)
}

/** [photo] with the page the detector finds in it, or a frame a little inside its edges when it finds none. */
internal suspend fun shotOf(photo: Bitmap): Shot {
    val found = withContext(Dispatchers.Default) { DocScanner.detect(photo) }?.first
    val inset = min(photo.width, photo.height) * 0.06f
    return Shot(photo, found, found ?: PageCorners.whole(photo.width, photo.height, inset))
}

/** [page] as the [index]th JPEG of this scan in [DocScanPages.dir], or null when it would not write. */
internal suspend fun savePage(context: Context, page: Bitmap, index: Int): File? = withContext(Dispatchers.IO) {
    runCatching {
        File(DocScanPages.dir(context), "SCAN_${System.currentTimeMillis()}_$index.jpg").also { f ->
            f.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }.getOrNull()
}

/** The page the viewfinder sees, in the upright frame's pixels. */
internal class LiveQuad(val corners: PageCorners, val width: Int, val height: Int)

/** One 4:3 shape for all three streams, so the viewfinder, the frames the outline is found in and the photo frame the same scene. */
private val fourThree = AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)

internal fun scanImageCapture(): ImageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(fourThree)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(MAX_PHOTO_SIDE, MAX_PHOTO_SIDE * 3 / 4),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build(),
    )
    .build()

internal fun scanPreview(): Preview = Preview.Builder()
    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
    .build()

/**
 * Small frames for the live outline, looked at every [LIVE_INTERVAL_MS] at
 * most; [onLive] hears where the page is, or null once it has been gone for
 * a few frames running.
 */
internal fun scanAnalysis(executor: java.util.concurrent.Executor, onLive: (LiveQuad?) -> Unit): ImageAnalysis {
    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(fourThree)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
    var last = 0L
    var misses = 0
    analysis.setAnalyzer(executor) { proxy ->
        proxy.use {
            val now = System.currentTimeMillis()
            if (now - last < LIVE_INTERVAL_MS) return@use
            last = now
            val frame = it.toBitmap()
            val rotation = it.imageInfo.rotationDegrees
            val found = DocScanner.detect(frame)?.first
            val (w, h) = if (rotation % 180 == 0) frame.width to frame.height else frame.height to frame.width
            if (found != null) {
                misses = 0
                onLive(LiveQuad(found.rotated(rotation, frame.width, frame.height), w, h))
            } else if (++misses >= 3) {
                // A frame or two without it is a hand shaking, not the page gone.
                onLive(null)
            }
            frame.recycle()
        }
    }
    return analysis
}

/**
 * The page the viewfinder sees, drawn over it. The preview fills the box
 * (FILL_CENTER) from the same 4:3 frame the outline was found in, so one
 * scale and centring offset map the one onto the other.
 */
@Composable
internal fun LiveOutline(live: LiveQuad?, accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val quad = live ?: return@Canvas
        val s = max(size.width / quad.width, size.height / quad.height)
        val ox = (size.width - quad.width * s) / 2f
        val oy = (size.height - quad.height * s) / 2f
        val path = Path().apply {
            for (i in 0 until 4) {
                val x = ox + quad.corners.x(i) * s
                val y = oy + quad.corners.y(i) * s
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, accent.copy(alpha = 0.22f))
        drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
    }
}

/**
 * The photo with the page's four corners to drag, everything outside them
 * dimmed, and a loupe while a corner moves: the finger covers exactly the
 * spot it is placing. A fold-over quad turns the outline red.
 */
@Composable
internal fun CornerEditor(
    shot: Shot,
    accent: Color,
    onCorners: (PageCorners) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo = shot.photo
    val image = remember(photo) { photo.asImageBitmap() }
    val density = LocalDensity.current
    val corners by rememberUpdatedState(shot.corners)
    val moveTo by rememberUpdatedState(onCorners)
    var dragging by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val s = min(boxW / photo.width, boxH / photo.height)
        val ox = (boxW - photo.width * s) / 2f
        val oy = (boxH - photo.height * s) / 2f
        val grab = with(density) { 44.dp.toPx() }

        Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(s, ox, oy) {
                    detectDragGestures(
                        onDragStart = { at ->
                            val c = corners
                            dragging = (0 until 4)
                                .minByOrNull { hypot(ox + c.x(it) * s - at.x, oy + c.y(it) * s - at.y) }
                                ?.takeIf { hypot(ox + c.x(it) * s - at.x, oy + c.y(it) * s - at.y) <= grab }
                                ?: -1
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                        onDrag = { change, drag ->
                            val i = dragging
                            if (i >= 0) {
                                change.consume()
                                val c = corners
                                val x = (c.x(i) + drag.x / s).coerceIn(0f, photo.width - 1f)
                                val y = (c.y(i) + drag.y / s).coerceIn(0f, photo.height - 1f)
                                moveTo(c.moved(i, x, y))
                            }
                        },
                    )
                },
        ) {
            val c = shot.corners
            val points = (0 until 4).map { Offset(ox + c.x(it) * s, oy + c.y(it) * s) }
            val quad = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            // Everything outside the page dimmed, the page left bright.
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(ox, oy, ox + photo.width * s, oy + photo.height * s))
                addPath(quad)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.5f))
            val line = if (c.convex) accent else Color(0xFFE57373)
            drawPath(quad, line, style = Stroke(width = 2.dp.toPx()))
            points.forEachIndexed { i, p ->
                drawCircle(Color.White, radius = (if (i == dragging) 14 else 11).dp.toPx(), center = p)
                drawCircle(line, radius = (if (i == dragging) 11 else 8).dp.toPx(), center = p)
            }
            val i = dragging
            if (i >= 0) {
                // Never taller than the box: the keyboard's panel is short.
                val loupe = min(120.dp.toPx(), size.height * 0.6f)
                val zoom = 2.5f
                val half = (loupe / 2f / zoom / s).roundToInt().coerceAtLeast(1)
                val srcW = min(half * 2, photo.width)
                val srcH = min(half * 2, photo.height)
                val left = (c.x(i).roundToInt() - half).coerceIn(0, photo.width - srcW)
                val top = (c.y(i).roundToInt() - half).coerceIn(0, photo.height - srcH)
                // Opposite the finger, so the loupe never sits under it.
                val lx = if (points[i].x > size.width / 2) 12.dp.toPx() else size.width - loupe - 12.dp.toPx()
                val ly = 12.dp.toPx()
                val circle = Path().apply { addOval(Rect(lx, ly, lx + loupe, ly + loupe)) }
                clipPath(circle) {
                    drawImage(
                        image,
                        srcOffset = IntOffset(left, top),
                        srcSize = IntSize(srcW, srcH),
                        dstOffset = IntOffset(lx.roundToInt(), ly.roundToInt()),
                        dstSize = IntSize(loupe.roundToInt(), loupe.roundToInt()),
                    )
                }
                drawPath(circle, Color.White, style = Stroke(width = 2.dp.toPx()))
                val cx = lx + (c.x(i) - left) / srcW * loupe
                val cy = ly + (c.y(i) - top) / srcH * loupe
                drawLine(line, Offset(cx - 10.dp.toPx(), cy), Offset(cx + 10.dp.toPx(), cy), 1.5.dp.toPx())
                drawLine(line, Offset(cx, cy - 10.dp.toPx()), Offset(cx, cy + 10.dp.toPx()), 1.5.dp.toPx())
            }
        }
    }
}

internal fun filterLabel(filter: PageFilter): Int = when (filter) {
    PageFilter.ORIGINAL -> R.string.ime_docscan_filter_original
    PageFilter.COLOUR -> R.string.ime_docscan_filter_colour
    PageFilter.GREY -> R.string.ime_docscan_filter_grey
    PageFilter.BLACK_WHITE -> R.string.ime_docscan_filter_bw
}

/** [page] turned a quarter clockwise [turns] times. */
internal fun turned(page: Bitmap, turns: Int): Bitmap {
    if (turns % 4 == 0) return page
    val matrix = Matrix().apply { postRotate(90f * turns) }
    return Bitmap.createBitmap(page, 0, 0, page.width, page.height, matrix, true)
}

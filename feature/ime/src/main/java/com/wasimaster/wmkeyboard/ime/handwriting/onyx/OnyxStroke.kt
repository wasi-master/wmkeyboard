package com.wasimaster.wmkeyboard.ime.handwriting.onyx

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.wasimaster.wmkeyboard.core.handwriting.HwPoint
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import kotlin.math.abs

internal data class OnyxPenStyle(val color: Color, val widthDp: Float)

/** Which Compose overlay buttons sit inside the canvas and need exclusion rects. */
internal data class OnyxChromeRegions(
    val languageChip: Boolean = false,
    val fullScreenButton: Boolean = false,
)

/** Directly readable at gesture boundaries; changing input ownership is not a UI update. */
internal class OnyxInputState {
    var rawCaptureActive = false
    var fallbackStylusActive = false
}

/**
 * Reconstructs one recognition [HwStroke] per stylus gesture across BOOX
 * firmware variants:
 * - Standard Wacom/EMR streams: `begin` -> `move`* -> cumulative `pointList` -> `end`
 * - Chunked mid-stroke `pointList` batches: merged without ending the gesture early
 * - Short taps / dots with no `pointList`: finalized from `begin` + `end`
 * - Late duplicate `pointList` arriving right after `end`: ignored
 * - Buffered-only firmwares emitting `pointList` without `begin`/`end`: emitted directly
 */
internal class OnyxStrokeCollector {
    var inStroke: Boolean = false
        private set

    private val movePoints = ArrayList<HwPoint>()
    private val listPoints = ArrayList<HwPoint>()
    private var lastSubmitted: List<HwPoint>? = null

    fun begin(point: HwPoint, canvasSize: IntSize, fallbackTimeMs: Long = 0L): HwStroke? {
        val unfinished = if (inStroke) finishCurrent(null, canvasSize, fallbackTimeMs) else null
        inStroke = true
        movePoints.clear()
        listPoints.clear()
        movePoints += point
        return unfinished
    }

    fun move(point: HwPoint) {
        if (!inStroke) {
            inStroke = true
            movePoints.clear()
            listPoints.clear()
        }
        movePoints += point
    }

    fun onPointList(points: List<HwPoint>, canvasSize: IntSize, fallbackTimeMs: Long = 0L): HwStroke? {
        if (points.isEmpty()) return null
        if (inStroke) {
            mergeIntoList(points)
            return null
        }
        if (isDuplicateOfLast(points)) return null
        val snapshot = points.toList()
        lastSubmitted = snapshot
        return normalizedOnyxStroke(snapshot, canvasSize, fallbackTimeMs)
    }

    fun end(point: HwPoint?, canvasSize: IntSize, fallbackTimeMs: Long = 0L): HwStroke? {
        if (!inStroke) return null
        return finishCurrent(point, canvasSize, fallbackTimeMs)
    }

    fun cancel() {
        inStroke = false
        movePoints.clear()
        listPoints.clear()
    }

    private fun mergeIntoList(incoming: List<HwPoint>) {
        if (listPoints.isEmpty() ||
            (incoming.size >= listPoints.size && sameAnchor(listPoints.first(), incoming.first()))
        ) {
            listPoints.clear()
            listPoints.addAll(incoming)
            return
        }
        val start = if (sameAnchor(listPoints.last(), incoming.first())) 1 else 0
        for (i in start until incoming.size) {
            listPoints += incoming[i]
        }
    }

    private fun finishCurrent(endPoint: HwPoint?, canvasSize: IntSize, fallbackTimeMs: Long): HwStroke? {
        inStroke = false
        if (endPoint != null && (movePoints.isEmpty() || !exactCoords(movePoints.last(), endPoint))) {
            movePoints += endPoint
        }
        val chosen = if (listPoints.isNotEmpty() && listPoints.size >= movePoints.size) {
            if (endPoint != null && !exactCoords(listPoints.last(), endPoint)) {
                listPoints + endPoint
            } else {
                listPoints.toList()
            }
        } else {
            movePoints.toList()
        }
        movePoints.clear()
        listPoints.clear()
        if (chosen.isEmpty()) return null
        lastSubmitted = chosen
        return normalizedOnyxStroke(chosen, canvasSize, fallbackTimeMs)
    }

    private fun isDuplicateOfLast(candidate: List<HwPoint>): Boolean {
        val prev = lastSubmitted ?: return false
        if (prev.isEmpty() || candidate.isEmpty()) return false
        val sameStart = sameAnchor(prev.first(), candidate.first())
        val sameEnd = sameAnchor(prev.last(), candidate.last())
        val closeInTime = abs(candidate.first().t - prev.first().t) <= DUPLICATE_WINDOW_MS
        return sameStart && sameEnd && closeInTime
    }

    companion object {
        private const val ANCHOR_EPSILON_PX = 4f
        private const val DUPLICATE_WINDOW_MS = 250L

        private fun exactCoords(a: HwPoint, b: HwPoint): Boolean = a.x == b.x && a.y == b.y

        private fun sameAnchor(a: HwPoint, b: HwPoint): Boolean =
            abs(a.x - b.x) <= ANCHOR_EPSILON_PX && abs(a.y - b.y) <= ANCHOR_EPSILON_PX
    }
}

private const val SYNTHETIC_POINT_STEP_MS = 5L

/**
 * Clamps coordinates to [canvasSize], repairs missing/non-monotonic firmware
 * timestamps, and duplicates single-point taps so recognizers see a stroke.
 */
internal fun normalizedOnyxStroke(
    points: List<HwPoint>,
    canvasSize: IntSize,
    fallbackBaseTimeMs: Long = 0L,
): HwStroke? {
    if (points.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) return null
    val maxX = canvasSize.width.toFloat()
    val maxY = canvasSize.height.toFloat()
    val normalized = ArrayList<HwPoint>(points.size + 1)
    var currentTime = if (points.first().t > 0L) points.first().t else fallbackBaseTimeMs
    for (index in points.indices) {
        val raw = points[index]
        val timestamp = if (index == 0) {
            currentTime
        } else if (raw.t > 0L && raw.t >= currentTime) {
            raw.t
        } else {
            currentTime + SYNTHETIC_POINT_STEP_MS
        }
        currentTime = timestamp
        normalized += HwPoint(
            x = raw.x.coerceIn(0f, maxX),
            y = raw.y.coerceIn(0f, maxY),
            t = timestamp,
        )
    }
    if (normalized.size == 1) {
        val first = normalized.first()
        normalized += first.copy(t = first.t + 1L)
    }
    return HwStroke(normalized)
}

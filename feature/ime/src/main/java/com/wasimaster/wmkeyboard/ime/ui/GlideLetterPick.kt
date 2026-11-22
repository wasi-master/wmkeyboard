package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.FlickArm
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.flickArm
import com.wasimaster.wmkeyboard.core.settings.GlidePauseLetters
import com.wasimaster.wmkeyboard.ime.GlidePin
import com.wasimaster.wmkeyboard.ime.GlidePins
import com.wasimaster.wmkeyboard.ime.PinnedKeys
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Letters picked off a key in the middle of a glide (#1519).
 *
 * A glide passes over a key's flick arms and long-press letters without being
 * able to type them: the decoder's grid holds what a key types on a tap and
 * the letters its long press spells, never its arms. With the setting on, a
 * pause on a key that has letters to offer puts them around the finger, on
 * the 3×3 pad the key's flick cross uses, each arm where it points. Sliding
 * onto one picks it, and the stroke carries on to the next key without
 * lifting.
 *
 * A pick does not type anything at once. The stroke is still one word, read
 * at the lift the way every glide is, with two changes: the picked letter is
 * on the grid, at its key's centre, and only words that have it may be typed
 * (see [GlidePins] and the service's decode). The pause before the pick stays
 * in the stroke, which is the decoder's own mark for "this key's letter is in
 * the word" (the pause mark of "Marking a letter as you glide"), so the
 * letter is placed on the key it came from. The walk out to the letter and on
 * to the next bend is cut from the stroke, as the walk to and from the Shift
 * key is (#163): it is an instruction, not letters, and read as letters it
 * would spell whatever lies between the key and the pick.
 *
 * Kept out of KeyboardScreen.kt, whose glide loop is near ART's method-size
 * limit; the loop only asks this file what to do.
 */

/** One letter a pause offers, the cell of the pad it sits in, and the text it came from. */
internal data class PauseLetter(val text: String, val codePoint: Int, val direction: FlickDirection)

/**
 * The letters a pause on [key] offers, letters only: the key's flick arms
 * where they point, or its long-press letters laid out above the finger
 * first. Empty for a key that is not a letter key, or has nothing to offer
 * but its own letter.
 */
internal fun pauseLettersOf(key: Key?, source: GlidePauseLetters): List<PauseLetter> {
    val base = baseLetterOf(key) ?: return emptyList()
    key ?: return emptyList()
    val out = ArrayList<PauseLetter>()
    val seen = HashSet<Int>().apply { add(base) }
    when (source) {
        GlidePauseLetters.FLICKS -> for (direction in FlickDirection.entries) {
            val text = (key.flickArm(direction) as? FlickArm.Text)?.text ?: continue
            val cp = GlidePins.letterOf(text) ?: continue
            if (seen.add(cp)) out += PauseLetter(text, cp, direction)
        }
        GlidePauseLetters.ALTERNATES -> for (text in key.longPress) {
            if (out.size == PauseLetterOrder.size) break
            val cp = GlidePins.letterOf(text) ?: continue
            if (seen.add(cp)) out += PauseLetter(text, cp, PauseLetterOrder[out.size])
        }
    }
    return out
}

/** The letter [key] types on a tap, lowercased, or null when it types no single letter. */
internal fun baseLetterOf(key: Key?): Int? {
    if (key == null || key.action != KeyAction.Text) return null
    return GlidePins.letterOf(key.output ?: key.label)
}

/**
 * The cells long-press letters fill, in order: above the finger first, where
 * the hand does not cover them, then beside it, then below.
 */
internal val PauseLetterOrder: List<FlickDirection> = listOf(
    FlickDirection.UP,
    FlickDirection.UP_LEFT,
    FlickDirection.UP_RIGHT,
    FlickDirection.LEFT,
    FlickDirection.RIGHT,
    FlickDirection.DOWN_LEFT,
    FlickDirection.DOWN_RIGHT,
    FlickDirection.DOWN,
)

/** [pauseLetterAt]: the finger is on the centre or an empty cell, still choosing. */
internal const val PAD_CHOOSING = -1

/** [pauseLetterAt]: the finger has left the pad without landing on a letter. */
internal const val PAD_LEFT = -2

/**
 * Which of the letters laid out in [directions] the finger is on, [dx], [dy]
 * from where it paused, on a pad whose cells are [cellW] by [cellH]: its
 * index, [PAD_CHOOSING] on the centre or an empty cell, or [PAD_LEFT] once
 * it is past the pad's outer edge.
 */
internal fun pauseLetterAt(directions: List<FlickDirection>, dx: Float, dy: Float, cellW: Float, cellH: Float): Int {
    if (cellW <= 0f || cellH <= 0f) return PAD_LEFT
    val across = dx / cellW
    val down = dy / cellH
    if (abs(across) >= PAD_HALF_CELLS || abs(down) >= PAD_HALF_CELLS) return PAD_LEFT
    val col = across.roundToInt().coerceIn(-1, 1)
    val row = down.roundToInt().coerceIn(-1, 1)
    val direction = FlickDirection.gridOrder[(row + 1) * 3 + (col + 1)] ?: return PAD_CHOOSING
    val index = directions.indexOf(direction)
    return if (index >= 0) index else PAD_CHOOSING
}

/** Half the pad, in cells: the centre cell and one each way. */
private const val PAD_HALF_CELLS = 1.5f

/** A pad cell, as a share of the key cell it opened over. */
internal const val PAD_CELL_SHARE = 0.9f

/** A letter's tile, as a share of its pad cell. */
private const val PAD_TILE_SHARE = 0.86f

/**
 * The pad's live state: which letters are up and where. [letters] is snapshot
 * state, read by [GlideLetterPadOverlay]; the geometry is plain fields, read
 * by the pointer loop at touch-report rate, and written before [letters] so
 * the overlay never draws a pad at the last one's place.
 *
 * Lives on [GlidePickerState], which the pointer loop and the stroke overlay
 * already share, so neither needed another parameter.
 */
@Stable
internal class GlideLetterPadState {

    /** The letters up now; empty while the pad is closed. */
    var letters by mutableStateOf<List<PauseLetter>>(emptyList())
        private set

    /** Where the finger paused, in the grid's space. */
    var anchorX = 0f
        private set
    var anchorY = 0f
        private set

    /** A pad cell's size in pixels. */
    var cellW = 0f
        private set
    var cellH = 0f
        private set

    /** The letter the paused-on key types itself. */
    var base = 0
        private set

    private var directions: List<FlickDirection> = emptyList()

    // What the key under the current pause offers, read once per pause rather
    // than per event: a pause is one still point, and its key cannot change.
    private var offeredSince = Long.MIN_VALUE
    private var offered: List<PauseLetter> = emptyList()
    private var offeredBase = 0

    val isOpen: Boolean
        get() = letters.isNotEmpty()

    /**
     * Whether the pause that began at [since] is on a key with letters to
     * offer. [keyAt] is asked only when the pause is a new one.
     */
    fun offersAt(since: Long, source: GlidePauseLetters, keyAt: () -> Key?): Boolean {
        if (since != offeredSince) {
            offeredSince = since
            val key = keyAt()
            offered = pauseLettersOf(key, source)
            offeredBase = baseLetterOf(key) ?: 0
        }
        return offered.isNotEmpty()
    }

    /**
     * Puts the offered letters up around [x], [y], on cells sized from the key
     * cell [keyW] by [keyH] the finger paused in. False, and nothing shown,
     * when there was nothing to offer.
     */
    fun open(x: Float, y: Float, keyW: Float, keyH: Float): Boolean {
        if (offered.isEmpty() || keyW <= 0f || keyH <= 0f) return false
        anchorX = x
        anchorY = y
        cellW = keyW * PAD_CELL_SHARE
        cellH = keyH * PAD_CELL_SHARE
        base = offeredBase
        directions = offered.map { it.direction }
        letters = offered
        return true
    }

    /** One move while open: the index of the letter landed on, [PAD_CHOOSING] or [PAD_LEFT]. */
    fun track(x: Float, y: Float): Int = pauseLetterAt(directions, x - anchorX, y - anchorY, cellW, cellH)

    /** Takes the pad down and forgets the pause, ready for the next one. */
    fun close() {
        if (letters.isNotEmpty()) letters = emptyList()
        directions = emptyList()
        offeredSince = Long.MIN_VALUE
        offered = emptyList()
    }
}

/**
 * One stroke's picks: the letters, and where in the word segment being drawn
 * each walk out to a letter begins, so the walk can be cut from it.
 *
 * Positions are indices into the screen's segment buffer, which a spacebar
 * crossing or a dropped word replaces with a new one; the buffer's first
 * sample says which segment the positions belong to, so a pick never cuts a
 * later word's samples. The picks themselves are the stroke's, every segment's,
 * and ride to the service on the key list ([PinnedKeys]).
 */
internal class GlideLetterPicks {

    private var segmentFirst: GesturePoint? = null
    private val exits = ArrayList<Int>()
    private val targets = ArrayList<GesturePoint>()
    private val pins = ArrayList<GlidePin>()

    /**
     * Records [letter] picked off the key [pad] is open over, the finger now
     * at [x], [y], in the segment [seg] whose last sample is the pause.
     * Returns the stroke's grid with every pick so far on it, built from
     * [keys], the grid the stroke has been decoded on.
     */
    fun pick(
        seg: List<GesturePoint>,
        letter: PauseLetter,
        pad: GlideLetterPadState,
        x: Float,
        y: Float,
        keys: List<KeyCenter>,
    ): List<KeyCenter> {
        val first = seg.firstOrNull() ?: return keys
        if (first !== segmentFirst) {
            segmentFirst = first
            exits.clear()
            targets.clear()
        }
        exits.add(seg.size)
        targets.add(GesturePoint(x, y))
        val base = (keys as? PinnedKeys)?.base ?: keys
        val home = base.firstOrNull { Character.toLowerCase(it.codePoint) == pad.base }
        pins.add(
            GlidePin(
                codePoint = letter.codePoint,
                base = pad.base,
                x = home?.x ?: pad.anchorX,
                y = home?.y ?: pad.anchorY,
                segmentStart = first.t,
                t = seg.last().t,
            ),
        )
        return GlidePins.pinned(base, pins)
    }

    /**
     * [seg] with the walks out to this segment's picks cut, and [shiftCuts],
     * indices into [seg], moved to match. Unchanged for a segment with no pick.
     */
    fun cut(seg: List<GesturePoint>, shiftCuts: List<Int>, keyWidth: Float): Pair<List<GesturePoint>, List<Int>> {
        val first = seg.firstOrNull()
        if (exits.isEmpty() || first == null || first !== segmentFirst || keyWidth <= 0f) {
            return seg.toList() to shiftCuts
        }
        return cutPickExits(seg, exits.toIntArray(), targets, shiftCuts, keyWidth * PICK_EXIT_TOLERANCE)
    }
}

/** How far off a walk's chord a point may sit before the walk has bent, in key widths; the Shift detour's. */
internal const val PICK_EXIT_TOLERANCE = 0.35f

/**
 * [points] with each pick's walk cut out: from [exits]' index, the first
 * sample after the pick, to the first bend of the path leaving [targets]'
 * point, where the finger turned toward the word's next letter. The pause
 * before each pick stays, so the key the letter came from keeps its mark; the
 * time spent choosing does not, and the jump across the cut takes the time
 * its distance would at the stroke's own pace. A cut never reaches the next
 * pick's pause. [shiftCuts], indices into [points], come back as indices into
 * the result: a Shift crossing inside a cut lands where the cut ends.
 *
 * The walk is found the way the Shift detour's is (`GlideShiftDetour`): a
 * chord from the letter to the furthest sample, split at the sample furthest
 * off it while that is more than [tolerancePx], so a straight walk on to the
 * next letter is cut whole and a bend keeps everything from the bend on.
 */
@Suppress("CyclomaticComplexMethod")
internal fun cutPickExits(
    points: List<GesturePoint>,
    exits: IntArray,
    targets: List<GesturePoint>,
    shiftCuts: List<Int>,
    tolerancePx: Float,
): Pair<List<GesturePoint>, List<Int>> {
    val n = points.size
    if (n < 2 || exits.isEmpty()) return points.toList() to shiftCuts
    val keep = BooleanArray(n) { true }
    val boundary = BooleanArray(n)
    for (k in exits.indices) {
        val start = exits[k]
        if (start < 1 || start >= n) continue
        val target = targets.getOrNull(k) ?: continue
        // Up to, not into, the next pick's pause: that sample is its key's mark.
        val limit = if (k + 1 < exits.size) minOf(exits[k + 1] - 1, n - 1) else n - 1
        if (limit < start) continue
        val bend = firstBend(points, start, limit, target, tolerancePx)
        for (i in start until bend) keep[i] = false
        boundary[bend] = true
    }
    val rawSpan = (points.last().t - points.first().t).toFloat()
    val speed = if (rawSpan > 0f) arcOf(points) / rawSpan else 0f
    val out = ArrayList<GesturePoint>(n)
    val outIndex = IntArray(n) { -1 }
    var clockShift = 0L
    var previous: GesturePoint? = null
    for (i in 0 until n) {
        if (!keep[i]) continue
        val p = points[i]
        val before = previous
        if (before != null && boundary[i] && speed > 0f) {
            val elapsed = p.t - clockShift - before.t
            val wanted = (distance(before, p) / speed).toLong()
            if (elapsed > wanted) clockShift += elapsed - wanted
        }
        val placed = if (clockShift == 0L) p else GesturePoint(p.x, p.y, p.t - clockShift)
        outIndex[i] = out.size
        out.add(placed)
        previous = placed
    }
    val moved = shiftCuts.map { cut ->
        var at = cut
        while (at < n && !keep[at]) at++
        if (at >= n) out.size else outIndex[at]
    }
    return out to moved
}

/**
 * The index the walk starting at [start] from [from] ends on: its first bend,
 * no later than [end]. [end] itself when the walk runs straight to it.
 */
private fun firstBend(points: List<GesturePoint>, start: Int, end: Int, from: GesturePoint, tolerance: Float): Int {
    var to = end
    var bend = end
    while (true) {
        var split = -1
        var off = -1f
        for (i in start until to) {
            val d = offChord(points[i], from, points[to])
            if (d > off) {
                off = d
                split = i
            }
        }
        if (split < 0 || off <= tolerance) return bend
        bend = split
        to = split
    }
}

private fun offChord(p: GesturePoint, a: GesturePoint, b: GesturePoint): Float {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val len2 = vx * vx + vy * vy
    if (len2 <= 0f) return distance(p, a)
    val t = (((p.x - a.x) * vx + (p.y - a.y) * vy) / len2).coerceIn(0f, 1f)
    val dx = p.x - (a.x + t * vx)
    val dy = p.y - (a.y + t * vy)
    return sqrt(dx * dx + dy * dy)
}

private fun arcOf(points: List<GesturePoint>): Float {
    var total = 0f
    for (i in 1 until points.size) total += distance(points[i - 1], points[i])
    return total
}

private fun distance(a: GesturePoint, b: GesturePoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * The pad over the grid while a pause offers letters: each letter on its own
 * tile in the cell it is picked from, around where the finger stopped. Its
 * own window, pinned over the grid with room above it, the way the word
 * picker's is, so a pad over the top row is not cut off; the window takes no
 * touches, and the finger stays with the glide loop throughout.
 */
@Composable
internal fun GlideLetterPadOverlay(pad: GlideLetterPadState, gridSize: IntSize) {
    val letters = pad.letters
    if (letters.isEmpty()) return
    val theme = LocalKbTheme.current
    val density = LocalDensity.current
    val headroomPx = (pad.cellH * PAD_HALF_CELLS).roundToInt()
    val tileW = (pad.cellW * PAD_TILE_SHARE).roundToInt().coerceAtLeast(1)
    val tileH = (pad.cellH * PAD_TILE_SHARE).roundToInt().coerceAtLeast(1)
    val fontSize = with(density) { (tileH * PAD_TEXT_SHARE).toSp() }
    Popup(
        popupPositionProvider = remember(headroomPx) { GridOverlayPositionProvider(headroomPx) },
        properties = PreviewPopupProperties,
    ) {
        Layout(
            content = {
                for (letter in letters) {
                    Surface(
                        color = theme.popup,
                        contentColor = theme.popupText,
                        shape = theme.popupShape(),
                        shadowElevation = elevationFor(theme.popupShapeKind, 4.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = letter.text, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val width = if (gridSize.width > 0) gridSize.width else constraints.maxWidth
            val height = gridSize.height + headroomPx
            val tiles = measurables.map { it.measure(Constraints.fixed(tileW, tileH)) }
            layout(width, height) {
                tiles.forEachIndexed { index, tile ->
                    val at = FlickDirection.gridOrder.indexOf(letters[index].direction)
                    val col = at % 3 - 1
                    val row = at / 3 - 1
                    val x = (pad.anchorX + col * pad.cellW - tileW / 2f).roundToInt()
                        .coerceIn(0, (width - tileW).coerceAtLeast(0))
                    val y = (pad.anchorY + row * pad.cellH + headroomPx - tileH / 2f).roundToInt()
                        .coerceIn(0, (height - tileH).coerceAtLeast(0))
                    tile.place(x, y)
                }
            }
        }
    }
}

/** A tile's letter, as a share of the tile's height. */
private const val PAD_TEXT_SHARE = 0.5f

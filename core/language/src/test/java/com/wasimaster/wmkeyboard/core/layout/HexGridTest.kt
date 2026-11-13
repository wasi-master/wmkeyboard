package com.wasimaster.wmkeyboard.core.layout

import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexGridTest {

    private fun letters(text: String) = text.map { Key(it.toString()) }
    private val shift = Key("⇧", action = KeyAction.Shift, width = 1.5f)
    private val delete = Key("⌫", action = KeyAction.Delete, width = 1.5f)
    private val space = Key(" ", action = KeyAction.Space, width = 4f)

    /** The shipped Hex QWERTY's letter rows, on a ten-cell board. */
    private val hexQwerty = listOf(
        listOf(Key("q", width = 1.5f)) + letters("wertyui") + Key("o", width = 1.5f),
        letters("asdfghjklp"),
        listOf(shift) + letters("zxcvbnm") + delete,
        listOf(
            Key("?123", action = KeyAction.Symbols, width = 2f),
            Key(","),
            space,
            Key("."),
            Key("⏎", action = KeyAction.Enter, width = 2f),
        ),
    )

    @Test
    fun `rows alternate between whole and half cell edges`() {
        val board = hexBoard(hexQwerty, 10f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.5f, 0f), board.phases, 0f)
        for (r in 0 until 3) assertTrue("rows $r and ${r + 1} interlock", board.joinedBelow(r))
        assertFalse(board.joinedAbove(0))
        assertFalse(board.joinedBelow(3))
    }

    @Test
    fun `plain QWERTY staggers its home row half a cell, so the two interlock`() {
        val board = hexBoard(listOf(letters("qwertyuiop"), letters("asdfghjkl")), 10f)
        assertArrayEquals(floatArrayOf(0f, 0.5f), board.phases, 0f)
        assertTrue(board.joinedBelow(0))
        // The home row is centred: a starts half a cell in.
        assertEquals(0.5f, board.slotsIn(1).first().x, 0.0001f)
    }

    @Test
    fun `rows on the same edges meet on a straight line`() {
        val board = hexBoard(listOf(letters("qwertyuiop"), letters("asdfghjklz")), 10f)
        assertFalse(board.joinedBelow(0))
    }

    @Test
    fun `a row with no inner edge interlocks with the row above it`() {
        val board = hexBoard(listOf(letters("qwertyuiop"), listOf(space.copy(width = 10f))), 10f)
        assertEquals(0.5f, board.phases[1], 0f)
        assertTrue(board.joinedBelow(0))
    }

    @Test
    fun `key centres sit on the cell centres of their row`() {
        val board = hexBoard(hexQwerty, 10f)
        val w = board.slotsIn(0)[1]
        assertEquals("w", w.key.label)
        // Half-cell rows have their centres on whole cells.
        assertEquals(2f, w.x + w.width / 2f, 0.0001f)
        val s = board.slotsIn(1)[1]
        assertEquals(1.5f, s.x + s.width / 2f, 0.0001f)
        assertEquals(0f, board.centreDistance(0, 2f), 0.0001f)
        assertEquals(0.5f, board.centreDistance(0, 2.5f), 0.0001f)
        assertEquals(0f, board.centreDistance(1, 1.5f), 0.0001f)
    }

    @Test
    fun `half-cell keys start on a cell centre, whole ones on a cell edge`() {
        val board = hexBoard(hexQwerty, 10f)
        val q = board.slotsIn(0).first()
        val w = board.slotsIn(0)[1]
        val a = board.slotsIn(1).first()
        assertEquals(0.5f, board.startPhase(q), 0.0001f)
        assertEquals(0f, board.startPhase(w), 0.0001f)
        assertEquals(0f, board.startPhase(a), 0.0001f)
    }

    @Test
    fun `a touch in the point of a hexagon goes to that key, not the rectangle under it`() {
        val board = hexBoard(hexQwerty, 10f)
        val cell = 36f
        val tops = rowTops(4, 54f)
        val amplitude = hexAmplitude(cell, 54f / 3f)
        // Just inside row 1's rectangle, under the centre of w (x = 2 cells).
        val x = 2.02f * cell
        val y = tops[1] + 1f
        val plain = board.slots[board.slotAt(x, y, tops, cell, 0f)].key.label
        val hex = board.slots[board.slotAt(x, y, tops, cell, amplitude)].key.label
        assertEquals("d", plain)
        assertEquals("w", hex)
        // And under a cell edge of row 0 the row below reaches up instead.
        val up = board.slots[board.slotAt(2.5f * cell, tops[1] - 1f, tops, cell, amplitude)].key.label
        assertEquals("d", up)
    }

    @Test
    fun `the zigzag of the right depth is exactly the nearest cell centre`() {
        // The depth at which the plain Euclidean Voronoi of the centres is the zigzag.
        val cell = 36f
        val pitch = 54f
        assertMatchesNearestCentre(cell, pitch, cell * cell / (4f * pitch), yScale = 1f)
    }

    @Test
    fun `the regular honeycomb is the nearest cell centre in axial space`() {
        // Regular hexagons, stretched to the key height: nearest centre once the
        // vertical axis is scaled back to a regular honeycomb.
        val cell = 36f
        val pitch = 54f
        val amplitude = hexAmplitude(cell, Float.MAX_VALUE)
        val yScale = sqrt(cell * cell / (4f * pitch * amplitude))
        assertMatchesNearestCentre(cell, pitch, amplitude, yScale)
    }

    @Test
    fun `nothing on the board is a dead corner`() {
        val board = hexBoard(hexQwerty, 10f)
        val cell = 36f
        val tops = rowTops(4, 54f)
        val amplitude = hexAmplitude(cell, 18f)
        var y = 0f
        while (y < tops.last()) {
            var x = 0f
            while (x < 10f * cell) {
                assertTrue(board.slotAt(x, y, tops, cell, amplitude) >= 0)
                x += 3f
            }
            y += 3f
        }
    }

    @Test
    fun `a split cuts each row near the middle and moves the right half over`() {
        val board = hexBoard(hexQwerty, 10f, splitGap = 1f)
        assertEquals(11f, board.weight, 0.0001f)
        // Row 0 has half-cell edges, so none is at 5: of 4.5 and 5.5 the tie
        // goes to the right, and y is the first key moved.
        val row0 = board.slotsIn(0)
        val firstMoved0 = row0.first { it.shift > 0f }
        assertEquals("y", firstMoved0.key.label)
        assertEquals(6.5f, firstMoved0.x, 0.0001f)
        // Row 1 (whole-cell edges) cuts at 5, exactly the middle.
        val firstMoved1 = board.slotsIn(1).first { it.shift > 0f }
        assertEquals("h", firstMoved1.key.label)
        assertEquals(6f, firstMoved1.x, 0.0001f)
        // The phases are the rows', and the moved halves keep them.
        assertEquals(0.5f, board.phases[0], 0f)
        val y = firstMoved0
        assertEquals(0f, board.startPhase(y), 0.0001f)
    }

    @Test
    fun `a split cuts a spacebar across the middle in two, or bridges it`() {
        val cut = hexBoard(hexQwerty, 10f, splitGap = 1f).slotsIn(3)
        val spaces = cut.filter { it.key.action == KeyAction.Space }
        assertEquals(2, spaces.size)
        assertEquals("", spaces[0].key.label)
        assertEquals(2f, spaces[0].width, 0.0001f)
        assertEquals(6f, spaces[1].x, 0.0001f)

        val bridged = hexBoard(hexQwerty, 10f, splitGap = 1f, bridgeSpace = true).slotsIn(3)
        val whole = bridged.single { it.key.action == KeyAction.Space }
        assertEquals(5f, whole.width, 0.0001f)
        assertEquals(whole.end, bridged.first { it.key.label == "." }.x, 0.0001f)
    }

    @Test
    fun `the end keys of a short row take its side gaps as touch area`() {
        val board = hexBoard(listOf(letters("qwertyuiop"), letters("asdfghjkl")), 10f)
        val a = board.slotsIn(1).first()
        val l = board.slotsIn(1).last()
        assertEquals(0.5f, a.x, 0.0001f)
        assertEquals(0f, a.touchX, 0.0001f)
        assertEquals(10f, l.touchEnd, 0.0001f)
        val kept = hexBoard(listOf(letters("asdfghjkl")), 10f, extendEdges = false).slotsIn(0).first()
        assertEquals(0.5f, kept.touchX, 0.0001f)
    }

    @Test
    fun `a whole cell's face is a hexagon pointing into both neighbours`() {
        val outline = hexFaceOutline(
            widthCells = 1f, startPhase = 0f, zigTop = true, zigBottom = true,
            faceWidth = 36f, faceHeight = 50f, gapPx = 0f, amplitude = 10f,
        )
        assertArrayEquals(
            floatArrayOf(0f, 5f, 18f, -5f, 36f, 5f, 36f, 45f, 18f, 55f, 0f, 45f),
            outline,
            0.0001f,
        )
    }

    @Test
    fun `a half-cell key at the edge is half a hexagon`() {
        val outline = hexFaceOutline(
            widthCells = 0.5f, startPhase = 0.5f, zigTop = true, zigBottom = true,
            faceWidth = 18f, faceHeight = 50f, gapPx = 0f, amplitude = 10f,
        )
        assertArrayEquals(floatArrayOf(0f, -5f, 18f, 5f, 18f, 45f, 0f, 55f), outline, 0.0001f)
    }

    @Test
    fun `a wide key has a point over every cell it covers`() {
        val outline = hexFaceOutline(
            widthCells = 3f, startPhase = 0f, zigTop = true, zigBottom = false,
            faceWidth = 108f, faceHeight = 50f, gapPx = 0f, amplitude = 10f,
        )
        val peaks = (0 until outline.size / 2).count { outline[it * 2 + 1] < 0f }
        assertEquals(3, peaks)
        // The straight bottom: its two corners and nothing between them.
        assertEquals(50f, outline[outline.size - 1], 0f)
    }

    @Test
    fun `a key gap insets the face without moving its points off the cell centres`() {
        // A 40 px cell with 2 px of gap either side: the point stays at the
        // cell centre, which is 18 px into the 36 px face.
        val outline = hexFaceOutline(
            widthCells = 1f, startPhase = 0f, zigTop = true, zigBottom = true,
            faceWidth = 36f, faceHeight = 50f, gapPx = 2f, amplitude = 10f,
        )
        assertEquals(18f, outline[2], 0.0001f)
        assertEquals(-5f, outline[3], 0.0001f)
        // At the face's side, 2 px in from the cell edge, the notch is 1 px shallower.
        assertEquals(4f, outline[1], 0.0001f)
    }

    @Test
    fun `the convex hull of a wide key runs straight between its points`() {
        val outline = hexFaceOutline(
            widthCells = 2f, startPhase = 0f, zigTop = true, zigBottom = true,
            faceWidth = 72f, faceHeight = 50f, gapPx = 0f, amplitude = 10f, convex = true,
        )
        // Corners, then the two points, along each edge; the notch at 36 is gone.
        assertArrayEquals(
            floatArrayOf(0f, 5f, 18f, -5f, 54f, -5f, 72f, 5f, 72f, 45f, 54f, 55f, 18f, 55f, 0f, 45f),
            outline,
            0.0001f,
        )
        // Every turn of a convex outline goes the same way round.
        val n = outline.size / 2
        val turns = (0 until n).map { i ->
            val ax = outline[i * 2]; val ay = outline[i * 2 + 1]
            val bx = outline[(i + 1) % n * 2]; val by = outline[(i + 1) % n * 2 + 1]
            val cx = outline[(i + 2) % n * 2]; val cy = outline[(i + 2) % n * 2 + 1]
            (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        }
        assertTrue(turns.all { it >= -0.0001f } || turns.all { it <= 0.0001f })
    }

    @Test
    fun `rows that do not interlock keep straight edges`() {
        val outline = hexFaceOutline(1f, 0f, zigTop = false, zigBottom = false, 36f, 50f, 0f, 10f)
        assertArrayEquals(floatArrayOf(0f, 0f, 36f, 0f, 36f, 50f, 0f, 50f), outline, 0f)
    }

    @Test
    fun `the zigzag is a regular hexagon's slope, capped for short keys`() {
        assertEquals(36f * HexRise, hexAmplitude(36f, 100f), 0.0001f)
        assertEquals(8f, hexAmplitude(80f, 8f), 0f)
        assertEquals(0f, hexAmplitude(0f, 8f), 0f)
    }

    private fun rowTops(rows: Int, pitch: Float) = FloatArray(rows + 1) { it * pitch }

    /**
     * Every sampled point of the Hex QWERTY letters goes to the key that owns the
     * cell centre nearest to it, distances measured with y scaled by [yScale].
     */
    private fun assertMatchesNearestCentre(cell: Float, pitch: Float, amplitude: Float, yScale: Float) {
        val rows = hexQwerty.take(3)
        val board = hexBoard(rows, 10f)
        val tops = rowTops(rows.size, pitch)
        // Every cell centre of every row, the half cells at the edges included,
        // and the key that holds it.
        data class Centre(val x: Float, val y: Float, val label: String)
        val centres = ArrayList<Centre>()
        for (r in rows.indices) {
            var c = board.phases[r] + 0.5f - 1f
            while (c <= 10f + 0.5f) {
                if (c >= -Epsilon && c <= 10f + Epsilon) {
                    val owner = board.slotsIn(r).minBy { s ->
                        when {
                            c < s.touchX -> s.touchX - c
                            c > s.touchEnd -> c - s.touchEnd
                            else -> 0f
                        }
                    }
                    centres += Centre(c * cell, (tops[r] + tops[r + 1]) / 2f, owner.key.label)
                }
                c += 1f
            }
        }
        val random = Random(7)
        var checked = 0
        repeat(4000) {
            val x = random.nextFloat() * 10f * cell
            val y = random.nextFloat() * tops.last()
            val ranked = centres.sortedBy { hypot(it.x - x, (it.y - y) * yScale) }
            val d0 = hypot(ranked[0].x - x, (ranked[0].y - y) * yScale)
            val d1 = hypot(ranked[1].x - x, (ranked[1].y - y) * yScale)
            // Points a hair from a boundary are float noise either way.
            if (d1 - d0 < 0.5f) return@repeat
            val got = board.slots[board.slotAt(x, y, tops, cell, amplitude)].key.label
            assertEquals("point ($x, $y)", ranked[0].label, got)
            checked++
        }
        assertTrue("sampled enough points ($checked)", checked > 3000)
    }

    private companion object {
        const val Epsilon = 0.001f
    }
}

package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where this user's finger lands relative to where each key is drawn, learned
 * from the glides they keep and handed back to the decoder as a shifted grid
 * (issue #52).
 *
 * The decoder scores a stroke against key centres, and the noise sweep says
 * that is its steepest axis by a distance: a stroke drawn systematically off
 * or short of the keys costs more accuracy than any amount of corner cutting.
 * Real hands are systematic. A right thumb reaches the far side of the board
 * short and lands low on the near side, and it does so on every stroke, which
 * is exactly what a per-key mean can learn and exactly what per-stroke noise
 * cannot. So each committed glide is laid back over the grid it was decoded
 * on, the place the stroke passed for each letter is measured against that
 * letter's key, and the running mean of those misses is where the key is put
 * for the next decode.
 *
 * **Keyed by position, not by letter.** The miss is a property of where the
 * key sits under the hand, not of what is printed on it: the same thumb cuts
 * the top-right corner the same way on QWERTY, AZERTY and Probhat. Cells are
 * half a key width square, which separates every key on every grid and lets
 * two layouts with the same geometry share what either one learned.
 *
 * **Shrunk toward the whole hand.** A cell with one observation is noise; a
 * board with forty is a hand. Each cell's mean is pulled toward the global
 * mean by a prior count, so a key the user has barely swiped through still
 * moves with the rest of the board, and a key they swipe through constantly
 * gets its own reading. Both means are capped in memory so a hand that
 * changes — a new phone, a case, a broken thumb — is followed rather than
 * averaged against for the rest of time.
 *
 * **A cell has to disagree with the hand by a margin to be heard.** Strokes
 * have geometry of their own that is not the hand: a finger cuts inside
 * every corner and starts each word a little late, and both leave a small
 * per-key mean that depends on which way the words through that key turn.
 * Those quirks point different ways on different keys and cancel in the
 * whole-hand mean, but they survive in the cells, and following them cost
 * an unbiased hand real accuracy on the harness. So a cell's residual from
 * the global mean is soft-thresholded at [DEAD_ZONE]: the first 0.08 key
 * widths of it are treated as the stroke's, not the hand's.
 *
 * **A hand that draws small, or large.** A thumb that never quite reaches
 * the far keys on a wide phone misses every key by an amount that grows
 * with its distance from the centre and points inward on both sides: a
 * scale, not a shift. Per-cell means can only learn that one cell at a
 * time, from strokes that visit each cell, and the edge cells need shifts
 * past [MAX_SHIFT]; measured on the corpus with a hand drawing at 85%, the
 * cells alone recover top-1 from .518 to .908 of an unscaled .964. So the
 * whole-hand reading is a *trend*, not just a mean: the slope of the cells'
 * means against their positions, weighted by how much each cell has seen,
 * which is what a scale looks like to a per-key model. The trend is
 * estimated from the cells rather than from the letters of each glide
 * because a glide's own letters shrink toward its centre on every stroke
 * — corners are cut inward, small words are drawn smaller — and reading
 * that as a scale about the keyboard shrank an unbiased hand's grid by
 * twelve percent and cost it a point. Across cells, those per-word
 * shrinks cancel the way the corner cuts do, and only a shrink about the
 * keyboard survives. The trend has its own dead zone and prior, and the
 * cell residuals are taken against it.
 *
 * **How widely the taps scatter.** Each cell also keeps the running mean of
 * its squared misses, so the spread of the taps around the cell's own mean
 * can be pooled over the board ([spreadFactors]). The tap decoder's spread
 * was fitted for a typical hand; a person whose taps scatter twice as wide
 * gets a spread twice as wide, and a careful one a narrower one. Measured on
 * recorded phone taps (Vertanen & Kristensson's noisy typing set and TSI,
 * scored on the second half of each person's sentences after learning from
 * what the keyboard committed in the first), it took the words left wrong
 * from 20.1% to 18.3% on top of the offsets alone, correct words changed
 * unmoved. Only the tap model's spread is read; the glide model keeps one
 * too, unused.
 *
 * **Undoable.** A glide the user backspaces was a wrong reading of the
 * stroke, and a wrong word aligned to a stroke teaches wrong offsets. So
 * [observe] hands back exactly what it changed and [retract] puts it back —
 * for the *last* observation only, which is the only one an undo can reach.
 *
 * Persisted as JSON in the learning directory beside the lexicon; in memory
 * only until the device is unlocked, like every store there.
 */
class KeyOffsets(private val storageFile: File?) {

    /**
     * One letter of one kept glide: where its key is drawn and where the
     * stroke actually passed for it, both in key widths.
     */
    class Observation(val keyX: Float, val keyY: Float, val seenX: Float, val seenY: Float)

    /** What one [observe] changed, so an undo can put it back exactly. */
    class Adjustment internal constructor(
        internal val cells: List<Pair<Long, Cell>>,
        internal val global: Cell,
    )

    /**
     * A running mean, remembered [n] observations deep, and the running mean
     * of its squares ([sx], [sy]); NaN for a cell read from a file written
     * before those were kept, which then says nothing about the spread.
     */
    internal class Cell(
        var dx: Float,
        var dy: Float,
        var n: Int,
        var sx: Float = 0f,
        var sy: Float = 0f,
    ) {
        fun copy() = Cell(dx, dy, n, sx, sy)
    }

    @Serializable
    private data class StoredCell(
        val x: Int,
        val y: Int,
        val dx: Float,
        val dy: Float,
        val n: Int,
        val sx: Float? = null,
        val sy: Float? = null,
    )

    @Serializable
    private data class Snapshot(
        val cells: List<StoredCell> = emptyList(),
        val gx: Float = 0f,
        val gy: Float = 0f,
        val gn: Int = 0,
    )

    private val cells = HashMap<Long, Cell>()
    private var global = Cell(0f, 0f, 0)
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /** [spreadFactors] as of [spreadVersion]. */
    private var spreadVersion = -1
    private var spread: FloatArray? = null

    /** The trend as of [trendVersion]: slope per key width from the cells' weighted centre, and that centre. */
    private var trendVersion = -1
    private var trendX = 0f
    private var trendY = 0f
    private var centreX = 0f
    private var centreY = 0f

    /**
     * Bumped on every change. The decoder's grid is built from this model and
     * cached; the version is what tells the cache the grid has moved.
     */
    @Volatile
    var version: Int = 0
        private set

    init {
        load()
    }

    /** True until the first observation: a grid shifted by nothing is the grid. */
    @Synchronized
    fun isEmpty(): Boolean = global.n == 0

    /** How many glides have been learned from, capped at the global memory. */
    @Synchronized
    fun observations(): Int = global.n

    /** The hand's trend along x: extra shift per key width from the cells' centre, 0 until it is trusted. */
    @Synchronized
    fun trendX(): Float {
        ensureTrend()
        return trendX
    }

    /** The hand's trend along y, as [trendX]. */
    @Synchronized
    fun trendY(): Float {
        ensureTrend()
        return trendY
    }

    /**
     * The shift to apply at position ([x], [y]) in key widths, written into
     * [out] as dx, dy. The whole hand's mean scaled down while it is itself
     * still young, plus the hand's trend for how far the position is from
     * the cells' centre, plus the cell's own residual where it has one,
     * pulled toward that by [CELL_PRIOR] observations' worth; never longer
     * than [MAX_SHIFT], whatever the data says.
     */
    @Synchronized
    fun offsetAt(x: Float, y: Float, out: FloatArray) {
        ensureTrend()
        val trust = global.n / (global.n + GLOBAL_PRIOR).toFloat()
        var ox = global.dx * trust + trendX * (x - centreX)
        var oy = global.dy * trust + trendY * (y - centreY)
        val cell = cells[cellOf(x, y)]
        if (cell != null && cell.n > 0) {
            val w = cell.n / (cell.n + CELL_PRIOR).toFloat()
            var rx = (cell.dx - ox) * w
            var ry = (cell.dy - oy) * w
            val residual = sqrt(rx * rx + ry * ry)
            if (residual > DEAD_ZONE) {
                val keep = (residual - DEAD_ZONE) / residual
                rx *= keep
                ry *= keep
            } else {
                rx = 0f
                ry = 0f
            }
            ox += rx
            oy += ry
        }
        val length = sqrt(ox * ox + oy * oy)
        if (length > MAX_SHIFT) {
            ox *= MAX_SHIFT / length
            oy *= MAX_SHIFT / length
        }
        out[0] = ox
        out[1] = oy
    }

    /**
     * [keys] moved to where this hand lands on them, in the same pixel space,
     * for a keyboard whose keys are [keyWidth] wide. The list itself when
     * there is nothing learned yet. Characters sharing a key keep sharing it:
     * the shift is a function of the drawn position alone. Held under the
     * lock for the whole grid, so a glide learned mid-call cannot leave half
     * the keys on the old offsets and half on the new.
     */
    @Synchronized
    fun shifted(keys: List<KeyCenter>, keyWidth: Float): List<KeyCenter> {
        if (isEmpty() || keyWidth <= 0f) return keys
        val out = FloatArray(2)
        return keys.map { key ->
            offsetAt(key.x / keyWidth, key.y / keyWidth, out)
            KeyCenter(key.codePoint, key.x + out[0] * keyWidth, key.y + out[1] * keyWidth)
        }
    }

    /**
     * The factors on the tap decoder's spread, across and down: the taps'
     * spread around each cell's own mean, pooled over the cells, with
     * [SPREAD_PRIOR] taps of a typical hand ([SPREAD_REF]) mixed in, over
     * that typical spread. Null until anything with a spread is learned.
     * The same rule as `wm-engine`'s `TouchOffsets::spread_factors`, which
     * the sentence harness measures.
     */
    @Synchronized
    fun spreadFactors(): FloatArray? {
        if (spreadVersion == version) return spread?.copyOf()
        spreadVersion = version
        var sumX = 0.0
        var sumY = 0.0
        var count = 0.0
        for (cell in cells.values) {
            if (cell.n < 2 || cell.sx.isNaN() || cell.sy.isNaN()) continue
            sumX += cell.n * maxOf(0.0, cell.sx.toDouble() - cell.dx.toDouble() * cell.dx)
            sumY += cell.n * maxOf(0.0, cell.sy.toDouble() - cell.dy.toDouble() * cell.dy)
            count += cell.n - 1
        }
        spread = if (count <= 0.0) {
            null
        } else {
            val r = SPREAD_REF.toDouble()
            val k = SPREAD_PRIOR.toDouble()
            floatArrayOf(
                (sqrt((sumX + k * r * r) / (count + k)) / r).toFloat(),
                (sqrt((sumY + k * r * r) / (count + k)) / r).toFloat(),
            )
        }
        return spread?.copyOf()
    }

    /**
     * Learns from one kept glide. An observation further than [MAX_OBSERVED]
     * from its key is a misalignment, not a hand, and is skipped. Returns
     * what changed, or null when nothing usable was offered.
     */
    @Synchronized
    fun observe(observations: List<Observation>): Adjustment? {
        val usable = observations.filter {
            abs(it.seenX - it.keyX) <= MAX_OBSERVED && abs(it.seenY - it.keyY) <= MAX_OBSERVED
        }
        if (usable.isEmpty()) return null
        val before = LinkedHashMap<Long, Cell>()
        val globalBefore = global.copy()
        for (o in usable) {
            val id = cellOf(o.keyX, o.keyY)
            val cell = cells.getOrPut(id) { Cell(0f, 0f, 0) }
            if (id !in before) before[id] = cell.copy()
            val dx = o.seenX - o.keyX
            val dy = o.seenY - o.keyY
            update(cell, dx, dy, CELL_MEMORY)
            update(global, dx, dy, GLOBAL_MEMORY)
        }
        trim()
        dirty = true
        version++
        return Adjustment(before.entries.map { it.key to it.value }, globalBefore)
    }

    /**
     * Puts back what [adjustment] changed. Only sound for the most recent
     * [observe]: a later one has already built on the state this restores.
     */
    @Synchronized
    fun retract(adjustment: Adjustment) {
        for ((id, previous) in adjustment.cells) {
            if (previous.n == 0) cells.remove(id) else cells[id] = previous.copy()
        }
        global = adjustment.global.copy()
        dirty = true
        version++
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                cells.map { (id, cell) ->
                    StoredCell(
                        xOf(id), yOf(id), cell.dx, cell.dy, cell.n,
                        cell.sx.takeIf { it.isFinite() }, cell.sy.takeIf { it.isFinite() },
                    )
                },
                global.dx,
                global.dy,
                global.n,
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    /** Re-reads the file after the settings app deleted or replaced it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        cells.clear()
        global = Cell(0f, 0f, 0)
        load()
        dirty = false
        version++
    }

    @Synchronized
    fun clear() {
        cells.clear()
        global = Cell(0f, 0f, 0)
        version++
        // The delete is the write; stay dirty only if it failed, so the next
        // save overwrites the stale file with the empty snapshot.
        dirty = snapshotFile?.delete() == false
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            for (c in snapshot.cells) {
                if (c.n <= 0 || !c.dx.isFinite() || !c.dy.isFinite()) continue
                val sx = c.sx?.takeIf { it.isFinite() } ?: Float.NaN
                val sy = c.sy?.takeIf { it.isFinite() } ?: Float.NaN
                cells[pack(c.x, c.y)] = Cell(c.dx, c.dy, c.n.coerceAtMost(CELL_MEMORY), sx, sy)
            }
            if (snapshot.gn > 0 && snapshot.gx.isFinite() && snapshot.gy.isFinite()) {
                global = Cell(snapshot.gx, snapshot.gy, snapshot.gn.coerceAtMost(GLOBAL_MEMORY))
            }
        }
    }

    /**
     * Refits the trend when the cells have changed since it was last read:
     * the weighted least-squares slope of each cell's mean miss against its
     * position, per axis, cells weighted by what they have seen. Shrunk
     * toward zero by [TREND_PRIOR] observations' worth, soft-thresholded at
     * [TREND_DEAD_ZONE] so the small inward slope corner cutting leaves on
     * the edge keys is read as the strokes' and not the hand's, and never
     * steeper than [MAX_TREND]. An axis the cells do not spread along says
     * nothing about it.
     */
    private fun ensureTrend() {
        if (trendVersion == version) return
        trendVersion = version
        var weight = 0f
        var sumX = 0f
        var sumY = 0f
        for ((id, cell) in cells) {
            val w = cell.n.toFloat()
            weight += w
            sumX += w * xOf(id).toFloat() / CELLS_PER_KEY
            sumY += w * yOf(id).toFloat() / CELLS_PER_KEY
        }
        if (weight <= 0f) {
            trendX = 0f
            trendY = 0f
            return
        }
        centreX = sumX / weight
        centreY = sumY / weight
        var spreadX = 0f
        var slopeX = 0f
        var spreadY = 0f
        var slopeY = 0f
        for ((id, cell) in cells) {
            val w = cell.n.toFloat()
            val x = xOf(id).toFloat() / CELLS_PER_KEY - centreX
            val y = yOf(id).toFloat() / CELLS_PER_KEY - centreY
            spreadX += w * x * x
            slopeX += w * x * cell.dx
            spreadY += w * y * y
            slopeY += w * y * cell.dy
        }
        val trust = weight / (weight + TREND_PRIOR)
        trendX = if (spreadX >= MIN_TREND_SPREAD * weight) trend(slopeX / spreadX * trust) else 0f
        trendY = if (spreadY >= MIN_TREND_SPREAD * weight) trend(slopeY / spreadY * trust) else 0f
    }

    /** [slope] past its dead zone, capped. */
    private fun trend(slope: Float): Float {
        val kept = when {
            slope > TREND_DEAD_ZONE -> slope - TREND_DEAD_ZONE
            slope < -TREND_DEAD_ZONE -> slope + TREND_DEAD_ZONE
            else -> 0f
        }
        return kept.coerceIn(-MAX_TREND, MAX_TREND)
    }

    /**
     * A capped running mean: the newest observation is worth `1/n`, never
     * less than `1/memory`; the squares alike. A cell whose squares were
     * never kept (an old file) starts keeping them afresh.
     */
    private fun update(cell: Cell, dx: Float, dy: Float, memory: Int) {
        val n = minOf(cell.n + 1, memory)
        if (cell.sx.isNaN() || cell.sy.isNaN()) {
            cell.sx = cell.dx * cell.dx
            cell.sy = cell.dy * cell.dy
        }
        cell.dx += (dx - cell.dx) / n
        cell.dy += (dy - cell.dy) / n
        cell.sx += (dx * dx - cell.sx) / n
        cell.sy += (dy * dy - cell.sy) / n
        cell.n = n
    }

    /** Past [MAX_CELLS] the least-observed cell makes room. Grids are small; this is a backstop. */
    private fun trim() {
        while (cells.size > MAX_CELLS) {
            val weakest = cells.entries.minByOrNull { it.value.n } ?: return
            cells.remove(weakest.key)
        }
    }

    private fun cellOf(x: Float, y: Float): Long =
        pack((x * CELLS_PER_KEY).roundToInt(), (y * CELLS_PER_KEY).roundToInt())

    private fun pack(qx: Int, qy: Int): Long = (qx.toLong() shl 32) or (qy.toLong() and 0xffffffffL)

    private fun xOf(id: Long): Int = (id shr 32).toInt()

    private fun yOf(id: Long): Int = id.toInt()

    companion object {
        /** Cells per key width along each axis: half a key, so no two keys share one. */
        private const val CELLS_PER_KEY = 2

        /** A letter seen further than this from its key, in key widths, is a bad alignment, not a hand. */
        const val MAX_OBSERVED = 0.9f

        /**
         * The furthest a key is ever moved, in key widths, whatever was
         * observed. Was 0.45 before the trend: a hand drawing at 85% puts the
         * far keys of a wide layout seven tenths of a key inward, and capping
         * short of that was most of what the trend could not recover. At 0.9
         * the biased hand the harness draws went .726 to .852 on the cap
         * alone, and the unbiased one did not move.
         */
        const val MAX_SHIFT = 0.9f

        /** Observations a cell's mean remembers; older ones fade so a changed hand is followed. */
        const val CELL_MEMORY = 40

        /** Observations the whole hand's mean remembers. */
        const val GLOBAL_MEMORY = 200

        /** Pseudo-observations of the whole hand a cell has to outweigh to speak for itself. */
        const val CELL_PRIOR = 10

        /** How far a cell's mean must leave the whole hand's before the difference counts, in key widths. */
        const val DEAD_ZONE = 0.08f

        /** Observations before the whole hand's mean is applied at full strength. */
        const val GLOBAL_PRIOR = 8

        /** Cell observations' worth of pull toward no trend while the hand is young. */
        const val TREND_PRIOR = 100f

        /**
         * Slope the trend must show before any of it is followed, in key
         * widths of miss per key width from the centre: the inward slope
         * corner cutting leaves on the edge keys of an unbiased hand sits
         * inside it.
         */
        const val TREND_DEAD_ZONE = 0.02f

        /** The steepest trend followed: a hand drawing at three quarters or five quarters of the keyboard. */
        const val MAX_TREND = 0.25f

        /** Least weighted variance of the cells' positions along an axis, in key widths squared, for a trend along it. */
        const val MIN_TREND_SPREAD = 0.3f

        private const val MAX_CELLS = 512

        /**
         * The spread of a typical hand's taps around its own key centres, in
         * key widths: what the tap decoder's spread was fitted for. Fitted on
         * recorded taps; a smaller one rewrote correctly typed words.
         */
        const val SPREAD_REF = 0.25f

        /** Taps of a typical hand mixed into the learned spread. */
        const val SPREAD_PRIOR = 200f
    }
}

package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import com.wasimaster.wmkeyboard.core.prediction.WordContext
import com.wasimaster.wmkeyboard.core.prediction.WordKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the decoder asks of learned shapes: one layout's worth, read-only,
 * safe to read on the decode thread while the keyboard's thread learns.
 */
interface GlideShapeSource {
    /**
     * How far [drawn] sits from the nearest shape stored for [word], as a
     * mean distance in key widths, or -1 when the word has none here.
     */
    fun minDistance(word: String, drawn: ByteArray): Float

    /**
     * The shapes stored for [word], in key-width coordinates times
     * [GlideShapeStore.QUANT], empty when it has none here. The decoder's
     * shape channel compares them after taking their position and size out,
     * the way it does the drawn stroke.
     */
    fun shapesOf(word: String): List<ByteArray>

    /** Words with a stored shape within [radius] of [drawn], nearest first, at most [limit] of them. */
    fun wordsNear(drawn: ByteArray, radius: Float, limit: Int): List<String>
}

/**
 * One kept glide's shape, as the learning buffer carries it from commit to
 * settle: the grid it was drawn on and the stroke as [GlideBeam.sampleShape]
 * keeps it.
 */
class GlideShapeSample(val layoutKey: Long, val shape: ByteArray)

/**
 * How this user draws each word (issue #52): a few shapes per word, learned
 * from the glides they keep, for the decoder's shape channel to compare a
 * stroke against beside the word's ideal path.
 *
 * The ideal path through a word's key centres is what the shape channel has
 * to go on, and a real hand never draws it: it cuts corners its own way,
 * overshoots one key and undershoots the next, and does so the same way
 * every time. Swype kept the user's own strokes for exactly this reason. A
 * shape here is a stroke resampled the way the decoder resamples one, kept
 * in key-width coordinates so where it starts still counts for merging and
 * seeding, and quantised to a byte a coordinate, so a word's whole record is
 * a few hundred bytes. The shape channel normalises it like the drawn
 * stroke — centred, scaled to unit spread — before comparing the two.
 *
 * **Graduated, never taken at commit.** A shape is attached to the word in
 * the learning buffer and lands here only when the word settles: a glide the
 * user backspaces or replaces off the strip teaches nothing, and one they
 * read past and left alone does. A shape is also only kept for a word the
 * lexicon has, so nothing that is not yet a learned word is written down.
 *
 * **Keyed by grid, then word.** The same word on QWERTY and AZERTY is two
 * shapes, and so is the same word in portrait and landscape, whose row pitch
 * differs in key widths; two layouts with the same geometry share. The key
 * is a fingerprint of the keys as drawn, before the hand model moves them.
 *
 * **Accept and reject counts per shape.** A settle finds the nearest stored
 * shape of the word: within [MERGE_RADIUS] it blends in and counts an
 * acceptance, further off it is a new way of drawing the word and is added,
 * up to the user's limit ([setShapesPerWord], three unless they moved it),
 * the least-accepted making room. A glide the user undoes or replaces marks
 * the shape that matched it rejected, and a shape rejected more often than
 * accepted goes.
 *
 * Bounded at [MAX_WORDS] words across every layout, the longest untouched
 * evicted. Persisted in the learning directory beside the lexicon as a
 * binary file with a SHA-256 over it ([GlideShapeBinary], issue #1130), the
 * previous generation kept beside it; memory-only until the device is
 * unlocked, like every store there. Reads take a published immutable
 * snapshot, swapped whole on every change.
 *
 * JSON v2, the format up to 0.6.5, is still read: from [storageFile] itself
 * when it holds JSON, or from the `.json` file beside a `.bin` one, which the
 * first binary save then removes. It is also the backup format
 * ([exportJson], [importJson]), so a backup restores on an older build.
 */
class GlideShapeStore(private val storageFile: File?) {

    private class Shape(var points: ByteArray, var accepted: Int, var rejected: Int)

    private class Entry(var tick: Long, val shapes: ArrayList<Shape>)

    @Serializable
    private data class StoredShape(val p: String, val a: Int = 1, val r: Int = 0)

    @Serializable
    private data class StoredWord(val t: Long = 0L, val s: List<StoredShape> = emptyList())

    @Serializable
    private data class Snapshot(
        val v: Int = VERSION,
        val tick: Long = 0L,
        val layouts: Map<String, Map<String, StoredWord>> = emptyMap(),
    )

    /** One layout's shapes as the decoder reads them: flat arrays, never mutated. */
    private class LayoutShapes(
        private val words: Array<String>,
        private val shapes: Array<ByteArray>,
        private val byWord: HashMap<String, IntArray>,
    ) : GlideShapeSource {

        /**
         * Shape indices by the key-sized cell each shape starts in, in
         * index order within a cell: what [wordsNear] scans instead of
         * every shape (issue #966 §5.4).
         *
         * The seeding scan ran over every stored shape on every decode,
         * previews included: 66 µs on a real store of 3,131 shapes and
         * 2.6 ms at the store's ceiling, up to 45 % of a decode. A shape
         * within the seeding radius of the drawn stroke almost always
         * starts where the stroke starts, since the store keeps shapes in
         * key widths and the radius is a third of one, so the scan takes
         * the 3×3 cells around the stroke's first point and nothing else:
         * 9.4 % of the shapes, 5.5× faster, and the same first word on all
         * 3,131 real queries. It is approximate in theory, since the mean
         * distance lets a far first point hide behind forty-seven near
         * ones; a stroke whose cells hold nothing falls back to the full
         * scan, so an empty answer is never the index's doing.
         */
        private val byStart: HashMap<Int, IntArray> = bucketByStart(shapes)

        override fun minDistance(word: String, drawn: ByteArray): Float {
            val indices = byWord[shapeKey(word)] ?: return -1f
            var best = Float.MAX_VALUE
            for (i in indices) {
                val d = distance(shapes[i], drawn, best)
                if (d < best) best = d
            }
            return if (best == Float.MAX_VALUE) -1f else best
        }

        override fun shapesOf(word: String): List<ByteArray> {
            val indices = byWord[shapeKey(word)] ?: return emptyList()
            return indices.map { shapes[it] }
        }

        override fun wordsNear(drawn: ByteArray, radius: Float, limit: Int): List<String> {
            if (limit <= 0 || shapes.isEmpty()) return emptyList()
            val found = ArrayList<Pair<String, Float>>()
            val near = startingNear(drawn)
            if (near != null) {
                for (i in near) consider(i, drawn, radius, found)
            } else {
                for (i in shapes.indices) consider(i, drawn, radius, found)
            }
            found.sortBy { it.second }
            return found.take(limit).map { it.first }
        }

        /** Files shape [i] under its word when it sits within [radius] of [drawn], keeping the word's nearest. */
        private fun consider(i: Int, drawn: ByteArray, radius: Float, found: ArrayList<Pair<String, Float>>) {
            val d = distance(shapes[i], drawn, radius)
            if (d > radius) return
            val word = words[i]
            val at = found.indexOfFirst { it.first == word }
            if (at < 0) found.add(word to d) else if (d < found[at].second) found[at] = word to d
        }

        /**
         * The shapes starting in the 3×3 cells around [drawn]'s first
         * point, in index order so a tie sorts as the full scan sorts it;
         * null when those cells hold nothing, which is the full scan's cue.
         */
        private fun startingNear(drawn: ByteArray): IntArray? {
            val cx = Math.floorDiv(drawn[0].toInt(), CELL)
            val cy = Math.floorDiv(drawn[1].toInt(), CELL)
            var total = 0
            for (dx in -1..1) for (dy in -1..1) total += byStart[cellKey(cx + dx, cy + dy)]?.size ?: 0
            if (total == 0) return null
            val out = IntArray(total)
            var at = 0
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val cell = byStart[cellKey(cx + dx, cy + dy)] ?: continue
                    System.arraycopy(cell, 0, out, at, cell.size)
                    at += cell.size
                }
            }
            java.util.Arrays.sort(out)
            return out
        }

        private companion object {
            /** A cell is one key wide: [QUANT] quantised units. */
            const val CELL = 10

            /** A cell's key packs its column and row, each a byte's worth of cells at most, into one int. */
            const val CELL_BITS = 16
            const val CELL_MASK = 0xFFFF

            fun cellKey(cx: Int, cy: Int): Int = (cx shl CELL_BITS) or (cy and CELL_MASK)

            fun cellOf(shape: ByteArray): Int =
                cellKey(Math.floorDiv(shape[0].toInt(), CELL), Math.floorDiv(shape[1].toInt(), CELL))

            fun bucketByStart(shapes: Array<ByteArray>): HashMap<Int, IntArray> {
                val counts = HashMap<Int, Int>()
                for (shape in shapes) {
                    val cell = cellOf(shape)
                    counts[cell] = (counts[cell] ?: 0) + 1
                }
                val out = HashMap<Int, IntArray>(counts.size * 2)
                val filled = HashMap<Int, Int>(counts.size * 2)
                for ((i, shape) in shapes.withIndex()) {
                    val cell = cellOf(shape)
                    val bucket = out.getOrPut(cell) { IntArray(counts.getValue(cell)) }
                    val at = filled[cell] ?: 0
                    bucket[at] = i
                    filled[cell] = at + 1
                }
                return out
            }
        }
    }

    private val layouts = HashMap<Long, HashMap<String, Entry>>()
    private var tick = 0L
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /** The JSON file this store was kept in before the binary format, beside [storageFile]. */
    private val legacyFile: File? = storageFile?.let(::legacyOf)

    @Volatile
    private var published: Map<Long, LayoutShapes> = emptyMap()

    /** How many ways of drawing one word are kept apart. See [setShapesPerWord]. */
    private var shapeLimit = DEFAULT_SHAPES_PER_WORD

    init {
        load()
    }

    /** The shapes stored for one layout, for the decoder; null when it has none. */
    fun forLayout(layoutKey: Long): GlideShapeSource? = published[layoutKey]

    /**
     * How many ways of drawing one word to keep apart (#326), clamped to
     * 1..[MAX_SHAPES_PER_WORD].
     *
     * Lowering it takes the least-accepted extras away from the decoder at
     * once, but drops them from the store only when that word next learns a
     * new way of being drawn. Until then raising it again brings them back,
     * so a slider dragged down and up again, or a settings emit that arrives
     * with the default before the user's value, loses nothing.
     */
    @Synchronized
    fun setShapesPerWord(limit: Int) {
        val clamped = limit.coerceIn(1, MAX_SHAPES_PER_WORD)
        if (clamped == shapeLimit) return
        shapeLimit = clamped
        publish()
    }

    /**
     * A settled glide of [word] drawn as [sample]: blends into the nearest
     * stored shape of the word when there is one within [MERGE_RADIUS], else
     * is kept as a new one.
     */
    @Synchronized
    fun learn(sample: GlideShapeSample, word: String) {
        val key = shapeKey(word)
        if (key.isEmpty() || sample.shape.size != POINTS) return
        tick++
        val words = layouts.getOrPut(sample.layoutKey) { HashMap() }
        val entry = words.getOrPut(key) { Entry(tick, ArrayList(shapeLimit)) }
        entry.tick = tick
        val nearest = nearest(entry, sample.shape)
        if (nearest != null && distance(nearest.points, sample.shape, MERGE_RADIUS) <= MERGE_RADIUS) {
            blend(nearest, sample.shape)
            nearest.accepted++
        } else {
            if (entry.shapes.size >= shapeLimit) {
                // A new way of drawing a word always arrives at one acceptance,
                // so a plain count leaves an established shape unbeatable: it
                // evicts each newcomer in turn, and a hand that has changed can
                // never say so (issue #213). Every sighting of something new
                // ages what is already here instead. A shape still in use keeps
                // its lead by being accepted again; one the user has stopped
                // drawing halves away and gives up the slot in a few sightings.
                for (shape in entry.shapes) shape.accepted = (shape.accepted + 1) / 2
                // More than one goes when the limit was lowered since this word
                // last learned something new.
                while (entry.shapes.size >= shapeLimit) {
                    entry.shapes.remove(entry.shapes.minByOrNull { it.accepted - it.rejected })
                }
            }
            entry.shapes.add(Shape(sample.shape.copyOf(), 1, 0))
        }
        evict()
        settle()
    }

    /**
     * A glide read as [word] and drawn as [drawn] was undone or replaced: the
     * stored shape that matched it, if one did within [REJECT_RADIUS], counts
     * a rejection, and goes once rejected more often than accepted. True
     * when a shape was marked.
     */
    @Synchronized
    fun reject(layoutKey: Long, word: String, drawn: ByteArray): Boolean {
        val words = layouts[layoutKey] ?: return false
        val key = shapeKey(word)
        val entry = words[key] ?: return false
        val nearest = nearest(entry, drawn) ?: return false
        if (distance(nearest.points, drawn, REJECT_RADIUS) > REJECT_RADIUS) return false
        tick++
        entry.tick = tick
        nearest.rejected++
        if (nearest.rejected > nearest.accepted) entry.shapes.remove(nearest)
        if (entry.shapes.isEmpty()) words.remove(key)
        if (words.isEmpty()) layouts.remove(layoutKey)
        settle()
        return true
    }

    /** Drops every shape of [word], on every layout. True when there were any. */
    @Synchronized
    fun forget(word: String): Boolean {
        val key = shapeKey(word)
        var any = false
        val iterator = layouts.entries.iterator()
        while (iterator.hasNext()) {
            val words = iterator.next().value
            if (words.remove(key) != null) any = true
            if (words.isEmpty()) iterator.remove()
        }
        if (any) settle()
        return any
    }

    /** How many shapes [word] has that the decoder reads, across every layout. */
    @Synchronized
    fun countFor(word: String): Int {
        val key = shapeKey(word)
        var count = 0
        for (words in layouts.values) count += minOf(words[key]?.shapes?.size ?: 0, shapeLimit)
        return count
    }

    @Synchronized
    fun wordCount(): Int = layouts.values.sumOf { it.size }

    @Synchronized
    fun isEmpty(): Boolean = layouts.isEmpty()

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, image) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to image()
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.writeBytes(ticket) { GlideShapeBinary.encode(image) }) {
            markUnsaved()
            return
        }
        // The JSON it was migrated from is now behind the binary file; left,
        // a Forget that missed it would bring old shapes back.
        legacyFile?.takeIf { it.exists() }?.delete()
    }

    /** Everything stored, copied, as the files carry it. Under the lock. */
    private fun image(): GlideShapeImage = GlideShapeImage(
        tick,
        layouts.entries.associate { (layoutKey, words) ->
            layoutKey to words.entries.associate { (word, entry) ->
                word to GlideShapeWord(
                    entry.tick,
                    entry.shapes.map { GlideShapeRecord(it.points.copyOf(), it.accepted, it.rejected) },
                )
            }
        },
    )

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    /** Re-reads the file after the settings app deleted or replaced it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        layouts.clear()
        tick = 0L
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        layouts.clear()
        tick = 0L
        publish()
        // The delete is the write; stay dirty only if it failed, so the next
        // save overwrites the stale file with the empty snapshot.
        dirty = snapshotFile?.delete() == false
        legacyFile?.delete()
    }

    private fun nearest(entry: Entry, drawn: ByteArray): Shape? {
        var best: Shape? = null
        var bestDistance = Float.MAX_VALUE
        for (shape in entry.shapes) {
            val d = distance(shape.points, drawn, bestDistance)
            if (d < bestDistance) {
                bestDistance = d
                best = shape
            }
        }
        return best
    }

    /**
     * Running mean in the quantised space, the shape's acceptances deep — but
     * never deeper than [BLEND_DEPTH], and rounded rather than truncated.
     *
     * Two things were wrong with the plain mean (issue #213). It freezes: at
     * fifty acceptances a new stroke moves the stored shape by a fiftieth, so a
     * word drawn often enough stops following the hand this store exists to
     * follow. And `toInt()` truncates *toward zero*, so every coordinate that
     * failed to move a whole quantised unit fell back toward the origin instead
     * of staying put — a slow pull on every stored shape, in the one direction
     * the normalisation has already centred them on.
     *
     * Capping the depth turns the tail into an exponential mean, which keeps
     * converging while a habit holds and still comes across when it changes.
     * The cap has to be shallow because the mean is kept in whole quantised
     * units: a step of half the weight or less rounds away to nothing, so a
     * depth of `n` cannot track a drift finer than about `n / 2` units. At four
     * that floor is an eighth of a key width, comfortably inside
     * [MERGE_RADIUS], which is the resolution the merge decision works at
     * anyway.
     */
    private fun blend(shape: Shape, drawn: ByteArray) {
        val depth = minOf(shape.accepted, BLEND_DEPTH)
        val weight = depth + 1
        for (i in 0 until POINTS) {
            val mixed = (shape.points[i] * depth + drawn[i]).toFloat() / weight
            shape.points[i] = mixed.roundToInt().coerceIn(-QUANT_LIMIT, QUANT_LIMIT).toByte()
        }
    }

    /** Past [MAX_WORDS] the word left alone longest makes room. */
    private fun evict() {
        while (wordCount() > MAX_WORDS) {
            var oldestLayout = 0L
            var oldestWord: String? = null
            var oldestTick = Long.MAX_VALUE
            for ((layoutKey, words) in layouts) {
                for ((word, entry) in words) {
                    if (entry.tick < oldestTick) {
                        oldestTick = entry.tick
                        oldestLayout = layoutKey
                        oldestWord = word
                    }
                }
            }
            val words = layouts[oldestLayout] ?: return
            words.remove(oldestWord ?: return)
            if (words.isEmpty()) layouts.remove(oldestLayout)
        }
    }

    private fun settle() {
        publish()
        dirty = true
    }

    private fun publish() {
        published = if (layouts.isEmpty()) {
            emptyMap()
        } else {
            layouts.entries.associate { (layoutKey, words) ->
                val wordList = ArrayList<String>()
                val shapeList = ArrayList<ByteArray>()
                val byWord = HashMap<String, IntArray>(words.size * 2)
                for ((word, entry) in words) {
                    val kept = readable(entry.shapes)
                    val indices = IntArray(kept.size)
                    for ((i, shape) in kept.withIndex()) {
                        indices[i] = shapeList.size
                        wordList.add(word)
                        shapeList.add(shape.points.copyOf())
                    }
                    byWord[word] = indices
                }
                layoutKey to LayoutShapes(wordList.toTypedArray(), shapeList.toTypedArray(), byWord)
            }
        }
    }

    /** The [shapeLimit] most-accepted of [shapes], or all of them when they fit. */
    private fun readable(shapes: List<Shape>): List<Shape> =
        if (shapes.size <= shapeLimit) {
            shapes
        } else {
            shapes.sortedByDescending { it.accepted - it.rejected }.take(shapeLimit)
        }

    private fun load() {
        val file = storageFile ?: return
        val (image, rewrite) = read(file, quarantine = true)
        if (image != null) {
            adopt(image)
            // Read from JSON or from the previous generation: the next save
            // writes the binary file those stand in for.
            if (rewrite) dirty = true
        }
        publish()
    }

    /**
     * Takes [image] in, with the same checks whichever file it came from: a
     * key [shapeKey] would not produce is re-keyed, a shape of the wrong size
     * or never accepted is dropped, and a word keeps at most
     * [MAX_SHAPES_PER_WORD]; past [MAX_WORDS] the oldest go.
     */
    private fun adopt(image: GlideShapeImage) {
        tick = image.tick.coerceAtLeast(0L)
        for ((layoutKey, words) in image.layouts) {
            val entries = HashMap<String, Entry>()
            for ((word, stored) in words) {
                val key = shapeKey(word)
                if (key.isEmpty()) continue
                val shapes = ArrayList<Shape>(minOf(stored.shapes.size, MAX_SHAPES_PER_WORD))
                for (s in stored.shapes) {
                    if (s.points.size != POINTS || s.accepted <= 0) continue
                    shapes.add(Shape(s.points.copyOf(), s.accepted, s.rejected.coerceAtLeast(0)))
                    if (shapes.size >= MAX_SHAPES_PER_WORD) break
                }
                if (shapes.isEmpty()) continue
                // Two keys that differ only by an apostrophe, from before
                // [shapeKey] dropped it, are one word's shapes now.
                val held = entries[key]
                if (held == null) {
                    entries[key] = Entry(stored.tick.coerceIn(0L, tick), shapes)
                } else {
                    for (shape in shapes) if (held.shapes.size < MAX_SHAPES_PER_WORD) held.shapes.add(shape)
                    held.tick = maxOf(held.tick, stored.tick.coerceIn(0L, tick))
                }
            }
            if (entries.isNotEmpty()) layouts[layoutKey] = entries
        }
        evict()
    }

    companion object {
        /**
         * The key a word's shapes are kept under: [WordKey] with every
         * apostrophe taken out (#1051). The grid has no apostrophe, so
         * *geht's* is drawn as `gehts` and decoded as `gehts`, and a shape
         * kept under the spelling with the apostrophe, which is what the user
         * typed to replace a wrong glide, was never asked for again.
         */
        fun shapeKey(word: String): String = WordKey.of(word).filterNot(WordContext::isApostrophe)

        /** The JSON format's version, which the backup carries. */
        private const val VERSION = 2

        private val json = Json { ignoreUnknownKeys = true }

        /** The JSON file a store at [file] used before the binary one: `x.json` beside `x.bin`. */
        private fun legacyOf(file: File): File? =
            if (file.extension == "bin") File(file.parentFile, file.nameWithoutExtension + ".json") else null

        /**
         * What a store at [file] would load, and whether it came from anything
         * but the live binary file (so the next save should write that).
         *
         * In order: the live file, binary or (up to 0.6.5) JSON; its previous
         * generation; the JSON file beside it. A live file that cannot be
         * trusted is, with [quarantine], moved aside as `.corrupt` so it is
         * neither read again nor rotated over the good previous generation.
         */
        private fun read(file: File, quarantine: Boolean): Pair<GlideShapeImage?, Boolean> {
            bytesOf(file)?.let { bytes ->
                val image = if (GlideShapeBinary.isBinary(bytes)) GlideShapeBinary.decode(bytes) else null
                if (image != null) return image to false
                // Not ours by its magic: JSON from before #1130, or nothing.
                if (!GlideShapeBinary.isBinary(bytes) && looksLikeJson(bytes)) {
                    jsonImage(bytes.toString(Charsets.UTF_8))?.let { return it to true }
                }
                if (quarantine) moveAside(file)
            }
            bytesOf(SnapshotFile.backupOf(file))?.let(GlideShapeBinary::decode)?.let { return it to true }
            legacyOf(file)?.let(::bytesOf)?.takeIf(::looksLikeJson)
                ?.let { jsonImage(it.toString(Charsets.UTF_8)) }
                ?.let { return it to true }
            return null to false
        }

        /** The file's bytes, or null when it is missing, empty, unreadable or past [GlideShapeBinary.MAX_FILE_BYTES]. */
        private fun bytesOf(file: File): ByteArray? {
            if (!file.isFile) return null
            val length = file.length()
            if (length <= 0L || length > GlideShapeBinary.MAX_FILE_BYTES) return null
            return runCatching { file.readBytes() }.getOrNull()
        }

        private fun looksLikeJson(bytes: ByteArray): Boolean =
            bytes.firstOrNull { !it.toInt().toChar().isWhitespace() } == '{'.code.toByte()

        private fun moveAside(file: File) {
            val corrupt = SnapshotFile.corruptOf(file)
            corrupt.delete()
            if (!file.renameTo(corrupt)) {
                runCatching { file.copyTo(corrupt, overwrite = true) }
                file.delete()
            }
        }

        /** A JSON v2 snapshot as an image, or null when it is not one. Omitted fields take 0.6.4's defaults. */
        private fun jsonImage(text: String): GlideShapeImage? = runCatching {
            val snapshot = json.decodeFromString<Snapshot>(text)
            if (snapshot.v != VERSION) return@runCatching null
            GlideShapeImage(
                snapshot.tick,
                snapshot.layouts.entries.associate { (layoutHex, words) ->
                    java.lang.Long.parseUnsignedLong(layoutHex, 16) to words.mapValues { (_, stored) ->
                        GlideShapeWord(
                            stored.t,
                            stored.s.mapNotNull { s -> s.p.fromHex()?.let { GlideShapeRecord(it, s.a, s.r) } },
                        )
                    }
                },
            )
        }.getOrNull()

        private fun jsonText(image: GlideShapeImage): String = json.encodeToString(
            Snapshot(
                VERSION,
                image.tick,
                image.layouts.entries.associate { (layoutKey, words) ->
                    java.lang.Long.toHexString(layoutKey) to words.mapValues { (_, word) ->
                        StoredWord(word.tick, word.shapes.map { StoredShape(it.points.toHex(), it.accepted, it.rejected) })
                    }
                },
            ),
        )

        /**
         * The store at [file] as JSON v2, for a backup: the format every build
         * since 0.5 restores. Null when there is nothing to export. Reads only;
         * a damaged file is left where it is for the keyboard to deal with.
         */
        fun exportJson(file: File): String? {
            val image = read(file, quarantine = false).first ?: return null
            val store = GlideShapeStore(null)
            synchronized(store) { store.adopt(image) }
            if (store.isEmpty()) return null
            return jsonText(synchronized(store) { store.image() })
        }

        /**
         * Replaces the store at [file] with JSON v2 [text] from a backup,
         * written as the binary file. Nothing is touched unless [text] is a
         * snapshot this build reads; false then, or when the write fails. The
         * caller tells a running keyboard to reload.
         */
        fun importJson(text: String, file: File): Boolean {
            val image = jsonImage(text) ?: return false
            val store = GlideShapeStore(null)
            val clean = synchronized(store) {
                store.adopt(image)
                store.image()
            }
            return runCatching {
                SnapshotFile.writeAtomically(file, GlideShapeBinary.encode(clean), keepBackup = true)
                legacyOf(file)?.takeIf { it.exists() }?.delete()
                true
            }.getOrDefault(false)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.fromHex(): ByteArray? {
            if (length % 2 != 0 || any { it !in "0123456789abcdefABCDEF" }) return null
            return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        }

        /** Words kept across every layout; the longest untouched goes first. */
        const val MAX_WORDS = 10000

        /** Ways of drawing one word that are kept apart, unless the user sets another number. */
        const val DEFAULT_SHAPES_PER_WORD = 3

        /**
         * Most ways of drawing one word the user can ask to keep (#326). Set
         * high on purpose, so a backup can show how many a hand really uses.
         * A full store at this is 10000 words of about 2 KB each.
         */
        const val MAX_SHAPES_PER_WORD = 20

        /**
         * Deepest the running mean behind a stored shape ever gets: past this
         * many acceptances a shape keeps following the hand at a fixed rate
         * rather than settling for good. See [blend].
         */
        const val BLEND_DEPTH = 4

        /** A settled shape this close to a stored one is the same way of drawing the word, and blends in (in key widths). */
        const val MERGE_RADIUS = 0.35f

        /** An undone stroke this close to a stored shape is the one that shape read wrongly (in key widths). */
        const val REJECT_RADIUS = 0.60f

        /** Coordinates per shape: x then y for each of the decoder's samples. */
        const val POINTS = 2 * GlideWorkspace.SHAPE_POINTS

        /** Quantisation: a key-width coordinate times this, rounded to a byte. */
        const val QUANT = 10f

        private const val QUANT_LIMIT = 127

        /**
         * Mean distance between two shapes, in the shape channel's units — a
         * coordinate is `QUANT` bytes to the key width of spread. Stops
         * counting once the running mean can no longer come in under
         * [abortAbove], and answers something above it then.
         *
         * The root per point is the cost of this, and the seeding scan
         * ([GlideShapeSource.wordsNear]) takes it against every stored shape
         * on every decode, previews included (issue #966). So a cut-off is
         * first tried without any: each point's larger axis difference is
         * never more than its distance, and a shape whose sum of those
         * alone passes the limit is over it whatever the roots would have
         * said. The answer is the same as before for every shape, nearer
         * ones included, which then take the exact pass as they always did.
         */
        fun distance(a: ByteArray, b: ByteArray, abortAbove: Float = Float.MAX_VALUE): Float {
            val n = GlideWorkspace.SHAPE_POINTS
            val limit = abortAbove * n * QUANT
            if (abortAbove < Float.MAX_VALUE) {
                var bound = 0
                for (j in 0 until n) {
                    val dx = abs(a[2 * j] - b[2 * j])
                    val dy = abs(a[2 * j + 1] - b[2 * j + 1])
                    bound += if (dx > dy) dx else dy
                    if (bound > limit) return abortAbove + 1f
                }
            }
            var sum = 0f
            for (j in 0 until n) {
                val dx = (a[2 * j] - b[2 * j]).toFloat()
                val dy = (a[2 * j + 1] - b[2 * j + 1]).toFloat()
                sum += sqrt(dx * dx + dy * dy)
                if (sum > limit) return abortAbove + 1f
            }
            return sum / (n * QUANT)
        }
    }
}

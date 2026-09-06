package com.wasimaster.wmkeyboard.core.vocab

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a flashcard went. [quality] is the SM-2 grade the answer maps to. */
enum class ReviewGrade(val quality: Int) {
    AGAIN(1),
    HARD(3),
    GOOD(4),
    EASY(5),
}

/** One review, kept so the app can draw a history. */
@Serializable
data class ReviewEvent(
    val day: Int,
    val grade: Int,
    val scheme: String = "",
)

/**
 * Where one word stands. Both schedulers keep their state here so a user
 * who switches scheme mid-way keeps what the other one learned about the
 * word: [box] is Leitner's, [ease]/[intervalDays]/[reps] are SM-2's, and
 * [dueDay]/[learnt] are what everybody reads.
 */
@Serializable
data class WordProgress(
    val box: Int = 0,
    val ease: Double = Sm2Scheduler.START_EASE,
    val intervalDays: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    /** Local epoch day the next review is due; 0 means never scheduled. */
    val dueDay: Int = 0,
    val firstDay: Int = 0,
    val lastDay: Int = 0,
    val learnt: Boolean = false,
    val history: List<ReviewEvent> = emptyList(),
) {
    val seen: Boolean get() = firstDay > 0 || learnt
}

/** The numbers a progress screen shows for one pack or for everything. */
data class ProgressStats(
    val seen: Int,
    val learnt: Int,
    val due: Int,
    val reviewedToday: Int,
)

/**
 * The user's learning record: one [WordProgress] per word, keyed by lemma
 * rather than by pack so it survives packs being deleted and downloaded
 * again, plus which word each day drew as its word of the day.
 *
 * Same personal-store contract as `TypingStats`: a nullable file (direct
 * boot → memory only), a dirty flag with an explicit [save], and [reload]
 * for edits by the other process. The keyboard and the settings app both
 * write this file; each saves promptly after a change and re-reads on its
 * next opening, which is enough because a review is a tap, not a stream.
 */
class VocabProgress(private var storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val words: Map<String, WordProgress> = emptyMap(),
        /** Local epoch day → the lemma drawn for it. */
        val daily: Map<Int, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val words = HashMap<String, WordProgress>()
    private val daily = HashMap<Int, String>()
    private var dirty = false
    private var loadedLength = -1L
    private var loadedModified = -1L

    init {
        load()
    }

    /** Points a store created blind (direct boot) at its file once the user unlocks. */
    @Synchronized
    fun attach(file: File) {
        if (storageFile == file) return
        storageFile = file
        load()
    }

    @Synchronized
    fun stateOf(word: String): WordProgress = words[word] ?: WordProgress()

    @Synchronized
    fun isLearnt(word: String): Boolean = words[word]?.learnt == true

    @Synchronized
    fun isSeen(word: String): Boolean = words[word]?.seen == true

    /** Applies one flashcard answer and returns the word's new state. */
    @Synchronized
    fun review(word: String, grade: ReviewGrade, nowDay: Int, scheme: VocabScheduler): WordProgress {
        val next = preview(word, grade, nowDay, scheme)
        words[word] = next
        dirty = true
        return next
    }

    /** What [review] would produce, for interval previews on the grade buttons. */
    @Synchronized
    fun preview(word: String, grade: ReviewGrade, nowDay: Int, scheme: VocabScheduler): WordProgress {
        val current = stateOf(word)
        val scheduled = when (scheme) {
            VocabScheduler.LEITNER -> LeitnerScheduler.next(current, grade, nowDay)
            VocabScheduler.SM2 -> Sm2Scheduler.next(current, grade, nowDay)
        }
        val history = (current.history + ReviewEvent(nowDay, grade.quality, scheme.name)).takeLast(MAX_HISTORY)
        return scheduled.copy(
            firstDay = if (current.firstDay == 0) nowDay else current.firstDay,
            lastDay = nowDay,
            history = history,
        )
    }

    /** Marks a word known (or unknown again) by hand; a known word stops being due. */
    @Synchronized
    fun markLearnt(word: String, learnt: Boolean, nowDay: Int) {
        val current = stateOf(word)
        words[word] = if (learnt) {
            current.copy(
                learnt = true,
                box = LeitnerScheduler.TOP,
                firstDay = if (current.firstDay == 0) nowDay else current.firstDay,
                lastDay = nowDay,
            )
        } else {
            current.copy(learnt = false, box = 0, intervalDays = 0, reps = 0, dueDay = nowDay)
        }
        dirty = true
    }

    /** Forgets everything about [word]. */
    @Synchronized
    fun reset(word: String) {
        if (words.remove(word) != null) dirty = true
    }

    /**
     * Words due on or before [day]: seen, not learnt, and scheduled. Sorted
     * most-overdue first. [among] narrows to one pack's words.
     */
    @Synchronized
    fun dueWords(day: Int, among: Collection<String>? = null): List<String> {
        val candidates = among ?: words.keys
        return candidates
            .filter { lemma ->
                val state = words[lemma] ?: return@filter false
                state.seen && !state.learnt && state.dueDay in 1..day
            }
            .sortedWith(compareBy({ words[it]?.dueDay ?: 0 }, { it }))
    }

    /** Words never reviewed and not marked learnt, in the given order. */
    @Synchronized
    fun unseen(among: Collection<String>): List<String> =
        among.filter { words[it]?.seen != true }

    @Synchronized
    fun stats(day: Int, among: Collection<String>? = null): ProgressStats {
        val candidates = among ?: words.keys
        var seen = 0
        var learnt = 0
        var due = 0
        var today = 0
        for (lemma in candidates) {
            val state = words[lemma] ?: continue
            if (state.seen) seen++
            if (state.learnt) learnt++
            if (state.seen && !state.learnt && state.dueDay in 1..day) due++
            if (state.lastDay == day && state.history.isNotEmpty()) today++
        }
        return ProgressStats(seen = seen, learnt = learnt, due = due, reviewedToday = today)
    }

    /**
     * The word drawn for [day], pinned once drawn so a word marked learnt in
     * the afternoon does not change the morning's card. [candidates] are the
     * lemmas eligible today — normally every unlearnt word of the enabled
     * packs, sorted. Returns null when there is nothing to draw from.
     */
    @Synchronized
    fun wordOfTheDay(day: Int, candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        daily[day]?.let { pinned -> if (pinned in candidates) return pinned }
        val picked = WordOfDay.pick(day, candidates) ?: return null
        daily[day] = picked
        daily.keys.filter { it < day - DAILY_KEEP_DAYS }.forEach { daily.remove(it) }
        dirty = true
        return picked
    }

    /** The word already drawn for [day], without drawing one. */
    @Synchronized
    fun pinnedWordOfTheDay(day: Int): String? = daily[day]

    @Synchronized
    fun save() {
        val file = storageFile ?: return
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(json.encodeToString(Snapshot(words = words.toSortedMap(), daily = daily.toSortedMap())))
            file.delete()
            part.renameTo(file)
            loadedLength = file.length()
            loadedModified = file.lastModified()
            dirty = false
        }
    }

    /** Re-reads the file, dropping unsaved changes; for an edit by the other process. */
    @Synchronized
    fun reload() {
        load()
    }

    /** [reload]s only when the file changed since it was last read; true when it did. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        val file = storageFile ?: return false
        if (file.length() == loadedLength && file.lastModified() == loadedModified) return false
        load()
        return true
    }

    private fun load() {
        words.clear()
        daily.clear()
        dirty = false
        val file = storageFile ?: return
        loadedLength = file.length()
        loadedModified = file.lastModified()
        if (!file.isFile) return
        val snapshot = runCatching { json.decodeFromString<Snapshot>(file.readText()) }.getOrNull() ?: return
        words.putAll(snapshot.words)
        daily.putAll(snapshot.daily)
    }

    companion object {
        const val FILE_PATH = "vocab/progress.json"
        const val MAX_HISTORY = 50
        private const val DAILY_KEEP_DAYS = 7
    }
}

/**
 * Five boxes with growing gaps. A right answer moves the card up a box, a
 * wrong one sends it back to the first; a card that leaves the top box is
 * learnt. Simple enough to draw as a ladder on a keyboard panel.
 */
object LeitnerScheduler {
    val INTERVALS = intArrayOf(1, 3, 7, 14, 30)
    val TOP = INTERVALS.size - 1

    fun next(state: WordProgress, grade: ReviewGrade, nowDay: Int): WordProgress {
        val box = when (grade) {
            ReviewGrade.AGAIN -> 0
            ReviewGrade.HARD -> state.box.coerceIn(0, TOP)
            ReviewGrade.GOOD -> (state.box + 1).coerceAtMost(TOP)
            ReviewGrade.EASY -> (state.box + 2).coerceAtMost(TOP)
        }
        val learnt = grade != ReviewGrade.AGAIN && grade != ReviewGrade.HARD && state.box >= TOP
        return state.copy(
            box = box,
            lapses = if (grade == ReviewGrade.AGAIN) state.lapses + 1 else state.lapses,
            dueDay = nowDay + INTERVALS[box],
            learnt = learnt,
        )
    }
}

/**
 * SuperMemo 2: an ease factor per card that a good answer nudges up and a
 * bad one down, with intervals of 1 day, 6 days, then the last interval
 * times the ease. A card whose interval passes two months is learnt.
 */
object Sm2Scheduler {
    const val START_EASE = 2.5
    const val MIN_EASE = 1.3
    const val LEARNT_INTERVAL_DAYS = 60

    fun next(state: WordProgress, grade: ReviewGrade, nowDay: Int): WordProgress {
        val q = grade.quality
        val ease = (state.ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)).coerceAtLeast(MIN_EASE)
        if (q < 3) {
            return state.copy(
                ease = ease,
                reps = 0,
                intervalDays = 1,
                lapses = state.lapses + 1,
                dueDay = nowDay + 1,
                learnt = false,
            )
        }
        val interval = when (state.reps) {
            0 -> 1
            1 -> 6
            else -> Math.round(state.intervalDays * ease).toInt().coerceAtLeast(state.intervalDays + 1)
        }
        val boosted = if (grade == ReviewGrade.EASY && state.reps >= 2) Math.round(interval * 1.3).toInt() else interval
        return state.copy(
            ease = ease,
            reps = state.reps + 1,
            intervalDays = boosted,
            dueDay = nowDay + boosted,
            learnt = boosted >= LEARNT_INTERVAL_DAYS,
        )
    }
}

/**
 * A deterministic draw for a day: the same day and the same candidates give
 * the same word on every device and in both processes, with no state.
 */
object WordOfDay {

    fun pick(day: Int, sortedCandidates: List<String>, exclude: Set<String> = emptySet()): String? {
        if (sortedCandidates.isEmpty()) return null
        val start = Math.floorMod(splitMix64(day.toLong()), sortedCandidates.size.toLong()).toInt()
        for (offset in sortedCandidates.indices) {
            val candidate = sortedCandidates[(start + offset) % sortedCandidates.size]
            if (candidate !in exclude) return candidate
        }
        return null
    }

    private fun splitMix64(seed: Long): Long {
        var z = seed + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}

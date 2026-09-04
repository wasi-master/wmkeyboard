package com.wasimaster.wmkeyboard.core.prediction

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The user's personal vocabulary: words they have typed and the bigrams
 * between them, used for personalized completion and next-word prediction.
 *
 * Persisted as JSON in app-private storage. All typing data stays on
 * device; [clear] wipes it for the privacy setting.
 */
class UserLexicon(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val words: Map<String, Int> = emptyMap(),
        val bigrams: Map<String, Map<String, Int>> = emptyMap(),
        /** Save-session counter — the decay clock. Old files default to 0. */
        val generation: Long = 0L,
        /** Per-word last-touched generation, for lazy decay at compaction. */
        val wordGen: Map<String, Long> = emptyMap(),
        /** Trigram contexts, keyed "prev2<NUL>prev1". Additive; old files
         * simply have none. */
        val trigrams: Map<String, Map<String, Int>> = emptyMap(),
        /** Language id each word was last learned under. Additive; words
         * with no entry (legacy files, settings-app adds) are untagged and
         * treated as belonging to every language. */
        val wordLang: Map<String, String> = emptyMap(),
    )

    /** A word's followers plus a lazily cached count-descending order, so the
     * per-strip-refresh nextWords read stops re-sorting on every call. */
    private class Followers {
        val counts = HashMap<String, Int>()
        var sorted: List<String>? = null

        fun bump(next: String) {
            counts.merge(next, 1, Int::plus)
            sorted = null
            if (counts.size > MAX_FOLLOWERS) {
                counts.remove(counts.minByOrNull { it.value }?.key)
            }
        }

        /** Moves [old]'s count onto [new], adding to any count [new] already has. */
        fun rename(old: String, new: String) {
            val moved = counts.remove(old) ?: return
            counts.merge(new, moved, Int::plus)
            sorted = null
        }

        /** Folds [other]'s counts into this one, then re-applies the follower cap. */
        fun absorb(other: Followers) {
            for ((next, count) in other.counts) counts.merge(next, count, Int::plus)
            sorted = null
            while (counts.size > MAX_FOLLOWERS) {
                counts.remove(counts.minByOrNull { it.value }?.key)
            }
        }

        fun ordered(): List<String> = sorted ?: counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .also { sorted = it }

        fun total(): Int = counts.values.sum()
    }

    private var trie = Trie()
    private val words = HashMap<String, Int>()
    private val bigrams = HashMap<String, Followers>()
    private val trigrams = HashMap<String, Followers>()
    private val wordGen = HashMap<String, Long>()
    private val wordLangs = HashMap<String, String>()
    private var generation = 0L
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether anything has been learned since the file last matched memory.
     *
     * [save] runs on the main thread every time the keyboard is dismissed, and
     * it serialises every word and bigram the user has ever typed. Most
     * dismissals have nothing new to write — the keyboard came up, the user
     * tapped a suggestion or typed nothing at all, and it went away again — so
     * the flag turns those into a return rather than a re-encode and a rewrite
     * of the whole file. It is deliberately not a "save later" scheme: when
     * there *is* something new it is still written synchronously, before the
     * process can be killed with the user's new words only in memory.
     */
    private var dirty = false

    /**
     * Bumped whenever the word set changes, so the engine's cached walk
     * results can key on it. Volatile: read lock-free on the suggestion
     * coroutine while learning happens on the main thread.
     */
    @Volatile
    private var mutations = 0L

    fun mutationCount(): Long = mutations

    init {
        load()
    }

    /**
     * [count] grades the strength of the signal: a suggestion the user
     * deliberately tapped teaches harder than a word that merely got
     * committed in passing.
     */
    @Synchronized
    fun learnWord(word: String, count: Int = 1, langId: String = "") {
        val key = WordKey.of(word)
        if (key.length < 2 || key.length > MAX_WORD_LENGTH || count <= 0) return
        val before = words[key] ?: 0
        val merged = (before.toLong() + count).coerceAtMost(MAX_COUNT.toLong()).toInt()
        words[key] = merged
        // Reinforce by the clamped delta so the trie's copy can never race
        // past the cap (or overflow) while the word map stays clamped.
        trie.reinforce(key, merged - before)
        wordGen[key] = generation
        // Most-recent language wins: a word the user types under several
        // languages keeps flipping its tag, which is harmless — the engine
        // only damps a tag that *disagrees* with the active language, and a
        // genuinely shared word keeps being re-tagged to whatever is active.
        if (langId.isNotBlank()) wordLangs[key] = langId
        mutations++
        dirty = true
    }

    /**
     * Language id [word] was last learned under, or null for untagged words
     * (settings-app additions, legacy files) — untagged means "any language".
     */
    @Synchronized
    fun languageOf(word: String): String? = wordLangs[WordKey.of(word)]

    /**
     * User-added dictionary entry: weighted like a word typed [boost]
     * times so it competes with genuinely frequent words immediately and
     * is never "corrected" away.
     */
    @Synchronized
    fun addWord(word: String, boost: Int = 200) {
        val key = WordKey.of(word.trim())
        if (key.isEmpty() || key.length > MAX_WORD_LENGTH) return
        val before = words[key] ?: 0
        val merged = (before.toLong() + boost).coerceAtMost(MAX_COUNT.toLong()).toInt()
        words[key] = merged
        trie.reinforce(key, merged - before)
        wordGen[key] = generation
        mutations++
        dirty = true
    }

    /**
     * Respells [word] as [replacement], keeping its count, language tag and
     * age, and carrying the word pairs and triples it took part in across
     * with it (personal dictionary screen, #47). Respelling onto a word that
     * already exists merges the two: counts add, clamped, and the existing
     * word keeps its own tag. Returns false, and touches nothing, when [word]
     * is unknown, when the new spelling is empty or too long, or when the two
     * spellings fold to the same key.
     */
    @Synchronized
    fun rename(word: String, replacement: String): Boolean {
        val oldKey = WordKey.of(word)
        val newKey = WordKey.of(replacement.trim())
        if (newKey.isEmpty() || newKey.length > MAX_WORD_LENGTH || oldKey == newKey) return false
        val count = words.remove(oldKey) ?: return false
        val existing = words[newKey] ?: 0
        words[newKey] = (existing.toLong() + count).coerceAtMost(MAX_COUNT.toLong()).toInt()
        val oldGen = wordGen.remove(oldKey) ?: generation
        wordGen[newKey] = maxOf(oldGen, wordGen[newKey] ?: 0L)
        val oldLang = wordLangs.remove(oldKey)
        if (oldLang != null && newKey !in wordLangs) wordLangs[newKey] = oldLang
        // Pairs where the word led: its follower set moves under the new key.
        bigrams.remove(oldKey)?.let { moved ->
            val target = bigrams[newKey]
            if (target == null) bigrams[newKey] = moved else target.absorb(moved)
        }
        // Pairs where it followed: the count moves within each follower set.
        bigrams.values.forEach { it.rename(oldKey, newKey) }
        // Triples: rewrite every context the word is part of, merging on
        // collision, then move it within the follower sets too.
        val contexts = trigrams.keys.filter { key ->
            key.split(TRIGRAM_SEPARATOR).any { it == oldKey }
        }
        for (context in contexts) {
            val moved = trigrams.remove(context) ?: continue
            val rewritten = context.split(TRIGRAM_SEPARATOR)
                .joinToString(TRIGRAM_SEPARATOR.toString()) { if (it == oldKey) newKey else it }
            val target = trigrams[rewritten]
            if (target == null) trigrams[rewritten] = moved else target.absorb(moved)
        }
        trigrams.values.forEach { it.rename(oldKey, newKey) }
        rebuildTrie()
        mutations++
        dirty = true
        return true
    }

    /**
     * Sets a known word's weight outright, clamped to `1..MAX_COUNT`, so the
     * personal dictionary screen can raise or lower how hard a word competes
     * (#47). Unknown words are left alone: use [addWord] for those. Lowering
     * a weight has to rebuild the trie, since its per-node upper bounds only
     * ever grow; this is a settings-app path, never the typing path.
     */
    @Synchronized
    fun setCount(word: String, count: Int): Boolean {
        val key = WordKey.of(word)
        if (key !in words) return false
        words[key] = count.coerceIn(1, MAX_COUNT)
        wordGen[key] = generation
        rebuildTrie()
        mutations++
        dirty = true
        return true
    }

    /**
     * Re-reads the storage file. The settings app edits the file directly
     * (personal dictionary screen); the IME calls this when signalled so
     * its in-memory copy doesn't clobber those edits on the next save.
     */
    @Synchronized
    fun reload() {
        words.clear()
        bigrams.clear()
        trigrams.clear()
        wordGen.clear()
        wordLangs.clear()
        rebuildTrie()
        load()
        mutations++
        // Memory is the file again, so there is nothing outstanding to write —
        // and writing would clobber the settings-app edit this reload exists
        // to pick up.
        dirty = false
    }

    @Synchronized
    fun learnBigram(previous: String, next: String) {
        val prev = WordKey.of(previous)
        val nxt = WordKey.of(next)
        if (prev.isEmpty() || nxt.isEmpty()) return
        if (prev.length > MAX_WORD_LENGTH || nxt.length > MAX_WORD_LENGTH) return
        bigrams.getOrPut(prev) { Followers() }.bump(nxt)
        dirty = true
    }

    @Synchronized
    fun nextWords(previous: String, limit: Int): List<String> =
        bigrams[WordKey.of(previous)]?.ordered()?.take(limit).orEmpty()

    @Synchronized
    fun learnTrigram(prev2: String, prev1: String, next: String) {
        val a = WordKey.of(prev2)
        val b = WordKey.of(prev1)
        val c = WordKey.of(next)
        if (a.isEmpty() || b.isEmpty() || c.isEmpty()) return
        if (a.length > MAX_WORD_LENGTH || b.length > MAX_WORD_LENGTH ||
            c.length > MAX_WORD_LENGTH
        ) {
            return
        }
        trigrams.getOrPut(trigramKey(a, b)) { Followers() }.bump(c)
        dirty = true
    }

    /** Followers of the two-word context (prev2, prev1), best first. More
     * specific than [nextWords]; callers consult this first and fall back. */
    @Synchronized
    fun nextWordsAfter(prev2: String, prev1: String, limit: Int): List<String> =
        trigrams[trigramKey(WordKey.of(prev2), WordKey.of(prev1))]
            ?.ordered()?.take(limit).orEmpty()

    /** Learned count of ((prev2, prev1) -> next), 0 when never seen. */
    @Synchronized
    fun trigramCount(prev2: String, prev1: String, next: String): Int =
        trigrams[trigramKey(WordKey.of(prev2), WordKey.of(prev1))]
            ?.counts?.get(WordKey.of(next)) ?: 0

    /** Learned count of the pair (previous -> next), 0 when never seen. */
    @Synchronized
    fun bigramCount(previous: String, next: String): Int =
        bigrams[WordKey.of(previous)]?.counts?.get(WordKey.of(next)) ?: 0

    /** Defensive copy of a word's follower counts (capped at MAX_FOLLOWERS). */
    @Synchronized
    fun followerCounts(previous: String): Map<String, Int> =
        bigrams[WordKey.of(previous)]?.counts?.let(::HashMap).orEmpty()

    @Synchronized
    fun complete(prefix: String, limit: Int): List<Suggestion> = trie.complete(prefix, limit)

    /**
     * Walkers over the learned-word trie for the fuzzy beam search. The walker
     * escapes this monitor, so a walk concurrent with learning has the same
     * (pre-existing, benign) staleness hazard as [complete] — callers
     * invalidate their caches on lexicon change.
     */
    @Synchronized
    fun walkers(): List<TrieWalker> = trie.walkers()

    @Synchronized
    fun contains(word: String): Boolean = trie.contains(WordKey.of(word))

    /**
     * Whether the word has been seen often enough to be treated as one the
     * user really means, rather than one they typed once.
     *
     * The difference matters for autocorrect: a learned word is exempt from
     * correction, so at a threshold of 1 a single committed typo is
     * permanently protected. Suggestion ranking is unaffected either way, so a
     * word below the threshold can still be offered, just not shielded.
     */
    @Synchronized
    fun isEstablished(word: String, minCount: Int): Boolean {
        val key = WordKey.of(word)
        if (!trie.contains(key)) return false
        return minCount <= 1 || (words[key] ?: 0) >= minCount
    }

    @Synchronized
    fun frequencyOf(word: String): Int = trie.frequencyOf(WordKey.of(word))

    /** Snapshot of all learned words with their counts. */
    @Synchronized
    fun allWords(): List<Pair<String, Int>> = words.toList()

    @Synchronized
    fun forget(word: String) {
        forgetAll(listOf(word))
    }

    /**
     * Drops every word in [victims] in one pass.
     *
     * The trie is append-only, so each removal rebuilds it; doing that once for
     * a thousand words rather than a thousand times is the difference between
     * the settings screen's "clean up" finishing and appearing to hang.
     */
    @Synchronized
    fun forgetAll(victims: Collection<String>) {
        val keys = victims.mapTo(HashSet()) { WordKey.of(it) }
        if (keys.isEmpty()) return
        for (key in keys) {
            words.remove(key)
            wordGen.remove(key)
            wordLangs.remove(key)
            bigrams.remove(key)
        }
        bigrams.values.forEach { followers ->
            if (followers.counts.keys.removeAll(keys)) followers.sorted = null
        }
        trigrams.keys.removeAll { context ->
            context.split(TRIGRAM_SEPARATOR).any { it in keys }
        }
        trigrams.values.forEach { followers ->
            if (followers.counts.keys.removeAll(keys)) followers.sorted = null
        }
        rebuildTrie()
        mutations++
        dirty = true
    }

    @Synchronized
    fun clear() {
        words.clear()
        bigrams.clear()
        trigrams.clear()
        wordGen.clear()
        wordLangs.clear()
        rebuildTrie()
        mutations++
        // The delete is the write, so there is normally nothing left to save.
        // If it failed, the file still holds the data this call was meant to
        // wipe — stay dirty so the next save overwrites it with the empty
        // snapshot, which is what the old unconditional save did.
        dirty = storageFile?.delete() == false
    }

    @Synchronized
    fun save() {
        val file = storageFile ?: return
        if (!dirty) return
        generation++
        compactIfNeeded()
        val snapshot = Snapshot(
            words = words,
            bigrams = bigrams.mapValues { it.value.counts.toMap() },
            generation = generation,
            wordGen = wordGen,
            trigrams = trigrams.mapValues { it.value.counts.toMap() },
            wordLang = wordLangs,
        )
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(snapshot))
        // Only on success: a write that failed leaves the file behind memory,
        // and the next dismissal should try again rather than assume it landed.
        }.onSuccess { dirty = false }
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            words.putAll(snapshot.words)
            snapshot.bigrams.forEach { (prev, map) ->
                bigrams[prev] = Followers().also { it.counts.putAll(map) }
            }
            snapshot.trigrams.forEach { (context, map) ->
                trigrams[context] = Followers().also { it.counts.putAll(map) }
            }
            generation = snapshot.generation
            // Words with no recorded generation (legacy files, settings-app
            // rewrites) are treated as fresh rather than instantly decayed;
            // orphaned entries for words no longer present are dropped.
            for (word in words.keys) {
                wordGen[word] = snapshot.wordGen[word] ?: snapshot.generation
                snapshot.wordLang[word]?.let { wordLangs[word] = it }
            }
            rebuildTrie()
        }
    }

    /** Trie is append-only, so removal rebuilds it from the word map. */
    private fun rebuildTrie() {
        val fresh = Trie()
        for ((word, count) in words) fresh.insert(word, count)
        trie = fresh
    }

    /**
     * Bounds the store, running only on a dirty save (dismissal-time, main
     * thread — the same moment that already rewrites the whole file). Words
     * are scored with lazy exponential decay — `count * 2^(-age/HALF_LIFE)`
     * with age in save-generations — so a year-old typo-learn finally loses
     * to anything the user still types, while raw counts are never rewritten
     * on the hot path. Deliberately user-added words ([addWord], count >=
     * STICKY_MIN_COUNT) are evicted only after every organic word is gone.
     */
    private fun compactIfNeeded() {
        if (words.size > MAX_WORDS) {
            fun score(word: String): Double {
                val age = (generation - (wordGen[word] ?: generation)).coerceAtLeast(0L)
                val halfLives = age.toDouble() / HALF_LIFE_GENERATIONS
                return (words[word] ?: 0) * Math.pow(2.0, -halfLives)
            }
            val evictable = words.keys
                .sortedWith(
                    compareBy(
                        { (words[it] ?: 0) >= STICKY_MIN_COUNT },
                        { score(it) },
                    )
                )
            val toEvict = evictable.take(words.size - EVICT_TO)
            for (word in toEvict) {
                words.remove(word)
                wordGen.remove(word)
                wordLangs.remove(word)
                bigrams.remove(word)
                bigrams.values.forEach {
                    if (it.counts.remove(word) != null) it.sorted = null
                }
            }
            rebuildTrie()
            mutations++
        }
        if (bigrams.size > MAX_BIGRAM_PREVS) {
            val evictable = bigrams.entries.sortedBy { it.value.total() }
            for (entry in evictable.take(bigrams.size - MAX_BIGRAM_PREVS)) {
                bigrams.remove(entry.key)
            }
        }
        if (trigrams.size > MAX_TRIGRAM_CONTEXTS) {
            val evictable = trigrams.entries.sortedBy { it.value.total() }
            for (entry in evictable.take(trigrams.size - MAX_TRIGRAM_CONTEXTS)) {
                trigrams.remove(entry.key)
            }
        }
    }

    companion object {
        /** Longest word the store keeps, in folded characters. */
        const val MAX_WORD_LENGTH = 32
        /** Ceiling on a word's count. 1e6 x USER_WORD_WEIGHT(500) stays far
         * inside Int range. Public so the personal dictionary screen can
         * bound the weight it lets the user type. */
        const val MAX_COUNT = 1_000_000
        private const val MAX_WORDS = 10_000
        /** Eviction target below the cap: 10% hysteresis so compaction does
         * not churn on every save once the cap is reached. */
        private const val EVICT_TO = 9_000
        private const val MAX_BIGRAM_PREVS = 5_000
        private const val MAX_TRIGRAM_CONTEXTS = 2_000
        private const val MAX_FOLLOWERS = 32

        /** NUL, built rather than written literally. */
        private val TRIGRAM_SEPARATOR: Char = 0.toChar()

        private fun trigramKey(prev2: String, prev1: String): String =
            prev2 + TRIGRAM_SEPARATOR + prev1
        private const val HALF_LIFE_GENERATIONS = 64.0
        /** addWord's default boost lands at 200; organic words rarely reach
         * this, so it doubles as the "deliberately added" marker. */
        private const val STICKY_MIN_COUNT = 100
    }
}

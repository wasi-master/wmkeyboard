package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import java.io.File

/**
 * The user's personal vocabulary: words they have typed and the bigrams,
 * trigrams and 1-skip bigrams between them, used for personalized
 * completion, next-word prediction and the context reranker.
 *
 * Persisted as JSON in app-private storage. All typing data stays on
 * device; [clear] wipes it for the privacy setting.
 *
 * The store is Rust (`wm-engine`'s `user_lexicon`, which WMK Desktop runs
 * too, behind [PredictionNative]'s `lex*` calls): the counts, the learning
 * rules (the case vote, the follower cap, compaction by decayed count) and
 * the file's format, and on a load the folding and judging of the words the
 * file holds. This class folds each word a caller passes to the key every
 * store files it under ([WordKey]), judges which words may be learned at all
 * ([WordContext.isLearnableWord]), and owns the file itself ([SnapshotFile]'s
 * atomic writes). Every method takes a word in any spelling, as before.
 *
 * Thread-safe: the Rust store sits behind a read-write lock, and the
 * operations that span several calls (load, save, reload, clear) hold this
 * object's monitor as the Kotlin store's did.
 */
class UserLexicon(private val storageFile: File?) {

    private val native: Long = PredictionNative.lexNew().also {
        NativeHandles.track(this, it, PredictionNative::lexClose)
    }

    /** The Rust store, for the Rust code that reads it ([NgramReranker], the fuzzy walk). */
    internal val nativeHandle: Long get() = native

    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /** The trie the Kotlin walks read, copied out of Rust when it changes. */
    @Volatile
    private var exported: LexiconTrie? = null

    init {
        load()
    }

    /**
     * Bumped whenever the word set changes, so the engine's cached walk
     * results can key on it.
     */
    fun mutationCount(): Long = PredictionNative.lexMutationCount(native)

    /**
     * [count] grades the strength of the signal: a suggestion the user
     * deliberately tapped teaches harder than a word that merely got
     * committed in passing.
     *
     * Returns whether the word was taken. The automatic path ends here, so
     * this is where a word that is not a word is refused (#185) — see
     * [WordContext.isLearnableWord]; [addWord], the deliberate path, does not
     * ask, because a word the user typed into a dialog is theirs to spell.
     *
     * [caseEvidence] says the capital in [word] is the user's own (#44), not
     * auto-capitalize's; [listedInLowerCase] that a loaded word list already
     * spells it in lower case, so one shifted sighting does not flip it.
     */
    fun learnWord(
        word: String,
        count: Int = 1,
        langId: String = "",
        caseEvidence: Boolean = false,
        listedInLowerCase: Boolean = false,
    ): Boolean {
        val key = WordKey.of(word)
        if (key.isEmpty() || key.length > MAX_WORD_LENGTH || count <= 0) return false
        if (!WordContext.isLearnableWord(key)) return false
        val vote = if (caseEvidence) WordKey.surface(word).takeIf { WordKey.of(it) == key } else null
        return PredictionNative.lexLearnWord(
            native, key, vote, count, langId.takeIf { it.isNotBlank() }.orEmpty(), listedInLowerCase,
        )
    }

    /**
     * Language id [word] was last learned under, or the one the user filed it
     * under ([setLanguage]), or null for untagged words (settings-app
     * additions, legacy files) — untagged means "any language".
     */
    fun languageOf(word: String): String? = PredictionNative.lexLanguageOf(native, WordKey.of(word))

    /**
     * Files [word] under [langId], or under every language when it is null,
     * from the personal dictionary screen (#930). The choice is pinned:
     * typing the word under another language later does not move it, as an
     * unpinned word moves to wherever it was last typed. Returns whether
     * anything changed; false for a word the store does not have.
     */
    fun setLanguage(word: String, langId: String?): Boolean =
        PredictionNative.lexSetLanguage(native, WordKey.of(word), langId?.takeIf { it.isNotBlank() }.orEmpty())

    /** Whether [word]'s language was chosen by hand (#930); see [setLanguage]. */
    fun isLanguagePinned(word: String): Boolean = PredictionNative.lexIsLanguagePinned(native, WordKey.of(word))

    /**
     * The spelling [word] should be written in — "Boston" for a key of
     * "boston" — or null when the user writes it in lower case (#44). Fed
     * only by spellings the user chose: a streaming majority vote per word,
     * which a word pinned by hand (#100) does not take.
     */
    fun displayOf(word: String): String? = PredictionNative.lexDisplayOf(native, WordKey.of(word))

    /**
     * User-added dictionary entry, counted like a word typed [boost] times
     * (#164). A word added by hand is never "corrected" away whatever the
     * learn-after threshold ([isEstablished]), and compaction evicts it last.
     * With [caseEvidence] its spelling is settled outright, and [pinCase]
     * keeps it past every later vote (#100).
     *
     * [langId] is the language the word was added while typing, from the
     * keyboard's own Add. A word with no language yet is filed under it, as
     * [setLanguage] files it, so an Arabic word added on the Arabic keyboard
     * stays Arabic instead of joining every language's list, English's
     * included. A word already filed somewhere keeps its place, and a blank
     * [langId] (the settings screen, an import) leaves it under every
     * language as before.
     */
    fun addWord(
        word: String,
        boost: Int = 1,
        caseEvidence: Boolean = true,
        pinCase: Boolean = caseEvidence,
        langId: String = "",
    ) {
        val trimmed = word.trim()
        val key = WordKey.of(trimmed)
        if (key.isEmpty() || key.length > MAX_WORD_LENGTH || boost <= 0) return
        val surface = WordKey.surface(trimmed).takeIf { WordKey.of(it) == key }
        PredictionNative.lexAddWord(native, key, caseEvidence, surface, boost, pinCase)
        if (langId.isNotBlank() && PredictionNative.lexLanguageOf(native, key) == null) {
            PredictionNative.lexSetLanguage(native, key, langId)
        }
    }

    /** Whether the user put [word] in themselves (#164); see [addWord]. */
    fun isAddedByHand(word: String): Boolean = PredictionNative.lexIsAddedByHand(native, WordKey.of(word))

    /**
     * When [word] first joined the store, as a save generation: a larger
     * number is a newer word (#194). It is an order, not a date. Null for a
     * word the store does not have.
     */
    fun addedGeneration(word: String): Long? =
        PredictionNative.lexAddedGeneration(native, WordKey.of(word)).takeIf { it != Long.MIN_VALUE }

    /**
     * Respells [word] as [replacement], keeping its count, language tag and
     * age, and carrying the word pairs and triples it took part in across
     * with it (personal dictionary screen, #47). Respelling onto a word that
     * already exists merges the two. A respelling that only moves capitals
     * about settles and pins the word's case instead. Returns false, and
     * touches nothing, when [word] is unknown or the new spelling is empty or
     * too long.
     */
    fun rename(word: String, replacement: String): Boolean {
        val trimmed = replacement.trim()
        val newKey = WordKey.of(trimmed)
        val surface = WordKey.surface(trimmed)
        return PredictionNative.lexRename(native, WordKey.of(word), newKey, surface, WordKey.of(surface) == newKey)
    }

    /**
     * Sets a known word's weight outright, clamped to `1..MAX_COUNT`, so the
     * personal dictionary screen can raise or lower how hard a word competes
     * (#47). Unknown words are left alone.
     */
    fun setCount(word: String, count: Int): Boolean = PredictionNative.lexSetCount(native, WordKey.of(word), count)

    /** Whether [word]'s spelling is pinned against the case vote (#100). */
    fun isCasePinned(word: String): Boolean = PredictionNative.lexIsCasePinned(native, WordKey.of(word))

    /**
     * Pins or releases [word]'s current spelling (#100). Returns whether
     * anything changed.
     */
    fun pinCase(word: String, pinned: Boolean): Boolean = PredictionNative.lexPinCase(native, WordKey.of(word), pinned)

    /**
     * Re-reads the storage file. The settings app edits the file directly
     * (personal dictionary screen); the IME calls this when signalled so
     * its in-memory copy doesn't clobber those edits on the next save.
     */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        PredictionNative.lexResetForReload(native)
        load()
        // Memory is the file again, so there is nothing outstanding to write.
        PredictionNative.lexFinishReload(native)
    }

    fun learnBigram(previous: String, next: String) = learn(BIGRAM, previous, next)

    fun nextWords(previous: String, limit: Int): List<String> =
        PredictionNative.lexFollowers(native, BIGRAM, WordKey.of(previous), "", limit).asList()

    fun learnTrigram(prev2: String, prev1: String, next: String) {
        PredictionNative.lexLearnNgram(native, TRIGRAM, WordKey.of(prev2), WordKey.of(prev1), WordKey.of(next))
    }

    /** Followers of the two-word context (prev2, prev1), best first. More
     * specific than [nextWords]; callers consult this first and fall back. */
    fun nextWordsAfter(prev2: String, prev1: String, limit: Int): List<String> =
        PredictionNative.lexFollowers(native, TRIGRAM, WordKey.of(prev2), WordKey.of(prev1), limit).asList()

    /** Learned count of ((prev2, prev1) -> next), 0 when never seen. */
    fun trigramCount(prev2: String, prev1: String, next: String): Int =
        PredictionNative.lexNgramCount(native, TRIGRAM, WordKey.of(prev2), WordKey.of(prev1), WordKey.of(next))

    /**
     * Learns the gappy pair (prev2 -> next): [prev2] stood two words before
     * [next], with anything at all in between (#195). Read by the context
     * reranker alone, never offered as a next word.
     */
    fun learnSkip1gram(prev2: String, next: String) = learn(SKIP1, prev2, next)

    /** Learned count of the gappy pair (prev2 -> next), 0 when never seen. */
    fun skip1gramCount(prev2: String, next: String): Int = count(SKIP1, prev2, next)

    /** Words that have followed [prev2] one word later, best first. */
    fun skip1Followers(prev2: String, limit: Int): List<String> =
        PredictionNative.lexFollowers(native, SKIP1, WordKey.of(prev2), "", limit).asList()

    /** Words that have followed [prev3] two words later, best first. */
    fun skip2Followers(prev3: String, limit: Int): List<String> =
        PredictionNative.lexFollowers(native, SKIP2, WordKey.of(prev3), "", limit).asList()

    /** Learns the pair (prev3 -> next), [prev3] three words before [next]
     * with any two words between — the long-range twin of [learnSkip1gram]. */
    fun learnSkip2gram(prev3: String, next: String) = learn(SKIP2, prev3, next)

    /** Learned count of the pair (prev3 -> next), 0 when never seen. */
    fun skip2gramCount(prev3: String, next: String): Int = count(SKIP2, prev3, next)

    /** Learned count of the pair (previous -> next), 0 when never seen. */
    fun bigramCount(previous: String, next: String): Int = count(BIGRAM, previous, next)

    private fun learn(kind: Int, head: String, next: String) {
        PredictionNative.lexLearnNgram(native, kind, WordKey.of(head), WordKey.of(next), "")
    }

    private fun count(kind: Int, head: String, next: String): Int =
        PredictionNative.lexNgramCount(native, kind, WordKey.of(head), WordKey.of(next), "")

    /** A word's follower counts (capped at the store's follower limit). */
    fun followerCounts(previous: String): Map<String, Int> {
        val pair = PredictionNative.lexFollowerCounts(native, WordKey.of(previous))
        @Suppress("UNCHECKED_CAST")
        val words = pair[0] as Array<String>
        val counts = pair[1] as IntArray
        return HashMap<String, Int>(words.size * 2).apply { words.forEachIndexed { i, w -> put(w, counts[i]) } }
    }

    /** Completions of [prefix] from the learned words, best first, as typed (not folded). */
    fun complete(prefix: String, limit: Int): List<Suggestion> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val frequencies = IntArray(minOf(limit, wordCount().coerceAtLeast(1)))
        val words = PredictionNative.lexComplete(native, prefix, frequencies.size, frequencies)
        return List(words.size) { Suggestion(words[it], frequencies[it]) }
    }

    /**
     * Walkers over the learned-word trie, for the fuzzy beam search and the
     * engine's own walks. A copy of the Rust trie as it stood when asked,
     * refreshed when the trie changes; callers invalidate their caches on
     * lexicon change, as they always did.
     */
    fun walkers(): List<TrieWalker> = listOf(trie())

    @Synchronized
    private fun trie(): LexiconTrie {
        val version = PredictionNative.lexTrieVersion(native)
        exported?.takeIf { it.version == version }?.let { return it }
        return LexiconTrie.of(native, PredictionNative.lexExportTrie(native)).also { exported = it }
    }

    fun contains(word: String): Boolean = PredictionNative.lexContains(native, WordKey.of(word))

    /**
     * Whether the word has been seen often enough to be treated as one the
     * user really means, rather than one they typed once: a learned word is
     * exempt from correction, so at a threshold of 1 a single committed typo
     * would be protected for good. A word added by hand always is (#164).
     */
    fun isEstablished(word: String, minCount: Int): Boolean =
        PredictionNative.lexIsEstablished(native, WordKey.of(word), minCount)

    fun frequencyOf(word: String): Int = PredictionNative.lexFrequencyOf(native, WordKey.of(word))

    /**
     * How many words this user has learned. [allWords] answers the same
     * question by building the whole list, which is far too much for a
     * caller that only wants the count.
     */
    fun wordCount(): Int = PredictionNative.lexWordCount(native)

    /**
     * Every learned word with its count, in key order, each in the spelling
     * it is written in — so the personal dictionary screen shows "Boston",
     * not the "boston" it is filed under.
     */
    fun allWords(): List<Pair<String, Int>> {
        val pair = PredictionNative.lexAllWords(native)
        @Suppress("UNCHECKED_CAST")
        val words = pair[0] as Array<String>
        val counts = pair[1] as IntArray
        return List(words.size) { words[it] to counts[it] }
    }

    fun forget(word: String) {
        forgetAll(listOf(word))
    }

    /** Drops every word in [victims], their pairs and triples, in one pass. */
    fun forgetAll(victims: Collection<String>) {
        val keys = victims.mapTo(LinkedHashSet()) { WordKey.of(it) }
        if (keys.isEmpty()) return
        PredictionNative.lexForgetAll(native, keys.toTypedArray())
    }

    @Synchronized
    fun clear() {
        // The delete is the write, so there is normally nothing left to save.
        // If it failed, the file still holds the data this call was meant to
        // wipe, so the store stays dirty and the next save overwrites it.
        val failed = snapshotFile?.delete() == false
        PredictionNative.lexClear(native, !failed)
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, json) = synchronized(this) {
            val json = PredictionNative.lexPrepareSave(native) ?: return
            file.ticket() to json
        }
        // Written outside the lock, so the store stays usable while the file
        // goes to disk. A failed write makes the store dirty again, so the
        // next save retries rather than assuming it landed.
        if (!file.write(ticket) { json }) PredictionNative.lexMarkUnsaved(native)
    }

    /**
     * Reads the file into the store. The Rust side parses it, drops the
     * words that may not be learned (once, as #185 did) and keeps each
     * capital spelling that still folds to its word, by the same rules as
     * [WordContext.isLearnableWord] and [WordKey.of] (`word_key.rs`).
     */
    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        PredictionNative.lexLoad(native, text)
    }

    companion object {
        /** Longest word the store keeps, in folded characters. */
        const val MAX_WORD_LENGTH = 32

        /** Ceiling on a word's count. Public so the personal dictionary
         * screen can bound the weight it lets the user type. */
        const val MAX_COUNT = 1_000_000

        private const val BIGRAM = 0
        private const val TRIGRAM = 1
        private const val SKIP1 = 2
        private const val SKIP2 = 3
    }
}

/**
 * A copy of the Rust lexicon's trie for the Kotlin walks: flat arrays in the
 * Rust node numbering, children sorted by label as the Kotlin `Trie` kept
 * them. [nativeHandle] is the store it came from, so the native fuzzy walk
 * reads the trie in place instead ([NativeFuzzySearch]).
 */
internal class LexiconTrie private constructor(
    val nativeHandle: Long,
    val version: Long,
    private val childStart: IntArray,
    private val labels: CharArray,
    private val targets: IntArray,
    private val frequencies: IntArray,
    private val maxSubtrees: IntArray,
    private val words: BooleanArray,
) : TrieWalker {

    override fun child(node: Int, label: Char): Int {
        var lo = childStart[node]
        var hi = childStart[node + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = labels[mid]
            when {
                c < label -> lo = mid + 1
                c > label -> hi = mid - 1
                else -> return targets[mid]
            }
        }
        return -1
    }

    override fun childrenInto(node: Int, out: ChildBuffer): Int {
        val start = childStart[node]
        val count = childStart[node + 1] - start
        out.ensure(count)
        for (i in 0 until count) {
            out.labels[i] = labels[start + i]
            out.nodes[i] = targets[start + i]
        }
        return count
    }

    override fun isWord(node: Int): Boolean = words[node]

    override fun frequency(node: Int): Int = frequencies[node]

    override fun maxSubtree(node: Int): Int = maxSubtrees[node]

    /** Whether the trie holds nothing at all. */
    val isEmpty: Boolean get() = childStart[1] == 0 && !words[0]

    companion object {
        fun of(handle: Long, parts: Array<Any>): LexiconTrie = LexiconTrie(
            nativeHandle = handle,
            version = (parts[6] as LongArray)[0],
            childStart = parts[0] as IntArray,
            labels = parts[1] as CharArray,
            targets = parts[2] as IntArray,
            frequencies = parts[3] as IntArray,
            maxSubtrees = parts[4] as IntArray,
            words = parts[5] as BooleanArray,
        )
    }
}

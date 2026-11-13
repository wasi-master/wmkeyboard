package com.wasimaster.wmkeyboard.ime.semantic

import android.content.Context
import com.wasimaster.wmkeyboard.core.semantic.EmbeddingInput
import com.wasimaster.wmkeyboard.core.semantic.Embedder
import com.wasimaster.wmkeyboard.core.semantic.Embeddings
import com.wasimaster.wmkeyboard.core.semantic.EmojiVectors
import com.wasimaster.wmkeyboard.core.semantic.MeaningRank
import com.wasimaster.wmkeyboard.core.semantic.MeaningSync
import com.wasimaster.wmkeyboard.core.semantic.SemanticModels
import com.wasimaster.wmkeyboard.core.semantic.VectorIndex
import com.wasimaster.wmkeyboard.core.settings.SemanticSearchSettings
import java.io.File
import java.util.EnumMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The kinds of thing search by meaning looks through, one index each. */
internal enum class MeaningSpace(val fileName: String, val pictures: Boolean = false) {
    CLIPS("clips"),

    /** Clips that are pictures: screenshots and copied images, found by what they show. */
    CLIP_PICTURES("clip_pictures", pictures = true),
    NOTES("notes"),
    SNIPPETS("snippets"),
    EMOJI("emoji"),
    STICKERS("stickers", pictures = true),

    /** The phone's photos, for the gallery panel's search; only with the photos permission. */
    PHOTOS("photos", pictures = true),

    /** The AI's memories of the user; kept up to date by `AiMemoryRecall`, not by a panel. */
    MEMORIES("ai_memory"),
}

/**
 * Search by meaning inside the keyboard: an index per [MeaningSpace], kept up
 * to date in the background, and the queries the panels' search boxes ask
 * of it. The vectors themselves come from [Embeddings], in a process of its
 * own.
 *
 * Nothing here runs on the typing path. Indexing passes run on one
 * low-priority thread when a panel opens, a few hundred texts or a few dozen
 * pictures at a time, and a query waits for the typing in its box to pause.
 * With the feature off or the model absent every call is a no-op that
 * answers nothing, and the boxes show what keyword search found, as before.
 *
 * Indexes live in `noBackupFilesDir/semantic/index`. They are derived from
 * the clipboard, notes and snippets, so turning the feature off deletes them.
 */
internal class MeaningSearch(private val context: Context, private val scope: CoroutineScope) {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "meaning-index").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

    /** Touched only on [worker]. */
    private val indexes = EnumMap<MeaningSpace, VectorIndex>(MeaningSpace::class.java)

    /** Main thread only, like everything below that is not marked otherwise. */
    private val syncJobs = EnumMap<MeaningSpace, Job>(MeaningSpace::class.java)
    private val queuedItems = EnumMap<MeaningSpace, List<MeaningSync.Item>>(MeaningSpace::class.java)
    private val queryJobs = EnumMap<MeaningSpace, Job>(MeaningSpace::class.java)

    @Volatile var settings: SemanticSearchSettings = SemanticSearchSettings()
        private set

    @Volatile private var unlocked = false

    /** The last few query vectors, so one query asked of two spaces is embedded once. */
    private val queryCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > QUERY_CACHE
    }

    /** Tags read off untagged stickers, by `"<packId>/<stickerId>"`; see [refreshStickerTags]. */
    @Volatile var stickerTags: Map<String, List<String>> = emptyMap()
        private set

    /** Moves whenever [stickerTags] does, for the trigger index's cache. */
    @Volatile var stickerTagsVersion: Int = 0
        private set

    /** Whether a query can be answered at all now. Cheap: a few file checks. */
    val enabled: Boolean get() = settings.enabled && unlocked && Embeddings.available(context)

    private val picturesEnabled: Boolean get() = enabled && settings.pictures && Embeddings.picturesAvailable(context)

    /** Whether pictures can be searched now, for the gallery's search box. Cheap, like [enabled]. */
    val picturesReady: Boolean get() = picturesEnabled

    /** From the settings collector. Off deletes every index; they are copies of private text. */
    fun configure(next: SemanticSearchSettings, userUnlocked: Boolean) {
        val wasOn = settings.enabled
        settings = next
        unlocked = userUnlocked
        if (wasOn && !next.enabled) {
            syncJobs.values.forEach(Job::cancel)
            queryJobs.values.forEach(Job::cancel)
            syncJobs.clear()
            queuedItems.clear()
            stickerTags = emptyMap()
            stickerTagsVersion++
            scope.launch(worker) {
                indexes.clear()
                indexDir(context).deleteRecursively()
            }
        }
        if (!next.pictures && stickerTags.isNotEmpty()) {
            stickerTags = emptyMap()
            stickerTagsVersion++
        }
    }

    /**
     * Brings [space] up to [items] in the background: what is gone is
     * dropped, what is new or edited is embedded. [items] is a snapshot the
     * caller took; a pass already running for [space] picks up the newest
     * snapshot when it ends. [onDone] runs on the main thread after a pass
     * that changed something.
     */
    fun index(space: MeaningSpace, items: List<MeaningSync.Item>, onDone: () -> Unit = {}) {
        if (!enabled || (space.pictures && !picturesEnabled)) return
        if (space == MeaningSpace.EMOJI) return
        if (syncJobs[space]?.isActive == true) {
            queuedItems[space] = items
            return
        }
        syncJobs[space] = scope.launch {
            val changed = withContext(worker) {
                val index = indexOf(space)
                val embedder = if (space.pictures) {
                    Embedder { inputs -> Embeddings.images(context, inputs) }
                } else {
                    Embedder { inputs -> Embeddings.texts(context, inputs, EmbeddingInput.Role.DOCUMENT) }
                }
                val result = MeaningSync.sync(
                    index,
                    items,
                    embedder,
                    batch = if (space.pictures) PICTURE_BATCH else TEXT_BATCH,
                    budget = if (space.pictures) PICTURE_BUDGET else TEXT_BUDGET,
                    onBatch = { save(space, index) },
                )
                if (result.removed > 0 || result.embedded > 0) save(space, index)
                result.removed > 0 || result.embedded > 0
            }
            if (changed) onDone()
            queuedItems.remove(space)?.let { index(space, it, onDone) }
        }
    }

    /**
     * Asks [space] what [text] means, after the typing pauses, and hands the
     * keys found to [onHits] on the main thread (an empty list when the
     * feature is off, the query too short, or nothing stands out). [accept]
     * narrows the candidates. A newer query to the same space cancels this one.
     */
    fun query(
        space: MeaningSpace,
        text: String,
        tuning: MeaningRank.Tuning,
        accept: (String) -> Boolean = { true },
        onHits: (List<String>) -> Unit,
    ) {
        queryJobs[space]?.cancel()
        val query = text.trim()
        if (!enabled || query.length < MIN_QUERY_LENGTH || (space.pictures && !picturesEnabled)) {
            onHits(emptyList())
            return
        }
        queryJobs[space] = scope.launch {
            delay(DEBOUNCE_MS)
            val vector = vectorOf(query) ?: return@launch onHits(emptyList())
            val keys = withContext(worker) {
                if (space == MeaningSpace.EMOJI) loadEmoji()
                indexOf(space).search(vector, tuning, accept).map { it.key }
            }
            onHits(keys)
        }
    }

    /**
     * [query] for a caller that is already on a background thread and has to
     * answer there (the sticker grid's provider dispatch): blocks for at most
     * [timeoutMs], and answers nothing when the model is still loading.
     */
    fun blockingQuery(space: MeaningSpace, text: String, tuning: MeaningRank.Tuning, timeoutMs: Long, accept: (String) -> Boolean): List<String> {
        val query = text.trim()
        if (!enabled || query.length < MIN_QUERY_LENGTH || (space.pictures && !picturesEnabled)) return emptyList()
        return runBlocking {
            withTimeoutOrNull(timeoutMs) {
                val vector = vectorOf(query) ?: return@withTimeoutOrNull emptyList()
                withContext(worker) { indexOf(space).search(vector, tuning, accept).map { it.key } }
            }
        } ?: emptyList()
    }

    /**
     * The items of each of [targets] closest in meaning to the item [key] of
     * [space] (#1293: a note's related notes and pinned clips), from the
     * vectors already in the indexes, so no model runs. [key] itself is left
     * out, and [accept] narrows the rest. [onHits] runs on the main thread,
     * with nothing when the feature is off or [key] is not indexed yet.
     */
    fun neighbours(
        space: MeaningSpace,
        key: String,
        targets: List<MeaningSpace>,
        accept: (MeaningSpace, String) -> Boolean,
        onHits: (Map<MeaningSpace, List<String>>) -> Unit,
    ) {
        if (!enabled) {
            onHits(emptyMap())
            return
        }
        scope.launch {
            val found = withContext(worker) {
                val vector = indexOf(space).vectorOf(key) ?: return@withContext emptyMap()
                targets.associateWith { target ->
                    indexOf(target)
                        .search(vector, MeaningRank.TEXT) { k -> !(target == space && k == key) && accept(target, k) }
                        .map { it.key }
                }
            }
            onHits(found)
        }
    }

    /**
     * Gives every untagged sticker in [untagged] (keys `"<packId>/<stickerId>"`)
     * the emoji whose names sit closest to its picture, and those names'
     * words, for the trigger index to offer it by. Needs the sticker and emoji
     * indexes, so it runs after a sticker pass. [emojiName] is the catalogue's
     * name for an emoji, or null.
     */
    fun refreshStickerTags(untagged: Set<String>, emojiName: (String) -> String?, onDone: () -> Unit) {
        if (!picturesEnabled || !settings.tagStickers) return
        scope.launch {
            val tags: Map<String, List<String>> = withContext(worker) {
                loadEmoji()
                val stickers = indexOf(MeaningSpace.STICKERS)
                val emoji = indexOf(MeaningSpace.EMOJI)
                if (emoji.size == 0) return@withContext emptyMap()
                val out = HashMap<String, List<String>>()
                for (key in untagged) {
                    val vector = stickers.vectorOf(key) ?: continue
                    val picked = emoji.search(vector, MeaningRank.TAG).map { it.key }
                    if (picked.isEmpty()) continue
                    val words = picked.flatMap { e -> emojiName(e)?.split(' ').orEmpty() }
                        .filter { it.length >= MIN_TAG_WORD }
                        .distinct()
                    out[key] = picked + words
                }
                out
            }
            if (tags != stickerTags) {
                stickerTags = tags
                stickerTagsVersion++
                onDone()
            }
        }
    }

    /** Main thread or not; the cache is locked. */
    private suspend fun vectorOf(query: String): FloatArray? {
        synchronized(queryCache) { queryCache[query] }?.let { return it }
        val vector = Embeddings.query(context, query) ?: return null
        synchronized(queryCache) { queryCache[query] = vector }
        return vector
    }

    /** On [worker]. */
    private fun indexOf(space: MeaningSpace): VectorIndex =
        indexes.getOrPut(space) { VectorIndex.read(indexFile(context, space)) }

    /** On [worker]: the emoji vectors come downloaded, not indexed here. */
    private fun loadEmoji() {
        if ((indexes[MeaningSpace.EMOJI]?.size ?: 0) > 0) return
        indexes[MeaningSpace.EMOJI] = EmojiVectors.read(
            SemanticModels.file(context.noBackupFilesDir, SemanticModels.Part.EMOJI_VECTORS),
        )
    }

    private fun save(space: MeaningSpace, index: VectorIndex) {
        runCatching { index.write(indexFile(context, space)) }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val MIN_QUERY_LENGTH = 2
        private const val QUERY_CACHE = 8
        private const val TEXT_BATCH = 16
        private const val PICTURE_BATCH = 4

        /** Texts embedded per pass: a few minutes of a phone's background time at most. */
        private const val TEXT_BUDGET = 400

        /** Pictures per pass: each costs a vision pass as well as a text one. */
        private const val PICTURE_BUDGET = 32

        /** Words of an emoji's name shorter than this are not tags ("of", "a"). */
        private const val MIN_TAG_WORD = 3

        /**
         * Where the indexes live: derived from the user's own things, so not
         * backed up, and deleted with the feature.
         */
        fun indexDir(context: Context): File = File(context.noBackupFilesDir, "semantic/index")

        /** [space]'s index, read by [MeaningLookup] as well. */
        fun indexFile(context: Context, space: MeaningSpace): File = File(indexDir(context), space.fileName + ".vec")

        /** A stamp for a text in [space]; the model version is folded in. */
        fun stampOf(text: String): Long = MeaningSync.stampOf(text, SemanticModels.VERSION)
    }
}

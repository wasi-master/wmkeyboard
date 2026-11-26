package com.wasimaster.wmkeyboard.ime.semantic

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipSensitivity
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.semantic.MeaningRank
import com.wasimaster.wmkeyboard.core.semantic.VectorIndex
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.tools.AiMeaningFormat
import com.wasimaster.wmkeyboard.core.tools.AiMeaningHit
import com.wasimaster.wmkeyboard.core.tools.AiMeaningSource

/**
 * Search by meaning for the AI tools: one query against the clipboard, notes
 * or snippets index [MeaningSearch] keeps (`search_clipboard`, and the meaning
 * halves of `search_notes` and `search_snippets`), each item checked against
 * its store as it is now.
 *
 * The indexes are only as fresh as the last time their panel opened, so
 * the stores are the word on what may be read: a clip that has since been
 * marked a password, a note locked or put in the trash since, or anything
 * deleted, is dropped even though its vector is still there. A clip that
 * reads as a password or a one-time code is dropped too, marked or not:
 * with the clipboard's sensitive handling on Keep nothing is marked, and a
 * model has no business reading those either way.
 *
 * Pure apart from what it is handed, so a test can give it a fake embedder.
 */
internal class MeaningLookup(
    /** The query as a unit vector, or null when the model could not answer. */
    private val embedQuery: suspend (String) -> FloatArray?,
    private val indexOf: (MeaningSpace) -> VectorIndex,
) {

    /** The stores' current contents. */
    class Sources(
        val clips: List<ClipItem> = emptyList(),
        val notes: List<Note> = emptyList(),
        val snippets: List<Snippet> = emptyList(),
    )

    /**
     * What [query] finds, best first, at most [AiMeaningFormat.MAX_HITS]; an
     * empty list for a query too short to mean anything, null when the model
     * did not answer.
     */
    suspend fun search(query: String, sources: Sources): List<AiMeaningHit>? {
        val q = query.trim()
        if (q.length < MIN_QUERY_LENGTH) return emptyList()
        val vector = embedQuery(q) ?: return null

        val clips = sources.clips.filter(::readable).associateBy { it.id.toString() }
        val notes = sources.notes.filter(::readable).associateBy { it.id.toString() }
        val snippets = sources.snippets.associateBy { it.id.toString() }

        val hits = ArrayList<AiMeaningHit>()
        for (hit in found(MeaningSpace.CLIPS, vector, clips.keys)) {
            val clip = clips.getValue(hit.key)
            val text = clipText(clip) ?: continue
            hits += AiMeaningHit(AiMeaningSource.CLIPBOARD, null, AiMeaningFormat.excerpt(text), hit.score, hit.key)
        }
        for (hit in found(MeaningSpace.NOTES, vector, notes.keys)) {
            val note = notes.getValue(hit.key)
            val body = com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup.strip(note.body).ifBlank { note.tags.joinToString(", ") }
            hits += AiMeaningHit(AiMeaningSource.NOTE, note.title.ifBlank { null }, AiMeaningFormat.excerpt(body), hit.score, hit.key)
        }
        // A snippet's text is a template; it is read as written, never expanded.
        for (hit in found(MeaningSpace.SNIPPETS, vector, snippets.keys)) {
            val snippet = snippets.getValue(hit.key)
            hits += AiMeaningHit(AiMeaningSource.SNIPPET, snippet.label.ifBlank { null }, AiMeaningFormat.excerpt(snippet.text), hit.score, hit.key)
        }
        // Each space was thresholded on its own, against its own crowd; across
        // them the raw cosine is the fairest order there is.
        return hits.sortedByDescending { it.score }.take(AiMeaningFormat.MAX_HITS)
    }

    private fun found(space: MeaningSpace, vector: FloatArray, live: Set<String>): List<MeaningRank.Hit> {
        if (live.isEmpty()) return emptyList()
        val index = indexOf(space)
        if (index.size == 0) return emptyList()
        return index.search(vector, MeaningRank.TEXT) { it in live }
    }

    companion object {
        private const val MIN_QUERY_LENGTH = 2

        /** A clip a model may read: not a password or a code, by its mark or by its shape. */
        fun readable(clip: ClipItem): Boolean =
            !clip.sensitive && !(clip.kind.isTextual && ClipSensitivity.looksSensitive(clip.text))

        /** A note a model may read: not locked, not in the trash. */
        fun readable(note: Note): Boolean = !note.locked && !note.inTrash

        /**
         * What a clip is found by and read as: its text (and a link's page
         * title), a file's name. Pictures are not texts; they have their own
         * index, which this tool does not read.
         */
        fun clipText(clip: ClipItem): String? = when {
            clip.kind.isTextual -> listOfNotNull(clip.linkPreview?.title?.takeIf { it.isNotBlank() }, clip.text)
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
            clip.kind == ClipKind.FILE -> clip.fileName?.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

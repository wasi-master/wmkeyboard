package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.notes.Note
import java.util.Locale

/**
 * Tags a note might want (#1540), offered as chips and never put on by
 * themselves. Rules only, no model:
 *
 * 1. a tag the user already has whose words the note uses ("Dishwasher" for
 *    a note about the dishwasher);
 * 2. the tags on the notes most like this one, by [NoteRelated]'s word
 *    weighting, so a new note about the landlord picks up the "Flat" tag the
 *    other landlord notes carry;
 * 3. when those leave room, the note's own most telling words as new tags:
 *    words in the title first, then the ones it repeats, then the longest.
 *    Short words, numbers and the names of days and months are left out.
 *
 * Tags the note already has are never offered, and a name is offered once
 * whatever its case.
 */
object NoteTagSuggestions {

    /** The most tags offered at once. */
    const val MAX_SUGGESTIONS = 4

    /** The most new words offered as tags; the user's own tags come first. */
    private const val MAX_NEW_WORDS = 2

    /** A word must be this long to be offered as a new tag. */
    private const val NEW_WORD_MIN_LENGTH = 5

    /** How many of the most alike notes lend their tags. */
    private const val RELATED_NOTES = 5

    /**
     * Words that say when rather than what: a note about Friday's car service
     * is about the car service.
     */
    private val CALENDAR_WORDS: Set<String> = """
        january february march april june july august september october november december
        monday tuesday wednesday thursday friday saturday sunday today tomorrow tonight yesterday
        morning afternoon evening night week weekend month year
        """.trim().split(Regex("""\s+""")).toSet()

    /**
     * Up to [limit] tags for [note]: [tags] are the user's tags by name,
     * [notes] the other notes whose tags may be lent. Nothing for a locked
     * or trashed note.
     */
    fun suggest(note: Note, notes: List<Note>, tags: List<String>, limit: Int = MAX_SUGGESTIONS): List<String> {
        if (!NoteAssistRules.readable(note) || limit <= 0) return emptyList()
        val have = note.tags.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val out = LinkedHashMap<String, String>()
        fun offer(name: String) {
            val shown = name.trim()
            val key = shown.lowercase(Locale.ROOT)
            if (shown.isNotEmpty() && key !in have && key !in out) out[key] = shown
        }
        val terms = NoteRelated.terms(note.title + "\n" + note.body)
        if (terms.isEmpty()) return emptyList()

        // 1. The user's tags that the note names.
        for (tag in tags) {
            val words = NoteRelated.terms(tag).keys
            if (words.isNotEmpty() && words.all { it in terms }) offer(tag)
        }

        // 2. The tags of the notes most like this one, the more alike the earlier.
        val pool = notes.filter { it.id != note.id && it.tags.isNotEmpty() && NoteAssistRules.readable(it) }
        if (pool.isNotEmpty()) {
            val byId = pool.associateBy { it.id }
            val target = RelatedSource(RelatedKind.NOTE, note.id, note.title, note.body)
            val sources = pool.map { RelatedSource(RelatedKind.NOTE, it.id, it.title, it.body) }
            val weight = LinkedHashMap<String, Double>()
            val names = HashMap<String, String>()
            for (hit in NoteRelated.related(target, sources, limit = RELATED_NOTES, minScore = NoteRelated.GROUP_SCORE)) {
                for (tag in byId[hit.id]?.tags.orEmpty()) {
                    val key = tag.lowercase(Locale.ROOT)
                    weight[key] = (weight[key] ?: 0.0) + hit.score
                    names.putIfAbsent(key, tag)
                }
            }
            weight.entries.sortedByDescending { it.value }.forEach { offer(names.getValue(it.key)) }
        }

        // 3. The note's own telling words, as tags it could start.
        if (out.size < limit) {
            val known = tags.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
            val titled = NoteRelated.terms(note.title).keys
            terms.entries
                .filter { (word, _) ->
                    word.length >= NEW_WORD_MIN_LENGTH && word !in known && word !in CALENDAR_WORDS && word.none { it.isDigit() }
                }
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.key in titled }
                        .thenByDescending { it.value }
                        .thenByDescending { it.key.length }
                        .thenBy { it.key },
                )
                .take(MAX_NEW_WORDS)
                .forEach { offer(it.key.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }) }
        }
        return out.values.take(limit)
    }
}

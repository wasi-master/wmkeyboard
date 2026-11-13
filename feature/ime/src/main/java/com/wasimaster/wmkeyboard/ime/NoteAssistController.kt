package com.wasimaster.wmkeyboard.ime

import android.content.Context
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistCache
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistPrompts
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistRules
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlight
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlights
import com.wasimaster.wmkeyboard.core.notes.assist.NoteRelated
import com.wasimaster.wmkeyboard.core.notes.assist.NoteReminder
import com.wasimaster.wmkeyboard.core.notes.assist.NoteReminders
import com.wasimaster.wmkeyboard.core.notes.assist.RelatedHit
import com.wasimaster.wmkeyboard.core.notes.assist.RelatedKind
import com.wasimaster.wmkeyboard.core.notes.assist.RelatedSource
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.NotesAssistSettings
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notes assistant's view of the note open in the panel (#1293), as the
 * service publishes it on [NotesPanelUi.assist]. Everything here is about
 * [noteId]; a different note open means this is stale and is not drawn.
 */
data class NoteAssistUi(
    val noteId: Long,
    /** A heading the model suggests for an untitled note, or null. */
    val heading: String? = null,
    val headingBusy: Boolean = false,
    /** The model's summary for this version of the note, or null. */
    val summary: String? = null,
    val summaryBusy: Boolean = false,
    /** The note is long enough for a summary, by the setting. */
    val wantsSummary: Boolean = false,
    /** An on-device model is installed, so the model's parts can run. */
    val modelReady: Boolean = false,
    val highlights: List<NoteHighlight> = emptyList(),
    val reminders: List<NoteReminder> = emptyList(),
    val related: List<RelatedHit> = emptyList(),
    /** Cards the note spells out, for the deck chip to count; the model may find more. */
    val deckCards: Int = 0,
    /** A model run failed: its message, shown once under the summary. */
    val error: String? = null,
)

/**
 * The keyboard's half of the notes assistant (#1293). Works out the rules
 * half (key details, reminders, related items) off the main thread, and runs
 * the on-device model for a heading and a summary when a note wants one and
 * the cache has none for its current text.
 *
 * Nothing runs unless the master switch is on, and nothing that reads a
 * note runs for a locked or trashed one. The model is the local one only,
 * [LocalLlmEngine]: whatever cloud provider the AI tool is set to, the notes
 * never leave the phone through here.
 */
internal class NoteAssistController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    interface Host {
        fun settings(): KeyboardSettings

        /** The on-device model to run, or null when none is installed. */
        fun modelFile(): File?

        fun note(id: Long): Note?

        /** The live notes the assistant may read: no locked or trashed ones. */
        fun readableNotes(): List<Note>

        /** Pinned text clips that are not masked as secrets. */
        fun pinnedClips(): List<ClipItem>

        /** Search by meaning's neighbours of a note, by kind, or nothing when it is off. */
        fun meaningNeighbours(noteId: Long, onHits: (notes: List<Long>, clips: List<Long>) -> Unit)

        /** The panel's assist state changed; null takes it down. */
        fun publish(ui: NoteAssistUi?)
    }

    val cache: NoteAssistCache by lazy { NoteAssistCache(NoteAssistCache.fileIn(context.noBackupFilesDir)) }

    /** What [sync] last worked out for, so a publish that changed nothing about the note does nothing. */
    private var syncedKey: String? = null
    private var current: NoteAssistUi? = null
    private var rulesJob: Job? = null
    private var modelJob: Job? = null

    /** The note whose model run is under way, so a second ask for it does not queue another. */
    private var modelNoteId: Long? = null

    /**
     * Brings the assist state up to date with [openId], the note open to
     * read (null when none, or while the editor is open). Cheap when nothing
     * changed: the note, its text and the settings are its key.
     */
    fun sync(openId: Long?, editing: Boolean) {
        val settings = host.settings().notes.assist
        val note = openId?.takeIf { !editing }?.let(host::note)
        if (!settings.enabled || note == null || !NoteAssistRules.readable(note)) {
            if (current != null || syncedKey != null) {
                syncedKey = null
                current = null
                rulesJob?.cancel()
                host.publish(null)
            }
            return
        }
        val key = "${note.id}:${note.updatedAt}:${note.title.hashCode()}:${settings.hashCode()}"
        if (key == syncedKey) return
        syncedKey = key
        refresh(note, settings)
    }

    /** Forces a recompute of the open note, after a clip was pinned or the model arrived. */
    fun invalidate() {
        syncedKey = null
    }

    /** The switch went off: every answer kept for a note is forgotten. */
    fun disable() {
        rulesJob?.cancel()
        modelJob?.cancel()
        modelNoteId = null
        current = null
        syncedKey = null
        scope.launch(Dispatchers.IO) { cache.clear() }
    }

    private fun refresh(note: Note, settings: NotesAssistSettings) {
        cache.reloadIfChanged()
        val modelReady = host.modelFile() != null
        val base = NoteAssistUi(
            noteId = note.id,
            heading = if (settings.headings && note.title.isBlank()) cache.heading(note) else null,
            summary = if (settings.summaries) cache.summary(note) else null,
            wantsSummary = settings.summaries && NoteAssistRules.wantsSummary(note, settings.summaryWords),
            modelReady = modelReady,
            headingBusy = modelNoteId == note.id && current?.headingBusy == true,
            summaryBusy = modelNoteId == note.id && current?.summaryBusy == true,
            // The rules' half from last time stays up while it is worked out again.
            highlights = current?.takeIf { it.noteId == note.id }?.highlights.orEmpty(),
            reminders = current?.takeIf { it.noteId == note.id }?.reminders.orEmpty(),
            related = current?.takeIf { it.noteId == note.id }?.related.orEmpty(),
            deckCards = current?.takeIf { it.noteId == note.id }?.deckCards ?: 0,
        )
        emit(base)
        rulesJob?.cancel()
        rulesJob = scope.launch {
            val worked = withContext(Dispatchers.Default) { rules(note, settings) }
            update(note.id) { it.copy(highlights = worked.highlights, reminders = worked.reminders, related = worked.related, deckCards = worked.deckCards) }
            if (settings.related) mergeMeaning(note)
        }
        if (modelReady) runModel(note, settings, force = false)
    }

    private class Worked(
        val highlights: List<NoteHighlight>,
        val reminders: List<NoteReminder>,
        val related: List<RelatedHit>,
        val deckCards: Int,
    )

    private fun rules(note: Note, settings: NotesAssistSettings): Worked {
        val zone = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val anchor = note.updatedAt.takeIf { it > 0 } ?: now
        val found = if (settings.highlights || settings.reminders) {
            NoteHighlights.find(note.body, anchor, zone, Locale.getDefault())
        } else {
            emptyList()
        }
        val related = if (settings.related) {
            val target = RelatedSource(RelatedKind.NOTE, note.id, note.displayTitle(), note.body)
            NoteRelated.related(target, relatedPool())
        } else {
            emptyList()
        }
        return Worked(
            highlights = if (settings.highlights) found else emptyList(),
            reminders = if (settings.reminders) NoteReminders.upcoming(found, now, zone) else emptyList(),
            related = related,
            deckCards = if (settings.decks) com.wasimaster.wmkeyboard.core.notes.assist.NoteStudyCards.fromText(note.body).size else 0,
        )
    }

    /** The notes and pinned clips a note can be related to. */
    private fun relatedPool(): List<RelatedSource> =
        host.readableNotes().map { RelatedSource(RelatedKind.NOTE, it.id, it.displayTitle(), it.body) } +
            host.pinnedClips().map { RelatedSource(RelatedKind.CLIP, it.id, it.text.lineSequence().firstOrNull().orEmpty().take(TITLE_CHARS), it.text) }

    /** Adds what search by meaning finds to the words' related list, when it is on and the note is indexed. */
    private fun mergeMeaning(note: Note) {
        host.meaningNeighbours(note.id) { noteIds, clipIds ->
            if (noteIds.isEmpty() && clipIds.isEmpty()) return@meaningNeighbours
            val notes = host.readableNotes().associateBy { it.id }
            val clips = host.pinnedClips().associateBy { it.id }
            update(note.id) { ui ->
                val have = ui.related.mapTo(HashSet()) { it.kind to it.id }
                val extra = noteIds.mapNotNull { id -> notes[id]?.takeIf { (RelatedKind.NOTE to id) !in have } }
                    .map { RelatedHit(RelatedKind.NOTE, it.id, it.displayTitle(), MEANING_SCORE, emptyList()) } +
                    clipIds.mapNotNull { id -> clips[id]?.takeIf { (RelatedKind.CLIP to id) !in have } }
                        .map { RelatedHit(RelatedKind.CLIP, it.id, it.text.lineSequence().firstOrNull().orEmpty().take(TITLE_CHARS), MEANING_SCORE, emptyList()) }
                ui.copy(related = (ui.related + extra).take(NoteRelated.MAX_RELATED))
            }
        }
    }

    /**
     * Asks the model for what [note] still needs: a heading when it has no
     * title, a summary when it is long, each only when the cache has none
     * for this text. [force] re-asks for the summary even when one is kept
     * (the Summarise again chip).
     */
    fun runModel(note: Note, settings: NotesAssistSettings = host.settings().notes.assist, force: Boolean) {
        if (!settings.enabled || !NoteAssistRules.readable(note)) return
        if (modelNoteId == note.id && modelJob?.isActive == true) return
        val wantHeading = settings.headings && NoteAssistRules.wantsHeading(note) &&
            cache.heading(note) == null && !cache.headingDismissed(note)
        val wantSummary = settings.summaries && NoteAssistRules.wantsSummary(note, settings.summaryWords) &&
            (force || cache.summary(note) == null)
        if (!wantHeading && !wantSummary) return
        val model = host.modelFile() ?: return
        val ai = host.settings().ai
        modelNoteId = note.id
        update(note.id) { it.copy(headingBusy = wantHeading, summaryBusy = wantSummary, error = null) }
        modelJob = scope.launch {
            try {
                val input = NoteAssistPrompts.noteInput(note.title, note.body)
                if (wantHeading) {
                    val heading = withContext(Dispatchers.IO) {
                        LocalLlmEngine.generate(context, model, ai.localBackend, ai.localContextTokens, NoteAssistPrompts.headingSystem(), input)
                    }.let(NoteAssistPrompts::cleanHeading)
                    if (heading != null) cache.putHeading(note, heading)
                    update(note.id) { it.copy(heading = heading, headingBusy = false) }
                }
                if (wantSummary) {
                    val summary = withContext(Dispatchers.IO) {
                        LocalLlmEngine.generate(context, model, ai.localBackend, ai.localContextTokens, NoteAssistPrompts.summarySystem(), input)
                    }.let(NoteAssistPrompts::cleanSummary)
                    if (summary != null) cache.putSummary(note, summary)
                    update(note.id) { it.copy(summary = summary ?: it.summary, summaryBusy = false) }
                }
                withContext(Dispatchers.IO) { cache.save() }
            } catch (e: java.io.IOException) {
                update(note.id) { it.copy(headingBusy = false, summaryBusy = false, error = e.message) }
            } catch (e: OutOfMemoryError) {
                update(note.id) { it.copy(headingBusy = false, summaryBusy = false, error = e.message) }
            } finally {
                if (modelNoteId == note.id) modelNoteId = null
                update(note.id) { it.copy(headingBusy = false, summaryBusy = false) }
            }
        }
    }

    /** The suggested heading waved away; not offered again for this text. */
    fun dismissHeading(note: Note) {
        cache.dismissHeading(note)
        update(note.id) { it.copy(heading = null) }
        scope.launch(Dispatchers.IO) { cache.save() }
    }

    /**
     * Cards for a study deck from [note]: what the note spells out, or, when
     * that is fewer than [MIN_RULE_CARDS] and a model is installed, what the
     * model writes. Blocking; call off the main thread.
     */
    fun deckCards(note: Note): List<com.wasimaster.wmkeyboard.core.vocab.FlashcardImport.Card> {
        val byRules = com.wasimaster.wmkeyboard.core.notes.assist.NoteStudyCards.fromText(note.body)
        if (byRules.size >= MIN_RULE_CARDS) return byRules
        val model = host.modelFile() ?: return byRules
        val ai = host.settings().ai
        val answer = LocalLlmEngine.generate(
            context, model, ai.localBackend, ai.localContextTokens,
            NoteAssistPrompts.deckSystem(), NoteAssistPrompts.noteInput(note.title, note.body),
        )
        val byModel = com.wasimaster.wmkeyboard.core.notes.assist.NoteStudyCards.fromModel(
            com.wasimaster.wmkeyboard.core.tools.AiThinking.stripped(answer),
        )
        return if (byModel.size > byRules.size) byModel else byRules
    }

    private fun emit(ui: NoteAssistUi) {
        current = ui
        host.publish(ui)
    }

    private fun update(noteId: Long, change: (NoteAssistUi) -> NoteAssistUi) {
        val ui = current?.takeIf { it.noteId == noteId } ?: return
        emit(change(ui))
    }

    companion object {
        /** A pinned clip's name in the related row: its first line, this long. */
        private const val TITLE_CHARS = 60

        /** What a meaning-only neighbour is said to score; it ranks after the words' own. */
        private const val MEANING_SCORE = 0.2

        /** Below this many cards in the note itself, the model is asked to write them. */
        const val MIN_RULE_CARDS = 3
    }
}

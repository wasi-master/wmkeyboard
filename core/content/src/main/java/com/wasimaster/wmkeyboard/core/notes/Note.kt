package com.wasimaster.wmkeyboard.core.notes

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.clipboard.capClipText
import com.wasimaster.wmkeyboard.core.clipboard.clipPreviewText
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One note in the Notes tool (#560).
 *
 * [body] is the note: plain text that the reading view draws as Markdown, with
 * `- [ ]` lines as boxes to tick. [title] is stored only when the user typed
 * one; most notes written from a keyboard have none, and [displayTitle] reads
 * the first line instead. Storing the derived title would go stale on the
 * first edit of the body, so it is never stored.
 *
 * The flags are deliberately independent: a pinned note can be archived, and
 * a trashed note keeps its pin so [NoteStore.restore] puts it back exactly as
 * it was. [deletedAt] is the trash: set when a note is deleted, cleared when it
 * is restored, and the reason there is no separate undo list anywhere.
 *
 * [createdAt] and [updatedAt] are always written, defaults or not, because
 * the sync merge keys items by `id@createdAt`. The optional fields are written
 * only when set, so a plain note stays a few short lines of JSON.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class Note(
    val id: Long,
    val body: String,
    /** The title the user typed, or "" for none; see [displayTitle]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val title: String = "",
    /** Names of [NoteTag]s, in their canonical casing, each at most once. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tags: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pinned: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val archived: Boolean = false,
    /**
     * Masked on the keyboard, behind the fingerprint in the app, and left out
     * of a backup unless secrets go along. A lock on the screen, not on the
     * bytes: the note is stored like any other.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val locked: Boolean = false,
    val createdAt: Long = 0,
    /** When the title, body or tags last changed. Flag flips leave it alone. */
    val updatedAt: Long = 0,
    /** In the trash since this time, or null when it is not. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deletedAt: Long? = null,
) {

    val inTrash: Boolean get() = deletedAt != null

    /** Whether [name] is one of this note's tags, ignoring case. */
    fun hasTag(name: String): Boolean = tags.any { it.equals(name, ignoreCase = true) }

    /**
     * What a list calls this note: [title] when there is one, else the first
     * line of the body with its Markdown taken off (`# `, a bullet, a task box,
     * `**bold**`), cut to [DISPLAY_TITLE_CHARS]. "" for an empty note, which the
     * UI names "Untitled" in the user's language.
     *
     * A locked note never derives its name from its body: the first line of a
     * note the user asked to hide is the one line a list would otherwise print.
     */
    fun displayTitle(): String {
        if (title.isNotBlank()) return title
        if (locked) return ""
        // Only the line's start: the title is cut to DISPLAY_TITLE_CHARS
        // anyway, and a note holding a pasted file's text can open on one line
        // hundreds of kilobytes long, which every card and every sort by title
        // ran a regex and the Markdown reader over, on the main thread (#1466).
        val line = firstContentLine(TITLE_SCAN_CHARS) ?: return ""
        return cleanTitleLine(line)
    }

    /**
     * The body for a list preview: when the title was read off the first line,
     * that line is left out so the row does not say it twice. At most [limit]
     * characters of it, and only those are copied: a list asks this of every
     * row as it draws, and a note can hold a pasted file's hundreds of
     * kilobytes (#1466).
     */
    fun previewBody(limit: Int = Int.MAX_VALUE): String {
        val n = body.length
        var start = if (title.isNotBlank()) 0 else lineEnd(firstContentLineStart() ?: return "")
        while (start < n && body[start].isWhitespace()) start++
        var end = n
        while (end > start && body[end - 1].isWhitespace()) end--
        if (end - start > limit) {
            end = start + limit.coerceAtLeast(0)
            if (end > start && Character.isHighSurrogate(body[end - 1])) end--
        }
        return body.substring(start, end)
    }

    /**
     * What a list row shows under the title in [lines] lines: [previewBody],
     * cut after the line break past the last line that shows, and never more
     * than [PREVIEW_CHARS_PER_LINE] a line. Text layout measures every
     * character it is handed, however few lines are drawn, so a row that
     * shows three lines of a long paragraph is handed a few hundred
     * characters of it, not the whole (#1466).
     */
    fun preview(lines: Int): String {
        val shown = lines.coerceAtLeast(1)
        val cap = shown * PREVIEW_CHARS_PER_LINE
        return clipPreviewText(previewBody(cap), shown, cap)
    }

    /** Words in the body, as whitespace separates them. */
    fun wordCount(): Int = body.split(WHITESPACE).count { it.isNotEmpty() }

    fun charCount(): Int = body.length

    /** The body's task boxes, ticked and in total. */
    fun checklist(): NoteChecklist.Progress = NoteChecklist.progress(body)

    /**
     * The first line of the body that is not blank, cut to [limit] characters,
     * or null. Lines end where [lineSequence] ends them: `\n`, `\r\n` or `\r`.
     * Only that line is copied, not every line before it or the rest of it.
     */
    private fun firstContentLine(limit: Int = Int.MAX_VALUE): String? {
        val start = firstContentLineStart() ?: return null
        val end = lineEnd(start)
        return body.substring(start, if (end - start > limit) start + limit else end)
    }

    /** Where the first line that is not blank starts, or null for a blank body. */
    private fun firstContentLineStart(): Int? {
        val n = body.length
        var start = 0
        while (start <= n) {
            var end = start
            var content = false
            while (end < n && body[end] != '\n' && body[end] != '\r') {
                if (!content && !body[end].isWhitespace()) content = true
                end++
            }
            if (content) return start
            if (end >= n) return null
            start = if (body[end] == '\r' && end + 1 < n && body[end + 1] == '\n') end + 2 else end + 1
        }
        return null
    }

    /** Where the line starting at [start] ends: its `\n` or `\r`, or the body's end. */
    private fun lineEnd(start: Int): Int {
        var end = start
        while (end < body.length && body[end] != '\n' && body[end] != '\r') end++
        return end
    }

    companion object {
        /** Longest derived title. Stored titles are capped by [NoteStore.MAX_TITLE_LENGTH]. */
        const val DISPLAY_TITLE_CHARS = 80

        /** How much of the first line [displayTitle] reads: room for markers and Markdown around 80 characters. */
        private const val TITLE_SCAN_CHARS = 1_000

        /**
         * The most characters a preview line is handed: more than the widest
         * row fits of the narrowest letters, so a cut preview still overflows
         * its lines and ends in an ellipsis.
         */
        const val PREVIEW_CHARS_PER_LINE = 200

        private val WHITESPACE = Regex("""\s+""")

        /** The block markers a first line may open with, which a title does not want. */
        private val LEADING_MARKERS = Regex("""^\s*(?:#{1,6}\s+|[-*+]\s+(?:\[[ xX]\]\s*)?|\d{1,3}[.)]\s+|>\s*)+""")

        /** [line] as a title: markers off, inline Markdown read, cut to length. */
        fun cleanTitleLine(line: String): String {
            val stripped = line.replace(LEADING_MARKERS, "").trim()
            val plain = AiMarkdownBlocks.inline(stripped).text.trim()
            return capClipText(plain, DISPLAY_TITLE_CHARS)
        }
    }
}

/**
 * A tag: a name and a colour. Kept as a list of its own in the store, not
 * derived from the notes, so a tag can exist with a colour and no notes yet
 * (just made from a suggested chip), be renamed, and merge on a collision.
 *
 * [colour] is an index into [NoteTagPalette], never an ARGB value, so the dot
 * reads on every keyboard theme. 0 is the theme's own accent.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class NoteTag(
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val colour: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val createdAt: Long = 0,
)

/** How a list of notes is ordered. Pinned notes come first whatever this says. */
@Serializable
enum class NoteSort { UPDATED, CREATED, TITLE }

/** Which shelf a list reads from: the live notes, the archive, or the trash. */
enum class NoteShelf { ACTIVE, ARCHIVED, TRASH }

/**
 * What a list asks the store for. [tag] narrows to notes carrying it (ignoring
 * case); [includeLocked] false hides locked notes entirely, which is what the
 * keyboard does while the device is locked.
 */
data class NoteFilter(
    val shelf: NoteShelf = NoteShelf.ACTIVE,
    val tag: String? = null,
    val includeLocked: Boolean = true,
)

/**
 * The colours a tag may wear, as ARGB. Index 0 stands for the theme's accent
 * and holds no colour of its own; the rest are the Material 400 tones the
 * tool colours already use, which read on light and dark themes alike.
 */
object NoteTagPalette {
    val ARGB: IntArray = intArrayOf(
        0, // the theme accent
        0xFFEF5350.toInt(), // red
        0xFFFFA726.toInt(), // orange
        0xFFFDD835.toInt(), // yellow
        0xFF66BB6A.toInt(), // green
        0xFF26A69A.toInt(), // teal
        0xFF42A5F5.toInt(), // blue
        0xFFAB47BC.toInt(), // purple
        0xFF90A4AE.toInt(), // grey
    )

    val size: Int get() = ARGB.size

    /** [index] held inside the palette; an index a newer build wrote falls back to the accent. */
    fun clamp(index: Int): Int = if (index in ARGB.indices) index else 0
}

/**
 * The chips the tag picker offers while the user has few tags of their own.
 * Each becomes a real [NoteTag] the first time it is put on a note; until
 * then it is a word on a chip and nothing in the store. The names are
 * resources so they read in the user's language, and the colour is the
 * palette index the tag is born with.
 */
enum class SuggestedNoteTag(@StringRes val nameRes: Int, val colour: Int) {
    IMPORTANT(R.string.core_content_note_tag_important, 1),
    WORK(R.string.core_content_note_tag_work, 6),
    SCHOOL(R.string.core_content_note_tag_school, 4),
    STUDY(R.string.core_content_note_tag_study, 7),
    PERSONAL(R.string.core_content_note_tag_personal, 2),
    IDEAS(R.string.core_content_note_tag_ideas, 3),
}

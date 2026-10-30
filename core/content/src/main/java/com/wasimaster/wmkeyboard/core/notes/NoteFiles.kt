package com.wasimaster.wmkeyboard.core.notes

import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Notes as files: one note to Markdown or plain text and back, and a whole
 * set as a zip of such files.
 *
 * The shape is deliberately not front matter. A `.md` opened in any editor
 * should read as the note, not as a configuration block followed by the note:
 *
 * ```
 * # Title                 (only when the user typed a title the body does not already open with)
 *
 * the body, verbatim
 *
 * Tags: work, school stuff   (only when the note has tags; always the last line)
 * ```
 *
 * Tags are comma-separated rather than `#work #school`, because a tag may hold
 * spaces and a `#` at the start of a line turns into a heading the moment the
 * text is moved around. The store strips commas from tag names, so the line
 * splits without ambiguity. Reading a file leaves a leading heading in the
 * body: [Note.displayTitle] derives the same words from it, so the round trip
 * is lossless for the text and the list looks the same.
 *
 * An Obsidian vault is the exception ([toObsidian]): tags only work there in
 * front matter, so that is what it gets, and [fromText] reads the same block back.
 *
 * Policy-free on purpose: the caller decides which notes travel (a locked
 * note does not leave the app without the user passing its lock first).
 */
object NoteFiles {

    enum class Format(val extension: String, val mimeType: String) {
        MARKDOWN("md", "text/markdown"),
        TEXT("txt", "text/plain"),
    }

    const val ZIP_MIME = "application/zip"

    /** Most files one zip import reads, and most bytes one entry may hold: both zip-bomb guards. */
    const val MAX_IMPORT_FILES = 1_000
    const val MAX_IMPORT_BYTES = 4L * NoteStore.MAX_BODY_CHARS

    /** File names this reads as notes, by extension, ignoring case. */
    val IMPORT_EXTENSIONS: Set<String> = setOf("md", "markdown", "txt", "text")

    private val TAGS_LINE = Regex("""^tags:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("""^#{1,6}\s+(.*?)(\s+#+\s*)?$""")
    private val SLUG_KEEP = Regex("""[^\p{L}\p{N}]+""")
    private val OBSIDIAN_TAG_DROP = Regex("""[^\p{L}\p{N}_/-]+""")
    private val FRONT_KEY = Regex("""^(\w+):\s*(.*)$""")
    private val FRONT_ITEM = Regex("""^\s*-\s+(.+)$""")
    private const val FRONT_DATE = "yyyy-MM-dd'T'HH:mm:ss"

    /** [note] as text, see the class note for the shape. */
    fun toText(note: Note): String {
        val out = StringBuilder()
        val body = note.body.trim()
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }
        val headingAlreadyThere = firstLine != null &&
            HEADING.matchEntire(firstLine)?.groupValues?.get(1)?.trim() == note.title.trim()
        if (note.title.isNotBlank() && !headingAlreadyThere) {
            out.append("# ").append(note.title.trim()).append("\n\n")
        }
        out.append(body)
        if (note.tags.isNotEmpty()) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append("Tags: ").append(note.tags.joinToString(", "))
        }
        out.append('\n')
        return out.toString()
    }

    /**
     * [note] for an Obsidian vault: tags and dates in front matter, the body as
     * it is. Obsidian takes letters, digits, `_`, `-` and `/` in a tag, so a space
     * in one becomes a dash; the app's own tag is unchanged.
     */
    fun toObsidian(note: Note): String {
        val out = StringBuilder("---\n")
        val tags = note.tags.map { it.replace(OBSIDIAN_TAG_DROP, "-").trim('-') }.filter { it.isNotEmpty() }
        if (tags.isNotEmpty()) {
            out.append("tags:\n")
            tags.forEach { out.append("  - ").append(it).append('\n') }
        }
        val dates = SimpleDateFormat(FRONT_DATE, Locale.ROOT)
        if (note.createdAt > 0) out.append("created: ").append(dates.format(Date(note.createdAt))).append('\n')
        if (note.updatedAt > 0) out.append("updated: ").append(dates.format(Date(note.updatedAt))).append('\n')
        out.append("---\n\n")
        out.append(toText(note.copy(tags = emptyList())))
        return out.toString()
    }

    /** What [frontMatter] found: the tags and dates, and the text after the block. */
    private class FrontMatter(val tags: List<String>, val created: Long?, val updated: Long?, val rest: String)

    /**
     * The block [toObsidian] writes, or null when [text] does not open with one
     * holding only those keys. Any other key (an alias, a publish date) belongs to
     * the user's own note, so that text stays in the body whole rather than losing
     * the part the app has no field for.
     */
    private fun frontMatter(text: String): FrontMatter? {
        if (!text.startsWith("---\n")) return null
        val end = text.indexOf("\n---", 3)
        if (end < 0 || text.getOrNull(end + 4).let { it != null && it != '\n' }) return null
        val tags = ArrayList<String>()
        var created: Long? = null
        var updated: Long? = null
        var inTags = false
        for (line in text.substring(4, maxOf(4, end)).lineSequence()) {
            if (line.isBlank()) continue
            val item = FRONT_ITEM.matchEntire(line)
            if (item != null) {
                if (!inTags) return null
                tags += item.groupValues[1].trim().trim('"', '\'')
                continue
            }
            val key = FRONT_KEY.matchEntire(line) ?: return null
            val value = key.groupValues[2].trim()
            inTags = false
            when (key.groupValues[1].lowercase(Locale.ROOT)) {
                "tags" -> {
                    inTags = value.isEmpty()
                    tags += value.removeSurrounding("[", "]").split(',')
                        .map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
                }
                "created" -> created = frontDate(value)
                "updated" -> updated = frontDate(value)
                else -> return null
            }
        }
        return FrontMatter(tags, created, updated, text.substring(end + 4).trim())
    }

    private fun frontDate(value: String): Long? =
        runCatching { SimpleDateFormat(FRONT_DATE, Locale.ROOT).parse(value)?.time }.getOrNull()

    /**
     * A note read from [text], not yet stored (id 0; hand it to
     * [NoteStore.add]). A trailing `Tags:` line, or the front matter an Obsidian
     * vault holds, becomes the tags. [modifiedAt] is the file's time when the
     * picker gives one, so an imported note keeps the date it was written rather
     * than the date it was imported.
     */
    fun fromText(fileName: String, text: String, modifiedAt: Long = System.currentTimeMillis()): Note {
        var body = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        var tags: List<String> = emptyList()
        val front = frontMatter(body)
        if (front != null) {
            body = front.rest
            tags = front.tags
        }
        val lastLine = body.lineSequence().lastOrNull { it.isNotBlank() }
        if (lastLine != null && tags.isEmpty()) {
            TAGS_LINE.matchEntire(lastLine.trim())?.let { match ->
                tags = match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                body = body.substring(0, body.lastIndexOf(lastLine)).trimEnd()
            }
        }
        // A file whose body is empty still has a name worth keeping as the title.
        val title = if (body.isBlank()) stem(fileName) else ""
        return Note(
            id = 0,
            body = body,
            title = title,
            tags = tags,
            createdAt = front?.created ?: modifiedAt,
            updatedAt = front?.updated ?: modifiedAt,
        )
    }

    /**
     * A file name for [note]: the title as a slug, the id when the title gives
     * nothing, and a `-2`, `-3` suffix while [taken] already holds the name.
     * The chosen name is added to [taken].
     */
    fun fileName(note: Note, format: Format, taken: MutableSet<String>): String {
        val base = slug(note.displayTitle()).ifEmpty { "note-${note.id}" }
        var candidate = "$base.${format.extension}"
        var n = 2
        while (!taken.add(candidate.lowercase(Locale.ROOT))) {
            candidate = "$base-$n.${format.extension}"
            n++
        }
        return candidate
    }

    /** [notes] as one zip, one file each, named by [fileName]. */
    fun writeZip(notes: List<Note>, format: Format, out: OutputStream) {
        val taken = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            for (note in notes) {
                val entry = ZipEntry(fileName(note, format, taken))
                if (note.updatedAt > 0) entry.time = note.updatedAt
                zip.putNextEntry(entry)
                zip.write(toText(note).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /**
     * The notes in a zip: every `.md`/`.txt` entry at any depth, folders and
     * other files skipped, capped by [MAX_IMPORT_FILES] and [MAX_IMPORT_BYTES].
     */
    fun readZip(input: InputStream): List<Note> {
        val out = ArrayList<Note>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (!isNoteFile(name)) continue
                if (out.size >= MAX_IMPORT_FILES) break
                val bytes = readCapped(zip, MAX_IMPORT_BYTES) ?: continue
                val time = entry.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                out += fromText(name, String(bytes), time)
            }
        }
        return out
    }

    /** Whether [fileName] is one this reads as a note. */
    fun isNoteFile(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMPORT_EXTENSIONS && '.' in fileName

    /** Letters and digits kept, everything else one dash, at most 60 characters. */
    fun slug(text: String): String =
        text.replace(SLUG_KEEP, "-").trim('-').take(60).trim('-')

    private fun stem(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.').trim()

    /** The stream up to [max] bytes, or null when it holds more. */
    private fun readCapped(input: InputStream, max: Long): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > max) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}

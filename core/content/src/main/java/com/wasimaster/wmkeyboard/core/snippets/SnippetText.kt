package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText
import java.util.Locale

/**
 * Every snippet as one plain-text document, for the "Edit all as text" screen
 * and for `.txt` import and export.
 *
 * Each snippet is a block that opens with its trigger between `===` marks.
 * Settings follow as `@name: value` lines, then the text; a line of exactly
 * `---` starts another expansion of the same snippet:
 *
 * ```
 * === omw ===
 * On my way!
 *
 * === addr ===
 * @label: Home address
 * @folder: Personal
 * @flags: confirm
 * 12 Example Road
 * ---
 * PO Box 7
 * ```
 *
 * The format is built to round-trip: [encode] then [parse] gives back every
 * field a snippet has, links and pictures included, so a save with nothing
 * edited changes nothing. A line of text that would read as markup gets a
 * backslash in front ([escapeLine]), as do blank lines at either end of a text,
 * which would otherwise be taken for the gap between blocks.
 *
 * Folders travel by name. Ids never appear: the editor matches blocks to the
 * stored snippets by trigger ([plan]), so the person editing the text never
 * has to carry a number around.
 */
object SnippetText {

    const val MIME_TYPE = "text/plain"

    const val FILE_NAME = "snippets.txt"

    /** Where a block's text splits into the next expansion. */
    const val SEPARATOR = "---"

    /** What a line of comment before the first block starts with. */
    private const val COMMENT = "#"

    private val HEADER = Regex("""^===(.*)===[ \t]*$""")

    private val SETTING = Regex("""^@([A-Za-z-]+)[ \t]*:(.*)$""")

    /** The settings a block may carry, by the name written after `@`. */
    private val KNOWN = setOf(
        "label", "alias", "pattern", "words", "case", "flags", "several", "folder", "tag", "link", "picture",
    )

    /** One snippet read out of the document, not yet tied to anything stored. */
    data class Entry(
        /** Id 0, folder 0, no links: [plan] or [toImport] fills those in. */
        val snippet: Snippet,
        /** The `@folder:` name, or null. */
        val folder: String?,
        /** The `@link:` keys, in order. */
        val links: List<String>,
        /** The 1-based line of the block's `===` header. */
        val line: Int,
    ) {
        val key: String get() = keyOf(snippet)
    }

    /** Something wrong at a 1-based [line]. */
    data class Problem(val line: Int, val message: ContentText)

    data class Parsed(val entries: List<Entry>, val problems: List<Problem>)

    /** True when [text] reads as this format: its first line that says anything is a header. */
    fun looksLikeText(text: String): Boolean {
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith(COMMENT)) continue
            return HEADER.matches(line)
        }
        return false
    }

    /**
     * What a snippet is known by in the document: its trigger, else its
     * pattern, else its label. The header shows the first; the editor matches
     * blocks to stored snippets on this, and `@link:` names a snippet by it.
     */
    fun keyOf(snippet: Snippet): String =
        snippet.trigger?.trim()?.takeIf { it.isNotEmpty() }
            ?: snippet.triggerPattern?.trim()?.takeIf { it.isNotEmpty() }
            ?: snippet.label.trim()

    // ---- writing -----------------------------------------------------------

    /**
     * [snippets] as one document, in their stored order. [header] goes on top
     * as comment lines; [parse] skips them.
     */
    fun encode(snippets: List<Snippet>, folders: List<SnippetFolder>, header: List<String> = emptyList()): String {
        val folderNames = folders.associate { it.id to it.name }
        val keys = snippets.associate { it.id to keyOf(it) }
        val out = StringBuilder()
        for (line in header) out.append(COMMENT).append(' ').append(line).append('\n')
        if (header.isNotEmpty() && snippets.isNotEmpty()) out.append('\n')
        snippets.forEachIndexed { index, snippet ->
            if (index > 0) out.append('\n')
            val trigger = snippet.trigger?.trim().orEmpty()
            out.append("=== ").append(trigger).append(" ===\n")
            if (snippet.label != trigger) setting(out, "label", snippet.label)
            for (alias in snippet.aliases) setting(out, "alias", alias)
            snippet.triggerPattern?.let { setting(out, "pattern", it) }
            if (snippet.triggerWords != 0) setting(out, "words", snippet.triggerWords.toString())
            if (snippet.propagateCase) setting(out, "case", caseName(snippet.uppercaseStyle))
            val flags = buildList {
                if (snippet.confirm) add("confirm")
                if (snippet.immediate) add("immediate")
                if (snippet.insideWords) add("inside-words")
            }
            if (flags.isNotEmpty()) setting(out, "flags", flags.joinToString(", "))
            when (snippet.multiExpand) {
                MultiExpand.CHIPS_ONLY -> setting(out, "several", "chips")
                MultiExpand.INSERT_FIRST -> setting(out, "several", "insert")
                MultiExpand.DEFAULT -> Unit
            }
            folderNames[snippet.folderId]?.let { setting(out, "folder", it) }
            for (tag in snippet.tags) setting(out, "tag", tag)
            for (child in snippet.children) keys[child]?.let { setting(out, "link", it) }
            snippet.image?.let { setting(out, "picture", it) }
            body(out, snippet.text)
            for (alternate in snippet.alternates) {
                out.append(SEPARATOR).append('\n')
                body(out, alternate)
            }
        }
        return out.toString()
    }

    private fun setting(out: StringBuilder, name: String, value: String) {
        // A value is one line: a newline in one would start the text early.
        out.append('@').append(name).append(": ").append(value.replace('\n', ' ')).append('\n')
    }

    private fun body(out: StringBuilder, text: String) {
        if (text.isEmpty()) return
        val lines = text.split('\n')
        val first = lines.indexOfFirst { it.isNotBlank() }
        val last = lines.indexOfLast { it.isNotBlank() }
        lines.forEachIndexed { i, line ->
            val edge = first < 0 || i < first || i > last
            out.append(escapeLine(line, edge)).append('\n')
        }
    }

    /**
     * [line] as it is written in a text: with a backslash in front when it
     * would otherwise read as a setting, a header, a separator or an escape,
     * or when it is a blank line at one end of the text ([edge]).
     */
    internal fun escapeLine(line: String, edge: Boolean): String {
        val needs = (edge && line.isBlank()) ||
            line.startsWith("@") || line.startsWith("===") || line.startsWith(SEPARATOR) ||
            (line.startsWith("\\") && isEscape(line))
        return if (needs) "\\" + line else line
    }

    /** True when [line], starting with a backslash, is one [escapeLine] wrote. */
    private fun isEscape(line: String): Boolean {
        val rest = line.substring(1)
        return rest.isBlank() || rest[0] == '@' || rest[0] == '=' || rest[0] == '-' || rest[0] == '\\'
    }

    private fun unescape(line: String): String =
        if (line.startsWith("\\") && isEscape(line)) line.substring(1) else line

    private fun caseName(style: UppercaseStyle): String = when (style) {
        UppercaseStyle.CAPITALIZE -> "capitalize"
        UppercaseStyle.CAPITALIZE_WORDS -> "capitalize_words"
        UppercaseStyle.UPPERCASE -> "uppercase"
    }

    // ---- reading -----------------------------------------------------------

    /**
     * Reads [document]. Every block with something wrong is left out of the
     * entries and reported with the line it went wrong on; the rest are read.
     */
    fun parse(document: String): Parsed {
        val lines = document.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val entries = ArrayList<Entry>()
        val problems = ArrayList<Problem>()
        var i = 0
        var strayReported = false
        // Before the first block: comments and blank lines only.
        while (i < lines.size && !HEADER.matches(lines[i])) {
            val line = lines[i]
            if (line.isNotBlank() && !line.startsWith(COMMENT) && !strayReported) {
                problems += Problem(
                    i + 1,
                    ContentText(
                        if (line.startsWith("===")) R.string.core_content_snippet_text_bad_header
                        else R.string.core_content_snippet_text_before_header,
                    ),
                )
                strayReported = true
            }
            i++
        }
        while (i < lines.size) {
            val start = i
            i++
            while (i < lines.size && !HEADER.matches(lines[i])) i++
            readBlock(lines, start, i, entries, problems)
        }
        return Parsed(entries, problems)
    }

    private fun readBlock(
        lines: List<String>,
        start: Int,
        end: Int,
        entries: MutableList<Entry>,
        problems: MutableList<Problem>,
    ) {
        val before = problems.size
        val headerLine = start + 1
        val trigger = HEADER.find(lines[start])!!.groupValues[1].trim().takeIf { it.isNotEmpty() }
        var label: String? = null
        val aliases = ArrayList<String>()
        var pattern: String? = null
        var words = 0
        var case: UppercaseStyle? = null
        var confirm = false
        var immediate = false
        var insideWords = false
        var several = MultiExpand.DEFAULT
        var folder: String? = null
        val tags = ArrayList<String>()
        val links = ArrayList<String>()
        var picture: String? = null

        fun bad(at: Int, name: String, value: String) {
            problems += Problem(at + 1, ContentText(R.string.core_content_snippet_text_bad_value, args = listOf("@$name", value)))
        }

        var i = start + 1
        while (i < end && lines[i].startsWith("@")) {
            val match = SETTING.find(lines[i])
            val name = match?.groupValues?.get(1)?.lowercase(Locale.ROOT).orEmpty()
            if (match == null || name !in KNOWN) {
                problems += Problem(
                    i + 1,
                    ContentText(R.string.core_content_snippet_text_unknown_setting, args = listOf(lines[i].substringBefore(':').trim())),
                )
                i++
                continue
            }
            val value = match.groupValues[2].trim()
            when (name) {
                "label" -> label = value
                "alias" -> if (value.isNotEmpty()) aliases += value
                "pattern" -> {
                    val error = SnippetMatcher.validate(value)
                    if (error == null) {
                        pattern = value
                    } else {
                        problems += Problem(
                            i + 1,
                            ContentText(R.string.core_content_snippet_text_bad_pattern, args = listOf(error.description ?: value)),
                        )
                    }
                }
                "words" -> {
                    val n = value.toIntOrNull()
                    if (n == null || n !in 0..SnippetMatcher.MAX_WORDS) bad(i, name, value) else words = n
                }
                "case" -> case = when (value.lowercase(Locale.ROOT)) {
                    "capitalize" -> UppercaseStyle.CAPITALIZE
                    "capitalize_words", "words" -> UppercaseStyle.CAPITALIZE_WORDS
                    "uppercase" -> UppercaseStyle.UPPERCASE
                    else -> {
                        bad(i, name, value)
                        null
                    }
                }
                "flags" -> for (flag in value.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }) {
                    when (flag) {
                        "confirm" -> confirm = true
                        "immediate" -> immediate = true
                        "inside-words", "inside_words" -> insideWords = true
                        else -> bad(i, name, flag)
                    }
                }
                "several" -> several = when (value.lowercase(Locale.ROOT)) {
                    "chips" -> MultiExpand.CHIPS_ONLY
                    "insert" -> MultiExpand.INSERT_FIRST
                    "default", "" -> MultiExpand.DEFAULT
                    else -> {
                        bad(i, name, value)
                        MultiExpand.DEFAULT
                    }
                }
                "folder" -> folder = value.takeIf { it.isNotEmpty() }
                "tag" -> if (value.isNotEmpty()) tags += value
                "link" -> if (value.isNotEmpty()) links += value
                "picture" -> if (SnippetStore.isImageName(value)) picture = value else bad(i, name, value)
            }
            i++
        }

        // The text: what is left, with blank lines at the end dropped (the gap
        // before the next block) and the ones at the start too, since the
        // writer escapes any blank line a text really starts with.
        val bodyStart = i
        val expansions = ArrayList<String>()
        var from = bodyStart
        for (j in bodyStart..end) {
            if (j == end || lines[j] == SEPARATOR) {
                expansions += text(lines, from, j)
                from = j + 1
            } else if (lines[j].startsWith("===")) {
                problems += Problem(j + 1, ContentText(R.string.core_content_snippet_text_bad_header))
            }
        }
        val main = expansions.first()
        if (trigger == null && label == null && pattern == null) {
            problems += Problem(headerLine, ContentText(R.string.core_content_snippet_text_no_trigger))
        }
        if (main.isEmpty() && picture == null) {
            problems += Problem(headerLine, ContentText(R.string.core_content_snippet_text_no_text))
        }
        if (problems.size > before) return
        entries += Entry(
            snippet = Snippet(
                id = 0,
                label = label ?: trigger ?: "",
                text = main,
                trigger = trigger,
                aliases = aliases,
                propagateCase = case != null,
                uppercaseStyle = case ?: UppercaseStyle.CAPITALIZE,
                triggerPattern = pattern,
                triggerWords = words,
                confirm = confirm,
                immediate = immediate,
                insideWords = insideWords,
                alternates = expansions.drop(1).filter { it.isNotBlank() },
                tags = tags,
                multiExpand = several,
                image = picture,
            ),
            folder = folder,
            links = links,
            line = headerLine,
        )
    }

    /** Lines [from] until [until] as one text: blank edges trimmed, escapes taken off. */
    private fun text(lines: List<String>, from: Int, until: Int): String {
        var a = from
        var b = until
        while (a < b && lines[a].isBlank()) a++
        while (b > a && lines[b - 1].isBlank()) b--
        if (a >= b) return ""
        return (a until b).joinToString("\n") { unescape(lines[it]) }
    }

    // ---- saving over the stored snippets ------------------------------------

    /**
     * What saving [parsed] over [current] would do.
     *
     * [snippets] is the whole list as it would be stored, in the document's
     * order. A block that matched a stored snippet keeps its id and creation
     * time; a new one has a negative id, and links to it carry that id, for
     * [SnippetStore.replaceFromText] to swap for a real one. A folder the
     * document names that does not exist yet is in [newFolders], and the
     * snippets in it point at `-(index + 1)`.
     */
    data class Plan(
        val snippets: List<Snippet>,
        val newFolders: List<String>,
        val added: List<Snippet>,
        val changed: List<Snippet>,
        val removed: List<Snippet>,
        /** True when nothing was added, changed or removed but the order moved. */
        val reordered: Boolean,
        val problems: List<Problem>,
    ) {
        val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && removed.isEmpty() && !reordered
    }

    /**
     * Matches [parsed] to [current] and works out the [Plan].
     *
     * A block is the stored snippet with the same key ([keyOf]), first come
     * first served; one left over is then matched on its text, so editing a
     * trigger reads as a change rather than a delete and an add. A `@picture`
     * must be one of [knownImages]: pictures are added in the snippet's own
     * editor, not by typing a file name. A `@link` must name a block.
     */
    fun plan(
        current: List<Snippet>,
        folders: List<SnippetFolder>,
        parsed: Parsed,
        knownImages: Set<String>,
    ): Plan {
        val problems = parsed.problems.toMutableList()
        val entries = parsed.entries
        val matched = arrayOfNulls<Snippet>(entries.size)
        val used = HashSet<Long>()
        val byKey = current.groupBy { keyOf(it) }
        entries.forEachIndexed { i, entry ->
            val old = byKey[entry.key]?.firstOrNull { it.id !in used } ?: return@forEachIndexed
            matched[i] = old
            used += old.id
        }
        entries.forEachIndexed { i, entry ->
            if (matched[i] != null) return@forEachIndexed
            val old = current.firstOrNull {
                it.id !in used && it.text == entry.snippet.text && it.image == entry.snippet.image
            } ?: return@forEachIndexed
            matched[i] = old
            used += old.id
        }
        // Final ids: the stored one, or a provisional negative for a new block.
        var provisional = 0L
        val ids = LongArray(entries.size) { i -> matched[i]?.id ?: --provisional }
        val idByKey = HashMap<String, Long>()
        entries.forEachIndexed { i, entry -> idByKey.putIfAbsent(entry.key, ids[i]) }
        val idByLowerKey = HashMap<String, Long>()
        entries.forEachIndexed { i, entry -> idByLowerKey.putIfAbsent(entry.key.lowercase(Locale.ROOT), ids[i]) }

        val newFolders = ArrayList<String>()
        val liveIds = current.mapTo(HashSet()) { it.id }
        val out = ArrayList<Snippet>(entries.size)
        val added = ArrayList<Snippet>()
        val changed = ArrayList<Snippet>()
        entries.forEachIndexed { i, entry ->
            val old = matched[i]
            entry.snippet.image?.let { name ->
                if (name !in knownImages && name != old?.image) {
                    problems += Problem(entry.line, ContentText(R.string.core_content_snippet_text_unknown_picture, args = listOf(name)))
                }
            }
            val children = entry.links.mapNotNull { key ->
                val id = idByKey[key] ?: idByLowerKey[key.lowercase(Locale.ROOT)]
                if (id == null) {
                    problems += Problem(entry.line, ContentText(R.string.core_content_snippet_text_unknown_link, args = listOf(key)))
                }
                id
            }
            val folderName = entry.folder
            val folderId = when {
                folderName == null -> 0L
                // A stored snippet already in a folder of that name stays in
                // that very folder, even when two folders share the name.
                old != null && folders.firstOrNull { it.id == old.folderId }?.name == folderName -> old.folderId
                else -> folders.firstOrNull { it.name == folderName }?.id
                    ?: run {
                        var index = newFolders.indexOf(folderName)
                        if (index < 0) {
                            newFolders += folderName
                            index = newFolders.size - 1
                        }
                        -(index + 1L)
                    }
            }
            val snippet = entry.snippet.copy(
                id = ids[i],
                createdAt = old?.createdAt ?: 0L,
                folderId = folderId,
                children = children,
                // Off, the style is not written, so the stored one is kept.
                uppercaseStyle = if (!entry.snippet.propagateCase && old != null) old.uppercaseStyle else entry.snippet.uppercaseStyle,
            )
            out += snippet
            when {
                old == null -> added += snippet
                snippet != old.copy(children = old.children.filter { it in liveIds }) -> changed += snippet
            }
        }
        val removed = current.filter { it.id !in used }
        val reordered = added.isEmpty() && removed.isEmpty() && out.map { it.id } != current.map { it.id }
        return Plan(out, newFolders, added, changed, removed, reordered, problems.sortedBy { it.line })
    }

    // ---- importing ------------------------------------------------------------

    /** A document read for import: snippets and folders with file-local ids, for [SnippetStore.addAll]. */
    data class Import(
        val snippets: List<Snippet>,
        val folders: List<SnippetFolder>,
        val notes: List<ContentText>,
    )

    /**
     * [parsed] as an import. Blocks with mistakes are skipped and named by
     * line; pictures stay behind, since a text file holds only their names,
     * and a picture snippet with no words is skipped.
     */
    fun toImport(parsed: Parsed): Import {
        val notes = ArrayList<ContentText>()
        for (line in parsed.problems.map { it.line }.distinct()) {
            notes += ContentText(R.string.core_content_snippet_text_skipped_line, args = listOf(line))
        }
        val folderIds = LinkedHashMap<String, Long>()
        val kept = parsed.entries.filter { it.snippet.image == null || it.snippet.text.isNotEmpty() }
        val pictures = parsed.entries.count { it.snippet.image != null }
        if (pictures > 0) {
            notes += ContentText(pluralsRes = R.plurals.core_content_snippet_text_pictures_left, quantity = pictures, args = listOf(pictures))
        }
        val ids = HashMap<String, Long>()
        kept.forEachIndexed { i, entry -> ids.putIfAbsent(entry.key, i + 1L) }
        val snippets = kept.mapIndexed { i, entry ->
            entry.snippet.copy(
                id = i + 1L,
                image = null,
                folderId = entry.folder?.let { name -> folderIds.getOrPut(name) { folderIds.size + 1L } } ?: 0L,
                children = entry.links.mapNotNull { ids[it] },
            )
        }
        val folders = folderIds.map { (name, id) -> SnippetFolder(id = id, name = name) }
        return Import(snippets, folders, notes)
    }
}

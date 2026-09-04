package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SnippetEnvelope(
    val format: String = "",
    val version: Int = 0,
    val appVersion: Int = 0,
    val appVersionName: String = "",
    val snippets: List<Snippet> = emptyList(),
    val folders: List<SnippetFolder> = emptyList(),
)

/**
 * Snippets read out of a file, with whatever had to be fixed to use them.
 *
 * [repairs] arrive unresolved: this reader has no Context, so the screen that
 * lists the notes calls [ContentText.resolve] on each one.
 *
 * [folders] are the groups the file declared. Their ids mean something only
 * within the file — [SnippetStore.addAll] reassigns them on the way in, exactly
 * as it does for snippet ids.
 */
data class ImportedSnippets(
    val snippets: List<Snippet>,
    val repairs: List<ContentText>,
    /** Version code of the build that wrote the file; 0 when unstated. */
    val fromAppVersion: Int,
    val folders: List<SnippetFolder> = emptyList(),
)

/**
 * The `.wmsnippets.json` file: the app's native snippet export and import.
 *
 * ```json
 * {
 *   "format": "wmkeyboard-snippets",
 *   "version": 2,
 *   "appVersion": 41,
 *   "appVersionName": "1.4.0",
 *   "snippets": [
 *     { "id": 1, "label": "Shrug", "text": "¯\\_(ツ)_/¯", "trigger": "shrug" }
 *   ]
 * }
 * ```
 *
 * A snippet may also carry `alternates` (further expansions, `text` being the
 * first and the default), `children` (ids of snippets it links to, meaningful
 * only within the file), `tags`, and `multiExpand`. A file written before those
 * existed simply has none of them.
 *
 * Same versioned envelope as [com.wasimaster.wmkeyboard.core.layout.LayoutFile]:
 * `format` is the one strict check, and everything past it is repaired and
 * reported rather than refused, because a file assembled by hand shouldn't fail
 * over one bad row.
 *
 * `id` is written for readability but **never trusted** — [SnippetStore.add]
 * assigns fresh ids on the way in, so importing the same pack twice produces
 * two independent sets rather than silently overwriting the first.
 */
object SnippetFile {

    const val FORMAT = "wmkeyboard-snippets"

    /**
     * 2 since snippets grew several expansions and links to each other.
     *
     * Documentary: nothing reads it. A version-1 file has none of the new keys
     * and loads as it always did, and a version-1 *reader* ignores keys it does
     * not know, so it reads a version-2 file as the plain snippets it also is.
     */
    const val VERSION = 2

    const val FILE_EXTENSION = "wmsnippets.json"

    /**
     * Plain JSON rather than a vendor type, for the reason the layout format
     * gives: a custom MIME would stop most file managers and chat apps from
     * offering the file at all.
     */
    const val MIME_TYPE = "application/json"

    /** Permissive on purpose; the format tag inside is the real check. */
    val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

    /** A snippet longer than this is a pasted document, not a snippet. */
    private const val MAX_TEXT_LENGTH = 20_000

    /** Enough for anyone; a file past this is a generated dump. */
    private const val MAX_SNIPPETS = 500

    /** Folders are a filing aid, not a data structure; a file past this is junk. */
    private const val MAX_FOLDERS = 100

    // Values that do not fit their type are coerced to the default rather than
    // thrown: a single unrecognised enum word — one this build is older than —
    // would otherwise turn a whole file of good snippets into "not a snippet
    // file", with no note and no way to see why.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun fileName(): String = "snippets.$FILE_EXTENSION"

    /**
     * Writes [snippets] and the [folders] they are filed under.
     *
     * Folders travel with the snippets rather than being flattened away,
     * because a pack of thirty replies filed into four groups is a different
     * thing from thirty loose replies — and the reader can always drop the
     * grouping, while it can never invent it back.
     */
    fun encode(
        snippets: List<Snippet>,
        appVersion: Int,
        appVersionName: String,
        folders: List<SnippetFolder> = emptyList(),
    ): String =
        json.encodeToString(
            SnippetEnvelope(
                format = FORMAT,
                version = VERSION,
                appVersion = appVersion,
                appVersionName = appVersionName,
                snippets = snippets,
                // Only the folders something is actually filed in: exporting a
                // subset of a library must not carry its empty shelves along.
                folders = folders.filter { folder -> snippets.any { it.folderId == folder.id } },
            ),
        )

    /**
     * Parses [text], or returns null when it is not a snippet file at all.
     *
     * A row with no text is dropped — there is nothing for it to insert. A row
     * with no label is kept and labelled from its own text, since the label is
     * only how the panel lists it.
     */
    fun decode(text: String): ImportedSnippets? {
        val envelope = runCatching { json.decodeFromString<SnippetEnvelope>(text) }.getOrNull()
            ?: return null
        if (envelope.format != FORMAT) return null

        val repairs = ArrayList<ContentText>()
        val kept = ArrayList<Snippet>()
        val folders = keptFolders(envelope.folders, repairs)
        val folderIds = folders.mapTo(HashSet()) { it.id }
        // A link means something only inside the file that declares both ends.
        // [SnippetStore.addAll] re-points these at the ids it hands out; one
        // naming a snippet the file never carried would land on whatever this
        // user happens to have stored under that number.
        val declared = envelope.snippets.mapTo(HashSet()) { it.id }

        for (snippet in envelope.snippets) {
            if (kept.size >= MAX_SNIPPETS) {
                repairs += ContentText(
                    pluralsRes = R.plurals.core_content_snippet_repair_kept_first,
                    quantity = MAX_SNIPPETS,
                    args = listOf(MAX_SNIPPETS),
                )
                break
            }
            val body = snippet.text
            if (body.isBlank()) {
                repairs += ContentText(
                    R.string.core_content_snippet_repair_no_text,
                    args = listOf(snippet.label.take(30)),
                )
                continue
            }
            val trimmedBody = if (body.length > MAX_TEXT_LENGTH) {
                repairs += ContentText(
                    pluralsRes = R.plurals.core_content_snippet_repair_shortened,
                    quantity = MAX_TEXT_LENGTH,
                    args = listOf(snippet.label.take(30), MAX_TEXT_LENGTH),
                )
                body.take(MAX_TEXT_LENGTH)
            } else {
                body
            }
            val label = snippet.label.ifBlank {
                repairs += ContentText(R.string.core_content_snippet_repair_named_after_text)
                trimmedBody.lineSequence().first().take(40)
            }
            kept += snippet.copy(
                label = label,
                text = trimmedBody,
                alternates = keptAlternates(snippet, label, repairs),
                // Silent, like an undeclared folder: a link is filing, not
                // content, and the snippet inserts the same text without it.
                children = snippet.children.filter { it in declared && it != snippet.id },
                tags = snippet.tags.mapNotNull { tag ->
                    tag.trim().take(SnippetStore.MAX_TAG_LENGTH).takeIf { it.isNotEmpty() }
                }.take(SnippetStore.MAX_TAGS),
                trigger = snippet.trigger?.trim()?.takeIf { it.isNotEmpty() },
                triggerPattern = keptPattern(snippet, label, repairs),
                // A number nudged back into range is not lost content, so it
                // earns no note; every other note here is about content.
                triggerWords = snippet.triggerWords.coerceIn(0, SnippetMatcher.MAX_WORDS),
                // A folder the file never declared is dropped rather than
                // carried: the importer would have to invent a name for it, and
                // an ungrouped snippet is a filing detail, not lost content.
                folderId = snippet.folderId.takeIf { it in folderIds } ?: 0,
            )
        }

        return ImportedSnippets(
            snippets = kept,
            repairs = repairs,
            fromAppVersion = envelope.appVersion,
            // Only the ones something actually landed in: a folder whose every
            // snippet was dropped for having no text would otherwise arrive as
            // an empty group nobody asked for.
            folders = folders.filter { folder -> kept.any { it.folderId == folder.id } },
        )
    }

    /**
     * The snippet's further expansions, shortened and capped like its text.
     *
     * Each one is a thing the snippet may insert, so the same limits apply for
     * the same reasons, and both are worth a note: an expansion that quietly
     * vanished is a chip the user set up and will never see.
     */
    private fun keptAlternates(
        snippet: Snippet,
        label: String,
        repairs: MutableList<ContentText>,
    ): List<String> {
        if (snippet.alternates.isEmpty()) return emptyList()
        val out = ArrayList<String>(snippet.alternates.size)
        var dropped = 0
        for (alternate in snippet.alternates) {
            if (alternate.isBlank()) continue
            if (out.size >= SnippetStore.MAX_ALTERNATES) {
                dropped++
                continue
            }
            if (alternate.length > MAX_TEXT_LENGTH) {
                repairs += ContentText(
                    pluralsRes = R.plurals.core_content_snippet_repair_shortened,
                    quantity = MAX_TEXT_LENGTH,
                    args = listOf(label.take(30), MAX_TEXT_LENGTH),
                )
                out += alternate.take(MAX_TEXT_LENGTH)
            } else {
                out += alternate
            }
        }
        if (dropped > 0) {
            repairs += ContentText(
                pluralsRes = R.plurals.core_content_snippet_repair_alternates_dropped,
                quantity = dropped,
                args = listOf(label.take(30), dropped),
            )
        }
        return out
    }

    /**
     * The folders the app will use, with whatever had to be dropped noted.
     *
     * A folder is only a name and a switch, so the checks are the ones that can
     * actually mislead: a nameless folder (nothing to draw, nothing to pick in
     * a menu), a second folder claiming an id the file already used, which
     * would silently swallow the first one's snippets, and id 0, which is how
     * every snippet spells "in no folder at all".
     */
    private fun keptFolders(
        folders: List<SnippetFolder>,
        repairs: MutableList<ContentText>,
    ): List<SnippetFolder> {
        if (folders.isEmpty()) return emptyList()
        val kept = ArrayList<SnippetFolder>(folders.size)
        val seen = HashSet<Long>(folders.size)
        var dropped = 0
        for (folder in folders) {
            if (kept.size >= MAX_FOLDERS ||
                folder.id == 0L ||
                folder.name.isBlank() ||
                !seen.add(folder.id)
            ) {
                dropped++
                continue
            }
            kept += folder.copy(name = folder.name.trim())
        }
        if (dropped > 0) {
            repairs += ContentText(
                pluralsRes = R.plurals.core_content_snippet_repair_folders_dropped,
                quantity = dropped,
                args = listOf(dropped),
            )
        }
        return kept
    }

    /**
     * The snippet's pattern, or null when the app will not run it.
     *
     * A broken pattern drops the pattern and keeps the snippet. A row is
     * dropped only when there is nothing left to insert, and a snippet whose
     * trigger no longer works still inserts from the panel.
     */
    private fun keptPattern(snippet: Snippet, label: String, repairs: MutableList<ContentText>): String? {
        val source = snippet.triggerPattern?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val fault = SnippetMatcher.validate(source)?.fault ?: return source
        repairs += if (fault == SnippetMatcher.Fault.TOO_LONG) {
            ContentText(
                pluralsRes = R.plurals.core_content_snippet_repair_pattern_too_long,
                quantity = SnippetMatcher.MAX_PATTERN_LENGTH,
                args = listOf(label.take(30), SnippetMatcher.MAX_PATTERN_LENGTH),
            )
        } else {
            ContentText(
                R.string.core_content_snippet_repair_bad_pattern,
                args = listOf(label.take(30)),
            )
        }
        return null
    }
}

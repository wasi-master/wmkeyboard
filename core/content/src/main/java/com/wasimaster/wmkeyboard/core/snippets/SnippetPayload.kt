package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoFile
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoPackage
import java.io.File

/**
 * Works out what a file of snippets is, and reads it.
 *
 * Three places ask this question — the file picker on the Text Expander screen,
 * the import-from-a-URL dialog, and the add-on installer — and they must not be
 * able to disagree, so the decision lives here rather than three times over.
 *
 * The order is the safety property, and it is the same one `WMFileTypes` uses
 * for the app's other formats: an archive is recognised by its first four bytes,
 * then the app's own **tagged** format is tried, then Gboard's dictionary (whose
 * first line is as good as a tag), then the untagged Espanso one, and a table
 * last of all. An untagged format must never be tried ahead of a tagged one,
 * because it has no way to say the file was not meant for it, and a table is
 * the loosest of them: almost any text has a comma in it.
 */
object SnippetPayload {

    /** Which format a payload turned out to be in. */
    enum class Source {
        /** The app's own `.wmsnippets.json`. */
        NATIVE,

        /** An Espanso match file. */
        ESPANSO_FILE,

        /** An Espanso package archive. */
        ESPANSO_PACKAGE,

        /** A CSV or TSV table from another text expander or a spreadsheet. */
        CSV,

        /** Gboard's personal dictionary export: its shortcuts, as snippets. */
        GBOARD,
    }

    /**
     * A payload after reading, whatever it turned out to be.
     *
     * [folders] is only ever filled by the app's own format; Espanso has no
     * folder of its own, and its packages arrive as one folder named by
     * [suggestedName].
     */
    data class Parsed(
        val snippets: List<Snippet>,
        val folders: List<SnippetFolder>,
        val notes: List<ContentText>,
        val source: Source,
        val suggestedName: String,
        /**
         * Pictures the payload carried, by the [Snippet.image] name its
         * snippets hold: the app's own file or an Espanso package; pass them to
         * [SnippetStore.addAll] along with the snippets.
         */
        val images: Map<String, ByteArray> = emptyMap(),
    ) {

        /** True when this came from Espanso, in either of its two shapes. */
        val isEspanso: Boolean get() = source == Source.ESPANSO_FILE || source == Source.ESPANSO_PACKAGE

        /**
         * True when this came from anything but the app's own format, and so
         * is described and confirmed before it is written.
         */
        val isForeign: Boolean get() = source != Source.NATIVE
    }

    /** Bytes read to tell an archive from text. */
    private const val MAGIC_BYTES = 4

    /** MIME types the file picker offers for every format. */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/json",
        "text/yaml",
        "application/x-yaml",
        "application/yaml",
        "application/zip",
        "text/plain",
        "application/octet-stream",
    ) + SnippetCsv.MIME_TYPES

    /**
     * Reads [payload], or returns null when it is none of the formats.
     *
     * [fallbackName] names the folder an Espanso file lands in when it carries
     * no better name of its own; pass the file name. [expectTable] lets a table
     * with no header row through, read by position: pass it when the user said
     * the file is a table. A `.csv` or `.tsv` name says the same thing.
     */
    fun read(payload: File, fallbackName: String, expectTable: Boolean = false): Parsed? = runCatching {
        if (payload.length() > EspansoFile.MAX_BYTES) return@runCatching null
        read(payload.readBytes(), fallbackName, expectTable)
    }.getOrNull()

    /**
     * The same question asked of bytes already in memory.
     *
     * What the file picker and the URL import both have: a stream that has to be
     * drained anyway, and no file on disk to hand over.
     */
    fun read(payload: ByteArray, fallbackName: String, expectTable: Boolean = false): Parsed? = runCatching {
        if (payload.size > EspansoFile.MAX_BYTES) return@runCatching null
        if (payload.size >= MAGIC_BYTES && EspansoPackage.looksLikeZip(payload.copyOf(MAGIC_BYTES))) {
            // Gboard's export first: an Espanso package never holds a
            // `dictionary.txt`, and Gboard's never holds a `package.yml`.
            payload.inputStream().use { GboardDictionary.readZip(it) }?.let { return@runCatching gboard(it) }
            val archive = payload.inputStream().use { EspansoPackage.read(it, fallbackName) }
                ?: return@runCatching null
            return@runCatching Parsed(
                snippets = archive.snippets,
                folders = emptyList(),
                notes = archive.notes,
                source = Source.ESPANSO_PACKAGE,
                suggestedName = archive.folderName,
                images = archive.images,
            )
        }
        readText(payload.decodeToString(), fallbackName, expectTable)
    }.getOrNull()

    /** The text half of [read], split out so the URL import can reuse it. */
    fun readText(text: String, fallbackName: String, expectTable: Boolean = false): Parsed? {
        SnippetFile.decode(text)?.let { native ->
            return Parsed(
                snippets = native.snippets,
                folders = native.folders,
                notes = native.repairs,
                source = Source.NATIVE,
                suggestedName = fallbackName.substringBefore('.'),
                images = native.images,
            )
        }
        GboardDictionary.read(text)?.let { return gboard(it) }
        if (EspansoFile.looksLikeEspanso(text)) {
            val espanso = EspansoFile.read(text, fallbackName) ?: return null
            return Parsed(
                snippets = espanso.snippets,
                folders = emptyList(),
                notes = espanso.notes,
                source = Source.ESPANSO_FILE,
                suggestedName = espanso.folderName,
            )
        }
        val table = SnippetCsv.read(text, allowHeaderless = expectTable || SnippetCsv.isTableName(fallbackName))
            ?: return null
        return Parsed(
            snippets = table.snippets,
            folders = table.folders,
            notes = table.notes,
            source = Source.CSV,
            suggestedName = fallbackName.substringBeforeLast('.').trim(),
        )
    }

    /**
     * A Gboard dictionary's shortcuts. Its plain words have nowhere to go on a
     * snippet screen, so they are counted in a note that says where they do.
     */
    private fun gboard(contents: GboardDictionary.Contents): Parsed {
        val converted = GboardDictionary.toSnippets(contents)
        val words = contents.words.size
        val notes = if (words > 0) {
            converted.notes + ContentText(
                pluralsRes = R.plurals.core_content_snippet_gboard_words_skipped,
                quantity = words,
                args = listOf(words),
            )
        } else {
            converted.notes
        }
        return Parsed(
            snippets = converted.snippets,
            folders = emptyList(),
            notes = notes,
            source = Source.GBOARD,
            suggestedName = GboardDictionary.FOLDER_NAME,
        )
    }
}

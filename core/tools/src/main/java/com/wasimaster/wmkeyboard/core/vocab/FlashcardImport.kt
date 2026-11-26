package com.wasimaster.wmkeyboard.core.vocab

import java.util.Locale

/**
 * Turns other apps' flashcards into a vocabulary pack: Anki notes (read out
 * of a deck by [AnkiPackage]) and term/definition text — Quizlet's export,
 * Anki's own "Notes in Plain Text", or any CSV or TSV with a word column.
 *
 * Everything here is plain text in, [VocabPack] out, so it is tested on the
 * JVM; the zip and SQLite side lives in [AnkiPackage].
 *
 * A card becomes one word: the front is the lemma, the back its definition,
 * and an example or reading field, when the note type has one by name, fills
 * those parts of the card. A front that repeats an earlier one adds a sense
 * instead of a second word. Fronts too long to be a word (a sentence card,
 * a cloze) are skipped rather than made into a word nobody would type.
 */
object FlashcardImport {

    /** One card, already stripped to plain text. */
    data class Card(
        val term: String,
        val definition: String = "",
        val example: String? = null,
        val reading: String? = null,
    )

    /** An Anki note: its note type's id and its fields, still HTML. */
    data class AnkiNote(val notetypeId: Long, val fields: List<String>)

    private const val MAX_SENSES = 8

    private val TERM_NAMES = setOf(
        "front", "word", "term", "expression", "vocab", "vocabulary", "vocabulary-kanji", "kanji",
        "hanzi", "simplified", "traditional", "question", "target", "target word", "lemma", "headword",
    )
    private val DEFINITION_NAMES = setOf(
        "back", "meaning", "meanings", "definition", "definitions", "answer", "english", "translation",
        "gloss", "def", "vocabulary-english", "sense",
    )
    private val EXAMPLE_NAMES = setOf("example", "examples", "sentence", "example sentence", "context")
    private val READING_NAMES = setOf(
        "reading", "pronunciation", "ipa", "pinyin", "furigana", "kana", "romaji", "jyutping",
        "vocabulary-kana", "transliteration", "romanization", "romanisation",
    )

    /** Anki text-export columns that are bookkeeping, not card text. */
    private val META_HEADERS = setOf("tags", "tag", "deck", "notetype", "note type", "guid", "id")

    // ------------------------------------------------------------ Anki notes

    /**
     * Cards from Anki [notes], whose note types' field names are in
     * [fieldNames] (by note type id). A note type missing from the map reads
     * as front = first field, back = second.
     */
    fun cardsFromAnki(notes: List<AnkiNote>, fieldNames: Map<Long, List<String>>): List<Card> {
        val roles = HashMap<Long, Roles>()
        val out = ArrayList<Card>(notes.size)
        for (note in notes) {
            if (note.fields.isEmpty()) continue
            // A cloze note has no front to speak of: its "word" is a sentence
            // with the answer cut out.
            if (CLOZE.containsMatchIn(note.fields[0])) continue
            val role = roles.getOrPut(note.notetypeId) { rolesFor(fieldNames[note.notetypeId], note.fields.size) }
            out += role.card(note.fields.map(::plainText)) ?: continue
        }
        return out
    }

    // ------------------------------------------------------- delimited text

    /**
     * Cards from term/definition text. Reads Anki's `#separator:` / `#columns:`
     * header lines when they are there; otherwise the separator is whichever
     * of tab, comma, semicolon or " - " most lines carry (tab for a `.tsv`,
     * comma for a `.csv`). A first row of column names ("Front, Back",
     * "Term, Definition") is used to pick the columns; without one, the first
     * column is the term and the rest, joined back, the definition — which is
     * also what repairs Quizlet's comma export, where a comma inside a
     * definition is not quoted.
     */
    fun cardsFromText(raw: String, fileName: String = ""): List<Card> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (!isCardText(text)) return emptyList()
        val lines = text.split('\n')
        var separator: String? = null
        var header: List<String>? = null
        val skipColumns = HashSet<Int>()
        var html = true
        var bodyStart = 0
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#") || !line.contains(':')) break
            val key = line.substring(1).substringBefore(':').trim().lowercase(Locale.ROOT)
            val value = line.substringAfter(':')
            when (key) {
                "separator" -> separator = separatorNamed(value.trim())
                "html" -> html = value.trim().lowercase(Locale.ROOT) != "false"
                "columns" -> header = value.split(separator ?: "\t").map { it.trim() }
                "notetype column", "deck column", "tags column", "guid column" ->
                    value.trim().toIntOrNull()?.let { skipColumns += it - 1 }
                else -> break
            }
            bodyStart = i + 1
        }
        val body = lines.drop(bodyStart).joinToString("\n")
        val sep = separator ?: detectSeparator(body, fileName) ?: return emptyList()
        val rows = splitRows(body, sep).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()

        var dataRows = rows
        if (header == null && looksLikeHeader(rows[0])) {
            header = rows[0]
            dataRows = rows.drop(1)
        }
        val joiner = if (sep == "\t") "; " else if (sep == " - ") " - " else "$sep "
        val clean: (String) -> String = if (html) ::plainText else ::plainTextNoHtml
        val out = ArrayList<Card>(dataRows.size)
        val names = header?.mapIndexed { i, name -> if (i in skipColumns) "" else name }
        for (row in dataRows) {
            val cells = row.mapIndexed { i, cell -> if (i in skipColumns) "" else cell }
            val card = if (names != null) {
                rolesFor(names, cells.size).card(cells.map(clean))
            } else {
                val kept = cells.filterIndexed { i, _ -> i !in skipColumns }
                val term = clean(kept.firstOrNull().orEmpty())
                val rest = kept.drop(1).filter { it.isNotBlank() }.joinToString(joiner) { clean(it) }
                Card(term, rest).takeIf { term.isNotEmpty() }
            }
            out += card ?: continue
        }
        return out
    }

    // ----------------------------------------------------------------- pack

    /**
     * Folds [cards] into a pack called [name]: lemmas normalised the way the
     * index keys them, repeated fronts merged into one word with several
     * senses, and the [VocabPackFile.MAX_WORDS] cap applied.
     */
    fun toPack(cards: List<Card>, name: String, langId: String = "en"): VocabPack {
        val words = LinkedHashMap<String, VocabWord>()
        for (card in cards) {
            val lemma = VocabPackFile.normalizeLemma(card.term) ?: continue
            val sense = VocabSense(definition = card.definition, example = card.example)
            val existing = words[lemma]
            if (existing != null) {
                if (card.definition.isEmpty() || existing.senses.size >= MAX_SENSES) continue
                if (existing.senses.any { it.definition == card.definition }) continue
                val senses = existing.senses.filter { it.definition.isNotEmpty() || it.example != null }
                words[lemma] = existing.copy(senses = senses + sense)
                continue
            }
            if (words.size >= VocabPackFile.MAX_WORDS) continue
            words[lemma] = VocabWord(
                word = lemma,
                respelling = card.reading,
                senses = if (card.definition.isEmpty() && card.example == null) emptyList() else listOf(sense),
            )
        }
        return VocabPack(
            meta = VocabPackMeta(name = name.trim().take(60).ifEmpty { "Flashcards" }, langId = langId),
            words = words.values.toList(),
        )
    }

    /**
     * A display name for a pack read from [fileName]: extension dropped, and
     * the generic names Anki gives whole-collection exports replaced by null
     * so the caller can prefer the deck's own name.
     */
    fun nameFromFile(fileName: String): String? {
        val base = fileName.substringAfterLast('/')
            .replace(Regex("""(\.(apkg|colpkg|csv|tsv|txt|json|gz))+$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (base.isEmpty() || base.startsWith("collection-", ignoreCase = true)) return null
        return base
    }

    // ------------------------------------------------------------ internals

    /** Which field index plays which part, for one note type or one header row. */
    private class Roles(val term: Int, val definition: Int?, val example: Int?, val reading: Int?, val extras: List<Int>) {
        fun card(cells: List<String>): Card? {
            val term = cells.getOrNull(term).orEmpty()
            if (term.isEmpty()) return null
            val definition = definition?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() }
                ?: extras.mapNotNull { cells.getOrNull(it)?.takeIf { c -> c.isNotEmpty() } }.firstOrNull()
                ?: ""
            return Card(
                term = term,
                definition = definition,
                example = example?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() },
                reading = reading?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() && !it.equals(term, ignoreCase = true) },
            )
        }
    }

    private fun rolesFor(names: List<String>?, fieldCount: Int): Roles {
        val lower = names?.map { it.trim().lowercase(Locale.ROOT) }.orEmpty()
        fun find(set: Set<String>, exclude: Set<Int> = emptySet()): Int? =
            lower.indices.firstOrNull { it !in exclude && lower[it] in set }
        val usable = (0 until maxOf(fieldCount, lower.size)).filter { lower.getOrNull(it) !in META_HEADERS && lower.getOrNull(it) != "" }
        val term = find(TERM_NAMES) ?: usable.firstOrNull() ?: 0
        val definition = find(DEFINITION_NAMES, setOf(term))
        val example = find(EXAMPLE_NAMES, setOfNotNull(term, definition))
        val reading = find(READING_NAMES, setOfNotNull(term, definition, example))
        val taken = setOfNotNull(term, definition, example, reading)
        // With no back by name, the next usable field is the back.
        val extras = usable.filter { it !in taken }
        return Roles(term, definition, example, reading, extras)
    }

    /**
     * Whether [text] could be a card list at all: not binary (a picked image
     * decodes to NULs and replacement characters) and not JSON (a pack file
     * with a bad tag would otherwise split on its commas into junk words).
     */
    private fun isCardText(text: String): Boolean {
        val start = text.trimStart()
        if (start.isEmpty() || start[0] == '{' || start[0] == '[') return false
        var odd = 0
        for (ch in text) {
            if (ch == '\u0000') return false
            if (ch == '\uFFFD') odd++
        }
        return odd * 100 < text.length
    }

    private fun looksLikeHeader(row: List<String>): Boolean {
        if (row.size < 2) return false
        val lower = row.map { it.trim().lowercase(Locale.ROOT) }
        val hasTerm = lower.any { it in TERM_NAMES }
        val hasDefinition = lower.any { it in DEFINITION_NAMES }
        return hasTerm && hasDefinition
    }

    private fun separatorNamed(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "tab" -> "\t"
        "comma" -> ","
        "semicolon" -> ";"
        "pipe" -> "|"
        "colon" -> ":"
        "space" -> " "
        else -> value.ifEmpty { "\t" }
    }

    private fun detectSeparator(body: String, fileName: String): String? {
        val lines = body.split('\n').filter { it.isNotBlank() }.take(200)
        if (lines.isEmpty()) return null
        fun share(sep: String) = lines.count { it.contains(sep) }.toDouble() / lines.size
        val lowerName = fileName.lowercase(Locale.ROOT)
        if (lowerName.endsWith(".tsv") && share("\t") > 0) return "\t"
        if (lowerName.endsWith(".csv") && share(",") > 0) return ","
        val candidates = listOf("\t", ";", ",", " - ")
        val best = candidates.maxByOrNull { share(it) } ?: return null
        // Tab wins any tie it is part of: nobody types one into a definition.
        val pick = if (share("\t") >= share(best)) "\t" else best
        return pick.takeIf { share(it) >= 0.5 }
    }

    /**
     * Splits [body] into rows of cells. Single-character separators follow
     * RFC 4180 quoting (a quoted cell may hold the separator, `""` and line
     * breaks); " - " is split on its first occurrence per line.
     */
    internal fun splitRows(body: String, separator: String): List<List<String>> {
        if (separator.length != 1) {
            return body.split('\n').filter { it.isNotBlank() }.map { line ->
                val at = line.indexOf(separator)
                if (at < 0) listOf(line) else listOf(line.substring(0, at), line.substring(at + separator.length))
            }
        }
        val sep = separator[0]
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var atCellStart = true
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < body.length && body[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(c)
                }
            } else when {
                c == '"' && atCellStart -> {
                    quoted = true
                    atCellStart = false
                }
                c == sep -> {
                    row += cell.toString()
                    cell.setLength(0)
                    atCellStart = true
                }
                c == '\n' -> {
                    row += cell.toString()
                    cell.setLength(0)
                    rows += row
                    row = ArrayList()
                    atCellStart = true
                }
                else -> {
                    cell.append(c)
                    if (!(atCellStart && c == ' ')) atCellStart = false
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 26 KB across this file's patterns.
    private val CLOZE by lazy { Regex("""\{\{c\d+::""") }
    private val SOUND by lazy { Regex("""\[sound:[^\]]*]""") }
    private val BREAK by lazy { Regex("""<\s*/?\s*(br|div|p|li|tr|hr)\b[^>]*>""", RegexOption.IGNORE_CASE) }
    private val TAG by lazy { Regex("""</?[A-Za-z][^<>]*>""") }
    private val COMMENT by lazy { Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL) }
    private val STYLE by lazy {
        Regex("""<(style|script)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
    private val ENTITY by lazy { Regex("""&(#x[0-9A-Fa-f]+|#\d+|[A-Za-z]+);""") }
    private val FURIGANA by lazy { Regex(""" ?([^\s\[\]]+)\[([\u3040-\u30FF\u30FC]+)]""") }
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "middot" to "·", "times" to "×", "eacute" to "é",
    )

    /**
     * A field as a reader sees it: tags dropped (line-ending ones become
     * "; "), entities decoded, `[sound:…]` and Anki's furigana brackets
     * (`漢字[かんじ]` → `漢字`) removed, whitespace collapsed.
     */
    fun plainText(field: String): String {
        var text = field
        text = COMMENT.replace(text, "")
        text = STYLE.replace(text, "")
        text = SOUND.replace(text, "")
        text = BREAK.replace(text, "\n")
        text = TAG.replace(text, "")
        text = decodeEntities(text)
        text = FURIGANA.replace(text) { m -> m.groupValues[1] }
        return joinLines(text)
    }

    private fun plainTextNoHtml(field: String): String = joinLines(SOUND.replace(field, ""))

    private fun joinLines(text: String): String =
        text.split('\n')
            .map { it.replace(Regex("""[\s\u00A0]+"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ")

    private fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") ->
                name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            name.startsWith("#") ->
                name.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }
                    ?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED_ENTITIES[name.lowercase(Locale.ROOT)] ?: m.value
        }
    }
}

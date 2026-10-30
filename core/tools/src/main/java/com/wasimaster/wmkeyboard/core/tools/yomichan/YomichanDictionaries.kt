package com.wasimaster.wmkeyboard.core.tools.yomichan

import android.database.sqlite.SQLiteDatabase
import com.wasimaster.wmkeyboard.core.tools.DictDefinition
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictMeaning
import com.wasimaster.wmkeyboard.core.tools.stardict.StarDictImport
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The Yomichan / Yomitan dictionaries the user imported (#765): any language
 * pair the community has built one for, with readings, pitch accent and
 * frequency where the dictionary carries them. One source of the Dictionary
 * tool's list ([com.wasimaster.wmkeyboard.core.tools.DictionarySource.IMPORTED]),
 * which asks every dictionary that is on, in the user's order here.
 *
 * Each lives in `filesDir/yomichan/<id>/` as `dict.db` ([YomichanImport]) and
 * `info.json`. The order and the switches are `order.json` beside them. The
 * keyboard and the settings app are one process, so this is one store for
 * both; the application [attach]es it at start-up.
 */
object YomichanDictionaries {

    const val DIR_NAME = "yomichan"
    private const val ORDER = "order.json"

    /** Longest span a scan tries from a point, in characters: Japanese compounds rarely run longer. */
    const val MAX_SCAN = 20

    private const val ROW_LIMIT = 120

    /** What a dictionary is: from its `index.json`, plus how many terms the import kept. */
    @Serializable
    data class Info(
        val id: String,
        val title: String,
        val revision: String = "",
        val author: String = "",
        val attribution: String = "",
        val sourceLanguage: String = "",
        val targetLanguage: String = "",
        val terms: Int = 0,
    )

    /** An installed dictionary as the settings list shows it. */
    data class Installed(val info: Info, val enabled: Boolean, val sizeBytes: Long)

    @Serializable
    private data class OrderRow(val id: String, val on: Boolean = true)

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var root: File? = null

    private val _changes = MutableStateFlow(0)

    /** Bumps on every import, delete, switch and move, for a screen showing the list. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Open read-only handles, one per dictionary, kept for the next look-up. */
    private val databases = HashMap<String, SQLiteDatabase>()

    /** Tag notes per dictionary ("n" → "noun"), read once per open. */
    private val tagNotes = HashMap<String, Map<String, String>>()

    @Volatile
    private var cached: List<Installed>? = null

    fun attach(filesDir: File) {
        val next = File(filesDir, DIR_NAME)
        if (next == root) return
        root = next
        cached = null
        releaseDatabases()
    }

    fun encodeInfo(info: Info): String = json.encodeToString(Info.serializer(), info)

    /** Every installed dictionary, in the order a look-up asks them. */
    fun installed(): List<Installed> {
        cached?.let { return it }
        val dir = root ?: return emptyList()
        val infos = dir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            .orEmpty()
            .mapNotNull { folder ->
                if (!File(folder, YomichanImport.DATABASE).isFile) return@mapNotNull null
                runCatching { json.decodeFromString(Info.serializer(), File(folder, YomichanImport.INFO).readText()) }
                    .getOrNull()
                    ?.let { it to folder.walkBottomUp().filter(File::isFile).sumOf(File::length) }
            }
        val order = readOrder()
        val position = order.withIndex().associate { (index, row) -> row.id to index }
        val enabled = order.associate { it.id to it.on }
        val found = infos
            .sortedWith(compareBy({ position[it.first.id] ?: Int.MAX_VALUE }, { it.first.title.lowercase() }))
            .map { (info, size) -> Installed(info, enabled[info.id] ?: true, size) }
        cached = found
        return found
    }

    /** Whether any dictionary is installed and on: whether the source has anything to ask. */
    val hasEnabled: Boolean get() = installed().any { it.enabled }

    fun isDictionary(zip: ZipFile): Boolean = YomichanImport.isDictionary(zip)

    /** The same test on a zip's entry names, for a zip read as a stream. */
    fun looksLikeDictionary(entryNames: Collection<String>): Boolean {
        val leaves = entryNames.map { it.substringAfterLast('/') }
        return YomichanFormat.INDEX in leaves && leaves.any { YomichanFormat.TERM_BANK.matches(it) }
    }

    /**
     * Installs [zipFile], replacing one with the same title. A new one joins
     * the end of the list, switched on. Blocking: minutes for a large
     * dictionary, so call it off the main thread.
     */
    fun import(filesDir: File, zipFile: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Info {
        attach(filesDir)
        val base = root!!
        val info = YomichanImport.import(base, zipFile, onProgress, beforeReplace = ::close)
        val order = readOrder()
        if (order.none { it.id == info.id }) writeOrder(order + OrderRow(info.id))
        changed()
        return info
    }

    /** Installs the StarDict books in [archive], as [import] does a Yomichan zip. */
    fun importStarDict(filesDir: File, archive: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): List<Info> {
        attach(filesDir)
        val infos = StarDictImport.import(root!!, archive, onProgress, beforeReplace = ::close)
        val order = readOrder()
        val added = infos.filter { info -> order.none { it.id == info.id } }.map { OrderRow(it.id) }
        if (added.isNotEmpty()) writeOrder(order + added)
        changed()
        return infos
    }

    @Synchronized
    fun delete(id: String) {
        val base = root ?: return
        if (id.isEmpty() || id.startsWith(".") || '/' in id) return
        close(id)
        File(base, id).deleteRecursively()
        writeOrder(readOrder().filterNot { it.id == id })
        changed()
    }

    fun setEnabled(id: String, on: Boolean) {
        val rows = currentOrder()
        writeOrder(rows.map { if (it.id == id) it.copy(on = on) else it })
        changed()
    }

    /** Puts the dictionaries in [ids]' order; one [ids] leaves out keeps its place at the end. */
    fun reorder(ids: List<String>) {
        val rows = currentOrder()
        val byId = rows.associateBy { it.id }
        val moved = ids.mapNotNull { byId[it] }
        writeOrder(moved + rows.filter { it.id !in ids })
        changed()
    }

    /** The stored order, with every installed dictionary in it. */
    private fun currentOrder(): List<OrderRow> = installed().map { OrderRow(it.info.id, it.enabled) }

    private fun readOrder(): List<OrderRow> {
        val file = File(root ?: return emptyList(), ORDER)
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(OrderRow.serializer()), file.readText()) }.getOrDefault(emptyList())
    }

    private fun writeOrder(rows: List<OrderRow>) {
        val base = root ?: return
        base.mkdirs()
        val part = File(base, "$ORDER.part")
        part.writeText(json.encodeToString(ListSerializer(OrderRow.serializer()), rows))
        part.renameTo(File(base, ORDER))
    }

    private fun changed() {
        cached = null
        _changes.value++
    }

    private fun close(id: String) {
        synchronized(databases) {
            databases.remove(id)?.close()
            tagNotes.remove(id)
        }
    }

    /**
     * Closes every open handle. Each is a SQLite connection with a page cache;
     * worth keeping while the dictionary is in use, not for the rest of the
     * process's life. The next look-up reopens what it needs.
     */
    fun releaseDatabases() {
        synchronized(databases) {
            databases.values.forEach { it.close() }
            databases.clear()
            tagNotes.clear()
        }
    }

    private fun database(id: String): SQLiteDatabase? {
        val base = root ?: return null
        synchronized(databases) {
            databases[id]?.let { return it }
            val file = File(File(base, id), YomichanImport.DATABASE)
            if (!file.isFile) return null
            return runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }
                .getOrNull()
                ?.also { databases[id] = it }
        }
    }

    private fun tags(id: String, db: SQLiteDatabase): Map<String, String> {
        synchronized(databases) { tagNotes[id]?.let { return it } }
        val notes = HashMap<String, String>()
        runCatching {
            db.rawQuery("SELECT name, notes FROM tags", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val note = cursor.getString(1).orEmpty()
                    if (note.isNotBlank()) notes[cursor.getString(0)] = note
                }
            }
        }
        synchronized(databases) { tagNotes[id] = notes }
        return notes
    }

    /**
     * [word]'s entries in every dictionary that is on, in the user's order.
     * An inflected Japanese word finds its dictionary form
     * ([JapaneseDeinflector]); a Latin-script word is asked in any case.
     */
    fun lookup(word: String): List<DictEntry> {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return emptyList()
        val candidates = JapaneseDeinflector.candidates(trimmed)
        val out = ArrayList<DictEntry>()
        for (dictionary in installed()) {
            if (!dictionary.enabled) continue
            out += lookupIn(dictionary.info, candidates)
        }
        return out
    }

    /**
     * Yomichan's scan: the longest stretch of [text] from [start] that some
     * dictionary knows, inflected or not, and its entries. Null when not even
     * one character from [start] is known.
     */
    fun longestMatch(text: String, start: Int, maxLength: Int = MAX_SCAN): Match? {
        if (start !in text.indices) return null
        if (!hasEnabled) return null
        var end = start
        val limit = minOf(text.length, start + maxLength)
        // Stop at the first space or punctuation: no headword runs across one.
        while (end < limit && !text[end].isWhitespace() && !isBreak(text[end])) end++
        for (stop in end downTo start + 1) {
            val entries = lookup(text.substring(start, stop))
            if (entries.isNotEmpty()) return Match(start, stop, entries)
        }
        return null
    }

    /** What [longestMatch] found: [text]'s span [start] until [end], and the entries for it. */
    data class Match(val start: Int, val end: Int, val entries: List<DictEntry>)

    private fun isBreak(char: Char): Boolean = when (Character.getType(char).toByte()) {
        Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.OTHER_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.DASH_PUNCTUATION, Character.CONNECTOR_PUNCTUATION,
        -> true
        else -> false
    }

    private class Row(
        val term: String,
        val reading: String,
        val defTags: String,
        val rules: String,
        val score: Int,
        val glossary: String,
        val steps: Int,
    )

    private fun lookupIn(info: Info, candidates: List<JapaneseDeinflector.Candidate>): List<DictEntry> {
        val db = database(info.id) ?: return emptyList()
        val folds = candidates.map { YomichanImport.fold(it.text) }.distinct()
        val texts = candidates.map { it.text }.distinct()
        val sql = "SELECT term, reading, def_tags, rules, score, glossary FROM terms WHERE fold IN (" +
            folds.joinToString(",") { "?" } + ") OR reading IN (" + texts.joinToString(",") { "?" } +
            ") LIMIT $ROW_LIMIT"
        val rows = ArrayList<Row>()
        runCatching {
            db.rawQuery(sql, (folds + texts).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val term = cursor.getString(0)
                    val reading = cursor.getString(1).orEmpty()
                    val rules = cursor.getString(3).orEmpty()
                    val fold = YomichanImport.fold(term)
                    // The fewest steps of any candidate this row really is.
                    val steps = candidates
                        .filter { (YomichanImport.fold(it.text) == fold || it.text == reading) && JapaneseDeinflector.matchesRules(it, rules) }
                        .minOfOrNull { it.steps } ?: continue
                    rows += Row(term, reading, cursor.getString(2).orEmpty(), rules, cursor.getInt(4), cursor.getString(5).orEmpty(), steps)
                }
            }
        }
        if (rows.isEmpty()) return emptyList()
        val notes = tags(info.id, db)
        val grouped = rows
            .sortedWith(compareBy<Row> { it.steps }.thenByDescending { it.score })
            .groupBy { it.term to it.reading }
        val meta = meta(db, grouped.keys.map { it.first }.distinct())
        return grouped.map { (key, group) ->
            val (term, reading) = key
            val termMeta = meta[term].orEmpty()
            DictEntry(
                word = term,
                phonetic = termMeta.filter { it.first == "ipa" }
                    .flatMap { YomichanFormat.ipa(it.second, reading) }
                    .firstOrNull()?.let { if (it.startsWith("/") || it.startsWith("[")) it else "/$it/" }
                    .orEmpty(),
                audioUrl = null,
                meanings = group.map { row ->
                    DictMeaning(
                        partOfSpeech = row.defTags.split(' ').filter { it.isNotBlank() }
                            .joinToString(", ") { notes[it] ?: it },
                        definitions = row.glossary.lines().filter { it.isNotBlank() }
                            .map { DictDefinition(it, null, emptyList()) },
                        synonyms = emptyList(),
                        antonyms = emptyList(),
                    )
                },
                reading = reading.takeIf { it != term }.orEmpty(),
                pitch = termMeta.filter { it.first == "pitch" }
                    .flatMap { YomichanFormat.pitch(it.second, reading) }
                    .distinct(),
                frequency = termMeta.filter { it.first == "freq" }
                    .firstNotNullOfOrNull { YomichanFormat.frequency(it.second, reading) }
                    .orEmpty(),
                origin = info.title,
            )
        }
    }

    private fun meta(db: SQLiteDatabase, terms: List<String>): Map<String, List<Pair<String, kotlinx.serialization.json.JsonElement>>> {
        if (terms.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Pair<String, kotlinx.serialization.json.JsonElement>>>()
        runCatching {
            db.rawQuery(
                "SELECT term, mode, data FROM meta WHERE term IN (" + terms.joinToString(",") { "?" } + ")",
                terms.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val data = runCatching { json.parseToJsonElement(cursor.getString(2)) }.getOrNull() ?: continue
                    out.getOrPut(cursor.getString(0)) { ArrayList() } += cursor.getString(1) to data
                }
            }
        }
        return out
    }
}

/**
 * Pitch accent as one line of kana (#765): a ꜜ after the mora the pitch falls
 * from, and the number in brackets, so たべる with a downstep on 2 reads
 * たべꜜる [2], and a flat word ends in [0].
 */
object PitchNotation {

    private const val SMALL = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"

    /** [reading] split into morae: a small kana belongs to the one before it. */
    fun morae(reading: String): List<String> {
        val out = ArrayList<String>()
        for (char in reading) {
            if (char in SMALL && out.isNotEmpty()) out[out.lastIndex] = out.last() + char else out += char.toString()
        }
        return out
    }

    fun format(reading: String, downstep: Int): String {
        val morae = morae(reading)
        if (downstep <= 0 || downstep > morae.size) return "$reading [$downstep]"
        return morae.take(downstep).joinToString("") + "ꜜ" + morae.drop(downstep).joinToString("") + " [$downstep]"
    }
}

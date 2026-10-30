package com.wasimaster.wmkeyboard.core.tools.yomichan

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.StatFs
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence

/**
 * Turns a Yomichan / Yomitan dictionary zip into the SQLite file the look-up
 * reads (#765).
 *
 * A dictionary like Jitendex is hundreds of megabytes of JSON, so no bank is
 * ever read whole: each is streamed row by row ([decodeToSequence]) into
 * prepared inserts, a few thousand to a transaction. The database is built
 * beside the installed ones and only renamed into place once it is complete,
 * so a failed or cancelled import never replaces a working dictionary.
 */
object YomichanImport {

    const val DATABASE = "dict.db"
    const val INFO = "info.json"

    private const val BATCH = 5_000
    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    /** Past this, inflated, the zip is not a dictionary but a mistake (or a zip bomb). */
    private const val MAX_INFLATED_BYTES = 4L * 1024 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class NotADictionaryException : IOException("not a Yomichan dictionary")
    class NoSpaceException : IOException("not enough space for the dictionary")

    /** Every entry by its last name segment, whatever folder the zip keeps it in. */
    private fun entriesByName(zip: ZipFile): Map<String, ZipEntry> =
        zip.entries().asSequence()
            .filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") }
            .associateBy { it.name.substringAfterLast('/') }

    fun isDictionary(zip: ZipFile): Boolean {
        val names = entriesByName(zip).keys
        return YomichanFormat.INDEX in names && names.any { YomichanFormat.TERM_BANK.matches(it) }
    }

    /**
     * Installs [zipFile] under [root], replacing a dictionary of the same
     * title. [onProgress] gets bytes of bank JSON read so far and the total.
     * [beforeReplace] runs just before the old copy goes, so the store can
     * close its handle on it.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun import(
        root: File,
        zipFile: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        beforeReplace: (id: String) -> Unit = {},
    ): YomichanDictionaries.Info {
        root.mkdirs()
        ZipFile(zipFile).use { zip ->
            val entries = entriesByName(zip)
            val indexEntry = entries[YomichanFormat.INDEX] ?: throw NotADictionaryException()
            val index = runCatching {
                zip.getInputStream(indexEntry).use { json.parseToJsonElement(it.readBytes().decodeToString()) as JsonObject }
            }.getOrNull()?.let(YomichanFormat::index) ?: throw NotADictionaryException()
            val termBanks = entries.filterKeys { YomichanFormat.TERM_BANK.matches(it) }.values.sortedBy { bankNumber(it) }
            if (termBanks.isEmpty()) throw NotADictionaryException()
            val metaBanks = entries.filterKeys { YomichanFormat.META_BANK.matches(it) }.values.sortedBy { bankNumber(it) }
            val tagBanks = entries.filterKeys { YomichanFormat.TAG_BANK.matches(it) }.values.sortedBy { bankNumber(it) }
            val banks = termBanks + metaBanks + tagBanks
            val total = banks.sumOf { it.size.coerceAtLeast(0) }
            if (total > MAX_INFLATED_BYTES) throw NotADictionaryException()
            // The database ends up about the size of the JSON it came from.
            if (StatFs(root.path).availableBytes < total + SPACE_MARGIN_BYTES) throw NoSpaceException()

            val id = idFor(index.title)
            val staging = File(root, ".$id.part").apply { deleteRecursively(); mkdirs() }
            try {
                var done = 0L
                val counted = { bytes: Long ->
                    done += bytes
                    onProgress(done, total)
                }
                var terms = 0
                val db = SQLiteDatabase.openOrCreateDatabase(File(staging, DATABASE), null)
                try {
                    createTables(db)
                    terms = writeTerms(db, zip, termBanks, index.format, counted)
                    writeMeta(db, zip, metaBanks, counted)
                    writeTags(db, zip, tagBanks, counted)
                    db.execSQL("CREATE INDEX terms_fold ON terms(fold)")
                    db.execSQL("CREATE INDEX terms_reading ON terms(reading)")
                    db.execSQL("CREATE INDEX meta_term ON meta(term)")
                } finally {
                    db.close()
                }
                if (terms == 0) throw NotADictionaryException()
                val info = YomichanDictionaries.Info(
                    id = id,
                    title = index.title,
                    revision = index.revision,
                    author = index.author,
                    attribution = index.attribution,
                    sourceLanguage = index.sourceLanguage,
                    targetLanguage = index.targetLanguage,
                    terms = terms,
                )
                File(staging, INFO).writeText(YomichanDictionaries.encodeInfo(info))
                beforeReplace(id)
                val target = File(root, id)
                target.deleteRecursively()
                if (!staging.renameTo(target)) throw IOException("could not move the dictionary into place")
                return info
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun bankNumber(entry: ZipEntry): Int =
        entry.name.substringAfterLast('_').substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE

    internal fun createTables(db: SQLiteDatabase) {
        // A staging file: nothing to roll back to, so no journal and no fsyncs.
        db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA synchronous=OFF")
        db.execSQL(
            "CREATE TABLE terms (term TEXT NOT NULL, fold TEXT NOT NULL, reading TEXT NOT NULL, " +
                "def_tags TEXT, rules TEXT, score INTEGER, glossary TEXT, seq INTEGER, term_tags TEXT)",
        )
        db.execSQL("CREATE TABLE meta (term TEXT NOT NULL, mode TEXT NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tags (name TEXT PRIMARY KEY, category TEXT, notes TEXT)")
    }

    /** Each row of one bank, streamed; the bank's stream is closed afterwards. */
    @OptIn(ExperimentalSerializationApi::class)
    private inline fun forEachRow(zip: ZipFile, entry: ZipEntry, noinline counted: (Long) -> Unit, block: (JsonElement) -> Unit) {
        CountingStream(zip.getInputStream(entry), counted).use { input ->
            for (row in json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) block(row)
        }
    }

    private inline fun batched(db: SQLiteDatabase, block: (tick: () -> Unit) -> Unit) {
        var pending = 0
        db.beginTransaction()
        try {
            block {
                if (++pending >= BATCH) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    db.beginTransaction()
                    pending = 0
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun writeTerms(db: SQLiteDatabase, zip: ZipFile, banks: List<ZipEntry>, format: Int, counted: (Long) -> Unit): Int {
        var count = 0
        val insert = db.compileStatement(
            "INSERT INTO terms (term, fold, reading, def_tags, rules, score, glossary, seq, term_tags) VALUES (?,?,?,?,?,?,?,?,?)",
        )
        insert.use {
            batched(db) { tick ->
                for (bank in banks) {
                    forEachRow(zip, bank, counted) { row ->
                        val term = YomichanFormat.term(row, format)
                        if (term != null && term.glossary.isNotEmpty()) {
                            bindTerm(insert, term)
                            insert.executeInsert()
                            count++
                            tick()
                        }
                    }
                }
            }
        }
        return count
    }

    private fun bindTerm(insert: SQLiteStatement, term: YomichanFormat.Term) {
        insert.clearBindings()
        insert.bindString(1, term.term)
        insert.bindString(2, fold(term.term))
        insert.bindString(3, term.reading)
        insert.bindString(4, term.definitionTags)
        insert.bindString(5, term.rules)
        insert.bindLong(6, term.score.toLong())
        insert.bindString(7, term.glossary.joinToString("\n"))
        insert.bindLong(8, term.sequence.toLong())
        insert.bindString(9, term.termTags)
    }

    private fun writeMeta(db: SQLiteDatabase, zip: ZipFile, banks: List<ZipEntry>, counted: (Long) -> Unit) {
        val insert = db.compileStatement("INSERT INTO meta (term, mode, data) VALUES (?,?,?)")
        insert.use {
            batched(db) { tick ->
                for (bank in banks) {
                    forEachRow(zip, bank, counted) { row ->
                        val meta = YomichanFormat.meta(row)
                        if (meta != null) {
                            insert.clearBindings()
                            insert.bindString(1, meta.term)
                            insert.bindString(2, meta.mode)
                            insert.bindString(3, meta.data.toString())
                            insert.executeInsert()
                            tick()
                        }
                    }
                }
            }
        }
    }

    private fun writeTags(db: SQLiteDatabase, zip: ZipFile, banks: List<ZipEntry>, counted: (Long) -> Unit) {
        val insert = db.compileStatement("INSERT OR REPLACE INTO tags (name, category, notes) VALUES (?,?,?)")
        insert.use {
            batched(db) { tick ->
                for (bank in banks) {
                    forEachRow(zip, bank, counted) { row ->
                        val tag = YomichanFormat.tag(row)
                        if (tag != null) {
                            insert.clearBindings()
                            insert.bindString(1, tag.name)
                            insert.bindString(2, tag.category)
                            insert.bindString(3, tag.notes)
                            insert.executeInsert()
                            tick()
                        }
                    }
                }
            }
        }
    }

    /** What a look-up compares against: Latin-script dictionaries are asked in any case. */
    fun fold(text: String): String = text.lowercase(Locale.ROOT)

    /**
     * The folder a dictionary lives in, from its title, so importing a newer
     * revision replaces the old one. A title with no Latin letters or digits
     * (大辞林) is named by its hash instead.
     */
    fun idFor(title: String): String {
        val slug = title.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
        return slug.ifEmpty { "dict-" + Integer.toHexString(title.hashCode()) }
    }

    /** Reports every byte read through it, for the import's progress bar. */
    private class CountingStream(input: InputStream, private val counted: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) counted(it.toLong()) }
    }
}

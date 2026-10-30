package com.wasimaster.wmkeyboard.core.tools.stardict

import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanImport
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Turns a StarDict book into the same SQLite file a Yomichan dictionary becomes, so it
 * joins the same list and answers the same look-ups: nothing past this file knows
 * where a dictionary came from. A `.syn` word becomes a row under the entry's own
 * headword with the word as its reading, which is the column a look-up also asks.
 *
 * A failed import never replaces a working dictionary: as with Yomichan, the book is
 * built in a staging folder and renamed into place once it is whole.
 */
object StarDictImport {

    private const val BATCH = 5_000
    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    private const val MAX_RECORD_BYTES = 8 * 1024 * 1024

    /** One book, by the file names found for it. */
    private class Book(val ifo: File, val index: File, val body: File, val synonyms: File?)

    /**
     * Installs every book [archive] holds under [root], replacing one with the same
     * title. [onProgress] gets entries written so far and the book's count. Blocking.
     */
    fun import(
        root: File,
        archive: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        beforeReplace: (id: String) -> Unit = {},
    ): List<YomichanDictionaries.Info> {
        root.mkdirs()
        val staging = File(root, ".stardict.part").apply { deleteRecursively(); mkdirs() }
        try {
            val unpacked = File(staging, "unpacked")
            val names = StarDictArchive.unpack(archive, unpacked)
            if (!StarDictArchive.holdsBook(names)) throw YomichanImport.NotADictionaryException()
            val installed = booksIn(unpacked).mapNotNull { book -> importBook(root, staging, book, onProgress, beforeReplace) }
            if (installed.isEmpty()) throw YomichanImport.NotADictionaryException()
            return installed
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun booksIn(dir: File): List<Book> {
        val files = dir.listFiles().orEmpty().associateBy { it.name.lowercase(Locale.ROOT) }
        return files.values.filter { it.name.lowercase(Locale.ROOT).endsWith(".ifo") }.mapNotNull { ifo ->
            val stem = ifo.name.lowercase(Locale.ROOT).removeSuffix(".ifo")
            fun first(vararg suffixes: String) = suffixes.firstNotNullOfOrNull { files[stem + it] }
            val index = first(".idx", ".idx.gz", ".idx.dz") ?: return@mapNotNull null
            val body = first(".dict", ".dict.dz") ?: return@mapNotNull null
            Book(ifo, index, body, first(".syn", ".syn.gz", ".syn.dz"))
        }
    }

    private fun opened(file: File): InputStream {
        val raw = file.inputStream().buffered()
        val name = file.name.lowercase(Locale.ROOT)
        return if (name.endsWith(".gz") || name.endsWith(".dz")) GZIPInputStream(raw).buffered() else raw
    }

    private fun importBook(
        root: File,
        staging: File,
        book: Book,
        onProgress: (Long, Long) -> Unit,
        beforeReplace: (String) -> Unit,
    ): YomichanDictionaries.Info? {
        val ifo = StarDictFormat.parseIfo(book.ifo.readText()) ?: return null
        // Records are read by position, which a dictzip stream can't do cheaply.
        val body = if (book.body.name.endsWith(".dz", ignoreCase = true)) {
            val plain = File(staging, book.body.name.removeSuffix(".dz").removeSuffix(".DZ"))
            // A dictzip file is an ordinary gzip file with an index in its header.
            opened(book.body).use { input -> plain.outputStream().use { input.copyTo(it) } }
            plain
        } else {
            book.body
        }
        // The database ends up about the size of the text it came from.
        if (StatFs(root.path).availableBytes < body.length() + SPACE_MARGIN_BYTES) throw YomichanImport.NoSpaceException()

        val id = YomichanImport.idFor(ifo.bookName)
        val folder = File(staging, id).apply { deleteRecursively(); mkdirs() }
        var terms = 0
        val db = SQLiteDatabase.openOrCreateDatabase(File(folder, YomichanImport.DATABASE), null)
        try {
            YomichanImport.createTables(db)
            terms = writeTerms(db, book, ifo, body, onProgress)
            db.execSQL("CREATE INDEX terms_fold ON terms(fold)")
            db.execSQL("CREATE INDEX terms_reading ON terms(reading)")
            db.execSQL("CREATE INDEX meta_term ON meta(term)")
        } finally {
            db.close()
        }
        if (terms == 0) return null
        val info = YomichanDictionaries.Info(
            id = id,
            title = ifo.bookName,
            revision = ifo.version,
            author = ifo.author,
            attribution = ifo.website.ifBlank { StarDictFormat.stripMarkup(ifo.description).lineSequence().firstOrNull().orEmpty() },
            terms = terms,
        )
        File(folder, YomichanImport.INFO).writeText(YomichanDictionaries.encodeInfo(info))
        beforeReplace(id)
        val target = File(root, id)
        target.deleteRecursively()
        if (!folder.renameTo(target)) throw IOException("could not move the dictionary into place")
        return info
    }

    private fun writeTerms(
        db: SQLiteDatabase,
        book: Book,
        ifo: StarDictFormat.Ifo,
        body: File,
        onProgress: (Long, Long) -> Unit,
    ): Int {
        val insert = db.compileStatement(
            "INSERT INTO terms (term, fold, reading, def_tags, rules, score, glossary, seq, term_tags) VALUES (?,?,?,'','',0,?,?,'')",
        )
        var written = 0
        var pending = 0
        // By position, so a `.syn` row finds its entry without keeping every definition in memory.
        val positions = ArrayList<Long>()
        fun add(term: String, reading: String, glossary: String, sequence: Int) {
            insert.clearBindings()
            insert.bindString(1, term)
            insert.bindString(2, YomichanImport.fold(term))
            insert.bindString(3, reading)
            insert.bindString(4, glossary)
            insert.bindLong(5, sequence.toLong())
            insert.executeInsert()
            written++
            if (++pending >= BATCH) {
                db.setTransactionSuccessful()
                db.endTransaction()
                db.beginTransaction()
                pending = 0
            }
        }
        val words = ArrayList<String>()
        insert.use {
            RandomAccessFile(body, "r").use { dict ->
                fun record(offset: Long, size: Int): String? {
                    if (size !in 0..MAX_RECORD_BYTES || offset < 0 || offset + size > dict.length()) return null
                    val bytes = ByteArray(size)
                    dict.seek(offset)
                    dict.readFully(bytes)
                    return StarDictFormat.definition(bytes, ifo.sameTypeSequence).takeIf { it.isNotBlank() }
                }
                db.beginTransaction()
                try {
                    opened(book.index).use { input ->
                        StarDictFormat.forEachIndexRow(input, ifo.offsetBits) { row ->
                            words += row.word
                            positions += row.offset
                            positions += row.size.toLong()
                            val glossary = record(row.offset, row.size)
                            if (glossary != null && row.word.isNotBlank()) add(row.word, "", glossary, words.lastIndex)
                            if (words.size % PROGRESS_EVERY == 0) onProgress(words.size.toLong(), ifo.wordCount.toLong())
                        }
                    }
                    book.synonyms?.let { syn ->
                        opened(syn).use { input ->
                            StarDictFormat.forEachSynonym(input) { word, entry ->
                                val headword = words.getOrNull(entry)
                                val glossary = headword?.let { record(positions[entry * 2], positions[entry * 2 + 1].toInt()) }
                                if (glossary != null && word.isNotBlank()) add(headword, word, glossary, entry)
                            }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        return written
    }

    private const val PROGRESS_EVERY = 1_000
}

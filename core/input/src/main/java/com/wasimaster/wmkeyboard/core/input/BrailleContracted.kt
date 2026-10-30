package com.wasimaster.wmkeyboard.core.input

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Contracted braille, read with liblouis (LGPL-2.1-or-later): a word's cells in, its
 * print out, so ⠮ types "the". [BrailleGrade1] goes a cell at a time, but a contraction
 * means what it means only once the word around it is whole, so the keyboard hands
 * this a word at a time.
 *
 * The library ships with the app, under 100 KB. The tables are the [PACK_ID] pack,
 * about 4 MB zipped. Until it is on the phone the keyboard stays on [BrailleGrade1].
 * A table compiles in tens of milliseconds and stays in memory, a few MB, so only
 * the first use of a table should be kept off the main thread: [open] it there.
 */
object BrailleContracted {

    const val PACK_ID = "louis_tables"

    /** A table to pick from: liblouis's file name, and the language in its own words. */
    data class Table(val id: String, val label: String)

    val tables = listOf(
        Table("en-ueb-g2.ctb", "English, Unified English Braille"),
        Table("en-us-g2.ctb", "English, American (EBAE)"),
        Table("ar-ar-g2.ctb", "العربية"),
        Table("cy-cy-g2.ctb", "Cymraeg"),
        Table("de-g2.ctb", "Deutsch"),
        Table("es-g2.ctb", "Español"),
        Table("fr-bfu-g2.ctb", "Français"),
        Table("hu-hu-g2.ctb", "Magyar"),
        Table("no-no-g2.ctb", "Norsk"),
        Table("pt-pt-g2.ctb", "Português"),
        Table("sv-g2.ctb", "Svenska"),
        Table("tr-g2.ctb", "Türkçe"),
        Table("ur-pk-g2.ctb", "اردو"),
        Table("vi-vn-g2.ctb", "Tiếng Việt"),
    )

    private const val LIBRARY = "wmlouis"
    private const val DATA_DIR = "data"
    private const val TABLES_DIR = "liblouis/tables"
    private const val DONE_MARKER = ".unpacked"
    private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    private const val CELL_BASE = 0x2800
    private const val LOU_DOTS = 0x8000

    private var libraryLoaded = false

    @Volatile
    private var opened: String? = null
    private var refused: String? = null

    /** The pack is on the phone. A file check. */
    fun ready(filesDir: File): Boolean = CjkDictStore.downloadedFileFor(filesDir, PACK_ID) != null

    /** [table] is compiled, so [backTranslate] answers at once. */
    fun isOpen(table: String): Boolean = opened == table

    /** Compiles [table]; false when it can't be. Slow the first time: not on the main thread. */
    @Synchronized
    fun open(filesDir: File, table: String): Boolean {
        if (opened == table) return true
        if (refused == table || !ready(filesDir)) return false
        val ok = runCatching {
            if (!libraryLoaded) {
                System.loadLibrary(LIBRARY)
                libraryLoaded = true
            }
            BrailleNative.open(unpacked(filesDir).path, table)
        }.getOrDefault(false)
        if (ok) opened = table else refused = table
        return ok
    }

    /**
     * The print of [cells], each one a character of the Braille Patterns block.
     * Null when liblouis can't answer, which leaves the caller its own decoding.
     */
    @Synchronized
    fun backTranslate(filesDir: File, table: String, cells: String): String? {
        if (cells.isEmpty() || !open(filesDir, table)) return null
        val dots = CharArray(cells.length) { (LOU_DOTS or (cells[it].code - CELL_BASE)).toChar() }
        return runCatching { BrailleNative.backTranslate(table, dots) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** The pack is one zip because a pack is one file; liblouis wants a folder named as below it. */
    @Throws(IOException::class)
    private fun unpacked(filesDir: File): File {
        val pack = requireNotNull(CjkDictStore.downloadedFileFor(filesDir, PACK_ID)) { "No tables" }
        val root = File(pack.parentFile, DATA_DIR)
        val tables = File(root, TABLES_DIR)
        val marker = File(tables, DONE_MARKER)
        if (marker.isFile) return root
        root.deleteRecursively()
        tables.mkdirs()
        ZipInputStream(pack.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // The pack is pinned by checksum, but a path out of the folder is never right.
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.isEmpty() || name == "..") continue
                File(tables, name).outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ENTRY_BYTES) throw IOException("$name is too big")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
        marker.createNewFile()
        return root
    }
}

/** The JNI of `native/louis-jni`. Loaded by [BrailleContracted], which also serializes it. */
internal object BrailleNative {
    external fun open(dataPath: String, table: String): Boolean
    external fun backTranslate(table: String, cells: CharArray): String?
}

package com.wasimaster.wmkeyboard.core.input.composer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading a FlorisBoard language pack.
 *
 * A `.flex` is a ZIP with an `extension.json` saying what kind of extension it
 * is. The theme reader in `:core:theme` takes the ones that say
 * `ime.extension.theme`; this takes the ones that say
 * `ime.extension.languagepack`, which is how FlorisBoard ships the shape-based
 * Chinese tables — Cangjie, Quick, stroke, Wubi and the rest — as an SQLite
 * database of code to character rows.
 *
 * Those are exactly the tables this app's own Cangjie and stroke packs hold, so
 * a pack somebody already has fills a pack this app would otherwise have to
 * download. The rows are converted to the `code<TAB>character<TAB>frequency`
 * lines [CodeTableDictionary] reads and written where a download would have put
 * them, so everything downstream — the settings row, the state token that makes
 * a new pack go live, deleting it again — works on it unchanged.
 *
 * **Written from the published format, not from FlorisBoard's source.**
 * FlorisBoard is Apache-2.0 and this app is MIT; a manifest field name and a
 * table's columns are facts about a file.
 *
 * ### Only the schemes this app types
 *
 * A pack may hold a dozen tables. Cangjie (and Quick, which is a different
 * query over the same codes), stroke and Wubi 86 have composers here; Zhengma,
 * Boshiamy, Wubi 98 and the others do not, and a table imported for a keyboard
 * that cannot type it would be a download that changed nothing. Those are named
 * back to the user instead.
 */
object FlexLanguagePack {

    /** The `$` an extension.json carries when it is a language pack. */
    const val FORMAT = "ime.extension.languagepack"

    val IMPORT_MIME_TYPES = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    /** What reading one produced. */
    sealed interface Result {

        /** Rows written into [packId]'s file, ready for the next focus. */
        data class Imported(
            val packId: String,
            /** The table they came from, so the user sees which scheme landed. */
            val schema: String,
            val rows: Int,
        ) : Result

        /** A language pack, but of schemes this app has no composer for. */
        data class NoUsableTable(val schemas: List<String>) : Result

        /** A `.flex`, but a theme or a keyboard extension rather than this. */
        data object NotALanguagePack : Result

        /** Not a ZIP, truncated, past the caps, or an unreadable database. */
        data object Unreadable : Result
    }

    /**
     * Reads [input] and installs the first table this app can type.
     *
     * [cacheDir] is where the database is unpacked to: SQLite reads a file, not
     * a stream, so there is no way to do this without one. The copy is deleted
     * before this returns, on every path.
     *
     * Never throws.
     */
    fun import(input: InputStream, filesDir: File, cacheDir: File): Result {
        val scratch = File(cacheDir, SCRATCH_NAME)
        return try {
            when (unpack(input, scratch)) {
                Unpacked.READY -> install(scratch, filesDir)
                // Worth telling apart: a user who picked the wrong `.flex` off
                // a list of them needs to know it was the theme, not that the
                // file is broken.
                Unpacked.WRONG_FORMAT -> Result.NotALanguagePack
                Unpacked.FAILED -> Result.Unreadable
            }
        } catch (_: Exception) {
            Result.Unreadable
        } finally {
            scratch.delete()
        }
    }

    /**
     * The tables a pack holds, without importing any of them. For a dialog that
     * has to say what is in the file before the user agrees to it.
     */
    fun schemas(input: InputStream, cacheDir: File): List<String> {
        val scratch = File(cacheDir, SCRATCH_NAME)
        return try {
            if (unpack(input, scratch) == Unpacked.READY) tablesOf(scratch) else emptyList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            scratch.delete()
        }
    }

    // ---- the archive ----

    /** What walking the archive found. */
    private enum class Unpacked { READY, WRONG_FORMAT, FAILED }

    /**
     * Walks the archive once, writing the database to [scratch].
     *
     * **Entry names are never used as paths**, the same rule `PluginFile` and
     * the theme reader work by: a name is only ever compared, and the one file
     * that reaches the disk is written to a path this object chose. Sizes are
     * counted from bytes actually read, so an entry that lies about its size is
     * stopped by the same cap as an honest one.
     */
    private fun unpack(input: InputStream, scratch: File): Unpacked {
        var isLanguagePack = false
        var wroteDatabase = false
        scratch.parentFile?.mkdirs()
        scratch.delete()
        ZipInputStream(input.buffered()).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++entries > MAX_ENTRIES) break
                val name = entry.name.substringAfterLast('/')
                when {
                    name == MANIFEST -> {
                        val text = readCapped(zip, MAX_MANIFEST_BYTES).decodeToString()
                        val root = runCatching { json.parseToJsonElement(text) }
                            .getOrNull() as? JsonObject ?: return Unpacked.FAILED
                        if (root.string(FORMAT_FIELD) != FORMAT) return Unpacked.WRONG_FORMAT
                        isLanguagePack = true
                    }
                    !wroteDatabase && name.endsWith(DATABASE_SUFFIX) -> {
                        wroteDatabase = copyCapped(zip, scratch)
                    }
                    else -> Unit
                }
            }
        }
        return if (isLanguagePack && wroteDatabase) Unpacked.READY else Unpacked.FAILED
    }

    private fun readCapped(zip: ZipInputStream, max: Int): ByteArray {
        val out = ByteArray(max)
        var filled = 0
        val buffer = ByteArray(BUFFER_BYTES)
        while (filled < max) {
            val n = zip.read(buffer, 0, minOf(buffer.size, max - filled))
            if (n <= 0) break
            System.arraycopy(buffer, 0, out, filled, n)
            filled += n
        }
        return out.copyOf(filled)
    }

    /** False when the entry runs past the cap, which leaves no half file behind. */
    private fun copyCapped(zip: ZipInputStream, target: File): Boolean {
        var written = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        target.outputStream().use { out ->
            while (true) {
                val n = zip.read(buffer)
                if (n <= 0) break
                written += n
                if (written > MAX_DATABASE_BYTES) {
                    target.delete()
                    return false
                }
                out.write(buffer, 0, n)
            }
        }
        return written > 0
    }

    // ---- the database ----

    private fun install(database: File, filesDir: File): Result {
        val tables = tablesOf(database)
        if (tables.isEmpty()) return Result.Unreadable
        val chosen = tables.firstNotNullOfOrNull { table ->
            packFor(table)?.let { table to it }
        } ?: return Result.NoUsableTable(tables)
        val (table, target) = chosen
        val rows = writeTable(database, table, target, filesDir)
        return if (rows == 0) {
            Result.NoUsableTable(tables)
        } else {
            Result.Imported(packId = target.pack.id, schema = table, rows = rows)
        }
    }

    /** Every table in the database, in the order SQLite lists them. */
    private fun tablesOf(database: File): List<String> = openRead(database)?.use { db ->
        db.rawQuery(TABLE_LIST_QUERY, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }.orEmpty()

    /**
     * Copies one table out as the lines [CodeTableDictionary] reads, returning
     * how many survived.
     *
     * Written beside the target and renamed, so a failure part way through
     * leaves no half pack for the next focus to load.
     */
    private fun writeTable(
        database: File,
        table: String,
        target: Target,
        filesDir: File,
    ): Int {
        val pack = target.pack
        val file = CjkDictStore.packFile(filesDir, pack)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".part")
        var written = 0
        val ok = openRead(database)?.use { db ->
            // The table name cannot be bound as a parameter, so it is only ever
            // one this reader listed out of the database itself and matched
            // against a name it knows. Nothing the archive says reaches here.
            db.rawQuery("select code, text, weight from \"$table\"", null).use { cursor ->
                temp.bufferedWriter().use { out ->
                    while (cursor.moveToNext() && written < MAX_ROWS) {
                        val code = target.normalize(cursor.getString(CODE_COLUMN).orEmpty())
                        val text = cursor.getString(TEXT_COLUMN).orEmpty().trim()
                        val weight = weightOf(cursor)
                        if (code.isEmpty() || text.isEmpty() || !pack.isValidCode(code)) continue
                        out.write(code)
                        out.write("\t")
                        out.write(text)
                        out.write("\t")
                        out.write(weight.toString())
                        out.newLine()
                        written++
                    }
                }
            }
            true
        } ?: false
        if (!ok || written == 0) {
            temp.delete()
            return 0
        }
        file.delete()
        if (!temp.renameTo(file)) {
            temp.delete()
            return 0
        }
        return written
    }

    /**
     * The row's weight as the integer frequency this app ranks on.
     *
     * The column is declared `INT` by the converter that builds these packs,
     * and the pack FlorisBoard actually ships stores **fractions** in it: every
     * weight in its Cangjie table is between 0.03 and 1.0. SQLite does not
     * enforce a column's declared type, so `getInt` on those rows returns 0 —
     * which imported a real pack with every frequency flattened to nothing, and
     * `CodeTableDictionary` ranks a prefix's characters by exactly this number.
     *
     * So the stored type decides. A fraction is spread over the same 0..10000
     * range the app's own lists use; anything else is already a count.
     */
    private fun weightOf(cursor: Cursor): Int {
        val raw = runCatching {
            when (cursor.getType(WEIGHT_COLUMN)) {
                Cursor.FIELD_TYPE_FLOAT -> {
                    val value = cursor.getDouble(WEIGHT_COLUMN)
                    if (value <= 1.0) value * MAX_WEIGHT else value
                }
                else -> cursor.getInt(WEIGHT_COLUMN).toDouble()
            }
        }.getOrDefault(0.0)
        return raw.roundToInt().coerceIn(0, MAX_WEIGHT)
    }

    private fun openRead(database: File): SQLiteDatabase? = runCatching {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull()

    // ---- which table becomes which pack ----

    /** A pack of this app's, and how that scheme's codes have to be spelled. */
    private class Target(val pack: CjkDictPack, val normalize: (String) -> String)

    /**
     * The pack a table belongs in, or null for a scheme this app cannot type.
     *
     * Matched on the table's name with its punctuation dropped, because the
     * tables are named after their source files and those carry version numbers
     * and dashes: `cangjie5`, `cangjie-large`, `quick-classic`, `stroke5`.
     */
    private fun packFor(table: String): Target? {
        val name = table.lowercase().filter { it.isLetter() }
        val packId = when {
            // Quick is the same code set queried differently, so it fills the
            // same pack rather than needing one of its own.
            name.contains("cangjie") || name.contains("quick") || name.contains("sucheng") ->
                PACK_CANGJIE
            name.contains("stroke") || name.contains("bihua") -> PACK_STROKE
            isWubi86(table) -> PACK_WUBI
            else -> return null
        }
        val pack = CjkDictCatalog.byId(packId) ?: return null
        val normalize = if (packId == PACK_STROKE) ::strokeDigits else ::lowercase
        return Target(pack, normalize)
    }

    /**
     * Wubi 86 and nothing else, read off the name before its digits are dropped:
     * `wubi98` and `wubi06` are other code sets, whose rows would all pass the
     * alphabet check and then spell the wrong characters. Fcitx calls its table
     * `wbx`; a name with no version is 86, the edition everybody means by it.
     */
    private fun isWubi86(table: String): Boolean {
        val name = table.lowercase()
        if (!name.contains("wubi") && name != "wbx") return false
        val version = name.filter { it.isDigit() }
        return version.isEmpty() || version == "86"
    }

    private fun lowercase(code: String): String = code.trim().lowercase()

    /**
     * A stroke code as the digits this app's table is keyed on.
     *
     * The source tables spell the five strokes as `h s p n z` (横竖撇捺折),
     * which is what a hardware keyboard types there; here a stored code is
     * always 1 to 5, and the composer does the same translation on its way in.
     * Without this every row of an imported stroke table would be dropped for
     * having an invalid code.
     */
    private fun strokeDigits(code: String): String = buildString {
        for (c in code.trim().lowercase()) {
            val digit = StrokeDigits[c] ?: return ""
            append(digit)
        }
    }

    private val StrokeDigits: Map<Char, Char> = mapOf(
        'h' to '1', 's' to '2', 'p' to '3', 'n' to '4', 'z' to '5',
        '一' to '1', '丨' to '2', '丿' to '3', '丶' to '4', '乙' to '5',
        '1' to '1', '2' to '2', '3' to '3', '4' to '4', '5' to '5',
    )

    /** The alphabet each pack's rows have to be in, from the table's own reader. */
    private fun CjkDictPack.isValidCode(code: String): Boolean = when (id) {
        PACK_CANGJIE -> CodeTableDictionary.CANGJIE_CODE(code)
        PACK_STROKE -> CodeTableDictionary.STROKE_CODE(code)
        PACK_WUBI -> CodeTableDictionary.WUBI_CODE(code)
        else -> true
    }

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val MANIFEST = "extension.json"
    private const val FORMAT_FIELD = "$"
    private const val DATABASE_SUFFIX = ".sqlite3"
    private const val SCRATCH_NAME = "flex_languagepack.sqlite3"
    /**
     * The scheme tables, which is every table but the bookkeeping ones.
     * `android_metadata` is one Android's own SQLite adds to a database it
     * creates; naming it back to the user as a scheme they could type would be
     * nonsense.
     */
    private const val TABLE_LIST_QUERY =
        "select name from sqlite_master where type='table' " +
            "and name not like 'sqlite_%' and name != 'android_metadata'"

    private const val CODE_COLUMN = 0
    private const val TEXT_COLUMN = 1
    private const val WEIGHT_COLUMN = 2

    private const val PACK_CANGJIE = "cangjie"
    private const val PACK_STROKE = "stroke"
    private const val PACK_WUBI = "wubi"

    private const val MAX_ENTRIES = 64
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_DATABASE_BYTES = 64L * 1024 * 1024
    private const val MAX_ROWS = 500_000

    /** The top of this app's own frequency range, which a fraction maps onto. */
    private const val MAX_WEIGHT = 10_000
    private const val BUFFER_BYTES = 8 * 1024
}

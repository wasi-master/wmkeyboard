package com.wasimaster.wmkeyboard.core.input.imported

import com.wasimaster.wmkeyboard.core.layout.InputMethodKind
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What an imported method's folder says about it, beside its data. */
@Serializable
data class InputMethodMeta(
    val version: Int = 1,
    val name: String,
    /** `cin`, `rime`, `m17n` or `jquery`. */
    val format: String,
    val kind: InputMethodKind,
    /** Key caps from the file, by key. */
    val keyNames: Map<String, String> = emptyMap(),
    /** The characters the method reads. */
    val keys: String = "",
    /** Whether a code's capitals differ from its lowercase. */
    val keepCase: Boolean = false,
    /** Characters typed between syllables to mark a boundary (`'`). */
    val delimiters: String = "",
    /** A Rime schema's display rules for the keys typed. */
    val preeditFormat: List<String> = emptyList(),
    val entries: Int = 0,
    /**
     * Whether a word can begin with a digit key: Zhuyin's `1` is ㄅ and
     * ITRANS's digits are the script's own. Where digits only follow (Array's
     * `w1`), a number typed on its own stays a number.
     */
    val digitsStart: Boolean = false,
    /**
     * Whether the space bar is one of the method's keys while a word is being
     * spelled: a Rime Bopomofo schema writes the first tone with it, so `ㄅㄚ`
     * then space is bā. A second space, or one on an empty word, is a space.
     */
    val spaceKey: Boolean = false,
)

/**
 * Where imported input methods live: `filesDir/input_methods/<id>/`, one
 * folder each, holding `meta.json` and the method's data in the form this app
 * reads fastest.
 *
 * - `table.tsv`: `code<TAB>text<TAB>weight` for a table, the line format
 *   [CodeTableDictionary] reads; `reading<TAB>text<TAB>weight` for a reading
 *   method, the one [ConversionDictionary] reads.
 * - `spellings.tsv`: for a reading method, `typed<TAB>syllable<TAB>kind<TAB>penalty`,
 *   the schema's spelling algebra worked out once at import.
 * - `main.mim` and `lib_<n>.mim`: an m17n method's own files, compiled when
 *   loaded. They are small, and keeping the source means a later, better
 *   engine reads the same thing the user imported.
 * - `rules.txt`: a jquery.ime method's rule file, `.js` or `.xml`, as given,
 *   for the same reason.
 *
 * A folder is written under a temporary name and renamed into place, so a
 * half-written method never loads.
 */
object InputMethodStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun root(filesDir: File): File = File(filesDir, "input_methods")

    fun dir(filesDir: File, id: String): File = File(root(filesDir), id)

    /** A fresh id: unique, and safe as a folder name. */
    fun newId(): String = "im_${System.currentTimeMillis()}"

    fun exists(filesDir: File, id: String): Boolean = File(dir(filesDir, id), META).isFile

    fun meta(filesDir: File, id: String): InputMethodMeta? = runCatching {
        json.decodeFromString(InputMethodMeta.serializer(), File(dir(filesDir, id), META).readText())
    }.getOrNull()

    fun delete(filesDir: File, id: String) {
        if (!isSafeId(id)) return
        dir(filesDir, id).deleteRecursively()
    }

    /** Every imported method's folder. */
    fun ids(filesDir: File): List<String> =
        root(filesDir).listFiles()?.filter { it.isDirectory && !it.name.endsWith(TEMP) }?.map { it.name }.orEmpty()

    /** Writes [method] as [id], returning whether it all landed. */
    fun write(filesDir: File, id: String, method: ConvertedMethod): Boolean {
        if (!isSafeId(id)) return false
        val target = dir(filesDir, id)
        val temp = File(root(filesDir), id + TEMP)
        temp.deleteRecursively()
        if (!temp.mkdirs()) return false
        return try {
            val meta = InputMethodMeta(
                name = method.name,
                format = method.format.name.lowercase(),
                kind = method.kind,
                keyNames = method.keyNames.mapKeys { it.key.toString() },
                keys = method.keys.sorted().joinToString(""),
                keepCase = (method.payload as? MethodPayload.Table)?.keepCase ?: true,
                delimiters = (method.payload as? MethodPayload.Reading)?.delimiters.orEmpty(),
                preeditFormat = (method.payload as? MethodPayload.Reading)?.preeditFormat.orEmpty(),
                entries = method.entries,
                digitsStart = when (val p = method.payload) {
                    is MethodPayload.Table -> p.rows.any { it.first.firstOrNull()?.isDigit() == true }
                    is MethodPayload.Reading -> p.spellings.keys.any { it.firstOrNull()?.isDigit() == true }
                    is MethodPayload.Rules -> method.keys.any { it.isDigit() }
                    is MethodPayload.Regex -> false
                },
                spaceKey = (method.payload as? MethodPayload.Reading)?.spellings?.keys?.any { ' ' in it } == true,
            )
            when (val p = method.payload) {
                is MethodPayload.Table -> writeRows(File(temp, TABLE), p.rows, p.weights)
                is MethodPayload.Reading -> {
                    writeRows(File(temp, TABLE), p.rows, p.weights)
                    File(temp, SPELLINGS).bufferedWriter().use { out ->
                        for ((typed, targets) in p.spellings) {
                            for (t in targets) {
                                out.write(typed)
                                out.write("\t")
                                out.write(t.syllable)
                                out.write("\t")
                                out.write(t.kind.name)
                                out.write("\t")
                                out.write(t.penalty.toString())
                                out.newLine()
                            }
                        }
                    }
                }
                is MethodPayload.Rules -> {
                    File(temp, MAIN_MIM).writeText(p.main)
                    p.library.forEachIndexed { i, text -> File(temp, "lib_$i.mim").writeText(text) }
                }
                is MethodPayload.Regex -> File(temp, REGEX_RULES).writeText(p.source)
            }
            // Last, so a folder with a meta file is a folder with everything else.
            File(temp, META).writeText(json.encodeToString(InputMethodMeta.serializer(), meta))
            target.deleteRecursively()
            temp.renameTo(target)
        } catch (_: java.io.IOException) {
            temp.deleteRecursively()
            false
        }
    }

    /**
     * Rows sorted by code, once, here: both readers binary-search a sorted
     * table and sort one that is not, and a keyboard loads a method far more
     * often than anyone imports one. The sort is stable, so rows sharing a code
     * keep the order the table's author gave them.
     */
    private fun writeRows(file: File, rows: List<Pair<String, String>>, weights: IntArray) {
        val order = rows.indices.sortedWith { a, b -> rows[a].first.compareTo(rows[b].first) }
        file.bufferedWriter().use { out ->
            for (i in order) {
                val row = rows[i]
                // A tab or line break inside a cell would split the row; no
                // real table has one, so such a row is simply left out.
                if (row.first.any { it == '\t' || it == '\n' } || row.second.any { it == '\t' || it == '\n' }) continue
                out.write(row.first)
                out.write("\t")
                out.write(row.second)
                out.write("\t")
                out.write(weights.getOrElse(i) { 0 }.toString())
                out.newLine()
            }
        }
    }

    // ---- reading back ----

    internal fun tableFile(filesDir: File, id: String): File = File(dir(filesDir, id), TABLE)

    internal fun spellingsFile(filesDir: File, id: String): File = File(dir(filesDir, id), SPELLINGS)

    internal fun mimFiles(filesDir: File, id: String): Pair<String, List<String>>? {
        val d = dir(filesDir, id)
        val main = File(d, MAIN_MIM).takeIf { it.isFile }?.readText() ?: return null
        val library = d.listFiles()
            ?.filter { it.name.startsWith("lib_") && it.name.endsWith(".mim") }
            ?.sortedBy { it.name }
            ?.map { it.readText() }
            .orEmpty()
        return main to library
    }

    /** A jquery.ime method's rule file, kept as the user gave it and read when loaded. */
    internal fun regexFile(filesDir: File, id: String): File? = File(dir(filesDir, id), REGEX_RULES).takeIf { it.isFile }

    /** What decides whether a loaded copy is current: each wanted folder's meta file's age. */
    internal fun stamp(filesDir: File, ids: Collection<String>): String =
        ids.sorted().joinToString(",") { id -> "$id:${File(dir(filesDir, id), META).lastModified()}" }

    /** Ids are only ever ones this app made, but a layout file is user-editable. */
    fun isSafeId(id: String): Boolean = id.isNotEmpty() && id.length <= MAX_ID && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private const val META = "meta.json"
    private const val TABLE = "table.tsv"
    private const val SPELLINGS = "spellings.tsv"
    private const val MAIN_MIM = "main.mim"
    private const val REGEX_RULES = "rules.txt"
    private const val TEMP = ".part"
    private const val MAX_ID = 64
}

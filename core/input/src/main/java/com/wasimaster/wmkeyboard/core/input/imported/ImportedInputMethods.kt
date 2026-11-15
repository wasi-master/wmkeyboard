package com.wasimaster.wmkeyboard.core.input.imported

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.CodeTableDictionary
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.layout.InputMethodBinding
import com.wasimaster.wmkeyboard.core.layout.InputMethodKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The imported input methods the keyboard has in memory, and the composer for
 * each.
 *
 * Loading is the service's job, off the main thread, for the methods its
 * enabled layouts name ([load]); a method's tables can run to a million rows
 * and nobody wants the ones they are not typing with in memory. Until a method
 * is loaded its composer still answers, with no candidates and the keys as
 * typed, which is the same thing a CJK layout does before its pack arrives.
 */
object ImportedInputMethods {

    /** One method, read and ready. */
    sealed class Loaded(val meta: InputMethodMeta) {
        val keys: Set<Char> = meta.keys.toSet()
        val keyNames: Map<Char, String> = meta.keyNames.mapNotNull { (k, v) -> k.singleOrNull()?.let { it to v } }.toMap()
        val takesDigits: Boolean = keys.any { it.isDigit() }

        class Table(meta: InputMethodMeta, val dict: CodeTableDictionary) : Loaded(meta)

        class Reading(
            meta: InputMethodMeta,
            val dict: ConversionDictionary,
            /** Typed spelling → the syllables it can stand for, best first. */
            val options: Map<String, Options>,
            val maxUnit: Int,
            /** Whether the spellings are all lowercase, so a buffer can be folded to match. */
            val caseless: Boolean,
        ) : Loaded(meta)

        class Rules(meta: InputMethodMeta, val program: MimProgram) : Loaded(meta) {
            val engine = MimEngine(program)
        }

        class Regex(meta: InputMethodMeta, val rules: RegexRules) : Loaded(meta)
    }

    /** What one typed spelling may be: [syllables] best first, abbreviations from [partialFrom] on. */
    class Options(val syllables: Array<String>, val penalties: DoubleArray, val partialFrom: Int)

    @Volatile
    private var loaded: Map<String, Loaded> = emptyMap()

    /** Each loaded method's meta-file age, to tell a re-import from the copy in memory. */
    @Volatile
    private var stamps: Map<String, Long> = emptyMap()

    private val composers = ConcurrentHashMap<String, Composer>()

    fun loaded(id: String): Loaded? = loaded[id]

    /** A jquery.ime method's rules, whether it ships ([BundledInputMethods]) or was imported. */
    fun regexRules(id: String): RegexRules? =
        if (BundledInputMethods.isBundled(id)) BundledInputMethods.rules(id) else (loaded[id] as? Loaded.Regex)?.rules

    /** The composer a layout bound to [binding] types through. Always the same object for one binding. */
    fun composer(binding: InputMethodBinding): Composer =
        composers.getOrPut("${binding.id}/${binding.kind}") {
            when (binding.kind) {
                InputMethodKind.TABLE -> TableMethodComposer(binding.id)
                InputMethodKind.READING -> ReadingMethodComposer(binding.id)
                InputMethodKind.RULES -> RulesMethodComposer(binding.id)
                InputMethodKind.REGEX -> RegexMethodComposer(binding.id)
            }
        }

    /**
     * Whether memory already holds exactly [wanted], as they are on disk. Two
     * file-age checks per method, cheap enough to ask on every field focus.
     */
    fun isCurrent(filesDir: File, wanted: Set<String>): Boolean {
        val have = stamps
        if (have.keys != wanted.filterTo(HashSet()) { InputMethodStore.exists(filesDir, it) }) return false
        return have.all { (id, stamp) -> metaAge(filesDir, id) == stamp }
    }

    /**
     * Loads [wanted] and drops everything else. Blocking: call it off the
     * main thread. A method that fails to read is left out, and its layout
     * types its keys as they are.
     */
    @Synchronized
    fun load(filesDir: File, wanted: Set<String>) {
        val next = HashMap<String, Loaded>()
        val nextStamps = HashMap<String, Long>()
        for (id in wanted) {
            if (!InputMethodStore.isSafeId(id) || !InputMethodStore.exists(filesDir, id)) continue
            val age = metaAge(filesDir, id)
            val kept = loaded[id]?.takeIf { stamps[id] == age }
            val method = kept ?: runCatching { read(filesDir, id) }.getOrNull() ?: continue
            next[id] = method
            nextStamps[id] = age
        }
        loaded = next
        stamps = nextStamps
        // The composers cache their last ranking against this.
        CjkDictionaries.invalidate()
    }

    private fun metaAge(filesDir: File, id: String): Long =
        File(InputMethodStore.dir(filesDir, id), "meta.json").lastModified()

    private fun read(filesDir: File, id: String): Loaded? {
        val meta = InputMethodStore.meta(filesDir, id) ?: return null
        return when (meta.kind) {
            InputMethodKind.TABLE -> {
                val dict = InputMethodStore.tableFile(filesDir, id).bufferedReader().useLines {
                    CodeTableDictionary.parse(it) { code -> code.isNotEmpty() && code.none(Char::isWhitespace) }
                }
                Loaded.Table(meta, dict)
            }
            InputMethodKind.READING -> {
                val dict = InputMethodStore.tableFile(filesDir, id).bufferedReader().useLines(ConversionDictionary::parse)
                val grouped = HashMap<String, MutableList<SpellingAlgebra.Target>>()
                InputMethodStore.spellingsFile(filesDir, id).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val cells = line.split('\t')
                        if (cells.size < SPELLING_CELLS) continue
                        val kind = runCatching { SpellingAlgebra.Kind.valueOf(cells[2]) }.getOrNull() ?: continue
                        val penalty = cells[3].toDoubleOrNull() ?: 0.0
                        grouped.getOrPut(cells[0]) { ArrayList() } += SpellingAlgebra.Target(cells[1], kind, penalty)
                    }
                }
                val options = HashMap<String, Options>(grouped.size)
                for ((typed, targets) in grouped) {
                    val sorted = targets.sortedWith(
                        compareBy<SpellingAlgebra.Target> { it.kind == SpellingAlgebra.Kind.ABBREVIATION }
                            .thenByDescending { it.penalty },
                    ).distinctBy { it.syllable }
                    val partial = sorted.indexOfFirst { it.kind == SpellingAlgebra.Kind.ABBREVIATION }
                    options[typed] = Options(
                        sorted.map { it.syllable }.toTypedArray(),
                        DoubleArray(sorted.size) { sorted[it].penalty },
                        if (partial < 0) sorted.size else partial,
                    )
                }
                Loaded.Reading(
                    meta,
                    dict,
                    options,
                    maxUnit = options.keys.maxOfOrNull { it.length } ?: 0,
                    caseless = options.keys.none { k -> k.any(Char::isUpperCase) },
                )
            }
            InputMethodKind.RULES -> {
                val (main, library) = InputMethodStore.mimFiles(filesDir, id) ?: return null
                Loaded.Rules(meta, MimProgram.compile(main, library))
            }
            InputMethodKind.REGEX -> {
                val text = InputMethodStore.regexFile(filesDir, id)?.readText() ?: return null
                Loaded.Regex(meta, JqueryImeFiles.parse(text) ?: return null)
            }
        }
    }

    private const val SPELLING_CELLS = 4
}

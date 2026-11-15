package com.wasimaster.wmkeyboard.core.input.imported

import com.wasimaster.wmkeyboard.core.layout.InputMethodKind
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One file the user picked, by the name the picker gave it. */
class SourceFile(val name: String, val bytes: ByteArray) {
    /** The file as text: UTF-8 when it is, else the CJK legacy encodings old `.cin` tables used. */
    val text: String by lazy { decode(bytes) }

    private companion object {
        fun decode(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            for (name in listOf("UTF-8", "Big5", "GB18030")) {
                val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                try {
                    return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
                } catch (_: CharacterCodingException) {
                    continue
                }
            }
            return String(bytes, Charsets.UTF_8)
        }
    }
}

/** Which program wrote the files. */
enum class MethodFormat { CIN, RIME, M17N, JQUERY }

/** Something about an import worth telling the user before they agree to it. */
sealed interface ImportNote {
    /** Files the method refers to (an imported table, a shared rule file) that were not picked. */
    data class MissingFiles(val names: List<String>) : ImportNote

    /** Picked files the method did not use. */
    data class UnusedFiles(val names: List<String>) : ImportNote

    /** The table was larger than this app keeps; the rest was left out. */
    data object Truncated : ImportNote

    /** Spelling rules this app could not read, which were skipped. */
    data class SkippedRules(val count: Int) : ImportNote

    /** A part of an m17n method that does not run here. */
    data class Unsupported(val feature: MimFeature) : ImportNote

    /** The schema types with the space bar (a tone key), which here always types a space. */
    data object SpaceKey : ImportNote
}

/** Why a set of files could not become an input method. */
enum class ImportRefusal {
    /** Not a `.cin`, a Rime schema or dictionary, or a `.mim`. */
    NOT_A_METHOD,

    /** The right kind of file, but it does not parse. */
    BROKEN,

    /** A schema whose dictionary was not among the files. */
    NO_DICTIONARY,

    /** Parsed, but holds nothing to type with. */
    EMPTY,
}

/** What the payload of a converted method is, written by [InputMethodStore]. */
sealed interface MethodPayload {

    /** `code → text` rows; [weights] parallel to [rows]. */
    class Table(val rows: List<Pair<String, String>>, val weights: IntArray, val keepCase: Boolean) : MethodPayload

    /**
     * Readings: rows keyed by their syllables run together (`nei5hou2`), and
     * what each typed spelling can stand for.
     */
    class Reading(
        val rows: List<Pair<String, String>>,
        val weights: IntArray,
        val spellings: Map<String, List<SpellingAlgebra.Target>>,
        val delimiters: String,
        val preeditFormat: List<String>,
    ) : MethodPayload

    /** The `.mim` itself and the ones it includes, kept as text and compiled when loaded. */
    class Rules(val main: String, val library: List<String>) : MethodPayload

    /** A jquery.ime rule file (`.js` or Indic Keyboard's `.xml`), kept as text and read when loaded. */
    class Regex(val source: String) : MethodPayload
}

/** A method read out of foreign files, not yet written anywhere. */
class ConvertedMethod(
    val format: MethodFormat,
    val kind: InputMethodKind,
    val name: String,
    /** The language the file names, when it names one. */
    val languageTag: String?,
    /** Text the method writes, for guessing its language when the file names none. */
    val sample: String,
    /** The characters the method reads. */
    val keys: Set<Char>,
    /** The caps the file gives its keys. */
    val keyNames: Map<Char, String>,
    /** Whether capitals are keys of their own. */
    val shifted: Boolean,
    /** Rows or rules, for the confirmation to count. */
    val entries: Int,
    val notes: List<ImportNote>,
    val payload: MethodPayload,
)

sealed interface Conversion {
    class Ready(val method: ConvertedMethod) : Conversion

    data class Refused(val reason: ImportRefusal, val detail: String = "") : Conversion
}

/**
 * Turns picked files into an input method, whatever program wrote them.
 *
 * Several files may be picked at once, because two of the three formats are
 * spread over several: a Rime schema names its dictionary, the dictionary
 * names the tables it imports, and the rules come from shared files such as
 * `pinyin.yaml`; an m17n method may `include` another. Which format it is
 * comes from the files themselves, so the order they arrive in does not
 * matter.
 */
object InputMethodSources {

    fun convert(picked: List<SourceFile>): Conversion {
        // The same file picked twice (from two folders, say) is one file.
        val files = picked.distinctBy { it.name }
        if (files.isEmpty()) return Conversion.Refused(ImportRefusal.NOT_A_METHOD)
        val mims = files.filter { it.name.endsWith(".mim", ignoreCase = true) || it.text.contains("(input-method") }
        if (mims.isNotEmpty()) return fromMim(mims, files - mims.toSet())
        val jquery = files.firstOrNull { JqueryImeFiles.looks(it.text) }
        if (jquery != null) return fromJquery(jquery, files - jquery)
        val yamls = files.filter { RimeFiles.isYaml(it.name) || RimeFiles.looksLikeSchema(it.name, it.text) || RimeFiles.looksLikeDict(it.name, it.text) }
        if (yamls.isNotEmpty()) return fromRime(yamls, files - yamls.toSet())
        val cins = files.filter { CinTable.looksLikeCin(it.text) }
        if (cins.isNotEmpty()) return fromCin(cins.first(), files - cins.first())
        return Conversion.Refused(ImportRefusal.NOT_A_METHOD)
    }

    // ---- .cin ----

    private fun fromCin(file: SourceFile, unused: List<SourceFile>): Conversion {
        val table = CinTable.parse(file.text) ?: return Conversion.Refused(ImportRefusal.EMPTY)
        val notes = ArrayList<ImportNote>()
        if (table.truncated) notes += ImportNote.Truncated
        if (unused.isNotEmpty()) notes += ImportNote.UnusedFiles(unused.map { it.name })
        val name = table.name.ifBlank { baseName(file.name) }
        return Conversion.Ready(
            ConvertedMethod(
                format = MethodFormat.CIN,
                kind = InputMethodKind.TABLE,
                name = name,
                languageTag = cantoneseTag(name, file.name),
                sample = sample(table.rows.asSequence().map { it.second }),
                keys = table.alphabet.filterTo(HashSet()) { it.isTypeable() },
                keyNames = table.keyNames,
                shifted = table.keepCase && table.alphabet.any { it.isUpperCase() },
                entries = table.rows.size,
                notes = notes,
                // File order is the author's ranking, and the table keeps rows
                // with one code in the order they arrive.
                payload = MethodPayload.Table(table.rows, IntArray(table.rows.size), table.keepCase),
            ),
        )
    }

    // ---- Rime ----

    private fun fromRime(files: List<SourceFile>, unused: List<SourceFile>): Conversion {
        val notes = ArrayList<ImportNote>()
        val dictFiles = files.filter { RimeFiles.looksLikeDict(it.name, it.text) && !RimeFiles.looksLikeSchema(it.name, it.text) }
        val schemaFiles = files.filter { RimeFiles.looksLikeSchema(it.name, it.text) }
        val configFiles = files - dictFiles.toSet()
        val config = RimeFiles.Config(configFiles.associate { RimeFiles.refName(it.name) to RimeFiles.loadYaml(it.text) })
        // Parsed on demand: a dictionary is the large file, and one the
        // schema does not name is never read past its header.
        val dicts = HashMap<String, SourceFile>()
        for (f in dictFiles) {
            dicts[RimeFiles.refName(f.name).removeSuffix(".dict")] = f
        }
        val schemas = schemaFiles.mapNotNull { RimeFiles.schema(config, RimeFiles.refName(it.name)) }
        val schema = schemas.firstOrNull { it.dictionary != null && it.dictionary in dicts } ?: schemas.firstOrNull()
        val usedFiles = HashSet<SourceFile>()
        schema?.let { s -> schemaFiles.firstOrNull { RimeFiles.refName(it.name) == "${s.id}.schema" }?.let(usedFiles::add) }
        val rootName = when {
            schema?.dictionary != null -> schema.dictionary
            else -> {
                val imported = HashSet<String>()
                val parsed = dicts.mapValues { (_, f) -> RimeFiles.dict(f.name, f.text) }
                parsed.values.filterNotNull().forEach { imported += it.importTables }
                parsed.keys.firstOrNull { it !in imported } ?: parsed.keys.firstOrNull()
            }
        } ?: return Conversion.Refused(ImportRefusal.NO_DICTIONARY, schema?.dictionary.orEmpty())
        val rootFile = dicts[rootName]
            ?: return Conversion.Refused(ImportRefusal.NO_DICTIONARY, "$rootName.dict.yaml")

        // The dictionary and everything it imports, depth first, each once.
        val rows = ArrayList<RimeFiles.Row>()
        val missing = LinkedHashSet<String>()
        var truncated = false
        var settings: RimeFiles.Dict? = null
        val seen = HashSet<String>()
        fun load(name: String, depth: Int) {
            if (!seen.add(name) || depth > MAX_IMPORT_DEPTH) return
            val file = dicts[name] ?: run {
                missing += "$name.dict.yaml"
                return
            }
            usedFiles += file
            val dict = RimeFiles.dict(file.name, file.text) ?: return
            if (depth == 0) settings = dict
            if (dict.truncated) truncated = true
            for (row in dict.rows) {
                if (rows.size >= RimeFiles.MAX_ROWS) {
                    truncated = true
                    break
                }
                rows += row
            }
            for (imported in dict.importTables) load(imported, depth + 1)
        }
        load(rootName, 0)
        if (rows.isEmpty()) return Conversion.Refused(ImportRefusal.EMPTY)
        // Shared rule files count as used once the schema exists to read them.
        if (schema != null) usedFiles += configFiles.filter { !RimeFiles.looksLikeSchema(it.name, it.text) }

        // The vocabulary: where the counts come from when the dictionary
        // states none, and, for a reading dictionary, most of its phrases.
        val vocabularyName = settings?.vocabulary
        var vocabulary: Map<String, Long> = emptyMap()
        if (vocabularyName != null) {
            val file = (files + unused).firstOrNull { it.name.equals("$vocabularyName.txt", ignoreCase = true) }
            if (file == null) {
                missing += "$vocabularyName.txt"
            } else {
                usedFiles += file
                vocabulary = RimeFiles.vocabulary(file.text)
            }
        }
        val reading = schema?.scriptTranslator ?: rows.any { ' ' in it.code }
        if (reading && vocabulary.isNotEmpty()) {
            if (!addPhrases(rows, vocabulary, settings)) truncated = true
        }

        missing += config.missing
        if (missing.isNotEmpty()) notes += ImportNote.MissingFiles(missing.toList())
        if (truncated) notes += ImportNote.Truncated
        val leftOver = (files + unused) - usedFiles
        if (leftOver.isNotEmpty()) notes += ImportNote.UnusedFiles(leftOver.map { it.name })

        val problems = ArrayList<SpellingAlgebra.Problem>()
        val algebra = SpellingAlgebra.of(schema?.algebra.orEmpty(), problems)
        if (problems.isNotEmpty()) notes += ImportNote.SkippedRules(problems.size)
        if (schema != null && ' ' in schema.alphabet) notes += ImportNote.SpaceKey

        val weights = weightsOf(rows, vocabulary)
        val name = schema?.name ?: rootName
        val keyNames = SpellingAlgebra.keyNames(schema?.preeditFormat.orEmpty(), schema?.commentFormat.orEmpty())
        val alphabet: Set<Char> = schema?.alphabet.orEmpty().filter { it.isTypeable() }.toHashSet()
        val tag = cantoneseTag(name, rootName)
        val sampleText = sample(rows.asSequence().map { it.text })
        return if (reading) {
            val syllables = LinkedHashSet<String>()
            val out = ArrayList<Pair<String, String>>(rows.size)
            for (row in rows) {
                val parts = row.code.split(' ').filter { it.isNotEmpty() }
                syllables += parts
                out += parts.joinToString("") to row.text
            }
            val spellings = algebra.project(syllables)
            val keys = HashSet<Char>(alphabet)
            for (spelling in spellings.keys) for (c in spelling) if (c.isTypeable()) keys += c
            val delimiters = schema?.delimiters.orEmpty().filter { it > ' ' }
            keys += delimiters.toSet()
            Conversion.Ready(
                ConvertedMethod(
                    format = MethodFormat.RIME,
                    kind = InputMethodKind.READING,
                    name = name,
                    languageTag = tag,
                    sample = sampleText,
                    keys = keys,
                    keyNames = keyNames,
                    shifted = spellings.keys.any { s -> s.any { it.isUpperCase() } },
                    entries = out.size,
                    notes = notes,
                    payload = MethodPayload.Reading(out, weights, spellings, delimiters, schema?.preeditFormat.orEmpty()),
                ),
            )
        } else {
            val out = ArrayList<Pair<String, String>>(rows.size)
            val outWeights = ArrayList<Int>(rows.size)
            for ((i, row) in rows.withIndex()) {
                val code = row.code.replace(" ", "")
                for (s in algebra.spell(code)) {
                    if (s.kind == SpellingAlgebra.Kind.ABBREVIATION || s.text.isEmpty()) continue
                    out += s.text to row.text
                    outWeights += weights[i]
                }
            }
            if (out.isEmpty()) return Conversion.Refused(ImportRefusal.EMPTY)
            val keys = HashSet<Char>(alphabet)
            for ((code, _) in out) for (c in code) if (c.isTypeable()) keys += c
            Conversion.Ready(
                ConvertedMethod(
                    format = MethodFormat.RIME,
                    kind = InputMethodKind.TABLE,
                    name = name,
                    languageTag = tag,
                    sample = sampleText,
                    keys = keys,
                    keyNames = keyNames,
                    shifted = keys.any { it.isUpperCase() },
                    entries = out.size,
                    notes = notes,
                    payload = MethodPayload.Table(out, outWeights.toIntArray(), keepCase = true),
                ),
            )
        }
    }

    /**
     * Each row's weight as a count: the one it states, else the vocabulary's
     * count for its text. A percentage is a share of the text's count among
     * its readings, taken of the vocabulary's count, else of the largest count
     * another of its rows states, else of [PERCENT_BASE].
     */
    private fun weightsOf(rows: List<RimeFiles.Row>, vocabulary: Map<String, Long>): IntArray {
        val best = HashMap<String, Long>()
        for (row in rows) {
            val w = row.weight ?: continue
            if (w > (best[row.text] ?: -1L)) best[row.text] = w
        }
        return IntArray(rows.size) { i ->
            val row = rows[i]
            val w = when {
                row.weight != null -> row.weight
                row.percent != null ->
                    ((vocabulary[row.text] ?: best[row.text] ?: PERCENT_BASE) * row.percent / PERCENT).toLong()
                else -> vocabulary[row.text] ?: 0L
            }
            w.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /**
     * The vocabulary's phrases that the dictionary does not list, spelled from
     * the readings of their characters, as Rime builds a reading dictionary.
     * A character's reading that holds under [MIN_READING_SHARE] of its uses
     * is not used to spell phrases (了 is `le` in a phrase, not `liao`), and a
     * phrase spells at most [MAX_SPELLINGS_PER_PHRASE] ways. False when the
     * row limit cut the list short.
     */
    private fun addPhrases(
        rows: MutableList<RimeFiles.Row>,
        vocabulary: Map<String, Long>,
        settings: RimeFiles.Dict?,
    ): Boolean {
        val known = HashSet<String>(rows.size * 2)
        val readings = HashMap<String, MutableList<Pair<String, Double>>>()
        for (row in rows) {
            known += row.text
            if (' ' in row.code || row.text.codePointCount(0, row.text.length) != 1) continue
            val share = row.percent?.div(PERCENT) ?: 1.0
            if (share < MIN_READING_SHARE) continue
            readings.getOrPut(row.text) { ArrayList() } += row.code to share
        }
        val maxLength = settings?.maxPhraseLength ?: 0
        val minWeight = settings?.minPhraseWeight ?: 0.0
        val options = ArrayList<List<Pair<String, Double>>>()
        val picked = arrayOfNulls<String>(MAX_PHRASE_CHARS)
        for ((phrase, weight) in vocabulary) {
            if (phrase in known) continue
            if (minWeight > 0 && weight < minWeight) continue
            options.clear()
            var i = 0
            var usable = true
            while (i < phrase.length) {
                val end = i + Character.charCount(phrase.codePointAt(i))
                val list = readings[phrase.substring(i, end)]
                if (list == null || options.size >= MAX_PHRASE_CHARS) {
                    usable = false
                    break
                }
                options += list
                i = end
            }
            if (!usable || options.size < 2) continue
            if (maxLength > 0 && options.size > maxLength) continue
            // Every way to spell it, up to the cap: an odometer over the
            // characters' readings rather than a recursion per phrase.
            val at = IntArray(options.size)
            var spelled = 0
            while (spelled < MAX_SPELLINGS_PER_PHRASE) {
                var share = 1.0
                for (k in options.indices) {
                    val (code, s) = options[k][at[k]]
                    picked[k] = code
                    share *= s
                }
                if (rows.size >= RimeFiles.MAX_ROWS) return false
                val code = StringBuilder()
                for (k in options.indices) {
                    if (k > 0) code.append(' ')
                    code.append(picked[k])
                }
                rows += RimeFiles.Row(phrase, code.toString(), (weight * share).toLong(), null)
                spelled++
                var k = options.size - 1
                while (k >= 0) {
                    at[k]++
                    if (at[k] < options[k].size) break
                    at[k] = 0
                    k--
                }
                if (k < 0) break
            }
        }
        return true
    }

    // ---- m17n ----

    private fun fromMim(files: List<SourceFile>, unused: List<SourceFile>): Conversion {
        // The method is the file the others do not include: the one with
        // states of its own, else the first.
        val main = files.firstOrNull { it.text.contains("(state") } ?: files.first()
        val library = (files - main).map { it.text }
        val program = try {
            MimProgram.compile(main.text, library)
        } catch (e: MimReader.SyntaxError) {
            return Conversion.Refused(ImportRefusal.BROKEN, e.message.orEmpty())
        } catch (_: IllegalArgumentException) {
            return Conversion.Refused(ImportRefusal.NOT_A_METHOD)
        }
        if (!program.isUsable) return Conversion.Refused(ImportRefusal.EMPTY)
        val notes = ArrayList<ImportNote>()
        if (program.missingIncludes.isNotEmpty()) {
            notes += ImportNote.MissingFiles(program.missingIncludes.map { "${it.replace(' ', '-')}.mim" })
        }
        for (feature in program.unsupported) notes += ImportNote.Unsupported(feature)
        if (unused.isNotEmpty()) notes += ImportNote.UnusedFiles(unused.map { it.name })
        val engine = MimEngine(program)
        val sample = buildString {
            for (c in program.keys.sorted()) {
                append(engine.finish(c.toString()))
                if (length > SAMPLE_CHARS) break
            }
        }
        val name = if (program.language == "t" || program.language == "nil") program.name else "${program.language}-${program.name}"
        return Conversion.Ready(
            ConvertedMethod(
                format = MethodFormat.M17N,
                kind = InputMethodKind.RULES,
                name = name,
                languageTag = program.language,
                sample = sample,
                keys = program.keys,
                keyNames = emptyMap(),
                shifted = program.keys.any { it.isUpperCase() || it in SHIFTED_SIGNS },
                entries = program.entries,
                notes = notes,
                payload = MethodPayload.Rules(main.text, library),
            ),
        )
    }

    // ---- jquery.ime ----

    private fun fromJquery(file: SourceFile, unused: List<SourceFile>): Conversion {
        val rules = JqueryImeFiles.parse(file.text) ?: return Conversion.Refused(ImportRefusal.EMPTY)
        val notes = ArrayList<ImportNote>()
        if (rules.skipped > 0) notes += ImportNote.SkippedRules(rules.skipped)
        if (unused.isNotEmpty()) notes += ImportNote.UnusedFiles(unused.map { it.name })
        val letters = ('a'..'z') + ('A'..'Z')
        val sample = buildString {
            for (c in letters) {
                append(rules.run(c.toString()))
                if (length > SAMPLE_CHARS) break
            }
        }
        // Capitals are keys of their own whenever one types something its
        // lowercase does not: Nepali's T is ट where t is त.
        val shifted = ('A'..'Z').any { rules.run(it.toString()) != rules.run(it.lowercase()) }
        // The language code the method's id opens with ("ne" of
        // ne-transliteration), which is how every jquery.ime id is formed.
        val tag = rules.id.substringBefore('-').takeIf { it.isNotBlank() && it.length <= 3 }
        return Conversion.Ready(
            ConvertedMethod(
                format = MethodFormat.JQUERY,
                kind = InputMethodKind.REGEX,
                name = rules.name,
                languageTag = tag,
                sample = sample,
                keys = letters.toSet() + rules.keys,
                keyNames = emptyMap(),
                shifted = shifted,
                entries = rules.rules.size,
                notes = notes,
                payload = MethodPayload.Regex(file.text),
            ),
        )
    }

    // ---- shared ----

    private fun sample(texts: Sequence<String>): String {
        val out = StringBuilder()
        for (t in texts) {
            out.append(t)
            if (out.length >= SAMPLE_CHARS) break
        }
        return out.toString()
    }

    /** Cantonese tables say so in their name; everything else Han is Chinese. */
    private fun cantoneseTag(vararg names: String): String? =
        "yue".takeIf { names.any { n -> CantoneseNames.any { n.contains(it, ignoreCase = true) } } }

    /**
     * A key a US keyboard has. These formats all assume one, and a character
     * outside it in a code is stray data (a Han character in a romanisation
     * column) that no key could type.
     */
    private fun Char.isTypeable(): Boolean = this in '!'..'~'

    private fun baseName(file: String): String = file.substringAfterLast('/').substringBefore('.')

    private val CantoneseNames = listOf("jyut", "canton", "粵", "粤", "廣東", "广东", "yale")

    private const val SHIFTED_SIGNS = "!@#$%^&*()_+{}|:\"<>?~"
    private const val MAX_IMPORT_DEPTH = 16
    private const val SAMPLE_CHARS = 2000
    private const val PERCENT = 100.0
    private const val PERCENT_BASE = 1000L
    private const val MIN_READING_SHARE = 0.05
    private const val MAX_SPELLINGS_PER_PHRASE = 4

    /** Longer "phrases" in a vocabulary are sentences; Rime's own default stops far sooner. */
    private const val MAX_PHRASE_CHARS = 16
}

package com.wasimaster.wmkeyboard.core.tools.offlinedict

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.tools.DictDefinition
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictMeaning
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * One offline dictionary the data repository publishes, under
 * `dictionary/<langId>/<id>.wmdict.gz`. Counts and sizes are display and
 * progress hints, not checksums: the file's own header is what an install
 * checks.
 */
data class OfflineDictionaryPack(
    val id: String,
    val name: String,
    val langId: String,
    val headwords: Int,
    /** Compressed size: the progress denominator. */
    val approxGzBytes: Long,
    val license: String,
) {
    /** The name it is published under, which is also how an import recognises it. */
    val fileName: String get() = "$id.wmdict.gz"

    val url: String get() = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("dictionary/$langId/$fileName")
}

/** The packs `scripts/build_dictionary_packs.py` builds. */
object OfflineDictionaryCatalog {

    val WIKTIONARY = OfflineDictionaryPack(
        "wiktionary", "Wiktionary (offline)", "en", 64_778, 5_140_272L, "CC BY-SA 4.0, GFDL",
    )

    val OEWN = OfflineDictionaryPack(
        "oewn", "Open English WordNet", "en", 78_928, 5_027_668L, "CC BY 4.0",
    )

    /**
     * Bengali words with their English meanings (Telegram group): English
     * Wiktionary's Bengali entries, inflected forms pointing at their entry.
     */
    val BENGALI_WIKTIONARY = OfflineDictionaryPack(
        "bn_wiktionary", "Bengali–English Wiktionary (offline)", "bn", 7_481, 494_548L, "CC BY-SA 4.0, GFDL",
    )

    /**
     * Other languages' words with their English meanings (#1085), one download
     * each: English Wiktionary's entries for the language. The source that
     * reads them asks whichever are on the device.
     */
    val LANGUAGES: List<OfflineDictionaryPack> = listOf(
        language("sq", "Albanian", 9_562, 411_287L),
        language("ar", "Arabic", 16_933, 890_656L),
        language("eu", "Basque", 3_925, 123_283L),
        language("bg", "Bulgarian", 16_443, 1_004_214L),
        language("ca", "Catalan", 25_542, 1_340_396L),
        language("cs", "Czech", 33_085, 1_154_466L),
        language("da", "Danish", 20_410, 842_591L),
        language("nl", "Dutch", 48_830, 2_246_816L),
        language("et", "Estonian", 6_120, 290_317L),
        language("fi", "Finnish", 153_557, 5_413_419L),
        language("fr", "French", 62_998, 2_803_478L),
        language("gl", "Galician", 16_589, 1_222_542L),
        language("de", "German", 75_065, 4_012_187L),
        language("el", "Greek", 25_056, 1_487_536L),
        language("he", "Hebrew", 9_147, 530_656L),
        language("hu", "Hungarian", 31_726, 1_604_207L),
        language("is", "Icelandic", 15_217, 663_405L),
        language("it", "Italian", 125_224, 4_417_343L),
        language("lv", "Latvian", 14_504, 933_965L),
        language("lt", "Lithuanian", 6_512, 327_173L),
        language("fa", "Persian", 10_359, 571_236L),
        language("pl", "Polish", 61_905, 3_104_337L),
        language("pt", "Portuguese", 53_074, 3_153_170L),
        language("ro", "Romanian", 65_340, 1_556_540L),
        language("ru", "Russian", 46_823, 2_233_215L),
        language("sk", "Slovak", 7_420, 311_940L),
        language("sl", "Slovene", 3_897, 131_752L),
        language("es", "Spanish", 82_312, 4_402_899L),
        language("sv", "Swedish", 50_557, 2_656_296L),
        language("tr", "Turkish", 16_940, 788_915L),
        language("uk", "Ukrainian", 24_649, 1_089_187L),
    )

    private fun language(langId: String, name: String, headwords: Int, gzBytes: Long) = OfflineDictionaryPack(
        "${langId}_wiktionary", "$name–English Wiktionary (offline)", langId, headwords, gzBytes, "CC BY-SA 4.0, GFDL",
    )

    val entries: List<OfflineDictionaryPack> = listOf(WIKTIONARY, OEWN, BENGALI_WIKTIONARY) + LANGUAGES

    fun byId(id: String): OfflineDictionaryPack? = entries.firstOrNull { it.id == id }
}

/**
 * The offline dictionaries on the device (#665): what lets the Dictionary
 * tool define an ordinary word with no network, where the vocabulary packs
 * know only their study lists.
 *
 * Each pack lives inflated in `filesDir/dictionaries/<id>.wmdict`, and a
 * look-up binary-searches it in place ([WmDictFile]). The keyboard and the
 * settings app share one process, so the application [attach]es the folder
 * once at start-up, as the GIF packs do; a look-up before that, or before
 * the device is unlocked, finds nothing.
 */
object OfflineDictionaries {

    const val DIR_NAME = "dictionaries"
    private const val EXTENSION = ".wmdict"
    private const val PART_SUFFIX = ".part"

    /** Far above the largest pack (about 15 MB inflated); a file past it is not one of ours. */
    private const val MAX_INFLATED_BYTES = 96L * 1024 * 1024

    @Volatile
    private var dir: File? = null

    fun attach(filesDir: File) {
        dir = File(filesDir, DIR_NAME)
    }

    fun file(filesDir: File, id: String): File = File(File(filesDir, DIR_NAME), id + EXTENSION)

    fun isInstalled(filesDir: File, id: String): Boolean = file(filesDir, id).isFile

    /** Whether [name] is a published pack's name, gzipped or not. */
    fun recognises(name: String): Boolean = name.endsWith("$EXTENSION.gz") || name.endsWith(EXTENSION)

    fun delete(filesDir: File, id: String) {
        file(filesDir, id).delete()
    }

    /**
     * [packId]'s entries for [word], empty when the pack is not installed or
     * does not know the word. An inflection the pack lists ("mice") and one
     * the English rules undo ("houses", "running") both find their lemma.
     */
    fun lookup(packId: String, word: String): List<DictEntry> {
        val folder = dir ?: return emptyList()
        val file = File(folder, packId + EXTENSION)
        if (!file.isFile) return emptyList()
        val dict = runCatching { WmDictFile.open(file) }.getOrNull() ?: return emptyList()
        val langId = OfflineDictionaryCatalog.byId(packId)?.langId.orEmpty()
        val english = langId == "en"
        // Whose word it is goes on the entry (#1085): the card names it, and
        // the speaker reads it in that language rather than in English.
        return dict.use { lookup(it, word, englishRules = english) }.map { it.copy(language = langId) }
    }

    /**
     * [word] in every language pack on the device (#1085). The same spelling
     * can be a word in several ("hand"), and an entry does not say whose it is,
     * so when more than one answers each part of speech names its language.
     */
    fun lookupLanguages(word: String): List<DictEntry> {
        val answers = OfflineDictionaryCatalog.LANGUAGES.mapNotNull { pack ->
            lookup(pack.id, word).takeIf { it.isNotEmpty() }?.let { pack to it }
        }
        if (answers.size <= 1) return answers.firstOrNull()?.second.orEmpty()
        return answers.flatMap { (pack, entries) ->
            val language = Locale.forLanguageTag(pack.langId).displayLanguage
            entries.map { entry ->
                entry.copy(meanings = entry.meanings.map { it.copy(partOfSpeech = "$language: ${it.partOfSpeech}") })
            }
        }
    }

    /** The look-up itself, on an open file: for [lookup] and the tests. */
    internal fun lookup(dict: WmDictFile, word: String, englishRules: Boolean = true): List<DictEntry> {
        val key = word.trim().replace('’', '\'').lowercase(Locale.ROOT)
        if (key.isEmpty()) return emptyList()
        val lines = dict.find(key)
        lines.entries().takeIf { it.isNotEmpty() }?.let { return it.map(::toDictEntry) }
        val targets = lines.filterIsInstance<WmDictFile.Redirect>().flatMap { it.targets }.distinct()
        for (target in targets) {
            val found = dict.find(target.lowercase(Locale.ROOT)).entries()
            if (found.isNotEmpty()) return found.map(::toDictEntry)
        }
        if (!englishRules) return emptyList()
        for (candidate in EnglishLemmas.candidates(key)) {
            val found = dict.find(candidate).entries()
            if (found.isNotEmpty()) return found.map(::toDictEntry)
        }
        return emptyList()
    }

    private fun List<WmDictFile.Line>.entries(): List<WmDictFile.Entry> = filterIsInstance<WmDictFile.Entry>()

    /** A line as the panel's entry: its senses grouped by part of speech, in the file's order. */
    internal fun toDictEntry(entry: WmDictFile.Entry): DictEntry {
        val byPos = LinkedHashMap<String, MutableList<WmDictFile.Sense>>()
        for (sense in entry.senses) byPos.getOrPut(sense.pos) { ArrayList() } += sense
        return DictEntry(
            word = entry.word,
            phonetic = entry.ipa.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("/") || it.startsWith("[")) it else "/$it/" }.orEmpty(),
            audioUrl = null,
            meanings = byPos.map { (pos, senses) ->
                DictMeaning(
                    partOfSpeech = pos,
                    definitions = senses.map {
                        DictDefinition(it.definition, it.example.takeIf { e -> e.isNotEmpty() }, it.synonyms)
                    },
                    synonyms = emptyList(),
                    antonyms = senses.flatMap { it.antonyms }.distinct(),
                )
            },
        )
    }

    /** What an install put in place. */
    data class Installed(val pack: OfflineDictionaryPack, val sizeBytes: Long)

    class NotAPackException : IOException("not an offline dictionary")

    /**
     * Installs [source], a published pack gzipped or already inflated: the
     * import route, for a file fetched on another device.
     */
    fun install(filesDir: File, source: File): Installed =
        source.inputStream().use { install(filesDir, it, expectedId = null) }

    /**
     * Inflates [input] (gzip or plain, told apart by its first bytes) beside
     * its final place, checks the header names a pack this build knows (and
     * [expectedId], for a download), and only then moves it into place, so a
     * broken file never replaces a working one.
     */
    fun install(filesDir: File, input: InputStream, expectedId: String?, onBytes: (Long) -> Unit = {}): Installed {
        val folder = File(filesDir, DIR_NAME).apply { mkdirs() }
        // Its own name per install: a download and an import may run at once.
        val part = File.createTempFile("incoming", EXTENSION + PART_SUFFIX, folder)
        try {
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(2)
            val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val stream = if (gzipped) GZIPInputStream(buffered, 64 * 1024) else buffered
            part.outputStream().use { out ->
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_INFLATED_BYTES) throw NotAPackException()
                    out.write(chunk, 0, read)
                    onBytes(total)
                }
            }
            val id = WmDictFile.open(part)?.use { it.id } ?: throw NotAPackException()
            val pack = OfflineDictionaryCatalog.byId(id) ?: throw NotAPackException()
            if (expectedId != null && id != expectedId) throw NotAPackException()
            val target = file(filesDir, id)
            target.delete()
            if (!part.renameTo(target)) throw IOException("could not move $target into place")
            return Installed(pack, target.length())
        } finally {
            part.delete()
        }
    }
}

/**
 * The lemmas an English word form could be, for a pack that lists only the
 * irregular forms: WordNet's detachment rules ("houses" → house, "cried" →
 * cry), plus the doubled consonant before -ing, -ed, -er and -est
 * ("running" → run, "bigger" → big). Every candidate is only a guess the
 * pack then confirms or not.
 */
internal object EnglishLemmas {

    private val RULES = listOf(
        "'s" to "", "s'" to "s",
        "ies" to "y", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh",
        "men" to "man", "es" to "e", "es" to "", "s" to "",
        "ied" to "y", "ed" to "e", "ed" to "",
        "ing" to "e", "ing" to "",
        "iest" to "y", "ier" to "y", "est" to "e", "est" to "", "er" to "e", "er" to "",
        "ly" to "",
    )

    private val DOUBLED = listOf("ing", "ed", "er", "est")

    fun candidates(word: String): List<String> {
        val out = LinkedHashSet<String>()
        for ((suffix, replacement) in RULES) {
            if (word.length > suffix.length + 1 && word.endsWith(suffix)) {
                out += word.dropLast(suffix.length) + replacement
            }
        }
        for (suffix in DOUBLED) {
            if (!word.endsWith(suffix)) continue
            val stem = word.dropLast(suffix.length)
            if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2] && stem.last() !in "aeiouls") {
                out += stem.dropLast(1)
            }
        }
        out.remove(word)
        return out.filter { it.length >= 2 }
    }
}

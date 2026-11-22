package com.wasimaster.wmkeyboard.core.thesaurus

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.tools.R
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import java.util.Locale

/**
 * A place the keyboard can ask for a word's synonyms (#321). The user puts
 * them in order and switches them on and off; a lookup asks each one in
 * turn until one has synonyms, so a source that is down, or that knows the
 * word but lists nothing for it, falls through to the next.
 *
 * [id] is what the settings store: never store [name].
 */
enum class SynonymSource(val id: String, val online: Boolean) {
    /**
     * The vocabulary packs on the device: Wiktionary's synonyms, grouped by
     * meaning. Only knows the packs' words, but costs nothing to ask.
     */
    VOCAB_PACKS("vocab_packs", online = false),

    /** Datamuse's thesaurus (WordNet and more), ranked, by part of speech. */
    DATAMUSE("datamuse", online = true),

    /** Wiktionary's full entry through kaikki.org, grouped by meaning. */
    WIKTIONARY("wiktionary", online = true),

    /** The free dictionary API the Dictionary tool uses. */
    DICTIONARY_API("dictionary_api", online = true),

    /**
     * Datamuse's looser "means like" words, for a word no thesaurus lists
     * synonyms for. Last, because these are related words rather than
     * words that can stand in for it.
     */
    SIMILAR_WORDS("similar_words", online = true),

    /** A plugin's provider, which one being [SynonymRow.key]. Never in the stored source list. */
    PLUGIN("plugin", online = true),
    ;

    /** The source's name, for the settings list and the sheet's "From …" line. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            VOCAB_PACKS -> R.string.core_tools_synonym_source_vocab_packs
            DATAMUSE -> R.string.core_tools_synonym_source_datamuse
            WIKTIONARY -> R.string.core_tools_synonym_source_wiktionary
            DICTIONARY_API -> R.string.core_tools_synonym_source_dictionary_api
            SIMILAR_WORDS -> R.string.core_tools_synonym_source_similar_words
            PLUGIN -> R.string.core_tools_provider_plugin
        }

    /** One line on what the source knows, under its name in settings. */
    @get:StringRes
    val descriptionRes: Int
        get() = when (this) {
            VOCAB_PACKS -> R.string.core_tools_synonym_source_vocab_packs_desc
            DATAMUSE -> R.string.core_tools_synonym_source_datamuse_desc
            WIKTIONARY -> R.string.core_tools_synonym_source_wiktionary_desc
            DICTIONARY_API -> R.string.core_tools_synonym_source_dictionary_api_desc
            SIMILAR_WORDS -> R.string.core_tools_synonym_source_similar_words_desc
            PLUGIN -> R.string.core_tools_provider_plugin_desc
        }

    companion object {
        fun of(id: String): SynonymSource? = entries.firstOrNull { it.id == id }

        /** The built-in sources, the ones the stored list holds. */
        val builtIn: List<SynonymSource> get() = entries.filter { it != PLUGIN }
    }
}

/** One row of the synonym provider list: a built-in [source], or the plugin provider [key] names, called [name]. */
data class SynonymRow(val source: SynonymSource, val key: String = source.id, val name: String = "") {
    companion object {
        fun plugin(key: String, name: String) = SynonymRow(SynonymSource.PLUGIN, key, name)
    }
}

/** One row of the user's source list: the source, and whether it is asked. */
data class SynonymSourceChoice(val source: SynonymSource, val enabled: Boolean = true)

/** The source list as settings store it: ids in order, a `-` in front of one that is off. */
object SynonymSources {

    /** Every source, on, in the order that suits most people: offline first, then the richest. */
    val DEFAULT: List<SynonymSourceChoice> = SynonymSource.builtIn.map { SynonymSourceChoice(it) }

    fun encode(choices: List<SynonymSourceChoice>): String =
        choices.joinToString(",") { (if (it.enabled) "" else "-") + it.source.id }

    /**
     * An id this build does not know is dropped. A source this build knows
     * and the stored list does not (added in an update) joins at the end,
     * switched on, so an update adds a backup rather than hiding it.
     */
    fun decode(raw: String): List<SynonymSourceChoice> {
        val seen = LinkedHashMap<SynonymSource, Boolean>()
        for (token in raw.split(',')) {
            val trimmed = token.trim()
            val off = trimmed.startsWith('-')
            val source = SynonymSource.of(trimmed.removePrefix("-"))?.takeIf { it != SynonymSource.PLUGIN } ?: continue
            seen.putIfAbsent(source, !off)
        }
        return seen.map { (source, enabled) -> SynonymSourceChoice(source, enabled) } +
            SynonymSource.builtIn.filter { it !in seen }.map { SynonymSourceChoice(it) }
    }
}

/**
 * Synonyms that belong together: one part of speech and, when the source
 * says, one meaning ([sense] is that meaning's definition).
 */
data class SynonymGroup(
    val pos: String = "",
    val sense: String? = null,
    val words: List<String>,
)

/** A word's synonyms as one source gave them. */
data class SynonymSet(
    val word: String,
    val source: SynonymSource,
    val groups: List<SynonymGroup>,
    /** A plugin row's name, for the "From …" line; empty for a built-in source. */
    val name: String = "",
)

/**
 * Turns what each source returns into [SynonymGroup]s and cleans them the
 * same way: the word itself, repeats across groups, Wiktionary's pointers
 * to its thesaurus pages and anything that is not a short word or phrase go.
 */
object SynonymFold {

    const val MAX_GROUPS = 8
    const val MAX_PER_GROUP = 16
    const val MAX_TOTAL = 60
    private const val MAX_PHRASE_WORDS = 3
    private const val MAX_LENGTH = 40

    /** A vocabulary pack's or kaikki's record: one group per meaning that lists synonyms, then the rest. */
    fun fromVocabWord(record: VocabWord, word: String): List<SynonymGroup> {
        val groups = ArrayList<SynonymGroup>()
        for (sense in record.senses) {
            if (sense.synonyms.isEmpty()) continue
            groups += SynonymGroup(sense.pos, sense.definition.takeIf { it.isNotBlank() }, sense.synonyms)
        }
        if (record.synonyms.isNotEmpty()) {
            groups += SynonymGroup(record.pos.singleOrNull().orEmpty(), null, record.synonyms)
        }
        return clean(groups, word)
    }

    /** The free dictionary API: a group per definition that lists synonyms, then one per part of speech. */
    fun fromDictionary(entries: List<DictEntry>, word: String): List<SynonymGroup> {
        val groups = ArrayList<SynonymGroup>()
        for (meaning in entries.flatMap { it.meanings }) {
            val pos = meaning.partOfSpeech.trim().lowercase(Locale.ROOT)
            for (definition in meaning.definitions) {
                if (definition.synonyms.isNotEmpty()) {
                    groups += SynonymGroup(pos, definition.text.trim().takeIf { it.isNotEmpty() }, definition.synonyms)
                }
            }
            if (meaning.synonyms.isNotEmpty()) groups += SynonymGroup(pos, null, meaning.synonyms)
        }
        return clean(groups, word)
    }

    /**
     * Datamuse's "means like" list, in its own ranked order, grouped by each
     * word's commonest part of speech. [synonymsOnly] keeps the words it
     * tags as synonyms; off keeps everything it ranked.
     */
    fun fromDatamuse(results: List<DatamuseWord>, word: String, synonymsOnly: Boolean): List<SynonymGroup> {
        val byPos = LinkedHashMap<String, ArrayList<String>>()
        for (result in results) {
            if (synonymsOnly && DatamuseClient.TAG_SYNONYM !in result.tags) continue
            val pos = result.tags.firstNotNullOfOrNull { DatamuseClient.POS[it] }.orEmpty()
            byPos.getOrPut(pos) { ArrayList() } += result.word
        }
        return clean(byPos.map { (pos, words) -> SynonymGroup(pos, null, words) }, word)
    }

    /**
     * Groups with the same part of speech and no meaning of their own are
     * merged; a word already in an earlier group is not repeated.
     */
    fun clean(groups: List<SynonymGroup>, word: String): List<SynonymGroup> {
        val self = key(word)
        val seen = HashSet<String>().apply { add(self) }
        val merged = LinkedHashMap<Pair<String, String?>, LinkedHashSet<String>>()
        var total = 0
        for (group in groups) {
            val slot = group.pos.trim().lowercase(Locale.ROOT) to group.sense?.trim()?.takeIf { it.isNotEmpty() }
            for (raw in group.words) {
                if (total >= MAX_TOTAL) break
                val candidate = raw.trim().replace(SPACES, " ")
                if (!isUsable(candidate) || !seen.add(key(candidate))) continue
                val bucket = merged.getOrPut(slot) { LinkedHashSet() }
                if (bucket.size >= MAX_PER_GROUP) continue
                bucket += candidate
                total++
            }
        }
        return merged.entries
            .filter { it.value.isNotEmpty() }
            .take(MAX_GROUPS)
            .map { (slot, words) -> SynonymGroup(slot.first, slot.second, words.toList()) }
    }

    /** A word or a short phrase in letters: no "Thesaurus:happy", no "see also", no numbers. */
    internal fun isUsable(candidate: String): Boolean {
        if (candidate.isEmpty() || candidate.length > MAX_LENGTH) return false
        if (candidate.contains(':')) return false
        if (candidate.none { it.isLetter() }) return false
        if (candidate.any { !(it.isLetter() || it == ' ' || it == '-' || it == '\'' || it == '’' || it == '.') }) {
            return false
        }
        return candidate.split(' ').size <= MAX_PHRASE_WORDS
    }

    private fun key(text: String): String = text.trim().lowercase(Locale.ROOT).replace('’', '\'')

    private val SPACES = Regex("\\s+")
}

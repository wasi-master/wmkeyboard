package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.providers.PluginProviders
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaries
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryCatalog
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries
import com.wasimaster.wmkeyboard.core.vocab.KaikkiClient
import com.wasimaster.wmkeyboard.core.vocab.VocabAccent
import com.wasimaster.wmkeyboard.core.vocab.VocabIndex
import com.wasimaster.wmkeyboard.core.vocab.VocabTranslation
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import com.wasimaster.wmkeyboard.core.vocab.WiktionaryRestClient
import com.wasimaster.wmkeyboard.tools.R
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A place the Dictionary tool can ask for a word's definitions. The user
 * puts them in order and switches them on and off; a look-up asks each one
 * in turn until one has the word, so a source that is down (the free
 * dictionary API is often down for days) falls through to the next.
 *
 * [id] is what the settings store: never store [name].
 */
enum class DictionarySource(
    val id: String,
    val online: Boolean,
    /** The [OfflineDictionaryCatalog] pack this source reads, for one that is an offline dictionary. */
    val packId: String? = null,
    /** Listed but off until the user turns it on, on a new install and after an update alike. */
    val startsOff: Boolean = false,
) {
    /** The vocabulary packs on the device. Only knows the packs' words, but costs nothing to ask and works offline. */
    VOCAB_PACKS("vocab_packs", online = false),

    /** Wiktionary's full entry through kaikki.org: senses, examples, both accents' IPA and recordings. */
    WIKTIONARY("wiktionary", online = true),

    /** Wiktionary's own definition endpoint: definitions and examples, no pronunciation. */
    WIKTIONARY_REST("wiktionary_rest", online = true),

    /** The free dictionary API (dictionaryapi.dev). Last by default: it is often down. */
    DICTIONARY_API("dictionary_api", online = true),

    /**
     * Wiktionary's common words as a download (#665): what the no-internet
     * build and a phone with no signal define ordinary words with. After the
     * online sources by default, so it answers when they cannot; an update
     * adds it there too ([DictionarySources.decode]).
     */
    OFFLINE_WIKTIONARY("offline_wiktionary", online = false, packId = OfflineDictionaryCatalog.WIKTIONARY.id),

    /** Open English WordNet as a download: every single word, plainer definitions than Wiktionary's. */
    OFFLINE_WORDNET("offline_wordnet", online = false, packId = OfflineDictionaryCatalog.OEWN.id),

    /** Bengali words in English, as a download; only a Bengali word finds anything in it. */
    OFFLINE_BENGALI("offline_bn_wiktionary", online = false, packId = OfflineDictionaryCatalog.BENGALI_WIKTIONARY.id),

    /**
     * Words of other languages in English, one download per language (#1085):
     * asks whichever of [OfflineDictionaryCatalog.LANGUAGES] are on the device.
     */
    OFFLINE_LANGUAGES("offline_languages", online = false),

    /**
     * The Yomichan / Yomitan dictionaries the user imported (#765): any
     * language pair, with readings and pitch accent. Asks every one that is
     * on, in the order its own settings list keeps ([YomichanDictionaries]).
     */
    IMPORTED("imported_dictionaries", online = false),

    /** Slang, written by anyone: many definitions are crude, so nobody gets it without asking. */
    URBAN_DICTIONARY("urban_dictionary", online = true, startsOff = true),

    /**
     * A plugin's provider: which one is [DictionaryRow.key]. Never in the
     * stored source list, which only knows the built-in sources; plugin rows
     * live in the provider list alone.
     */
    PLUGIN("plugin", online = true),
    ;

    /** The source's name, for the settings list and the panel's "From …" line. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            VOCAB_PACKS -> R.string.core_tools_dictionary_source_vocab_packs
            WIKTIONARY -> R.string.core_tools_dictionary_source_wiktionary
            WIKTIONARY_REST -> R.string.core_tools_dictionary_source_wiktionary_rest
            DICTIONARY_API -> R.string.core_tools_dictionary_source_dictionary_api
            OFFLINE_WIKTIONARY -> R.string.core_tools_dictionary_source_offline_wiktionary
            OFFLINE_WORDNET -> R.string.core_tools_dictionary_source_offline_wordnet
            OFFLINE_BENGALI -> R.string.core_tools_dictionary_source_offline_bengali
            OFFLINE_LANGUAGES -> R.string.core_tools_dictionary_source_offline_languages
            IMPORTED -> R.string.core_tools_dictionary_source_imported
            URBAN_DICTIONARY -> R.string.core_tools_dictionary_source_urban_dictionary
            PLUGIN -> R.string.core_tools_provider_plugin
        }

    /** One line on what the source knows, under its name in settings. */
    @get:StringRes
    val descriptionRes: Int
        get() = when (this) {
            VOCAB_PACKS -> R.string.core_tools_dictionary_source_vocab_packs_desc
            WIKTIONARY -> R.string.core_tools_dictionary_source_wiktionary_desc
            WIKTIONARY_REST -> R.string.core_tools_dictionary_source_wiktionary_rest_desc
            DICTIONARY_API -> R.string.core_tools_dictionary_source_dictionary_api_desc
            OFFLINE_WIKTIONARY -> R.string.core_tools_dictionary_source_offline_wiktionary_desc
            OFFLINE_WORDNET -> R.string.core_tools_dictionary_source_offline_wordnet_desc
            OFFLINE_BENGALI -> R.string.core_tools_dictionary_source_offline_bengali_desc
            OFFLINE_LANGUAGES -> R.string.core_tools_dictionary_source_offline_languages_desc
            IMPORTED -> R.string.core_tools_dictionary_source_imported_desc
            URBAN_DICTIONARY -> R.string.core_tools_dictionary_source_urban_dictionary_desc
            PLUGIN -> R.string.core_tools_provider_plugin_desc
        }

    companion object {
        fun of(id: String): DictionarySource? = entries.firstOrNull { it.id == id }

        /** The built-in sources, the ones the stored list holds. */
        val builtIn: List<DictionarySource> get() = entries.filter { it != PLUGIN }
    }
}

/**
 * One row of the Dictionary tool's provider list as a look-up asks it: a
 * built-in [source], or a plugin's provider, [key] being its
 * `plugin:<id>/dictionary/<provider>` and [name] what the card's "From …"
 * line says.
 */
data class DictionaryRow(val source: DictionarySource, val key: String = source.id, val name: String = "") {
    companion object {
        fun plugin(key: String, name: String) = DictionaryRow(DictionarySource.PLUGIN, key, name)
    }
}

/** One row of the user's source list: the source, and whether it is asked. */
data class DictionarySourceChoice(val source: DictionarySource, val enabled: Boolean = true)

/** The source list as settings store it: ids in order, a `-` in front of one that is off. */
object DictionarySources {

    /**
     * Every source, on unless it [DictionarySource.startsOff]: the packs first,
     * then the richest online ones, then the least reliable, then the offline
     * dictionaries, which only answer once downloaded and are asked when the
     * network is not there.
     */
    val DEFAULT: List<DictionarySourceChoice> =
        DictionarySource.builtIn.map { DictionarySourceChoice(it, enabled = !it.startsOff) }

    fun encode(choices: List<DictionarySourceChoice>): String =
        choices.joinToString(",") { (if (it.enabled) "" else "-") + it.source.id }

    /**
     * An id this build does not know is dropped. A source this build knows
     * and the stored list does not (added in an update) joins at the end,
     * switched on unless it [DictionarySource.startsOff], so an update adds a
     * backup rather than hiding it.
     */
    fun decode(raw: String): List<DictionarySourceChoice> {
        val seen = LinkedHashMap<DictionarySource, Boolean>()
        for (token in raw.split(',')) {
            val trimmed = token.trim()
            val off = trimmed.startsWith('-')
            val source = DictionarySource.of(trimmed.removePrefix("-"))?.takeIf { it != DictionarySource.PLUGIN } ?: continue
            seen.putIfAbsent(source, !off)
        }
        return seen.map { (source, enabled) -> DictionarySourceChoice(source, enabled) } +
            DictionarySource.builtIn.filter { it !in seen }.map { DictionarySourceChoice(it, enabled = !it.startsOff) }
    }
}

/**
 * Asks the user's dictionary sources in their order until one has the word.
 *
 * A source that does not know the word and a source that cannot be asked
 * (offline, down, a page too large) both fall through to the next; only the
 * verdict at the end tells them apart, so the panel can say "no entry" or
 * "could not reach any source" rather than one message for both.
 */
object DictionaryLookup {

    sealed interface Result {
        data class Found(val entries: List<DictEntry>, val source: DictionarySource) : Result

        /** Every source that was asked answered, and none knew the word. */
        data object NotFound : Result

        /** No source could be asked (offline, or every one failed). */
        data object Failed : Result

        /**
         * Only the sources on the device answered, and none knew the word,
         * while every online one could not be reached (#665). The packs
         * hold only their own lists' words, so "no entry" would wrongly say
         * the word does not exist.
         */
        data object NotOnDevice : Result
    }

    /** One source asked for one word: its entries, empty for none; throws when it could not be asked. */
    fun interface Fetch {
        fun entries(source: DictionarySource, word: String): List<DictEntry>
    }

    suspend fun resolve(word: String, sources: List<DictionarySource>, fetch: Fetch): Result =
        resolveRows(word, sources.map { DictionaryRow(it) }, fetch)

    /**
     * [resolve] over the provider list's rows, a plugin's among them: a
     * plugin row is asked through [PluginProviders], the rest through [fetch].
     */
    suspend fun resolveRows(word: String, rows: List<DictionaryRow>, fetch: Fetch): Result {
        val trimmed = word.trim()
        if (trimmed.isEmpty() || rows.isEmpty()) return Result.NotFound
        val sources = rows.map { it.source }
        var answered = false
        var onlineAnswered = false
        var onlineFailed = false
        for (row in rows) {
            val source = row.source
            val entries = try {
                withContext(Dispatchers.IO) {
                    if (source == DictionarySource.PLUGIN) pluginEntries(row, trimmed) else fetch.entries(source, trimmed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (source.online) onlineFailed = true
                continue
            }
            answered = true
            if (source.online) onlineAnswered = true
            if (entries.isNotEmpty()) {
                return Result.Found(entries + otherLanguages(trimmed, source, entries, sources, fetch), source)
            }
        }
        return when {
            !answered -> Result.Failed
            onlineFailed && !onlineAnswered -> Result.NotOnDevice
            else -> Result.NotFound
        }
    }

    /** A plugin row's entries, each saying which plugin it is from. */
    private fun pluginEntries(row: DictionaryRow, word: String): List<DictEntry> =
        PluginReplies.dictionary(PluginProviders.call(row.key, PluginReplies.wordRequest(word)), word)
            .map { if (it.origin.isEmpty()) it.copy(origin = row.name) else it }

    /**
     * The same spelling in the other languages' packs on the device (#1085),
     * after an answer from [answered]: "motte" is an English word and a German
     * one, "Gift" a present in English and poison in German. Only when the
     * language packs are one of [sources] the user left on and did not answer
     * already, and only entries whose language is not one [found] has.
     * Nothing when they cannot be read.
     */
    private suspend fun otherLanguages(
        word: String,
        answered: DictionarySource,
        found: List<DictEntry>,
        sources: List<DictionarySource>,
        fetch: Fetch,
    ): List<DictEntry> {
        if (answered == DictionarySource.OFFLINE_LANGUAGES || DictionarySource.OFFLINE_LANGUAGES !in sources) return emptyList()
        val extra = try {
            withContext(Dispatchers.IO) { fetch.entries(DictionarySource.OFFLINE_LANGUAGES, word) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val have = found.map { it.language.ifEmpty { "en" } }.toSet()
        return extra.filter { it.language.isNotEmpty() && it.language !in have }
    }

    /**
     * The real sources. [index] is the vocabulary packs' index, or null when
     * none is loaded (the packs source then has nothing). [translationCodes]
     * are the languages Wiktionary's entry should bring translations in
     * (#764); the same page carries them, so they cost no extra request.
     */
    fun fetcher(index: () -> VocabIndex?, translationCodes: List<String> = emptyList()): Fetch = Fetch { source, word ->
        when (source) {
            DictionarySource.VOCAB_PACKS ->
                index()?.lookupAnyForm(word.lowercase(Locale.ROOT))?.let(::fromVocabWord).orEmpty()
            DictionarySource.WIKTIONARY ->
                KaikkiClient.lookup(word, translationCodes, NetSource.DICTIONARY)?.let(::fromVocabWord).orEmpty()
            DictionarySource.WIKTIONARY_REST ->
                WiktionaryRestClient.lookup(word, NetSource.DICTIONARY)?.let(::fromVocabWord).orEmpty()
            DictionarySource.DICTIONARY_API -> try {
                DictionaryClient.lookup(word)
            } catch (_: DictionaryClient.NotFoundException) {
                emptyList()
            }
            DictionarySource.OFFLINE_WIKTIONARY, DictionarySource.OFFLINE_WORDNET, DictionarySource.OFFLINE_BENGALI ->
                OfflineDictionaries.lookup(source.packId!!, word)
            DictionarySource.OFFLINE_LANGUAGES -> OfflineDictionaries.lookupLanguages(word)
            DictionarySource.IMPORTED -> YomichanDictionaries.lookup(word)
            DictionarySource.URBAN_DICTIONARY -> UrbanDictionaryClient.lookup(word)
            // Asked by resolveRows itself, which knows which plugin.
            DictionarySource.PLUGIN -> emptyList()
        }
    }

    /**
     * [word]'s translations into [codes] from Wiktionary (#764), for an entry
     * whose source brought none: one more request, to kaikki.org. Empty when
     * the page lists none of [codes] or could not be fetched. Blocking; call
     * it on an IO dispatcher.
     */
    fun translations(word: String, codes: List<String>): Map<String, VocabTranslation> {
        if (codes.isEmpty() || word.isBlank()) return emptyMap()
        return try {
            KaikkiClient.lookup(word.trim(), codes, NetSource.DICTIONARY)?.translations.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** The translations worth a row for [entry]: [codes] in their order, each with at least one gloss. */
    fun translationsFor(entry: DictEntry, codes: List<String>): List<Pair<String, VocabTranslation>> =
        codes.mapNotNull { code -> entry.translations[code]?.takeIf { it.glosses().isNotEmpty() }?.let { code to it } }

    /** A pack's or Wiktionary's record as one entry, its senses grouped by part of speech in the record's order. */
    fun fromVocabWord(record: VocabWord): List<DictEntry> {
        val byPos = LinkedHashMap<String, ArrayList<DictDefinition>>()
        for (sense in record.senses) {
            val text = sense.definition.trim()
            if (text.isEmpty()) continue
            byPos.getOrPut(sense.pos.trim().lowercase(Locale.ROOT)) { ArrayList() } += DictDefinition(
                text = text,
                example = sense.example?.trim()?.takeIf { it.isNotEmpty() },
                synonyms = sense.synonyms,
            )
        }
        if (byPos.isEmpty()) return emptyList()
        val single = byPos.size == 1
        val meanings = byPos.map { (pos, definitions) ->
            DictMeaning(
                partOfSpeech = pos,
                definitions = definitions,
                // The record's own lists are not tied to a part of speech;
                // they only belong under one when there is only one.
                synonyms = if (single) record.synonyms else emptyList(),
                antonyms = if (single) record.antonyms else emptyList(),
            )
        }
        return listOf(
            DictEntry(
                word = record.word,
                phonetic = record.ipaFor(VocabAccent.US).orEmpty(),
                audioUrl = record.audioFor(VocabAccent.US)?.takeIf { it.startsWith("http") },
                meanings = meanings,
                translations = record.translations,
            ),
        )
    }
}

package com.wasimaster.wmkeyboard.core.vocab

import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.tools.DictionaryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fills in a word the user adds to a list of their own: from an installed
 * pack when one knows the word, else from the network, else the user types
 * the details.
 *
 * Online, three places are asked in turn until one answers: kaikki.org
 * (Wiktionary's full entry, the packs' own source), then Wiktionary's own
 * definition endpoint as its backup, then the free dictionary API the
 * Dictionary tool uses. A source that is down is skipped; only a word that
 * none of them has is "not found".
 *
 * The online step is a plain flag here rather than a metered decision,
 * because the data-saver types live in `:core:settings`, which depends on
 * this module; the caller resolves the decision and passes the verdict.
 */
object VocabAutofill {

    /** One place the network can be asked: null means "no such word", a throw means "could not ask". */
    fun interface Source {
        fun lookup(lemma: String, translationCodes: List<String>): VocabWord?
    }

    /** The free dictionary API, folded through [fromDictionary]. */
    object DictionaryApiSource : Source {
        override fun lookup(lemma: String, translationCodes: List<String>): VocabWord? =
            try {
                fromDictionary(DictionaryClient.lookup(lemma), lemma)
            } catch (_: DictionaryClient.NotFoundException) {
                null
            }
    }

    /** Richest first; each is only asked when the one before had nothing or could not be reached. */
    val defaultSources: List<Source> = listOf(KaikkiClient, WiktionaryRestClient, DictionaryApiSource)

    /**
     * The source behind a row of the vocabulary provider list, by its type
     * id, or null for a type that is not one. The list's default is
     * [defaultSources], in that order.
     */
    fun sourceOf(typeId: String, key: String = typeId): Source? = when (typeId) {
        ProviderTypes.WIKTIONARY.id -> KaikkiClient
        ProviderTypes.WIKTIONARY_REST.id -> WiktionaryRestClient
        ProviderTypes.DICTIONARY_API.id -> DictionaryApiSource
        ProviderTypes.PLUGIN.id -> PluginSource(key)
        else -> null
    }

    /**
     * A plugin's provider, [key] naming it: asked with the word and the
     * languages to bring glosses in, answering with a record or dictionary
     * entries ([com.wasimaster.wmkeyboard.core.tools.PluginReplies.vocabWord]).
     */
    class PluginSource(private val key: String) : Source {
        override fun lookup(lemma: String, translationCodes: List<String>): VocabWord? {
            val request = kotlinx.serialization.json.buildJsonObject {
                put("word", kotlinx.serialization.json.JsonPrimitive(lemma))
                put("translations", kotlinx.serialization.json.JsonArray(translationCodes.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
            return com.wasimaster.wmkeyboard.core.tools.PluginReplies.vocabWord(
                com.wasimaster.wmkeyboard.core.providers.PluginProviders.call(key, request),
                lemma,
            )
        }
    }

    sealed interface Result {
        data class Found(val word: VocabWord, val fromOnline: Boolean) : Result

        /** Nothing installed knows the word and the network was not allowed. */
        data object NeedsOnline : Result
        data object NotFound : Result
        data object Failed : Result
    }

    private const val MAX_SENSES_PER_POS = 3
    private const val MAX_RELATED = 12

    fun fromIndex(index: VocabIndex, lemma: String): VocabWord? = index.lookup(lemma)

    /** The dictionary API's shape folded into a pack record; null when it had nothing usable. */
    fun fromDictionary(entries: List<DictEntry>, lemma: String): VocabWord? {
        if (entries.isEmpty()) return null
        val pos = ArrayList<String>()
        val senses = ArrayList<VocabSense>()
        val synonyms = ArrayList<String>()
        val antonyms = ArrayList<String>()
        var ipa: String? = null
        var audio: String? = null
        for (entry in entries) {
            if (ipa == null && entry.phonetic.isNotBlank()) ipa = entry.phonetic
            if (audio == null && !entry.audioUrl.isNullOrBlank()) audio = entry.audioUrl
            for (meaning in entry.meanings) {
                val partOfSpeech = meaning.partOfSpeech.trim().lowercase()
                if (partOfSpeech.isNotEmpty() && partOfSpeech !in pos) pos += partOfSpeech
                synonyms += meaning.synonyms
                antonyms += meaning.antonyms
                var kept = 0
                for (definition in meaning.definitions) {
                    if (kept >= MAX_SENSES_PER_POS || definition.text.isBlank()) continue
                    senses += VocabSense(
                        pos = partOfSpeech,
                        definition = definition.text.trim(),
                        example = definition.example?.trim()?.takeIf { it.isNotEmpty() },
                        synonyms = definition.synonyms,
                    )
                    synonyms += definition.synonyms
                    kept++
                }
            }
        }
        if (senses.isEmpty()) return null
        return VocabWord(
            word = lemma,
            pos = pos,
            ipa = ipa?.let { mapOf(VocabAccent.US.key to it) }.orEmpty(),
            audio = audio?.let { mapOf(VocabAccent.US.key to it) }.orEmpty(),
            senses = senses,
            synonyms = synonyms.map { it.trim() }.filter { it.isNotEmpty() && it != lemma }.distinct().take(MAX_RELATED),
            antonyms = antonyms.map { it.trim() }.filter { it.isNotEmpty() && it != lemma }.distinct().take(MAX_RELATED),
            sources = emptyList(),
        )
    }

    /**
     * Installed packs first, then the network when [allowOnline], then
     * [Result.NeedsOnline] so the caller can offer a data-saver override or
     * the manual form. [translationCodes] are the languages a fetched record
     * should carry glosses for.
     */
    suspend fun resolve(
        index: VocabIndex,
        lemma: String,
        allowOnline: Boolean,
        translationCodes: List<String> = emptyList(),
        sources: List<Source> = defaultSources,
    ): Result {
        val normalized = VocabPackFile.normalizeLemma(lemma) ?: return Result.NotFound
        fromIndex(index, normalized)?.let { return Result.Found(it, fromOnline = false) }
        if (!allowOnline) return Result.NeedsOnline
        var answered = false
        for (source in sources) {
            val found = try {
                withContext(Dispatchers.IO) { source.lookup(normalized, translationCodes) }
            } catch (_: DictionaryClient.NotFoundException) {
                answered = true
                continue
            } catch (_: Exception) {
                continue
            }
            if (found != null) return Result.Found(found, fromOnline = true)
            answered = true
        }
        return if (answered) Result.NotFound else Result.Failed
    }

    /** The old shape, for callers and tests that bring their own dictionary-API fetch. */
    suspend fun resolve(
        index: VocabIndex,
        lemma: String,
        allowOnline: Boolean,
        lookup: (String) -> List<DictEntry>,
    ): Result = resolve(
        index, lemma, allowOnline,
        sources = listOf(Source { word, _ -> fromDictionary(lookup(word), word) }),
    )

    /** One word per line, trimmed, de-duplicated, case-folded; for a pasted list. */
    fun parseWordList(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val cleaned = line.substringBefore('#').trim().trimEnd(',', ';')
            val lemma = VocabPackFile.normalizeLemma(cleaned) ?: continue
            if (lemma.any { it.isLetter() }) seen += lemma
        }
        return seen.toList()
    }
}

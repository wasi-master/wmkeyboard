package com.wasimaster.wmkeyboard.core.thesaurus

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.providers.PluginProviders
import com.wasimaster.wmkeyboard.core.tools.DictionaryClient
import com.wasimaster.wmkeyboard.core.tools.PluginReplies
import com.wasimaster.wmkeyboard.core.vocab.KaikkiClient
import com.wasimaster.wmkeyboard.core.vocab.VocabIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks the user's synonym sources in their order until one has synonyms
 * for a word (#321).
 *
 * A source that answers with nothing (it does not know the word, or knows
 * it and lists no synonyms) and a source that cannot be asked (offline,
 * down, a page too large) both fall through to the next; only the verdict
 * at the end tells them apart, so the keyboard can say "no synonyms" or
 * "could not reach any source" rather than one message for both.
 */
object SynonymLookup {

    sealed interface Result {
        data class Found(val set: SynonymSet) : Result

        /** Every source that was asked answered, and none had synonyms. */
        data object NotFound : Result

        /** No source could be asked (offline, or every one failed). */
        data object Failed : Result
    }

    /** One source asked for one word: its groups, empty for none; throws when it could not be asked. */
    fun interface Fetch {
        fun groups(source: SynonymSource, word: String): List<SynonymGroup>
    }

    /**
     * [sources] in order. [allowOnline] false skips the online ones, which
     * then count as not reached.
     */
    suspend fun resolve(
        word: String,
        sources: List<SynonymSource>,
        allowOnline: Boolean = true,
        fetch: Fetch,
    ): Result = resolveRows(word, sources.map { SynonymRow(it) }, allowOnline, fetch)

    /** [resolve] over the provider list's rows: a plugin row is asked through [PluginProviders]. */
    suspend fun resolveRows(
        word: String,
        rows: List<SynonymRow>,
        allowOnline: Boolean = true,
        fetch: Fetch,
    ): Result {
        val trimmed = word.trim()
        if (trimmed.isEmpty() || rows.isEmpty()) return Result.NotFound
        var answered = false
        for (row in rows) {
            val source = row.source
            if (source.online && !allowOnline) continue
            val groups = try {
                withContext(Dispatchers.IO) {
                    if (source == SynonymSource.PLUGIN) {
                        PluginReplies.synonymGroups(PluginProviders.call(row.key, PluginReplies.wordRequest(trimmed)))
                    } else {
                        fetch.groups(source, trimmed)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            answered = true
            if (groups.isNotEmpty()) return Result.Found(SynonymSet(trimmed, source, groups, row.name))
        }
        return if (answered) Result.NotFound else Result.Failed
    }

    /**
     * The real sources. [index] is the vocabulary packs' index, or null when
     * none is loaded (the packs source then has nothing).
     */
    fun fetcher(index: () -> VocabIndex?): Fetch = Fetch { source, word ->
        when (source) {
            SynonymSource.VOCAB_PACKS ->
                index()?.lookupAnyForm(word.lowercase())?.let { SynonymFold.fromVocabWord(it, word) }.orEmpty()
            SynonymSource.DATAMUSE ->
                SynonymFold.fromDatamuse(DatamuseClient.meansLike(word), word, synonymsOnly = true)
            SynonymSource.SIMILAR_WORDS ->
                SynonymFold.fromDatamuse(DatamuseClient.meansLike(word), word, synonymsOnly = false)
            SynonymSource.WIKTIONARY ->
                KaikkiClient.lookup(word, emptyList(), NetSource.SYNONYMS)
                    ?.let { SynonymFold.fromVocabWord(it, word) }.orEmpty()
            SynonymSource.DICTIONARY_API -> try {
                SynonymFold.fromDictionary(DictionaryClient.lookup(word, NetSource.SYNONYMS), word)
            } catch (_: DictionaryClient.NotFoundException) {
                emptyList()
            }
            // Asked by resolveRows itself, which knows which plugin.
            SynonymSource.PLUGIN -> emptyList()
        }
    }
}

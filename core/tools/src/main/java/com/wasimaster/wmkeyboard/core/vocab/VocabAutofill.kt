package com.wasimaster.wmkeyboard.core.vocab

import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictionaryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fills in a word the user adds to a list of their own: from an installed
 * pack when one knows the word, else from the online dictionary the
 * Dictionary tool already uses, else the user types the details.
 *
 * The online step is a plain flag here rather than a metered decision,
 * because the data-saver types live in `:core:settings`, which depends on
 * this module; the caller resolves the decision and passes the verdict.
 */
object VocabAutofill {

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
     * the manual form.
     */
    suspend fun resolve(
        index: VocabIndex,
        lemma: String,
        allowOnline: Boolean,
        lookup: (String) -> List<DictEntry> = DictionaryClient::lookup,
    ): Result {
        val normalized = VocabPackFile.normalizeLemma(lemma) ?: return Result.NotFound
        fromIndex(index, normalized)?.let { return Result.Found(it, fromOnline = false) }
        if (!allowOnline) return Result.NeedsOnline
        val entries = try {
            withContext(Dispatchers.IO) { lookup(normalized) }
        } catch (_: DictionaryClient.NotFoundException) {
            return Result.NotFound
        } catch (_: Exception) {
            return Result.Failed
        }
        return fromDictionary(entries, normalized)?.let { Result.Found(it, fromOnline = true) } ?: Result.NotFound
    }

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

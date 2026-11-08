package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.vocab.VocabTranslation
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One sense of a word: the definition, an optional usage example, synonyms. */
data class DictDefinition(
    val text: String,
    val example: String?,
    val synonyms: List<String>,
)

/** Definitions grouped under one part of speech (noun, verb, …). */
data class DictMeaning(
    val partOfSpeech: String,
    val definitions: List<DictDefinition>,
    val synonyms: List<String>,
    val antonyms: List<String>,
)

/** One dictionary entry (a word can have several, e.g. homographs). */
data class DictEntry(
    val word: String,
    /** IPA transcription like "/kwɪkˈsɒtɪk/", empty when absent. */
    val phonetic: String,
    /** URL of a pronunciation recording, or null when the API has none. */
    val audioUrl: String?,
    val meanings: List<DictMeaning>,
    /**
     * The word in other languages, keyed by Wiktionary language code (#764):
     * the codes the look-up asked for that the source had. Empty for a
     * source that carries no translations.
     */
    val translations: Map<String, VocabTranslation> = emptyMap(),
    /**
     * How the word is read when that differs from how it is written: the
     * kana of a Japanese headword, from an imported dictionary (#765). Empty
     * otherwise.
     */
    val reading: String = "",
    /** Pitch accent downstep positions for [reading] (0 = flat), from an imported dictionary. */
    val pitch: List<Int> = emptyList(),
    /** An imported dictionary's frequency figure for the word, as it shows it ("1234", "★★★"). */
    val frequency: String = "",
    /** The imported dictionary this entry is from, for the card's "From …" line; empty for the built-in sources. */
    val origin: String = "",
    /**
     * The language the headword is a word of, as a BCP 47 tag, when the source
     * says (#1085): a language pack's, an imported dictionary's own. The card
     * names it, and the speaker reads the word in it. Empty for the English
     * sources and any that do not say.
     */
    val language: String = "",
)

/**
 * Minimal client for the Free Dictionary API (dictionaryapi.dev — no key,
 * no account). One of the Dictionary tool's sources (see [DictionaryLookup]),
 * asked only when the user looks a word up.
 */
object DictionaryClient {

    /** The word exists in no entry — the API answered 404. */
    class NotFoundException(val word: String) : Exception("no entry for $word")

    /**
     * Blocking lookup; call on an IO dispatcher. Throws on any failure.
     * [netSource] names the feature in the network log: the synonym look-up
     * asks the same API (#321).
     */
    fun lookup(word: String, netSource: NetSource = NetSource.DICTIONARY): List<DictEntry> {
        val cleaned = word.trim()
        require(cleaned.isNotEmpty()) { "empty word" }
        val url = ServiceEndpoints.base(ServiceEndpoint.DICTIONARY_API) + "/api/v2/entries/en/" +
            URLEncoder.encode(cleaned, "UTF-8").replace("+", "%20")
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(netSource, "GET", url, route = "/api/v2/entries")
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            netCall.status = connection.responseCode
            if (connection.responseCode == 404) throw NotFoundException(cleaned)
            val body = netCall.countIn(connection.inputStream).bufferedReader().use { it.readText() }
            return parse(body)
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    /** Tolerant parse: every field except the word itself may be missing. */
    internal fun parse(body: String): List<DictEntry> {
        val root = Json.parseToJsonElement(body)
        if (root !is kotlinx.serialization.json.JsonArray) return emptyList()
        return root.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val word = entry.optString("word") ?: return@mapNotNull null
            val phonetics = entry.optArray("phonetics").mapNotNull { it as? JsonObject }
            DictEntry(
                word = word,
                phonetic = entry.optString("phonetic")
                    ?: phonetics.firstNotNullOfOrNull { it.optString("text") }.orEmpty(),
                // Prefer a phonetics item that carries both audio and text.
                audioUrl = (phonetics.firstOrNull { it.optString("audio") != null && it.optString("text") != null }
                    ?: phonetics.firstOrNull { it.optString("audio") != null })
                    ?.optString("audio")
                    ?.takeIf { it.startsWith("http") },
                meanings = entry.optArray("meanings").mapNotNull { meaningElement ->
                    val meaning = meaningElement as? JsonObject ?: return@mapNotNull null
                    DictMeaning(
                        partOfSpeech = meaning.optString("partOfSpeech").orEmpty(),
                        definitions = meaning.optArray("definitions").mapNotNull { defElement ->
                            val def = defElement as? JsonObject ?: return@mapNotNull null
                            val text = def.optString("definition") ?: return@mapNotNull null
                            DictDefinition(
                                text = text,
                                example = def.optString("example"),
                                synonyms = def.optStringList("synonyms"),
                            )
                        },
                        synonyms = meaning.optStringList("synonyms"),
                        antonyms = meaning.optStringList("antonyms"),
                    )
                }.filter { it.definitions.isNotEmpty() },
            )
        }.filter { it.meanings.isNotEmpty() }
    }

    private fun JsonObject.optString(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.optArray(key: String) =
        this[key]?.takeIf { it !is JsonNull }?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())

    private fun JsonObject.optStringList(key: String): List<String> =
        optArray(key).mapNotNull { item ->
            runCatching { item.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
        }
}

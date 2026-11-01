package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Urban Dictionary's public look-up (no key, no account). One of the Dictionary
 * tool's sources (see [DictionaryLookup]), off until the user turns it on: its
 * definitions are written by anyone and many are crude.
 */
object UrbanDictionaryClient {

    /** The rest are mostly the same joke again, and the service's own order puts the best first. */
    private const val MAX_DEFINITIONS = 5

    /** Blocking lookup; call on an IO dispatcher. Throws when the service could not be asked. */
    fun lookup(word: String): List<DictEntry> {
        val cleaned = word.trim()
        require(cleaned.isNotEmpty()) { "empty word" }
        val url = ServiceEndpoints.base(ServiceEndpoint.URBAN_DICTIONARY) + "/v0/define?term=" +
            URLEncoder.encode(cleaned, "UTF-8")
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DICTIONARY, "GET", url, route = "/v0/define")
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            netCall.status = connection.responseCode
            val body = netCall.countIn(connection.inputStream).bufferedReader().use { it.readText() }
            return parse(body, cleaned)
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    /**
     * One entry holding [word]'s definitions, or nothing. A word the service
     * does not know answers 200 with an empty list. Only entries for the word
     * itself count, in any case.
     */
    internal fun parse(body: String, word: String): List<DictEntry> {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val list = root["list"] as? JsonArray ?: return emptyList()
        val matching = list.mapNotNull { it as? JsonObject }
            .filter { it.optString("word")?.trim().equals(word, ignoreCase = true) }
        val definitions = matching.mapNotNull { item ->
            val text = item.optString("definition")?.let(::plain)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DictDefinition(
                text = text,
                example = item.optString("example")?.let(::plain)?.takeIf { it.isNotEmpty() },
                synonyms = emptyList(),
            )
        }.take(MAX_DEFINITIONS)
        if (definitions.isEmpty()) return emptyList()
        return listOf(
            DictEntry(
                word = matching.first().optString("word").orEmpty().trim(),
                phonetic = "",
                audioUrl = null,
                meanings = listOf(DictMeaning("slang", definitions, emptyList(), emptyList())),
            ),
        )
    }

    /** The service writes a link to another entry as `[word]` and ends its lines in CRLF. */
    private fun plain(text: String): String =
        text.replace("[", "").replace("]", "").replace("\r\n", "\n").trim()

    private fun JsonObject.optString(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
}

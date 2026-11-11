package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mwmbl (#1207), a non-profit search engine with an index of its own: no key,
 * no account. Its API takes the query and answers with one page of results,
 * each title and extract split into runs that are or are not the query's
 * words. There is no paging, no SafeSearch and no image search.
 */
object MwmblSearchClient {

    /** Blocking; call on an IO dispatcher. Throws on failure. A further [page] has nothing. */
    fun webSearch(
        query: String,
        count: Int,
        source: NetSource = NetSource.WEB_SEARCH,
        page: Int = 0,
    ): List<WebResult> {
        if (page > 0) return emptyList()
        val url = ServiceEndpoints.base(ServiceEndpoint.MWMBL) + PATH + "?s=" + ToolHttp.encode(query.trim())
        val body = ToolHttp.get(url, headers = mapOf("Accept" to "application/json"), source = source, route = PATH)
        return parseWeb(body).take(count.coerceAtLeast(1))
    }

    private const val PATH = "/api/v1/search/"

    internal fun parseWeb(body: String): List<WebResult> {
        val results = Json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element.jsonObject
            val url = item["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WebResult(
                title = runs(item["title"]).ifBlank { url },
                snippet = runs(item["extract"]).trim().removeSuffix("…").trim(),
                url = url,
                displayUrl = url.substringAfter("://").substringBefore('/'),
            )
        }
    }

    /** A title or extract's runs, joined: `[{"value": "…", "is_bold": …}, …]`. */
    private fun runs(element: kotlinx.serialization.json.JsonElement?): String =
        (element as? JsonArray)?.joinToString("") { run ->
            runCatching { run.jsonObject["value"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
        }.orEmpty()
}

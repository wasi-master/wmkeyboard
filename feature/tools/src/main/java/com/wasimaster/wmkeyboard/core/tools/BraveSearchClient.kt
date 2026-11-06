package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Brave Search API client — the whole-web alternative for the web and
 * image search tools now that Google Programmable Search no longer offers
 * "search the entire web" to new engines (since 2026-01-20; existing
 * whole-web engines run until 2027-01-01). Key goes in the
 * X-Subscription-Token header; Brave's plan gives a monthly free credit
 * but requires attribution, which the panels show.
 */
object BraveSearchClient {

    /**
     * Blocking; call on an IO dispatcher. Throws on failure.
     *
     * [source] is what the network log files the request under. It is the
     * search tool unless the AI tool ran the search itself (#470).
     *
     * [page] counts pages of [count] from 0, for More results (#879). Brave
     * gives ten: past [MAX_WEB_PAGE] this returns nothing rather than ask.
     */
    fun webSearch(
        query: String,
        apiKey: String,
        count: Int,
        safe: Boolean,
        source: NetSource = NetSource.WEB_SEARCH,
        page: Int = 0,
    ): List<WebResult> {
        if (page > MAX_WEB_PAGE) return emptyList()
        val url = ServiceEndpoints.base(ServiceEndpoint.BRAVE_SEARCH) + "/res/v1/web/search" +
            "?q=${ToolHttp.encode(query.trim())}" +
            "&count=${count.coerceIn(1, 20)}" +
            (if (page > 0) "&offset=$page" else "") +
            "&safesearch=${if (safe) "moderate" else "off"}"
        return parseWeb(counted { ToolHttp.getWithHeaders(url, headers = headers(apiKey), wantHeaders = ServiceUsage.BRAVE_HEADERS, source = source, route = "/res/v1/web/search") })
    }

    /**
     * Blocking; call on an IO dispatcher. Throws on failure.
     *
     * The image API has no offset, so a further [page] asks for that many
     * more pictures in one list, up to Brave's 200, and keeps only the new tail.
     */
    fun imageSearch(query: String, apiKey: String, count: Int, safe: Boolean, page: Int = 0): List<ImageResult> {
        val each = count.coerceIn(1, 20)
        val skip = each * page
        if (skip >= MAX_IMAGES) return emptyList()
        // Image search only knows strict/off — no "moderate".
        val url = ServiceEndpoints.base(ServiceEndpoint.BRAVE_SEARCH) + "/res/v1/images/search" +
            "?q=${ToolHttp.encode(query.trim())}" +
            "&count=${(skip + each).coerceAtMost(MAX_IMAGES)}" +
            "&safesearch=${if (safe) "strict" else "off"}"
        return parseImages(
            counted {
                ToolHttp.getWithHeaders(
                    url, headers = headers(apiKey), wantHeaders = ServiceUsage.BRAVE_HEADERS,
                    source = NetSource.IMAGE_SEARCH, route = "/res/v1/images/search",
                )
            },
        ).drop(skip)
    }

    /** The [ServiceUsage] key Brave's searches count under. */
    const val USAGE_KEY = "brave"

    /**
     * Runs one request and files what it cost (#1135): one more search, and
     * the quota Brave reports in its headers, on a refusal as well, since a
     * 429 is when the number matters most.
     */
    private inline fun counted(request: () -> HttpResponse): String {
        val now = System.currentTimeMillis()
        val response = try {
            request()
        } catch (e: ToolHttpException) {
            ServiceUsage.braveHeaders(e.headers, now)?.let { ServiceUsage.report(USAGE_KEY, it) }
            throw e
        }
        ServiceUsage.record(USAGE_KEY, nowMs = now)
        ServiceUsage.braveHeaders(response.headers, now)?.let { ServiceUsage.report(USAGE_KEY, it) }
        return response.body
    }

    /** Brave's last web page: `offset` takes 0 to 9. */
    private const val MAX_WEB_PAGE = 9

    /** The most pictures Brave lists for one image search. */
    private const val MAX_IMAGES = 200

    private fun headers(apiKey: String) = mapOf(
        "X-Subscription-Token" to apiKey,
        "Accept" to "application/json",
    )

    internal fun parseWeb(body: String): List<WebResult> {
        val results = Json.parseToJsonElement(body).jsonObject["web"]?.jsonObject
            ?.get("results")?.jsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element.jsonObject
            val url = item["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            WebResult(
                title = item["title"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                snippet = item["description"]?.jsonPrimitive?.content.orEmpty()
                    // Brave descriptions carry highlight markup.
                    .replace("<strong>", "").replace("</strong>", ""),
                url = url,
                displayUrl = item["meta_url"]?.jsonObject?.get("netloc")?.jsonPrimitive?.content
                    ?: url.substringAfter("://").substringBefore('/'),
            )
        }
    }

    internal fun parseImages(body: String): List<ImageResult> {
        val results = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
            ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element.jsonObject
            val full = item["properties"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: return@mapNotNull null
            ImageResult(
                title = item["title"]?.jsonPrimitive?.content.orEmpty(),
                thumbUrl = item["thumbnail"]?.jsonObject?.get("src")?.jsonPrimitive?.content ?: full,
                imageUrl = full,
                mime = mimeFromUrl(full),
                contextUrl = item["url"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    /** Brave doesn't report MIME; go by the file extension. */
    internal fun mimeFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }
}

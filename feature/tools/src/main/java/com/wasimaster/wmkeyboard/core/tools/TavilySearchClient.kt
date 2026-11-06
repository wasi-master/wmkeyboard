package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tavily Search API client, the third backend for the web and image search
 * tools (#439), beside [BraveSearchClient] and [SearxClient].
 *
 * Tavily is built for AI agents rather than people, but its `/search` answers
 * with the same title, address and snippet a results list needs. The free plan
 * gives 1,000 credits a month, and a `basic` search costs one. There is no
 * built-in key: nothing here runs until the user pastes their own.
 *
 * There is no separate image endpoint. `include_images` adds the pictures
 * Tavily found on the result pages, as bare addresses, so image search gets
 * fewer results than Brave gives and no page to link each one back to.
 */
object TavilySearchClient {

    private const val PATH = "/search"

    /**
     * Blocking; call on an IO dispatcher. Throws on failure.
     *
     * [depth] is Tavily's `search_depth`: `advanced` costs two credits, the
     * others one (#470, #879). With [answer], the pictures Tavily found come
     * too, for the strip under the answer (#879): they cost nothing more.
     * [answer] asks for the short answer Tavily writes from the results, which
     * costs nothing more. [source] is what the network log files the request
     * under: the search tool, unless the AI tool ran the search itself (#470).
     */
    fun webSearch(
        query: String,
        apiKey: String,
        count: Int,
        safe: Boolean,
        depth: String = DEFAULT_DEPTH,
        answer: Boolean = false,
        source: NetSource = NetSource.WEB_SEARCH,
        excludeDomains: List<String> = emptyList(),
    ): WebSearchPage {
        val body = post(
            requestBody(query, count, safe, images = answer, depth = depth, answer = answer, excludeDomains = excludeDomains),
            apiKey,
            source,
            depth,
        )
        return WebSearchPage(
            parseWeb(body),
            if (answer) parseAnswer(body) else null,
            if (answer) parseImages(body).take(ANSWER_IMAGES) else emptyList(),
        )
    }

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun imageSearch(
        query: String,
        apiKey: String,
        count: Int,
        safe: Boolean,
        depth: String = DEFAULT_DEPTH,
        excludeDomains: List<String> = emptyList(),
    ): List<ImageResult> =
        parseImages(
            post(
                requestBody(query, count, safe, images = true, depth = depth, excludeDomains = excludeDomains),
                apiKey,
                NetSource.IMAGE_SEARCH,
                depth,
            ),
        )
            .take(count.coerceIn(1, MAX_RESULTS))

    /** The [ServiceUsage] key Tavily's searches count under. */
    const val USAGE_KEY = "tavily"

    /** What one search at [depth] costs: two credits at `advanced`, one otherwise. */
    internal fun credits(depth: String): Long = if (depth == "advanced") 2 else 1

    private fun post(body: String, apiKey: String, source: NetSource, depth: String): String =
        ToolHttp.postJson(
            url = ServiceEndpoints.base(ServiceEndpoint.TAVILY) + PATH,
            body = body,
            timeoutMs = 20_000,
            headers = mapOf(
                "Authorization" to "Bearer ${apiKey.trim()}",
                "Accept" to "application/json",
            ),
            source = source,
            route = PATH,
        ).also { ServiceUsage.record(USAGE_KEY, credits = credits(depth)) }

    /**
     * The search at [depth]. `safe_search` is left out at the two fast
     * depths, which refuse it outright rather than ignore it.
     *
     * [excludeDomains] is how More results asks for something new (#879):
     * Tavily has no page number, so the sites already shown are left out,
     * up to the 150 it takes.
     */
    internal fun requestBody(
        query: String,
        count: Int,
        safe: Boolean,
        images: Boolean,
        depth: String = DEFAULT_DEPTH,
        answer: Boolean = false,
        excludeDomains: List<String> = emptyList(),
    ): String =
        buildJsonObject {
            put("query", query.trim())
            put("search_depth", depth)
            put("max_results", count.coerceIn(1, MAX_RESULTS))
            put("include_answer", answer)
            put("include_images", images)
            if (depth !in FAST_DEPTHS) put("safe_search", safe)
            if (excludeDomains.isNotEmpty()) {
                put("exclude_domains", JsonArray(excludeDomains.distinct().take(MAX_EXCLUDED).map(::JsonPrimitive)))
            }
        }.toString()

    /** Tavily's written answer, or null when it gave none. */
    internal fun parseAnswer(body: String): String? =
        (Json.parseToJsonElement(body).jsonObject["answer"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    internal fun parseWeb(body: String): List<WebResult> {
        val results = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.text("url") ?: return@mapNotNull null
            WebResult(
                title = item.text("title") ?: SearxClient.hostOf(url),
                // Tavily joins the chunks it quotes from one page with "[...]".
                snippet = item.text("content").orEmpty().replace(" [...] ", " … "),
                url = url,
                displayUrl = SearxClient.hostOf(url),
            )
        }
    }

    /**
     * The top-level `images` list: bare addresses, or `{url, description}`
     * objects when descriptions were asked for. Both shapes are read, so a
     * change of request can never empty the grid.
     *
     * Each result also lists the pictures from its own page; an image found
     * there gets that page as its [ImageResult.contextUrl], which More results
     * leaves out next time and the result menu can open.
     */
    internal fun parseImages(body: String): List<ImageResult> {
        val root = Json.parseToJsonElement(body).jsonObject
        val images = root["images"]?.jsonArray.orEmpty()
        val pageOf = HashMap<String, String>()
        for (result in root["results"]?.jsonArray.orEmpty()) {
            val item = result as? JsonObject ?: continue
            val page = item.text("url") ?: continue
            for (image in (item["images"] as? JsonArray).orEmpty()) {
                val url = when (image) {
                    is JsonPrimitive -> image.content.takeIf { image.isString }
                    is JsonObject -> image.text("url")
                    else -> null
                } ?: continue
                pageOf.getOrPut(url) { page }
            }
        }
        return images.mapNotNull { element ->
            val url: String
            val title: String
            when (element) {
                is JsonPrimitive -> {
                    url = element.content.takeIf { element.isString && it.isNotBlank() } ?: return@mapNotNull null
                    title = ""
                }
                is JsonObject -> {
                    url = element.text("url") ?: return@mapNotNull null
                    title = element.text("description").orEmpty()
                }
                else -> return@mapNotNull null
            }
            ImageResult(
                title = title,
                thumbUrl = url,
                imageUrl = url,
                mime = BraveSearchClient.mimeFromUrl(url),
                contextUrl = pageOf[url].orEmpty(),
            )
        }.distinctBy { it.imageUrl }
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** Tavily's own ceiling for `max_results`. */
    private const val MAX_RESULTS = 20

    /** How many pictures go under the answer. */
    const val ANSWER_IMAGES = 5

    /** Tavily's one-credit default. */
    const val DEFAULT_DEPTH = "basic"

    /** Tavily's ceiling for `exclude_domains`. */
    private const val MAX_EXCLUDED = 150

    /** The depths that take no `safe_search`. */
    private val FAST_DEPTHS = setOf("fast", "ultra-fast")
}

package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Web search that needs no key of the app's (the owner's question on the
 * 2026-11-16 sweep): each of these publishes an API for apps to use, and
 * each was tried live before it was added. Mwmbl, the first of them, has
 * its own file. Left out on purpose: DuckDuckGo (its Instant Answer API has
 * no web results, and its result pages may not be scraped), Mojeek (paid
 * key only), Stract and Alexandria (gone), public SearXNG instances (most
 * refuse JSON, and the project asks apps not to lean on them).
 */

/**
 * Wiby, a search engine for the small, old-style web: personal and hobby
 * pages, little script. Its About page invites apps to use its JSON output;
 * the engine is GPLv2 and self-hostable, so the address can be changed.
 * One page of results, no images, no SafeSearch.
 */
object WibySearchClient {

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun webSearch(query: String, count: Int, source: NetSource = NetSource.WEB_SEARCH, page: Int = 0): List<WebResult> {
        // Wiby pages with `p`, from 1, twelve results a page.
        val url = ServiceEndpoints.base(ServiceEndpoint.WIBY) + PATH + "?q=" + ToolHttp.encode(query.trim()) +
            if (page > 0) "&p=${page + 1}" else ""
        return parse(ToolHttp.get(url, headers = mapOf("Accept" to "application/json"), source = source, route = PATH))
            .take(count.coerceAtLeast(1))
    }

    private const val PATH = "/json/"

    /** `[{"URL": "…", "Title": "…", "Snippet": "…", "Description": "…"}, …]`. */
    internal fun parse(body: String): List<WebResult> {
        val items = Json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.text("URL").ifBlank { return@mapNotNull null }
            WebResult(
                title = searchUnescape(item.text("Title")).ifBlank { url },
                snippet = searchUnescape(item.text("Snippet").ifBlank { item.text("Description") }).trim(),
                url = url,
                displayUrl = url.substringAfter("://").substringBefore('/'),
            )
        }
    }
}

/**
 * Marginalia Search, an independent engine that favours text-heavy,
 * non-commercial sites. Its API takes a key in the `API-Key` header: a
 * free non-commercial one is given out by e-mail, and without one the
 * shared `public` key is used, which its docs say "often hits a rate
 * limit" (it answered 429 on the day this was written). Results are under
 * CC BY-NC-SA 4.0, so they carry the "via Marginalia" credit.
 */
object MarginaliaSearchClient {

    const val PUBLIC_KEY = "public"

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun webSearch(
        query: String,
        apiKey: String,
        count: Int,
        safe: Boolean,
        source: NetSource = NetSource.WEB_SEARCH,
        page: Int = 0,
    ): List<WebResult> {
        val url = ServiceEndpoints.base(ServiceEndpoint.MARGINALIA) + PATH +
            "?query=" + ToolHttp.encode(query.trim()) +
            "&count=${count.coerceIn(1, 100)}" +
            "&page=${page + 1}" +
            // 1 is its (experimental) filter for the worst results; 0 none.
            "&nsfw=${if (safe) 1 else 0}"
        val key = apiKey.trim().ifEmpty { PUBLIC_KEY }
        return parse(ToolHttp.get(url, headers = mapOf("API-Key" to key, "Accept" to "application/json"), source = source, route = PATH))
    }

    private const val PATH = "/search"

    /** `{"query": "…", "license": "…", "results": [{"url", "title", "description"}, …]}`. */
    internal fun parse(body: String): List<WebResult> {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.text("url").ifBlank { return@mapNotNull null }
            WebResult(
                title = item.text("title").ifBlank { url },
                snippet = item.text("description").trim(),
                url = url,
                displayUrl = url.substringAfter("://").substringBefore('/'),
            )
        }
    }
}

/**
 * Wikipedia's own search (MediaWiki's `list=search`), as a search provider:
 * articles only, in the Wikipedia tool's language. Free, keyless and meant
 * for apps; the address follows the Wikipedia server setting.
 */
object WikipediaSearchClient {

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun webSearch(query: String, lang: String, count: Int, source: NetSource = NetSource.WEB_SEARCH, page: Int = 0): List<WebResult> {
        val each = count.coerceIn(1, 50)
        val base = ServiceEndpoints.wikipedia(lang.ifBlank { "en" })
        val url = base + PATH + "?action=query&list=search&format=json&formatversion=2&utf8=1" +
            "&srlimit=$each&sroffset=${each * page}&srsearch=" + ToolHttp.encode(query.trim())
        return parse(ToolHttp.get(url, source = source, route = PATH), base)
    }

    private const val PATH = "/w/api.php"

    private val TAGS = Regex("<[^>]+>")

    internal fun parse(body: String, base: String): List<WebResult> {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val search = ((root["query"] as? JsonObject)?.get("search")) as? JsonArray ?: return emptyList()
        return search.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = item.text("title").ifBlank { return@mapNotNull null }
            val url = "$base/wiki/" + ToolHttp.encode(title.replace(' ', '_')).replace("%2F", "/")
            WebResult(
                title = title,
                snippet = searchUnescape(item.text("snippet").replace(TAGS, "")).trim(),
                url = url,
                displayUrl = base.substringAfter("://"),
            )
        }
    }
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/** The entities these services leave in their text: `&#34;`, `&amp;`, `&quot;`. */
internal fun searchUnescape(text: String): String =
    if ('&' !in text) {
        text
    } else {
        ENTITY.replace(text) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED[name]
            } ?: m.value
        }
    }

private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.GifContentFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GIPHY answered 429 to the key the build ships with. Every user who has not
 * added a key of their own shares that one, so its hourly quota runs out on
 * busy days; the panel says so and points at [rowKey]'s provider page, where
 * a free key of the user's own lifts the limit. Still a 429 to
 * `ProviderRunner`, so the row cools down as for any other.
 */
class GiphySharedKeyLimited(cause: ToolHttpException, val rowKey: String) :
    ToolHttpException(cause.messageRes, cause.status, cause.apiMessage, cause.headers) {
    init {
        initCause(cause)
    }
}

/**
 * Minimal GIPHY v1 client — the second GIF/sticker provider next to
 * [KlipyClient]. Same shape: blank query returns trending, stickers are a
 * separate endpoint with transparent results. Requires a (free) GIPHY API
 * key. The shared [GifContentFilter] maps onto GIPHY's MPAA-style rating.
 */
object GiphyClient {

    /**
     * [search], with a 429 on the build's own key turned into
     * [GiphySharedKeyLimited] for row [rowKey]. A key the user pasted keeps
     * the plain error: the limit is theirs, and no other key would help.
     */
    fun searchRow(
        rowKey: String,
        query: String,
        apiKey: String,
        stickers: Boolean,
        filter: GifContentFilter,
        limit: Int = 24,
    ): List<GifItem> = try {
        search(query, apiKey, stickers, filter, limit)
    } catch (e: ToolHttpException) {
        val builtIn = ProviderTypes.GIPHY.builtInKey
        throw if (e.status == HTTP_TOO_MANY_REQUESTS && builtIn.isNotBlank() && apiKey == builtIn) {
            GiphySharedKeyLimited(e, rowKey)
        } else {
            e
        }
    }

    private const val HTTP_TOO_MANY_REQUESTS = 429

    /** Blocking; call on an IO dispatcher. Empty [query] returns trending. */
    fun search(
        query: String,
        apiKey: String,
        stickers: Boolean,
        filter: GifContentFilter,
        limit: Int = 24,
    ): List<GifItem> {
        val type = if (stickers) "stickers" else "gifs"
        val endpoint = if (query.isBlank()) "trending" else "search"
        val url = buildString {
            append("${ServiceEndpoints.base(ServiceEndpoint.GIPHY)}/v1/$type/$endpoint")
            append("?api_key=${ToolHttp.encode(apiKey)}")
            if (query.isNotBlank()) append("&q=${ToolHttp.encode(query.trim())}")
            append("&limit=$limit&rating=${rating(filter)}")
        }
        return parse(
            ToolHttp.get(url, source = if (stickers) NetSource.STICKER else NetSource.GIF, route = "/v1/$type/$endpoint"),
        )
    }

    /**
     * Categories to browse, for the panel's default view. Blocking; call on
     * an IO dispatcher.
     *
     * GIPHY publishes a category tree for GIFs only, so stickers borrow the
     * trending search terms instead. Those are live queries rather than a
     * taxonomy, which reads the same on a chip and works for either panel.
     */
    fun categories(apiKey: String, stickers: Boolean, limit: Int = MediaCategories.MAX_CATEGORIES):
        List<MediaCategory> {
        val path = if (stickers) "trending/searches" else "gifs/categories"
        val url = buildString {
            append("${ServiceEndpoints.base(ServiceEndpoint.GIPHY)}/v1/$path")
            append("?api_key=${ToolHttp.encode(apiKey)}")
            if (!stickers) append("&limit=$limit")
        }
        return parseCategories(
            ToolHttp.get(url, source = if (stickers) NetSource.STICKER else NetSource.GIF, route = "/v1/$path"),
        )
    }

    /**
     * `data` holds category objects for `gifs/categories` and bare strings
     * for `trending/searches`; take either. `name_encoded` is the form the
     * search endpoint wants, so it wins over the display `name`.
     * `subcategories` is ignored: flattening it gives hundreds of chips.
     */
    internal fun parseCategories(body: String): List<MediaCategory> {
        val data = runCatching {
            Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray
        }.getOrNull() ?: return emptyList()
        return data.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                    ?.let { MediaCategory(term = it, label = it) }
                is JsonObject -> {
                    val name = element.text("name") ?: return@mapNotNull null
                    MediaCategory(term = element.text("name_encoded") ?: name, label = name)
                }
                else -> null
            }
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /** GIPHY ratings are cumulative: "pg" means pg and below. */
    private fun rating(filter: GifContentFilter): String = when (filter) {
        GifContentFilter.HIGH -> "g"
        GifContentFilter.MEDIUM -> "pg"
        GifContentFilter.LOW -> "pg-13"
        GifContentFilter.OFF -> "r"
    }

    internal fun parse(body: String): List<GifItem> {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val result = element.jsonObject
            val images = result["images"]?.jsonObject ?: return@mapNotNull null
            val preview = images.rendition("fixed_width_small")
                ?: images.rendition("preview_gif")
                ?: images.rendition("original")
                ?: return@mapNotNull null
            val full = images.rendition("original") ?: preview
            GifItem(
                id = "giphy_" + (result["id"]?.jsonPrimitive?.content ?: return@mapNotNull null),
                previewUrl = preview.url,
                fullUrl = full.url,
                mime = "image/gif",
                aspectRatio = preview.aspectRatio,
                source = GifSource.GIPHY,
                title = result.text("title").orEmpty(),
                creator = creator(result),
            )
        }
    }

    /**
     * The uploader's GIPHY username, for the credit the API terms ask for. The
     * `user` object carries it for verified channels; plain uploads only have
     * the top-level `username`, often blank.
     */
    private fun creator(result: JsonObject): String =
        (result["user"] as? JsonObject)?.text("username") ?: result.text("username").orEmpty()

    private class Rendition(val url: String, val aspectRatio: Float)

    private fun JsonObject.rendition(name: String): Rendition? {
        val rendition = this[name]?.jsonObject ?: return null
        val url = rendition["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val width = rendition["width"]?.jsonPrimitive?.floatOrNull
        val height = rendition["height"]?.jsonPrimitive?.floatOrNull
        val ratio = if (width != null && height != null && height > 0f) width / height else 1f
        return Rendition(url, ratio)
    }
}

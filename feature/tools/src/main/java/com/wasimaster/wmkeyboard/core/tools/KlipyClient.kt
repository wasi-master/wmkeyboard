package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
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
 * Minimal KLIPY client — Tenor's successor as the primary GIF/sticker
 * provider (Google retired the Tenor API for third parties on
 * 2026-06-30). Same shape as the other providers: blank query returns
 * trending, stickers are their own endpoint. The API key goes in the URL
 * path; get one at partner.klipy.com. The shared [GifContentFilter] maps
 * onto KLIPY's GIPHY-style rating parameter.
 */
object KlipyClient {

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
            append("${ServiceEndpoints.base(ServiceEndpoint.KLIPY)}/api/v1/${ToolHttp.encode(apiKey)}/$type/$endpoint")
            append("?page=1&per_page=$limit&rating=${rating(filter)}")
            if (query.isNotBlank()) append("&q=${ToolHttp.encode(query.trim())}")
        }
        // The key is a path segment on KLIPY, so the route names it rather than showing it.
        return parse(ToolHttp.get(url, source = gifSource(stickers), route = "/api/v1/{key}/$type/$endpoint"))
    }

    /**
     * KLIPY's memes (`/static-memes/`, docs.klipy.com/memes-api): still images
     * in their own tab of the GIF panel, as KLIPY's integration rules want
     * them kept apart from the GIFs. Blocking; call on an IO dispatcher.
     * Empty [query] returns trending. The memes endpoints take
     * `content_filter` (off, low, medium, high) where the GIF ones take a
     * rating.
     */
    fun memes(
        query: String,
        apiKey: String,
        filter: GifContentFilter,
        limit: Int = 24,
    ): List<GifItem> {
        val endpoint = if (query.isBlank()) "trending" else "search"
        val url = buildString {
            append("${ServiceEndpoints.base(ServiceEndpoint.KLIPY)}/api/v1/${ToolHttp.encode(apiKey)}/static-memes/$endpoint")
            append("?page=1&per_page=${limit.coerceIn(1, MEMES_PER_PAGE_MAX)}&content_filter=${contentFilter(filter)}")
            if (query.isNotBlank()) append("&q=${ToolHttp.encode(query.trim())}")
        }
        return parseMemes(ToolHttp.get(url, source = NetSource.GIF, route = "/api/v1/{key}/static-memes/$endpoint"))
    }

    /** The memes endpoints' own cap on `per_page`. */
    private const val MEMES_PER_PAGE_MAX = 50

    private fun contentFilter(filter: GifContentFilter): String = when (filter) {
        GifContentFilter.HIGH -> "high"
        GifContentFilter.MEDIUM -> "medium"
        GifContentFilter.LOW -> "low"
        GifContentFilter.OFF -> "off"
    }

    /**
     * Same envelope as [parse]; a meme's `file.<size>` holds `png` and `webp`
     * (`jpg` on some). The grid previews the small WebP; a tap sends the PNG,
     * which every app that takes images reads, where a WebP may be taken for
     * a sticker.
     */
    internal fun parseMemes(body: String): List<GifItem> {
        val data = Json.parseToJsonElement(body).jsonObject["data"] ?: return emptyList()
        val items = when (data) {
            is JsonArray -> data
            is JsonObject -> data["data"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return items.mapNotNull { element ->
            val result = element as? JsonObject ?: return@mapNotNull null
            val file = result["file"] as? JsonObject ?: return@mapNotNull null
            val preview = file.variant("sm", "webp")
                ?: file.variant("sm", "png")
                ?: file.variant("sm", "jpg")
                ?: file.variant("md", "webp")
                ?: file.variant("xs", "webp")
                ?: file.variant("md", "png")
            val full = file.variant("hd", "png")
                ?: file.variant("md", "png")
                ?: file.variant("hd", "jpg")
                ?: file.variant("md", "jpg")
                ?: file.variant("hd", "webp")
                ?: file.variant("md", "webp")
                ?: preview
            if (preview == null || full == null) return@mapNotNull null
            GifItem(
                id = "klipy_meme_" + (result.text("id") ?: result.text("slug") ?: return@mapNotNull null),
                previewUrl = preview.url,
                fullUrl = full.url,
                mime = imageMime(full.url),
                aspectRatio = preview.aspectRatio,
                source = GifSource.KLIPY,
                title = result.text("title") ?: result.text("slug").orEmpty(),
            )
        }
    }

    private fun imageMime(url: String): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/gif"
    }

    /**
     * Categories to browse, for the panel's default view. Blocking; call on
     * an IO dispatcher.
     *
     * KLIPY advertises a categories endpoint but does not publish its shape,
     * so both the path and the response reading here are a best guess. That
     * is why nothing above this treats a failure as an error: a 404, or a
     * 200 in a shape we do not know, both come back as an empty list and the
     * panel falls back to [MediaCategories.bundled].
     */
    fun categories(apiKey: String, stickers: Boolean, limit: Int = MediaCategories.MAX_CATEGORIES):
        List<MediaCategory> {
        val type = if (stickers) "stickers" else "gifs"
        val url = "${ServiceEndpoints.base(ServiceEndpoint.KLIPY)}/api/v1/${ToolHttp.encode(apiKey)}/$type/categories" +
            "?page=1&per_page=$limit"
        return parseCategories(ToolHttp.get(url, source = gifSource(stickers), route = "/api/v1/{key}/$type/categories"))
    }

    private fun gifSource(stickers: Boolean) = if (stickers) NetSource.STICKER else NetSource.GIF

    /**
     * Same `data` unwrap as [parse]. Each entry is either a bare string or an
     * object naming itself; since the shape is unverified, read whichever of
     * the plausible keys is there rather than betting on one.
     */
    internal fun parseCategories(body: String): List<MediaCategory> {
        val data = runCatching { Json.parseToJsonElement(body).jsonObject["data"] }.getOrNull()
            ?: return emptyList()
        val items = when (data) {
            is JsonArray -> data
            is JsonObject -> data["data"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return items.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                    ?.let { MediaCategory(term = it, label = it) }
                is JsonObject -> {
                    val name = element.text("name")
                        ?: element.text("title")
                        ?: element.text("slug")
                        ?: element.text("term")
                        ?: return@mapNotNull null
                    val term = element.text("slug") ?: element.text("term") ?: name
                    MediaCategory(term = term, label = name)
                }
                else -> null
            }
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun rating(filter: GifContentFilter): String = when (filter) {
        GifContentFilter.HIGH -> "g"
        GifContentFilter.MEDIUM -> "pg"
        GifContentFilter.LOW -> "pg-13"
        GifContentFilter.OFF -> "r"
    }

    /**
     * Response shape: `{"result":true,"data":{"data":[…],"has_next":…}}`
     * (the inner "data" is the item list; some endpoints return the list
     * directly). Each item's media lives under `file.<size>.<format>.url`
     * with sizes hd/md/sm/xs and formats gif/webp/mp4/jpg.
     */
    internal fun parse(body: String): List<GifItem> {
        val data = Json.parseToJsonElement(body).jsonObject["data"] ?: return emptyList()
        val items = when (data) {
            is JsonArray -> data
            is JsonObject -> data["data"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return items.mapNotNull { element ->
            val result = element.jsonObject
            val file = result["file"]?.jsonObject ?: return@mapNotNull null
            val preview = file.variant("sm", "gif")
                ?: file.variant("xs", "gif")
                ?: file.variant("md", "gif")
                ?: file.variant("sm", "webp")
                ?: file.variant("xs", "webp")
            val full = file.variant("hd", "gif")
                ?: file.variant("md", "gif")
                ?: preview
            if (preview == null || full == null) return@mapNotNull null
            GifItem(
                id = "klipy_" + (result["id"]?.jsonPrimitive?.content
                    ?: result["slug"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null),
                previewUrl = preview.url,
                fullUrl = full.url,
                mime = if (full.url.substringAfterLast('.').startsWith("webp")) "image/webp" else "image/gif",
                aspectRatio = preview.aspectRatio,
                source = GifSource.KLIPY,
                title = result.text("title") ?: result.text("slug").orEmpty(),
            )
        }
    }

    private class Variant(val url: String, val aspectRatio: Float)

    /** `file.<size>.<format>` — with a flat `file.<format>` fallback. */
    private fun JsonObject.variant(size: String, format: String): Variant? {
        val holder = this[size]?.jsonObject?.get(format)?.jsonObject
            ?: this[format]?.jsonObject?.takeIf { size == "hd" }
            ?: return null
        val url = holder["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val width = holder["width"]?.jsonPrimitive?.floatOrNull
        val height = holder["height"]?.jsonPrimitive?.floatOrNull
        val ratio = if (width != null && height != null && height > 0f) width / height else 1f
        return Variant(url, ratio)
    }
}

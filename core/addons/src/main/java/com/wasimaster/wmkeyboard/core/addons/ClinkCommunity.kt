package com.wasimaster.wmkeyboard.core.addons

import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The "Open in Clink" links on Clink's community page
 * (`https://www.anti.ltd/store/clink/community`): `clink://community/<id>`.
 *
 * Clink is an iPhone keyboard, so on Android nothing else answers these links.
 * What this keyboard can do with one is limited by where the item lives: the
 * community page's public list gives each item's name, author, kind,
 * description and a preview picture, and the item itself is only handed to a
 * signed-in Clink account. So a link opens a preview of the item, and the
 * file, when its owner has one exported from Clink, comes in through the
 * ordinary Clink import.
 */
object ClinkCommunity {

    const val SCHEME = "clink"
    const val HOST = "community"

    /** The public page the links come from, for "see it on the web". */
    const val PAGE_URL = "https://www.anti.ltd/store/clink/community"

    /** How many items one page of the public list holds; the list says when there are more. */
    const val PAGE_SIZE = 24

    /** How far into the list a lookup goes before it gives up: newest first, so old links are the far ones. */
    const val MAX_PAGES = 25

    private val ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The item id a `clink://community/<id>` link names, lowercased; null for anything else. */
    fun itemIdFrom(link: String?): String? {
        val text = link?.trim().orEmpty()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        // "clink://community/<id>" has the host and a path; "clink:community/<id>" is opaque.
        val body = if (uri.isOpaque) {
            uri.schemeSpecificPart.orEmpty()
        } else {
            (uri.host.orEmpty() + "/" + uri.path.orEmpty().trim('/'))
        }
        val parts = body.substringBefore('?').trim('/').split('/')
        if (parts.size != 2 || !parts[0].equals(HOST, ignoreCase = true)) return null
        return parts[1].takeIf { ID.matches(it) }?.lowercase()
    }

    /** One page of the public list, newest first. */
    fun listUrl(offset: Int): String =
        "https://www.anti.ltd/api/clink/community?includeVotes=true&offset=$offset&kind=&sort=newest"

    /** An item as the public list describes it. */
    data class Item(
        val id: String,
        /** Clink's word for it: theme, layout, sound, plugin, panel, action, language, font, profile… */
        val kind: String,
        val title: String,
        val author: String,
        val description: String,
        val votes: Int,
        /** The preview picture as a `data:` URI, when there is one. */
        val preview: String?,
    ) {
        /** Whether a file of this kind, exported from Clink, imports here. */
        val importable: Boolean get() = kind.lowercase() in IMPORTABLE_KINDS
    }

    data class Page(val items: List<Item>, val hasMore: Boolean)

    /** [text], one page of the public list; null when it is not one. */
    fun parsePage(text: String): Page? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val list = root["items"] as? JsonArray ?: return null
        val items = list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.lowercase()?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            Item(
                id = id,
                kind = obj.string("kind").orEmpty(),
                title = obj.string("title")?.trim().orEmpty(),
                author = obj.string("author")?.trim().orEmpty(),
                description = obj.string("description")?.trim().orEmpty(),
                votes = (obj["votes"] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() } ?: 0,
                preview = obj.string("preview")?.takeIf { it.startsWith("data:image/") },
            )
        }
        val hasMore = (root["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: (items.size >= PAGE_SIZE)
        return Page(items, hasMore)
    }

    /** The picture inside a `data:image/…;base64,` URI, or null. Capped, since it comes off the web. */
    @OptIn(ExperimentalEncodingApi::class)
    fun previewBytes(dataUri: String?): ByteArray? {
        val uri = dataUri ?: return null
        if (!uri.startsWith("data:image/") || uri.length > MAX_PREVIEW_CHARS) return null
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) return null
        return runCatching { Base64.decode(uri.substring(comma + 1)) }.getOrNull()
    }

    /** Clink kinds whose exported file this keyboard reads: themes and custom layouts. */
    private val IMPORTABLE_KINDS = setOf("theme", "aesthetic", "layout")

    private const val MAX_PREVIEW_CHARS = 4 * 1024 * 1024

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

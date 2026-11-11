package com.wasimaster.wmkeyboard.core.clipboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Cleans a clipboard bundle item by item before it is written, so one bad item
 * cannot take the whole history with it (#1215, from saen1a's prototype).
 *
 * A bundle arrives as raw JSON (`{"items":[...]}`): a settings backup's
 * Clipboard section, a sync payload, a file another install wrote. It is not
 * trusted. Before this, a restore wrote it to `clipboard/history.json` as it
 * came, which let a crafted file do two things a real one never does:
 *
 * - **Delete files.** An item that names an `imagePath` is an image clip to
 *   the store, and the store deletes that path when the clip expires or is
 *   removed. A textual clip never needs one, so it is not accepted.
 * - **Lose the history.** The store decodes the whole file at once and needs
 *   an `id` and a `timestamp` on every item, so one item missing either, or
 *   carrying a field of the wrong type, made the file unreadable and the
 *   history loaded empty.
 *
 * What may travel is what [ClipboardStore] would keep for a copy of its own:
 * text, rich text and link clips, not marked sensitive, each decoded alone
 * with the store's own rules first. Image, file, folder and video clips point
 * at bytes or content URIs that only exist on the source phone, so carrying
 * them would only leave broken entries; that rule is older than this object
 * and unchanged. A clip longer than [ClipboardStore.HARD_MAX_TEXT_CHARS] is
 * cut to it rather than dropped, so nothing the user kept is lost to the cap.
 *
 * Deliberately not done: re-reading the text for a secret's shape. Under
 * "Keep it like any other clip" a password-shaped clip is an ordinary clip
 * the user asked to keep, and it travels as one.
 *
 * Pure and Android-free: no Context, no store, no I/O.
 */
object ClipboardSyncSanitizer {

    /** Clip kinds worth carrying: the ones whose content travels as text. */
    private val TEXTUAL_KINDS = ClipKind.entries.filter { it.isTextual }.map { it.name }.toSet()

    /** The store's own decoder settings, so an item that passes here loads there. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The bundle as it may be written: each item [portable] keeps, at most
     * once per id. A non-object element, or one with no `items` array,
     * becomes an empty `{"items":[]}` rather than an error, so one malformed
     * section cannot sink a restore.
     */
    fun sanitize(element: JsonElement): JsonObject {
        val obj = element as? JsonObject ?: return emptyBundle()
        val items = obj["items"] as? JsonArray ?: return emptyBundle()
        val seenIds = HashSet<Long>()
        val kept = items.mapNotNull { item ->
            val clean = portable(item) ?: return@mapNotNull null
            val id = clean.clipId() ?: return@mapNotNull null
            // Two items with one id would collide in the store; the first stays.
            clean.takeIf { seenIds.add(id) }
        }
        return buildJsonObject { put("items", JsonArray(kept)) }
    }

    /**
     * [item] as it may travel, or null when it may not: not a text, rich-text
     * or link clip, marked sensitive, carrying any of the file fields a
     * textual clip never has, or not a shape the store's decoder accepts.
     * Text past the store's hard cap is cut to it, and markup past its cap,
     * or for text that was cut, is dropped, as the store does for a copy.
     */
    fun portable(item: JsonElement): JsonObject? {
        val entry = item as? JsonObject ?: return null
        if (entry.rawSensitive() || entry.rawKind() !in TEXTUAL_KINDS) return null
        val text = (entry["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        // A textual clip never needs file paths or URIs. Accepting them from a
        // bundle would let a crafted item make the store delete a file.
        if (entry.hasValue("imagePath") || entry.hasValue("uriString") || entry.hasValue("fileName")) return null
        if (entry.hasValue("linkPreview")) return null
        if (entry.longOr("fileSize", -1L) > 0 || entry.longOr("durationMs", -1L) > 0) return null
        if (entry.clipId() == null || entry.longOr("timestamp", Long.MIN_VALUE) == Long.MIN_VALUE) return null
        val cut = capClipText(text, ClipboardStore.HARD_MAX_TEXT_CHARS)
        val html = (entry["htmlText"] as? JsonPrimitive)?.contentOrNull
        val dropHtml = html != null && (cut.length != text.length || !html.fitsHtmlCap())
        val clean = when {
            cut.length == text.length && !dropHtml -> entry
            else -> JsonObject(
                entry + buildMap<String, JsonElement> {
                    put("text", JsonPrimitive(cut))
                    if (dropHtml) {
                        put("htmlText", JsonNull)
                        put("mimeType", JsonPrimitive("text/plain"))
                        if (entry.rawKind() == ClipKind.HTML.name) put("kind", JsonPrimitive(ClipKind.TEXT.name))
                    }
                },
            )
        }
        // The checks above read a few fields leniently, so a value of the wrong
        // type (`pinned:"yes"`, `tags:3`) would still pass them and then fail
        // the store's strict decode, taking the whole history with it. Decode
        // this item alone with the store's settings; only a shape it accepts
        // may travel.
        return clean.takeIf { runCatching { json.decodeFromString<ClipItem>(it.toString()) }.isSuccess }
    }

    /** Whether [item] passes [portable]. */
    fun isPortableItem(item: JsonElement): Boolean = portable(item) != null

    /**
     * Whether [item], a clip already on this phone, stays when a synced bundle
     * replaces the textual ones: an image, file, folder or video clip, which
     * never syncs, or a clip marked sensitive, which never syncs either and
     * would otherwise be lost to the merge. The same clips stayed before #1215.
     */
    fun staysLocal(item: JsonElement): Boolean {
        val entry = item as? JsonObject ?: return false
        return entry.rawSensitive() || entry.rawKind() !in TEXTUAL_KINDS
    }

    /**
     * [incoming] clips made unique by id against [stays], which keep their
     * own, followed by [stays]. An incoming id that collides with a clip that
     * stays, or with an incoming clip already seen, is renumbered above the
     * highest id in use: the store looks a clip up by id, so two with one id
     * would make a delete or a pin land on the wrong one.
     */
    fun deconflictIds(incoming: List<JsonElement>, stays: List<JsonElement>): List<JsonElement> {
        val used = HashSet<Long>()
        stays.forEach { entry -> (entry as? JsonObject)?.clipId()?.let(used::add) }
        var next = ((stays + incoming).mapNotNull { (it as? JsonObject)?.clipId() }.maxOrNull() ?: 0L) + 1L
        return incoming.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = obj.clipId()
            if (id != null && used.add(id)) return@mapNotNull obj
            while (!used.add(next)) next++
            JsonObject(obj + ("id" to JsonPrimitive(next++)))
        } + stays
    }

    private fun JsonObject.clipId(): Long? = longOrNull("id")

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()

    private fun JsonObject.longOr(key: String, fallback: Long): Long = longOrNull(key) ?: fallback

    private fun JsonObject.hasValue(key: String): Boolean = this[key].let { it != null && it !is JsonNull }

    private fun JsonObject.rawKind(): String =
        (this["kind"] as? JsonPrimitive)?.contentOrNull ?: ClipKind.TEXT.name

    private fun JsonObject.rawSensitive(): Boolean =
        (this["sensitive"] as? JsonPrimitive)?.booleanOrNull == true

    private fun emptyBundle(): JsonObject = buildJsonObject { put("items", JsonArray(emptyList())) }
}

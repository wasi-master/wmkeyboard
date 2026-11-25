package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reading and rewriting a Lottie document as a JSON tree.
 *
 * Only the parts dynamic stickers touch are modelled: the canvas, the text
 * layers and their documents, the fonts list and the image assets. Everything
 * else passes through untouched, so a template keeps every effect and shape
 * its author gave it however much of the format this file knows about.
 *
 * Text layers are found in the top-level layers and inside precomps (assets
 * with layers of their own), since a template often animates its words as a
 * group. A layer is known by its name (`nm`); templates name theirs `_txt_0`,
 * `_txt_1`, … for the lines of their text.
 */
object LottieDoc {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Text layers are type 5, image layers type 2. */
    const val TYPE_TEXT = 5
    const val TYPE_IMAGE = 2

    /** A layer named like this is one line of a template's main text. */
    val LINE_LAYER = Regex("_txt_(\\d+)")

    /** [text] as a document, or null when it is not JSON or has no layers. */
    fun parse(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.takeIf { it["layers"] is JsonArray }

    fun encode(root: JsonObject): String = json.encodeToString(JsonObject.serializer(), root)

    /** The canvas and timing of a document. */
    data class Info(
        val width: Int,
        val height: Int,
        val inPoint: Float,
        val outPoint: Float,
        val frameRate: Float,
    ) {
        val frames: Float get() = (outPoint - inPoint).coerceAtLeast(1f)

        /** More than one frame: a still template is exported with an out point of 1. */
        val animated: Boolean get() = frames > 1f

        val durationMs: Int get() = (frames * MS_PER_SECOND / frameRate.coerceAtLeast(1f)).toInt()
    }

    fun info(root: JsonObject): Info = Info(
        width = root.int("w") ?: DEFAULT_SIZE,
        height = root.int("h") ?: DEFAULT_SIZE,
        inPoint = root.float("ip") ?: 0f,
        outPoint = root.float("op") ?: 1f,
        frameRate = root.float("fr") ?: DEFAULT_FPS,
    )

    /**
     * The first document of a text layer, read into the fields fitting needs.
     * `rs`, `mf`, `xf`, `ml` and `vj` are the auto-fit extensions Skia's
     * player reads; templates made for it carry them, and fitting text for
     * Lottie (which ignores them) honours them by hand.
     */
    data class TextDoc(
        val text: String,
        val size: Float,
        val font: String,
        /** 0 left, 1 right, 2 centre. */
        val justify: Int,
        /** All capitals (`ca` 1). */
        val allCaps: Boolean,
        val lineHeight: Float,
        /** In thousandths of an em. */
        val tracking: Float,
        val boxWidth: Float?,
        val boxHeight: Float?,
        val boxX: Float?,
        val boxY: Float?,
        /** 0 none, 1 scale to fit (up and down), 2 only scale down. Null when absent. */
        val resize: Int?,
        val minFont: Float?,
        val maxFont: Float?,
        val maxLines: Int?,
        /** 0 top, 1 centre, 2 bottom; the visual variants (3..5) read as their plain ones. */
        val verticalAlign: Int?,
        /** Fill colour as 0..1 RGB(A), when set. */
        val fill: List<Float>?,
    ) {
        val hasBox: Boolean get() = boxWidth != null && boxHeight != null && boxWidth > 0f && boxHeight > 0f

        /** The text's lines as the document holds them. */
        val lines: List<String> get() = splitLines(text)
    }

    /** A text layer and where it sits. */
    data class TextLayerInfo(
        val name: String,
        val doc: TextDoc,
        /** It has text animators: some per-character motion that a rasterised copy would lose. */
        val animators: Boolean,
        /** Inside a precomp rather than at the top level. */
        val nested: Boolean,
    )

    /** Every text layer, top level first, then each precomp's; the first of any repeated name wins. */
    fun textLayers(root: JsonObject): List<TextLayerInfo> {
        val out = ArrayList<TextLayerInfo>()
        val seen = HashSet<String>()
        fun scan(layers: JsonArray?, nested: Boolean) {
            layers ?: return
            for (element in layers) {
                val layer = element as? JsonObject ?: continue
                if (layer.int("ty") != TYPE_TEXT) continue
                val name = layer.string("nm") ?: continue
                val doc = firstDoc(layer)?.let(::readDoc) ?: continue
                if (!seen.add(name)) continue
                val animators = (layer.obj("t")?.get("a") as? JsonArray)?.isNotEmpty() == true
                out += TextLayerInfo(name, doc, animators, nested)
            }
        }
        scan(root["layers"] as? JsonArray, nested = false)
        (root["assets"] as? JsonArray)?.forEach { asset ->
            scan((asset as? JsonObject)?.get("layers") as? JsonArray, nested = true)
        }
        return out
    }

    /** `metadata.customProps`, where templates keep their matching rules. */
    fun customProps(root: JsonObject): JsonObject? = root.obj("metadata")?.obj("customProps")

    /** A font a document names: what its text layers' `f` refers to, and the family to look for. */
    data class FontEntry(val name: String, val family: String, val style: String, val path: String?)

    fun fonts(root: JsonObject): List<FontEntry> =
        (root.obj("fonts")?.get("list") as? JsonArray).orEmpty().mapNotNull { element ->
            val font = element as? JsonObject ?: return@mapNotNull null
            val name = font.string("fName") ?: return@mapNotNull null
            FontEntry(
                name = name,
                family = font.string("fFamily") ?: name,
                style = font.string("fStyle").orEmpty(),
                path = font.string("fPath")?.takeIf { it.isNotBlank() },
            )
        }

    /** An image asset: what a photo slot replaces, or a picture the template ships. */
    data class ImageAsset(val id: String, val width: Int, val height: Int, val file: String?, val embedded: Boolean)

    fun imageAssets(root: JsonObject): List<ImageAsset> =
        (root["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
            val asset = element as? JsonObject ?: return@mapNotNull null
            if (asset["layers"] != null) return@mapNotNull null
            val id = asset.string("id") ?: return@mapNotNull null
            val path = asset.string("p") ?: return@mapNotNull null
            ImageAsset(
                id = id,
                width = asset.int("w") ?: 0,
                height = asset.int("h") ?: 0,
                file = path.takeUnless { it.startsWith("data:") },
                embedded = path.startsWith("data:") || asset.int("e") == 1,
            )
        }

    /**
     * [root] with every layer passed through [transform], in the top-level
     * layers and in every precomp. [transform] returns the replacement, or the
     * layer itself to keep it. The tree is rebuilt only where something
     * changed.
     */
    fun mapLayers(root: JsonObject, transform: (JsonObject) -> JsonObject): JsonObject {
        fun mapArray(layers: JsonArray?): JsonArray? {
            layers ?: return null
            var changed = false
            val mapped = layers.map { element ->
                val layer = element as? JsonObject ?: return@map element
                val next = transform(layer)
                if (next !== layer) changed = true
                next
            }
            return if (changed) JsonArray(mapped) else layers
        }
        var out = root
        val layers = root["layers"] as? JsonArray
        val newLayers = mapArray(layers)
        if (newLayers !== layers && newLayers != null) out = out.with("layers", newLayers)
        val assets = root["assets"] as? JsonArray
        if (assets != null) {
            var changed = false
            val mapped = assets.map { element ->
                val asset = element as? JsonObject ?: return@map element
                val inner = asset["layers"] as? JsonArray ?: return@map asset
                val next = mapArray(inner)
                if (next !== inner && next != null) {
                    changed = true
                    asset.with("layers", next)
                } else {
                    asset
                }
            }
            if (changed) out = out.with("assets", JsonArray(mapped))
        }
        return out
    }

    /** [layer] with [edit] applied to every keyframe of its text document. */
    fun mapDocs(layer: JsonObject, edit: (JsonObject) -> JsonObject): JsonObject {
        val t = layer.obj("t") ?: return layer
        val d = t.obj("d") ?: return layer
        val keys = d["k"] as? JsonArray ?: return layer
        val mapped = keys.map { element ->
            val keyframe = element as? JsonObject ?: return@map element
            val s = keyframe.obj("s") ?: return@map keyframe
            keyframe.with("s", edit(s))
        }
        return layer.with("t", t.with("d", d.with("k", JsonArray(mapped))))
    }

    /** The document of a text layer's first keyframe. */
    fun firstDoc(layer: JsonObject): JsonObject? =
        ((layer.obj("t")?.obj("d")?.get("k") as? JsonArray)?.firstOrNull() as? JsonObject)?.obj("s")

    fun readDoc(s: JsonObject): TextDoc {
        val size = s.float("s") ?: DEFAULT_TEXT_SIZE
        val box = s["sz"] as? JsonArray
        val pos = s["ps"] as? JsonArray
        return TextDoc(
            text = s.string("t").orEmpty(),
            size = size,
            font = s.string("f").orEmpty(),
            justify = s.int("j") ?: 0,
            allCaps = s.int("ca") == 1,
            lineHeight = s.float("lh") ?: size * DEFAULT_LINE_HEIGHT,
            tracking = s.float("tr") ?: 0f,
            boxWidth = box?.getOrNull(0)?.floatValue(),
            boxHeight = box?.getOrNull(1)?.floatValue(),
            boxX = pos?.getOrNull(0)?.floatValue(),
            boxY = pos?.getOrNull(1)?.floatValue(),
            resize = s.int("rs"),
            minFont = s.float("mf"),
            maxFont = s.float("xf"),
            maxLines = s.int("ml")?.takeIf { it > 0 },
            verticalAlign = s.int("vj")?.let { if (it >= VISUAL_TOP) it - VISUAL_TOP else it },
            fill = (s["fc"] as? JsonArray)?.mapNotNull { it.floatValue() },
        )
    }

    /** A document's text cut at its line breaks: AE writes `\r`, some exporters `\n` or ETX. */
    fun splitLines(text: String): List<String> = text.split('\r', '\n', '\u0003')

    // ---- small JSON helpers, shared with the other files here ------------

    internal fun JsonObject.with(key: String, value: JsonElement): JsonObject =
        JsonObject(LinkedHashMap(this).also { it[key] = value })

    internal fun JsonObject.without(key: String): JsonObject =
        if (key in this) JsonObject(LinkedHashMap(this).also { it.remove(key) }) else this

    internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun JsonObject.float(key: String): Float? = this[key]?.floatValue()

    internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.floatOrNull?.toInt() }

    internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } }

    internal fun JsonElement.floatValue(): Float? = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.floatOrNull

    internal fun num(value: Number): JsonPrimitive = JsonPrimitive(value)

    /**
     * [value] with `"ty"` moved to the front of every object in it. Lottie
     * Android reads a shape item as a stream and skips every key until it
     * meets `"ty"`, so whatever comes before it (a path's points, a fill's
     * colour, a group's items) is lost and the shape draws nothing. Other
     * players read the whole object, so a drawing can look right everywhere
     * else and be empty here.
     */
    fun typeFirst(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> {
            val type = value["ty"]
            val rest = value.filterKeys { it != "ty" }.mapValues { typeFirst(it.value) }
            JsonObject(if (type == null) rest else linkedMapOf<String, JsonElement>("ty" to type).apply { putAll(rest) })
        }
        is JsonArray -> JsonArray(value.map(::typeFirst))
        else -> value
    }

    internal fun nums(vararg values: Number): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private const val DEFAULT_SIZE = 512
    private const val DEFAULT_FPS = 30f
    private const val DEFAULT_TEXT_SIZE = 72f
    private const val DEFAULT_LINE_HEIGHT = 1.2f
    private const val MS_PER_SECOND = 1000f

    /** `vj` 3..5 are the "visual" top/centre/bottom, measured on the ink rather than the line box. */
    private const val VISUAL_TOP = 3
}

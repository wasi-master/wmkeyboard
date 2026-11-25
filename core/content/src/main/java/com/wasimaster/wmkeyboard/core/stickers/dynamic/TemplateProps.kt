package com.wasimaster.wmkeyboard.core.stickers.dynamic

import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.floatValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a template document says about itself, and what can be read off it
 * when it says nothing.
 *
 * Templates made for text stickers carry their rules in
 * `metadata.customProps`: `keyword-map` (phrases, `;`-separated, `*` for
 * "any text"), `concept-map` (feelings and things, same shape),
 * `required-lines` (how many `_txt_N` layers the words are split over),
 * `lineN-char-min`/`-max` and `lineN-font-min`/`-max` per line, and
 * `alt-text`. Hand-written ones vary: keys turn up with a stray space or an
 * underscore (`required_lines `), and numbers as strings. All of those read
 * the same here.
 *
 * A document with no such block (any Lottie file with text in it) still
 * works: each `_txt_N` run becomes one slot over those layers, and every
 * other text layer a slot of its own, so the fill sheet can reach it.
 */
object TemplateProps {

    /** The rules [root] carries, or null when it carries none. */
    data class Props(
        val match: DynMatch,
        val requiredLines: Int,
        val lines: List<LineRule>,
        val altText: String,
    )

    fun read(root: JsonObject): Props? {
        val raw = LottieDoc.customProps(root) ?: return null
        val props = HashMap<String, JsonElement>()
        // Later spellings of a key do not overwrite an earlier clean one.
        for ((key, value) in raw) {
            val clean = key.trim().lowercase().replace('_', '-')
            if (clean !in props || key == clean) props[clean] = value
        }
        val keywords = list(props["keyword-map"])
        val concepts = list(props["concept-map"])
        val required = number(props["required-lines"])?.toInt()?.coerceIn(1, MAX_LINES) ?: 1
        val lines = (0 until required).map { i ->
            LineRule(
                minChars = number(props["line$i-char-min"])?.toInt()?.coerceAtLeast(0) ?: 1,
                maxChars = number(props["line$i-char-max"])?.toInt()?.coerceAtLeast(1) ?: LineRule.DEFAULT_MAX_CHARS,
                minFont = number(props["line$i-font-min"]) ?: 0f,
                maxFont = number(props["line$i-font-max"]) ?: 0f,
            )
        }
        val generic = keywords.any { it == "*" }
        return Props(
            match = DynMatch(
                phrases = keywords.filter { it != "*" },
                concepts = concepts.filter { it != "default" && it != "*" },
                generic = generic,
            ),
            requiredLines = required,
            lines = lines,
            altText = text(props["alt-text"]).orEmpty(),
        )
    }

    /**
     * The slots of [root]: from [props] when it has them, otherwise read off
     * its text layers.
     */
    fun slots(root: JsonObject, props: Props?): List<TextSlot> {
        val layers = LottieDoc.textLayers(root)
        if (layers.isEmpty()) return emptyList()
        val lineLayers = layers
            .mapNotNull { layer -> LottieDoc.LINE_LAYER.matchEntire(layer.name)?.let { it.groupValues[1].toInt() to layer } }
            .sortedBy { it.first }
            .map { it.second }
        val out = ArrayList<TextSlot>()
        if (lineLayers.isNotEmpty()) {
            val count = props?.requiredLines?.coerceAtMost(lineLayers.size) ?: lineLayers.size
            val used = lineLayers.take(count)
            out += TextSlot(
                id = MAIN_SLOT,
                layers = used.map { it.name },
                lines = props?.lines?.take(count) ?: used.map { LineRule() },
                case = if (used.all { it.doc.allCaps }) TextCase.UPPER else null,
                role = SlotRole.MAIN,
                sample = used.joinToString(" ") { it.doc.text.replace('\r', ' ').trim() },
            )
        }
        val taken = out.flatMapTo(HashSet()) { it.layers }
        // The `_txt_N` layers past required-lines stay as the template wrote
        // them; every other named text layer becomes a slot of its own.
        val lineNames = lineLayers.mapTo(HashSet()) { it.name }
        for (layer in layers) {
            if (layer.name in taken || layer.name in lineNames) continue
            out += TextSlot(
                id = layer.name,
                layers = listOf(layer.name),
                lines = listOf(LineRule()),
                case = if (layer.doc.allCaps) TextCase.UPPER else null,
                role = if (out.isEmpty()) SlotRole.MAIN else SlotRole.LABEL,
                label = layer.name.takeUnless { it.startsWith("_") || it.isBlank() }.orEmpty(),
                sample = layer.doc.text.replace('\r', ' ').trim(),
            )
        }
        return out
    }

    /**
     * Photo slots: image assets whose id starts with `photo`, the way the
     * builder names them. Any other picture is the template's own art (and
     * is looked for in the pack's `images/`), never something to ask for.
     */
    fun photos(root: JsonObject): List<PhotoSlot> =
        LottieDoc.imageAssets(root).filter { !it.embedded && it.id.startsWith(PHOTO_PREFIX) }.mapIndexed { i, asset ->
            PhotoSlot(id = asset.id.removePrefix("${PHOTO_PREFIX}_").ifEmpty { asset.id }, asset = asset.id, label = if (i == 0) "" else asset.id)
        }

    private const val PHOTO_PREFIX = "photo"

    /** The id of the slot most messages go into. */
    const val MAIN_SLOT = "main"

    /** More `_txt_N` lines than a sticker ever has; bounds a hostile `required-lines`. */
    private const val MAX_LINES = 8

    private fun list(element: JsonElement?): List<String> {
        val raw = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> element.contentOrNull?.split(';', '\n').orEmpty()
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun number(element: JsonElement?): Float? = when (element) {
        is JsonPrimitive -> element.floatValue() ?: element.contentOrNull?.trim()?.toFloatOrNull()
        else -> null
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull?.trim()
}

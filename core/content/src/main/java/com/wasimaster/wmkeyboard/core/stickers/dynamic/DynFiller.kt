package com.wasimaster.wmkeyboard.core.stickers.dynamic

import com.wasimaster.wmkeyboard.core.stickers.StickerKeywords
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.float
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.obj
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.string
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.with
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.ceil

/**
 * Puts a [DynFill] into a template's document.
 *
 * For each slot, the text is cased, laid over the slot's lines, and written
 * into its layers. How much more is done depends on the engine:
 *
 * - [Engine.EXACT] (Skia's player) fits text itself from the document's
 *   `rs`/`mf`/`xf`/`vj`, so only the words are replaced.
 * - [Engine.LOTTIE] ignores those fields, so the size is fitted here
 *   ([TextFit]), the lines are broken here, and the box is moved for the
 *   vertical alignment the document asks for.
 *
 * The result also reports what each filled layer ended up as, which the
 * renderer needs when it draws a layer's text itself (scripts Lottie cannot
 * shape, see [needsShaping]).
 */
object DynFiller {

    enum class Engine { LOTTIE, EXACT }

    /** What one text layer was filled with. */
    data class FilledLayer(
        val name: String,
        val original: LottieDoc.TextDoc,
        val lines: List<String>,
        val size: Float,
        val font: String,
        /** Fill colour as ARGB, after any override. */
        val color: Long,
        /** How far the box was moved down for vertical alignment, in document pixels. */
        val shiftY: Float,
    ) {
        val text: String get() = lines.joinToString("\r")
    }

    data class Result(val document: JsonObject, val layers: Map<String, FilledLayer>)

    fun fill(
        root: JsonObject,
        template: DynTemplate,
        fill: DynFill,
        engine: Engine,
        measurer: TextFit.TextMeasurer,
        locale: Locale = Locale.getDefault(),
    ): Result {
        val docs = LottieDoc.textLayers(root).associateBy { it.name }
        val planned = HashMap<String, Planned>()
        for (slot in template.slots) {
            val typed = fill.texts[slot.id]?.let(DynText::display)?.takeIf { it.isNotEmpty() } ?: continue
            val layers = slot.layers.filter { it in docs }
            if (layers.isEmpty()) continue
            val lines = if (layers.size == 1) {
                listOf(typed)
            } else {
                val words = typed.split(' ').filter { it.isNotEmpty() }
                TextFit.splitLines(words, layers.size, slot::rule) ?: spread(words, layers.size)
            }
            layers.forEachIndexed { i, name ->
                val info = docs.getValue(name)
                val case = slot.case ?: if (info.doc.allCaps) TextCase.UPPER else null
                planned[name] = Planned(DynText.applyCase(lines.getOrElse(i) { "" }, case, locale), slot.rule(i))
            }
        }
        if (planned.isEmpty() && fill.fontOverride == null) return Result(root, emptyMap())

        val filled = HashMap<String, FilledLayer>()
        var document = LottieDoc.mapLayers(root) { layer ->
            val name = layer.string("nm") ?: return@mapLayers layer
            val plan = planned[name] ?: return@mapLayers layer
            val info = docs[name] ?: return@mapLayers layer
            val font = fill.fontOverride ?: info.doc.font
            val result = if (engine == Engine.EXACT) {
                FilledLayer(name, info.doc, listOf(plan.text), info.doc.size, font, colorOf(info.doc, fill), 0f)
            } else {
                val fitted = TextFit.fit(plan.text, info.doc, plan.rule, measurer, font)
                val shift = verticalShift(info.doc, fitted)
                FilledLayer(name, info.doc, fitted.lines, fitted.size, font, colorOf(info.doc, fill), shift)
            }
            filled[name] = result
            LottieDoc.mapDocs(layer) { doc -> edited(doc, result, engine, fill) }
        }
        fill.fontOverride?.let { document = withFont(document, it) }
        return Result(document, filled)
    }

    /**
     * True when [text] needs shaping Lottie does not do: it draws one
     * character (with its marks) at a time, which breaks Indic conjuncts,
     * Arabic joining, right-to-left order and emoji sequences. Such a layer
     * is drawn by the platform's text stack instead.
     */
    fun needsShaping(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == ZWJ || cp == ZWNJ) return true
            if (isComplex(cp)) return true
            if (Character.getDirectionality(cp) == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                Character.getDirectionality(cp) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) {
                return true
            }
        }
        return StickerKeywords.clusters(text).any(DynText::isPictographic)
    }

    private class Planned(val text: String, val rule: LineRule)

    /** [words] over [count] lines as evenly as can be, when no split meets the lines' rules. */
    private fun spread(words: List<String>, count: Int): List<String> {
        if (words.size <= count) return words + List(count - words.size) { "" }
        val per = ceil(words.size.toFloat() / count).toInt()
        return words.chunked(per).map { it.joinToString(" ") }.let { if (it.size < count) it + List(count - it.size) { "" } else it }
    }

    /**
     * How far to move a box down so text shorter than it sits where the
     * document's `vj` says: Lottie lays box text from the top.
     */
    private fun verticalShift(doc: LottieDoc.TextDoc, fitted: TextFit.Fitted): Float {
        if (!doc.hasBox) return 0f
        val boxH = doc.boxHeight ?: return 0f
        val ratio = doc.lineHeight / doc.size.coerceAtLeast(1f)
        val textH = fitted.size * (1f + (fitted.lines.size - 1) * ratio)
        val spare = (boxH - textH).coerceAtLeast(0f)
        return when (doc.verticalAlign?.coerceIn(0, 2)) {
            1 -> spare / 2f
            2 -> spare
            else -> 0f
        }
    }

    private fun edited(doc: JsonObject, layer: FilledLayer, engine: Engine, fill: DynFill): JsonObject {
        var text = layer.text
        // A typewriter template shows a share of the letters at each step.
        doc.float("wr")?.let { share ->
            val clusters = StickerKeywords.clusters(text)
            text = clusters.take(ceil(clusters.size * share.coerceIn(0f, 1f)).toInt()).joinToString("")
        }
        var out = doc.with("t", JsonPrimitive(text))
        if (engine == Engine.LOTTIE) {
            val scale = layer.size / layer.original.size.coerceAtLeast(1f)
            out = out.with("s", JsonPrimitive(layer.size))
            out = out.with("lh", JsonPrimitive(layer.original.lineHeight * scale))
            // The case is already applied; a second pass would undo a TITLE or KEEP.
            out = out.with("ca", JsonPrimitive(0))
            if (layer.shiftY != 0f) {
                val ps = doc["ps"] as? JsonArray
                val x = ps?.getOrNull(0)?.let { (it as? JsonPrimitive)?.content?.toFloatOrNull() } ?: 0f
                val y = ps?.getOrNull(1)?.let { (it as? JsonPrimitive)?.content?.toFloatOrNull() } ?: 0f
                val boxH = layer.original.boxHeight ?: 0f
                out = out.with("ps", LottieDoc.nums(x, y + layer.shiftY))
                out = out.with("sz", LottieDoc.nums(layer.original.boxWidth ?: 0f, (boxH - layer.shiftY).coerceAtLeast(1f)))
            }
        }
        if (fill.fontOverride != null) out = out.with("f", JsonPrimitive(fill.fontOverride))
        fill.textColor?.let { out = out.with("fc", rgb(it)) }
        return out
    }

    /** [root] with [family] in its fonts list, so text layers that now name it are drawn. */
    private fun withFont(root: JsonObject, family: String): JsonObject {
        val fonts = root.obj("fonts")
        val list = (fonts?.get("list") as? JsonArray).orEmpty()
        if (list.any { (it as? JsonObject)?.string("fName") == family }) return root
        val entry = buildJsonObject {
            put("origin", 0)
            put("fPath", "")
            put("fClass", "")
            put("fFamily", family)
            put("fWeight", "")
            put("fStyle", "Regular")
            put("fName", family)
            put("ascent", DEFAULT_ASCENT)
        }
        val newFonts = (fonts ?: JsonObject(emptyMap())).with("list", JsonArray(list + entry))
        return root.with("fonts", newFonts)
    }

    private fun colorOf(doc: LottieDoc.TextDoc, fill: DynFill): Long {
        fill.textColor?.let { return it }
        val c = doc.fill ?: return BLACK
        fun byte(i: Int) = ((c.getOrNull(i) ?: 0f).coerceIn(0f, 1f) * BYTE_MAX).toLong()
        val a = if (c.size > 3) byte(3) else BYTE_MASK
        return (a shl ALPHA_SHIFT) or (byte(0) shl RED_SHIFT) or (byte(1) shl GREEN_SHIFT) or byte(2)
    }

    private fun rgb(argb: Long): JsonArray = LottieDoc.nums(
        ((argb ushr RED_SHIFT) and BYTE_MASK) / BYTE_MAX,
        ((argb ushr GREEN_SHIFT) and BYTE_MASK) / BYTE_MAX,
        (argb and BYTE_MASK) / BYTE_MAX,
    )

    private const val ZWJ = 0x200D
    private const val ZWNJ = 0x200C
    private const val DEFAULT_ASCENT = 75
    private const val BLACK = 0xFF000000L
    private const val BYTE_MASK = 0xFFL
    private const val BYTE_MAX = 255f
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    /**
     * Devanagari through Sinhala, Thai through Tibetan, Myanmar, Khmer and
     * Mongolian, the Hangul jamo Lottie would draw unjoined, and the Indic
     * extension blocks.
     */
    private fun isComplex(cp: Int): Boolean =
        cp in 0x0900..0x0DFF || cp in 0x0E00..0x0FFF || cp in 0x1000..0x109F ||
            cp in 0x1780..0x18AF || cp in 0x1100..0x11FF || cp in 0xA8E0..0xA8FF || cp in 0xA980..0xA9DF
}

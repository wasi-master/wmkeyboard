package com.wasimaster.wmkeyboard.core.stickers.dynamic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.FontAssetDelegate
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.obj
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.string
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.with
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc.without
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws a template filled with the user's words.
 *
 * [prepare] does the work once per (template, fill): the text is fitted and
 * written in ([DynFiller]), layers whose script Lottie cannot shape are
 * drawn by the platform's text stack into pictures that take their place,
 * photos and the pack's own pictures are decoded, and the document is
 * parsed. The [Prepared] result then draws any frame at any size.
 *
 * Two engines: Lottie, always there, and the exact engine (Skia's player,
 * a download), which draws documents made for it exactly. [exactEngine] is
 * set by the app once that library is installed; [ExactPolicy] says which
 * templates use it.
 *
 * Blocking work throughout; never on the main thread.
 */
class DynRenderer(
    private val store: DynStickerStore,
    private val fonts: DynFonts,
) {

    /** The exact engine, when its library is installed. */
    @Volatile
    var exactEngine: ExactEngine? = null

    enum class ExactPolicy { NEVER, IMPORTED, ALWAYS }

    /** Skia's player behind an interface, so this module does not link it. */
    interface ExactEngine {
        /**
         * Loads [json] with [fonts] (family or font name → file) and
         * [images] (asset id → picture); null when it cannot.
         */
        fun load(json: String, fonts: Map<String, File>, images: Map<String, Bitmap>): ExactAnimation?
    }

    interface ExactAnimation : Closeable {
        /** Draws the frame at [progress] (0..1) into [bitmap], cleared first. */
        fun render(progress: Float, bitmap: Bitmap): Boolean
    }

    /** A template ready to draw, with one fill. Close it to free its pictures. */
    abstract class Prepared(val info: LottieDoc.Info, val template: DynTemplate) : Closeable {
        /** Where the still is taken. */
        val posterProgress: Float get() = template.meta.poster.takeIf { it in 0f..1f } ?: 1f

        val animated: Boolean get() = info.animated && !template.meta.stillOnly

        /** Draws the frame at [progress] into [target] (cleared first), sized to it. */
        abstract fun draw(progress: Float, target: Bitmap)

        /** A new [size]-square bitmap of the frame at [progress]. */
        fun render(progress: Float, size: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            draw(progress, bitmap)
            return bitmap
        }

        /**
         * Calls [onFrame] with every frame at up to [fps], drawn into one
         * reused [size]-square bitmap, and how long each shows. At most
         * [maxFrames]: a longer animation is sampled more sparsely.
         */
        fun forEachFrame(size: Int, fps: Float, maxFrames: Int, onFrame: (Bitmap, Int) -> Unit): Int {
            val sourceFps = info.frameRate.coerceAtLeast(1f)
            val span = info.frames
            val step = max(1f, max(sourceFps / fps, span / maxFrames))
            val durationMs = (step * MS_PER_SECOND / sourceFps).toInt().coerceAtLeast(MIN_FRAME_MS)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            var count = 0
            try {
                var frame = 0f
                while (frame < span && count < maxFrames) {
                    draw((frame / span).coerceIn(0f, 1f), bitmap)
                    onFrame(bitmap, durationMs)
                    count++
                    frame += step
                }
            } finally {
                bitmap.recycle()
            }
            return count
        }

        /** A drawable that plays the animation, for a live preview. Main thread. Null for the exact engine. */
        open fun drawable(): LottieDrawable? = null
    }

    /**
     * [template] filled with [fill]. [photos] maps photo slot ids to
     * pictures the caller decoded; a slot with none shows a placeholder.
     * Null when the template's document cannot be read.
     */
    fun prepare(
        template: DynTemplate,
        fill: DynFill,
        policy: ExactPolicy = ExactPolicy.IMPORTED,
        photos: Map<String, Bitmap> = emptyMap(),
    ): Prepared? {
        val root = store.document(template) ?: return null
        val exact = exactEngine?.takeIf { useExact(template, policy) }
        if (exact != null) prepareExact(exact, root, template, fill, photos)?.let { return it }
        return prepareLottie(root, template, fill, photos)
    }

    private fun useExact(template: DynTemplate, policy: ExactPolicy): Boolean = when (template.meta.renderer) {
        RendererHint.LOTTIE -> false
        RendererHint.EXACT -> policy != ExactPolicy.NEVER
        RendererHint.AUTO -> when (policy) {
            ExactPolicy.NEVER -> false
            ExactPolicy.ALWAYS -> true
            ExactPolicy.IMPORTED -> template.source == PackSource.FEED || template.source == PackSource.ADDON
        }
    }

    private fun prepareExact(engine: ExactEngine, root: JsonObject, template: DynTemplate, fill: DynFill, photos: Map<String, Bitmap>): Prepared? {
        val filled = DynFiller.fill(root, template, fill, DynFiller.Engine.EXACT, fonts.measurer(template.packId))
        val images = photoBitmaps(filled.document, template, photos) + packImages(filled.document, template)
        val fontFiles = HashMap<String, File>()
        LottieDoc.fonts(filled.document).forEach { font ->
            store.fontFile(template.packId, font.name)?.let { fontFiles[font.name] = it }
            store.fontFile(template.packId, font.family)?.let { fontFiles.putIfAbsent(font.family, it) }
        }
        val animation = runCatching { engine.load(LottieDoc.encode(filled.document), fontFiles, images) }.getOrNull()
            ?: return null.also { images.values.forEach(Bitmap::recycle) }
        return object : Prepared(LottieDoc.info(filled.document), template) {
            override fun draw(progress: Float, target: Bitmap) {
                target.eraseColor(Color.TRANSPARENT)
                animation.render(progress, target)
            }

            override fun close() {
                animation.close()
                images.values.forEach(Bitmap::recycle)
            }
        }
    }

    private fun prepareLottie(root: JsonObject, template: DynTemplate, fill: DynFill, photos: Map<String, Bitmap>): Prepared? {
        val filled = DynFiller.fill(root, template, fill, DynFiller.Engine.LOTTIE, fonts.measurer(template.packId))
        var document = filled.document
        val images = HashMap<String, Bitmap>()
        // Layers Lottie would draw a character at a time, mangling the script,
        // become pictures of the same text drawn by the platform.
        for ((name, layer) in filled.layers) {
            if (!DynFiller.needsShaping(layer.text)) continue
            val shaped = shapeText(template.packId, layer) ?: continue
            val assetId = "shaped_$name"
            images[assetId] = shaped.bitmap
            document = rasterize(document, name, assetId, shaped)
        }
        images += photoBitmaps(document, template, photos)
        images += packImages(document, template)
        val json = LottieDoc.encode(document)
        val composition = runCatching { LottieCompositionFactory.fromJsonStringSync(json, null).value }.getOrNull()
        if (composition == null) {
            images.values.forEach(Bitmap::recycle)
            return null
        }
        return LottiePrepared(composition, LottieDoc.info(document), template, images, template.packId)
    }

    private inner class LottiePrepared(
        private val composition: LottieComposition,
        info: LottieDoc.Info,
        template: DynTemplate,
        private val images: Map<String, Bitmap>,
        private val packId: String,
    ) : Prepared(info, template) {

        private val drawable: LottieDrawable by lazy { newDrawable() }

        private fun newDrawable(): LottieDrawable = LottieDrawable().apply {
            // Progress is set by hand and drawn straight after; an update on
            // Lottie's own thread would race the draw.
            asyncUpdates = AsyncUpdates.DISABLED
            setImageAssetDelegate { asset -> images[asset.id] }
            setFontAssetDelegate(object : FontAssetDelegate() {
                override fun fetchFont(fontFamily: String, fontStyle: String, fontName: String): Typeface =
                    fonts.typeface(packId, fontName.ifBlank { fontFamily }, fontStyle)

                override fun fetchFont(fontFamily: String): Typeface = fonts.typeface(packId, fontFamily)
            })
            setComposition(composition)
        }

        @Synchronized
        override fun draw(progress: Float, target: Bitmap) {
            target.eraseColor(Color.TRANSPARENT)
            drawable.setBounds(0, 0, target.width, target.height)
            drawable.progress = progress
            drawable.draw(Canvas(target))
        }

        override fun drawable(): LottieDrawable = newDrawable().apply {
            repeatCount = LottieDrawable.INFINITE
        }

        override fun close() {
            drawable.clearComposition()
            images.values.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    // ---- pictures --------------------------------------------------------

    /** The user's pictures for the template's photo slots, fitted to their assets; placeholders for slots without one. */
    private fun photoBitmaps(root: JsonObject, template: DynTemplate, photos: Map<String, Bitmap>): Map<String, Bitmap> {
        val assets = LottieDoc.imageAssets(root).associateBy { it.id }
        val out = HashMap<String, Bitmap>()
        for (slot in template.meta.photos) {
            val asset = assets[slot.asset] ?: continue
            val w = asset.width.coerceIn(1, MAX_ASSET)
            val h = asset.height.coerceIn(1, MAX_ASSET)
            out[slot.asset] = photos[slot.id]?.let { fitInto(it, w, h, slot.fit) } ?: placeholder(w, h)
        }
        return out
    }

    /** Pictures the pack carries that the document refers to. */
    private fun packImages(root: JsonObject, template: DynTemplate): Map<String, Bitmap> {
        val photoAssets = template.meta.photos.mapTo(HashSet()) { it.asset }
        val out = HashMap<String, Bitmap>()
        for (asset in LottieDoc.imageAssets(root)) {
            if (asset.id in photoAssets || asset.embedded || asset.id.startsWith("shaped_")) continue
            val file = asset.file ?: continue
            val bytes = store.imageBytes(template.packId, file) ?: continue
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { out[asset.id] = it }
        }
        return out
    }

    /** [source] scaled to cover (or fit inside) a [w] × [h] picture. */
    fun fitInto(source: Bitmap, w: Int, h: Int, fit: PhotoFit): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = if (fit == PhotoFit.COVER) {
            max(w.toFloat() / source.width, h.toFloat() / source.height)
        } else {
            minOf(w.toFloat() / source.width, h.toFloat() / source.height)
        }
        val dw = source.width * scale
        val dh = source.height * scale
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        Canvas(out).drawBitmap(source, Rect(0, 0, source.width, source.height), dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** A grey card with a picture mark, where a photo goes before one is picked. */
    private fun placeholder(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(PLACEHOLDER_BACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER_MARK }
        val s = minOf(w, h) * MARK_SHARE
        val cx = w / 2f
        val cy = h / 2f
        // A mountain and a sun: the usual "picture goes here" mark.
        val path = android.graphics.Path().apply {
            moveTo(cx - s, cy + s * HALF)
            lineTo(cx - s * THIRD, cy - s * HALF)
            lineTo(cx + s * THIRD, cy + s * SIXTH)
            lineTo(cx + s * HALF, cy - s * SIXTH)
            lineTo(cx + s, cy + s * HALF)
            close()
        }
        canvas.drawPath(path, paint)
        canvas.drawCircle(cx + s * HALF, cy - s * HALF, s * SIXTH, paint)
        return out
    }

    // ---- text the platform shapes ------------------------------------------

    private class Shaped(val bitmap: Bitmap, val width: Float, val height: Float, val left: Float, val top: Float)

    /**
     * [layer]'s text drawn with the platform's text stack (which shapes every
     * script) into a picture the size of its box, at [SHAPED_SCALE] times
     * the document's resolution so it stays sharp at sticker size.
     */
    private fun shapeText(packId: String, layer: DynFiller.FilledLayer): Shaped? {
        val doc = layer.original
        val typeface = fonts.typeface(packId, layer.font)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = layer.size * SHAPED_SCALE
            letterSpacing = doc.tracking / DynFonts.TRACKING_UNITS
            color = layer.color.toInt()
        }
        val text = layer.lines.joinToString("\n")
        val alignment = when (doc.justify) {
            1 -> Layout.Alignment.ALIGN_OPPOSITE
            2 -> Layout.Alignment.ALIGN_CENTER
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val boxW = if (doc.hasBox) doc.boxWidth!! else layer.lines.maxOf { paint.measureText(it) } / SHAPED_SCALE + PAD
        val widthPx = ceil(boxW * SHAPED_SCALE).toInt().coerceIn(1, MAX_SHAPED)
        val spacing = (doc.lineHeight / doc.size.coerceAtLeast(1f)).coerceIn(MIN_SPACING, MAX_SPACING)
        val staticLayout = layout(text, paint, widthPx, alignment, spacing)
        val boxH = if (doc.hasBox) doc.boxHeight!! else staticLayout.height / SHAPED_SCALE
        val heightPx = ceil(boxH * SHAPED_SCALE).toInt().coerceIn(1, MAX_SHAPED)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val spare = (heightPx - staticLayout.height).coerceAtLeast(0)
        val dy = when (doc.verticalAlign?.coerceIn(0, 2)) {
            1 -> spare / 2f
            2 -> spare.toFloat()
            else -> 0f
        }
        canvas.translate(0f, dy)
        staticLayout.draw(canvas)
        val (left, top) = if (doc.hasBox) {
            (doc.boxX ?: 0f) to (doc.boxY ?: 0f)
        } else {
            // Point text hangs from its baseline at the layer's origin.
            val ascent = -paint.fontMetrics.ascent / SHAPED_SCALE
            val x = when (doc.justify) {
                1 -> -boxW
                2 -> -boxW / 2f
                else -> 0f
            }
            x to -ascent
        }
        return Shaped(bitmap, boxW, boxH, left, top)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment, spacing: Float): StaticLayout =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(alignment)
                .setLineSpacing(0f, spacing)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, alignment, spacing, 0f, false)
        }

    /**
     * [root] with the text layer [name] turned into an image layer showing
     * [shaped], placed where the text was: same transform, with the anchor
     * moved by where the picture's corner sits in the text layer's space.
     */
    private fun rasterize(root: JsonObject, name: String, assetId: String, shaped: Shaped): JsonObject {
        val withLayer = LottieDoc.mapLayers(root) { layer ->
            if (layer.string("nm") != name) return@mapLayers layer
            val ks = layer.obj("ks") ?: return@mapLayers layer
            val anchor = ks["a"] as? JsonObject
            val moved = anchor?.let { shiftAnchor(it, -shaped.left, -shaped.top) }
                ?: buildJsonObject {
                    put("a", 0)
                    put("k", LottieDoc.nums(-shaped.left, -shaped.top, 0))
                }
            layer.without("t")
                .with("ty", JsonPrimitive(LottieDoc.TYPE_IMAGE))
                .with("refId", JsonPrimitive(assetId))
                .with("ks", ks.with("a", moved))
        }
        val asset = buildJsonObject {
            put("id", assetId)
            put("w", shaped.width)
            put("h", shaped.height)
            put("u", "")
            put("p", "$assetId.png")
            put("e", 0)
        }
        val assets = (withLayer["assets"] as? JsonArray).orEmpty()
        return withLayer.with("assets", JsonArray(assets + asset))
    }

    /** An anchor property, static or keyframed, moved by ([dx], [dy]). */
    private fun shiftAnchor(property: JsonObject, dx: Float, dy: Float): JsonObject {
        fun shift(value: JsonElement?): JsonElement? {
            val array = value as? JsonArray ?: return value
            if (array.isEmpty() || array[0] !is JsonPrimitive) return value
            val x = (array.getOrNull(0) as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
            val y = (array.getOrNull(1) as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
            return JsonArray(listOf(JsonPrimitive(x + dx), JsonPrimitive(y + dy)) + array.drop(2))
        }
        val k = property["k"]
        val keyframed = (k as? JsonArray)?.firstOrNull() is JsonObject
        return if (!keyframed) {
            property.with("k", shift(k) ?: k ?: JsonArray(emptyList()))
        } else {
            property.with("k", JsonArray((k as JsonArray).map { element ->
                val key = element as? JsonObject ?: return@map element
                var next = key
                shift(key["s"])?.let { next = next.with("s", it) }
                shift(key["e"])?.let { next = next.with("e", it) }
                next
            }))
        }
    }

    companion object {
        private const val MS_PER_SECOND = 1000f
        private const val MIN_FRAME_MS = 10
        private const val MAX_ASSET = 2048
        private const val MAX_SHAPED = 4096
        private const val SHAPED_SCALE = 2f
        private const val PAD = 8f
        private const val MIN_SPACING = 0.6f
        private const val MAX_SPACING = 3f
        private const val PLACEHOLDER_BACK = 0xFFE0E0E0.toInt()
        private const val PLACEHOLDER_MARK = 0xFFB0B0B0.toInt()
        private const val MARK_SHARE = 0.22f
        private const val HALF = 0.5f
        private const val THIRD = 0.33f
        private const val SIXTH = 0.17f
    }
}

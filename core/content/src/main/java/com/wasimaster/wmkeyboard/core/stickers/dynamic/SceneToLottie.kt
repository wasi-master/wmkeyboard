package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Compiles a [DynScene] into a Lottie document.
 *
 * Every element becomes one layer: text a text layer, a photo or a picture an
 * image layer, a shape or an art drawing a shape layer. The first element is
 * at the bottom, as in the builder; Lottie lists layers top first, so the
 * order is reversed on the way out.
 *
 * Motion is baked into keyframes: each preset is a function of the frame,
 * several on one element add up, and the sum is sampled every
 * [SAMPLE_STEP] frames into linear keyframes. That way any mix of presets
 * plays the same in every player, without expressions.
 *
 * Text layers carry the auto-fit fields Skia's player reads (`rs`, `mf`,
 * `xf`, `ml`, `vj`), and the document carries the matching rules in
 * `metadata.customProps` as well as in our own manifest, so a template
 * exported from the builder works in other apps that read that block.
 */
object SceneToLottie {

    /** A drawing from the art library: Lottie shape items laid out in a [width] × [height] box, origin at its top left. */
    class Art(val width: Float, val height: Float, val shapes: JsonArray)

    data class Compiled(val document: JsonObject, val slots: List<TextSlot>, val photos: List<PhotoSlot>)

    /** The `nm` of the image asset a photo slot's picture replaces. */
    fun photoAssetId(slot: String): String = "photo_$slot"

    fun compile(
        scene: DynScene,
        name: String = "",
        match: DynMatch? = null,
        altText: String = "",
        art: (String) -> Art? = { null },
    ): Compiled {
        val frames = scene.frames.coerceIn(1, MAX_FRAMES)
        val ctx = Ctx(scene.width.toFloat(), scene.height.toFloat(), frames)
        val layers = ArrayList<JsonObject>()
        val assets = ArrayList<JsonObject>()
        val fonts = LinkedHashSet<String>()

        // Text slots: elements sharing a slot id are its lines, in order.
        val slotOrder = LinkedHashMap<String, MutableList<SceneElement>>()
        scene.elements.filter { it.type == ElementType.TEXT && it.slot != null }
            .forEach { slotOrder.getOrPut(it.slot!!) { ArrayList() } += it }
        val mainSlot = slotOrder.entries.firstOrNull { (_, els) -> els.first().role == SlotRole.MAIN }?.key
            ?: slotOrder.keys.firstOrNull()
        val layerNames = HashMap<String, String>()
        slotOrder.forEach { (slot, elements) ->
            elements.forEachIndexed { i, el ->
                layerNames[el.id] = if (slot == mainSlot) "_txt_$i" else if (elements.size == 1) slot else "${slot}_$i"
            }
        }

        var index = 1
        for (el in scene.elements) {
            val layer = when (el.type) {
                ElementType.TEXT -> {
                    fonts += el.font
                    textLayer(el, index, layerNames[el.id] ?: "static_${el.id}", ctx)
                }
                ElementType.SHAPE -> shapeLayer(el, index, ctx, shapeItems(el, ctx))
                ElementType.ART -> {
                    val drawing = el.art?.let(art)
                    if (drawing == null) null else shapeLayer(el, index, ctx, artItems(el, drawing, ctx))
                }
                ElementType.PHOTO -> {
                    val id = photoAssetId(el.slot ?: el.id)
                    assets += imageAsset(id, el, ctx, folder = "", file = "$id.png")
                    imageLayer(el, index, id, ctx)
                }
                ElementType.IMAGE -> {
                    val file = el.image
                    if (file == null) {
                        null
                    } else {
                        val id = "img_${el.id}"
                        assets += imageAsset(id, el, ctx, folder = DynPackFile.IMAGES_DIR + "/", file = file)
                        imageLayer(el, index, id, ctx)
                    }
                }
            }
            if (layer != null) {
                layers += layer
                index++
            }
        }
        scene.background?.let { layers += backgroundLayer(it, index, ctx) }

        val slots = slotOrder.map { (slot, elements) ->
            val first = elements.first()
            TextSlot(
                id = if (slot == mainSlot) TemplateProps.MAIN_SLOT else slot,
                layers = elements.map { layerNames.getValue(it.id) },
                lines = elements.map { LineRule(it.minChars, it.maxChars, it.minSize, it.maxSize) },
                case = first.case,
                role = if (slot == mainSlot) SlotRole.MAIN else first.role.takeIf { it != SlotRole.MAIN } ?: SlotRole.LABEL,
                label = first.label,
                sample = elements.joinToString(" ") { it.text },
            )
        }
        val photos = scene.elements.filter { it.type == ElementType.PHOTO }.map { el ->
            val slot = el.slot ?: el.id
            PhotoSlot(id = slot, asset = photoAssetId(slot), label = el.label, fit = el.fit)
        }

        val mainLines = slots.firstOrNull { it.id == TemplateProps.MAIN_SLOT }?.lines.orEmpty()
        val document = buildJsonObject {
            put("v", BODYMOVIN_VERSION)
            put("fr", scene.fps)
            put("ip", 0)
            put("op", frames)
            put("w", scene.width)
            put("h", scene.height)
            put("nm", name)
            put("ddd", 0)
            put("assets", JsonArray(assets))
            put("fonts", buildJsonObject { put("list", JsonArray(fonts.map(::fontEntry))) })
            // Lottie draws the first layer on top.
            put("layers", JsonArray(layers.reversed()))
            put("markers", JsonArray(emptyList()))
            put("metadata", buildJsonObject { put("customProps", customProps(match, mainLines, altText)) })
        }
        return Compiled(document, slots, photos)
    }

    // ---- layers --------------------------------------------------------

    private class Ctx(val width: Float, val height: Float, val frames: Int)

    private fun textLayer(el: SceneElement, index: Int, name: String, ctx: Ctx): JsonObject {
        val boxW = el.w * ctx.width
        val boxH = el.h * ctx.height
        val lines = LottieDoc.splitLines(el.text).size.coerceAtLeast(1)
        val size = el.maxSize.takeIf { it > 0f } ?: (boxH / (1f + (lines - 1) * el.lineHeight) * FILL_SHARE)
        val minSize = el.minSize.takeIf { it > 0f } ?: (size * MIN_SIZE_SHARE)
        val baseDoc = buildJsonObject {
            put("s", size)
            put("f", el.font)
            put("t", DynText.applyCase(el.text, el.case).replace('\n', '\r'))
            put("ca", if (el.case == TextCase.UPPER) 1 else 0)
            put("j", el.align.coerceIn(0, 2))
            put("tr", el.tracking)
            put("lh", size * el.lineHeight)
            put("ls", 0)
            put("fc", rgb(el.fill))
            el.stroke?.takeIf { el.strokeWidth > 0f }?.let {
                put("sc", rgb(it))
                put("sw", el.strokeWidth)
                // Outline under the fill, the way sticker lettering is drawn.
                put("of", false)
            }
            put("sz", LottieDoc.nums(boxW, boxH))
            put("ps", LottieDoc.nums(-boxW / 2f, -boxH / 2f))
            put("rs", 1)
            put("mf", minSize)
            put("xf", size)
            put("ml", el.maxLines.coerceAtLeast(1))
            put("vj", el.vAlign.coerceIn(0, 2))
        }
        val docKeys = textDocKeyframes(el, baseDoc, ctx)
        val text = buildJsonObject {
            put("d", buildJsonObject { put("k", JsonArray(docKeys)) })
            put("p", buildJsonObject { })
            put("m", buildJsonObject {
                put("g", 1)
                put("a", static(LottieDoc.nums(0, 0)))
            })
            put("a", JsonArray(emptyList()))
        }
        return layerBase(el, index, LottieDoc.TYPE_TEXT, name, ctx, anchor = floatArrayOf(0f, 0f)) { put("t", text) }
    }

    /**
     * The text document's keyframes: one, unless the element changes colour
     * or types itself out, in which case the document is repeated with the
     * colour (or the share of letters shown, as `wr`, read when text is
     * filled) at each step. Text documents only hold, so this steps rather
     * than blends; at [TEXT_STEP] frames apart it reads as motion.
     */
    private fun textDocKeyframes(el: SceneElement, base: JsonObject, ctx: Ctx): List<JsonObject> {
        val rainbow = el.animations.firstOrNull { it.preset == AnimPreset.RAINBOW }
        val typewriter = el.animations.firstOrNull { it.preset == AnimPreset.TYPEWRITER }
        if ((rainbow == null && typewriter == null) || ctx.frames <= 1) return listOf(docKey(base, 0))
        val out = ArrayList<JsonObject>()
        var frame = 0
        while (frame < ctx.frames) {
            var doc = base
            if (rainbow != null) {
                val hue = cycle(frame, rainbow) * FULL_TURN
                doc = withKey(doc, "fc", rgb(hsv(hue, RAINBOW_SAT, RAINBOW_VAL)))
            }
            if (typewriter != null) {
                val shown = once(frame, typewriter).coerceIn(0f, 1f)
                doc = withKey(doc, "wr", JsonPrimitive(shown))
            }
            out += docKey(doc, frame)
            frame += TEXT_STEP
        }
        return out
    }

    private fun docKey(doc: JsonObject, frame: Int) = buildJsonObject {
        put("s", doc)
        put("t", frame)
    }

    private fun shapeLayer(el: SceneElement, index: Int, ctx: Ctx, items: List<JsonObject>): JsonObject =
        layerBase(el, index, TYPE_SHAPE, "shape_${el.id}", ctx, anchor = floatArrayOf(0f, 0f)) {
            put("shapes", JsonArray(items))
        }

    private fun imageLayer(el: SceneElement, index: Int, assetId: String, ctx: Ctx): JsonObject {
        val w = el.w * ctx.width
        val h = el.h * ctx.height
        return layerBase(el, index, LottieDoc.TYPE_IMAGE, assetId, ctx, anchor = floatArrayOf(w / 2f, h / 2f)) {
            put("refId", assetId)
            if (el.corner > 0f) {
                put("hasMask", true)
                put("masksProperties", buildJsonArray {
                    add(buildJsonObject {
                        put("inv", false)
                        put("mode", "a")
                        put("pt", static(roundedRectPath(w / 2f, h / 2f, w, h, el.corner)))
                        put("o", static(JsonPrimitive(FULL)))
                        put("x", static(JsonPrimitive(0)))
                        put("nm", "Corners")
                    })
                })
            }
        }
    }

    private fun imageAsset(id: String, el: SceneElement, ctx: Ctx, folder: String, file: String) = buildJsonObject {
        put("id", id)
        put("w", (el.w * ctx.width).toInt().coerceAtLeast(1))
        put("h", (el.h * ctx.height).toInt().coerceAtLeast(1))
        put("u", folder)
        put("p", file)
        put("e", 0)
    }

    private fun backgroundLayer(fill: SceneFill, index: Int, ctx: Ctx): JsonObject {
        val el = SceneElement(id = "background", type = ElementType.SHAPE, w = 1f, h = 1f, fill = fill.color, fill2 = fill.color2)
        val items = listOf(
            group(
                listOf(rect(0f, 0f, ctx.width, ctx.height, 0f), fillItem(el, ctx.width, ctx.height, fill.angle)),
                "background",
            ),
        )
        return layerBase(el, index, TYPE_SHAPE, "background", ctx, anchor = floatArrayOf(0f, 0f)) { put("shapes", JsonArray(items)) }
    }

    /**
     * The fields every layer has, its transform sampled from the element's
     * motion, and whatever [extra] adds for its type.
     */
    private fun layerBase(
        el: SceneElement,
        index: Int,
        type: Int,
        name: String,
        ctx: Ctx,
        anchor: FloatArray,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("ddd", 0)
        put("ind", index)
        put("ty", type)
        put("nm", name)
        put("sr", 1)
        put("ks", transform(el, ctx, anchor))
        put("ao", 0)
        el.shadow?.let { put("ef", JsonArray(listOf(dropShadow(it, el.shadowDistance, el.shadowBlur)))) }
        extra()
        put("ip", 0)
        put("op", ctx.frames)
        put("st", 0)
        put("bm", 0)
    }

    // ---- motion --------------------------------------------------------

    /** What the element's presets do at one frame, on top of where the builder put it. */
    private class Pose {
        var dx = 0f
        var dy = 0f
        var sx = 1f
        var sy = 1f
        var rotation = 0f
        var opacity = 1f
    }

    private fun pose(el: SceneElement, frame: Int, ctx: Ctx): Pose {
        val pose = Pose()
        val unit = min(ctx.width, ctx.height)
        for (anim in el.animations) {
            val k = anim.strength
            when (anim.preset) {
                AnimPreset.POP_IN -> {
                    val u = once(frame, anim)
                    val s = if (u <= 0f) 0f else easeOutBack(u)
                    pose.sx *= s
                    pose.sy *= s
                    pose.opacity *= (u * POP_FADE).coerceIn(0f, 1f)
                }
                AnimPreset.FADE_IN -> pose.opacity *= easeOutCubic(once(frame, anim))
                AnimPreset.ZOOM_IN -> {
                    val u = easeOutCubic(once(frame, anim))
                    val s = 1f + (1f - u) * ZOOM_FROM * k
                    pose.sx *= s
                    pose.sy *= s
                    pose.opacity *= u
                }
                AnimPreset.DROP_IN -> {
                    val u = once(frame, anim)
                    pose.dy += -(1f - easeOutBounce(u)) * ctx.height * DROP_FROM * k
                    pose.opacity *= if (u <= 0f) 0f else 1f
                }
                AnimPreset.SLIDE_IN -> {
                    val u = easeOutCubic(once(frame, anim))
                    val travel = (1f - u) * k
                    when (anim.direction) {
                        1 -> pose.dx += travel * ctx.width
                        2 -> pose.dy += -travel * ctx.height
                        3 -> pose.dy += travel * ctx.height
                        else -> pose.dx += -travel * ctx.width
                    }
                    pose.opacity *= if (once(frame, anim) <= 0f) 0f else 1f
                }
                AnimPreset.BOUNCE -> pose.dy += -abs(sin(PI.toFloat() * cycle(frame, anim))) * unit * BOUNCE_HEIGHT * k
                AnimPreset.WIGGLE -> pose.rotation += sin(TAU * cycle(frame, anim)) * WIGGLE_DEGREES * k
                AnimPreset.FLOAT -> pose.dy += sin(TAU * cycle(frame, anim)) * unit * FLOAT_HEIGHT * k
                AnimPreset.SPIN -> pose.rotation += FULL_TURN * cycle(frame, anim) * (if (anim.direction == 1) -1f else 1f)
                AnimPreset.PULSE -> {
                    val s = 1f + sin(TAU * cycle(frame, anim)) * PULSE_AMOUNT * k
                    pose.sx *= s
                    pose.sy *= s
                }
                AnimPreset.SHAKE -> pose.dx += sin(TAU * SHAKES * cycle(frame, anim)) * unit * SHAKE_WIDTH * k
                AnimPreset.TWINKLE -> {
                    val w = (1f + cos(TAU * cycle(frame, anim))) / 2f
                    pose.opacity *= TWINKLE_FLOOR + (1f - TWINKLE_FLOOR) * w
                    val s = TWINKLE_SCALE + (1f - TWINKLE_SCALE) * w
                    pose.sx *= s
                    pose.sy *= s
                }
                AnimPreset.HEARTBEAT -> {
                    val u = cycle(frame, anim)
                    val beat = max(beat(u, 0f), beat(u, HEARTBEAT_SECOND))
                    val s = 1f + beat * HEARTBEAT_AMOUNT * k
                    pose.sx *= s
                    pose.sy *= s
                }
                AnimPreset.SWING -> pose.rotation += sin(TAU * cycle(frame, anim)) * SWING_DEGREES * k
                AnimPreset.JELLY -> {
                    val w = sin(TAU * cycle(frame, anim)) * JELLY_AMOUNT * k
                    pose.sx *= 1f + w
                    pose.sy *= 1f - w
                }
                AnimPreset.BLINK -> pose.opacity *= if (cycle(frame, anim) < BLINK_ON) 1f else 0f
                AnimPreset.FADE_OUT -> pose.opacity *= 1f - easeInCubic(once(frame, anim))
                // Colour and letters are in the document or the fill, not the transform.
                AnimPreset.RAINBOW, AnimPreset.TYPEWRITER -> Unit
            }
        }
        return pose
    }

    private fun transform(el: SceneElement, ctx: Ctx, anchor: FloatArray): JsonObject {
        val x = el.x * ctx.width
        val y = el.y * ctx.height
        val flip = if (el.flipX) -1f else 1f
        val moves = ctx.frames > 1 && el.animations.any { it.preset != AnimPreset.RAINBOW && it.preset != AnimPreset.TYPEWRITER }
        if (!moves) {
            return transformObject(
                position = static(LottieDoc.nums(x, y, 0)),
                scale = static(LottieDoc.nums(FULL * flip, FULL, FULL)),
                rotation = static(JsonPrimitive(el.rotation)),
                opacity = static(JsonPrimitive(el.opacity * FULL)),
                anchor = anchor,
            )
        }
        val frames = sampleFrames(ctx.frames)
        val poses = frames.map { pose(el, it, ctx) }
        return transformObject(
            position = animated(frames, poses.map { floatArrayOf(x + it.dx, y + it.dy, 0f) }),
            scale = animated(frames, poses.map { floatArrayOf(it.sx * FULL * flip, it.sy * FULL, FULL) }),
            rotation = animated(frames, poses.map { floatArrayOf(el.rotation + it.rotation) }),
            opacity = animated(frames, poses.map { floatArrayOf((el.opacity * it.opacity * FULL).coerceIn(0f, FULL)) }),
            anchor = anchor,
        )
    }

    private fun transformObject(
        position: JsonObject,
        scale: JsonObject,
        rotation: JsonObject,
        opacity: JsonObject,
        anchor: FloatArray,
    ) = buildJsonObject {
        put("o", opacity)
        put("r", rotation)
        put("p", position)
        put("a", static(LottieDoc.nums(anchor[0], anchor[1], 0)))
        put("s", scale)
    }

    /** Frames to sample at: every [SAMPLE_STEP], plus the last. */
    private fun sampleFrames(frames: Int): List<Int> {
        val out = ArrayList<Int>()
        var f = 0
        while (f < frames - 1) {
            out += f
            f += SAMPLE_STEP
        }
        out += frames - 1
        return out.distinct()
    }

    /**
     * An animated property from [values] at [frames], linear between them;
     * a static one when every value is the same.
     */
    private fun animated(frames: List<Int>, values: List<FloatArray>): JsonObject {
        val first = values.first()
        if (values.all { it.contentEquals(first) }) {
            return static(if (first.size == 1) JsonPrimitive(first[0]) else JsonArray(first.map { JsonPrimitive(round(it)) }))
        }
        val keys = frames.indices.map { i ->
            buildJsonObject {
                put("t", frames[i])
                put("s", JsonArray(values[i].map { JsonPrimitive(round(it)) }))
                if (i < frames.lastIndex) {
                    put("o", buildJsonObject {
                        put("x", LottieDoc.nums(0))
                        put("y", LottieDoc.nums(0))
                    })
                    put("i", buildJsonObject {
                        put("x", LottieDoc.nums(1))
                        put("y", LottieDoc.nums(1))
                    })
                }
            }
        }
        return buildJsonObject {
            put("a", 1)
            put("k", JsonArray(keys))
        }
    }

    private fun static(value: JsonElement) = buildJsonObject {
        put("a", 0)
        put("k", value)
    }

    /** Progress 0..1 through a one-off motion, 0 before its delay and 1 after. */
    private fun once(frame: Int, anim: SceneAnimation): Float =
        ((frame - anim.delay).toFloat() / anim.duration.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** Position 0..1 in the current repeat, or [once] when the motion does not loop. */
    private fun cycle(frame: Int, anim: SceneAnimation): Float {
        if (!anim.loop) return once(frame, anim)
        val local = frame - anim.delay
        if (local < 0) return 0f
        val period = anim.duration.coerceAtLeast(1)
        return (local % period).toFloat() / period
    }

    private fun beat(u: Float, at: Float): Float {
        val d = (u - at) / HEARTBEAT_WIDTH
        return if (d < 0f || d > 1f) 0f else sin(PI.toFloat() * d)
    }

    private fun easeOutCubic(u: Float) = 1f - (1f - u).pow(3)
    private fun easeInCubic(u: Float) = u * u * u
    private fun easeOutBack(u: Float): Float {
        val c1 = BACK_OVERSHOOT
        val c3 = c1 + 1f
        return 1f + c3 * (u - 1f).pow(3) + c1 * (u - 1f).pow(2)
    }

    private fun easeOutBounce(t: Float): Float {
        val n1 = BOUNCE_N1
        val d1 = BOUNCE_D1
        return when {
            t < 1f / d1 -> n1 * t * t
            t < 2f / d1 -> (t - 1.5f / d1).let { n1 * it * it + 0.75f }
            t < 2.5f / d1 -> (t - 2.25f / d1).let { n1 * it * it + 0.9375f }
            else -> (t - 2.625f / d1).let { n1 * it * it + 0.984375f }
        }
    }

    // ---- shapes --------------------------------------------------------

    private fun shapeItems(el: SceneElement, ctx: Ctx): List<JsonObject> {
        val w = el.w * ctx.width
        val h = el.h * ctx.height
        val r = min(w, h) / 2f
        // Fill first: Lottie draws a group's first paint on top, so the
        // outline sits under the fill and shows its full width outside it.
        val paint = { listOfNotNull(fillItem(el, w, h), strokeItem(el)) }
        val parts: List<JsonObject> = when (el.shape ?: ShapeKind.RECT) {
            ShapeKind.RECT -> listOf(rect(0f, 0f, w, h, el.corner)) + paint()
            ShapeKind.ELLIPSE -> listOf(ellipse(0f, 0f, w, h)) + paint()
            ShapeKind.STAR -> listOf(star(el.points.coerceIn(3, MAX_POINTS), r, r * el.innerRatio, el.corner)) + paint()
            ShapeKind.SPARKLE -> listOf(star(4, r, r * SPARKLE_INNER, 0f)) + paint()
            ShapeKind.BURST -> listOf(star(el.points.coerceIn(6, MAX_POINTS * 2), r, r * BURST_INNER, 0f)) + paint()
            ShapeKind.TRIANGLE -> listOf(polygon(3, r)) + paint()
            ShapeKind.HEART -> listOf(path(heart(w, h))) + paint()
            ShapeKind.DROP -> listOf(path(drop(w, h))) + paint()
            ShapeKind.LIGHTNING -> listOf(path(polyline(LIGHTNING, w, h))) + paint()
            ShapeKind.BANNER -> listOf(path(polyline(BANNER, w, h))) + paint()
            ShapeKind.CLOUD -> CLOUD.map { (cx, cy, cw, ch) -> ellipse(cx * w, cy * h, cw * w, ch * h) } +
                rect(0f, h * CLOUD_BASE_Y, w * CLOUD_BASE_W, h * CLOUD_BASE_H, h * CLOUD_BASE_H / 2f) + paint()
            ShapeKind.BUBBLE -> listOf(
                rect(0f, -h * BUBBLE_TAIL / 2f, w, h * (1f - BUBBLE_TAIL), min(w, h) * BUBBLE_CORNER),
                path(polyline(BUBBLE_TAIL_POINTS, w, h)),
            ) + paint()
            ShapeKind.LINE -> listOf(rect(0f, 0f, w, max(h, 2f), max(h, 2f) / 2f)) + paint()
            ShapeKind.RING -> listOf(ellipse(0f, 0f, w, h), ringStroke(el, r))
            ShapeKind.RAYS -> {
                val copies = el.points.coerceIn(3, MAX_POINTS * 2)
                listOf(rect(0f, -r * RAY_CENTER, r * RAY_WIDTH, r * RAY_LENGTH, r * RAY_WIDTH / 2f)) + paint() +
                    repeater(copies, FULL_TURN / copies, 0f, 0f)
            }
            ShapeKind.DOTS -> {
                val copies = el.points.coerceIn(2, MAX_POINTS * 2)
                val d = min(h, w / copies)
                val step = if (copies > 1) (w - d) / (copies - 1) else 0f
                listOf(ellipse(-w / 2f + d / 2f, 0f, d, d)) + paint() + repeater(copies, 0f, step, 0f)
            }
            ShapeKind.CONFETTI -> return confetti(el, w, h)
            ShapeKind.RAINBOW -> return rainbow(el, w, h)
        }
        return listOf(group(parts, el.id))
    }

    private fun artItems(el: SceneElement, art: Art, ctx: Ctx): List<JsonObject> {
        val w = el.w * ctx.width
        val h = el.h * ctx.height
        val scale = min(w / art.width.coerceAtLeast(1f), h / art.height.coerceAtLeast(1f)) * FULL
        val items = art.shapes.mapNotNull { it as? JsonObject } +
            transformItem(anchorX = art.width / 2f, anchorY = art.height / 2f, scale = scale)
        return listOf(buildJsonObject {
            put("ty", "gr")
            put("nm", el.art ?: el.id)
            put("it", JsonArray(items))
        })
    }

    private fun confetti(el: SceneElement, w: Float, h: Float): List<JsonObject> {
        val colors = el.palette.ifEmpty { CONFETTI_COLORS }
        val count = (el.points * CONFETTI_PER_POINT).coerceIn(CONFETTI_MIN, CONFETTI_MAX)
        // A fixed sequence, so the same scene always scatters the same way.
        var seed = el.id.hashCode().toLong() and SEED_MASK
        fun next(): Float {
            seed = (seed * LCG_A + LCG_C) and SEED_MASK
            return (seed ushr LCG_SHIFT).toFloat() / LCG_RANGE
        }
        return (0 until count).map { i ->
            val pw = w * CONFETTI_SIZE * (CONFETTI_MIN_SCALE + next())
            val ph = pw * CONFETTI_ASPECT
            val px = (next() - 0.5f) * w
            val py = (next() - 0.5f) * h
            val color = colors[i % colors.size]
            val piece = el.copy(fill = color, fill2 = null, stroke = null)
            buildJsonObject {
                put("ty", "gr")
                put("nm", "confetti_$i")
                put("it", JsonArray(listOf(
                    rect(0f, 0f, pw, ph, ph / 2f),
                    fillItem(piece, pw, ph),
                    transformItem(positionX = px, positionY = py, rotation = next() * FULL_TURN),
                )))
            }
        }
    }

    private fun rainbow(el: SceneElement, w: Float, h: Float): List<JsonObject> {
        val colors = el.palette.ifEmpty { RAINBOW_COLORS }
        val outer = min(w / 2f, h)
        val band = outer / (colors.size + 2)
        val centerY = h / 2f - band / 2f
        return colors.mapIndexed { i, color ->
            val radius = outer - band / 2f - i * band
            buildJsonObject {
                put("ty", "gr")
                put("nm", "band_$i")
                put("it", JsonArray(listOf(
                    ellipse(0f, centerY, radius * 2f, radius * 2f),
                    buildJsonObject {
                        // The top half: an ellipse path starts at twelve o'clock,
                        // so a quarter turn back starts it at nine.
                        put("ty", "tm")
                        put("s", static(JsonPrimitive(0)))
                        put("e", static(JsonPrimitive(HALF_PERCENT)))
                        put("o", static(JsonPrimitive(-QUARTER_TURN)))
                        put("m", 1)
                    },
                    strokeOf(color, band),
                    transformItem(),
                )))
            }
        }
    }

    // ---- shape items ---------------------------------------------------

    private fun group(items: List<JsonObject>, name: String) = buildJsonObject {
        put("ty", "gr")
        put("nm", name)
        put("it", JsonArray(items + transformItem()))
    }

    private fun transformItem(
        positionX: Float = 0f,
        positionY: Float = 0f,
        anchorX: Float = 0f,
        anchorY: Float = 0f,
        scale: Float = FULL,
        rotation: Float = 0f,
    ) = buildJsonObject {
        put("ty", "tr")
        put("p", static(LottieDoc.nums(positionX, positionY)))
        put("a", static(LottieDoc.nums(anchorX, anchorY)))
        put("s", static(LottieDoc.nums(scale, scale)))
        put("r", static(JsonPrimitive(rotation)))
        put("o", static(JsonPrimitive(FULL)))
        put("sk", static(JsonPrimitive(0)))
        put("sa", static(JsonPrimitive(0)))
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float, corner: Float) = buildJsonObject {
        put("ty", "rc")
        put("d", 1)
        put("p", static(LottieDoc.nums(x, y)))
        put("s", static(LottieDoc.nums(w, h)))
        put("r", static(JsonPrimitive(corner.coerceAtMost(min(w, h) / 2f))))
    }

    private fun ellipse(x: Float, y: Float, w: Float, h: Float) = buildJsonObject {
        put("ty", "el")
        put("d", 1)
        put("p", static(LottieDoc.nums(x, y)))
        put("s", static(LottieDoc.nums(w, h)))
    }

    private fun star(points: Int, outer: Float, inner: Float, roundness: Float) = buildJsonObject {
        put("ty", "sr")
        put("sy", 1)
        put("d", 1)
        put("pt", static(JsonPrimitive(points)))
        put("p", static(LottieDoc.nums(0, 0)))
        put("r", static(JsonPrimitive(0)))
        put("ir", static(JsonPrimitive(inner)))
        put("is", static(JsonPrimitive(0)))
        put("or", static(JsonPrimitive(outer)))
        put("os", static(JsonPrimitive(roundness.coerceIn(0f, FULL))))
    }

    private fun polygon(points: Int, radius: Float) = buildJsonObject {
        put("ty", "sr")
        put("sy", 2)
        put("d", 1)
        put("pt", static(JsonPrimitive(points)))
        put("p", static(LottieDoc.nums(0, 0)))
        put("r", static(JsonPrimitive(0)))
        put("or", static(JsonPrimitive(radius)))
        put("os", static(JsonPrimitive(0)))
    }

    private fun repeater(copies: Int, rotation: Float, stepX: Float, stepY: Float) = buildJsonObject {
        put("ty", "rp")
        put("c", static(JsonPrimitive(copies)))
        put("o", static(JsonPrimitive(0)))
        put("m", 1)
        put("tr", buildJsonObject {
            put("ty", "tr")
            put("p", static(LottieDoc.nums(stepX, stepY)))
            put("a", static(LottieDoc.nums(0, 0)))
            put("s", static(LottieDoc.nums(FULL, FULL)))
            put("r", static(JsonPrimitive(rotation)))
            put("so", static(JsonPrimitive(FULL)))
            put("eo", static(JsonPrimitive(FULL)))
        })
    }

    /** A cubic path: vertices with their in and out tangents, relative to each vertex. */
    private class Path(val v: List<FloatArray>, val i: List<FloatArray>, val o: List<FloatArray>, val closed: Boolean = true)

    private fun path(p: Path) = buildJsonObject {
        put("ty", "sh")
        put("ks", static(pathValue(p)))
    }

    private fun pathValue(p: Path) = buildJsonObject {
        put("i", JsonArray(p.i.map { LottieDoc.nums(round(it[0]), round(it[1])) }))
        put("o", JsonArray(p.o.map { LottieDoc.nums(round(it[0]), round(it[1])) }))
        put("v", JsonArray(p.v.map { LottieDoc.nums(round(it[0]), round(it[1])) }))
        put("c", p.closed)
    }

    private fun polyline(points: List<Pair<Float, Float>>, w: Float, h: Float): Path {
        val zero = points.map { floatArrayOf(0f, 0f) }
        return Path(points.map { (x, y) -> floatArrayOf(x * w, y * h) }, zero, zero)
    }

    private fun heart(w: Float, h: Float) = Path(
        v = listOf(p(0f, 0.5f, w, h), p(-0.5f, -0.12f, w, h), p(0f, -0.28f, w, h), p(0.5f, -0.12f, w, h)),
        i = listOf(p(0.22f, -0.12f, w, h), p(0f, 0.3f, w, h), p(-0.05f, -0.27f, w, h), p(0f, -0.38f, w, h)),
        o = listOf(p(-0.22f, -0.12f, w, h), p(0f, -0.38f, w, h), p(0.05f, -0.27f, w, h), p(0f, 0.3f, w, h)),
    )

    private fun drop(w: Float, h: Float) = Path(
        v = listOf(p(0f, -0.5f, w, h), p(0.4f, 0.15f, w, h), p(0f, 0.5f, w, h), p(-0.4f, 0.15f, w, h)),
        i = listOf(p(-0.15f, 0.2f, w, h), p(0f, -0.2f, w, h), p(0.22f, 0f, w, h), p(0f, 0.2f, w, h)),
        o = listOf(p(0.15f, 0.2f, w, h), p(0f, 0.2f, w, h), p(-0.22f, 0f, w, h), p(0f, -0.2f, w, h)),
    )

    private fun p(x: Float, y: Float, w: Float, h: Float) = floatArrayOf(x * w, y * h)

    /** A rounded rectangle as a path, for masks (which take paths only). Centre [cx], [cy]. */
    private fun roundedRectPath(cx: Float, cy: Float, w: Float, h: Float, corner: Float): JsonObject {
        val r = corner.coerceIn(0f, min(w, h) / 2f)
        val k = r * KAPPA
        val l = cx - w / 2f
        val t = cy - h / 2f
        val rt = cx + w / 2f
        val b = cy + h / 2f
        return pathValue(
            Path(
                v = listOf(
                    floatArrayOf(l + r, t), floatArrayOf(rt - r, t), floatArrayOf(rt, t + r), floatArrayOf(rt, b - r),
                    floatArrayOf(rt - r, b), floatArrayOf(l + r, b), floatArrayOf(l, b - r), floatArrayOf(l, t + r),
                ),
                i = listOf(
                    floatArrayOf(-k, 0f), floatArrayOf(0f, 0f), floatArrayOf(0f, -k), floatArrayOf(0f, 0f),
                    floatArrayOf(k, 0f), floatArrayOf(0f, 0f), floatArrayOf(0f, k), floatArrayOf(0f, 0f),
                ),
                o = listOf(
                    floatArrayOf(0f, 0f), floatArrayOf(k, 0f), floatArrayOf(0f, 0f), floatArrayOf(0f, k),
                    floatArrayOf(0f, 0f), floatArrayOf(-k, 0f), floatArrayOf(0f, 0f), floatArrayOf(0f, -k),
                ),
            ),
        )
    }

    private fun fillItem(el: SceneElement, w: Float, h: Float, angle: Float = GRADIENT_ANGLE): JsonObject {
        val second = el.fill2
        val color = el.fill
        if (second == null) {
            return buildJsonObject {
                put("ty", "fl")
                put("c", colourValue(color, el))
                put("o", static(JsonPrimitive(alpha(color) * FULL)))
                put("r", 1)
            }
        }
        val rad = angle * PI.toFloat() / HALF_TURN
        val dx = cos(rad) * w / 2f
        val dy = sin(rad) * h / 2f
        val a = rgb(color)
        val b = rgb(second)
        return buildJsonObject {
            put("ty", "gf")
            put("o", static(JsonPrimitive(alpha(color) * FULL)))
            put("r", 1)
            put("t", 1)
            put("s", static(LottieDoc.nums(-dx, -dy)))
            put("e", static(LottieDoc.nums(dx, dy)))
            put("g", buildJsonObject {
                put("p", 2)
                put("k", static(JsonArray(listOf(JsonPrimitive(0)) + a.take(3) + JsonPrimitive(1) + b.take(3))))
            })
        }
    }

    /** A fill colour, cycling through the rainbow when the element asks for it. */
    private fun colourValue(color: Long, el: SceneElement): JsonObject {
        val rainbow = el.animations.firstOrNull { it.preset == AnimPreset.RAINBOW }
        if (rainbow == null) return static(rgba(color))
        val frames = (0..rainbow.duration step TEXT_STEP).map { rainbow.delay + it }
        val hues = frames.map { ((it - rainbow.delay).toFloat() / rainbow.duration.coerceAtLeast(1)) * FULL_TURN }
        val keys = frames.indices.map { i ->
            buildJsonObject {
                put("t", frames[i])
                put("s", rgba(hsv(hues[i], RAINBOW_SAT, RAINBOW_VAL)))
                if (i < frames.lastIndex) {
                    put("o", buildJsonObject {
                        put("x", LottieDoc.nums(0))
                        put("y", LottieDoc.nums(0))
                    })
                    put("i", buildJsonObject {
                        put("x", LottieDoc.nums(1))
                        put("y", LottieDoc.nums(1))
                    })
                }
            }
        }
        return buildJsonObject {
            put("a", 1)
            put("k", JsonArray(keys))
        }
    }

    private fun strokeItem(el: SceneElement): JsonObject? {
        val color = el.stroke ?: return null
        if (el.strokeWidth <= 0f) return null
        return strokeOf(color, el.strokeWidth)
    }

    private fun ringStroke(el: SceneElement, r: Float) =
        strokeOf(el.fill, el.strokeWidth.takeIf { it > 0f } ?: (r * RING_WIDTH))

    private fun strokeOf(color: Long, width: Float) = buildJsonObject {
        put("ty", "st")
        put("c", static(rgba(color)))
        put("o", static(JsonPrimitive(alpha(color) * FULL)))
        put("w", static(JsonPrimitive(width)))
        put("lc", 2)
        put("lj", 2)
        put("ml", MITER_LIMIT)
    }

    private fun dropShadow(color: Long, distance: Float, blur: Float): JsonObject {
        fun param(name: String, type: Int, value: JsonElement, index: Int) = buildJsonObject {
            put("ty", type)
            put("nm", name)
            put("mn", "ADBE Drop Shadow-000$index")
            put("ix", index)
            put("v", static(value))
        }
        return buildJsonObject {
            put("ty", DROP_SHADOW_EFFECT)
            put("nm", "Drop Shadow")
            put("mn", "ADBE Drop Shadow")
            put("ix", 1)
            put("en", 1)
            put("ef", JsonArray(listOf(
                param("Shadow Color", 2, rgba(color), 1),
                param("Opacity", 0, JsonPrimitive(alpha(color) * BYTE_MAX), 2),
                param("Direction", 0, JsonPrimitive(SHADOW_DIRECTION), 3),
                param("Distance", 0, JsonPrimitive(distance), 4),
                param("Softness", 0, JsonPrimitive(blur), 5),
            )))
        }
    }

    private fun fontEntry(family: String) = buildJsonObject {
        put("origin", 0)
        put("fPath", "")
        put("fClass", "")
        put("fFamily", family)
        put("fWeight", "")
        put("fStyle", "Regular")
        put("fName", family)
        put("ascent", FONT_ASCENT)
    }

    private fun customProps(match: DynMatch?, lines: List<LineRule>, altText: String) = buildJsonObject {
        val phrases = match?.phrases.orEmpty() + (if (match?.generic == true) listOf("*") else emptyList())
        put("keyword-map", phrases.joinToString(";"))
        put("concept-map", match?.concepts.orEmpty().joinToString(";"))
        put("required-lines", lines.size.coerceAtLeast(1))
        lines.forEachIndexed { i, rule ->
            put("line$i-char-min", rule.minChars)
            put("line$i-char-max", rule.maxChars)
            if (rule.minFont > 0f) put("line$i-font-min", rule.minFont)
            if (rule.maxFont > 0f) put("line$i-font-max", rule.maxFont)
        }
        if (altText.isNotBlank()) put("alt-text", altText)
    }

    // ---- colour ----------------------------------------------------------

    private fun alpha(argb: Long): Float = ((argb ushr ALPHA_SHIFT) and BYTE_MASK).toFloat() / BYTE_MAX

    private fun channels(argb: Long) = listOf(
        ((argb ushr RED_SHIFT) and BYTE_MASK).toFloat() / BYTE_MAX,
        ((argb ushr GREEN_SHIFT) and BYTE_MASK).toFloat() / BYTE_MAX,
        (argb and BYTE_MASK).toFloat() / BYTE_MAX,
    )

    /** `[r, g, b]` 0..1, for text documents. */
    private fun rgb(argb: Long): JsonArray = JsonArray(channels(argb).map { JsonPrimitive(round3(it)) })

    /** `[r, g, b, 1]`, for shape colours (opacity is its own property). */
    private fun rgba(argb: Long): JsonArray = JsonArray(channels(argb).map { JsonPrimitive(round3(it)) } + JsonPrimitive(1))

    private fun hsv(hueDegrees: Float, s: Float, v: Float): Long {
        val h = ((hueDegrees % FULL_TURN) + FULL_TURN) % FULL_TURN / SIXTY
        val c = v * s
        val x = c * (1f - abs(h % 2f - 1f))
        val m = v - c
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(f: Float) = ((f + m) * BYTE_MAX).toLong().coerceIn(0, BYTE_MASK)
        return (BYTE_MASK shl ALPHA_SHIFT) or (byte(r) shl RED_SHIFT) or (byte(g) shl GREEN_SHIFT) or byte(b)
    }

    private fun withKey(obj: JsonObject, key: String, value: JsonElement) = JsonObject(LinkedHashMap(obj).also { it[key] = value })

    private fun round(f: Float): Float = kotlin.math.round(f * HUNDRED) / HUNDRED
    private fun round3(f: Float): Float = kotlin.math.round(f * THOUSAND) / THOUSAND

    // ---- constants -------------------------------------------------------

    private const val BODYMOVIN_VERSION = "5.7.14"
    private const val TYPE_SHAPE = 4
    private const val MAX_FRAMES = 600
    private const val SAMPLE_STEP = 2
    private const val TEXT_STEP = 3
    private const val FULL = 100f
    private const val FULL_TURN = 360f
    private const val HALF_TURN = 180f
    private const val QUARTER_TURN = 90f
    private const val HALF_PERCENT = 50f
    private const val SIXTY = 60f
    private const val TAU = 6.2831855f
    private const val HUNDRED = 100f
    private const val THOUSAND = 1000f

    private const val FILL_SHARE = 0.8f
    private const val MIN_SIZE_SHARE = 0.25f
    private const val FONT_ASCENT = 75

    private const val POP_FADE = 3f
    private const val ZOOM_FROM = 1.2f
    private const val DROP_FROM = 0.7f
    private const val BOUNCE_HEIGHT = 0.06f
    private const val FLOAT_HEIGHT = 0.025f
    private const val WIGGLE_DEGREES = 8f
    private const val SWING_DEGREES = 14f
    private const val PULSE_AMOUNT = 0.07f
    private const val SHAKES = 3f
    private const val SHAKE_WIDTH = 0.02f
    private const val TWINKLE_FLOOR = 0.3f
    private const val TWINKLE_SCALE = 0.75f
    private const val HEARTBEAT_SECOND = 0.22f
    private const val HEARTBEAT_WIDTH = 0.16f
    private const val HEARTBEAT_AMOUNT = 0.14f
    private const val JELLY_AMOUNT = 0.08f
    private const val BLINK_ON = 0.7f
    private const val BACK_OVERSHOOT = 1.70158f
    private const val BOUNCE_N1 = 7.5625f
    private const val BOUNCE_D1 = 2.75f

    private const val MAX_POINTS = 16
    private const val SPARKLE_INNER = 0.22f
    private const val BURST_INNER = 0.8f
    private const val RAY_CENTER = 0.62f
    private const val RAY_WIDTH = 0.09f
    private const val RAY_LENGTH = 0.55f
    private const val RING_WIDTH = 0.12f
    private const val BUBBLE_TAIL = 0.2f
    private const val BUBBLE_CORNER = 0.25f
    private const val CLOUD_BASE_Y = 0.18f
    private const val CLOUD_BASE_W = 0.9f
    private const val CLOUD_BASE_H = 0.42f
    private const val CONFETTI_PER_POINT = 3
    private const val CONFETTI_MIN = 6
    private const val CONFETTI_MAX = 40
    private const val CONFETTI_SIZE = 0.06f
    private const val CONFETTI_MIN_SCALE = 0.6f
    private const val CONFETTI_ASPECT = 0.45f
    private const val KAPPA = 0.5523f
    private const val MITER_LIMIT = 4
    private const val GRADIENT_ANGLE = 90f
    private const val DROP_SHADOW_EFFECT = 25
    private const val SHADOW_DIRECTION = 135f
    private const val RAINBOW_SAT = 0.75f
    private const val RAINBOW_VAL = 1f

    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFFL
    private const val BYTE_MAX = 255f

    private const val SEED_MASK = 0xFFFFFFFFFFFFL
    private const val LCG_A = 0x5DEECE66DL
    private const val LCG_C = 0xBL
    private const val LCG_SHIFT = 24
    private const val LCG_RANGE = 16777216f

    /** Cloud puffs: centre x, centre y, width, height, as shares of the box. */
    private val CLOUD = listOf(
        floatArrayOf(-0.24f, 0.08f, 0.5f, 0.56f),
        floatArrayOf(0.04f, -0.1f, 0.56f, 0.72f),
        floatArrayOf(0.27f, 0.08f, 0.46f, 0.56f),
    )

    private val LIGHTNING = listOf(
        0.12f to -0.5f, -0.3f to 0.06f, -0.02f to 0.06f, -0.12f to 0.5f, 0.3f to -0.08f, 0.02f to -0.08f,
    )
    private val BANNER = listOf(
        -0.5f to -0.3f, 0.5f to -0.3f, 0.4f to 0f, 0.5f to 0.3f, -0.5f to 0.3f, -0.4f to 0f,
    )
    private val BUBBLE_TAIL_POINTS = listOf(-0.22f to 0.28f, -0.04f to 0.28f, -0.3f to 0.5f)

    private val RAINBOW_COLORS = listOf(0xFFE53935L, 0xFFFB8C00L, 0xFFFDD835L, 0xFF43A047L, 0xFF1E88E5L, 0xFF8E24AAL)
    private val CONFETTI_COLORS = listOf(0xFFE91E63L, 0xFFFFC107L, 0xFF03A9F4L, 0xFF8BC34AL, 0xFF9C27B0L, 0xFFFF5722L)
}

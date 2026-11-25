package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The builder's source for a template: elements on a canvas, each with its
 * look and its motion, compiled to a Lottie document by [SceneToLottie].
 *
 * Positions and sizes are fractions of the canvas, so a scene can be drawn at
 * any size; font sizes, stroke widths and shadow distances are in canvas
 * pixels at [DynScene.width]. Colours are ARGB as `Long`, the way the rest of
 * the app stores them.
 *
 * The built-in templates are scenes too, so every one of them opens in the
 * builder and can be copied and changed.
 */
@Serializable
data class DynScene(
    val version: Int = 1,
    val width: Int = DEFAULT_SIZE,
    val height: Int = DEFAULT_SIZE,
    val fps: Int = DEFAULT_FPS,
    /** Length in frames; 1 makes a still. */
    val frames: Int = DEFAULT_FRAMES,
    /** Null for a transparent canvas, which is what a sticker wants. */
    val background: SceneFill? = null,
    val elements: List<SceneElement> = emptyList(),
) {
    val animated: Boolean get() = frames > 1

    companion object {
        const val DEFAULT_SIZE = 512
        const val DEFAULT_FPS = 30
        const val DEFAULT_FRAMES = 90
    }
}

/** A solid colour, or a linear gradient from [color] to [color2] at [angle] degrees. */
@Serializable
data class SceneFill(
    val color: Long = WHITE,
    val color2: Long? = null,
    val angle: Float = 90f,
) {
    companion object {
        const val WHITE = 0xFFFFFFFFL
        const val BLACK = 0xFF000000L
    }
}

@Serializable
enum class ElementType {
    /** Words: typed text when [SceneElement.slot] is set, fixed words when not. */
    @SerialName("text")
    TEXT,

    /** The user's own picture. */
    @SerialName("photo")
    PHOTO,

    /** A shape drawn from parameters ([ShapeKind]). */
    @SerialName("shape")
    SHAPE,

    /** A drawing from the art library (emoji-style pictures as vector shapes). */
    @SerialName("art")
    ART,

    /** A picture the pack carries, under `images/`. */
    @SerialName("image")
    IMAGE,
}

@Serializable
enum class ShapeKind {
    @SerialName("rect") RECT,
    @SerialName("ellipse") ELLIPSE,
    @SerialName("star") STAR,
    @SerialName("sparkle") SPARKLE,
    @SerialName("heart") HEART,
    @SerialName("burst") BURST,
    @SerialName("rays") RAYS,
    @SerialName("confetti") CONFETTI,
    @SerialName("rainbow") RAINBOW,
    @SerialName("cloud") CLOUD,
    @SerialName("bubble") BUBBLE,
    @SerialName("banner") BANNER,
    @SerialName("triangle") TRIANGLE,
    @SerialName("line") LINE,
    @SerialName("ring") RING,
    @SerialName("dots") DOTS,
    @SerialName("drop") DROP,
    @SerialName("lightning") LIGHTNING,
}

/** Motion an element can have; several combine. */
@Serializable
enum class AnimPreset {
    /** Grows from nothing past its size and settles. */
    @SerialName("pop_in") POP_IN,
    @SerialName("fade_in") FADE_IN,
    @SerialName("zoom_in") ZOOM_IN,
    /** Falls in from above and bounces. */
    @SerialName("drop_in") DROP_IN,
    /** Slides in from a side ([SceneAnimation.direction]: 0 left, 1 right, 2 top, 3 bottom). */
    @SerialName("slide_in") SLIDE_IN,
    @SerialName("bounce") BOUNCE,
    @SerialName("wiggle") WIGGLE,
    @SerialName("float") FLOAT,
    @SerialName("spin") SPIN,
    @SerialName("pulse") PULSE,
    @SerialName("shake") SHAKE,
    @SerialName("twinkle") TWINKLE,
    @SerialName("heartbeat") HEARTBEAT,
    @SerialName("swing") SWING,
    /** Squashes and stretches. */
    @SerialName("jelly") JELLY,
    /** Cycles its colour through the rainbow. */
    @SerialName("rainbow") RAINBOW,
    /** Text appears a letter at a time. */
    @SerialName("typewriter") TYPEWRITER,
    @SerialName("blink") BLINK,
    @SerialName("fade_out") FADE_OUT,
}

/**
 * One motion on one element. [delay] and [duration] are in frames. An
 * entrance ([AnimPreset.POP_IN] and the other `_IN`s) runs once; the rest
 * repeat every [duration] frames when [loop] is set, and run once otherwise.
 */
@Serializable
data class SceneAnimation(
    val preset: AnimPreset,
    val delay: Int = 0,
    val duration: Int = DEFAULT_DURATION,
    val loop: Boolean = true,
    /** 1 is the preset's own amount; 0.5 half as much, 2 twice. */
    val strength: Float = 1f,
    val direction: Int = 0,
) {
    companion object {
        const val DEFAULT_DURATION = 15
    }
}

/** One thing on the canvas. Which fields matter depends on [type]. */
@Serializable
data class SceneElement(
    val id: String,
    val type: ElementType,
    /** Centre, as fractions of the canvas. */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Size, as fractions of the canvas. */
    val w: Float = 0.8f,
    val h: Float = 0.3f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val flipX: Boolean = false,

    // ---- text and photo slots ----------------------------------------
    /** The slot typed text (or a picture) goes into; null for fixed words. Elements sharing a slot are its lines, in order. */
    val slot: String? = null,
    val role: SlotRole = SlotRole.MAIN,
    val label: String = "",
    /** Fixed words, or the sample a slot shows before anything is typed. */
    val text: String = "",
    val font: String = DEFAULT_FONT,
    /** Size bounds in canvas pixels; 0 lets the box decide. */
    val minSize: Float = 0f,
    val maxSize: Float = 0f,
    /** 0 left, 1 right, 2 centre. */
    val align: Int = ALIGN_CENTER,
    /** 0 top, 1 centre, 2 bottom. */
    val vAlign: Int = 1,
    val case: TextCase = TextCase.UPPER,
    /** Line height as a multiple of the size. */
    val lineHeight: Float = 1.1f,
    /** Thousandths of an em. */
    val tracking: Float = 0f,
    val maxLines: Int = 3,
    val minChars: Int = 1,
    val maxChars: Int = LineRule.DEFAULT_MAX_CHARS,

    // ---- look --------------------------------------------------------
    val fill: Long = SceneFill.WHITE,
    /** A second colour makes [fill] a gradient (shapes and art only; text stays solid). */
    val fill2: Long? = null,
    val stroke: Long? = null,
    val strokeWidth: Float = 0f,
    val shadow: Long? = null,
    val shadowDistance: Float = 0f,
    val shadowBlur: Float = 0f,

    // ---- shapes, art, pictures --------------------------------------
    val shape: ShapeKind? = null,
    val points: Int = 5,
    /** Inner radius of a star as a share of its outer one. */
    val innerRatio: Float = 0.45f,
    val corner: Float = 0f,
    /** Extra colours, for confetti and rainbows. */
    val palette: List<Long> = emptyList(),
    val art: String? = null,
    val image: String? = null,
    val fit: PhotoFit = PhotoFit.COVER,

    val animations: List<SceneAnimation> = emptyList(),
) {
    companion object {
        const val DEFAULT_FONT = "DynaPuff"
        const val ALIGN_CENTER = 2
    }
}

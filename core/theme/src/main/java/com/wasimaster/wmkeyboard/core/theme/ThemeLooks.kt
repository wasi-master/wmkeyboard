package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.Serializable

/*
 * Ways a theme can look like more than flat colour: what the keys are made
 * of ([KeyMaterialSpec]), what moves on the board behind them
 * ([BoardEffectSpec]), how they light up ([KeyLightingSpec]) and how the board
 * answers the finger ([TouchBloomSpec]). Hand-drawn key art is [KeySkinSpec]
 * in KeySkin.kt.
 *
 * Every kind travels as a string rather than an enum, for the reason spelled
 * out above [PhotoAttribution]: a kind from a later build costs that one field
 * on an older one, never the theme. Each spec is a nested nullable field on
 * [ThemeSpec], so a build from before it imports the theme without it.
 */

// ---- key materials ----------------------------------------------------------

/**
 * What a key is made of. Each kind is a preset of the same few primitives
 * (a raised cap, a texture, an edge, a sheen, a label treatment), and every
 * primitive can be set on its own in [KeyMaterialSpec], so a material is a
 * starting point rather than a fixed look.
 */
enum class KeyMaterialKind {
    /** No preset: only what the spec sets itself. */
    CUSTOM,
    /** A raised keycap: a darker skirt below a lit top face. */
    CAP,
    METAL,
    WOOD,
    STONE,
    STITCHED,
    LEATHER,
    CARBON,
    PAPER,
    LETTERPRESS,
    NEON,
    HOLO,
    TERMINAL,
    CARTOON,
    GLASS,
    CANDY,
    CERAMIC,
    RESIN,
    ARCADE,
    TYPEWRITER,
    INSTRUMENT,
    MOLTEN,
    /**
     * Soft plastic pressed out of the board itself: the key is the board's own
     * colour, lifted by a light shadow on one side and a dark one on the other
     * (neumorphism). After Expressive Keyboard's Neumorphism design.
     */
    NEUMORPH,
    /** A blocky game button: hard-edged light and shade bands that swap when pressed. */
    PIXEL,
    /** Labels made of dots, as on a dot-matrix display, with a dotted outline. */
    DOT_MATRIX,
}

/** A procedural tile laid over the key face. Drawn in greys, so it takes the key's own colour. */
enum class MaterialTexture {
    NONE, NOISE, LINEN, DOTS, SCANLINES, BRUSHED, WOOD, STONE, PAPER, CARBON, LEATHER,
    /** Square cells of light and shade, like a game block's texture. */
    PIXELS,
    /** Boards with dark seams between them and a few knots. */
    PLANKS,
}

/** What happens at a key's edge. */
enum class MaterialEdge {
    NONE,
    /** A dashed thread just inside the outline. */
    STITCH,
    /** A coloured outline with a soft glow around it: neon tube, molten rim. */
    GLOW,
    /** A heavy ink outline with a hard shadow under it: cartoon. */
    INK,
    /** Light along the top edge and shade along the bottom: glass, glaze, resin. */
    RIM,
    /** A shadow pressed into the top edge: letterpress, debossed paper. */
    INSET,
    /** A second outline inside the first: typewriter rings, instrument bezels. */
    DOUBLE,
    /** An outline of round dots. */
    DOTTED,
    /**
     * Hard light bands along the top and left and shade along the bottom and
     * right, snapped to whole pixels; a press swaps them, so the key reads as
     * pushed in. The game-block button.
     */
    BEVEL,
}

/** How a key's label is drawn on the material. */
enum class MaterialLabel {
    NONE, GLOW, DEBOSS, EMBOSS, SHADOW,
    /** The label drawn as a grid of dots, as on a dot-matrix display. */
    DOTS,
}

/**
 * A key material. [kind] picks the preset; every other field left null takes
 * the preset's value, and one that is set overrides it. Colours left null are
 * derived from the key's own fill and the theme's accent where it is drawn.
 */
@Serializable
data class KeyMaterialSpec(
    /** A [KeyMaterialKind] name. */
    val kind: String = KeyMaterialKind.CAP.name,
    /** How far the cap stands above its skirt, in dp; 0 is a flat key. */
    val bevelDp: Float? = null,
    /** How far the top face sits in from the skirt's sides, in dp. */
    val topInsetDp: Float? = null,
    /** How much darker the skirt is than the face, 0..0.6. */
    val skirtShade: Float? = null,
    /** Specular highlight on the face, 0..1. */
    val gloss: Float? = null,
    /** Where the light comes from, -60..60 degrees off straight above. */
    val lightAngleDeg: Float? = null,
    /** A [MaterialTexture] name. */
    val texture: String? = null,
    val textureOpacity: Float? = null,
    /** A [MaterialEdge] name. */
    val edge: String? = null,
    val edgeColor: Long? = null,
    val edgeWidthDp: Float? = null,
    /** A [MaterialLabel] name. */
    val label: String? = null,
    /** The label effect's colour; null derives one. */
    val labelColor: Long? = null,
    /** Iridescent rainbow sheen across the face, 0..1. */
    val iridescence: Float? = null,
    /**
     * A soft light shadow up and to the left of the key and a dark one down
     * and to the right, 0..1: what lifts a neumorphic key off a board of its
     * own colour. A press flattens it.
     */
    val softShadow: Float? = null,
)

/** A [KeyMaterialSpec] with its preset applied and every range enforced. */
data class ResolvedMaterial(
    val bevelDp: Float,
    val topInsetDp: Float,
    val skirtShade: Float,
    val gloss: Float,
    val lightAngleDeg: Float,
    val texture: MaterialTexture,
    val textureOpacity: Float,
    val edge: MaterialEdge,
    val edgeColor: Long?,
    val edgeWidthDp: Float,
    val label: MaterialLabel,
    val labelColor: Long?,
    val iridescence: Float,
    val softShadow: Float = 0f,
) {
    /** Nothing to draw: the plain key the theme would have had without a material. */
    val isPlain: Boolean
        get() = bevelDp == 0f && gloss == 0f && (texture == MaterialTexture.NONE || textureOpacity == 0f) &&
            edge == MaterialEdge.NONE && label == MaterialLabel.NONE && iridescence == 0f && softShadow == 0f
}

val MATERIAL_BEVEL_RANGE = 0f..8f
val MATERIAL_INSET_RANGE = 0f..6f
val MATERIAL_SKIRT_RANGE = 0f..0.6f
val MATERIAL_GLOSS_RANGE = 0f..1f
val MATERIAL_LIGHT_RANGE = -60f..60f
val MATERIAL_TEXTURE_OPACITY_RANGE = 0f..1f
val MATERIAL_EDGE_WIDTH_RANGE = 0.5f..6f
val MATERIAL_IRIDESCENCE_RANGE = 0f..1f
val MATERIAL_SOFT_SHADOW_RANGE = 0f..1f

fun keyMaterialKindOrNull(name: String?): KeyMaterialKind? =
    name?.let { wanted -> KeyMaterialKind.entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) } }

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { wanted -> enumValues<E>().firstOrNull { it.name.equals(wanted, ignoreCase = true) } }

fun materialTextureOrNull(name: String?): MaterialTexture? = enumOrNull<MaterialTexture>(name)
fun materialEdgeOrNull(name: String?): MaterialEdge? = enumOrNull<MaterialEdge>(name)
fun materialLabelOrNull(name: String?): MaterialLabel? = enumOrNull<MaterialLabel>(name)

/** The preset each kind starts from. CUSTOM is all zeros, so its spec is the whole look. */
fun materialPreset(kind: KeyMaterialKind): ResolvedMaterial {
    val base = ResolvedMaterial(
        bevelDp = 0f, topInsetDp = 0f, skirtShade = 0.25f, gloss = 0f, lightAngleDeg = 0f,
        texture = MaterialTexture.NONE, textureOpacity = 0f, edge = MaterialEdge.NONE, edgeColor = null,
        edgeWidthDp = 1.5f, label = MaterialLabel.NONE, labelColor = null, iridescence = 0f,
    )
    return when (kind) {
        KeyMaterialKind.CUSTOM -> base
        KeyMaterialKind.CAP -> base.copy(bevelDp = 3f, topInsetDp = 1.5f, gloss = 0.18f)
        KeyMaterialKind.METAL -> base.copy(
            bevelDp = 1f, gloss = 0.32f, texture = MaterialTexture.BRUSHED, textureOpacity = 0.4f,
            edge = MaterialEdge.RIM, label = MaterialLabel.EMBOSS,
        )
        KeyMaterialKind.WOOD -> base.copy(
            bevelDp = 1.5f, gloss = 0.08f, texture = MaterialTexture.WOOD, textureOpacity = 0.5f,
            edge = MaterialEdge.RIM, label = MaterialLabel.DEBOSS,
        )
        KeyMaterialKind.STONE -> base.copy(
            bevelDp = 2f, topInsetDp = 1f, skirtShade = 0.3f, texture = MaterialTexture.STONE,
            textureOpacity = 0.5f, label = MaterialLabel.DEBOSS,
        )
        KeyMaterialKind.STITCHED -> base.copy(
            texture = MaterialTexture.LINEN, textureOpacity = 0.35f, edge = MaterialEdge.STITCH, edgeWidthDp = 1.2f,
        )
        KeyMaterialKind.LEATHER -> base.copy(
            bevelDp = 1f, gloss = 0.1f, texture = MaterialTexture.LEATHER, textureOpacity = 0.45f,
            edge = MaterialEdge.STITCH, edgeWidthDp = 1.2f,
        )
        KeyMaterialKind.CARBON -> base.copy(
            gloss = 0.2f, texture = MaterialTexture.CARBON, textureOpacity = 0.5f, edge = MaterialEdge.RIM,
        )
        KeyMaterialKind.PAPER -> base.copy(
            texture = MaterialTexture.PAPER, textureOpacity = 0.4f, edge = MaterialEdge.INK, edgeWidthDp = 0.8f,
        )
        KeyMaterialKind.LETTERPRESS -> base.copy(
            texture = MaterialTexture.PAPER, textureOpacity = 0.35f, edge = MaterialEdge.INSET,
            label = MaterialLabel.DEBOSS,
        )
        KeyMaterialKind.NEON -> base.copy(
            edge = MaterialEdge.GLOW, edgeWidthDp = 1.5f, label = MaterialLabel.GLOW,
        )
        KeyMaterialKind.HOLO -> base.copy(gloss = 0.25f, iridescence = 0.55f, edge = MaterialEdge.RIM)
        KeyMaterialKind.TERMINAL -> base.copy(
            texture = MaterialTexture.SCANLINES, textureOpacity = 0.35f, label = MaterialLabel.GLOW,
        )
        KeyMaterialKind.CARTOON -> base.copy(
            gloss = 0.2f, edge = MaterialEdge.INK, edgeWidthDp = 2.5f,
        )
        KeyMaterialKind.GLASS -> base.copy(gloss = 0.35f, edge = MaterialEdge.RIM, edgeWidthDp = 1.5f)
        KeyMaterialKind.CANDY -> base.copy(
            bevelDp = 2f, topInsetDp = 1f, skirtShade = 0.2f, gloss = 0.55f, edge = MaterialEdge.RIM,
        )
        KeyMaterialKind.CERAMIC -> base.copy(
            bevelDp = 1.5f, gloss = 0.4f, texture = MaterialTexture.NOISE, textureOpacity = 0.08f,
            edge = MaterialEdge.RIM,
        )
        KeyMaterialKind.RESIN -> base.copy(
            gloss = 0.45f, texture = MaterialTexture.NOISE, textureOpacity = 0.15f, edge = MaterialEdge.RIM,
        )
        KeyMaterialKind.ARCADE -> base.copy(
            bevelDp = 4f, topInsetDp = 3f, skirtShade = 0.35f, gloss = 0.3f, edge = MaterialEdge.DOUBLE,
        )
        KeyMaterialKind.TYPEWRITER -> base.copy(
            bevelDp = 3f, topInsetDp = 2f, gloss = 0.22f, edge = MaterialEdge.DOUBLE, edgeWidthDp = 2f,
            texture = MaterialTexture.NOISE, textureOpacity = 0.1f,
        )
        KeyMaterialKind.INSTRUMENT -> base.copy(
            bevelDp = 2f, topInsetDp = 1f, gloss = 0.2f, texture = MaterialTexture.BRUSHED,
            textureOpacity = 0.25f, edge = MaterialEdge.DOUBLE, edgeWidthDp = 1f,
        )
        KeyMaterialKind.MOLTEN -> base.copy(
            bevelDp = 1.5f, texture = MaterialTexture.STONE, textureOpacity = 0.4f, edge = MaterialEdge.GLOW,
            edgeColor = 0xFFFF7A1A, edgeWidthDp = 2f, label = MaterialLabel.GLOW, labelColor = 0xFFFFA040,
        )
        KeyMaterialKind.NEUMORPH -> base.copy(lightAngleDeg = 45f, softShadow = 0.7f)
        KeyMaterialKind.PIXEL -> base.copy(
            texture = MaterialTexture.PIXELS, textureOpacity = 0.55f, edge = MaterialEdge.BEVEL, edgeWidthDp = 3f,
            label = MaterialLabel.SHADOW, labelColor = 0xFF3F3F3F,
        )
        KeyMaterialKind.DOT_MATRIX -> base.copy(
            edge = MaterialEdge.DOTTED, edgeWidthDp = 1.2f, label = MaterialLabel.DOTS,
        )
    }
}

/** [spec] over its preset, with every range enforced. */
fun resolveMaterial(spec: KeyMaterialSpec): ResolvedMaterial {
    val preset = materialPreset(keyMaterialKindOrNull(spec.kind) ?: KeyMaterialKind.CUSTOM)
    return ResolvedMaterial(
        bevelDp = (spec.bevelDp ?: preset.bevelDp).coerceIn(MATERIAL_BEVEL_RANGE),
        topInsetDp = (spec.topInsetDp ?: preset.topInsetDp).coerceIn(MATERIAL_INSET_RANGE),
        skirtShade = (spec.skirtShade ?: preset.skirtShade).coerceIn(MATERIAL_SKIRT_RANGE),
        gloss = (spec.gloss ?: preset.gloss).coerceIn(MATERIAL_GLOSS_RANGE),
        lightAngleDeg = (spec.lightAngleDeg ?: preset.lightAngleDeg).coerceIn(MATERIAL_LIGHT_RANGE),
        texture = materialTextureOrNull(spec.texture) ?: preset.texture,
        textureOpacity = (spec.textureOpacity ?: preset.textureOpacity).coerceIn(MATERIAL_TEXTURE_OPACITY_RANGE),
        edge = materialEdgeOrNull(spec.edge) ?: preset.edge,
        edgeColor = spec.edgeColor ?: preset.edgeColor,
        edgeWidthDp = (spec.edgeWidthDp ?: preset.edgeWidthDp).coerceIn(MATERIAL_EDGE_WIDTH_RANGE),
        label = materialLabelOrNull(spec.label) ?: preset.label,
        labelColor = spec.labelColor ?: preset.labelColor,
        iridescence = (spec.iridescence ?: preset.iridescence).coerceIn(MATERIAL_IRIDESCENCE_RANGE),
        softShadow = (spec.softShadow ?: preset.softShadow).coerceIn(MATERIAL_SOFT_SHADOW_RANGE),
    )
}

// ---- board effects ----------------------------------------------------------

/**
 * Something that moves on the board behind the keys, or, for the kinds marked
 * [onPress], a splash that a key press throws over the keys and that is gone
 * again a second later. The press kinds draw nothing while nobody types, run
 * no clock between presses and, having no still picture, draw nothing at all
 * under reduce motion or power saving.
 */
enum class BoardEffectKind(val onPress: Boolean = false) {
    SNOW,
    RAIN,
    CODE_RAIN,
    BOKEH,
    STARFIELD,
    WARP,
    FIREFLIES,
    MOTES,
    EMBERS,
    BUBBLES,
    CONFETTI,
    FIREWORKS,
    AURORA,
    NEBULA,
    LAVA,
    WAVES,
    SMOKE,
    DOT_GRID,
    CONTOUR,
    EQUALIZER,
    SCAN,
    SHEEN,
    RIPPLES,

    /**
     * Big soft blobs of colour that drift slowly behind the keys, the colour
     * pools Expressive Keyboard lays under its glass. Speed 0 holds them still.
     */
    ORBS,

    /** A splat of paint with flung drops and drips running down. */
    PAINT(onPress = true),

    /** A soft, uneven blot of ink that blooms out and fades. */
    INK(onPress = true),

    /** A round pool of soft light a few keys wide. */
    LIGHT_POOL(onPress = true),

    /** A wash of colour that spreads from the key across the whole board. */
    FLOOD(onPress = true),

    /** A puff of smoke that drifts up and thins out. */
    PUFF(onPress = true),

    /** A four-point glint of light on the key. */
    GLINT(onPress = true),

    /** A ring that pops outward and throws small bits. */
    POP(onPress = true),

    /** Short curved strokes that sweep round the key. */
    SWOOSH(onPress = true),

    /** A scatter of tiny specks round the key. */
    SPECKS(onPress = true),

    /**
     * The pressed key reaching out to its neighbours in glassy bridges, like
     * drops of liquid running together, and letting go as it is released.
     * After Expressive Keyboard's glass merge. Android 13 and later draw it
     * with a shader; older phones a plainer bridge.
     */
    MELD(onPress = true),
}

/**
 * A moving board. [colors] are one to four ARGB colours; empty takes the
 * theme's accent and label colour, except on a press kind, where empty gives
 * every press a colour of its own. [reactive] is how much typing drives it:
 * each press adds energy that speeds the effect up and brightens it, and the
 * energy halves every [halfLifeMs].
 */
@Serializable
data class BoardEffectSpec(
    /** A [BoardEffectKind] name. */
    val kind: String,
    val colors: List<Long> = emptyList(),
    /** How many things: flakes, stars, bubbles. 0..1. */
    val density: Float = 0.5f,
    /** 0..3, 1 being the effect's own pace. */
    val speed: Float = 1f,
    /** Opacity ceiling, 0..1. */
    val intensity: Float = 0.6f,
    /** Size of the things, 0.5..2. */
    val scale: Float = 1f,
    /** How much typing drives it, 0..1; 0 ignores the keys. */
    val reactive: Float = 0f,
    val halfLifeMs: Int = 1200,
    /**
     * Where the [BoardEffectKind.ORBS] pools sit, one spot per colour in
     * [colors] by index. Empty scatters them as the effect always has; set,
     * the pools rest at these places and [speed] only sways them round there.
     * Other kinds ignore it.
     */
    val orbSpots: List<OrbSpot> = emptyList(),
)

/**
 * One colour pool's place on the board: its centre as fractions of the
 * board's width and height (a little past either edge is allowed, so a pool
 * can hang off the board), and its radius as a fraction of the board's longer
 * side. Held to [ORB_SPOT_POSITION_RANGE] and [ORB_SPOT_RADIUS_RANGE].
 */
@Serializable
data class OrbSpot(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val radius: Float = 0.3f,
)

val ORB_SPOT_POSITION_RANGE = -0.2f..1.2f
val ORB_SPOT_RADIUS_RANGE = 0.05f..0.8f

/**
 * Spots for [count] pools spread across the board, the starting point the
 * editor hands out when the user first places them by hand.
 */
fun defaultOrbSpots(count: Int): List<OrbSpot> = List(count.coerceIn(1, MAX_BOARD_EFFECT_COLORS)) { i ->
    val n = count.coerceIn(1, MAX_BOARD_EFFECT_COLORS)
    OrbSpot(
        x = (i + 0.5f) / n,
        y = if (i % 2 == 0) 0.3f else 0.7f,
        radius = 0.35f,
    )
}

val BOARD_EFFECT_DENSITY_RANGE = 0f..1f
val BOARD_EFFECT_SPEED_RANGE = 0f..3f
val BOARD_EFFECT_INTENSITY_RANGE = 0f..1f
val BOARD_EFFECT_SCALE_RANGE = 0.5f..2f
val BOARD_EFFECT_REACTIVE_RANGE = 0f..1f
val BOARD_EFFECT_HALF_LIFE_RANGE = 300..4000

/** Most colours an effect cycles through. */
const val MAX_BOARD_EFFECT_COLORS = 4

fun boardEffectKindOrNull(name: String?): BoardEffectKind? = enumOrNull<BoardEffectKind>(name)

// ---- key lighting -----------------------------------------------------------

/**
 * How the keys light up. The first group runs on its own; the second answers
 * key presses and goes dark again when the typing stops.
 */
enum class KeyLightingPattern(val reactive: Boolean) {
    STEADY(false),
    BREATHE(false),
    WAVE(false),
    SWEEP(false),
    TWINKLE(false),
    HEARTBEAT(false),
    KEY(true),
    RIPPLE(true),
    ECHO(true),
    SPLASH(true),
    CROSS(true),
    RAIN(true),
}

/** Where the light lands. */
enum class KeyLightingTarget {
    /** A wash over the key face. */
    FACE,
    /** A lit outline round each key. */
    EDGE,
    /** A halo behind each key, showing in the gaps. */
    GLOW,
    /** The key face filled with the colour, the label showing through. */
    FILL,
    /** The key face filled with two tones running corner to corner. */
    GRADIENT,
    /** Twinkling specks of glitter scattered over the key. */
    GLITTER,
}

/**
 * Where the light's colour comes from. [FIXED] is the one colour (turned by
 * the rainbow spread); the others give each press, or on the patterns that
 * run on their own each key, a colour of its own.
 */
enum class KeyLightingColorMode {
    FIXED,
    /** Bright colours, a different one each time. */
    RANDOM,
    /** Soft pale colours, a different one each time. */
    PASTEL,
}

@Serializable
data class KeyLightingSpec(
    /** A [KeyLightingPattern] name. */
    val pattern: String,
    /** A [KeyLightingTarget] name. */
    val target: String = KeyLightingTarget.GLOW.name,
    /** Null takes the theme's accent. */
    val color: Long? = null,
    /** 0..1. */
    val intensity: Float = 0.6f,
    /** Halo reach or outline width, in dp. */
    val sizeDp: Float = 8f,
    /** 0.25..4. */
    val speed: Float = 1f,
    /** How far the hue turns across the board, 0..360; 0 is one colour. */
    val hueSpanDeg: Float = 0f,
    /** How long a press stays lit, for the patterns that answer presses. */
    val decayMs: Int = 700,
    /** A [KeyLightingColorMode] name; null is [KeyLightingColorMode.FIXED]. */
    val colorMode: String? = null,
)

val LIGHTING_INTENSITY_RANGE = 0f..1f
val LIGHTING_SIZE_RANGE = 0f..24f
val LIGHTING_SPEED_RANGE = 0.25f..4f
val LIGHTING_HUE_SPAN_RANGE = 0f..360f
val LIGHTING_DECAY_RANGE = 100..2400

fun keyLightingPatternOrNull(name: String?): KeyLightingPattern? = enumOrNull<KeyLightingPattern>(name)
fun keyLightingTargetOrNull(name: String?): KeyLightingTarget? = enumOrNull<KeyLightingTarget>(name)
fun keyLightingColorModeOrNull(name: String?): KeyLightingColorMode? = enumOrNull<KeyLightingColorMode>(name)

// ---- touch bloom ------------------------------------------------------------

/**
 * The board answering the finger itself rather than the key under it: a soft
 * light blooms out from the touch across the board, and the keys within
 * [radiusDp] of the finger dip under it and spring back with a wobble when it
 * lifts, while the rest of the board stays still. During a glide both follow
 * the finger. The key preview bubble lights up in the same colour.
 *
 * Either half can be turned off on its own: [light] 0 draws no light, [dip] 0
 * keeps the keys still.
 */
@Serializable
data class TouchBloomSpec(
    /** Null takes the theme's accent. */
    val color: Long? = null,
    /** How bright the light from the finger is, 0..1. */
    val light: Float = 0.6f,
    /** How far from the finger the light and the dip reach, in dp. */
    val radiusDp: Float = 96f,
    /** How deep the keys near the finger sink, 0..1. */
    val dip: Float = 0.5f,
    /** How much the keys wobble as they spring back, 0..1; 0 settles with no overshoot. */
    val wobble: Float = 0.5f,
    /**
     * How the keys move, as a [KeyPressMotion] name; null is
     * [KeyPressMotion.SINK], the dip every theme written before the field had.
     */
    val motion: String? = null,
)

/**
 * How a key moves under the finger. [SINK] moves every key within the reach,
 * the nearer the deeper; the rest move only the key under the finger.
 * [TouchBloomSpec.dip] sets how far, [TouchBloomSpec.wobble] how much it rings
 * on the way back.
 */
enum class KeyPressMotion {
    /** The keys near the finger sink a little and drop. */
    SINK,
    /** The key grows larger. */
    GROW,
    /** The key shrinks. */
    SHRINK,
    /** The key lifts up off the board. */
    RISE,
    /** The key tips toward the side the finger landed on. */
    TILT,
    /** The key squashes flat and wide, like jelly. */
    JELLY,
    /** The key grows and hops up off the board. */
    POP,
    /** The key squashes much wider and flatter and sinks, then springs back. */
    SQUISH,
    /** The key fades back and shrinks a little. */
    FADE,
}

fun keyPressMotionOrNull(name: String?): KeyPressMotion? = enumOrNull<KeyPressMotion>(name)

val TOUCH_BLOOM_LIGHT_RANGE = 0f..1f
val TOUCH_BLOOM_RADIUS_RANGE = 40f..240f
val TOUCH_BLOOM_DIP_RANGE = 0f..1f
val TOUCH_BLOOM_WOBBLE_RANGE = 0f..1f

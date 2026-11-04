package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.Serializable

/*
 * Three ways a theme can look like more than flat colour: what the keys are
 * made of ([KeyMaterialSpec]), what moves on the board behind them
 * ([BoardEffectSpec]) and how they light up ([KeyLightingSpec]). A fourth,
 * hand-drawn key art, is [KeySkinSpec] in KeySkin.kt.
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
}

/** A procedural tile laid over the key face. Drawn in greys, so it takes the key's own colour. */
enum class MaterialTexture { NONE, NOISE, LINEN, DOTS, SCANLINES, BRUSHED, WOOD, STONE, PAPER, CARBON, LEATHER }

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
}

/** How a key's label is drawn on the material. */
enum class MaterialLabel { NONE, GLOW, DEBOSS, EMBOSS, SHADOW }

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
) {
    /** Nothing to draw: the plain key the theme would have had without a material. */
    val isPlain: Boolean
        get() = bevelDp == 0f && gloss == 0f && (texture == MaterialTexture.NONE || textureOpacity == 0f) &&
            edge == MaterialEdge.NONE && label == MaterialLabel.NONE && iridescence == 0f
}

val MATERIAL_BEVEL_RANGE = 0f..8f
val MATERIAL_INSET_RANGE = 0f..6f
val MATERIAL_SKIRT_RANGE = 0f..0.6f
val MATERIAL_GLOSS_RANGE = 0f..1f
val MATERIAL_LIGHT_RANGE = -60f..60f
val MATERIAL_TEXTURE_OPACITY_RANGE = 0f..1f
val MATERIAL_EDGE_WIDTH_RANGE = 0.5f..6f
val MATERIAL_IRIDESCENCE_RANGE = 0f..1f

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
    )
}

// ---- board effects ----------------------------------------------------------

/** Something that moves on the board behind the keys. */
enum class BoardEffectKind {
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
}

/**
 * A moving board. [colors] are one to four ARGB colours; empty takes the
 * theme's accent and label colour. [reactive] is how much typing drives it:
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
)

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
)

val LIGHTING_INTENSITY_RANGE = 0f..1f
val LIGHTING_SIZE_RANGE = 0f..24f
val LIGHTING_SPEED_RANGE = 0.25f..4f
val LIGHTING_HUE_SPAN_RANGE = 0f..360f
val LIGHTING_DECAY_RANGE = 100..2400

fun keyLightingPatternOrNull(name: String?): KeyLightingPattern? = enumOrNull<KeyLightingPattern>(name)
fun keyLightingTargetOrNull(name: String?): KeyLightingTarget? = enumOrNull<KeyLightingTarget>(name)

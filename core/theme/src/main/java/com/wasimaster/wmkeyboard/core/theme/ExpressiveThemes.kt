package com.wasimaster.wmkeyboard.core.theme

import com.wasimaster.wmkeyboard.theme.R

/*
 * Built-in themes after the designs of Expressive Keyboard
 * (github.com/kotlalipopus/Expressive-Keyboard, MIT licence), redrawn with
 * this keyboard's own looks: its Neumorphism as Soft, its dot-matrix design
 * as Dot matrix, its pixel-block design as Blocks and its Liquid Glass as
 * Droplet. The colours are taken from that project's skin files
 * (the JSON files in assets/plugins); the names are generic, because the designs it
 * named after other products are only styles here, not imitations of them.
 *
 * Where a colour had to change, the comment says why: this keyboard has no
 * per-key pressed label colour, so a press that darkened a key to the label's
 * own colour is a mid tone instead; and the settings panels draw the key
 * colour flattened to opaque, so a dark glass key is a dark tint, not a
 * white one at low alpha.
 */

// ---- Soft ---------------------------------------------------------------
// The board, the keys and the action keys share one colour; a light shadow
// up and to the left and a dark one down and to the right lift the keys out
// of it, and a press flattens them into it.

private val SoftDark: ThemeSpec = ThemeSpec(
    id = "builtin_soft_dark",
    name = "Soft dark",
    dark = true,
    boardBackground = 0xFF262A32,
    keyBackground = 0xFF292D35,
    keyGradient = GradientSpec(listOf(0x14FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 135f),
    keyText = 0xFFDDE2EC,
    modifierKeyBackground = 0xFF262A32,
    modifierKeyText = 0xFFA0A8BA,
    enterKeyBackground = 0xFF7C8EFF,
    enterKeyText = 0xFFFFFFFF,
    pressedKeyBackground = 0xFF1F2329,
    accent = 0xFF7C8EFF,
    popupBackground = 0xFF30353F,
    popupText = 0xFFE6E9F0,
    popupSelectedBackground = 0xFF7C8EFF,
    popupSelectedText = 0xFFFFFFFF,
    toolCircleBackground = 0xFF2C3039,
    chipBackground = 0xFF2C3039,
    suggestionText = 0xFFDDE2EC,
    keyCornerRadiusDp = 16,
    keyMaterial = KeyMaterialSpec(kind = KeyMaterialKind.NEUMORPH.name, softShadow = 0.85f),
)

private val SoftTheme: ThemeSpec = ThemeSpec(
    id = "builtin_soft",
    name = "Soft",
    dark = false,
    boardBackground = 0xFFE6EAF1,
    keyBackground = 0xFFE9EDF3,
    keyGradient = GradientSpec(listOf(0x59FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 135f),
    keyText = 0xFF39404F,
    modifierKeyBackground = 0xFFE6EAF1,
    modifierKeyText = 0xFF68718A,
    enterKeyBackground = 0xFF6B7FF0,
    enterKeyText = 0xFFFFFFFF,
    pressedKeyBackground = 0xFFD9DEE7,
    accent = 0xFF6B7FF0,
    popupBackground = 0xFFF1F3F8,
    popupText = 0xFF39404F,
    popupSelectedBackground = 0xFF6B7FF0,
    popupSelectedText = 0xFFFFFFFF,
    toolCircleBackground = 0xFFE9EDF3,
    chipBackground = 0xFFE9EDF3,
    suggestionText = 0xFF39404F,
    keyCornerRadiusDp = 16,
    keyMaterial = KeyMaterialSpec(kind = KeyMaterialKind.NEUMORPH.name, softShadow = 0.8f),
    variants = listOf(SoftDark),
)

// ---- Dot matrix ---------------------------------------------------------
// Monochrome, the letters drawn as dots and the keys ringed in dots, one red
// action key.

private val DotMatrixDark: ThemeSpec = ThemeSpec(
    id = "builtin_dot_matrix_dark",
    name = "Dot matrix dark",
    dark = true,
    boardBackground = 0xFF000000,
    keyBackground = 0xFF121212,
    keyText = 0xFFFFFFFF,
    modifierKeyBackground = 0xFF1D1D1D,
    modifierKeyText = 0xFFFFFFFF,
    enterKeyBackground = 0xFFD71921,
    enterKeyText = 0xFFFFFFFF,
    // Expressive Keyboard turns a held key white with a black label; with
    // one label colour for both states that would hide the label.
    pressedKeyBackground = 0xFF3A3A3A,
    accent = 0xFFD71921,
    gestureTrailColor = 0xFFD71921,
    popupBackground = 0xFFFFFFFF,
    popupText = 0xFF000000,
    popupSelectedBackground = 0xFFD71921,
    popupSelectedText = 0xFFFFFFFF,
    toolCircleBackground = 0xFF121212,
    chipBackground = 0xFF121212,
    suggestionText = 0xFFFFFFFF,
    keyCornerRadiusDp = 12,
    keyMaterial = KeyMaterialSpec(kind = KeyMaterialKind.DOT_MATRIX.name, edgeColor = 0xFF4A4A4A),
)

private val DotMatrixTheme: ThemeSpec = ThemeSpec(
    id = "builtin_dot_matrix",
    name = "Dot matrix",
    dark = false,
    boardBackground = 0xFFF2F2F2,
    keyBackground = 0xFFFFFFFF,
    keyText = 0xFF000000,
    modifierKeyBackground = 0xFFE4E4E4,
    modifierKeyText = 0xFF000000,
    enterKeyBackground = 0xFFD71921,
    enterKeyText = 0xFFFFFFFF,
    pressedKeyBackground = 0xFFC4C4C4,
    accent = 0xFFD71921,
    gestureTrailColor = 0xFFD71921,
    popupBackground = 0xFF000000,
    popupText = 0xFFFFFFFF,
    popupSelectedBackground = 0xFFD71921,
    popupSelectedText = 0xFFFFFFFF,
    toolCircleBackground = 0xFFFFFFFF,
    chipBackground = 0xFFFFFFFF,
    suggestionText = 0xFF000000,
    keyCornerRadiusDp = 12,
    keyMaterial = KeyMaterialSpec(kind = KeyMaterialKind.DOT_MATRIX.name, edgeColor = 0xFF9A9A9A),
    variants = listOf(DotMatrixDark),
)

// ---- Blocks -------------------------------------------------------------
// Square buttons cut from pixel textures with a hard bevel that swaps sides
// when pressed, on a board of dark earth; four materials.

/** What every block look shares: the shape, the bevelled pixel material, the tooltip-style popup. */
private fun blocks(
    id: String,
    name: String,
    board: Long,
    key: Long,
    modifier: Long,
    enter: Long,
    trail: Long,
    texture: MaterialTexture = MaterialTexture.PIXELS,
    variants: List<ThemeSpec> = emptyList(),
): ThemeSpec = ThemeSpec(
    id = id,
    name = name,
    dark = true,
    boardBackground = board,
    keyBackground = key,
    keyText = 0xFFFFFFFF,
    modifierKeyBackground = modifier,
    modifierKeyText = 0xFFFFFFFF,
    enterKeyBackground = enter,
    enterKeyText = 0xFFFFFFFF,
    pressedKeyBackground = 0xFF7C86C2,
    accent = enter,
    gestureTrailColor = trail,
    popupBackground = 0xF0100010,
    popupText = 0xFFFFFFFF,
    popupBorderColor = 0xFF5000FF,
    popupBorderWidthDp = 1.5f,
    popupSelectedBackground = enter,
    popupSelectedText = 0xFFFFFFFF,
    toolCircleBackground = modifier,
    chipBackground = modifier,
    suggestionText = 0xFFFFFFFF,
    keyShape = KeyShapeKind.SHARP,
    keyCornerRadiusDp = 0,
    keyMaterial = KeyMaterialSpec(kind = KeyMaterialKind.PIXEL.name, texture = texture.name),
    variants = variants,
)

private val BlocksTheme: ThemeSpec = blocks(
    id = "builtin_blocks",
    name = "Stone",
    board = 0xFF3B2819,
    key = 0xFF6F6F6F,
    modifier = 0xFF555555,
    enter = 0xFF5B8C32,
    trail = 0xFFFFFF55,
    variants = listOf(
        blocks(
            id = "builtin_blocks_planks",
            name = "Planks",
            board = 0xFF3B2819,
            // A shade darker than the source's oak, so a white label stays
            // readable on a plank with no outline round it.
            key = 0xFF8C6C3E,
            modifier = 0xFF6B4E2B,
            enter = 0xFF5B8C32,
            trail = 0xFFFFFF55,
            texture = MaterialTexture.PLANKS,
        ),
        blocks(
            id = "builtin_blocks_basalt",
            name = "Basalt",
            board = 0xFF1D1D21,
            key = 0xFF4D4D50,
            modifier = 0xFF36363A,
            enter = 0xFF23908B,
            trail = 0xFF55FFFF,
        ),
        blocks(
            id = "builtin_blocks_magma",
            name = "Magma",
            board = 0xFF2A0F10,
            key = 0xFF6F3534,
            modifier = 0xFF4A2222,
            enter = 0xFFCF5509,
            trail = 0xFFFFAA00,
        ),
    ),
)

// ---- Droplet ------------------------------------------------------------
// Glass keys over a pale or deep board. Pressing a key runs glass bridges
// out to the keys round it and the key squashes like jelly; the Orbs looks
// trade the bridges for colour pools under the glass.

/** The jelly press every droplet look shares. No light: the glass is the show. */
private val DropletPress = TouchBloomSpec(light = 0f, dip = 0.6f, wobble = 0.6f, motion = KeyPressMotion.JELLY.name)

private fun droplet(
    id: String,
    name: String,
    dark: Boolean,
    effect: BoardEffectSpec,
    variants: List<ThemeSpec> = emptyList(),
): ThemeSpec = if (dark) {
    ThemeSpec(
        id = id,
        name = name,
        dark = true,
        boardBackground = 0xFF12151F,
        boardGradient = GradientSpec(listOf(0xFF151925, 0xFF0E111A), GradientType.LINEAR, 90f),
        keyBackground = 0x80363B4A,
        keyGradient = GradientSpec(listOf(0x26FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f),
        keyText = 0xFFF4F6FB,
        modifierKeyBackground = 0x661F2330,
        modifierKeyText = 0xFFE6E9F2,
        enterKeyBackground = 0xFF5C8DFF,
        enterKeyText = 0xFFFFFFFF,
        pressedKeyBackground = 0xA0565C70,
        accent = 0xFF5C8DFF,
        gestureTrailColor = 0xCCFFFFFF,
        popupBackground = 0xE62A2E3A,
        popupText = 0xFFFFFFFF,
        popupSelectedBackground = 0xFF5C8DFF,
        popupSelectedText = 0xFFFFFFFF,
        toolCircleBackground = 0x80363B4A,
        chipBackground = 0x661F2330,
        suggestionText = 0xFFF4F6FB,
        keyCornerRadiusDp = 14,
        keyMaterial = KeyMaterialSpec(kind = KeyMaterialKind.GLASS.name),
        boardEffect = effect,
        touchBloom = DropletPress,
        variants = variants,
    )
} else {
    ThemeSpec(
        id = id,
        name = name,
        dark = false,
        boardBackground = 0xFFE6EAF2,
        boardGradient = GradientSpec(listOf(0xFFF1F3F8, 0xFFE3E7EF), GradientType.LINEAR, 90f),
        keyBackground = 0x8CFFFFFF,
        keyGradient = GradientSpec(listOf(0x40FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f),
        keyText = 0xFF15171C,
        modifierKeyBackground = 0x59FFFFFF,
        modifierKeyText = 0xFF15171C,
        enterKeyBackground = 0xFF2F6BFF,
        enterKeyText = 0xFFFFFFFF,
        pressedKeyBackground = 0xFFFFFFFF,
        accent = 0xFF2F6BFF,
        gestureTrailColor = 0x992F6BFF,
        popupBackground = 0xE6FFFFFF,
        popupText = 0xFF15171C,
        popupSelectedBackground = 0xFF2F6BFF,
        popupSelectedText = 0xFFFFFFFF,
        toolCircleBackground = 0x8CFFFFFF,
        chipBackground = 0x59FFFFFF,
        suggestionText = 0xFF15171C,
        keyCornerRadiusDp = 14,
        keyMaterial = KeyMaterialSpec(kind = KeyMaterialKind.GLASS.name),
        boardEffect = effect,
        touchBloom = DropletPress,
        variants = variants,
    )
}

/** The bridges in the board's own glass colour (no colours of their own). */
private val Meld = BoardEffectSpec(kind = BoardEffectKind.MELD.name, intensity = 1f)

/** Two still colour pools, as Expressive Keyboard lays under its glass. */
private fun orbs(colors: List<Long>) =
    BoardEffectSpec(kind = BoardEffectKind.ORBS.name, colors = colors, density = 0f, speed = 0f, intensity = 1f)

private val DropletTheme: ThemeSpec = droplet(
    id = "builtin_droplet",
    name = "Droplet",
    dark = false,
    effect = Meld,
    variants = listOf(
        droplet("builtin_droplet_dark", "Droplet dark", dark = true, effect = Meld),
        droplet("builtin_droplet_orbs", "Orbs", dark = false, effect = orbs(listOf(0x59B4C6FF, 0x4DFFC9DE))),
        droplet("builtin_droplet_orbs_dark", "Orbs dark", dark = true, effect = orbs(listOf(0x472E5BFF, 0x388E4DFF))),
    ),
)

/** The four families, appended to [BuiltInThemes] after the shape showcases. */
val ExpressiveThemes: List<ThemeSpec> = listOf(SoftTheme, DotMatrixTheme, BlocksTheme, DropletTheme)

internal val ExpressiveThemeNameRes: Map<String, Int> = mapOf(
    "builtin_soft" to R.string.core_theme_builtin_soft_label,
    "builtin_soft_dark" to R.string.core_theme_builtin_soft_dark_label,
    "builtin_dot_matrix" to R.string.core_theme_builtin_dot_matrix_label,
    "builtin_dot_matrix_dark" to R.string.core_theme_builtin_dot_matrix_dark_label,
    "builtin_blocks" to R.string.core_theme_builtin_blocks_label,
    "builtin_blocks_planks" to R.string.core_theme_builtin_blocks_planks_label,
    "builtin_blocks_basalt" to R.string.core_theme_builtin_blocks_basalt_label,
    "builtin_blocks_magma" to R.string.core_theme_builtin_blocks_magma_label,
    "builtin_droplet" to R.string.core_theme_builtin_droplet_label,
    "builtin_droplet_dark" to R.string.core_theme_builtin_droplet_dark_label,
    "builtin_droplet_orbs" to R.string.core_theme_builtin_droplet_orbs_label,
    "builtin_droplet_orbs_dark" to R.string.core_theme_builtin_droplet_orbs_dark_label,
)

internal val ExpressiveThemeFamilyNameRes: Map<String, Int> = mapOf(
    "builtin_soft" to R.string.core_theme_family_soft_label,
    "builtin_dot_matrix" to R.string.core_theme_family_dot_matrix_label,
    "builtin_blocks" to R.string.core_theme_family_blocks_label,
    "builtin_droplet" to R.string.core_theme_family_droplet_label,
)

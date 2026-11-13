package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes

/** One file on disk, as much of it as the sweep needs to decide. */
data class SweptFile(val name: String, val lastModified: Long)

/** What a sweep should remove. */
data class SweepPlan(
    val deleteImages: List<String>,
    val dropRotationStates: List<String>,
)

/**
 * Which background files nothing refers to any more.
 *
 * Deleting a theme leaves its image behind, and every re-crop and every
 * replaced photo leaves another. Left alone the directory only grows.
 *
 * The age guard is the important part. The photo picker writes a file
 * milliseconds before the theme that points at it is saved, so a sweep running
 * in that window would delete an image out from under a write that has not
 * landed yet. Nothing is removed until it has been unreferenced for a day.
 */
fun themePhotoSweepPlan(
    themes: List<ThemeSpec>,
    rotationStates: Map<String, RotationState>,
    imagesOnDisk: List<SweptFile>,
    poolFileNames: Set<String>,
    nowMs: Long,
    minAgeMs: Long = SWEEP_MIN_AGE_MS,
): SweepPlan {
    // Variants hold their own images and rotation states, so the whole
    // families are walked flat — a sweep that only saw parents would take a
    // variant's photo after a day.
    val allThemes = themes.flattenedThemes()
    val referenced = buildSet {
        allThemes.forEach { theme ->
            // A cropped background keeps the picture it was cut from beside
            // it, so the next crop can reach parts the last one cut away.
            listOfNotNull(theme.backgroundImage, theme.backgroundImageLandscape).forEach {
                val name = it.substringAfterLast('/')
                add(name)
                add(name + CROP_ORIGINAL_SUFFIX)
                add(name + CROP_FRAME_SUFFIX)
            }
            // Key textures live in the same directory; a sweep that does not
            // know them would take an active theme's textures after a day.
            theme.keyTexture?.let { add(it.substringAfterLast('/')) }
            theme.keyTextureModifier?.let { add(it.substringAfterLast('/')) }
            theme.keyTextureEnter?.let { add(it.substringAfterLast('/')) }
            theme.keyTextureSpace?.let { add(it.substringAfterLast('/')) }
            theme.keyTexturePressed?.let { add(it.substringAfterLast('/')) }
            theme.popupTexture?.let { add(it.substringAfterLast('/')) }
            theme.decals.forEach { decal ->
                decal.image?.let { add(it.substringAfterLast('/')) }
            }
            theme.keyEffectImages.forEach { add(it.substringAfterLast('/')) }
            theme.keyOverrides.values.forEach { override ->
                override.texture?.let { add(it.substringAfterLast('/')) }
                override.pressedTexture?.let { add(it.substringAfterLast('/')) }
            }
            // A converted theme's own key glyphs are written here too.
            theme.keyIcons.values.forEach { add(it.substringAfterLast('/')) }
        }
        rotationStates.values.forEach { state ->
            state.imagePath?.let { add(it.substringAfterLast('/')) }
        }
        addAll(poolFileNames)
    }
    val liveThemeIds = allThemes.mapTo(HashSet()) { it.id }
    return SweepPlan(
        deleteImages = imagesOnDisk
            .filter { it.name !in referenced && nowMs - it.lastModified >= minAgeMs }
            .map { it.name },
        dropRotationStates = rotationStates.keys.filterNot { it in liveThemeIds },
    )
}

/** A file has to have been unreferenced this long before it is removed. */
const val SWEEP_MIN_AGE_MS = 24L * 60 * 60 * 1000

/** Beside a cropped background: the uncropped picture it was cut from. */
const val CROP_ORIGINAL_SUFFIX = ".original"

/** Beside a cropped background: where in [CROP_ORIGINAL_SUFFIX] the frame sat. */
const val CROP_FRAME_SUFFIX = ".frame"

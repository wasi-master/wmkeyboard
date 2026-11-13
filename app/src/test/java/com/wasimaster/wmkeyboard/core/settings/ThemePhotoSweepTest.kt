package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.theme.KeyOverride
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which background files nothing refers to any more. No filesystem needed. */
class ThemePhotoSweepTest {

    private val now = 1_700_000_000_000L
    private val old = now - 2 * SWEEP_MIN_AGE_MS

    private fun theme(id: String, image: String? = null, landscape: String? = null) = ThemeSpec(
        id = id,
        name = id,
        backgroundImage = image?.let { "/data/files/theme_images/$it" },
        backgroundImageLandscape = landscape?.let { "/data/files/theme_images/$it" },
    )

    @Test
    fun `key glyphs and held-key pictures count as references`() {
        val dir = "/data/files/theme_images/"
        val themed = theme("a").copy(
            keyIcons = mapOf("key.backspace" to dir + "a_keyicon_keybackspace.img"),
            keyOverrides = mapOf("ENTER" to KeyOverride(pressedTexture = dir + "a_keypress_ENTER.img")),
        )
        val plan = themePhotoSweepPlan(
            themes = listOf(themed),
            rotationStates = emptyMap(),
            imagesOnDisk = listOf(
                SweptFile("a_keyicon_keybackspace.img", old),
                SweptFile("a_keypress_ENTER.img", old),
                SweptFile("orphan.img", old),
            ),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertEquals(listOf("orphan.img"), plan.deleteImages)
    }

    @Test
    fun `a file no theme refers to is swept`() {
        val plan = themePhotoSweepPlan(
            themes = listOf(theme("a", image = "keep.img")),
            rotationStates = emptyMap(),
            imagesOnDisk = listOf(SweptFile("keep.img", old), SweptFile("orphan.img", old)),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertEquals(listOf("orphan.img"), plan.deleteImages)
    }

    @Test
    fun `a cropped background keeps its original and frame, an old crop's are swept`() {
        val plan = themePhotoSweepPlan(
            themes = listOf(theme("a", image = "crop_2.img")),
            rotationStates = emptyMap(),
            imagesOnDisk = listOf(
                SweptFile("crop_2.img", old),
                SweptFile("crop_2.img$CROP_ORIGINAL_SUFFIX", old),
                SweptFile("crop_2.img$CROP_FRAME_SUFFIX", old),
                SweptFile("crop_1.img$CROP_ORIGINAL_SUFFIX", old),
            ),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertEquals(listOf("crop_1.img$CROP_ORIGINAL_SUFFIX"), plan.deleteImages)
    }

    @Test
    fun `a file written moments ago is left alone`() {
        // The picker writes the file just before the theme that points at it is
        // saved. Sweeping in that window would delete it out from under a write
        // that has not landed.
        val plan = themePhotoSweepPlan(
            themes = emptyList(),
            rotationStates = emptyMap(),
            imagesOnDisk = listOf(SweptFile("just_written.img", now - 1000)),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertTrue(plan.deleteImages.isEmpty())
    }

    @Test
    fun `the landscape slot counts as a reference`() {
        val plan = themePhotoSweepPlan(
            themes = listOf(theme("a", landscape = "wide.img")),
            rotationStates = emptyMap(),
            imagesOnDisk = listOf(SweptFile("wide.img", old)),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertTrue(plan.deleteImages.isEmpty())
    }

    @Test
    fun `a photo a rotation is showing is not swept`() {
        val plan = themePhotoSweepPlan(
            themes = listOf(theme("a")),
            rotationStates = mapOf(
                "a" to RotationState(imagePath = "/data/files/theme_images/showing.img"),
            ),
            imagesOnDisk = listOf(SweptFile("showing.img", old)),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertTrue(plan.deleteImages.isEmpty())
    }

    @Test
    fun `a photo in the pool is not swept`() {
        val plan = themePhotoSweepPlan(
            themes = emptyList(),
            rotationStates = emptyMap(),
            imagesOnDisk = listOf(SweptFile("p_unsplash_a.img", old)),
            poolFileNames = setOf("p_unsplash_a.img"),
            nowMs = now,
        )
        assertTrue(plan.deleteImages.isEmpty())
    }

    @Test
    fun `rotation entries for deleted themes are forgotten`() {
        val plan = themePhotoSweepPlan(
            themes = listOf(theme("a")),
            rotationStates = mapOf("a" to RotationState(), "gone" to RotationState()),
            imagesOnDisk = emptyList(),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertEquals(listOf("gone"), plan.dropRotationStates)
    }

    @Test
    fun `a variant's image counts as a reference`() {
        // Variants hold their own images; a sweep that only saw parents would
        // take a variant's photo after a day.
        val plan = themePhotoSweepPlan(
            themes = listOf(
                theme("a").copy(variants = listOf(theme("a_v0", image = "night.img"))),
            ),
            rotationStates = emptyMap(),
            imagesOnDisk = listOf(SweptFile("night.img", old)),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertTrue(plan.deleteImages.isEmpty())
    }

    @Test
    fun `a variant's rotation entry is not forgotten`() {
        val plan = themePhotoSweepPlan(
            themes = listOf(theme("a").copy(variants = listOf(theme("a_v0")))),
            rotationStates = mapOf("a_v0" to RotationState(), "gone" to RotationState()),
            imagesOnDisk = emptyList(),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertEquals(listOf("gone"), plan.dropRotationStates)
    }

    @Test
    fun `a tidy install sweeps nothing`() {
        val plan = themePhotoSweepPlan(
            themes = listOf(theme("a", image = "a.img")),
            rotationStates = mapOf("a" to RotationState()),
            imagesOnDisk = listOf(SweptFile("a.img", old)),
            poolFileNames = emptySet(),
            nowMs = now,
        )
        assertTrue(plan.deleteImages.isEmpty())
        assertTrue(plan.dropRotationStates.isEmpty())
    }
}

package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenVariantTest {

    @Test fun variantIsChosenFromOrientationAndPosture() {
        assertEquals(ScreenVariant.PORTRAIT, ScreenVariant.of(landscape = false, unfolded = false))
        assertEquals(ScreenVariant.LANDSCAPE, ScreenVariant.of(landscape = true, unfolded = false))
        assertEquals(
            ScreenVariant.PORTRAIT_UNFOLDED,
            ScreenVariant.of(landscape = false, unfolded = true),
        )
        assertEquals(
            ScreenVariant.LANDSCAPE_UNFOLDED,
            ScreenVariant.of(landscape = true, unfolded = true),
        )
    }

    @Test fun portraitResolvesToTheBaseSettingsUntouched() {
        val settings = KeyboardSettings(keyHeightDp = 48)
        assertSame(settings, settings.resolvedFor(ScreenVariant.PORTRAIT))
    }

    /**
     * The service reads one-handed settings straight off its own raw state while
     * the keyboard composable reads them through [resolvedFor] — the two must
     * agree, or the toolbar's one-handed button and its hardware shortcut would
     * dock the keyboard to different sides. They agree because the sizing
     * fields carry nothing about one-handed mode; this pins that, so a new
     * [SizingOverride] field cannot quietly break it. The one exception is the
     * per-shape gate (#611), which the service asks through the same
     * [oneHandedAllowedOn].
     */
    @Test fun sizingOverridesNeverTouchOneHandedSettings() {
        val settings = KeyboardSettings(
            oneHandedMode = OneHandedMode.LEFT,
            oneHanded = OneHandedSettings(
                landscape = OneHandedProfile(side = OneHandedSide.RIGHT),
            ),
            sizingOverrides = ScreenVariant.entries.associateWith {
                SizingOverride(
                    keyHeightDp = 30,
                    numberRowHeightDp = 20,
                    bottomPaddingDp = 4,
                    keyboardWidthPercent = 90,
                    fontScale = 1.2f,
                    keyboardAlignment = KeyboardAlignment.RIGHT,
                    keyboardScale = 1.5f,
                )
            },
        )
        for (variant in ScreenVariant.entries) {
            val resolved = settings.resolvedFor(variant)
            assertEquals(variant.name, settings.oneHandedMode, resolved.oneHandedMode)
            assertEquals(variant.name, settings.oneHanded, resolved.oneHanded)
        }
    }

    @Test fun unsetFieldsInheritPortrait() {
        val settings = KeyboardSettings(
            keyHeightDp = 48,
            keyboardWidthPercent = 100,
            sizingOverrides = mapOf(
                ScreenVariant.LANDSCAPE to SizingOverride(keyHeightDp = 36),
            ),
        )
        val landscape = settings.resolvedFor(ScreenVariant.LANDSCAPE)
        assertEquals(36, landscape.keyHeightDp)
        // Only key height was overridden; width still follows portrait.
        assertEquals(100, landscape.keyboardWidthPercent)
    }

    @Test fun variantsDoNotLeakIntoEachOther() {
        val settings = KeyboardSettings(
            keyHeightDp = 48,
            sizingOverrides = mapOf(
                ScreenVariant.LANDSCAPE to SizingOverride(keyHeightDp = 36),
            ),
        )
        assertEquals(48, settings.resolvedFor(ScreenVariant.PORTRAIT_UNFOLDED).keyHeightDp)
        assertEquals(48, settings.resolvedFor(ScreenVariant.LANDSCAPE_UNFOLDED).keyHeightDp)
    }

    @Test fun anEmptyOverrideChangesNothing() {
        val settings = KeyboardSettings(
            keyHeightDp = 48,
            sizingOverrides = mapOf(ScreenVariant.LANDSCAPE to SizingOverride()),
        )
        assertSame(settings, settings.resolvedFor(ScreenVariant.LANDSCAPE))
    }

    /**
     * Issue #251: a phone in landscape keeps its width and loses over half its
     * height, so a board built from the portrait numbers overflowed the window
     * and its bottom rows were clipped away. The shape has its own defaults
     * now, sized to Gboard's landscape board on the same screen.
     */
    @Test fun aLandscapePhoneStartsAtALandscapeSizedBoard() {
        val phone = KeyboardSettings(keyHeightDp = 48, numberRowHeightDp = 42)
            .applyScreenDefaults(ScreenVariant.LANDSCAPE)
        // 33dp of key on this keyboard's 4dp gap is Gboard's 41dp row pitch.
        assertEquals(33, phone.keyHeightDp)
        assertEquals(29, phone.numberRowHeightDp)
        // Gboard's landscape is four rows; the switch here is untouched.
        assertFalse(phone.numberRow)
    }

    /**
     * The bar is sized in dp of its own, so it did not shrink with the board:
     * a 38dp tool circle above a 33dp key is chrome drawn bigger than the keys
     * it serves. Scaled, not replaced — it has no per-shape override to escape
     * to, so a width the user picked survives in proportion.
     */
    @Test fun theToolbarShrinksWithTheLandscapeBoard() {
        val phone = KeyboardSettings().applyScreenDefaults(ScreenVariant.LANDSCAPE)
        assertEquals(31, phone.toolbarHeightDp)
        assertEquals(27, phone.toolbarBehavior.toolWidthDp)

        val wide = KeyboardSettings(
            toolbarBehavior = ToolbarBehavior(toolWidthDp = 60),
        ).applyScreenDefaults(ScreenVariant.LANDSCAPE)
        assertEquals(42, wide.toolbarBehavior.toolWidthDp)
    }

    @Test fun theOtherShapesAreLeftAlone() {
        val settings = KeyboardSettings(keyHeightDp = 48)
        for (variant in ScreenVariant.entries - ScreenVariant.LANDSCAPE) {
            assertSame(variant.name, settings, settings.applyScreenDefaults(variant))
        }
    }

    /**
     * A tablet reaches [ScreenVariant.LANDSCAPE_UNFOLDED], never [LANDSCAPE]:
     * sideways it is still 600dp-plus tall, so it has the room and
     * `applyDeviceForm` has already picked its key height.
     */
    @Test fun aTabletSidewaysIsNotALandscapePhone() {
        val tablet = KeyboardSettings().applyDeviceForm(DeviceForm.LARGE_TABLET)
        assertEquals(44, tablet.applyScreenDefaults(ScreenVariant.LANDSCAPE_UNFOLDED).keyHeightDp)
    }

    /** A digit row the user switched on by hand survives the rotation. */
    @Test fun anAskedForDigitRowIsKeptInLandscape() {
        val asked = KeyboardSettings(
            numberRow = true,
            layoutBehavior = LayoutBehaviorSettings(numberRowUntouched = false),
        )
        assertTrue(asked.applyScreenDefaults(ScreenVariant.LANDSCAPE).numberRow)
    }

    /** A number the user set for this shape still beats the shape's default. */
    @Test fun theUsersLandscapeValueBeatsTheDefault() {
        val settings = KeyboardSettings(
            keyHeightDp = 48,
            sizingOverrides = mapOf(
                ScreenVariant.LANDSCAPE to SizingOverride(keyHeightDp = 52),
            ),
        )
        val landscape = settings
            .applyScreenDefaults(ScreenVariant.LANDSCAPE)
            .resolvedFor(ScreenVariant.LANDSCAPE)
        assertEquals(52, landscape.keyHeightDp)
        // Field by field: the rest of the shape's defaults still stand.
        assertEquals(29, landscape.numberRowHeightDp)
    }

    /** The shape's editor reads the same numbers its board is drawn at. */
    @Test fun theEditorShowsTheShapesDefaults() {
        val values = KeyboardSettings(keyHeightDp = 48, numberRowHeightDp = 42)
            .sizingValuesFor(ScreenVariant.LANDSCAPE)
        assertEquals(33, values.keyHeightDp)
        assertEquals(29, values.numberRowHeightDp)
    }

    @Test fun everyFieldCanBeOverridden() {
        val settings = KeyboardSettings(sizingOverrides = mapOf(
            ScreenVariant.LANDSCAPE to SizingOverride(
                keyHeightDp = 36,
                numberRowHeightDp = 30,
                bottomPaddingDp = 2,
                keyboardWidthPercent = 80,
                fontScale = 0.9f,
                keyboardAlignment = KeyboardAlignment.RIGHT,
            ),
        ))
        val landscape = settings.resolvedFor(ScreenVariant.LANDSCAPE)
        assertEquals(36, landscape.keyHeightDp)
        assertEquals(30, landscape.numberRowHeightDp)
        assertEquals(2, landscape.bottomPaddingDp)
        assertEquals(80, landscape.keyboardWidthPercent)
        assertEquals(0.9f, landscape.fontScale, 0.001f)
        assertEquals(KeyboardAlignment.RIGHT, landscape.keyboardAlignment)
    }

    /** #584: landscape follows portrait until it has a value of its own. */
    @Test fun landscapeFloatingFollowsPortraitUntilSet() {
        val legacy = KeyboardSettings(floatingKeyboard = true)
        assertTrue(legacy.floatingFor(landscape = false))
        assertTrue(legacy.floatingFor(landscape = true))
        val apart = KeyboardSettings(floatingKeyboard = false, floatingKeyboardLandscape = true)
        assertFalse(apart.floatingFor(landscape = false))
        assertTrue(apart.floatingFor(landscape = true))
    }

    /** The frame reads `floatingKeyboard` and never learns there are two. */
    @Test fun resolvingFoldsInTheOrientationsFloating() {
        val settings = KeyboardSettings(floatingKeyboard = false, floatingKeyboardLandscape = true)
        assertTrue(settings.resolvedFor(ScreenVariant.LANDSCAPE).floatingKeyboard)
        assertTrue(settings.resolvedFor(ScreenVariant.LANDSCAPE_UNFOLDED).floatingKeyboard)
        assertFalse(settings.resolvedFor(ScreenVariant.PORTRAIT).floatingKeyboard)
        assertSame(settings, settings.resolvedFor(ScreenVariant.PORTRAIT))
        val docked = KeyboardSettings(floatingKeyboard = true, floatingKeyboardLandscape = false)
        assertFalse(docked.resolvedFor(ScreenVariant.LANDSCAPE).floatingKeyboard)
        assertTrue(docked.resolvedFor(ScreenVariant.PORTRAIT_UNFOLDED).floatingKeyboard)
    }

    /** #611: an unfolded portrait screen can drop split and keep one-handed. */
    @Test fun aShapeCanHoldSplitBackAndKeepOneHanded() {
        val settings = KeyboardSettings(
            splitKeyboard = true,
            oneHandedMode = OneHandedMode.LEFT,
            sizingOverrides = mapOf(
                ScreenVariant.PORTRAIT_UNFOLDED to SizingOverride(allowSplit = false),
            ),
        )
        val unfolded = settings.resolvedFor(ScreenVariant.PORTRAIT_UNFOLDED)
        assertFalse(unfolded.splitKeyboard)
        assertEquals(OneHandedMode.LEFT, unfolded.oneHandedMode)
        val landscape = settings.resolvedFor(ScreenVariant.LANDSCAPE_UNFOLDED)
        assertTrue(landscape.splitKeyboard)
    }

    @Test fun aShapeCanHoldOneHandedBack() {
        val settings = KeyboardSettings(
            oneHandedMode = OneHandedMode.RIGHT,
            sizingOverrides = mapOf(
                ScreenVariant.PORTRAIT_UNFOLDED to SizingOverride(allowOneHanded = false),
            ),
        )
        assertEquals(
            OneHandedMode.OFF,
            settings.resolvedFor(ScreenVariant.PORTRAIT_UNFOLDED).oneHandedModeFor(landscape = false),
        )
        assertEquals(OneHandedMode.RIGHT, settings.resolvedFor(ScreenVariant.PORTRAIT).oneHandedMode)
    }

    /** Unset, the gates are the shared rules; set, they win over them. */
    @Test fun gatesDefaultToTheSharedRulesAndBeatThem() {
        val shared = KeyboardSettings(
            splitKeyboard = true,
            oneHandedMode = OneHandedMode.LEFT,
            oneHanded = OneHandedSettings(portraitOnly = true),
            layoutBehavior = KeyboardSettings().layoutBehavior.copy(splitOnlyOnLargeScreens = true),
        )
        assertFalse(shared.splitAllowedOn(ScreenVariant.PORTRAIT))
        assertTrue(shared.splitAllowedOn(ScreenVariant.PORTRAIT_UNFOLDED))
        assertFalse(shared.oneHandedAllowedOn(ScreenVariant.LANDSCAPE))
        assertTrue(shared.oneHandedAllowedOn(ScreenVariant.PORTRAIT_UNFOLDED))
        assertEquals(
            OneHandedMode.OFF,
            shared.resolvedFor(ScreenVariant.LANDSCAPE).oneHandedModeFor(landscape = true),
        )

        val allowed = shared.copy(
            sizingOverrides = mapOf(
                ScreenVariant.LANDSCAPE_UNFOLDED to SizingOverride(allowOneHanded = true),
            ),
        )
        assertEquals(
            OneHandedMode.LEFT,
            allowed.resolvedFor(ScreenVariant.LANDSCAPE_UNFOLDED).oneHandedModeFor(landscape = true),
        )
        assertFalse(allowed.sizingOverrides.getValue(ScreenVariant.LANDSCAPE_UNFOLDED).isEmpty)
    }

    /**
     * The tablet-style bars (Telegram group, 2026-11-05): a shape can turn the
     * full-width toolbar on for itself and size the toolbar row, and a shape
     * that says nothing keeps the base answer.
     */
    @Test fun fullWidthBarsAndToolbarHeightResolvePerShape() {
        val settings = KeyboardSettings(
            toolbarHeightDp = 44,
            sizingOverrides = mapOf(
                ScreenVariant.LANDSCAPE_UNFOLDED to SizingOverride(fullWidthBars = true, toolbarHeightDp = 52),
            ),
        )
        val tablet = settings.resolvedFor(ScreenVariant.LANDSCAPE_UNFOLDED)
        assertTrue(tablet.layoutBehavior.fullWidthBars)
        assertEquals(52, tablet.toolbarHeightDp)
        val upright = settings.resolvedFor(ScreenVariant.PORTRAIT_UNFOLDED)
        assertFalse(upright.layoutBehavior.fullWidthBars)
        assertEquals(44, upright.toolbarHeightDp)
        assertFalse(settings.sizingOverrides.getValue(ScreenVariant.LANDSCAPE_UNFOLDED).isEmpty)
        assertEquals(true, settings.sizingValuesFor(ScreenVariant.LANDSCAPE_UNFOLDED).fullWidthBars)
    }
}

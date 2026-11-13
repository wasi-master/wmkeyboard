package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFormTest {

    @Test fun formIsBucketedAtTheLatinImeThresholds() {
        assertEquals(DeviceForm.PHONE, DeviceForm.of(0))
        assertEquals(DeviceForm.PHONE, DeviceForm.of(360))
        assertEquals(DeviceForm.PHONE, DeviceForm.of(599))
        assertEquals(DeviceForm.SMALL_TABLET, DeviceForm.of(600))
        assertEquals(DeviceForm.SMALL_TABLET, DeviceForm.of(767))
        assertEquals(DeviceForm.LARGE_TABLET, DeviceForm.of(768))
        assertEquals(DeviceForm.LARGE_TABLET, DeviceForm.of(1200))
        assertEquals(DeviceForm.LARGE_TABLET, DeviceForm.of(Int.MAX_VALUE))
    }

    @Test fun onlyPhoneIsNotATablet() {
        assertTrue(DeviceForm.entries.count { it.isTablet } == 2)
        assertTrue(!DeviceForm.PHONE.isTablet)
    }

    /**
     * The two 600dp constants agree today and mean different things: one asks
     * about posture (is this foldable open?), the other about form factor (is
     * this a tablet?). Pinned so a future change to one is a deliberate edit
     * rather than a coincidence someone relied on.
     */
    @Test fun thePostureAndFormThresholdsCurrentlyAgree() {
        assertEquals(ScreenVariant.UNFOLDED_MIN_DP, DeviceForm.SMALL_TABLET_MIN_DP)
    }

    // -- the overlay --------------------------------------------------------

    /**
     * Instance-stable on a phone, which is ~90% of installs. The service caches
     * the result in its UI state and the composable remembers on it *by
     * instance*, so a fresh-but-equal copy per settings emission would cost a
     * full theme and screen-variant re-resolve on every unrelated write.
     */
    @Test fun aPhoneGetsBackTheVerySameInstance() {
        val settings = KeyboardSettings()
        assertSame(settings, settings.applyDeviceForm(DeviceForm.PHONE))
    }

    @Test fun aTabletWithNothingLeftToDefaultAlsoGetsTheSameInstance() {
        val settings = KeyboardSettings(
            numberRow = true,
            toolbarTools = toolKeysOf(ToolbarTool.EMOJI),
            layoutBehavior = LayoutBehaviorSettings(
                numberRowUntouched = false,
                keyHeightUntouched = false,
            ),
        )
        assertSame(settings, settings.applyDeviceForm(DeviceForm.LARGE_TABLET))
    }

    @Test fun aTabletTurnsTheDigitRowOnAndShortensTheKeys() {
        val applied = KeyboardSettings().applyDeviceForm(DeviceForm.LARGE_TABLET)
        assertTrue(applied.numberRow)
        assertEquals(44, applied.keyHeightDp)
        assertEquals(38, applied.numberRowHeightDp)
        assertEquals(LargeTabletToolbarTools.toolKeys(), applied.toolbarTools)

        val small = KeyboardSettings().applyDeviceForm(DeviceForm.SMALL_TABLET)
        assertEquals(46, small.keyHeightDp)
        assertEquals(40, small.numberRowHeightDp)
        assertEquals(SmallTabletToolbarTools.toolKeys(), small.toolbarTools)
    }

    /**
     * The whole reason the "untouched" flags exist. Without them a tablet user
     * could not switch the digit row off — the overlay would put it straight
     * back, and the toggle would look broken.
     */
    @Test fun anExplicitChoiceSurvivesOnATablet() {
        val chosen = KeyboardSettings(
            numberRow = false,
            keyHeightDp = 60,
            numberRowHeightDp = 55,
            layoutBehavior = LayoutBehaviorSettings(
                numberRowUntouched = false,
                keyHeightUntouched = false,
            ),
        ).applyDeviceForm(DeviceForm.LARGE_TABLET)

        assertTrue(!chosen.numberRow)
        assertEquals(60, chosen.keyHeightDp)
        assertEquals(55, chosen.numberRowHeightDp)
    }

    @Test fun aRearrangedToolbarIsLeftAlone() {
        val pinned = toolKeysOf(ToolbarTool.CLIPBOARD, ToolbarTool.SETTINGS)
        val applied = KeyboardSettings(toolbarTools = pinned)
            .applyDeviceForm(DeviceForm.LARGE_TABLET)
        assertEquals(pinned, applied.toolbarTools)
    }

    /**
     * Every tablet pin has to be in [RecommendedTools], or an onboarding persona
     * that starts from that set would leave one pinned but disabled — and
     * `visibleToolbarTools` filters those out, so the slot would silently vanish.
     */
    @Test fun everyTabletPinIsAToolTheWizardCanEnable() {
        assertTrue(RecommendedTools.containsAll(SmallTabletToolbarTools))
        assertTrue(RecommendedTools.containsAll(LargeTabletToolbarTools))
    }

    /**
     * The tablet key heights land on the base fields, never in
     * [KeyboardSettings.sizingOverrides]. That map means "what the user set for
     * this screen shape", and its editor reads an entry's presence as exactly
     * that — a default injected there would show as user-configured and make
     * "Reset to portrait" look like a button that does nothing.
     */
    @Test fun theOverlayNeverWritesIntoSizingOverrides() {
        val settings = KeyboardSettings()
        val applied = settings.applyDeviceForm(DeviceForm.LARGE_TABLET)
        assertNotSame(settings, applied)
        assertEquals(settings.sizingOverrides, applied.sizingOverrides)
        assertEquals(emptyMap<ScreenVariant, SizingOverride>(), applied.sizingOverrides)
    }

    /**
     * Ordering contract: device form is the *least* specific step, so everything
     * downstream beats it — a theme's authored height, and above that a
     * per-screen override the user set by hand.
     */
    @Test fun everyLaterOverlayBeatsTheTabletDefault() {
        val tablet = KeyboardSettings().applyDeviceForm(DeviceForm.LARGE_TABLET)
        assertEquals(44, tablet.keyHeightDp)

        val themed = tablet.applyThemeOverrides(ThemeSpec(id = "t", name = "T", keyHeightDp = 60))
        assertEquals(60, themed.keyHeightDp)

        val perScreen = themed
            .copy(sizingOverrides = mapOf(ScreenVariant.LANDSCAPE to SizingOverride(keyHeightDp = 40)))
            .resolvedFor(ScreenVariant.LANDSCAPE)
        assertEquals(40, perScreen.keyHeightDp)
    }

    /**
     * The overlay has to run *before* the direct-boot filter. A tablet's wider
     * default toolbar carries credential-backed tools, and re-pinning them after
     * that filter would put them on a lock screen that cannot read their data.
     */
    @Test fun directBootStillStripsTheTabletPins() {
        val locked = KeyboardSettings()
            .applyDeviceForm(DeviceForm.LARGE_TABLET)
            .restrictedToDirectBoot()
        assertTrue(
            "credential-backed tools survived direct boot: ${locked.toolbarTools}",
            locked.toolbarTools.all { it.tool?.let(::isDirectBootSafeTool) == true },
        )
    }
}

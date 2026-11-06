package com.wasimaster.wmkeyboard.ime.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRoutePlannerTest {

    private fun traits(
        sdk: Int = 35,
        family: RomFamily = RomFamily.AOSP,
        adjacentNeedsSource: Boolean = sdk >= 34,
        splitFramework: Boolean = true,
        freeform: Boolean = false,
        desktopDisplay: Boolean = false,
        toggleSplitAction: Boolean? = null,
        helperOn: Boolean = false,
        shell: Boolean = false,
    ) = DeviceTraits(
        sdk = sdk,
        family = family,
        adjacentNeedsSource = adjacentNeedsSource,
        splitFramework = splitFramework,
        freeform = freeform,
        desktopDisplay = desktopDisplay,
        toggleSplitAction = toggleSplitAction,
        helperOn = helperOn,
        shell = shell,
        secureSettings = false,
    )

    // ---- Floating ----

    @Test
    fun `stock phone without freeform has no floating route`() {
        assertEquals(emptyList<LaunchRoute>(), LaunchRoutePlanner.floating(traits(), emulatedAllowed = false))
    }

    @Test
    fun `stock phone with shizuku floats through the shell, emulated only when allowed`() {
        assertEquals(listOf(LaunchRoute.FLOAT_SHELL), LaunchRoutePlanner.floating(traits(shell = true), false))
        assertEquals(
            listOf(LaunchRoute.FLOAT_SHELL, LaunchRoute.FLOAT_EMULATED),
            LaunchRoutePlanner.floating(traits(shell = true), true),
        )
    }

    @Test
    fun `emulated window never offered without the shell`() {
        assertFalse(LaunchRoute.FLOAT_EMULATED in LaunchRoutePlanner.floating(traits(), true))
    }

    @Test
    fun `each skin leads with its own floating mode`() {
        val firsts = mapOf(
            RomFamily.COLOROS to LaunchRoute.FLOAT_COLOROS,
            RomFamily.SAMSUNG to LaunchRoute.FLOAT_FREEFORM,
            RomFamily.MIUI to LaunchRoute.FLOAT_MIUI_FACTORY,
            RomFamily.HUAWEI to LaunchRoute.FLOAT_HUAWEI,
            RomFamily.HONOR to LaunchRoute.FLOAT_HUAWEI,
            RomFamily.VIVO to LaunchRoute.FLOAT_VIVO,
            RomFamily.MOTOROLA to LaunchRoute.FLOAT_MOTOROLA,
            RomFamily.MEIZU to LaunchRoute.FLOAT_MEIZU,
            RomFamily.NUBIA to LaunchRoute.FLOAT_NUBIA,
            RomFamily.CHROMEOS to LaunchRoute.FLOAT_FREEFORM,
        )
        for ((family, route) in firsts) {
            assertEquals(family.name, route, LaunchRoutePlanner.floating(traits(family = family), false).first())
        }
    }

    @Test
    fun `xiaomi tries the factory before scaled bounds`() {
        assertEquals(
            listOf(LaunchRoute.FLOAT_MIUI_FACTORY, LaunchRoute.FLOAT_MIUI_SCALED),
            LaunchRoutePlanner.floating(traits(family = RomFamily.MIUI), false),
        )
    }

    @Test
    fun `freeform switched on adds the plain freeform route on any skin`() {
        val routes = LaunchRoutePlanner.floating(traits(family = RomFamily.COLOROS, freeform = true), false)
        assertEquals(listOf(LaunchRoute.FLOAT_COLOROS, LaunchRoute.FLOAT_FREEFORM), routes)
    }

    @Test
    fun `desktop display adds bounds alone after freeform`() {
        val routes = LaunchRoutePlanner.floating(traits(desktopDisplay = true), false)
        assertEquals(listOf(LaunchRoute.FLOAT_FREEFORM, LaunchRoute.FLOAT_BOUNDS), routes)
    }

    // ---- Split ----

    @Test
    fun `android 14 and later split through the trampoline, both orders`() {
        assertEquals(
            listOf(LaunchRoute.SPLIT_TRAMPOLINE_ADJ_FIRST, LaunchRoute.SPLIT_TRAMPOLINE_PLAIN_FIRST),
            LaunchRoutePlanner.split(traits(sdk = 34)),
        )
    }

    @Test
    fun `12L to 13 before QPR1 tries the service launch first`() {
        assertEquals(
            listOf(LaunchRoute.SPLIT_SERVICE, LaunchRoute.SPLIT_TRAMPOLINE_PLAIN_FIRST),
            LaunchRoutePlanner.split(traits(sdk = 32, adjacentNeedsSource = false)),
        )
    }

    @Test
    fun `below 12L only the helper or nothing`() {
        assertEquals(emptyList<LaunchRoute>(), LaunchRoutePlanner.split(traits(sdk = 30)))
        assertEquals(listOf(LaunchRoute.SPLIT_HELPER), LaunchRoutePlanner.split(traits(sdk = 30, helperOn = true)))
    }

    @Test
    fun `helper is skipped where the system does not list its switch`() {
        assertFalse(LaunchRoute.SPLIT_HELPER in LaunchRoutePlanner.split(traits(helperOn = true, toggleSplitAction = false)))
        assertTrue(LaunchRoute.SPLIT_HELPER in LaunchRoutePlanner.split(traits(helperOn = true, toggleSplitAction = true)))
        // Unknown (Android 10 and older) is tried.
        assertTrue(LaunchRoute.SPLIT_HELPER in LaunchRoutePlanner.split(traits(sdk = 29, helperOn = true)))
    }

    @Test
    fun `huawei leads with its own split modes`() {
        assertEquals(LaunchRoute.SPLIT_HUAWEI, LaunchRoutePlanner.split(traits(family = RomFamily.HUAWEI)).first())
    }

    @Test
    fun `shell split only from android 14`() {
        assertTrue(LaunchRoute.SPLIT_SHELL in LaunchRoutePlanner.split(traits(sdk = 34, shell = true)))
        assertFalse(LaunchRoute.SPLIT_SHELL in LaunchRoutePlanner.split(traits(sdk = 33, shell = true)))
    }

    @Test
    fun `no split framework means no routes at all`() {
        assertEquals(emptyList<LaunchRoute>(), LaunchRoutePlanner.split(traits(splitFramework = false, shell = true)))
    }

    @Test
    fun `pair gaps`() {
        assertEquals(LaunchRoutePlanner.ANDROID_16_GAP_MS, LaunchRoutePlanner.pairGapMs(traits(sdk = 36), false))
        assertEquals(
            LaunchRoutePlanner.SLOW_HELPER_GAP_MS,
            LaunchRoutePlanner.pairGapMs(traits(sdk = 33, family = RomFamily.MIUI), true),
        )
        assertEquals(LaunchRoutePlanner.SLOW_HELPER_GAP_MS, LaunchRoutePlanner.pairGapMs(traits(sdk = 35), true))
        assertEquals(LaunchRoutePlanner.DEFAULT_GAP_MS, LaunchRoutePlanner.pairGapMs(traits(sdk = 33), false))
    }

    // ---- Stored names ----

    @Test
    fun `route names round trip and unknown names parse to null`() {
        for (route in LaunchRoute.entries) assertEquals(route, LaunchRoute.parse(route.name))
        assertNull(LaunchRoute.parse("FLOAT_SOMETHING_REMOVED"))
        assertNull(LaunchRoute.parse(null))
    }
}

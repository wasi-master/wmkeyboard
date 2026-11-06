package com.wasimaster.wmkeyboard.ime.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ROM detection, the probe's verdict and dumpsys parsing: the pure parts of the launch routes. */
class LaunchDetectionTest {

    private fun family(
        brand: String,
        manufacturer: String = brand,
        props: Map<String, String> = emptyMap(),
        classes: Set<String> = emptySet(),
        chromeOs: Boolean = false,
    ) = RomFamily.resolve(brand, manufacturer, { props[it] }, { it in classes }, chromeOs)

    @Test
    fun `framework classes win over the brand`() {
        assertEquals(
            RomFamily.COLOROS,
            family("OnePlus", classes = setOf("com.oplus.flexiblewindow.FlexibleWindowManager")),
        )
        assertEquals(RomFamily.MIUI, family("POCO", classes = setOf("miui.app.MiuiFreeFormManager")))
    }

    @Test
    fun `brands fall back to their skin`() {
        assertEquals(RomFamily.COLOROS, family("realme"))
        assertEquals(RomFamily.COLOROS, family("OPPO"))
        assertEquals(RomFamily.MIUI, family("Redmi", "Xiaomi"))
        assertEquals(RomFamily.SAMSUNG, family("samsung"))
        assertEquals(RomFamily.VIVO, family("iQOO", "vivo"))
        assertEquals(RomFamily.MOTOROLA, family("motorola"))
        assertEquals(RomFamily.MEIZU, family("Meizu"))
        assertEquals(RomFamily.NUBIA, family("nubia"))
        assertEquals(RomFamily.AOSP, family("google", "Google"))
        assertEquals(RomFamily.AOSP, family("Nothing"))
    }

    @Test
    fun `honor is not huawei even with emui properties`() {
        assertEquals(RomFamily.HONOR, family("HONOR", props = mapOf("ro.build.version.emui" to "EmotionUI_12")))
        assertEquals(RomFamily.HUAWEI, family("HUAWEI", props = mapOf("ro.build.version.emui" to "EmotionUI_12")))
    }

    @Test
    fun `hyperos property marks xiaomi`() {
        assertEquals(RomFamily.MIUI, family("unknown", props = mapOf("ro.mi.os.version.name" to "OS2.0")))
    }

    @Test
    fun `chromeos first`() {
        assertEquals(RomFamily.CHROMEOS, family("google", chromeOs = true))
    }

    // ---- Probe verdict ----

    private fun report(w: Int, h: Int) = ProbeReport(w, h, 1080, 2400)

    @Test
    fun `full screen passes nothing`() {
        assertFalse(RouteProbe.verdict(LaunchRoute.Kind.FLOATING, report(1080, 2400)))
        assertFalse(RouteProbe.verdict(LaunchRoute.Kind.SPLIT, report(1080, 2400)))
    }

    @Test
    fun `a half is split, not floating`() {
        assertTrue(RouteProbe.verdict(LaunchRoute.Kind.SPLIT, report(1080, 1180)))
        assertFalse(RouteProbe.verdict(LaunchRoute.Kind.FLOATING, report(1080, 1180)))
        // Landscape halves.
        assertTrue(RouteProbe.verdict(LaunchRoute.Kind.SPLIT, ProbeReport(1190, 1080, 2400, 1080)))
    }

    @Test
    fun `coloros floating window measured on a CPH2481`() {
        // Task bounds 104,282-1184,2202 on a 1240x2772 screen.
        val r = ProbeReport(1080, 1920, 1240, 2772)
        assertTrue(RouteProbe.verdict(LaunchRoute.Kind.FLOATING, r))
        assertFalse(RouteProbe.verdict(LaunchRoute.Kind.SPLIT, r))
    }

    @Test
    fun `a sliver is neither`() {
        assertFalse(RouteProbe.verdict(LaunchRoute.Kind.SPLIT, report(1080, 300)))
        assertTrue(RouteProbe.verdict(LaunchRoute.Kind.FLOATING, report(298, 817)))
        assertFalse(RouteProbe.verdict(LaunchRoute.Kind.FLOATING, ProbeReport(0, 0, 0, 0)))
    }

    // ---- dumpsys ----

    private val dump14 = """
        Display #0 (activities from top to bottom):
          * Task{3f2a1b #812 type=standard A=10234:com.example.notes U=0 visible=true visibleRequested=true mode=multi-window translucent=false sz=1}
          * Task{9a8b7c #811 type=standard A=10111:com.example.video U=0 visible=true visibleRequested=true mode=multi-window translucent=false sz=1}
          * Task{1c2d3e #640 type=home A=10050:com.android.launcher3 U=0 visible=false mode=fullscreen translucent=false sz=1}
          * Task{4d5e6f #700 type=standard I=com.example.noaffinity/.Main U=0 visible=false mode=fullscreen translucent=false sz=1}
    """.trimIndent()

    @Test
    fun `task ids by package, topmost first`() {
        assertEquals(812, ShellParsing.topTaskOf(dump14, "com.example.notes"))
        assertEquals(811, ShellParsing.topTaskOf(dump14, "com.example.video"))
        assertEquals(700, ShellParsing.topTaskOf(dump14, "com.example.noaffinity"))
        assertNull(ShellParsing.topTaskOf(dump14, "com.example.missing"))
    }

    @Test
    fun `android 9 task records`() {
        val dump = "  * TaskRecord{ab12cd #45 A=com.example.old U=0 StackId=3 sz=1}"
        assertEquals(45, ShellParsing.topTaskOf(dump, "com.example.old"))
    }

    @Test
    fun `split visible needs two visible multi-window app tasks`() {
        assertTrue(ShellParsing.splitVisible(dump14))
        assertFalse(ShellParsing.splitVisible(dump14.replace("mode=multi-window", "mode=fullscreen")))
    }
}

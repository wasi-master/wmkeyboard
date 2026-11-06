package com.wasimaster.wmkeyboard.ime.launch

import com.wasimaster.wmkeyboard.ime.launch.DeviceTraits.Companion.SDK_12L
import com.wasimaster.wmkeyboard.ime.launch.DeviceTraits.Companion.SDK_14
import com.wasimaster.wmkeyboard.ime.launch.DeviceTraits.Companion.SDK_16

/**
 * Which routes to try, best first, for opening an app floating or in split
 * screen on a given device. Pure: [DeviceTraits] in, ordered list out. The
 * first route that passes calibration on the phone is kept ([RouteStore]);
 * until one has, launches walk the list.
 *
 * Where the orders come from (read in source, 2026-11):
 *
 * - **Floating** is a windowing mode in the options bundle, and each skin has
 *   its own number: AOSP and Samsung 5 (Samsung's pop-up view asks for exactly
 *   that), ColorOS 100 with zoom flag 4 (FlymeFreeform), Huawei/Honor 102
 *   (`ActivityManagerEx.startActivityOneStep`), vivo 106 and Meizu 11
 *   (XGesture, GestureEVO), Motorola 5 plus `key_moto_flags`. MIUI and
 *   HyperOS build theirs in `MiuiFreeFormManager`. `SafeActivityOptions` does
 *   not guard any of these; the window manager simply ignores a mode the
 *   display cannot do. HyperOS 3 refuses mode 5 from apps (sidebar-hyperos),
 *   and stock phones have no freeform at all, which is where the shell route
 *   and the experimental emulated window come in.
 * - **Split screen**: launch-adjacent works straight from a keyboard on 12L to
 *   13 before QPR1. From 13 QPR1 `ActivityStarter` drops the flag when no
 *   source activity is given, so the keyboard starts it from its trampoline.
 *   Huawei has its own split modes. The accessibility switch only works where
 *   a skin kept a handler for it (AOSP removed it in 12L), and the shell route
 *   is WM Shell's own `moveToSideStage` command, there from Android 14.
 */
object LaunchRoutePlanner {

    fun floating(t: DeviceTraits, emulatedAllowed: Boolean): List<LaunchRoute> = buildList {
        val freeformLike = t.freeform || t.desktopDisplay
        when (t.family) {
            RomFamily.COLOROS -> add(LaunchRoute.FLOAT_COLOROS)
            RomFamily.SAMSUNG -> add(LaunchRoute.FLOAT_FREEFORM)
            RomFamily.MIUI -> {
                add(LaunchRoute.FLOAT_MIUI_FACTORY)
                add(LaunchRoute.FLOAT_MIUI_SCALED)
            }
            RomFamily.HUAWEI, RomFamily.HONOR -> add(LaunchRoute.FLOAT_HUAWEI)
            RomFamily.VIVO -> {
                add(LaunchRoute.FLOAT_VIVO)
                add(LaunchRoute.FLOAT_FREEFORM)
            }
            RomFamily.MOTOROLA -> add(LaunchRoute.FLOAT_MOTOROLA)
            RomFamily.MEIZU -> add(LaunchRoute.FLOAT_MEIZU)
            RomFamily.NUBIA -> add(LaunchRoute.FLOAT_NUBIA)
            RomFamily.CHROMEOS -> add(LaunchRoute.FLOAT_FREEFORM)
            RomFamily.AOSP -> if (freeformLike) add(LaunchRoute.FLOAT_FREEFORM)
        }
        // Freeform turned on (developer option, desktop mode) works on any skin.
        if (freeformLike && LaunchRoute.FLOAT_FREEFORM !in this) add(LaunchRoute.FLOAT_FREEFORM)
        if (t.desktopDisplay) add(LaunchRoute.FLOAT_BOUNDS)
        if (t.shell) add(LaunchRoute.FLOAT_SHELL)
        if (emulatedAllowed && t.shell) add(LaunchRoute.FLOAT_EMULATED)
    }

    fun split(t: DeviceTraits): List<LaunchRoute> = buildList {
        if (!t.splitFramework) return@buildList
        if (t.family == RomFamily.HUAWEI || t.family == RomFamily.HONOR) add(LaunchRoute.SPLIT_HUAWEI)
        if (t.sdk >= SDK_12L) {
            if (t.adjacentNeedsSource) {
                add(LaunchRoute.SPLIT_TRAMPOLINE_ADJ_FIRST)
                add(LaunchRoute.SPLIT_TRAMPOLINE_PLAIN_FIRST)
            } else {
                add(LaunchRoute.SPLIT_SERVICE)
                add(LaunchRoute.SPLIT_TRAMPOLINE_PLAIN_FIRST)
            }
        }
        // The switch: below 12L it is AOSP's own; above, only where the skin
        // still lists it among the system actions (or nobody could ask yet).
        if (t.helperOn && (t.sdk < SDK_12L || t.toggleSplitAction != false)) add(LaunchRoute.SPLIT_HELPER)
        if (t.shell && t.sdk >= SDK_14) add(LaunchRoute.SPLIT_SHELL)
    }

    /**
     * Wait between a pair's two launches on the routes that make them from the
     * keyboard itself (the trampoline makes both in one go and needs none).
     * Android 16 refuses a launch-adjacent start for a second after split
     * screen opens; Xiaomi's skins and Android 15 let the first app settle
     * slower (Split Screen 1.3.8's timings).
     */
    fun pairGapMs(t: DeviceTraits, viaHelper: Boolean): Long = when {
        t.sdk >= SDK_16 -> ANDROID_16_GAP_MS
        viaHelper && (t.family == RomFamily.MIUI || t.sdk == SDK_15) -> SLOW_HELPER_GAP_MS
        else -> DEFAULT_GAP_MS
    }

    /** Gap between the helper's switch and the launch into the free half. */
    const val HELPER_SWITCH_GAP_MS = 600L
    const val DEFAULT_GAP_MS = 650L
    const val SLOW_HELPER_GAP_MS = 2_200L

    /** StageCoordinator's one-second lock after split screen opens, plus a margin. */
    const val ANDROID_16_GAP_MS = 1_100L
    private const val SDK_15 = 35
}

package com.wasimaster.wmkeyboard.ime.launch

/**
 * One way of opening an app floating or in split screen. Ids are stored (the
 * route that passed calibration on this phone, [RouteStore]), so never rename
 * or reorder-by-ordinal: storage uses [name].
 *
 * The table behind each one, with its sources, is in the docs of
 * [LaunchRoutePlanner] and [LaunchOptions].
 */
enum class LaunchRoute(val kind: Kind, val needsShell: Boolean = false, val experimental: Boolean = false) {
    // ---- Floating -------------------------------------------------------------

    /** ColorOS flexible window: windowing mode 100 plus the zoom launch flag 4. */
    FLOAT_COLOROS(Kind.FLOATING),

    /** Mode 5 with centred bounds: AOSP freeform, Samsung pop-up view, ChromeOS, DeX. */
    FLOAT_FREEFORM(Kind.FLOATING),

    /** Huawei and Honor freeform: windowing mode 102. */
    FLOAT_HUAWEI(Kind.FLOATING),

    /** Flyme: windowing mode 11. */
    FLOAT_MEIZU(Kind.FLOATING),

    /** vivo small window: windowing mode 106. */
    FLOAT_VIVO(Kind.FLOATING),

    /** Motorola: mode 5 plus its own `key_moto_flags` bit. */
    FLOAT_MOTOROLA(Kind.FLOATING),

    /** MIUI and HyperOS: the options MIUI's own sidebar asks its framework for. */
    FLOAT_MIUI_FACTORY(Kind.FLOATING),

    /** MIUI and HyperOS: mode 5 with bounds pre-scaled for MIUI's 0.7 window scale. */
    FLOAT_MIUI_SCALED(Kind.FLOATING),

    /** Nubia and RedMagic: the intent identifier their window manager looks for. */
    FLOAT_NUBIA(Kind.FLOATING),

    /** Bounds alone, the public API: floats only on a display already in desktop mode. */
    FLOAT_BOUNDS(Kind.FLOATING),

    /** `am start --windowingMode` as the shell (Shizuku or root), then a task resize. */
    FLOAT_SHELL(Kind.FLOATING, needsShell = true),

    /** The app drawn into a virtual display inside an overlay window. Opt-in. */
    FLOAT_EMULATED(Kind.FLOATING, needsShell = true, experimental = true),

    // ---- Split screen ---------------------------------------------------------

    /** Launch-adjacent straight from the keyboard: Android 12L to 13 before QPR1. */
    SPLIT_SERVICE(Kind.SPLIT),

    /**
     * Through [LaunchTrampolineActivity], which is the source activity the
     * system wants: it starts the new app adjacent first, then brings the
     * other one into the main half.
     */
    SPLIT_TRAMPOLINE_ADJ_FIRST(Kind.SPLIT),

    /** Same activity, other order: the other app first, then the new one adjacent. */
    SPLIT_TRAMPOLINE_PLAIN_FIRST(Kind.SPLIT),

    /** Huawei's own split windowing modes, 100 (primary) and 101 (secondary). */
    SPLIT_HUAWEI(Kind.SPLIT),

    /** The accessibility split-screen switch, then a launch into the free half. */
    SPLIT_HELPER(Kind.SPLIT),

    /** WM Shell's `splitscreen moveToSideStage` shell command (Android 14+), via Shizuku or root. */
    SPLIT_SHELL(Kind.SPLIT, needsShell = true),
    ;

    enum class Kind { FLOATING, SPLIT }

    companion object {
        fun parse(name: String?): LaunchRoute? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}

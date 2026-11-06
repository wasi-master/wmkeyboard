package com.wasimaster.wmkeyboard.ime.launch

/**
 * The phone maker's skin, as far as multi-window goes. Each one floats and
 * splits apps its own way; [LaunchRoutePlanner] orders the routes per family.
 *
 * Detected from framework classes first (a skin's own window manager is the
 * surest sign), then system properties, then the brand: OnePlus and realme
 * run ColorOS, Redmi and POCO run MIUI or HyperOS, and so on.
 */
enum class RomFamily {
    /** ColorOS, OxygenOS 12+ and realme UI: floating window mode 100. */
    COLOROS,
    SAMSUNG,

    /** MIUI and HyperOS, Xiaomi, Redmi and POCO. */
    MIUI,

    /** EMUI and HarmonyOS on Huawei: windowing modes 100–102. */
    HUAWEI,

    /** MagicOS on Honor: mode 102 like Huawei. */
    HONOR,

    /** OriginOS and Funtouch OS. */
    VIVO,
    MOTOROLA,

    /** Flyme: floating mode 11. */
    MEIZU,

    /** Nubia and RedMagic. */
    NUBIA,

    /** Android apps on a Chromebook: every app is a window already. */
    CHROMEOS,

    /** Pixel, Nothing, Fairphone, LineageOS and every skin not listed. */
    AOSP,
    ;

    companion object {
        /**
         * Pure resolver, so the detection order is unit-tested. [prop] reads a
         * system property (null or empty when unset); [hasClass] says whether a
         * framework class loads.
         */
        fun resolve(
            brand: String,
            manufacturer: String,
            prop: (String) -> String?,
            hasClass: (String) -> Boolean,
            chromeOs: Boolean,
        ): RomFamily {
            val b = brand.lowercase()
            val m = manufacturer.lowercase()
            fun set(name: String) = !prop(name).isNullOrBlank()
            return when {
                chromeOs -> CHROMEOS
                hasClass("com.oplus.flexiblewindow.FlexibleWindowManager") ||
                    hasClass("com.oplus.zoomwindow.OplusZoomWindowManager") ||
                    set("ro.build.version.oplusrom") -> COLOROS
                b == "samsung" || m == "samsung" ||
                    hasClass("com.samsung.android.multiwindow.MultiWindowManager") -> SAMSUNG
                hasClass("miui.app.MiuiFreeFormManager") || set("ro.miui.ui.version.name") ||
                    set("ro.mi.os.version.name") -> MIUI
                b == "honor" || m == "honor" || set("ro.build.version.magic") -> HONOR
                hasClass("com.huawei.android.app.ActivityManagerEx") || set("ro.build.version.emui") ||
                    b == "huawei" || m == "huawei" -> HUAWEI
                set("ro.vivo.os.version") || b == "vivo" || b == "iqoo" || m == "vivo" -> VIVO
                b in XIAOMI_BRANDS || m == "xiaomi" -> MIUI
                b in OPLUS_BRANDS || m in OPLUS_BRANDS -> COLOROS
                b == "motorola" || m == "motorola" || b == "moto" -> MOTOROLA
                b == "meizu" || m == "meizu" || set("ro.build.flyme.version") -> MEIZU
                b == "nubia" || m == "nubia" || b == "redmagic" -> NUBIA
                else -> AOSP
            }
        }

        private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
        private val OPLUS_BRANDS = setOf("oppo", "oneplus", "realme")
    }
}

/**
 * What this device offers for opening apps side by side or floating, read once
 * per launch decision. Everything [LaunchRoutePlanner] needs, as plain values,
 * so the planner is a pure function the unit tests drive with any device.
 */
data class DeviceTraits(
    val sdk: Int,
    val family: RomFamily,
    /**
     * Android 13 QPR1 or later: the system drops a launch-adjacent start that
     * has no source activity (ActivityStarter, 13.0.0_r16 onwards), so a
     * keyboard has to start split screen from an activity of its own.
     */
    val adjacentNeedsSource: Boolean,
    /** The framework allows split screen at all (not TV, watch, low-RAM or tiny screens). */
    val splitFramework: Boolean,
    /** `FEATURE_FREEFORM_WINDOW_MANAGEMENT`, or the developer option set before boot. */
    val freeform: Boolean,
    /** A display already in desktop (freeform) mode: DeX, desktop windowing, external screen. */
    val desktopDisplay: Boolean,
    /**
     * Whether the system honours the accessibility split-screen switch. AOSP
     * dropped its handler in 12L; some skins kept their own. Null when the
     * helper is off and nobody could ask.
     */
    val toggleSplitAction: Boolean?,
    /** The split-screen helper accessibility service is connected. */
    val helperOn: Boolean,
    /** Shizuku (or Sui), or root the user allowed, can run shell commands now. */
    val shell: Boolean,
    /** The keyboard holds WRITE_SECURE_SETTINGS (granted over adb or by the shell). */
    val secureSettings: Boolean,
) {
    val splitWithoutHelper: Boolean get() = splitFramework && sdk >= SDK_12L
    val splitPossible: Boolean
        get() = splitFramework && (sdk >= SDK_12L || helperOn || shell)

    companion object {
        const val SDK_12L = 32
        const val SDK_13 = 33
        const val SDK_14 = 34
        const val SDK_16 = 36
    }
}

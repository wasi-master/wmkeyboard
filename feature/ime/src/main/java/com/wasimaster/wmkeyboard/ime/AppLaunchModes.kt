package com.wasimaster.wmkeyboard.ime

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import com.wasimaster.wmkeyboard.core.accessibility.SplitScreenHelperService
import com.wasimaster.wmkeyboard.core.settings.LauncherOpenMode

/**
 * How the app-launcher tool opens an app normally, in a floating window or in
 * split screen, from a keyboard: a service context with no activity of its own
 * to launch "beside".
 *
 * **Split screen** is [Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT]. From Android 12L
 * (API 32) the system split screen owns a "launch-adjacent root", and the
 * window manager routes a launch carrying the flag into it whenever there is
 * no source task in the way. A keyboard has no source task, so its launch
 * lands in the side stage and the shell pulls the current full-screen app into
 * the other half. Below 12L the flag only does something while split screen is
 * already up, so the option is hidden there ([splitSupported]) unless the
 * split-screen helper is on.
 *
 * **The split-screen helper** ([SplitScreenHelperService], an opt-in
 * accessibility service) is the route the Split Screen app (any.splitscreen
 * 1.3.8) takes on the phones where the flag alone opens full screen: press
 * the system's split-screen switch with the current app in front, wait
 * [HELPER_LAUNCH_DELAY_MS] for the free half, then make the same
 * launch-adjacent start into it. That app uses it below Android 16 and not
 * on Samsung, which has its own route, so [splitHelperApplies] keeps to the
 * same devices. With the helper on, split screen is offered before 12L too:
 * the switch has been there since Android 7.
 *
 * [Intent.FLAG_ACTIVITY_MULTIPLE_TASK] is added only when the target is the
 * app already on screen: the platform needs it to put a second instance of
 * the same app beside itself, and anywhere else it would spawn a duplicate
 * task instead of bringing the existing one over.
 *
 * **Floating** is [ActivityOptions.setLaunchBounds], public since API 24. The
 * window manager honours the bounds only where the display is already in a
 * freeform (desktop) windowing mode: a tablet or foldable with desktop
 * windowing, a Chromebook, an external desktop display. On a phone the bounds
 * are dropped and the app opens full screen. Forcing the freeform windowing
 * mode would need `ActivityOptions.setLaunchWindowingMode`, a blocked hidden
 * API, so it is not called.
 *
 * **ColorOS** (OPPO, OnePlus, realme) has no freeform feature but floats apps
 * in its own "flexible window", windowing mode 100, which is what its smart
 * sidebar opens. That mode rides in the options bundle under the platform's
 * own key ([WINDOWING_MODE_KEY]), a plain bundle entry the window manager
 * reads on every start, so no hidden method is involved. Measured on a
 * CPH2481 (ColorOS 15): a start carrying it opens as a floating window. The
 * device is recognised by its framework class ([colorOsFlexibleWindow]).
 *
 * A keyboard is exempt from the background-activity-start rules while it is
 * the current input method (its window, shown or hidden, belongs to the IME),
 * which is what lets a split combo's second launch run a moment after the
 * first one has already taken focus away from the text field.
 */
object AppLaunchModes {

    /**
     * Gap between a split combo's two launches. The first app has to be the
     * top full-screen task before the second arrives, or the shell pairs the
     * second with whatever was on screen before; the open transition runs
     * about 350 ms on stock animation scales.
     */
    const val COMBO_SECOND_LAUNCH_DELAY_MS = 650L

    /**
     * Gap between the helper's split-screen switch and the launch into the
     * free half. Split Screen 1.3.8's timing, not measured here.
     */
    const val HELPER_LAUNCH_DELAY_MS = 600L

    /**
     * Gap before the helper presses the switch for a split pair, on the
     * systems where Split Screen 1.3.8 waits longer for the first app to
     * settle (Android 15 and Xiaomi's skins). Its timing, not measured here.
     */
    private const val HELPER_SLOW_COMBO_DELAY_MS = 2_200L

    /** The platform's options-bundle key for the launch windowing mode. */
    private const val WINDOWING_MODE_KEY = "android.activity.windowingMode"

    /** ColorOS's flexible (floating) window windowing mode. */
    private const val COLOROS_FLEXIBLE_WINDOWING_MODE = 100

    /** Share of the screen a floating window asks for, centred. */
    private const val FLOATING_FRACTION = 0.7f

    /**
     * Whether the keyboard can enter split screen: Android 12L or later, or
     * any version with the split-screen helper turned on, on a device whose
     * framework allows split screen at all (TV and watch builds do not). The
     * framework flags are read by name from the public system resources; a
     * missing one counts as yes.
     */
    fun splitSupported(context: Context): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 || splitHelperEnabled(context)) &&
            systemBool("config_supportsMultiWindow") &&
            systemBool("config_supportsSplitScreenMultiWindow") &&
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /**
     * Whether the split-screen helper is for this device at all: below
     * Android 16 and not on Samsung (see the class doc). Where it is not, the
     * settings leave its row out and the launcher never calls it.
     */
    val splitHelperApplies: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA &&
            !Build.BRAND.equals("samsung", ignoreCase = true)

    /** The helper is turned on in Settings › Accessibility and applies here. */
    fun splitHelperEnabled(context: Context): Boolean =
        splitHelperApplies && SplitScreenHelperService.isEnabled(context)

    /** The helper to press the switch with, or null to launch without it. */
    fun splitHelper(): SplitScreenHelperService? =
        SplitScreenHelperService.current?.takeIf { splitHelperApplies }

    /** How long a split pair's first app gets before the helper presses the switch. */
    fun helperComboDelayMs(): Long {
        val xiaomi = Build.BRAND.lowercase() in XIAOMI_BRANDS ||
            Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)
        return if (Build.VERSION.SDK_INT == Build.VERSION_CODES.VANILLA_ICE_CREAM || xiaomi) {
            HELPER_SLOW_COMBO_DELAY_MS
        } else {
            COMBO_SECOND_LAUNCH_DELAY_MS
        }
    }

    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")

    /**
     * Whether this device has any freeform window management: the declared
     * feature, or the developer option that turns it on. True does not promise
     * a floating window (see the class doc): a phone with the developer option
     * set still opens full screen unless its display is in desktop mode.
     */
    fun freeformSupported(context: Context): Boolean =
        colorOsFlexibleWindow ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            runCatching {
                Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0
            }.getOrDefault(false)

    /**
     * Adds the flags [mode] needs to [intent] and returns the options bundle to
     * start it with. [foregroundPackage] is the app currently on screen, for
     * the same-app split case.
     */
    fun prepare(
        context: Context,
        intent: Intent,
        mode: LauncherOpenMode,
        foregroundPackage: String?,
    ): Bundle? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return when (mode) {
            LauncherOpenMode.NORMAL -> null
            LauncherOpenMode.SPLIT -> {
                intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                val target = intent.component?.packageName ?: intent.`package`
                if (target != null && target == foregroundPackage) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                null
            }
            LauncherOpenMode.FLOATING -> if (colorOsFlexibleWindow) {
                // ColorOS sizes and places its own window; bounds would only
                // fight its default.
                ActivityOptions.makeBasic().toBundle().apply {
                    putInt(WINDOWING_MODE_KEY, COLOROS_FLEXIBLE_WINDOWING_MODE)
                }
            } else {
                ActivityOptions.makeBasic().setLaunchBounds(floatingBounds(context)).toBundle()
            }
        }
    }

    /** ColorOS's flexible window manager is on this device (see the class doc). */
    private val colorOsFlexibleWindow: Boolean by lazy {
        runCatching { Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager") }.isSuccess ||
            runCatching { Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager") }.isSuccess
    }

    /** A centred window [FLOATING_FRACTION] of the screen in each direction. */
    private fun floatingBounds(context: Context): Rect {
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        } else {
            null
        } ?: context.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
        val width = (screen.width() * FLOATING_FRACTION).toInt()
        val height = (screen.height() * FLOATING_FRACTION).toInt()
        val left = screen.left + (screen.width() - width) / 2
        val top = screen.top + (screen.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun systemBool(name: String): Boolean = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier(name, "bool", "android")
        if (id == 0) true else res.getBoolean(id)
    }.getOrDefault(true)
}

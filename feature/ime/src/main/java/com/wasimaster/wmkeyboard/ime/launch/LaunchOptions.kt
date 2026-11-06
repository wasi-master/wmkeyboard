package com.wasimaster.wmkeyboard.ime.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.wasimaster.wmkeyboard.core.shell.HiddenApi

/**
 * Options bundles and intent tweaks for the routes that need no special
 * identity. Every windowing mode rides as the platform's own bundle key, the
 * one `ActivityOptions.setLaunchWindowingMode` writes, so no hidden method is
 * called for it. Keys and numbers, with where each was read:
 *
 * | Key | Value | Source |
 * |---|---|---|
 * | `android.activity.windowingMode` | 5 freeform, 100 ColorOS, 102 Huawei, 106 vivo, 11 Flyme | AOSP ActivityOptions; FlymeFreeform, XGesture, Huawei ActivityManagerEx |
 * | `android:activity.mZoomLaunchFlags` | 4 | ColorOS zoom window (FlymeFreeform) |
 * | `android:activity.launchBounds` | Rect | AOSP, public setLaunchBounds |
 * | `key_moto_flags` | 0x100000 | Motorola's freeform (z2term handbook) |
 * | `intent.identifier` | `_WindowReply` | Nubia (XGesture) |
 */
object LaunchOptions {

    const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
    private const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"
    private const val MOTO_FLAGS_KEY = "key_moto_flags"

    const val MODE_FREEFORM = 5
    const val MODE_COLOROS_FLOAT = 100
    const val MODE_HUAWEI_PRIMARY = 100
    const val MODE_HUAWEI_SECONDARY = 101
    const val MODE_HUAWEI_FLOAT = 102
    const val MODE_VIVO_FLOAT = 106
    const val MODE_MEIZU_FLOAT = 11
    private const val COLOROS_ZOOM_FLAG = 4
    private const val MOTO_FREEFORM_FLAG = 0x100000
    private const val NUBIA_IDENTIFIER = "_WindowReply"

    /** MIUI shrinks a freeform window to 0.7 of the bounds asked for. */
    private const val MIUI_SCALE = 0.7f

    /** Share of the screen a floating window asks for, centred. */
    const val FLOATING_FRACTION = 0.7f

    /** The windowing mode a skin floats with, for the shell route too. */
    fun floatMode(family: RomFamily): Int = when (family) {
        RomFamily.COLOROS -> MODE_COLOROS_FLOAT
        RomFamily.HUAWEI, RomFamily.HONOR -> MODE_HUAWEI_FLOAT
        RomFamily.VIVO -> MODE_VIVO_FLOAT
        RomFamily.MEIZU -> MODE_MEIZU_FLOAT
        else -> MODE_FREEFORM
    }

    /**
     * Options for a floating start along [route], or null when the route is
     * not a bundle route or its pieces are missing on this device (the MIUI
     * factory on a phone without it). Adds the intent tweaks [route] needs.
     */
    fun floating(context: Context, route: LaunchRoute, intent: Intent): Bundle? {
        val bounds = floatingBounds(context)
        return when (route) {
            LaunchRoute.FLOAT_COLOROS -> mode(MODE_COLOROS_FLOAT).apply { putInt(ZOOM_FLAGS_KEY, COLOROS_ZOOM_FLAG) }
            LaunchRoute.FLOAT_FREEFORM -> mode(MODE_FREEFORM, bounds)
            LaunchRoute.FLOAT_HUAWEI -> mode(MODE_HUAWEI_FLOAT, bounds)
            LaunchRoute.FLOAT_VIVO -> mode(MODE_VIVO_FLOAT, bounds)
            LaunchRoute.FLOAT_MEIZU -> mode(MODE_MEIZU_FLOAT, bounds)
            LaunchRoute.FLOAT_MOTOROLA -> mode(MODE_FREEFORM, bounds).apply {
                putInt(MOTO_FLAGS_KEY, getInt(MOTO_FLAGS_KEY, 0) or MOTO_FREEFORM_FLAG)
            }
            LaunchRoute.FLOAT_MIUI_FACTORY -> miuiFactory(context, intent.component?.packageName ?: intent.`package`)
            LaunchRoute.FLOAT_MIUI_SCALED -> mode(MODE_FREEFORM, scaled(bounds, context))
            LaunchRoute.FLOAT_NUBIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent.identifier = NUBIA_IDENTIFIER
                Bundle()
            }
            LaunchRoute.FLOAT_BOUNDS -> ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle()
            else -> null
        }
    }

    /** Huawei's split modes: the app already up becomes primary, the new one secondary. */
    fun huaweiSplit(primary: Boolean): Bundle = mode(if (primary) MODE_HUAWEI_PRIMARY else MODE_HUAWEI_SECONDARY)

    private fun mode(mode: Int, bounds: Rect? = null): Bundle {
        val options = ActivityOptions.makeBasic()
        if (bounds != null) options.setLaunchBounds(bounds)
        return options.toBundle().apply { putInt(WINDOWING_MODE_KEY, mode) }
    }

    /**
     * MIUI and HyperOS build a small window's options in their own framework:
     * `MiuiFreeFormManager.getActivityOptions(Context, String, boolean, boolean)`
     * on newer builds, `MiuiMultiWindowUtils.getActivityOptions(Context, String,
     * boolean)` on older ones. MIUI's sidebar calls the same; whether an app's
     * uid may is what calibration finds out.
     */
    private fun miuiFactory(context: Context, packageName: String?): Bundle? {
        packageName ?: return null
        HiddenApi.exempt()
        val options = runCatching {
            Class.forName("miui.app.MiuiFreeFormManager").getDeclaredMethod(
                "getActivityOptions",
                Context::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(null, context, packageName, true, false) as? ActivityOptions
        }.getOrNull() ?: runCatching {
            Class.forName("android.util.MiuiMultiWindowUtils").getDeclaredMethod(
                "getActivityOptions",
                Context::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(null, context, packageName, true) as? ActivityOptions
        }.getOrNull()
        return options?.toBundle()
    }

    /** A centred window [FLOATING_FRACTION] of the screen each way. */
    fun floatingBounds(context: Context): Rect {
        val screen = screenBounds(context)
        val width = (screen.width() * FLOATING_FRACTION).toInt()
        val height = (screen.height() * FLOATING_FRACTION).toInt()
        val left = screen.left + (screen.width() - width) / 2
        val top = screen.top + (screen.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    /**
     * Bounds MIUI will scale by 0.7 into the size asked for: without this its
     * small window comes out a sliver (sidebar-hyperos measured 298×817).
     */
    private fun scaled(bounds: Rect, context: Context): Rect {
        val screen = screenBounds(context)
        val width = (bounds.width() / MIUI_SCALE).toInt().coerceAtMost(screen.width())
        val height = (bounds.height() / MIUI_SCALE).toInt().coerceAtMost(screen.height())
        return Rect(bounds.left, bounds.top, bounds.left + width, bounds.top + height)
    }

    fun screenBounds(context: Context): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        } else {
            null
        } ?: context.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
}

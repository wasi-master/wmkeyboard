package com.wasimaster.wmkeyboard.core.util

import android.content.Context
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * Whether this device has Google Play services, and whether its downloadable
 * font provider answers.
 *
 * Almost nothing in the keyboard cares: typing, prediction, glide, themes, the
 * bundled OCR and QR models and every download the app makes are its own code
 * over plain HTTPS. Two features are not the keyboard's own, and they are the
 * reason this exists — the document scanner is a Play services activity, and
 * the Google Fonts choices are files fetched from a Play services content
 * provider. On a device without either, both would be offered and then quietly
 * do nothing, which reads as a broken keyboard rather than a missing platform
 * piece. The answers here let the screens leave them out.
 *
 * Both probes are `PackageManager` lookups, so the app's manifest declares the
 * package and the provider authority in its `<queries>` block: without that,
 * API 30+ package visibility hides Play services from the lookup and a phone
 * that has it would be read as a phone that does not.
 *
 * A clone of the Play services package (microG) answers both probes the same
 * way, which is the intent — it implements some modules and not others, so the
 * feature is offered and its own failure path (a toast, a kept eraser brush)
 * takes over when a module is missing.
 *
 * Answers are cached for the life of the process. Play services can be
 * installed or removed while the app runs, but the keyboard's own process
 * restarts long before anyone notices, and re-probing on every toolbar frame
 * would be a binder call per key row.
 */
object PlayServices {

    /**
     * The F-Droid build answers "no" to both probes without asking the device.
     *
     * Not because the APK carries anything of Google's — it carries none, and
     * the font dependency is `androidx.compose.ui:ui-text-google-fonts`, which
     * is AndroidX and free software. The provider is reached by *name*, so the
     * only thing in the build is the string below.
     *
     * It is off there because of what the request does rather than what links
     * it: fetching a face through Play services is a connection the user did
     * not ask for, made to a proprietary service, and that is the one thing the
     * F-Droid listing promises does not happen. A script with no font of its own
     * falls back to the platform face, which on any current Android carries
     * Noto for these scripts anyway; a user who wants a specific face can still
     * import a file or install a font addon.
     *
     * Every other build answers "no" the same way while Privacy › Google
     * libraries is at its second level ([GoogleLibraries.switchedOff]), which
     * promises the same thing. The probes below still ask the device, so the
     * answers they cache stay true at either level.
     */
    private val channelAllowsGms: Boolean
        get() = !BuildConfig.ENABLE_FDROID && !GoogleLibraries.switchedOff

    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val FONTS_AUTHORITY = "com.google.android.gms.fonts"

    @Volatile
    private var installed: Boolean? = null

    @Volatile
    private var fontProvider: Boolean? = null

    /**
     * The cached answer to [isInstalled] for callers with no `Context` —
     * `isSupportedTool`, which runs deep inside toolbar layout and settings
     * lists. True until [prime] has run: a tool that appears and is then
     * filtered out one frame later is a flicker, whereas a tool that never
     * appears on a device that does support it is a bug report. Both the
     * keyboard service and the settings activity prime this in `onCreate`,
     * before either draws.
     */
    val available: Boolean
        get() = channelAllowsGms && (installed ?: true)

    /** Answers both probes now, so later reads are free and [available] is real. */
    fun prime(context: Context) {
        isInstalled(context)
        hasFontProvider(context)
    }

    /** Whether the Play services package is installed and enabled. */
    fun isInstalled(context: Context): Boolean =
        channelAllowsGms && (installed ?: probeInstalled(context).also { installed = it })

    /**
     * Whether the downloadable-font provider behind every `google:<Name>` font
     * id is on the device. False means the Google Fonts lists are not worth
     * showing: each entry would resolve to the system face.
     */
    fun hasFontProvider(context: Context): Boolean =
        channelAllowsGms && (fontProvider ?: probeFontProvider(context).also { fontProvider = it })

    private fun probeInstalled(context: Context): Boolean = !BuildConfig.ENABLE_FDROID && runCatching {
        context.packageManager.getApplicationInfo(GMS_PACKAGE, 0).enabled
    }.getOrDefault(false)

    private fun probeFontProvider(context: Context): Boolean = !BuildConfig.ENABLE_FDROID && runCatching {
        context.packageManager.resolveContentProvider(FONTS_AUTHORITY, 0) != null
    }.getOrDefault(false)
}

package com.wasimaster.wmkeyboard.ime.launch

import android.content.Context
import android.os.Build

/**
 * The route that passed calibration on this phone, per kind. Device-local
 * plain preferences, never synced: a route is a fact about one build of one
 * skin. Each answer carries the build fingerprint and the [DeviceTraits] parts
 * that change which routes exist (helper, shell, freeform), so an update or a
 * newly granted Shizuku makes the keyboard look again instead of trusting an
 * answer that no longer holds.
 *
 * Three states per kind: a route ([Known.route] set), "none works" (route
 * null, kept so a phone with no floating route does not re-probe every time),
 * or nothing stored yet (null from [get]).
 */
class RouteStore(context: Context) {

    data class Known(val route: LaunchRoute?)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(kind: LaunchRoute.Kind, traits: DeviceTraits): Known? {
        val raw = prefs.getString(kind.name, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 3 || parts[1] != Build.FINGERPRINT || parts[2] != signature(traits)) return null
        return Known(LaunchRoute.parse(parts[0]))
    }

    fun put(kind: LaunchRoute.Kind, traits: DeviceTraits, route: LaunchRoute?) {
        prefs.edit().putString(kind.name, "${route?.name ?: NONE}|${Build.FINGERPRINT}|${signature(traits)}").apply()
    }

    /** Forget both answers, for **Check what works on this phone**. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    /** A line the user can paste into an issue: what this phone answered. */
    fun describe(traits: DeviceTraits): String {
        val floating = get(LaunchRoute.Kind.FLOATING, traits)
        val split = get(LaunchRoute.Kind.SPLIT, traits)
        fun show(known: Known?) = when {
            known == null -> "untested"
            known.route == null -> "none"
            else -> known.route.name
        }
        return "family=${traits.family} sdk=${traits.sdk} model=${Build.MANUFACTURER} ${Build.MODEL} " +
            "floating=${show(floating)} split=${show(split)} shell=${traits.shell} helper=${traits.helperOn} " +
            "freeform=${traits.freeform}"
    }

    private fun signature(t: DeviceTraits): String =
        "h${t.helperOn.bit}s${t.shell.bit}f${t.freeform.bit}d${t.desktopDisplay.bit}"

    private val Boolean.bit get() = if (this) 1 else 0

    private companion object {
        const val PREFS = "launch_routes"
        const val NONE = "-"
    }
}

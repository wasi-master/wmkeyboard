package com.wasimaster.wmkeyboard.ime

import android.content.Context
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import com.wasimaster.wmkeyboard.ime.launch.DeviceTraitsReader
import com.wasimaster.wmkeyboard.ime.launch.EmulatedWindows
import com.wasimaster.wmkeyboard.ime.launch.LaunchExecutor
import com.wasimaster.wmkeyboard.ime.launch.LaunchRoute
import com.wasimaster.wmkeyboard.ime.launch.LaunchRoutePlanner

/**
 * What the app launcher may offer for opening apps floating or in split
 * screen on this phone. The opening itself is [LaunchExecutor]'s, along the
 * route calibration found (see `ime/launch/`); this is the yes/no the panel
 * and the settings screens ask before showing an option.
 *
 * Both answers mean "a route is known to work, or there are routes left to
 * try": an untested phone gets the option, and its first use finds out.
 */
object AppLaunchModes {

    /**
     * Split screen can be tried here: the framework allows it and at least one
     * route exists (Android 12L or later, the helper, Huawei's modes, or the
     * shell), and calibration has not already found that none works.
     */
    fun splitSupported(context: Context): Boolean {
        val traits = DeviceTraitsReader.read(context)
        if (LaunchRoutePlanner.split(traits).isEmpty()) return false
        return LaunchExecutor(context).known(LaunchRoute.Kind.SPLIT)?.let { it.route != null } ?: true
    }

    /**
     * A floating window can be tried here: a route is known to work, or there
     * are routes left to try, or the user picked the experimental emulated
     * window and it is ready. False is what the panel's "may open it full
     * screen" note means.
     */
    fun freeformSupported(context: Context, emulatedAllowed: Boolean = false): Boolean {
        if (emulatedAllowed && EmulatedWindows.ready(context)) return true
        val traits = DeviceTraitsReader.read(context)
        if (LaunchRoutePlanner.floating(traits, emulatedAllowed = false).isEmpty()) return false
        return LaunchExecutor(context).known(LaunchRoute.Kind.FLOATING)?.let { it.route != null } ?: true
    }

    /** Whether the split-screen helper is worth offering on this phone; see [DeviceTraitsReader.helperApplies]. */
    val splitHelperApplies: Boolean get() = DeviceTraitsReader.helperApplies

    /** The shell door the launcher would use now (Shizuku, root or none). */
    fun shellKind() = ShellAccess.kind()
}

package com.wasimaster.wmkeyboard.ime.launch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import com.wasimaster.wmkeyboard.ime.launch.LaunchRoute.Kind
import kotlinx.coroutines.delay

/**
 * Opens apps floating, in split screen, or as a split pair, along the route
 * that works on this phone. The first floating or split request on a device
 * calibrates: each candidate from [LaunchRoutePlanner] launches the probe
 * ([RouteProbe]) until one lands where it should, and that route is kept
 * ([RouteStore]). Later launches go straight along it.
 *
 * [context] is the keyboard service (or the settings activity, for
 * **Check what works on this phone**). A keyboard may start activities while
 * the user is in another app because the current input method is exempt from
 * the background-start rules.
 */
class LaunchExecutor(private val context: Context) {

    enum class Outcome {
        /** Opened the way asked. */
        DONE,

        /** No way to do it on this phone; opened normally instead. */
        FELL_BACK,

        /** Nothing opened at all (the app is gone, or the system refused). */
        FAILED,
    }

    private val store = RouteStore(context)

    fun traits(): DeviceTraits = DeviceTraitsReader.read(context)

    /** The stored answer for [kind], if this phone has one under its current traits. */
    fun known(kind: Kind): RouteStore.Known? = store.get(kind, traits())

    fun describe(): String = store.describe(traits())

    fun forget() = store.clear()

    /**
     * The stored floating route's options when it is a plain bundle route, for
     * starts that must go through `LauncherApps` (a work app without the shell).
     * Null on an uncalibrated phone: the work app then opens full screen.
     */
    fun storedFloatingOptions(target: Intent): Bundle? {
        val route = known(Kind.FLOATING)?.route ?: return null
        if (route.needsShell) return null
        return LaunchOptions.floating(context, route, target)
    }

    // ---- Floating -------------------------------------------------------------

    suspend fun openFloating(target: Intent, userId: Int?, emulatedAllowed: Boolean): Outcome {
        val traits = traits()
        val route = routeFor(Kind.FLOATING, traits, other = null)
            ?: if (emulatedAllowed && ShellAccess.canInject) LaunchRoute.FLOAT_EMULATED else null
        if (route == null) return fallBack(target)
        val ok = when (route) {
            LaunchRoute.FLOAT_SHELL -> shellFloat(target, userId, traits)
            LaunchRoute.FLOAT_EMULATED -> EmulatedWindows.open(context, target.component ?: return fallBack(target), userId)
            else -> start(target, LaunchOptions.floating(context, route, target))
        }
        return if (ok) Outcome.DONE else fallBack(target)
    }

    // ---- Split ----------------------------------------------------------------

    /** Puts [target] beside the app the user was in ([foregroundPackage]). */
    suspend fun openSplit(target: Intent, foregroundPackage: String?, userId: Int?): Outcome {
        val traits = traits()
        val other = foregroundPackage?.let(::launchIntentOf)
        val route = routeFor(Kind.SPLIT, traits, other) ?: return fallBack(target)
        if (userId != null && route != LaunchRoute.SPLIT_SHELL) return fallBack(target)
        val ok = runSplit(route, traits, target, other, pair = false, userId = userId)
        return if (ok) Outcome.DONE else fallBack(target)
    }

    /** [first] on top (or left), [second] below (or right). */
    suspend fun openPair(first: Intent, second: Intent): Outcome {
        val traits = traits()
        val route = routeFor(Kind.SPLIT, traits, first) ?: return fallBack(first)
        val ok = runSplit(route, traits, second, first, pair = true, userId = null)
        return if (ok) Outcome.DONE else fallBack(first)
    }

    private suspend fun runSplit(
        route: LaunchRoute,
        traits: DeviceTraits,
        target: Intent,
        other: Intent?,
        pair: Boolean,
        userId: Int?,
    ): Boolean {
        val samePackage = other != null && packageOf(other) == packageOf(target)
        return when (route) {
            LaunchRoute.SPLIT_SERVICE -> {
                if (pair && other != null) {
                    if (!start(other, null)) return false
                    delay(LaunchRoutePlanner.pairGapMs(traits, viaHelper = false))
                }
                start(adjacent(target, samePackage), null)
            }
            LaunchRoute.SPLIT_TRAMPOLINE_ADJ_FIRST, LaunchRoute.SPLIT_TRAMPOLINE_PLAIN_FIRST -> runCatching {
                val order = if (route == LaunchRoute.SPLIT_TRAMPOLINE_ADJ_FIRST) {
                    LaunchTrampolineActivity.Order.ADJACENT_FIRST
                } else {
                    LaunchTrampolineActivity.Order.PLAIN_FIRST
                }
                LaunchTrampolineActivity.start(context, target, other, order)
            }.isSuccess
            LaunchRoute.SPLIT_HUAWEI -> {
                if (pair && other != null) {
                    if (!start(other, LaunchOptions.huaweiSplit(primary = true))) return false
                    delay(LaunchRoutePlanner.pairGapMs(traits, viaHelper = false))
                }
                start(target, LaunchOptions.huaweiSplit(primary = false))
            }
            LaunchRoute.SPLIT_HELPER -> {
                val helper = DeviceTraitsReader.helper() ?: return false
                if (pair && other != null) {
                    if (!start(other, null)) return false
                    val firstPackage = packageOf(other)
                    val gap = LaunchRoutePlanner.pairGapMs(traits, viaHelper = true)
                    if (firstPackage == null || !helper.awaitWindowOf(firstPackage, gap * 2)) delay(gap)
                }
                if (helper.enterSplit()) delay(LaunchRoutePlanner.HELPER_SWITCH_GAP_MS)
                if (traits.adjacentNeedsSource) {
                    runCatching {
                        LaunchTrampolineActivity.start(context, target, null, LaunchTrampolineActivity.Order.ADJACENT_FIRST, 0)
                    }.isSuccess
                } else {
                    start(adjacent(target, samePackage), null)
                }
            }
            LaunchRoute.SPLIT_SHELL -> shellSplit(target, other, userId)
            else -> false
        }
    }

    // ---- Calibration ----------------------------------------------------------

    /**
     * The stored route for [kind], or a freshly calibrated one. Null when no
     * candidate works here (also stored, so the phone is not re-probed on
     * every tap; a change in its traits or a new build clears it).
     */
    private suspend fun routeFor(kind: Kind, traits: DeviceTraits, other: Intent?): LaunchRoute? {
        store.get(kind, traits)?.let { return it.route }
        val winner = calibrate(kind, traits, other)
        store.put(kind, traits, winner)
        return winner
    }

    /**
     * Runs calibration now, forgetting the stored answer first; for the
     * settings row. [otherPackage] is the app to split beside.
     */
    suspend fun recalibrate(kind: Kind, otherPackage: String?): LaunchRoute? {
        val traits = traits()
        val winner = calibrate(kind, traits, otherPackage?.let(::launchIntentOf))
        store.put(kind, traits, winner)
        return winner
    }

    private suspend fun calibrate(kind: Kind, traits: DeviceTraits, other: Intent?): LaunchRoute? {
        val candidates = when (kind) {
            Kind.FLOATING -> LaunchRoutePlanner.floating(traits, emulatedAllowed = false)
            Kind.SPLIT -> LaunchRoutePlanner.split(traits)
        }
        for (route in candidates) {
            RouteProbe.arm()
            val launched = when (kind) {
                Kind.FLOATING -> {
                    val probe = probeIntent()
                    if (route == LaunchRoute.FLOAT_SHELL) {
                        shellFloat(probe, null, traits)
                    } else {
                        val options = LaunchOptions.floating(context, route, probe)
                        options != null && start(probe, options)
                    }
                }
                Kind.SPLIT -> runSplit(route, traits, probeIntent(), other, pair = false, userId = null)
            }
            if (launched && RouteProbe.await(kind)) return route
            RouteProbe.finish()
            // Let the window manager put things back before the next try;
            // Android 16 also locks launch-adjacent for a second after a split.
            delay(if (kind == Kind.SPLIT) LaunchRoutePlanner.ANDROID_16_GAP_MS else BETWEEN_PROBES_MS)
        }
        return null
    }

    private fun probeIntent(): Intent = Intent(context, RouteProbeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    // ---- Shell routes -----------------------------------------------------------

    /**
     * `am start --windowingMode` as the shell, then the task resized to the
     * floating bounds: HyperOS ignores bounds given at launch and opens a sliver.
     */
    private suspend fun shellFloat(target: Intent, userId: Int?, traits: DeviceTraits): Boolean {
        val component = target.component ?: return false
        val mode = LaunchOptions.floatMode(traits.family)
        val started = ShellAccess.exec(context, amStart(component, userId, "--windowingMode $mode"))?.ok == true
        if (!started) return false
        delay(SHELL_SETTLE_MS)
        val task = taskOf(component.packageName) ?: return true
        val b = LaunchOptions.floatingBounds(context)
        ShellAccess.exec(context, "am task resize $task ${b.left} ${b.top} ${b.right} ${b.bottom}")
        return true
    }

    /**
     * WM Shell's own split command (Android 14+, SplitScreenShellCommandHandler):
     * open [target] so it has a task, bring [other] back on top, then move
     * [target]'s task to the side stage. Refused while a split is already up,
     * where the command has been seen to crash SystemUI (TriSplit's notes).
     */
    private suspend fun shellSplit(target: Intent, other: Intent?, userId: Int?): Boolean {
        val component = target.component ?: return false
        if (splitActive()) return false
        if (ShellAccess.exec(context, amStart(component, userId, ""))?.ok != true) return false
        delay(SHELL_SETTLE_MS)
        val task = taskOf(component.packageName) ?: return false
        val otherComponent = other?.component
        if (otherComponent != null) {
            ShellAccess.exec(context, amStart(otherComponent, null, ""))
            delay(SHELL_SETTLE_MS)
        }
        val moved = ShellAccess.exec(
            context,
            "dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage $task $SIDE_BOTTOM_OR_RIGHT",
        )
        return moved?.ok == true
    }

    private suspend fun splitActive(): Boolean {
        DeviceTraitsReader.helper()?.let { return it.inSplit() }
        val dump = ShellAccess.exec(context, "dumpsys activity activities")?.output ?: return false
        return ShellParsing.splitVisible(dump)
    }

    private suspend fun taskOf(packageName: String): Int? {
        val dump = ShellAccess.exec(context, "dumpsys activity activities")?.output ?: return null
        return ShellParsing.topTaskOf(dump, packageName)
    }

    private fun amStart(component: ComponentName, userId: Int?, extra: String): String =
        "am start -n ${component.flattenToShortString()} -f 0x10000000" +
            (userId?.let { " --user $it" } ?: "") + (if (extra.isBlank()) "" else " $extra")

    // ---- Plain launches -----------------------------------------------------------

    private fun fallBack(target: Intent): Outcome = if (start(target, null)) Outcome.FELL_BACK else Outcome.FAILED

    private fun start(intent: Intent, options: Bundle?): Boolean = runCatching {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
    }.isSuccess

    private fun adjacent(target: Intent, samePackage: Boolean): Intent =
        Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT).apply {
            if (samePackage) addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

    private fun launchIntentOf(packageName: String): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()

    private fun packageOf(intent: Intent): String? = intent.component?.packageName ?: intent.`package`

    private companion object {
        const val BETWEEN_PROBES_MS = 400L
        const val SHELL_SETTLE_MS = 700L

        /** SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT. */
        const val SIDE_BOTTOM_OR_RIGHT = 1
    }
}

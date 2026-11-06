package com.wasimaster.wmkeyboard.ime.launch

import android.app.Activity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Where [RouteProbeActivity] lands, as the window manager placed it: the
 * window's size against the screen's. Plain numbers, so [verdict] is tested.
 */
data class ProbeReport(val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int)

/**
 * Calibration: launch our own [RouteProbeActivity] along a route exactly as an
 * app would be launched, and see where its window ends up. A route passes when
 * the window really floats or really sits in half the screen. The mode label
 * is never trusted: ColorOS's floating window reports `fullscreen`, and its
 * mode 6 says multi-window while drawing full screen (CPH2481, ColorOS 15).
 */
object RouteProbe {

    /** How long a probe gets to settle; split screen animates for ~400 ms. */
    const val SETTLE_MS = 900L

    /** Give up when the probe never even started (the route launched nothing). */
    private const val START_TIMEOUT_MS = 2_500L

    private const val FULL = 0.9f
    private const val HALF_MAX = 0.75f
    private const val HALF_MIN = 0.2f
    private const val FLOAT_MAX = 0.95f

    private val reports = MutableStateFlow<List<ProbeReport>>(emptyList())
    private val started = MutableStateFlow(false)
    private var activity: WeakReference<Activity>? = null

    /** Clears what the last probe left before a new one starts. */
    fun arm() {
        reports.value = emptyList()
        started.value = false
        finish()
    }

    internal fun attach(probe: Activity) {
        activity = WeakReference(probe)
        started.value = true
    }

    internal fun report(report: ProbeReport) {
        reports.value = reports.value + report
    }

    /** Closes the probe window, if one is up. */
    fun finish() {
        activity?.get()?.let { if (!it.isFinishing) it.finishAndRemoveTask() }
        activity = null
    }

    /**
     * Waits for the probe to start and settle, then says whether any of its
     * reports passed for [kind]. False when it never started.
     */
    suspend fun await(kind: LaunchRoute.Kind): Boolean {
        withTimeoutOrNull(START_TIMEOUT_MS) { started.first { it } } ?: return false
        delay(SETTLE_MS)
        val passed = reports.value.any { verdict(kind, it) }
        finish()
        return passed
    }

    /**
     * Floating: narrower and shorter than the screen, and not a split half.
     * Split: one side the screen's full length, the other between a fifth and
     * three quarters of it.
     */
    fun verdict(kind: LaunchRoute.Kind, r: ProbeReport): Boolean {
        if (r.screenWidth <= 0 || r.screenHeight <= 0 || r.width <= 0 || r.height <= 0) return false
        val w = r.width.toFloat() / r.screenWidth
        val h = r.height.toFloat() / r.screenHeight
        val half = (w >= FULL && h in HALF_MIN..HALF_MAX) || (h >= FULL && w in HALF_MIN..HALF_MAX)
        return when (kind) {
            LaunchRoute.Kind.SPLIT -> half
            LaunchRoute.Kind.FLOATING -> !half && w < FLOAT_MAX && h < FLOAT_MAX
        }
    }
}

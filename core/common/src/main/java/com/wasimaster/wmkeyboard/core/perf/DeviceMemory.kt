package com.wasimaster.wmkeyboard.core.perf

import android.app.ActivityManager
import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.isTelevision

/**
 * Whether this device is short of memory, decided once when the process
 * starts, and what that does to the in-memory caches.
 *
 * The caches size themselves as a share of the heap, which already scales
 * with the device. What it misses is that on a phone or a television with
 * one or two gigabytes the heap is not the limit: the whole device is, and
 * a keyboard holding a full share of pictures is what gets the app behind
 * it, or the keyboard itself, killed. Cheap Android TV boxes are the
 * extreme case: 1 to 2 GB shared with a video decoder that wants most of it.
 *
 * [init] runs from the application's `onCreate`, before any cache is first
 * touched. Until then (and in a unit test) the device counts as roomy.
 */
object DeviceMemory {

    /** True on a low-memory device: the caches below hold half as much. */
    @Volatile
    var tight: Boolean = false
        private set

    /** Total RAM in MB, 0 until [init]. */
    @Volatile
    var totalMb: Long = 0L
        private set

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }
        totalMb = info.totalMem / BYTES_PER_MB
        tight = isTight(
            lowRamDevice = am.isLowRamDevice,
            totalMb = totalMb,
            television = runCatching { context.isTelevision() }.getOrDefault(false),
        )
    }

    /**
     * A cache's byte budget: a [denominator]th of the heap, between [min]
     * and [max], and half of that on a [tight] device (never under [min]).
     */
    fun cacheBudget(denominator: Long, min: Long, max: Long): Int {
        val share = (Runtime.getRuntime().maxMemory() / denominator).coerceIn(min, max)
        return (if (tight) (share / 2).coerceAtLeast(min) else share).toInt()
    }

    /**
     * The rule [init] applies, pure for tests. A television counts as tight
     * up to more memory than a phone, since the video it plays takes a share
     * a phone's apps never ask for.
     */
    fun isTight(lowRamDevice: Boolean, totalMb: Long, television: Boolean): Boolean =
        lowRamDevice ||
            totalMb in 1 until TIGHT_PHONE_MB ||
            (television && totalMb in 1 until TIGHT_TV_MB)

    private const val BYTES_PER_MB = 1_048_576L
    private const val TIGHT_PHONE_MB = 3_000L
    private const val TIGHT_TV_MB = 4_500L
}

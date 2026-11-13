package com.wasimaster.wmkeyboard.core.power

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.core.settings.DeviceClass
import com.wasimaster.wmkeyboard.core.settings.DeviceLoad
import com.wasimaster.wmkeyboard.core.settings.LoadLevel
import com.wasimaster.wmkeyboard.core.settings.LoadLevelTracker
import com.wasimaster.wmkeyboard.core.settings.classifyDevice
import com.wasimaster.wmkeyboard.core.settings.frameLoad
import com.wasimaster.wmkeyboard.core.settings.isTelevision
import com.wasimaster.wmkeyboard.core.settings.workLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Watches how hard the phone is working and publishes a [DeviceLoad] the
 * keyboard combines with its settings, the way [PowerSaver] publishes the
 * battery.
 *
 * What it reads, each chosen because it costs next to nothing to watch:
 *
 *  * **The keyboard's own frames**, through the window's frame metrics: the
 *    share that took more than twice the display's frame time. Frames only
 *    happen while something moves or a key is pressed, so an idle keyboard
 *    reports nothing and costs nothing.
 *  * **The phone's temperature**, through the thermal status the system
 *    already pushes (Android 10 and later) and its headroom forecast (11 and
 *    later), read at most every [HEADROOM_EVERY_MS].
 *  * **Memory**, through the trim callbacks the service already receives and
 *    the system's low-memory flag, read when the keyboard comes up.
 *  * **The strip's own cost**: the measured time of a suggestion pass.
 *
 * Other apps' CPU use cannot be read since Android 8 (`/proc/stat` is closed
 * to apps), and does not need to be: a busy phone shows up in all four above.
 *
 * The readings go through a [LoadLevelTracker], so the level rises after a
 * couple of seconds of strain and falls only after a calm spell.
 */
class LoadGovernor(private val context: Context) {

    private val _state = MutableStateFlow(DeviceLoad())

    /** The phone's class and load right now. Starts at a mid-range phone under no load. */
    val state: StateFlow<DeviceLoad> = _state.asStateFlow()

    private val lock = Any()
    private val tracker = LoadLevelTracker()
    private var deviceClass = DeviceClass.MID
    private var television = false

    @Volatile
    private var enabled = true
    private var started = false

    // The latest reading of each signal, and when it was taken.
    private var frames = LoadLevel.NORMAL
    private var framesAt = 0L
    private var thermal = LoadLevel.NORMAL
    private var headroom = LoadLevel.NORMAL
    private var headroomAt = 0L
    private var memory = LoadLevel.NORMAL
    private var memoryAt = 0L
    private var work = LoadLevel.NORMAL
    private var workAt = 0L

    /** Starts the thermal listener and classifies the phone, once. */
    fun start() {
        if (started) return
        started = true
        // Sysfs reads and a binder call: not on the main thread at startup.
        Thread({ classify() }, "wmkb-device-class").apply { isDaemon = true }.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Thermal.listen(this)
    }

    fun stop() {
        if (!started) return
        started = false
        detach()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Thermal.stop(this)
    }

    /**
     * Turns the watching on or off with the user's switch. Off, nothing is
     * listened to per frame and the load reads as none, so nothing anywhere
     * adapts.
     */
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        if (!on) detach()
        publish()
    }

    /**
     * The keyboard came up: rereads what is only worth reading then (memory,
     * the thermal forecast) and lets an old calm spell count.
     */
    fun refresh() {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        val low = runCatching { am?.getMemoryInfo(info); info.lowMemory }.getOrDefault(false)
        synchronized(lock) {
            if (low) {
                memory = maxOf(memory, LoadLevel.ELEVATED)
                memoryAt = now
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) readHeadroom(now)
        evaluate(urgent = low)
    }

    /** The system asked the process to give memory back; see [ComponentCallbacks2]. */
    fun noteTrimMemory(level: Int) {
        if (!enabled) return
        @Suppress("DEPRECATION")
        val reading = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> LoadLevel.HEAVY
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_MODERATE -> LoadLevel.ELEVATED
            else -> return
        }
        synchronized(lock) {
            memory = maxOf(memory, reading)
            memoryAt = SystemClock.uptimeMillis()
        }
        evaluate(urgent = true)
    }

    /** The strip's average pass took [averageMs]. */
    fun noteSuggestionCost(averageMs: Long) {
        if (!enabled) return
        synchronized(lock) {
            work = workLoad(averageMs)
            workAt = SystemClock.uptimeMillis()
        }
        evaluate(urgent = false)
    }

    // ---- frames ----

    private var window: Window? = null
    private var listener: Any? = null
    private var frameIntervalNanos = DEFAULT_FRAME_NANOS
    private var batchFrames = 0
    private var batchJanky = 0
    private var skipFrames = 0

    /** Counts [window]'s frames while it is on screen. Call on every show. */
    fun attach(window: Window?) {
        if (window == null || !enabled) return
        if (this.window === window && listener != null) {
            skipFrames = SKIP_FIRST_FRAMES
            return
        }
        detach()
        val rate = runCatching {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay.refreshRate
        }.getOrDefault(0f)
        frameIntervalNanos = if (rate > 1f) (NANOS_PER_SECOND / rate).toLong() else DEFAULT_FRAME_NANOS
        skipFrames = SKIP_FIRST_FRAMES
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, dropped -> onFrame(metrics, dropped) }
        val added = runCatching { window.addOnFrameMetricsAvailableListener(l, frameHandler) }.isSuccess
        if (added) {
            this.window = window
            listener = l
        }
    }

    /** Stops counting frames, the window having gone. */
    fun detach() {
        val w = window ?: return
        val l = listener as? Window.OnFrameMetricsAvailableListener
        if (l != null) runCatching { w.removeOnFrameMetricsAvailableListener(l) }
        window = null
        listener = null
        batchFrames = 0
        batchJanky = 0
    }

    // On the frame-metrics thread.
    private fun onFrame(metrics: FrameMetrics, dropped: Int) {
        // The first frames after a show build the whole board and are slow
        // on every phone; they say nothing about load.
        if (skipFrames > 0) {
            skipFrames--
            return
        }
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        // [dropped] counts reports this listener missed, not late frames: it
        // says nothing about how the frames went, so it is left out.
        batchFrames++
        // Late enough to see: twice the frame time, and never under a fixed
        // floor. Twice the frame time alone is 22 ms at 90 Hz and 17 ms at
        // 120, which a mid-range phone's keyboard frames pass routinely (a
        // G99 typing: p50 19 ms, p90 57 ms) without any visible stutter.
        if (total > maxOf(frameIntervalNanos * 2, JANK_FLOOR_NANOS)) batchJanky++
        if (batchFrames < FRAME_BATCH) return
        val reading = frameLoad(batchFrames, batchJanky)
        batchFrames = 0
        batchJanky = 0
        synchronized(lock) {
            frames = reading
            framesAt = SystemClock.uptimeMillis()
        }
        evaluate(urgent = false)
    }

    // ---- thermal ----

    internal fun noteThermalStatus(status: Int) {
        val reading = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> LoadLevel.HEAVY
            status >= PowerManager.THERMAL_STATUS_MODERATE -> LoadLevel.ELEVATED
            else -> LoadLevel.NORMAL
        }
        synchronized(lock) { thermal = reading }
        if (enabled) evaluate(urgent = true)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readHeadroom(now: Long) {
        if (now - headroomAt < HEADROOM_EVERY_MS && headroomAt != 0L) return
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        // How close to throttling the phone will be in ten seconds: 1.0 is
        // the point where it slows itself down. NaN where it will not say.
        val forecast = runCatching { power.getThermalHeadroom(HEADROOM_FORECAST_S) }.getOrDefault(Float.NaN)
        synchronized(lock) {
            headroomAt = now
            headroom = when {
                forecast.isNaN() -> LoadLevel.NORMAL
                forecast >= 1f -> LoadLevel.HEAVY
                forecast >= HEADROOM_ELEVATED -> LoadLevel.ELEVATED
                else -> LoadLevel.NORMAL
            }
        }
    }

    // ---- decision ----

    private fun evaluate(urgent: Boolean) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            val reading = maxOf(
                thermal,
                headroom.takeIf { now - headroomAt < HEADROOM_VALID_MS } ?: LoadLevel.NORMAL,
                memory.takeIf { now - memoryAt < MEMORY_VALID_MS } ?: LoadLevel.NORMAL,
                maxOf(
                    frames.takeIf { now - framesAt < FRAMES_VALID_MS } ?: LoadLevel.NORMAL,
                    work.takeIf { now - workAt < WORK_VALID_MS } ?: LoadLevel.NORMAL,
                ),
            )
            if (tracker.update(reading, now, urgent) && Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "load ${tracker.level} on a $deviceClass phone (thermal $thermal, headroom $headroom, " +
                        "memory $memory, frames $frames, strip $work; next calm ${tracker.calmNeededMs} ms)",
                )
            }
        }
        publish()
    }

    private fun publish() {
        val next = if (enabled) {
            synchronized(lock) { DeviceLoad(tracker.level, deviceClass, television) }
        } else {
            DeviceLoad()
        }
        if (_state.value != next) _state.value = next
    }

    private fun classify() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(info) }
        val cores = Runtime.getRuntime().availableProcessors()
        val maxKhz = (0 until cores).maxOfOrNull { core ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toInt()
            }.getOrDefault(0)
        } ?: 0
        val tv = runCatching { context.isTelevision() }.getOrDefault(false)
        val performanceClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.VERSION.MEDIA_PERFORMANCE_CLASS
        } else {
            0
        }
        val found = classifyDevice(
            lowRamDevice = am?.isLowRamDevice == true,
            totalMemMb = info.totalMem / BYTES_PER_MB,
            cores = cores,
            maxCpuMhz = maxKhz / KHZ_PER_MHZ,
            mediaPerformanceClass = performanceClass,
            television = tv,
        )
        synchronized(lock) {
            deviceClass = found
            television = tv
        }
        publish()
    }

    /** The thermal listener, apart so its API 29 types stay out of older phones' class loading. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private object Thermal {
        private val listeners = HashMap<LoadGovernor, PowerManager.OnThermalStatusChangedListener>()

        fun listen(governor: LoadGovernor) {
            val power = governor.context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val listener = PowerManager.OnThermalStatusChangedListener { governor.noteThermalStatus(it) }
            // Told the current status at once, then every change.
            runCatching {
                power.addThermalStatusListener(ContextCompat.getMainExecutor(governor.context), listener)
                synchronized(listeners) { listeners[governor] = listener }
            }
        }

        fun stop(governor: LoadGovernor) {
            val listener = synchronized(listeners) { listeners.remove(governor) } ?: return
            val power = governor.context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            runCatching { power.removeThermalStatusListener(listener) }
        }
    }

    private companion object {
        /** `adb shell setprop log.tag.WMLoad DEBUG` logs each change of level. */
        const val TAG = "WMLoad"

        /** One thread for every window's frame reports; idle unless frames are drawn. */
        val frameHandler: Handler by lazy {
            Handler(HandlerThread("wmkb-frames").apply { isDaemon = true; start() }.looper)
        }

        const val NANOS_PER_SECOND = 1_000_000_000f
        const val DEFAULT_FRAME_NANOS = 16_666_667L

        /** The shortest frame counted as late: three frames at 60 Hz. */
        const val JANK_FLOOR_NANOS = 50_000_000L
        const val FRAME_BATCH = 60
        const val SKIP_FIRST_FRAMES = 10
        const val FRAMES_VALID_MS = 20_000L
        const val WORK_VALID_MS = 30_000L
        const val MEMORY_VALID_MS = 60_000L
        const val HEADROOM_EVERY_MS = 10_000L
        const val HEADROOM_VALID_MS = 30_000L
        const val HEADROOM_FORECAST_S = 10
        const val HEADROOM_ELEVATED = 0.85f
        const val BYTES_PER_MB = 1_048_576L
        const val KHZ_PER_MHZ = 1_000
    }
}

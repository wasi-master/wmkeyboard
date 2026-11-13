package com.wasimaster.wmkeyboard.core.settings

/**
 * How hard the phone is working right now, as the keyboard sees it.
 *
 * Three steps rather than a number, because every consumer only ever asks
 * "should I do less?" and a step is what hysteresis can hold steady. The
 * keyboard's own work and the rest of the phone are both in it: a board
 * that drops frames, a suggestion pass that takes too long, a phone that is
 * hot or short of memory all raise it.
 */
enum class LoadLevel {
    /** Nothing is struggling: everything runs as the user set it. */
    NORMAL,

    /**
     * Something is working hard. Only costs nobody sees are cut: moving
     * themes draw fewer frames, the strip and the punctuation key wait a
     * little longer for a burst of keys to end, glide previews are spaced out.
     */
    ELEVATED,

    /**
     * The phone is struggling. On top of [ELEVATED], the moving parts of a
     * theme hold still, the system blur behind a glass theme stops, the
     * floating word hints over the keys go and panel animations are cut,
     * until the load has stayed down for a while.
     */
    HEAVY,
}

/**
 * Where a phone sits in its market, worked out once from its memory and its
 * processor. A [LOW] phone runs the invisible budgets ([DeviceLoad.budgetLevel])
 * one step tighter all the time; nothing visible changes because of the class
 * alone.
 */
enum class DeviceClass { LOW, MID, HIGH }

/**
 * The keyboard's reading of the phone: the class it was born in and the load
 * it is under. Combined with the settings in the service, so the board, the
 * engine and the tools see one already-reduced settings object (see
 * [underLoad]) and the budgets below.
 */
data class DeviceLoad(
    val level: LoadLevel = LoadLevel.NORMAL,
    val deviceClass: DeviceClass = DeviceClass.MID,
) {
    /**
     * The level the invisible budgets run at: [level], but never below
     * [LoadLevel.ELEVATED] on a [DeviceClass.LOW] phone, which has no spare
     * core to give a moving background or a back-to-back preview.
     */
    val budgetLevel: LoadLevel
        get() = if (deviceClass == DeviceClass.LOW && level < LoadLevel.ELEVATED) LoadLevel.ELEVATED else level
}

/**
 * Frame rate caps for a theme's moving parts, in frames per second; 0 means
 * the effect holds still (which [underLoad] arranges through the settings).
 *
 * The caps hold even when nothing is under load. A board effect used to draw
 * at the display's own rate, which on a 120 Hz phone is 120 redraws of the
 * whole board every second for snow that moves a few pixels a frame; at 60
 * it looks the same and costs half. The slow colour flow of an animated
 * gradient cycles over many seconds, and 30 frames a second is already more
 * than the eye can tell apart from 120.
 */
data class MotionBudget(val boardEffectFps: Int, val themeFlowFps: Int) {
    /** The wait between two frames of a board effect, in ms; 0 for no cap. */
    val boardEffectGapMs: Long get() = gapFor(boardEffectFps)

    /** The wait between two frames of the theme's colour flow, in ms; 0 for no cap. */
    val themeFlowGapMs: Long get() = gapFor(themeFlowFps)

    private fun gapFor(fps: Int): Long =
        // A little under the frame time, so the frame that follows the wait
        // lands on the vsync that keeps the cap rather than the one after.
        if (fps <= 0) 0L else (MILLIS_PER_SECOND / fps - VSYNC_SLACK_MS).coerceAtLeast(0L)

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val VSYNC_SLACK_MS = 4L

        val NORMAL = MotionBudget(boardEffectFps = 60, themeFlowFps = 30)
        val ELEVATED = MotionBudget(boardEffectFps = 30, themeFlowFps = 15)

        /** The budget for [load]; under [LoadLevel.HEAVY] the theme is held still anyway. */
        fun of(load: DeviceLoad): MotionBudget =
            if (load.budgetLevel == LoadLevel.NORMAL) NORMAL else ELEVATED
    }
}

/**
 * The shortest the suggestion strip waits for a burst of keys to end before
 * it reads, by [DeviceLoad.budgetLevel]. The measured cost of a pass still
 * decides above this; under load, a burst collapses into one read sooner.
 */
fun DeviceLoad.suggestionDebounceFloorMs(): Long = when (budgetLevel) {
    LoadLevel.NORMAL -> 16L
    LoadLevel.ELEVATED -> 40L
    LoadLevel.HEAVY -> 80L
}

/**
 * How much longer than usual the background reads that trail the keys wait:
 * the smart punctuation key's model, the spacing between glide previews.
 */
fun DeviceLoad.backgroundWorkStretch(): Int = when (budgetLevel) {
    LoadLevel.NORMAL -> 1
    LoadLevel.ELEVATED -> 2
    LoadLevel.HEAVY -> 3
}

/**
 * The settings as they apply under [level]. A view, like [underPowerSaving]:
 * applied on the way out of the repository and never written back, so the
 * moment the load is gone every choice the user made is back untouched.
 *
 * Only [LoadLevel.HEAVY] changes anything here; the lighter level only
 * tightens budgets the settings object does not carry ([MotionBudget],
 * [suggestionDebounceFloorMs], [backgroundWorkStretch]). Off entirely with
 * [PowerSavingSettings.adaptToLoad].
 */
fun KeyboardSettings.underLoad(level: LoadLevel): KeyboardSettings {
    if (level != LoadLevel.HEAVY || !powerSaving.adaptToLoad) return this
    return copy(
        // Panel transitions read this one switch, as under power saving.
        reduceMotion = true,
        // The theme's colours, images and materials stay; its motion stops.
        accessibility = accessibility.copy(themeMotion = false),
        // Words floated over the keys redraw the whole board per keystroke.
        octopus = octopus.copy(enabled = false),
    )
}

/**
 * Classifies a phone from what it says about itself. Pure, so the thresholds
 * are tested without a phone.
 *
 * [maxCpuMhz] is the fastest core's top clock, 0 when the kernel would not
 * say; [mediaPerformanceClass] is Android's own grade (12 and later), 0 when
 * the phone claims none.
 */
fun classifyDevice(
    lowRamDevice: Boolean,
    totalMemMb: Long,
    cores: Int,
    maxCpuMhz: Int,
    mediaPerformanceClass: Int,
): DeviceClass = when {
    lowRamDevice || totalMemMb in 1 until LOW_MEM_MB || cores in 1..LOW_CORES ||
        maxCpuMhz in 1 until LOW_CPU_MHZ -> DeviceClass.LOW
    mediaPerformanceClass >= HIGH_PERFORMANCE_CLASS ||
        (totalMemMb >= HIGH_MEM_MB && maxCpuMhz >= HIGH_CPU_MHZ) -> DeviceClass.HIGH
    else -> DeviceClass.MID
}

private const val LOW_MEM_MB = 3_500L
private const val LOW_CORES = 4
private const val LOW_CPU_MHZ = 2_000
private const val HIGH_MEM_MB = 7_500L
private const val HIGH_CPU_MHZ = 2_800

/** Android 13's media performance class. */
private const val HIGH_PERFORMANCE_CLASS = 33

/**
 * Turns noisy readings into a [LoadLevel] that does not flap.
 *
 * Up quickly, down slowly. A reading above the current level has to hold for
 * [escalateAfterMs] before the level follows it (one janky burst while an app
 * opens behind the keyboard is not load); an urgent reading, a hot phone or
 * a memory warning, raises it at once. Coming down needs [calmMs] of
 * readings below the level, and goes one step at a time.
 *
 * The keyboard's own moving theme is often what made the frames late, so
 * stilling it brings the frames back, the level drops, the theme moves
 * again and the frames go late again. To stop that loop, a rise soon after a
 * fall ([REBOUND_MS]) doubles the calm needed next time, up to [maxCalmMs];
 * a long quiet spell resets it.
 *
 * Time is passed in, so the whole thing is tested without a clock.
 */
class LoadLevelTracker(
    private val escalateAfterMs: Long = 2_000L,
    private val calmMs: Long = 30_000L,
    private val maxCalmMs: Long = 600_000L,
) {
    var level: LoadLevel = LoadLevel.NORMAL
        private set

    private var pressureSince = NONE
    private var calmSince = NONE
    private var lastFall = NONE
    private var lastChange = NONE
    private var calmNeeded = calmMs

    /** The calm currently needed before a step down, in ms. */
    val calmNeededMs: Long get() = calmNeeded

    /**
     * Feeds one reading taken at [now]; returns true when [level] changed.
     * [urgent] skips the wait on the way up.
     */
    fun update(reading: LoadLevel, now: Long, urgent: Boolean = false): Boolean {
        if (lastChange != NONE && now - lastChange >= RESET_CALM_AFTER_MS) calmNeeded = calmMs
        return when {
            reading > level -> {
                calmSince = NONE
                if (pressureSince == NONE) pressureSince = now
                if (!urgent && now - pressureSince < escalateAfterMs) return false
                if (lastFall != NONE && now - lastFall < REBOUND_MS) {
                    calmNeeded = (calmNeeded * 2).coerceAtMost(maxCalmMs)
                }
                change(reading, now)
                true
            }
            reading < level -> {
                pressureSince = NONE
                if (calmSince == NONE) calmSince = now
                if (now - calmSince < calmNeeded) return false
                change(LoadLevel.entries[level.ordinal - 1], now)
                lastFall = now
                // The next step down waits a whole calm spell of its own.
                calmSince = now
                true
            }
            else -> {
                pressureSince = NONE
                calmSince = NONE
                false
            }
        }
    }

    private fun change(next: LoadLevel, now: Long) {
        level = next
        pressureSince = NONE
        lastChange = now
    }

    private companion object {
        const val NONE = Long.MIN_VALUE

        /** A rise this soon after a fall means the fall came too early. */
        const val REBOUND_MS = 120_000L

        /** A level that has held this long earns the short calm back. */
        const val RESET_CALM_AFTER_MS = 900_000L
    }
}

/**
 * The level a run of frames points at: [janky] of [frames] took over twice
 * the display's frame time.
 */
fun frameLoad(frames: Int, janky: Int): LoadLevel {
    if (frames < MIN_FRAMES) return LoadLevel.NORMAL
    val share = janky.toFloat() / frames
    return when {
        share >= HEAVY_JANK_SHARE -> LoadLevel.HEAVY
        share >= ELEVATED_JANK_SHARE -> LoadLevel.ELEVATED
        else -> LoadLevel.NORMAL
    }
}

private const val MIN_FRAMES = 20
private const val ELEVATED_JANK_SHARE = 0.12f
private const val HEAVY_JANK_SHARE = 0.30f

/**
 * The level a suggestion pass's average cost points at, in ms. A pass is
 * work the user waits on after every key, so a slow one is load whatever
 * made it slow: a busy phone, a heat-throttled core or simply a big list on
 * a small phone, and all three are helped by doing it less often.
 */
fun workLoad(passCostMs: Long): LoadLevel = when {
    passCostMs >= HEAVY_PASS_MS -> LoadLevel.HEAVY
    passCostMs >= ELEVATED_PASS_MS -> LoadLevel.ELEVATED
    else -> LoadLevel.NORMAL
}

private const val ELEVATED_PASS_MS = 90L
private const val HEAVY_PASS_MS = 250L

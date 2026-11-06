package com.wasimaster.wmkeyboard.app.resources

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * What the Resources screen (#1163) can honestly measure about this process.
 *
 * Android reports memory for the process as a whole and processor time per
 * thread. It reports neither per feature, and it reports battery per feature
 * to nobody below the system itself. So this measures those two things and
 * attributes them only where the attribution is real: a thread a model engine
 * named after itself, a model file whose size its owner knows. Everything else
 * is labelled for what it is.
 *
 * Nothing here runs unless the screen is open: there is no sampler, no
 * service and no listener. That is why the screen has no on/off switch.
 */
internal object ResourceProbe {

    /** One look at the process's memory, in kilobytes as the platform counts them. */
    data class Memory(
        /** Proportional set size: this process's share of the RAM it touches. The figure App info shows. */
        val totalPssKb: Long,
        val javaHeapKb: Long,
        val nativeHeapKb: Long,
        val codeKb: Long,
        val stackKb: Long,
        /** GPU buffers and textures, where the driver reports them. */
        val graphicsKb: Long,
        val privateOtherKb: Long,
        val systemKb: Long,
        /** What native code has actually allocated from its heap right now, in bytes. */
        val nativeAllocatedBytes: Long,
        /** Java objects alive right now, and the most the heap may grow to, in bytes. */
        val javaUsedBytes: Long,
        val javaMaxBytes: Long,
    )

    /** The `summary.*` keys of [Debug.MemoryInfo.getMemoryStat], all in kB. */
    fun readMemory(): Memory {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        fun stat(key: String) = info.getMemoryStat("summary.$key")?.toLongOrNull() ?: 0L
        val runtime = Runtime.getRuntime()
        return Memory(
            totalPssKb = stat("total-pss").takeIf { it > 0 } ?: info.totalPss.toLong(),
            javaHeapKb = stat("java-heap"),
            nativeHeapKb = stat("native-heap"),
            codeKb = stat("code"),
            stackKb = stat("stack"),
            graphicsKb = stat("graphics"),
            privateOtherKb = stat("private-other"),
            systemKb = stat("system"),
            nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaMaxBytes = runtime.maxMemory(),
        )
    }

    /** One thread's processor time so far, in clock ticks. */
    data class ThreadTime(val tid: Int, val name: String, val ticks: Long)

    /** Every live thread of this process, from `/proc/self/task`. Empty where the kernel hides it. */
    fun readThreads(): List<ThreadTime> {
        val tasks = File("/proc/self/task").listFiles() ?: return emptyList()
        return tasks.mapNotNull { task ->
            val tid = task.name.toIntOrNull() ?: return@mapNotNull null
            val line = runCatching { File(task, "stat").readText() }.getOrNull() ?: return@mapNotNull null
            parseTaskStat(line)?.let { (name, ticks) -> ThreadTime(tid, name, ticks) }
        }
    }

    /** The kernel's clock tick rate, which `/proc` times are counted in; 100 almost everywhere. */
    val ticksPerSecond: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull()?.takeIf { it > 0 } ?: 100L
    }

    /** Processor time the whole process has used since it started, ended threads included, in ms. */
    fun processCpuMs(): Long = Process.getElapsedCpuTime()

    /** How long the process has been running, in ms. */
    fun processUptimeMs(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    /** This app's services that are running right now, by class name. */
    @Suppress("DEPRECATION")
    fun runningServices(context: Context): List<String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        // Since Android 8 this lists only the caller's own services, which is
        // exactly the question; the filter keeps an older phone to the same.
        return runCatching { am.getRunningServices(Int.MAX_VALUE) }.getOrNull().orEmpty()
            .filter { it.service.packageName == context.packageName }
            .map { it.service.className }
            .distinct()
    }

    /** Another process of this app: the ones that run a heavy engine apart from the keyboard. */
    data class OtherProcess(val name: String, val pssKb: Long)

    /**
     * This app's processes other than this one, with their memory. Android
     * lists only the caller's own processes here, and may hand back a reading
     * a few minutes old: it rate-limits this call for apps.
     */
    fun otherProcesses(context: Context): List<OtherProcess> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val mine = Process.myPid()
        val others = runCatching { am.runningAppProcesses }.getOrNull().orEmpty()
            .filter { it.pid != mine && it.processName.startsWith(context.packageName) }
        if (others.isEmpty()) return emptyList()
        val memory = runCatching { am.getProcessMemoryInfo(others.map { it.pid }.toIntArray()) }.getOrNull()
        return others.mapIndexed { i, p ->
            OtherProcess(p.processName.substringAfter(':', p.processName), memory?.getOrNull(i)?.totalPss?.toLong() ?: 0L)
        }
    }

    /** One cache directory and what is in it, in bytes. */
    data class Cache(val dir: File, val bytes: Long)

    /** The directories the system itself may clear: they hold nothing that is not rebuilt. */
    fun readCaches(context: Context): List<Cache> =
        listOfNotNull(context.cacheDir, context.codeCacheDir, context.externalCacheDir)
            .map { Cache(it, directorySize(it)) }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().onFail { _, _ -> }.filter { it.isFile }.sumOf { it.length() }
}

/**
 * The thread name and its user plus system time from one `/proc/<pid>/task/<tid>/stat`
 * line, or null for a line that is not one.
 *
 * The name is the second field, in parentheses, and may itself hold spaces and
 * parentheses, so the fields after it are counted from the *last* `)`. Those
 * fields start at the third (the state); user time is the fourteenth and
 * system time the fifteenth.
 */
internal fun parseTaskStat(line: String): Pair<String, Long>? {
    val open = line.indexOf('(')
    val close = line.lastIndexOf(')')
    if (open < 0 || close < open) return null
    val name = line.substring(open + 1, close)
    val rest = line.substring(close + 1).trim().split(' ')
    val utime = rest.getOrNull(UTIME_AFTER_NAME)?.toLongOrNull() ?: return null
    val stime = rest.getOrNull(UTIME_AFTER_NAME + 1)?.toLongOrNull() ?: return null
    return name to (utime + stime)
}

/** Field 14 (utime) counted from field 3 (state), the first one after the name. */
private const val UTIME_AFTER_NAME = 11

/**
 * Who a thread works for, as far as its name says. The kernel keeps 15
 * characters of a name, so every pattern is a prefix that survives that.
 */
internal enum class ThreadGroup {
    /** The thread that runs the keyboard's and the settings screens' code. */
    MAIN,

    /** Drawing the keyboard and the screens: the render thread and its helpers. */
    DRAWING,

    /** The on-device models: speech, language models, translation, OCR. */
    MODELS,

    /** Coroutines and thread pools doing the app's own background work. */
    BACKGROUND,

    /** Network clients. */
    NETWORK,

    /** The runtime's own housekeeping: garbage collection, the JIT, binder calls from the system. */
    RUNTIME,

    OTHER,
}

/**
 * The group a thread named [name] belongs to. [isMain] is whether its id is the
 * process id: the main thread carries the process name, which says nothing.
 */
internal fun threadGroupOf(name: String, isMain: Boolean = false): ThreadGroup {
    if (isMain) return ThreadGroup.MAIN
    val n = name.lowercase()
    fun any(vararg prefixes: String) = prefixes.any { n.startsWith(it) }
    return when {
        any("renderthread", "hwuitask", "gpu completion", "hwc release", "surfacesync", "choreographer") ->
            ThreadGroup.DRAWING
        any(
            "whisper", "llama", "ggml", "sherpa", "onnx", "ort", "tflite", "litert", "mnn", "ncnn",
            "executorch", "mms-tts", "wm-xlit", "asr", "vosk", "bergamot", "nllb", "ct2", "ocr", "tesseract", "paddle",
            "mlkit", "ml kit", "omnilingual", "denoise", "smartturn", "embed", "diffusion", "upscale",
            "xlit", "harper", "glide", "predict", "model",
        ) -> ThreadGroup.MODELS
        any("okhttp", "okio", "ktor", "cronet", "dns", "network") -> ThreadGroup.NETWORK
        any(
            "defaultdispatch", "kotlinx.corouti", "dispatchers", "pool-", "asynctask", "arch_disk_io",
            "background", "worker", "datastore", "room", "executor", "coroutine",
        ) -> ThreadGroup.BACKGROUND
        any(
            "binder:", "hwbinder", "heaptaskdaemon", "finalizer", "referencequeue", "jit thread",
            "signal catcher", "profile saver", "perfetto", "gc", "adbd", "jdwp", "studio:",
        ) -> ThreadGroup.RUNTIME
        else -> ThreadGroup.OTHER
    }
}

/** A thread group's processor time: so far, and as a share of one core over the last look. */
internal data class GroupCpu(
    val group: ThreadGroup,
    val totalMs: Long,
    /** Percent of one core over the interval between the last two looks; null on the first look. */
    val livePercent: Float?,
    /** The busiest thread names in the group, busiest first. */
    val topThreads: List<String>,
)

/**
 * Folds two looks at the threads into time per group. [previous] is the look
 * [intervalMs] before [now], or null on the first one; a thread that was not
 * there before counts from zero, and a thread that has ended since simply is
 * not in [now] (its time is in the process total, not in any group).
 */
internal fun groupCpu(
    now: List<ResourceProbe.ThreadTime>,
    previous: List<ResourceProbe.ThreadTime>?,
    intervalMs: Long,
    ticksPerSecond: Long,
    mainTid: Int,
): List<GroupCpu> {
    val before = previous?.associateBy { it.tid }
    val msPerTick = 1000.0 / ticksPerSecond
    return now.groupBy { threadGroupOf(it.name, isMain = it.tid == mainTid) }
        .map { (group, threads) ->
            val totalTicks = threads.sumOf { it.ticks }
            val live = if (before == null || intervalMs <= 0) {
                null
            } else {
                val delta = threads.sumOf { t -> (t.ticks - (before[t.tid]?.ticks ?: 0L)).coerceAtLeast(0L) }
                (delta * msPerTick / intervalMs * 100.0).toFloat()
            }
            GroupCpu(
                group = group,
                totalMs = (totalTicks * msPerTick).toLong(),
                livePercent = live,
                topThreads = threads.sortedByDescending { it.ticks }
                    .map { it.name }
                    .distinct()
                    .take(TOP_THREAD_NAMES),
            )
        }
        .sortedByDescending { it.totalMs }
}

private const val TOP_THREAD_NAMES = 3

/**
 * A running service's class name made readable when there is no name of its
 * own for it: the package and the `Service` suffix dropped and the words
 * split, so `com.example.ScreenContextService` reads "Screen context".
 */
internal fun humanServiceName(className: String): String {
    val simple = className.substringAfterLast('.').removeSuffix("Service").ifEmpty { className }
    val words = simple.split(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"))
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return simple
    return words.mapIndexed { i, w ->
        when {
            i == 0 -> w
            w.length > 1 && w.all { it.isUpperCase() } -> w
            else -> w.lowercase()
        }
    }.joinToString(" ")
}

/**
 * How `dumpsys batterystats` names an app's uid: `u0a123` for app id 10123 of
 * user 0. Null for a system uid, which has no such name.
 */
internal fun batteryStatsUidLabel(uid: Int): String? {
    val user = uid / PER_USER_RANGE
    val app = uid % PER_USER_RANGE
    if (app < FIRST_APPLICATION_UID) return null
    return "u${user}a${app - FIRST_APPLICATION_UID}"
}

private const val PER_USER_RANGE = 100_000
private const val FIRST_APPLICATION_UID = 10_000

/**
 * Android's own estimate of what one app drew from the battery, read from
 * `dumpsys batterystats` (#1163). Every number is the system's model of power
 * use, in mAh, not a measurement, and the screen says so.
 */
internal data class BatteryEstimate(
    /** What the app drew in the period. */
    val appMah: Double,
    /** What the system's model says the whole phone drew in the same period; null where not printed. */
    val computedDrainMah: Double?,
    /** The battery's capacity as the system knows it; null where not printed. */
    val capacityMah: Double?,
    /** The parts of [appMah] the system names (cpu, wifi, screen, …), largest first; zeros left out. */
    val parts: List<Pair<String, Double>>,
) {
    /** [appMah] as a share of [computedDrainMah], 0 to 1; null without a drain figure. */
    val share: Double? get() = computedDrainMah?.takeIf { it > 0 }?.let { (appMah / it).coerceIn(0.0, 1.0) }
}

/**
 * The [BatteryEstimate] for the uid named [uidLabel] in the "Estimated power
 * use" lines of `dumpsys batterystats`, or null when that uid has no line (the
 * period is too short, or the statistics were just reset).
 *
 * Two formats are read. Android 12 and later print
 * `UID u0a123: 12.3 fg: 10.0 bg: 2.0 ( cpu=8.00 (12m 3s) wifi=1.20 )`; older
 * versions print `Uid u0a123: 12.3 ( cpu=8.00 wifi=1.20 ) Including smearing: …`.
 * Only the part before "Including smearing" is read, so the parts are the
 * app's own and not the screen and system shares spread over it.
 */
internal fun parseBatteryStats(output: String, uidLabel: String): BatteryEstimate? {
    val lines = output.lines()
    val uidLine = Regex("""^\s*uid\s+${Regex.escape(uidLabel)}:\s*([0-9.]+)(.*)$""", RegexOption.IGNORE_CASE)
    val match = lines.firstNotNullOfOrNull { uidLine.find(it) } ?: return null
    val appMah = match.groupValues[1].toDoubleOrNull() ?: return null
    val rest = match.groupValues[2].substringBefore("Including smearing")
    val partsText = rest.substringAfter('(', missingDelimiterValue = "")
    val parts = Regex("""([a-z_]+)=([0-9.]+)""", RegexOption.IGNORE_CASE).findAll(partsText)
        .mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 }?.let { m.groupValues[1] to it } }
        .groupBy({ it.first }, { it.second })
        .map { (name, values) -> name to values.sum() }
        .sortedByDescending { it.second }
    fun number(label: String) = lines.firstNotNullOfOrNull {
        Regex("""$label:\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toDoubleOrNull()
    }
    return BatteryEstimate(
        appMah = appMah,
        computedDrainMah = number("Computed drain"),
        capacityMah = number("Capacity"),
        parts = parts,
    )
}

/**
 * The shell command that reads [BatteryEstimate] for [packageName] since the
 * battery was last full, cut down on the phone to the few lines it needs:
 * the whole dump can run past the 64 KiB a shell call hands back.
 */
internal fun batteryStatsCommand(packageName: String, uidLabel: String): String =
    "dumpsys batterystats --charged $packageName | grep -iE 'capacity:|uid $uidLabel:'"

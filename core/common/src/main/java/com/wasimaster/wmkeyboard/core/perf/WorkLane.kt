package com.wasimaster.wmkeyboard.core.perf

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A few threads of the keyboard's own for one kind of work, at a priority
 * chosen for that work, and (Android 12 and later) tied to the system's
 * performance hints.
 *
 * Why not `Dispatchers.Default`: its threads are shared by everything, so
 * the system cannot be told anything about them. A suggestion pass and a
 * dictionary rebuild look the same to the scheduler, and on a busy phone
 * the pass the user is waiting on lands on a slow core at a low clock
 * behind work nobody is waiting for. Here:
 *
 *  * **Priority.** The typing and glide lanes run a notch above ordinary
 *    threads (still below the UI and render threads, so they never cost a
 *    frame); the background lane runs at background priority, which the
 *    scheduler keeps on the small cores and lets anything else go first.
 *  * **ADPF hints** ([PerformanceHintManager]). A lane with a target tells
 *    the system how long one cycle of its work should take, and reports how
 *    long each one did. When passes run over (a hot phone, another app
 *    hogging the big cores) the system moves the lane's threads to a faster
 *    core or raises the clock for them, and only for them; when they come in
 *    under, it lets them go back. [loadUp] warns it a burst is starting
 *    before the first pass has even run (Android 13 and later).
 *
 * Threads start on first use and live for the process, idle when there is
 * nothing to do. A single-thread lane also serialises its work, which the
 * typing lane relies on (see the service's notes on suggestion passes).
 */
class WorkLane(
    context: Context,
    private val name: String,
    threads: Int,
    /** An `android.os.Process.THREAD_PRIORITY_*` value. */
    private val priority: Int,
    /** How long one cycle of this lane's work should take, in ms; 0 for no hints. */
    targetMs: Long,
) {
    private val appContext = context.applicationContext
    private val targetNanos = targetMs * NANOS_PER_MS
    private val tids = IntArray(threads)
    private val registered = AtomicInteger(0)
    private val counter = AtomicInteger(0)

    private val executor = Executors.newFixedThreadPool(threads) { runnable ->
        Thread(
            {
                runCatching { Process.setThreadPriority(priority) }
                register(Process.myTid())
                runnable.run()
            },
            "$name-${counter.incrementAndGet()}",
        ).apply { isDaemon = true }
    }

    /** Where this lane's work runs. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /**
     * Whether the hints are sent. Off under power saving: asking for a faster
     * core is the opposite of saving power, and a slower strip is the trade
     * the user chose there.
     */
    @Volatile
    var hinting: Boolean = true

    private val lock = Any()
    private var session: Any? = null
    private var sessionThreads = 0
    private var sessionFailed = false

    private fun register(tid: Int) {
        val index = registered.getAndIncrement()
        if (index < tids.size) tids[index] = tid
    }

    /** One cycle of work took [nanos]: the system tunes the lane's cores toward the target. */
    fun report(nanos: Long) {
        if (!hinting || targetNanos <= 0L || nanos <= 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        Hints.report(this, nanos)
    }

    /** Runs [block] and reports how long it took as one cycle. */
    inline fun <T> measured(block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            report(System.nanoTime() - start)
        }
    }

    /**
     * A burst of work is about to start (the first key after a pause): the
     * system may raise the clock before the first cycle reports late.
     */
    fun loadUp() {
        if (!hinting || targetNanos <= 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        Hints.loadUp(this)
    }

    /** Stops the threads; the lane cannot be used afterwards. */
    fun shutdown() {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Hints.close(this)
            session = null
        }
        executor.shutdown()
    }

    /** The API 31+ half, apart so its types stay off older phones' class loading. */
    @RequiresApi(Build.VERSION_CODES.S)
    private object Hints {
        fun sessionOf(lane: WorkLane): PerformanceHintManager.Session? = synchronized(lane.lock) {
            val count = lane.registered.get().coerceAtMost(lane.tids.size)
            if (count == 0 || lane.sessionFailed) return null
            val current = lane.session as? PerformanceHintManager.Session
            if (current != null) {
                // A thread that started after the session was made joins it
                // where the platform lets a session change its threads.
                if (count > lane.sessionThreads && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching { current.setThreads(lane.tids.copyOf(count)) }
                    lane.sessionThreads = count
                }
                return current
            }
            val manager = lane.appContext.getSystemService(PerformanceHintManager::class.java)
            val made = runCatching { manager?.createHintSession(lane.tids.copyOf(count), lane.targetNanos) }.getOrNull()
            if (made == null) {
                // Not supported on this phone: stop asking.
                lane.sessionFailed = true
                return null
            }
            lane.session = made
            lane.sessionThreads = count
            made
        }

        fun report(lane: WorkLane, nanos: Long) {
            val session = sessionOf(lane) ?: return
            runCatching { session.reportActualWorkDuration(nanos) }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun loadUp(lane: WorkLane) {
            val session = sessionOf(lane) ?: return
            runCatching { session.sendHint(PerformanceHintManager.Session.CPU_LOAD_UP) }
        }

        fun close(lane: WorkLane) {
            runCatching { (lane.session as? PerformanceHintManager.Session)?.close() }
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

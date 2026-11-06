package com.wasimaster.wmkeyboard.core.plugins

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.plugins.R

/**
 * Why a plugin was stopped part-way through a run.
 *
 * [messageRes] is what the panel says about it. A resource id rather than the
 * words: this module runs with no Context, so the host resolves the text where
 * it draws it.
 */
enum class PluginAbortReason(@StringRes val messageRes: Int) {
    /** Burned through its instruction allowance — almost always an accidental infinite loop. */
    INSTRUCTIONS(R.string.core_plugins_stopped_instructions),

    /** Ran past its wall-clock deadline. */
    DEADLINE(R.string.core_plugins_stopped_deadline),

    /** The host pulled the plug: panel closed, plugin switched, keyboard hidden. */
    CANCELLED(R.string.core_plugins_stopped_cancelled),
}

/**
 * Thrown from the instruction hook to stop a runaway script.
 *
 * Extends [Error] rather than [Exception] for one specific reason: luaj's
 * `pcall` and `xpcall` catch `LuaError` and `Exception`, and the interpreter
 * loop catches the same two. A `java.lang.Error` passes straight through both,
 * so a script cannot swallow its own termination with
 * `while true do pcall(function() while true do end end) end`. Nothing in Lua
 * can construct, throw or catch this type.
 *
 * The stack trace is deliberately not filled in: this is control flow on a hot
 * path, thrown from a hook that runs before every bytecode, and the trace would
 * be luaj interpreter frames that tell nobody anything.
 *
 * The throwable message is the reason's own name, which is for a crash log. What
 * the user reads is [PluginAbortReason.messageRes], resolved by the host.
 */
class PluginAbort(val reason: PluginAbortReason) : Error(reason.name) {
    override fun fillInStackTrace(): Throwable = this
}

/** How much work one call into a plugin may do. */
data class PluginLimit(val instructions: Long, val wallMillis: Long) {
    companion object {
        /** Loading the chunk and running it top to bottom. The most expensive entry. */
        val LOAD = PluginLimit(instructions = 30_000_000, wallMillis = 3_000)

        /** Handling one tap, toggle or keystroke. */
        val EVENT = PluginLimit(instructions = 20_000_000, wallMillis = 2_000)

        /**
         * One tap on a selection action: a fresh sandbox, the chunk, and the
         * `transform` call, all on one allowance. As much as [LOAD], since a
         * load is part of every action call.
         */
        val ACTION = PluginLimit(instructions = 30_000_000, wallMillis = 3_000)

        /** Building the widget tree. Runs after every event, so it is kept tight. */
        val RENDER = PluginLimit(instructions = 4_000_000, wallMillis = 500)
    }
}

/**
 * The instruction and wall-clock allowance for the plugin thread.
 *
 * [onInstruction] is called by [SandboxDebugLib] before every single Lua
 * bytecode, so it is written to be cheap: one decrement on a thread-confined
 * field, and a clock read only once every [CLOCK_CHECK_INTERVAL] instructions.
 *
 * Time spent inside a host call does not count against the deadline.
 * `wm.storage.set` writing a file is the host being slow, not the plugin
 * looping, and a plugin killed for the host's latency would be a bug the plugin
 * author cannot fix. [enterHostCall]/[exitHostCall] push the deadline out by
 * however long the call took.
 *
 * Threading: [onInstruction], [begin] and the host-call pair are only ever
 * called on the plugin thread. [cancel] and [isStuck] are called from the main
 * thread and the watchdog, which is why the fields they touch are volatile.
 * [usage] is read from the plugin editor's main thread the same way.
 */
class PluginBudget(private val nanoTime: () -> Long = { System.nanoTime() }) {

    private var remaining = 0L
    private var hostCallStart = 0L

    @Volatile
    private var deadlineNanos = Long.MAX_VALUE

    @Volatile
    private var cancelled = false

    @Volatile
    private var inHostCall = false

    // What usage() reports. Written only on the plugin thread and only on paths
    // that already ran: begin, end, and the branch of onInstruction that reads
    // the clock. Volatile so the editor can read them from its own thread.
    // [remaining] itself is never read across threads, because a plain long read
    // from another thread can tear.
    @Volatile
    private var sampledUsed = 0L

    @Volatile
    private var limitInstructions = 0L

    @Volatile
    private var limitWallMillis = 0L

    @Volatile
    private var startNanos = 0L

    @Volatile
    private var endNanos = 0L

    @Volatile
    private var running = false

    /**
     * How much of one call's allowance has gone, for the plugin editor's meter.
     *
     * [instructions] is exact once the call has ended, and while it runs it lags
     * by at most [CLOCK_CHECK_INTERVAL] + 1. [elapsedNanos] runs from [begin] and
     * stops at [end]. The fields are read one at a time, so a snapshot taken
     * mid-call can pair a count with a slightly later time; that is fine for a
     * meter and is not a promise for anything else.
     */
    data class Usage(
        val instructions: Long,
        val instructionLimit: Long,
        val elapsedNanos: Long,
        val wallLimitMillis: Long,
        val running: Boolean,
    ) {
        companion object {
            val NONE = Usage(0L, 0L, 0L, 0L, running = false)
        }
    }

    /** Starts a fresh allowance for one call into the plugin. */
    fun begin(limit: PluginLimit) {
        remaining = limit.instructions
        val start = nanoTime()
        deadlineNanos = start + limit.wallMillis * NANOS_PER_MILLI
        inHostCall = false
        startNanos = start
        limitInstructions = limit.instructions
        limitWallMillis = limit.wallMillis
        sampledUsed = 0L
        running = true
    }

    /**
     * Ends the current allowance.
     *
     * Must run when a call returns, however it returns. Without it the deadline
     * stays where the finished call left it, and the watchdog would read a
     * perfectly idle thread as stuck the moment that time passed.
     */
    fun end() {
        deadlineNanos = Long.MAX_VALUE
        inHostCall = false
        if (running) {
            sampledUsed = limitInstructions - remaining.coerceAtLeast(0L)
            endNanos = nanoTime()
            running = false
        }
    }

    /**
     * Called before every Lua instruction. Throws [PluginAbort] when the script
     * has run out of allowance, out of time, or has been cancelled.
     */
    fun onInstruction() {
        if (--remaining <= 0L) throw PluginAbort(PluginAbortReason.INSTRUCTIONS)
        if ((remaining and CLOCK_CHECK_INTERVAL) == 0L) {
            sampledUsed = limitInstructions - remaining
            if (cancelled) throw PluginAbort(PluginAbortReason.CANCELLED)
            if (nanoTime() > deadlineNanos) throw PluginAbort(PluginAbortReason.DEADLINE)
        }
    }

    /** Asks the script to stop at its next instruction. Safe from any thread. */
    fun cancel() {
        cancelled = true
    }

    fun isCancelled(): Boolean = cancelled

    /** The current or most recent call's consumption. Safe from any thread. */
    fun usage(): Usage {
        val limit = limitInstructions
        if (limit == 0L) return Usage.NONE
        val isRunning = running
        val stop = if (isRunning) nanoTime() else endNanos
        return Usage(
            instructions = sampledUsed,
            instructionLimit = limit,
            elapsedNanos = (stop - startNanos).coerceAtLeast(0L),
            wallLimitMillis = limitWallMillis,
            running = isRunning,
        )
    }

    /** Marks the start of a blocking host call, whose time is not the plugin's fault. */
    fun enterHostCall() {
        hostCallStart = nanoTime()
        inHostCall = true
    }

    /** Marks the end of a host call, extending the deadline by however long it took. */
    fun exitHostCall() {
        if (!inHostCall) return
        inHostCall = false
        val spent = nanoTime() - hostCallStart
        if (spent > 0 && deadlineNanos != Long.MAX_VALUE) deadlineNanos += spent
    }

    /**
     * Whether the watchdog should give up on this thread.
     *
     * True only once the deadline is [graceMillis] past due *and* the thread is
     * not parked in a host call. A hook that is still firing will have thrown by
     * now, so reaching here means the thread is somewhere the hook cannot
     * interrupt — inside luaj's pattern matcher, most likely — and no amount of
     * further waiting will help.
     */
    fun isStuck(graceMillis: Long): Boolean {
        if (inHostCall) return false
        val deadline = deadlineNanos
        if (deadline == Long.MAX_VALUE) return false
        return nanoTime() > deadline + graceMillis * NANOS_PER_MILLI
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Check the clock when the low bits of the counter are clear, i.e. every
         * 1024 instructions. A mask rather than a modulo because this runs
         * millions of times a second.
         */
        const val CLOCK_CHECK_INTERVAL = 1023L
    }
}

package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What one selection-action call came to. */
sealed interface PluginActionResult {

    /** [text] goes in place of the selection, or after it when [replaces] is false. */
    data class Output(val text: String, val replaces: Boolean) : PluginActionResult

    /** `transform` returned None: leave the selection as it is. */
    data object Unchanged : PluginActionResult

    /** The call failed and the plugin is fine. */
    data class Failed(val message: PluginText) : PluginActionResult

    /** The call was stopped for running away. [disabled] means it used its last strike. */
    data class Stopped(val message: PluginText, val disabled: Boolean) : PluginActionResult
}

/**
 * Runs a plugin's selection actions: the selected text in, a string out.
 *
 * Separate from [PluginRuntime] on purpose. That class holds one panel session
 * and closes it whenever something else opens, so running an action through it
 * would shut the panel the user has open. An action needs none of a session's
 * state anyway.
 *
 * ## Every tap starts from nothing
 *
 * Each call creates a fresh interpreter in `action` mode, runs the script,
 * calls `transform` once and frees the whole thing. A global the script sets
 * while holding the user's selection is gone before the next tap. Only what it
 * writes to its own `wm.storage` stays, and that has no way off the device.
 *
 * ## Stopping a call
 *
 * The interpreter stops the script itself at its step or time limit, or at a
 * [cancel]. A call that ran away earns a strike, as in [PluginRuntime], and two
 * switch the plugin off. The watchdog is the fallback for a call still running
 * past its deadline: it cancels, then abandons.
 *
 * ## Threading
 *
 * [run] and [cancel] may be called from any thread. One call runs at a time: a
 * new [run] cancels the one before it, whose result is then never delivered.
 * Results reach `onDone` through [post].
 */
class PluginActionRunner(
    private val store: PluginStore,
    private val post: (() -> Unit) -> Unit = { it() },
    private val limit: PluginLimit = PluginLimit.ACTION,
    private val watchdogPeriodMillis: Long = 250,
    private val watchdogGraceMillis: Long = 500,
    /** Runs after an action changed its plugin's reminders, so the host can move its wake-up. */
    private val onRemindersChanged: () -> Unit = {},
) {

    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "wm-plugin-action-watchdog").apply { isDaemon = true }
        }

    private var current: Call? = null

    /** Runs [actionId] of [pluginId] on [text]. Cancels whatever was running. */
    @Synchronized
    fun run(pluginId: String, actionId: String, text: String, onDone: (PluginActionResult) -> Unit) {
        cancel()
        val failure = refusal(pluginId, actionId)
        if (failure != null) {
            post { onDone(PluginActionResult.Failed(failure)) }
            return
        }
        val plugin = store.plugin(pluginId) ?: return
        val action = plugin.actions.first { it.id == actionId }
        val script = store.script(pluginId) ?: return
        val input = if (action.input == PluginActionSpec.INPUT_NONE) "" else text
        val call = Call(plugin, action, onDone)
        current = call
        val thread = Thread(null, { execute(call, script, input) }, "wm-plugin-action-${plugin.id}", PluginRuntime.PLUGIN_STACK_BYTES).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
        call.thread = thread
        call.deadlineNanos = System.nanoTime() + limit.wallMillis * NANOS_PER_MILLI
        call.watch = watchdog.scheduleWithFixedDelay(
            { patrol(call) },
            watchdogPeriodMillis,
            watchdogPeriodMillis,
            TimeUnit.MILLISECONDS,
        )
        thread.start()
    }

    /** Stops the running call, if any. Its result is dropped. */
    @Synchronized
    fun cancel() {
        val call = current ?: return
        current = null
        call.cancelled = true
        call.python?.cancel()
        call.watch?.cancel(false)
    }

    /** Cancels and stops the watchdog. For the host's own teardown. */
    @Synchronized
    fun shutdown() {
        cancel()
        watchdog.shutdownNow()
    }

    /** Why [actionId] of [pluginId] cannot run right now, or null when it can. */
    private fun refusal(pluginId: String, actionId: String): PluginText? {
        val plugin = store.plugin(pluginId) ?: return PluginText.of(R.string.core_plugins_error_not_installed)
        return when {
            !store.subsystemEnabled() || !plugin.enabled -> PluginText.of(R.string.core_plugins_error_turned_off)
            !plugin.grants(PluginPermission.SelectedText) || plugin.actions.none { it.id == actionId } ->
                PluginText.of(R.string.core_plugins_error_action_missing)
            store.script(pluginId) == null -> PluginText.of(R.string.core_plugins_error_script_missing)
            !PythonNative.available -> PluginText.of(R.string.core_plugins_error_not_ready)
            else -> null
        }
    }

    // ---- on the call's own thread -------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun execute(call: Call, script: String, input: String) {
        val log = PluginLog(store.logFile(call.plugin.id))
        val result = try {
            runPython(call, log, script, input)
        } catch (failure: Throwable) {
            val name = failure.javaClass.simpleName
            log.add("error: " + (failure.message?.take(MAX_ERROR)?.let { "$name: $it" } ?: name))
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_stopped_unexpected))
        } finally {
            call.deadlineNanos = Long.MAX_VALUE
            call.watch?.cancel(false)
            log.flush()
        }
        if (result != null) deliver(call, result)
    }

    private fun runPython(call: Call, log: PluginLog, script: String, input: String): PluginActionResult? {
        val plugin = call.plugin
        val python = PythonSession.create(
            PythonSession.Config(
                plugin = plugin,
                mode = PythonSession.Config.ACTION,
                storagePath = store.storageFile(plugin.id)?.path,
                remindersPath = store.remindersFile(plugin.id)?.path,
                action = limit,
            ),
        ) ?: return PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_not_ready))
        call.python = python
        // A cancel that came between the check in run() and the session existing.
        if (call.cancelled) python.cancel()
        try {
            val loaded = python.load(script)
            log(loaded, log)
            loaded.error?.let { return failed(call, log, it) }
            val result = python.transform(input, call.action.id)
            log(result, log)
            if (result.remindersChanged || loaded.remindersChanged) onRemindersChanged()
            result.error?.let { return failed(call, log, it) }
            return result.value?.let { PluginActionResult.Output(it.take(MAX_OUTPUT), call.action.replaces) }
                ?: PluginActionResult.Unchanged
        } finally {
            call.python = null
            python.close()
        }
    }

    private fun log(result: PythonSession.Result, log: PluginLog) {
        for (line in result.log) log.add(line)
    }

    private fun failed(call: Call, log: PluginLog, error: PythonSession.Error): PluginActionResult? {
        if (error.kind == PythonSession.Error.NO_TRANSFORM) {
            return PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_no_transform))
        }
        if (error.kind == PythonSession.Error.ABORT) {
            val reason = PluginAbortReason.ofWire(error.abort) ?: PluginAbortReason.INSTRUCTIONS
            log.add("stopped: ${error.abort ?: "steps"}")
            // Cancelled by the host, or abandoned, which the watchdog already counted.
            if (reason == PluginAbortReason.CANCELLED || call.cancelled || call.abandoned) return null
            if (reason == PluginAbortReason.RECURSION || reason == PluginAbortReason.MEMORY) {
                return PluginActionResult.Failed(PluginText.of(reason.messageRes))
            }
            val disabled = store.recordAbandon(call.plugin.id)?.enabled == false
            return PluginActionResult.Stopped(PluginText.of(reason.messageRes), disabled)
        }
        val text = error.message.take(MAX_ERROR)
        log.add("error: ${text.ifEmpty { "the script failed" }}")
        return PluginActionResult.Failed(
            text.takeIf { it.isNotEmpty() }?.let { PluginText.Script(PluginRuntime.locatedText(it, error.line)) }
                ?: PluginText.of(R.string.core_plugins_error_script_failed),
        )
    }

    // ---- the watchdog -------------------------------------------------------

    /** Cancels a call a grace period past its deadline, and abandons it a grace period after that. */
    private fun patrol(call: Call) {
        if (call.cancelled || call.abandoned || call.delivered.get()) return
        val deadline = call.deadlineNanos
        if (deadline == Long.MAX_VALUE) return
        val over = System.nanoTime() - deadline
        val grace = watchdogGraceMillis * NANOS_PER_MILLI
        if (over > grace && over <= 2 * grace) {
            call.python?.cancel()
            return
        }
        if (over <= 2 * grace) return
        call.abandoned = true
        call.watch?.cancel(false)
        runCatching { call.thread?.priority = Thread.MIN_PRIORITY }
        val disabled = store.recordAbandon(call.plugin.id)?.enabled == false
        val message = PluginText.of(
            if (disabled) {
                R.string.core_plugins_stopped_not_responding_disabled
            } else {
                R.string.core_plugins_stopped_not_responding
            },
        )
        deliver(call, PluginActionResult.Stopped(message, disabled), evenIfAbandoned = true)
        synchronized(this) { if (current === call) current = null }
    }

    /** Hands [result] to the caller once, unless the call was cancelled or superseded. */
    private fun deliver(call: Call, result: PluginActionResult, evenIfAbandoned: Boolean = false) {
        if (call.cancelled) return
        if (call.abandoned && !evenIfAbandoned) return
        if (!call.delivered.compareAndSet(false, true)) return
        synchronized(this) { if (current === call) current = null }
        post { if (!call.cancelled) call.onDone(result) }
    }

    private class Call(
        val plugin: InstalledPlugin,
        val action: PluginActionSpec,
        val onDone: (PluginActionResult) -> Unit,
    ) {
        val delivered = AtomicBoolean(false)

        @Volatile
        var python: PythonSession? = null

        @Volatile
        var cancelled = false

        @Volatile
        var abandoned = false

        @Volatile
        var deadlineNanos = Long.MAX_VALUE

        @Volatile
        var thread: Thread? = null

        @Volatile
        var watch: ScheduledFuture<*>? = null
    }

    companion object {
        /** The function a plugin writes for its actions. */
        const val HOOK = "transform"

        /** More than any selection the bar offers actions for, and then some. */
        const val MAX_OUTPUT = 64 * 1024

        private const val MAX_ERROR = 300
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

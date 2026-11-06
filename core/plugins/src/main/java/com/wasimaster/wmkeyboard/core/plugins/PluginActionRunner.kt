package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.core.plugins.python.PyError
import com.wasimaster.wmkeyboard.core.plugins.python.PyRuntime
import com.wasimaster.wmkeyboard.plugins.R
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What one selection-action call came to. */
sealed interface PluginActionResult {

    /** [text] goes in place of the selection, or after it when [replaces] is false. */
    data class Output(val text: String, val replaces: Boolean) : PluginActionResult

    /** `transform` returned nil or None: leave the selection as it is. */
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
 * Each call builds a fresh sandbox, loads the script, calls `transform` once and
 * throws the whole environment away. A global the script sets while holding
 * the user's selection is gone before the next tap. Only what it writes to its
 * own `wm.storage` stays, and that has no way off the device.
 *
 * ## Stopping a call
 *
 * Lua runs on [PluginBudget]'s instruction hook, with a watchdog for a thread
 * stuck where the hook cannot reach (the same rules as [PluginRuntime]: an
 * abandoned call is a strike, and two strikes switch the plugin off). Python runs
 * on the PyMini port, which checks for cancellation on every step and so can
 * always be stopped; it never needs abandoning and never earns a strike.
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
        val thread = Thread({ execute(call, script, input) }, "wm-plugin-action-${plugin.id}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
        call.thread = thread
        if (!plugin.isPython) {
            call.watch = watchdog.scheduleWithFixedDelay(
                { patrol(call) },
                watchdogPeriodMillis,
                watchdogPeriodMillis,
                TimeUnit.MILLISECONDS,
            )
        }
        thread.start()
    }

    /** Stops the running call, if any. Its result is dropped. */
    @Synchronized
    fun cancel() {
        val call = current ?: return
        current = null
        call.cancelled = true
        call.budget.cancel()
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
            else -> null
        }
    }

    // ---- on the call's own thread -------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun execute(call: Call, script: String, input: String) {
        val log = PluginLog(store.logFile(call.plugin.id))
        val result = try {
            if (call.plugin.isPython) runPython(call, script, input) else runLua(call, log, script, input)
        } catch (abort: PluginAbort) {
            stoppedLua(call, log, abort)
        } catch (error: LuaError) {
            scriptFailed(log, error.message)
        } catch (error: PyError) {
            pythonFailed(call, log, error)
        } catch (_: StackOverflowError) {
            log.add("error: stack overflow")
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_stopped_recursion))
        } catch (failure: Throwable) {
            val name = failure.javaClass.simpleName
            log.add("error: " + (failure.message?.take(MAX_ERROR)?.let { "$name: $it" } ?: name))
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_stopped_unexpected))
        } finally {
            call.budget.end()
            call.watch?.cancel(false)
            log.flush()
        }
        if (result != null) deliver(call, result)
    }

    private fun runLua(call: Call, log: PluginLog, script: String, input: String): PluginActionResult {
        call.budget.begin(limit)
        val plugin = call.plugin
        val globals = PluginSandbox.create(call.budget) { line -> log.add(line) }
        val api = PluginHostApi(
            plugin = plugin,
            log = log,
            storage = if (plugin.grants(PluginPermission.Storage)) PluginStorage(store.storageFile(plugin.id)) else null,
            setInput = { _, _ -> },
            revoked = { call.cancelled || call.abandoned },
            reminders = if (plugin.grants(PluginPermission.Reminders)) {
                PluginReminders(store.remindersFile(plugin.id), onRemindersChanged)
            } else {
                null
            },
            forAction = true,
        )
        globals.set("wm", api.table())
        PluginSandbox.compile(globals, PluginPrelude.SOURCE, PluginPrelude.CHUNK_NAME).call()
        PluginSandbox.compile(globals, script).call()
        val transform = globals.get(HOOK)
        if (!transform.isfunction()) return PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_no_transform))
        val out = transform.call(LuaValue.valueOf(input), LuaValue.valueOf(call.action.id))
        return when {
            out.isnil() -> PluginActionResult.Unchanged
            out.isstring() -> output(call, out.tojstring())
            else -> PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_action_not_text))
        }
    }

    private fun runPython(call: Call, script: String, input: String): PluginActionResult {
        val deadline = System.nanoTime() + limit.wallMillis * NANOS_PER_MILLI
        val runtime = PyRuntime(cancelled = {
            if (System.nanoTime() > deadline) call.pastDeadline = true
            call.cancelled || call.pastDeadline
        })
        runtime.load(script)
        val parameters = runtime.parameters(HOOK)
            ?: return PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_no_transform))
        val value = if (parameters.size >= 2) {
            runtime.call(HOOK, input, call.action.id)
        } else {
            runtime.call(HOOK, input)
        }
        return when {
            value.isNone -> PluginActionResult.Unchanged
            else -> output(call, value.stringValue ?: value.str())
        }
    }

    private fun output(call: Call, text: String): PluginActionResult =
        PluginActionResult.Output(text.take(MAX_OUTPUT), call.action.replaces)

    /** A Lua abort: cancelled by the host (nothing to say), or out of allowance (a strike). */
    private fun stoppedLua(call: Call, log: PluginLog, abort: PluginAbort): PluginActionResult? {
        log.add("stopped: ${abort.reason.name.lowercase()}")
        // Cancelled by the host, or abandoned, which the watchdog already counted.
        if (abort.reason == PluginAbortReason.CANCELLED || call.abandoned) return null
        val disabled = store.recordAbandon(call.plugin.id)?.enabled == false
        return PluginActionResult.Stopped(PluginText.of(abort.reason.messageRes), disabled)
    }

    private fun pythonFailed(call: Call, log: PluginLog, error: PyError): PluginActionResult? {
        val text = error.message.orEmpty().take(MAX_ERROR)
        log.add("error: $text")
        return when {
            call.cancelled -> null
            call.pastDeadline -> PluginActionResult.Failed(PluginText.of(PluginAbortReason.DEADLINE.messageRes))
            error.isAbort -> PluginActionResult.Failed(PluginText.of(PluginAbortReason.INSTRUCTIONS.messageRes))
            text.isNotEmpty() -> PluginActionResult.Failed(PluginText.Script(text))
            else -> PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_script_failed))
        }
    }

    private fun scriptFailed(log: PluginLog, message: String?): PluginActionResult {
        val text = message?.take(MAX_ERROR)
        log.add("error: ${text ?: "the script failed"}")
        return PluginActionResult.Failed(
            text?.let { PluginText.Script(it) } ?: PluginText.of(R.string.core_plugins_error_script_failed),
        )
    }

    // ---- the watchdog -------------------------------------------------------

    /** Gives up on a Lua call stuck where the instruction hook cannot reach it. */
    private fun patrol(call: Call) {
        if (call.cancelled || call.abandoned || call.delivered.get()) return
        if (!call.budget.isStuck(watchdogGraceMillis)) return
        call.abandoned = true
        call.budget.cancel()
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
        val budget = PluginBudget()
        val delivered = AtomicBoolean(false)

        @Volatile
        var cancelled = false

        @Volatile
        var abandoned = false

        @Volatile
        var pastDeadline = false

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

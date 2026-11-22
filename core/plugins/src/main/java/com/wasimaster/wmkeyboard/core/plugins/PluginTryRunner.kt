package com.wasimaster.wmkeyboard.core.plugins

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Calls a draft's provider or selection action once, the way the keyboard
 * would: a fresh interpreter in action mode, the script loaded, the provider
 * (`provider:<tool>/<id>`) or action asked with [input], everything freed.
 *
 * For the plugin editor's Try pane, so an author sees what a provider list or
 * the selection bar would get back before installing anything. The draft runs
 * with the preview's storage and the services the editor's preview has, and
 * the same time allowance a real call has; it earns no strike.
 */
class PluginTryRunner(
    private val services: PluginServices = PluginServices.NONE,
    private val limit: PluginLimit = PluginLimit.ACTION,
    /** How long past its allowance a call may take to stop before it is left behind. */
    private val graceMillis: Long = GRACE_MILLIS,
) {

    /** What one call came to. [value] is the reply as the script returned it, JSON for a provider. */
    data class Outcome(
        val value: String?,
        /** The script's error or the runner's own words, when it failed. */
        val error: String?,
        val errorLine: Int? = null,
        /** What the script printed, oldest first. */
        val log: List<String>,
        val millis: Long,
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * Runs [script] as [plugin] and asks [actionId] with [input]. Blocking; call
     * it off the main thread. [storagePath] is where `storage` lives for the call.
     */
    fun call(
        plugin: InstalledPlugin,
        script: String,
        actionId: String,
        input: String,
        storagePath: String?,
        modules: Map<String, String> = emptyMap(),
    ): Outcome {
        val started = System.nanoTime()
        if (!PythonNative.available) return Outcome(null, NOT_READY, null, emptyList(), 0)
        val done = CountDownLatch(1)
        val holder = Holder()
        val thread = Thread(
            null,
            {
                holder.outcome = runCatching { execute(plugin, script, actionId, input, storagePath, modules, holder) }
                done.countDown()
            },
            "wm-plugin-try-${plugin.id}",
            PluginRuntime.PLUGIN_STACK_BYTES,
        ).apply { isDaemon = true }
        thread.start()
        var cancelledAt = 0L
        while (!done.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            val used = System.nanoTime() - started - (holder.binding?.excusedNanos() ?: 0L)
            if (cancelledAt == 0L && used > TimeUnit.MILLISECONDS.toNanos(limit.wallMillis)) {
                cancelledAt = System.nanoTime()
                holder.python?.cancel()
            } else if (cancelledAt != 0L && System.nanoTime() - cancelledAt > TimeUnit.MILLISECONDS.toNanos(graceMillis)) {
                return Outcome(null, TOO_LONG, null, holder.log.toList(), elapsed(started))
            }
        }
        val result = holder.outcome?.getOrNull()
        val failure = holder.outcome?.exceptionOrNull()
        return when {
            result != null -> result.copy(millis = elapsed(started))
            failure != null -> Outcome(null, failure.message ?: failure.javaClass.simpleName, null, holder.log.toList(), elapsed(started))
            else -> Outcome(null, TOO_LONG, null, holder.log.toList(), elapsed(started))
        }
    }

    private fun execute(
        plugin: InstalledPlugin,
        script: String,
        actionId: String,
        input: String,
        storagePath: String?,
        modules: Map<String, String>,
        holder: Holder,
    ): Outcome {
        val python = PythonSession.create(
            PythonSession.Config(
                plugin = plugin,
                mode = PythonSession.Config.ACTION,
                storagePath = storagePath,
                remindersPath = null,
                action = limit,
                modules = modules,
            ),
        ) ?: return Outcome(null, NOT_READY, null, emptyList(), 0)
        holder.python = python
        val binding = PluginHost.Binding(plugin, null, services, PythonSession.Config.ACTION) {}
        holder.binding = binding
        PluginHost.bind(python.id, binding)
        try {
            val loaded = python.load(script)
            holder.log += loaded.log
            loaded.error?.let { return Outcome(null, it.message.ifEmpty { it.kind }, it.line, holder.log.toList(), 0) }
            val result = python.transform(input, actionId)
            holder.log += result.log
            result.error?.let { return Outcome(null, it.message.ifEmpty { it.kind }, it.line, holder.log.toList(), 0) }
            return Outcome(result.value, null, null, holder.log.toList(), 0)
        } finally {
            holder.python = null
            PluginHost.unbind(python.id)
            python.close()
        }
    }

    private class Holder {
        @Volatile
        var outcome: Result<Outcome>? = null

        @Volatile
        var python: PythonSession? = null

        @Volatile
        var binding: PluginHost.Binding? = null

        val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    }

    private fun elapsed(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    companion object {
        private const val POLL_MILLIS = 50L
        private const val GRACE_MILLIS = 1_500L

        /** Markers the screen turns into words; never shown as they are. */
        const val NOT_READY = "\u0000not-ready"
        const val TOO_LONG = "\u0000too-long"
    }
}

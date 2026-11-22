package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Calls a plugin's provider: one of the keyboard's provider lists (web search,
 * translate, dictionary, …) asks it with a JSON request, and it answers with
 * the JSON its `@plugin.provider` function returned.
 *
 * Like [PluginActionRunner], every call starts from nothing: a fresh
 * interpreter in action mode, the script run, the provider called once, the
 * whole thing freed. Unlike it, calls do not cancel each other, since a search
 * and a translation may well be asked at once, and the caller blocks: the
 * provider lists are blocking code on an IO thread, and a provider is one
 * more row they walk.
 *
 * Host calls go through [services], the same ones a selection action gets, so
 * `wm.http` reaches only the plugin's own hosts, only with `network` granted,
 * never with Data saver on, and the network log shows each request.
 *
 * A failure throws an [IOException] with words for the user: the provider
 * list moves on to its next row and shows the last failure's words. A call
 * that ran away earns a strike, as an action's does.
 */
class PluginProviderRunner(
    private val store: PluginStore,
    private val services: PluginServices = PluginServices.NONE,
    private val limit: PluginLimit = PluginLimit.ACTION,
    /** Runs after a call changed its plugin's reminders. */
    private val onRemindersChanged: () -> Unit = {},
    private val graceMillis: Long = GRACE_MILLIS,
    /** Puts the runner's own messages into words; the app passes `{ it.resolve(context) }`. */
    private val words: (PluginText) -> String = { (it as? PluginText.Script)?.text.orEmpty() },
) {

    /** A provider call that did not answer. [message] is shown as it is. */
    class ProviderException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun refused(@androidx.annotation.StringRes res: Int, cause: Throwable? = null) =
        ProviderException(words(PluginText.of(res)), cause)

    /**
     * Asks the provider [key] (`plugin:<id>/<tool>/<provider>`, as
     * [PluginProviderRef.key] makes it) with [request]. Blocking; never on
     * the main thread. Returns what the function returned, `null` as
     * [JsonNull]. [isActive] is asked while waiting: false cancels the call.
     */
    fun call(key: String, request: JsonObject, isActive: () -> Boolean = { true }): JsonElement {
        val ref = store.runnableProvider(key) ?: throw refused(R.string.core_plugins_error_turned_off)
        val script = store.script(ref.plugin.id) ?: throw refused(R.string.core_plugins_error_script_missing)
        if (!PythonNative.available) throw refused(R.string.core_plugins_error_not_ready)
        val call = Call(ref)
        val thread = Thread(
            null,
            { call.outcome = runCatching { execute(call, script, request) }; call.done.countDown() },
            "wm-plugin-provider-${ref.plugin.id}",
            PluginRuntime.PLUGIN_STACK_BYTES,
        ).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
        thread.start()
        wait(call, isActive)
        return call.outcome?.getOrThrow() ?: throw refused(R.string.core_plugins_stopped_deadline)
    }

    /**
     * Waits for the call: past its allowance (host calls not counted) it is
     * cancelled, and a grace period after that abandoned with a strike.
     */
    private fun wait(call: Call, isActive: () -> Boolean) {
        val started = System.nanoTime()
        var cancelledAt = 0L
        while (!call.done.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (!isActive()) {
                call.python?.cancel()
                throw refused(R.string.core_plugins_stopped_cancelled)
            }
            val used = System.nanoTime() - started - (call.binding?.excusedNanos() ?: 0L)
            val over = used - TimeUnit.MILLISECONDS.toNanos(limit.wallMillis)
            if (over > 0 && cancelledAt == 0L) {
                cancelledAt = System.nanoTime()
                call.python?.cancel()
            } else if (cancelledAt != 0L && System.nanoTime() - cancelledAt > TimeUnit.MILLISECONDS.toNanos(graceMillis)) {
                call.abandoned = true
                store.recordAbandon(call.ref.plugin.id)
                throw refused(R.string.core_plugins_stopped_not_responding)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute(call: Call, script: String, request: JsonObject): JsonElement {
        val plugin = call.ref.plugin
        val log = PluginLog(store.logFile(plugin.id))
        try {
            val python = PythonSession.create(
                PythonSession.Config(
                    plugin = plugin,
                    mode = PythonSession.Config.ACTION,
                    storagePath = store.storageFile(plugin.id)?.path,
                    remindersPath = store.remindersFile(plugin.id)?.path,
                    action = limit,
                    modules = store.modules(plugin.id),
                    strings = store.strings(plugin.id),
                ),
            ) ?: throw refused(R.string.core_plugins_error_not_ready)
            call.python = python
            // A provider answers for a list the user asked, not for something
            // they did in the plugin's panel: the user-only ops stay closed.
            val binding = PluginHost.Binding(plugin, store, services, PythonSession.Config.ACTION) {}
            call.binding = binding
            PluginHost.bind(python.id, binding)
            try {
                val loaded = python.load(script)
                loaded.log.forEach(log::add)
                loaded.error?.let { throw failure(call, log, it) }
                val result = python.transform(request.toString(), ACTION_PREFIX + call.ref.provider.key)
                result.log.forEach(log::add)
                if (result.remindersChanged || loaded.remindersChanged) onRemindersChanged()
                result.error?.let { throw failure(call, log, it) }
                return reply(result.value)
            } finally {
                call.python = null
                PluginHost.unbind(python.id)
                python.close()
            }
        } catch (failure: ProviderException) {
            throw failure
        } catch (failure: Throwable) {
            val name = failure.javaClass.simpleName
            log.add("error: " + (failure.message?.take(MAX_ERROR)?.let { "$name: $it" } ?: name))
            throw refused(R.string.core_plugins_error_script_failed, failure)
        } finally {
            log.flush()
        }
    }

    /** The prelude's `{"value": …}` envelope, opened. */
    private fun reply(text: String?): JsonElement {
        if (text == null) return JsonNull
        if (text.length > MAX_REPLY) throw refused(R.string.core_plugins_error_provider_too_long)
        val envelope = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw refused(R.string.core_plugins_error_provider_unreadable)
        return envelope["value"] ?: JsonNull
    }

    private fun failure(call: Call, log: PluginLog, error: PythonSession.Error): ProviderException {
        if (error.kind == PythonSession.Error.ABORT) {
            val reason = PluginAbortReason.ofWire(error.abort) ?: PluginAbortReason.INSTRUCTIONS
            log.add("stopped: ${error.abort ?: "steps"}")
            if (reason != PluginAbortReason.CANCELLED && !call.abandoned &&
                reason != PluginAbortReason.RECURSION && reason != PluginAbortReason.MEMORY
            ) {
                store.recordAbandon(call.ref.plugin.id)
            }
            return refused(reason.messageRes)
        }
        val text = error.message.take(MAX_ERROR)
        log.add("error: ${text.ifEmpty { "the script failed" }}")
        return if (text.isEmpty()) refused(R.string.core_plugins_error_script_failed) else ProviderException(text)
    }

    private class Call(val ref: PluginProviderRef) {
        val done = CountDownLatch(1)

        @Volatile
        var outcome: Result<JsonElement>? = null

        @Volatile
        var python: PythonSession? = null

        @Volatile
        var binding: PluginHost.Binding? = null

        @Volatile
        var abandoned = false
    }

    companion object {
        /**
         * How the prelude knows a provider call from a selection action: no
         * manifest action id may hold a colon.
         */
        const val ACTION_PREFIX = "provider:"

        /** A search page or a dictionary entry, with room; a picture sent back as text is bigger. */
        const val MAX_REPLY = 4 * 1024 * 1024

        private const val POLL_MILLIS = 100L
        private const val GRACE_MILLIS = 1_500L
        private const val MAX_ERROR = 300
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

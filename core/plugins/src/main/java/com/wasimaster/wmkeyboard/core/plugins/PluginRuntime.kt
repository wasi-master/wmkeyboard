package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one plugin at a time, off the keyboard's thread, with a watchdog.
 *
 * The contract the rest of the app relies on: **nothing here can make the
 * keyboard wait.** Every call into the script happens on a dedicated daemon
 * thread, results come back through [Listener], and the worst a broken plugin
 * can do is fail to draw its own panel.
 *
 * ## Stopping a plugin that will not stop
 *
 * The interpreter (`libwm_python_jni`, through [PythonSession]) counts steps and
 * reads the clock on every one, and checks a cancel flag as often, so an
 * infinite loop, a runaway regex or an overlong call all stop themselves
 * within one step of their limit. The watchdog is the fallback for a call
 * that is somehow still running [Limits.watchdogGraceMillis] past its
 * deadline: it cancels it, and if that has not helped after another grace
 * period it *abandons* the thread. Abandoning severs rather than kills: the
 * session is marked revoked so nothing it produces reaches the app, its
 * executor is shut down, its priority dropped, and the runtime forgets it.
 * A plugin that is abandoned twice is disabled ([PluginStore.recordAbandon]).
 *
 * ## Drafts
 *
 * The plugin editor runs a script nobody has installed, through [open] with a
 * [Draft]. A draft is never in the store, earns no strikes, and uses whatever
 * log, storage and reminders files the editor hands it, so running half-written
 * code can never disable or overwrite the installed plugin it will replace.
 *
 * ## Threading
 *
 * [open], [dispatch] and [close] are called from the keyboard's thread and
 * never wait on the script: freeing an interpreter is queued behind whatever
 * call it is in. Every [Listener] callback is handed to [post], which the host
 * supplies to hop back to its own thread; tests pass a direct runner. Nothing
 * in here calls back into itself, and the listener must not re-enter the
 * runtime synchronously.
 */
class PluginRuntime(
    private val store: PluginStore,
    private val listener: Listener,
    private val limits: Limits = Limits(),
    private val post: (() -> Unit) -> Unit = { it() },
    /** Extra signal for the plugin editor. The keyboard passes none. */
    private val observer: Observer? = null,
    /**
     * Runs on the plugin thread after an installed plugin changed its
     * reminders, so the host can move its wake-up. Must not block.
     */
    private val onRemindersChanged: () -> Unit = {},
    /** What API 5 host calls reach: the IME's field and clipboard, the app's network and AI. */
    private val services: PluginServices = PluginServices.NONE,
    /** Where this runtime's panel is drawn: "panel" or "float". */
    private val surface: String = PythonSession.Config.PANEL,
    /** Whether the keyboard is dark right now, for `plugin.theme`. */
    private val dark: () -> Boolean = { false },
) {

    /**
     * Everything the host needs to hear about. Delivered through [post].
     *
     * The messages arrive as [PluginText] rather than as words, because this
     * runtime has no Context. The host turns one into a line of text with
     * `message.resolve(context)` where it draws it.
     */
    interface Listener {
        fun onUi(pluginId: String, ui: RenderedUi)

        /** A call is taking long enough to be worth a spinner. */
        fun onBusy(pluginId: String, busy: Boolean)

        /** The script failed. The plugin is still loaded and can be retried. */
        fun onError(pluginId: String, message: PluginText)

        /** `wm.ui.set_input` wrote to one of the plugin's own text boxes. */
        fun onInputWrite(pluginId: String, inputId: String, text: String)

        /** The session is over and will not recover. [disabled] means it used its last strike. */
        fun onStopped(pluginId: String, message: PluginText, disabled: Boolean)

        /** API 5: the plugin's header commands, page title and back button changed. */
        fun onHost(pluginId: String, state: PythonSession.HostState) {}

        /** API 5: a call needed risky permissions the user has not turned on. The panel offers them. */
        fun onNeeds(pluginId: String, permissions: List<PluginPermission>) {}
    }

    /** Which call into the script a report is about. */
    enum class Phase { LOAD, EVENT, RENDER }

    /**
     * Why a call failed, told the way an author needs it. Unlike the [Listener]'s
     * message, [text] is not cut short. [line] is the line of `main.py` the
     * interpreter named, when it named one; [chunk] is then [SCRIPT_CHUNK].
     */
    data class Failure(
        val kind: Kind,
        val text: String,
        val chunk: String?,
        val line: Int?,
        val phase: Phase,
    ) {
        enum class Kind { COMPILE, RUNTIME, INSTRUCTIONS, DEADLINE, CANCELLED, ABANDONED, RECURSION, MEMORY, UNEXPECTED }
    }

    /**
     * What an authoring host wants beyond what the keyboard needs: every printed
     * line, what each call spent, why a call failed in full, and each event as it
     * arrives.
     *
     * Called **on the plugin thread**, not through [post], and [onFailure] for an
     * abandoned session on the watchdog's. An implementation must not block and
     * must not touch UI state directly. Every method has a do-nothing default.
     */
    interface Observer {
        fun onPrint(pluginId: String, line: String) {}

        fun onPhase(pluginId: String, phase: Phase, usage: PluginBudget.Usage) {}

        fun onFailure(pluginId: String, failure: Failure) {}

        fun onEvent(pluginId: String, event: PluginEvent) {}
    }

    /**
     * A session supplied in full, rather than one read out of the store.
     * [storage] and [reminders] are the files the interpreter keeps
     * `wm.storage` and `wm.reminders` in, or null for memory only; the
     * plugin's permissions still decide whether the script can reach them.
     */
    data class Draft(
        val plugin: InstalledPlugin,
        val script: String,
        val log: PluginLog,
        val storage: java.io.File?,
        /** Runaway strikes belong to installed plugins. A draft never earns one. */
        val recordStrikes: Boolean = false,
        val reminders: java.io.File? = null,
        /** Whether a change to [reminders] should move the app's wake-up: only an installed plugin's. */
        val liveReminders: Boolean = false,
        /** API 5: the plugin's other modules, `{"helpers.py": source}`. */
        val modules: Map<String, String> = emptyMap(),
        /** API 5: its translations for the user's language. */
        val strings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

    data class Limits(
        val load: PluginLimit = PluginLimit.LOAD,
        val event: PluginLimit = PluginLimit.EVENT,
        val render: PluginLimit = PluginLimit.RENDER,
        val watchdogPeriodMillis: Long = 250,
        val watchdogGraceMillis: Long = 500,
        val busyAfterMillis: Long = 150,
    )

    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "wm-plugin-watchdog").apply { isDaemon = true }
        }

    private var session: Session? = null

    /** The plugin currently loaded, if any. */
    @get:Synchronized
    val activePluginId: String?
        get() = session?.plugin?.id

    // ---- lifecycle -------------------------------------------------------

    /** Loads a plugin and renders it. Replaces whatever was open. */
    @Synchronized
    fun open(pluginId: String) {
        close()
        val plugin = store.plugin(pluginId)
        if (plugin == null) {
            post { listener.onError(pluginId, PluginText.of(R.string.core_plugins_error_not_installed)) }
            return
        }
        if (!plugin.enabled) {
            post { listener.onError(pluginId, PluginText.of(R.string.core_plugins_error_turned_off)) }
            return
        }
        val script = store.script(pluginId)
        if (script == null) {
            post { listener.onError(pluginId, PluginText.of(R.string.core_plugins_error_script_missing)) }
            return
        }
        start(
            Draft(
                plugin = plugin,
                script = script,
                log = PluginLog(store.logFile(pluginId)),
                storage = store.storageFile(pluginId),
                recordStrikes = true,
                reminders = store.remindersFile(pluginId),
                liveReminders = true,
                modules = store.modules(pluginId),
                strings = store.strings(pluginId),
            ),
        )
    }

    /** Loads a script the caller supplies, installed or not, and renders it. Replaces whatever was open. */
    @Synchronized
    fun open(draft: Draft) {
        close()
        start(draft)
    }

    /** Hands a user action to the script, then re-renders. */
    @Synchronized
    fun dispatch(event: PluginEvent) {
        val current = session ?: return
        enqueue(current, event)
    }

    /**
     * The keyboard's back button for an API 5 panel: True when the plugin had
     * a page to go back from, which it now leaves.
     */
    @Synchronized
    fun back(): Boolean {
        val current = session ?: return false
        if (current.host?.canBack != true) return false
        enqueue(current, PythonSession.hostEvent("back"))
        return true
    }

    /** A header command of an API 5 plugin was tapped. */
    fun command(id: String) = dispatch(PythonSession.hostEvent("command", mapOf("id" to kotlinx.serialization.json.JsonPrimitive(id))))

    /** The user typed `@keyword query` and tapped the plugin's chip. */
    fun keyword(word: String, query: String) = dispatch(
        PythonSession.hostEvent(
            "keyword",
            mapOf("id" to kotlinx.serialization.json.JsonPrimitive(word), "value" to kotlinx.serialization.json.JsonPrimitive(query)),
        ),
    )

    /** A value on the plugin's settings page changed. */
    fun settingChanged(key: String, value: kotlinx.serialization.json.JsonElement) = dispatch(
        PythonSession.hostEvent("setting", mapOf("key" to kotlinx.serialization.json.JsonPrimitive(key), "value" to value)),
    )

    /** Queues [event] for [current], unless the queue is full of host events already. */
    private fun enqueue(current: Session, event: PluginEvent) {
        // A timer that fires faster than the script handles it, or a stream
        // sending pieces faster than they are drawn, drops pieces rather than
        // building a backlog that keeps the plugin busy after the user left.
        if (event is PluginEvent.Host && current.queued.get() >= MAX_QUEUED && event.type != "result") return
        current.queued.incrementAndGet()
        submit(current, limits.event, Phase.EVENT) {
            current.queued.decrementAndGet()
            handle(current, event)
        }
    }

    /** Ends the session. Safe to call when nothing is open, and never waits for the script. */
    @Synchronized
    fun close() {
        val current = session ?: return
        session = null
        current.revoked = true
        current.python?.cancel()
        current.watch?.cancel(false)
        current.timers.values.forEach { it.cancel(false) }
        current.timers.clear()
        current.log.flush()
        val python = current.python
        if (python != null && current.plugin.isPackage && current.loaded) {
            // @plugin.on_close: one last short event. The cancel above stops
            // whatever call was running; this one starts with the flag cleared.
            runCatching { current.executor.execute { runCatching { python.event(PythonSession.hostEvent("close")) } } }
        }
        // Freed on the plugin thread, after the call it may be in has stopped.
        runCatching {
            current.executor.execute {
                python?.let { PluginHost.unbind(it.id) }
                python?.close()
            }
        }
        current.executor.shutdown()
    }

    /** Ends the session and stops the watchdog. For the service's own teardown. */
    @Synchronized
    fun shutdown() {
        close()
        watchdog.shutdownNow()
    }

    private fun start(draft: Draft) {
        val fresh = Session(draft.plugin, draft.log, draft.recordStrikes)
        session = fresh
        fresh.watch = watchdog.scheduleWithFixedDelay(
            { patrol(fresh) },
            limits.watchdogPeriodMillis,
            limits.watchdogPeriodMillis,
            TimeUnit.MILLISECONDS,
        )
        submit(fresh, limits.load, Phase.LOAD) { load(fresh, draft) }
    }

    // ---- work on the plugin thread ---------------------------------------

    private fun load(current: Session, draft: Draft) {
        val python = PythonSession.create(
            PythonSession.Config(
                plugin = current.plugin,
                mode = PythonSession.Config.PANEL,
                storagePath = draft.storage?.path,
                remindersPath = draft.reminders?.path,
                load = limits.load,
                event = limits.event,
                render = limits.render,
                modules = draft.modules,
                strings = draft.strings,
                locale = PluginLocales.preferred().firstOrNull()?.toLanguageTag() ?: java.util.Locale.getDefault().toLanguageTag(),
                dark = dark(),
                surface = surface,
            ),
        )
        if (python == null) {
            val message = PluginText.of(R.string.core_plugins_error_not_ready)
            deliver(current) { listener.onError(current.plugin.id, message) }
            return
        }
        current.python = python
        current.liveReminders = draft.liveReminders
        if (current.plugin.isPackage) {
            // A draft is the author's own code in the editor: its grants come
            // with the draft, and the global risky switch is not asked.
            val gate = if (current.recordStrikes) store else null
            val binding = PluginHost.Binding(current.plugin, gate, services, surface) { event ->
                synchronized(this) { if (session === current) enqueue(current, event) }
            }
            current.binding = binding
            PluginHost.bind(python.id, binding)
        }
        handleResult(current, python.load(draft.script), Phase.LOAD)
        current.loaded = true
    }

    private fun handle(current: Session, event: PluginEvent) {
        val python = current.python ?: return
        observer?.onEvent(current.plugin.id, event)
        val binding = current.binding
        binding?.userEvent = isUserEvent(event)
        try {
            handleResult(current, python.event(event), Phase.EVENT)
        } finally {
            binding?.userEvent = false
        }
    }

    /** Whether [event] is the user acting in the panel, which is when the field may be read. */
    private fun isUserEvent(event: PluginEvent): Boolean = when (event) {
        is PluginEvent.Host -> event.type == "keyword" || event.type == "command"
        else -> true
    }

    /** Schedules the timers the script asked for, and cancels the ones it dropped. */
    private fun syncTimers(current: Session, timers: List<PythonSession.HostState.Timer>) {
        if (current.revoked) return
        val wanted = timers.associateBy { it.id }
        for (id in current.timers.keys.toList()) {
            val spec = wanted[id]
            if (spec == null || current.timerSpecs[id] != spec) {
                current.timers.remove(id)?.cancel(false)
                current.timerSpecs.remove(id)
            }
        }
        for ((id, spec) in wanted) {
            if (id in current.timers) continue
            val millis = (spec.seconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(if (spec.repeat) MIN_REPEAT_MILLIS else MIN_ONCE_MILLIS)
            val fire = Runnable {
                synchronized(this) {
                    if (session === current) {
                        if (!spec.repeat) {
                            current.timers.remove(id)
                            current.timerSpecs.remove(id)
                        }
                        enqueue(current, PythonSession.hostEvent("timer", mapOf("id" to kotlinx.serialization.json.JsonPrimitive(id))))
                    }
                }
            }
            current.timers[id] = if (spec.repeat) {
                watchdog.scheduleWithFixedDelay(fire, millis, millis, TimeUnit.MILLISECONDS)
            } else {
                watchdog.schedule(fire, millis, TimeUnit.MILLISECONDS)
            }
            current.timerSpecs[id] = spec
        }
    }

    /**
     * Everything a result carries, in the order it happened: printed lines,
     * what each phase spent, input writes, then the panel or the failure.
     */
    private fun handleResult(current: Session, result: PythonSession.Result, phase: Phase) {
        for (line in result.log) {
            current.log.add(line)
            observer?.onPrint(current.plugin.id, line)
        }
        for (spent in result.phases) {
            val which = when (spent.phase) {
                "load" -> Phase.LOAD
                "event" -> Phase.EVENT
                "render" -> Phase.RENDER
                else -> null
            } ?: continue
            current.phase = which
            observer?.onPhase(current.plugin.id, which, spent.usage)
        }
        if (result.remindersChanged && current.liveReminders) onRemindersChanged()
        for ((id, text) in result.inputs) {
            deliver(current) { listener.onInputWrite(current.plugin.id, id, text) }
        }
        result.host?.let { state ->
            current.host = state
            synchronized(this) { syncTimers(current, state.timers) }
            deliver(current) { listener.onHost(current.plugin.id, state) }
        }
        val needs = result.needs.mapNotNull { PluginPermission.parse(it) }
        if (needs.isNotEmpty()) deliver(current) { listener.onNeeds(current.plugin.id, needs) }
        val error = result.error
        when {
            result.ok && result.ui != null -> {
                val ui = result.ui
                deliver(current) { listener.onUi(current.plugin.id, ui) }
            }

            // The phase the result ended in: a render() failing after a load is a render failure.
            error != null -> failed(current, error, if (result.phases.isEmpty()) phase else current.phase)
        }
    }

    // ---- scheduling and failure -------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun submit(current: Session, limit: PluginLimit, phase: Phase, body: () -> Unit) {
        val finished = AtomicBoolean(false)
        val busy = watchdog.schedule(
            {
                if (!finished.get() && !current.revoked) {
                    deliver(current) { listener.onBusy(current.plugin.id, true) }
                }
            },
            limits.busyAfterMillis,
            TimeUnit.MILLISECONDS,
        )
        val submitted = runCatching {
            current.executor.execute {
                if (current.revoked) {
                    busy.cancel(false)
                    return@execute
                }
                current.thread = Thread.currentThread()
                current.phase = phase
                // Render follows load and event in the same call, on its own allowance.
                current.deadlineNanos = System.nanoTime() + (limit.wallMillis + limits.render.wallMillis) * NANOS_PER_MILLI
                current.excusedAtStart = current.binding?.excusedNanos() ?: 0L
                try {
                    body()
                } catch (failure: Throwable) {
                    unexpected(current, failure)
                } finally {
                    current.deadlineNanos = Long.MAX_VALUE
                    finished.set(true)
                    busy.cancel(false)
                    deliver(current) { listener.onBusy(current.plugin.id, false) }
                }
            }
        }.isSuccess
        // The executor is already shutting down: the session is over and there
        // is nobody left to tell.
        if (!submitted) busy.cancel(false)
    }

    private fun failed(current: Session, error: PythonSession.Error, phase: Phase) {
        when (error.kind) {
            PythonSession.Error.ABORT -> {
                val reason = PluginAbortReason.ofWire(error.abort) ?: PluginAbortReason.INSTRUCTIONS
                val wire = error.abort ?: "steps"
                current.log.add("stopped: $wire")
                observe(current, abortKind(reason), "stopped: $wire", null, phase)
                // Cancellation is the host closing the panel, which the user did
                // on purpose and does not need to be told about.
                if (reason != PluginAbortReason.CANCELLED && !current.revoked) {
                    stop(current, PluginText.of(reason.messageRes), strike = reason.strikes)
                }
            }

            PythonSession.Error.NO_RENDER -> {
                val message = PluginText.of(R.string.core_plugins_error_no_render)
                deliver(current) { listener.onError(current.plugin.id, message) }
            }

            PythonSession.Error.COMPILE, PythonSession.Error.RUNTIME -> {
                val raw = error.message.take(MAX_ERROR)
                current.log.add("error: ${raw.ifEmpty { "the script failed" }}")
                val kind = if (error.kind == PythonSession.Error.COMPILE) Failure.Kind.COMPILE else Failure.Kind.RUNTIME
                observe(current, kind, error.message, error.line, phase)
                // The interpreter wrote this, so it goes to the panel as it
                // arrived. Only the stand-in for a missing message is ours.
                val message = raw.takeIf { it.isNotEmpty() }?.let { PluginText.Script(locatedText(it, error.line)) }
                    ?: PluginText.of(R.string.core_plugins_error_script_failed)
                deliver(current) { listener.onError(current.plugin.id, message) }
            }

            else -> {
                current.log.add("error: ${error.kind}: ${error.message.take(MAX_ERROR)}")
                observe(current, Failure.Kind.UNEXPECTED, error.message, error.line, phase)
                stop(current, PluginText.of(R.string.core_plugins_stopped_unexpected), strike = false)
            }
        }
    }

    /** Something in this process threw: never the script's doing, so never a strike. */
    private fun unexpected(current: Session, failure: Throwable) {
        val name = failure.javaClass.simpleName
        val detail = failure.message?.take(MAX_ERROR)
        current.log.add("error: " + if (detail != null) "$name: $detail" else name)
        observe(current, Failure.Kind.UNEXPECTED, failure.message?.let { "$name: $it" } ?: name, null, current.phase)
        stop(current, PluginText.of(R.string.core_plugins_stopped_unexpected), strike = false)
    }

    private fun observe(current: Session, kind: Failure.Kind, text: String, line: Int?, phase: Phase) {
        val watcher = observer ?: return
        watcher.onFailure(current.plugin.id, Failure(kind, text, line?.let { SCRIPT_CHUNK }, line, phase))
    }

    private fun abortKind(reason: PluginAbortReason): Failure.Kind = when (reason) {
        PluginAbortReason.INSTRUCTIONS -> Failure.Kind.INSTRUCTIONS
        PluginAbortReason.DEADLINE -> Failure.Kind.DEADLINE
        PluginAbortReason.CANCELLED -> Failure.Kind.CANCELLED
        PluginAbortReason.RECURSION -> Failure.Kind.RECURSION
        PluginAbortReason.MEMORY -> Failure.Kind.MEMORY
    }

    /** Ends the session for a reason the user should see. */
    private fun stop(current: Session, message: PluginText, strike: Boolean) {
        val disabled = if (strike && current.recordStrikes) {
            store.recordAbandon(current.plugin.id)?.enabled == false
        } else {
            false
        }
        deliverFinal { listener.onStopped(current.plugin.id, message, disabled) }
        synchronized(this) {
            if (session === current) close()
        }
    }

    /**
     * One watchdog tick. A call past its deadline by a grace period is
     * cancelled; one still running a grace period after that is abandoned.
     */
    private fun patrol(current: Session) {
        if (current.revoked) return
        val deadline = current.deadlineNanos
        if (deadline == Long.MAX_VALUE) return
        // Time in host calls (a request, the AI) is the keyboard's, not the script's.
        val over = System.nanoTime() - deadline - (current.binding?.excusedNanos() ?: 0L) + current.excusedAtStart
        val grace = limits.watchdogGraceMillis * NANOS_PER_MILLI
        when {
            over > 2 * grace -> abandon(current)
            over > grace -> current.python?.cancel()
        }
    }

    private fun abandon(current: Session) {
        current.revoked = true
        current.watch?.cancel(false)
        current.binding?.active = false
        synchronized(this) {
            current.timers.values.forEach { it.cancel(false) }
            current.timers.clear()
        }
        current.log.add("abandoned: exceeded its time limit")
        current.log.flush()
        observe(current, Failure.Kind.ABANDONED, "abandoned: exceeded its time limit", null, current.phase)
        // Severed rather than stopped: it may still be running, but nothing it
        // returns reaches the app now, and it is the lowest-priority daemon on
        // the device. Its interpreter is never freed, since it may still be in it.
        runCatching { current.executor.shutdownNow() }
        runCatching { current.thread?.priority = Thread.MIN_PRIORITY }

        val record = if (current.recordStrikes) store.recordAbandon(current.plugin.id) else null
        val disabled = record?.enabled == false
        val message = PluginText.of(
            if (disabled) {
                R.string.core_plugins_stopped_not_responding_disabled
            } else {
                R.string.core_plugins_stopped_not_responding
            },
        )
        deliverFinal { listener.onStopped(current.plugin.id, message, disabled) }
        synchronized(this) { if (session === current) session = null }
    }

    /**
     * Delivers an ordinary update, dropped if the session has been superseded —
     * a render that finished after the user closed the panel is noise.
     */
    private fun deliver(current: Session, body: () -> Unit) {
        if (current.revoked) return
        post {
            if (!current.revoked) body()
        }
    }

    /**
     * Delivers a terminal message, always.
     *
     * Deliberately not routed through [deliver]: ending a session is precisely
     * the moment [Session.revoked] gets set, so filtering on it here would drop
     * every "this plugin stopped responding" notice and leave the panel sitting
     * empty forever with nothing to explain why.
     */
    private fun deliverFinal(body: () -> Unit) {
        post(body)
    }

    // ---- one plugin's world -----------------------------------------------

    private class Session(
        val plugin: InstalledPlugin,
        val log: PluginLog,
        val recordStrikes: Boolean,
    ) {
        /** Set on the plugin thread by the load; read by the watchdog and [close]. */
        @Volatile
        var python: PythonSession? = null

        @Volatile
        var liveReminders = false

        /** API 5: the link [PluginHost] routes this session's host calls through. */
        @Volatile
        var binding: PluginHost.Binding? = null

        /** API 5: what the last render reported. */
        @Volatile
        var host: PythonSession.HostState? = null

        @Volatile
        var loaded = false

        /** [PluginHost.Binding.excusedNanos] when the call in flight started. */
        @Volatile
        var excusedAtStart = 0L

        /** Events waiting on the plugin thread. */
        val queued = java.util.concurrent.atomic.AtomicInteger(0)

        /** API 5 timers by id, guarded by the runtime's lock. */
        val timers = HashMap<String, ScheduledFuture<*>>()
        val timerSpecs = HashMap<String, PythonSession.HostState.Timer>()

        @Volatile
        var revoked = false

        @Volatile
        var thread: Thread? = null

        /** When the call in flight should have finished; [Long.MAX_VALUE] when idle. */
        @Volatile
        var deadlineNanos = Long.MAX_VALUE

        /** The call the plugin thread is in, for an observer's reports. */
        @Volatile
        var phase = Phase.LOAD

        var watch: ScheduledFuture<*>? = null

        val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(null, runnable, "wm-plugin-${plugin.id}", PLUGIN_STACK_BYTES).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    companion object {
        private const val MAX_ERROR = 300
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1000.0

        /** The shortest repeat `plugin.every` gets: once a second. */
        private const val MIN_REPEAT_MILLIS = 1000L
        private const val MIN_ONCE_MILLIS = 50L

        /** Host events waiting before more are dropped. */
        private const val MAX_QUEUED = 32

        /**
         * The interpreter recurses on the native stack, 300 calls deep at most,
         * which wants about 1 MB; `libwm_python_jni` asks its hosts for 8 MB so a
         * recursion stop can never be a stack overflow that takes the process.
         */
        const val PLUGIN_STACK_BYTES = 8L * 1024 * 1024

        /** The file a [Failure]'s line is in. */
        const val SCRIPT_CHUNK = PluginStore.SCRIPT_FILE

        /** `main.py:12: message`, the shape the keyboard's panel shows an error in. */
        fun locatedText(message: String, line: Int?): String =
            if (line == null) message else "$SCRIPT_CHUNK:$line: $message"
    }
}

/** Whether stopping for this reason counts toward disabling the plugin. */
private val PluginAbortReason.strikes: Boolean
    get() = this == PluginAbortReason.INSTRUCTIONS || this == PluginAbortReason.DEADLINE

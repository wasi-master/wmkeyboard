package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the plugin editor's draft and keeps what the editor shows about it: the
 * panel it drew, the console, what each call spent, and why it failed.
 *
 * One session per editor screen. [run] hands the buffer to a [PluginRuntime] as
 * a [PluginRuntime.Draft], so it is never installed and never earns a strike,
 * and its storage and log live in the draft's [PluginWorkspace] directory rather
 * than beside an installed plugin, even one with the same id. The draft
 * manifest's permissions are honoured exactly as an install would honour them.
 *
 * Everything the screen reads is in [state]. The console is kept apart, read
 * with [console], because a plugin that prints inside its render loop can write
 * hundreds of lines a second; [State.consoleRevision] moves at most once per
 * [post], however many lines arrived.
 *
 * [post] hops to the screen's thread, as it does for the runtime. Tests pass a
 * direct runner. Context-free, so all of it is driven from a plain JVM test.
 */
class PluginPreviewSession(
    private val workspace: PluginWorkspace,
    store: PluginStore = PluginStore(null),
    private val clock: () -> Long = System::currentTimeMillis,
    private val post: (() -> Unit) -> Unit = { it() },
    limits: PluginRuntime.Limits = PluginRuntime.Limits(),
) {

    enum class Status { IDLE, RUNNING, READY, FAILED, STOPPED }

    data class State(
        val status: Status = Status.IDLE,
        val draftId: String? = null,
        val ui: RenderedUi = RenderedUi.EMPTY,
        val busy: Boolean = false,
        /** The last failure of the current run. A replaced run's cancellation is never this. */
        val failure: PluginRuntime.Failure? = null,
        val usage: Map<PluginRuntime.Phase, PluginBudget.Usage> = emptyMap(),
        val targets: PluginTargets = PluginTargets.EMPTY,
        /** The text in each of the preview's input widgets, by id. */
        val inputs: Map<String, String> = emptyMap(),
        val consoleRevision: Int = 0,
        /** How many runs have started. */
        val runs: Int = 0,
    )

    /**
     * One console line. [at] is milliseconds since its run started. [text] is the
     * script's or the interpreter's own words; [message] is a line this build
     * writes, resolved where it is drawn.
     */
    data class ConsoleEntry(
        val at: Long,
        val kind: Kind,
        val text: String = "",
        val message: PluginText? = null,
        val chunk: String? = null,
        val line: Int? = null,
        val failure: PluginRuntime.Failure.Kind? = null,
        val repair: PluginRepair? = null,
    ) {
        enum class Kind { STARTED, PRINT, REPAIR, ERROR, STOPPED, CANCELLED }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val consoleLock = Any()
    private val entries = ArrayDeque<ConsoleEntry>()
    private val events = ArrayDeque<PluginEvent>()
    private val flushPending = AtomicBoolean(false)

    @Volatile
    private var runStartedAt = 0L

    /** The repairs the last render reported, so an unchanged list is not repeated. Screen thread only. */
    private var lastRepairs: List<PluginText> = emptyList()

    /** The storage inspector's view of a draft's file. One instance per draft. */
    private var storageOwner: String? = null
    private var sharedStorage: PluginStorage? = null
    private var remindersOwner: String? = null
    private var sharedReminders: PluginReminders? = null

    private val runtime = PluginRuntime(store, RuntimeListener(), limits, post, RuntimeObserver())

    companion object {
        const val MAX_CONSOLE = 500
        const val MAX_EVENTS = 200

        /** Enough for a deep traceback; beyond this the console is not where it will be read. */
        const val MAX_ERROR_TEXT = 8 * 1024

        /** The same cap the keyboard puts on a plugin's own input box. */
        const val MAX_INPUT = 8 * 1024
    }

    // ---- control ---------------------------------------------------------

    /** Runs [source] as the draft [draftId] with [manifest], replacing any run in flight. */
    fun run(draftId: String, source: String, manifest: PluginManifest) {
        val permissions = manifest.permissions.mapNotNull { PluginPermission.parse(it) }.distinct()
        val plugin = InstalledPlugin(
            id = PluginManifestCodec.sanitise(manifest.id, PluginManifestCodec.Field.ID).ifBlank { draftId },
            name = manifest.name,
            version = manifest.pluginVersion,
            author = manifest.author,
            description = manifest.description,
            apiVersion = manifest.apiVersion,
            permissions = permissions.map { it.wire },
        )
        // The files live in the draft's preview folder whether or not the
        // manifest asks for them; the permissions decide what the script reaches.
        val storage = workspace.previewStorageFile(draftId)
        val reminders = workspace.previewRemindersFile(draftId)
        runStartedAt = clock()
        lastRepairs = emptyList()
        _state.update {
            it.copy(
                status = Status.RUNNING,
                draftId = draftId,
                failure = null,
                usage = emptyMap(),
                inputs = if (it.draftId == draftId) it.inputs else emptyMap(),
                runs = it.runs + 1,
            )
        }
        append(ConsoleEntry(0, ConsoleEntry.Kind.STARTED))
        runtime.open(
            PluginRuntime.Draft(plugin, source, PluginLog(workspace.previewLogFile(draftId)), storage, reminders = reminders),
        )
        // The inspectors read the same files the run writes.
        storageOf(draftId)
        remindersOf(draftId)
    }

    /** Hands [event] to the running draft. Text typed into a preview input is kept for the input to show. */
    fun send(event: PluginEvent) {
        val sent = if (event is PluginEvent.InputChanged) event.copy(value = event.value.take(MAX_INPUT)) else event
        if (sent is PluginEvent.InputChanged) {
            _state.update { it.copy(inputs = it.inputs + (sent.id to sent.value)) }
        }
        runtime.dispatch(sent)
    }

    /** Ends the current run. The panel stays as it last drew. */
    fun stop() {
        runtime.close()
        _state.update { it.copy(status = if (it.status == Status.IDLE) Status.IDLE else Status.STOPPED, busy = false) }
    }

    /** Ends the run and stops the runtime's watchdog. For when the editor screen goes away. */
    fun shutdown() {
        runtime.shutdown()
    }

    /** The storage preview runs of [draftId] use, for the storage inspector. Null when there is nowhere to keep it. */
    @Synchronized
    fun storageOf(draftId: String): PluginStorage? {
        if (storageOwner != draftId) {
            storageOwner = draftId
            sharedStorage = workspace.previewStorageFile(draftId)?.let { PluginStorage(it) }
        }
        return sharedStorage
    }

    /**
     * The reminders preview runs of [draftId] set. Kept in the draft's preview
     * folder and never shown in the shade: the alarms read installed plugins'
     * files only, and a half-written script that set a reminder every second
     * would otherwise be a notification storm.
     */
    @Synchronized
    fun remindersOf(draftId: String): PluginReminders {
        if (remindersOwner != draftId) {
            remindersOwner = draftId
            sharedReminders = PluginReminders(workspace.previewRemindersFile(draftId))
        }
        return sharedReminders ?: PluginReminders(null).also { sharedReminders = it }
    }

    fun console(): List<ConsoleEntry> = synchronized(consoleLock) { entries.toList() }

    fun clearConsole() {
        synchronized(consoleLock) { entries.clear() }
        bumpConsole()
    }

    /** The events the current draft has been sent, oldest first, for a replay after an edit. */
    fun recordedEvents(): List<PluginEvent> = synchronized(consoleLock) { events.toList() }

    // ---- what the runtime reports ------------------------------------------

    private fun append(entry: ConsoleEntry) {
        synchronized(consoleLock) {
            entries.addLast(entry)
            while (entries.size > MAX_CONSOLE) entries.removeFirst()
        }
        bumpConsole()
    }

    /** One revision bump per burst: a post that has not run yet already covers the lines after it. */
    private fun bumpConsole() {
        if (!flushPending.compareAndSet(false, true)) return
        post {
            flushPending.set(false)
            _state.update { it.copy(consoleRevision = it.consoleRevision + 1) }
        }
    }

    private fun elapsed(): Long = (clock() - runStartedAt).coerceAtLeast(0L)

    private inner class RuntimeListener : PluginRuntime.Listener {
        override fun onUi(pluginId: String, ui: RenderedUi) {
            _state.update {
                it.copy(
                    ui = ui,
                    targets = ui.targets(),
                    status = if (it.status == Status.FAILED && it.failure?.kind == PluginRuntime.Failure.Kind.COMPILE) {
                        it.status
                    } else {
                        Status.READY
                    },
                )
            }
            if (ui.repairs != lastRepairs) {
                lastRepairs = ui.repairs
                ui.repairs.forEachIndexed { index, repair ->
                    append(
                        ConsoleEntry(
                            at = elapsed(),
                            kind = ConsoleEntry.Kind.REPAIR,
                            message = repair,
                            repair = ui.repairCodes.getOrNull(index),
                        ),
                    )
                }
            }
        }

        override fun onBusy(pluginId: String, busy: Boolean) {
            _state.update { it.copy(busy = busy) }
        }

        override fun onError(pluginId: String, message: PluginText) {
            // The interpreter's own words already reached the console, whole,
            // through the observer. Only this build's lines are added here.
            if (message is PluginText.Script) return
            append(ConsoleEntry(elapsed(), ConsoleEntry.Kind.ERROR, message = message))
            _state.update { it.copy(status = Status.FAILED) }
        }

        override fun onInputWrite(pluginId: String, inputId: String, text: String) {
            _state.update { it.copy(inputs = it.inputs + (inputId to text.take(MAX_INPUT))) }
        }

        override fun onStopped(pluginId: String, message: PluginText, disabled: Boolean) {
            append(ConsoleEntry(elapsed(), ConsoleEntry.Kind.STOPPED, message = message))
            _state.update { it.copy(status = Status.STOPPED, busy = false) }
        }
    }

    private inner class RuntimeObserver : PluginRuntime.Observer {
        override fun onPrint(pluginId: String, line: String) {
            append(ConsoleEntry(elapsed(), ConsoleEntry.Kind.PRINT, text = line))
        }

        override fun onPhase(pluginId: String, phase: PluginRuntime.Phase, usage: PluginBudget.Usage) {
            _state.update { it.copy(usage = it.usage + (phase to usage)) }
        }

        override fun onFailure(pluginId: String, failure: PluginRuntime.Failure) {
            when (failure.kind) {
                // A run the author replaced. Worth a line, never the new run's failure.
                PluginRuntime.Failure.Kind.CANCELLED ->
                    append(ConsoleEntry(elapsed(), ConsoleEntry.Kind.CANCELLED, failure = failure.kind))

                PluginRuntime.Failure.Kind.COMPILE, PluginRuntime.Failure.Kind.RUNTIME -> {
                    append(
                        ConsoleEntry(
                            at = elapsed(),
                            kind = ConsoleEntry.Kind.ERROR,
                            text = failure.text.take(MAX_ERROR_TEXT),
                            chunk = failure.chunk,
                            line = failure.line,
                            failure = failure.kind,
                        ),
                    )
                    _state.update { it.copy(failure = failure, status = Status.FAILED) }
                }

                // The runtime stops these sessions and says so through onStopped.
                else -> _state.update { it.copy(failure = failure) }
            }
        }

        override fun onEvent(pluginId: String, event: PluginEvent) {
            synchronized(consoleLock) {
                events.addLast(event)
                while (events.size > MAX_EVENTS) events.removeFirst()
            }
        }
    }
}

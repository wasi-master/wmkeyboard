package com.wasimaster.wmkeyboard.ime.todo

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.TodoSettings
import com.wasimaster.wmkeyboard.core.todo.TaskApp
import com.wasimaster.wmkeyboard.core.todo.TodoDue
import com.wasimaster.wmkeyboard.core.todo.TodoDuePreset
import com.wasimaster.wmkeyboard.core.todo.TodoProgress
import com.wasimaster.wmkeyboard.core.todo.TodoProviderSync
import com.wasimaster.wmkeyboard.core.todo.TodoReminders
import com.wasimaster.wmkeyboard.core.todo.TodoStore
import com.wasimaster.wmkeyboard.core.todo.TodoSyncResult
import com.wasimaster.wmkeyboard.core.todo.TodoTask
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What the panel's text box is for. */
enum class TodoTextField {
    /** A new top-level task. Enter adds it and leaves the box open for the next. */
    NEW,

    /** A new step under the task [TodoTextEntry.ref]. */
    NEW_STEP,

    /** The text of task [TodoTextEntry.ref], opened by a long press. */
    EDIT,

    /** What the user tells the AI about task [TodoTextEntry.ref] (#1388). Enter sends it. */
    ASK_AI,
}

/**
 * The text box the panel types into. Lives in `KeyboardUiState`, like
 * `ClockTextEntry`, because the keyboard's shared capture path (caret,
 * glide, suggestions, backspace) works on that state.
 */
data class TodoTextEntry(val field: TodoTextField, val text: String = "", val ref: Long = 0L)

/** One top-level task as the panel lists it, with its steps. */
data class TodoRow(val task: TodoTask, val steps: List<TodoTask>) {
    val progress: TodoProgress get() = TodoProgress(steps.count { it.done }, steps.size)
}

/** One line of the talk with the AI about a task (#1388). */
data class TodoAiMessage(
    val fromUser: Boolean,
    /** What the model reads back next turn: an answer keeps its JSON. */
    val text: String,
    /** What the panel shows: an answer without its JSON. */
    val shown: String = text,
)

/** The parts of a proposal the user can add, each with its own button. */
enum class TodoAiPart { STEPS, REWRITE, CALENDAR, POMODORO }

/**
 * The AI's help with one task (#1388), drawn over the list. The model may
 * only propose: [plan] is its newest proposal, and each part changes the
 * list, the calendar or the clock only when its button is pressed, and only
 * for [taskId].
 */
data class TodoAiUi(
    val taskId: Long,
    val taskText: String,
    val messages: List<TodoAiMessage> = emptyList(),
    val running: Boolean = false,
    /** The answer so far while [running], without its JSON. */
    val partial: String = "",
    val plan: com.wasimaster.wmkeyboard.core.tools.TodoAiPlan? = null,
    /** The parts of [plan] already added. */
    val applied: Set<TodoAiPart> = emptySet(),
    /** A line under the talk: what a button did, or why the AI could not answer. */
    val note: String? = null,
    val failed: Boolean = false,
)

/** What the Undo bar would put back. */
data class TodoUndo(val cleared: Boolean, val tasks: List<TodoTask>)

/** Where the task-app sync stands, for the header's sync button. */
data class TodoSyncUi(
    val enabled: Boolean = false,
    val running: Boolean = false,
    /** The last run could not read or write the task app. */
    val failed: Boolean = false,
)

/**
 * Everything the To-do panel draws, owned by [TodoController] rather than
 * `KeyboardUiState`, for the reason `ClockUi` gives.
 */
data class TodoUi(
    /** The store is attached: false before the first unlock. */
    val available: Boolean = false,
    val rows: List<TodoRow> = emptyList(),
    /** Top-level tasks with their steps open. Kept across visits, like the notes filter. */
    val expanded: Set<Long> = emptySet(),
    val openCount: Int = 0,
    val doneCount: Int = 0,
    val hideDone: Boolean = false,
    val undo: TodoUndo? = null,
    val sync: TodoSyncUi = TodoSyncUi(),
    /** The AI's help with one task, while it is open (#1388). */
    val ai: TodoAiUi? = null,
)

sealed interface TodoAction {
    data object StartNew : TodoAction

    /** The next of [com.wasimaster.wmkeyboard.core.todo.TodoRepeat.OFFERED], then off. */
    data class CycleRepeat(val id: Long) : TodoAction
    data class StartStep(val parentId: Long) : TodoAction
    data class Edit(val id: Long) : TodoAction

    /** The Done chip: whatever the box holds is kept, and the keys go back. */
    data object TextDone : TodoAction
    data object TextCancel : TodoAction
    data class Toggle(val id: Long) : TodoAction
    data class Expand(val id: Long) : TodoAction
    data class Delete(val id: Long) : TodoAction

    /** Types the task's text into the field. */
    data class Insert(val id: Long) : TodoAction

    /** A quick due date for the task being edited (#995); its words are kept too. */
    data class SetDue(val id: Long, val preset: TodoDuePreset) : TodoAction
    data object ClearDone : TodoAction
    data object ToggleHideDone : TodoAction
    data object Undo : TodoAction
    data object UndoDismiss : TodoAction
    data object SyncNow : TodoAction
    data object OpenSettings : TodoAction

    /** Opens the AI's help with task [id] (#1388) and hands the keys to its box. */
    data class AskAi(val id: Long) : TodoAction

    /** The keys back to the AI box, to answer it. */
    data object AiReply : TodoAction

    /** Adds one part of the AI's proposal, which the user has just seen. */
    data class AiApply(val part: TodoAiPart) : TodoAction

    /** Stops the answer being written. */
    data object AiStop : TodoAction

    /** Closes the AI's help; nothing it proposed and was not added is kept. */
    data object AiClose : TodoAction
}

/** What the To-do panel hands back; one bundle, see `ToolHoldCallbacks.todo`. */
data class TodoCallbacks(
    val state: StateFlow<TodoUi> = MutableStateFlow(TodoUi()),
    val onAction: (TodoAction) -> Unit = {},
)

/**
 * The keyboard half of the To-do tool (#920).
 *
 * Holds the panel's [TodoUi] and turns its [TodoAction]s into changes to
 * the [TodoStore] that the settings app shares. Every store change and every
 * task-app sync runs on one worker thread, one after another, so a sync can
 * never read the list halfway through an edit or write over one. Built on
 * first use; nothing here runs until the panel opens.
 */
internal class TodoController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> TodoSettings,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    private val openToolSettings: () -> Unit,
    /** Hands the keys to the panel's text box, with what it should start with. */
    private val beginText: (TodoTextEntry) -> Unit,
    /** Gives the keys back to the field. */
    private val endText: () -> Unit,
    /** What is in the panel's text box right now. */
    private val currentText: () -> TodoTextEntry?,
    /** Everything the AI help reads: the model, Data Saver, the calendar tool's mode (#1388). */
    private val keyboardSettings: () -> com.wasimaster.wmkeyboard.core.settings.KeyboardSettings,
) {

    private val app = context.applicationContext
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1, "wmkb-todo")
    private val _state = MutableStateFlow(TodoUi())
    val state: StateFlow<TodoUi> = _state

    val callbacks = TodoCallbacks(state = state, onAction = ::dispatch)

    private var store: TodoStore? = null
    private var undoJob: Job? = null
    private var syncJob: Job? = null

    /**
     * Retires an answer being written: an on-device generation is a blocking
     * call that runs to its end, so a bumped number, not a cancelled job, is
     * what makes its late text land nowhere.
     */
    @Volatile
    private var aiSeq = 0

    fun onPanelOpen() {
        // Each visit starts from the setting; the header's chip is for this visit.
        _state.update { it.copy(hideDone = settings().hideDone) }
        onWorker {
            val current = store ?: attach()
            current?.reloadIfChanged()
            publish()
        }
        scheduleSync(delayMs = 0)
    }

    fun onPanelClose() {
        if (currentText() != null) endText()
        undoJob?.cancel()
        aiSeq++
        _state.update { it.copy(undo = null, ai = null) }
    }

    /** tasks.json was rewritten under the keyboard: a restore, a sync of settings. */
    fun reload() {
        onWorker {
            store?.reload()
            publish()
        }
    }

    /**
     * Enter in the box. A new task or step is added and the box stays open,
     * empty, for the next one; an edit is kept and the keys go back.
     */
    fun onTextEnter(entry: TodoTextEntry) {
        when (entry.field) {
            TodoTextField.NEW, TodoTextField.NEW_STEP -> {
                if (entry.text.isBlank()) {
                    endText()
                    return
                }
                addFrom(entry)
                beginText(entry.copy(text = ""))
            }
            TodoTextField.EDIT -> {
                keepEdit(entry)
                endText()
            }
            TodoTextField.ASK_AI -> sendToAi(entry)
        }
    }

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun dispatch(action: TodoAction) {
        when (action) {
            TodoAction.StartNew -> beginText(TodoTextEntry(TodoTextField.NEW))
            is TodoAction.CycleRepeat -> {
                haptic()
                change { store ->
                    val offered = com.wasimaster.wmkeyboard.core.todo.TodoRepeat.OFFERED
                    val now = store.item(action.id)?.repeat
                    store.setRepeat(action.id, offered.getOrNull(offered.indexOf(now) + 1))
                }
            }
            is TodoAction.StartStep -> {
                _state.update { it.copy(expanded = it.expanded + action.parentId) }
                beginText(TodoTextEntry(TodoTextField.NEW_STEP, ref = action.parentId))
            }
            is TodoAction.Edit -> {
                val task = rowTask(action.id) ?: return
                haptic()
                beginText(TodoTextEntry(TodoTextField.EDIT, task.text, task.id))
            }
            TodoAction.TextDone -> {
                val entry = currentText()
                if (entry != null) {
                    when (entry.field) {
                        TodoTextField.NEW, TodoTextField.NEW_STEP -> if (entry.text.isNotBlank()) addFrom(entry)
                        TodoTextField.EDIT -> keepEdit(entry)
                        TodoTextField.ASK_AI -> {
                            sendToAi(entry)
                            return
                        }
                    }
                }
                endText()
            }
            TodoAction.TextCancel -> {
                val asking = currentText()?.field == TodoTextField.ASK_AI
                endText()
                // Cancelled before a word was said: there is no talk to go back to.
                if (asking && _state.value.ai?.messages.isNullOrEmpty()) closeAi()
            }
            is TodoAction.Toggle -> {
                haptic()
                change { it.setDone(action.id, !(it.item(action.id)?.done ?: return@change), System.currentTimeMillis()) }
            }
            is TodoAction.Expand -> _state.update {
                it.copy(expanded = if (action.id in it.expanded) it.expanded - action.id else it.expanded + action.id)
            }
            is TodoAction.Delete -> {
                haptic()
                if (currentText()?.ref == action.id) endText()
                change { s -> offerUndo(TodoUndo(cleared = false, tasks = s.delete(action.id))) }
            }
            is TodoAction.Insert -> {
                val task = rowTask(action.id) ?: return
                if (currentText() != null) endText()
                insertText(task.text)
            }
            is TodoAction.SetDue -> {
                haptic()
                val entry = currentText()
                if (entry?.field == TodoTextField.EDIT && entry.ref == action.id) {
                    keepEdit(entry)
                    endText()
                }
                val at = TodoDue.at(action.preset, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                change { it.setDue(action.id, at) }
            }
            TodoAction.ClearDone -> change { s -> offerUndo(TodoUndo(cleared = true, tasks = s.clearDone())) }
            TodoAction.ToggleHideDone -> {
                _state.update { it.copy(hideDone = !it.hideDone) }
                onWorker { publish() }
            }
            TodoAction.Undo -> {
                val undo = _state.value.undo ?: return
                undoJob?.cancel()
                _state.update { it.copy(undo = null) }
                change { it.restore(undo.tasks) }
            }
            TodoAction.UndoDismiss -> {
                undoJob?.cancel()
                _state.update { it.copy(undo = null) }
            }
            TodoAction.SyncNow -> scheduleSync(delayMs = 0)
            TodoAction.OpenSettings -> openToolSettings()
            is TodoAction.AskAi -> openAi(action.id)
            TodoAction.AiReply -> _state.value.ai?.let { beginText(TodoTextEntry(TodoTextField.ASK_AI, ref = it.taskId)) }
            is TodoAction.AiApply -> applyAi(action.part)
            TodoAction.AiStop -> {
                aiSeq++
                _state.update { ui -> ui.copy(ai = ui.ai?.copy(running = false, partial = "")) }
            }
            TodoAction.AiClose -> closeAi()
        }
    }

    // ---- the AI's help with one task (#1388) ------------------------------

    /**
     * Opens the help on a top-level task: a step is planned as part of its
     * task. The box takes the keys at once, so the user can say or type what
     * the task involves; the strip's microphone dictates into it.
     */
    private fun openAi(id: Long) {
        val row = _state.value.rows.firstOrNull { it.task.id == id }
            ?: _state.value.rows.firstOrNull { r -> r.steps.any { it.id == id } }
            ?: return
        haptic()
        if (currentText() != null) endText()
        aiSeq++
        _state.update { it.copy(ai = TodoAiUi(taskId = row.task.id, taskText = row.task.text)) }
        beginText(TodoTextEntry(TodoTextField.ASK_AI, ref = row.task.id))
    }

    private fun closeAi() {
        aiSeq++
        if (currentText()?.field == TodoTextField.ASK_AI) endText()
        _state.update { it.copy(ai = null) }
    }

    /**
     * Sends the box's words, and gives the keys back so the answer has the
     * panel's height. The model sees this task, its steps and the talk so
     * far; nothing about any other task goes in, and nothing it writes
     * changes the list.
     */
    private fun sendToAi(entry: TodoTextEntry) {
        val ai = _state.value.ai ?: return
        val words = entry.text.trim()
        endText()
        if (words.isEmpty() || ai.running) return
        val messages = ai.messages + TodoAiMessage(fromUser = true, text = words)
        val seq = ++aiSeq
        _state.update { it.copy(ai = ai.copy(messages = messages, running = true, partial = "", note = null, failed = false)) }
        scope.launch(Dispatchers.IO) {
            val steps = kotlinx.coroutines.withContext(worker) {
                (store ?: attach())?.subtasks(ai.taskId).orEmpty().map { it.text }
            }
            val system = com.wasimaster.wmkeyboard.core.tools.TodoAiPlans.system(
                ai.taskText,
                steps,
                com.wasimaster.wmkeyboard.core.tools.TodoAiPlans.today(System.currentTimeMillis()),
            )
            val turns = messages.map {
                com.wasimaster.wmkeyboard.core.tools.AiClient.ChatTurn(
                    if (it.fromUser) {
                        com.wasimaster.wmkeyboard.core.tools.AiClient.ChatRole.USER
                    } else {
                        com.wasimaster.wmkeyboard.core.tools.AiClient.ChatRole.ASSISTANT
                    },
                    it.text,
                )
            }
            var lastPartialAt = 0L
            val result = runCatching {
                com.wasimaster.wmkeyboard.ime.aichat.AiChatController.completeOnce(app, keyboardSettings(), system, turns) { raw ->
                    val now = System.currentTimeMillis()
                    if (seq == aiSeq && now - lastPartialAt >= AI_PARTIAL_MS) {
                        lastPartialAt = now
                        val shown = com.wasimaster.wmkeyboard.core.tools.TodoAiPlans.visible(raw)
                        _state.update { ui -> if (seq == aiSeq) ui.copy(ai = ui.ai?.copy(partial = shown)) else ui }
                    }
                }
            }
            if (seq != aiSeq) return@launch
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            _state.update { ui ->
                val now = ui.ai ?: return@update ui
                result.fold(
                    onSuccess = { raw ->
                        val plan = com.wasimaster.wmkeyboard.core.tools.TodoAiPlans.parse(raw, now.taskText)
                        val text = com.wasimaster.wmkeyboard.core.tools.AiThinking.stripped(raw)
                        val fresh = plan.takeIf { it.proposes }
                        ui.copy(
                            ai = now.copy(
                                messages = now.messages + TodoAiMessage(fromUser = false, text = text, shown = plan.reply),
                                running = false,
                                partial = "",
                                plan = fresh ?: now.plan,
                                applied = if (fresh != null) emptySet() else now.applied,
                            ),
                        )
                    },
                    onFailure = { failure ->
                        ui.copy(
                            ai = now.copy(
                                running = false,
                                partial = "",
                                note = failure.message?.takeIf { it.isNotBlank() } ?: app.getString(com.wasimaster.wmkeyboard.ime.R.string.ime_todo_ai_failed),
                                failed = true,
                            ),
                        )
                    },
                )
            }
        }
    }

    /**
     * One part of the proposal, now that the user pressed its button. Steps
     * and the new wording go to this task only; the calendar goes through the
     * AI's own calendar tool, the way the user set it up (the calendar app's
     * editor, or straight in); the Pomodoro is the clock's, on the user's own
     * rhythm, labelled with the first step.
     */
    private fun applyAi(part: TodoAiPart) {
        val ai = _state.value.ai ?: return
        val plan = ai.plan ?: return
        if (part in ai.applied) return
        haptic()
        val taskId = ai.taskId
        when (part) {
            TodoAiPart.STEPS -> {
                if (plan.steps.isEmpty()) return
                change { s -> for (step in plan.steps) s.add(stepText(step), parentId = taskId) }
                _state.update { it.copy(expanded = it.expanded + taskId) }
                markApplied(part, app.resources.getQuantityString(com.wasimaster.wmkeyboard.ime.R.plurals.ime_todo_ai_steps_added, plan.steps.size, plan.steps.size))
            }
            TodoAiPart.REWRITE -> {
                val words = plan.rewrite ?: return
                change { it.setText(taskId, words) }
                _state.update { ui -> ui.copy(ai = ui.ai?.copy(taskText = words)) }
                markApplied(part, app.getString(com.wasimaster.wmkeyboard.ime.R.string.ime_todo_ai_rewrite_done))
            }
            TodoAiPart.CALENDAR -> scope.launch(Dispatchers.IO) {
                val (line, ok) = calendarFor(plan, ai.taskText)
                // A failed booking is not marked as added: the button stays, to try again.
                markApplied(part, line, failed = !ok)
            }
            TodoAiPart.POMODORO -> {
                val label = plan.steps.firstOrNull()?.text ?: ai.taskText
                val rhythm = com.wasimaster.wmkeyboard.core.clock.ClockPrefs.get(app).pomodoro
                scope.launch(Dispatchers.IO) {
                    com.wasimaster.wmkeyboard.core.clock.ClockEngine.update(app) {
                        com.wasimaster.wmkeyboard.core.clock.ClockActions.startPomodoro(it, rhythm, label, System.currentTimeMillis())
                    }
                }
                markApplied(part, app.getString(com.wasimaster.wmkeyboard.ime.R.string.ime_todo_ai_pomodoro_done, rhythm.focusMinutes, label))
            }
        }
    }

    private fun markApplied(part: TodoAiPart, note: String, failed: Boolean = false) {
        _state.update { ui ->
            val ai = ui.ai ?: return@update ui
            ui.copy(ai = ai.copy(applied = if (failed) ai.applied else ai.applied + part, note = note, failed = failed))
        }
    }

    /** A step's words with its estimate after them, so the list keeps what the plan said. */
    private fun stepText(step: com.wasimaster.wmkeyboard.core.tools.TodoAiStep): String {
        val minutes = step.minutes ?: return step.text
        return app.getString(com.wasimaster.wmkeyboard.ime.R.string.ime_todo_ai_step_with_minutes, step.text, minutes)
    }

    /**
     * Books the plan through the `add_calendar_event` tool. Written straight
     * into the calendar, each step is an event of its own, back to back; in
     * the calendar app's editor, which saves one event at a time, the whole
     * task is one event with its steps in the notes. Returns the line to show,
     * and whether it went in.
     */
    private fun calendarFor(plan: com.wasimaster.wmkeyboard.core.tools.TodoAiPlan, task: String): Pair<String, Boolean> {
        val plans = com.wasimaster.wmkeyboard.core.tools.TodoAiPlans
        val settings = keyboardSettings()
        // The user pressed the button over the plan they read: that is the ask.
        val runner = com.wasimaster.wmkeyboard.core.tools.AiToolRunner(
            app, settings, approver = { com.wasimaster.wmkeyboard.core.tools.AiToolApproval.ONCE },
        )
        val now = System.currentTimeMillis()
        val start = plan.start?.let { com.wasimaster.wmkeyboard.core.tools.AiCalendarArgs.span(it, null)?.begin }
            ?.takeIf { it > now - HOUR_MS } ?: plans.nextQuarter(now)
        val steps = plan.steps.ifEmpty { listOf(com.wasimaster.wmkeyboard.core.tools.TodoAiStep(task, null)) }
        val direct = settings.ai.toolCalendarMode == com.wasimaster.wmkeyboard.core.tools.AiCalendarMode.DIRECT
        val calls = if (direct) {
            plans.schedule(steps, start).mapIndexed { i, (begin, end) ->
                calendarCall(i, steps[i].text, begin, end, notes = task)
            }
        } else {
            val end = start + steps.sumOf { it.minutes ?: plans.DEFAULT_STEP_MINUTES } * 60_000L
            val notes = if (plan.steps.isEmpty()) null else plan.steps.joinToString("\n") { "• " + stepText(it) }
            listOf(calendarCall(0, task, start, end, notes))
        }
        var failure: String? = null
        for (call in calls) {
            val result = runner.run(call)
            if (result.error.isNotEmpty()) {
                failure = result.error
                break
            }
        }
        val line = when {
            failure != null -> app.getString(com.wasimaster.wmkeyboard.ime.R.string.ime_todo_ai_calendar_failed, failure)
            direct -> app.resources.getQuantityString(com.wasimaster.wmkeyboard.ime.R.plurals.ime_todo_ai_calendar_added, calls.size, calls.size)
            else -> app.getString(com.wasimaster.wmkeyboard.ime.R.string.ime_todo_ai_calendar_opened)
        }
        return line to (failure == null)
    }

    private fun calendarCall(index: Int, title: String, begin: Long, end: Long, notes: String?): com.wasimaster.wmkeyboard.core.tools.AiToolCall {
        val plans = com.wasimaster.wmkeyboard.core.tools.TodoAiPlans
        val args = kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive(title))
            put("start", kotlinx.serialization.json.JsonPrimitive(plans.moment(begin)))
            put("end", kotlinx.serialization.json.JsonPrimitive(plans.moment(end)))
            if (notes != null) put("notes", kotlinx.serialization.json.JsonPrimitive(notes))
        }
        return com.wasimaster.wmkeyboard.core.tools.AiToolCall(
            id = "todo_plan_$index",
            name = com.wasimaster.wmkeyboard.core.tools.AiTools.ADD_CALENDAR_EVENT,
            arguments = args.toString(),
        )
    }

    // ---- the store, on the worker ----------------------------------------

    private fun attach(): TodoStore? {
        if (!DirectBoot.isUserUnlocked(app)) return null
        return TodoStore(File(app.filesDir, TodoStore.FILE_PATH)).also { store = it }
    }

    /** Runs [block] on the store, saves, redraws, and asks for a sync soon. */
    private fun change(block: (TodoStore) -> Unit) {
        onWorker {
            val s = store ?: attach() ?: return@onWorker
            block(s)
            s.save()
            publish()
            // A tick, a date or a delete moves the next reminder (#995).
            TodoReminders.rearm(app)
        }
        scheduleSync(delayMs = SYNC_AFTER_EDIT_MS)
    }

    private fun addFrom(entry: TodoTextEntry) {
        val parent = entry.ref.takeIf { entry.field == TodoTextField.NEW_STEP }
        change { it.add(entry.text, parentId = parent) }
    }

    private fun keepEdit(entry: TodoTextEntry) {
        // Blank keeps the old text: a delete is its own button, not an empty box.
        if (entry.text.isBlank()) return
        change { it.setText(entry.ref, entry.text) }
    }

    private fun offerUndo(undo: TodoUndo) {
        if (undo.tasks.isEmpty()) return
        _state.update { it.copy(undo = undo) }
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(UNDO_MS)
            _state.update { if (it.undo === undo) it.copy(undo = null) else it }
        }
    }

    /** Rebuilds the list the panel draws. Worker only. */
    private fun publish() {
        val s = store
        val hide = _state.value.hideDone
        if (s == null) {
            _state.update { it.copy(available = false, rows = emptyList(), openCount = 0, doneCount = 0) }
            return
        }
        val rows = s.topLevel(hideDone = hide).map { TodoRow(it, s.subtasks(it.id)) }
        val all = s.topLevel()
        _state.update { ui ->
            ui.copy(
                available = true,
                rows = rows,
                openCount = all.count { !it.done },
                doneCount = all.count { it.done },
                expanded = ui.expanded.filterTo(HashSet()) { id -> rows.any { it.task.id == id } },
                sync = ui.sync.copy(enabled = settings().syncReady),
            )
        }
    }

    private fun rowTask(id: Long): TodoTask? = _state.value.rows.firstNotNullOfOrNull { row ->
        if (row.task.id == id) row.task else row.steps.firstOrNull { it.id == id }
    }

    private fun onWorker(block: suspend () -> Unit) {
        scope.launch(worker) { block() }
    }

    // ---- the task app ----------------------------------------------------

    /**
     * A sync a moment after the last edit, so a run of ticks is one sync,
     * or straight away for the panel opening and the sync button.
     */
    private fun scheduleSync(delayMs: Long) {
        val config = settings()
        if (!config.syncReady) {
            _state.update { it.copy(sync = TodoSyncUi()) }
            return
        }
        val taskApp = TaskApp.of(config.syncAuthority) ?: return
        syncJob?.cancel()
        syncJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            _state.update { it.copy(sync = it.sync.copy(enabled = true, running = true)) }
            var result: TodoSyncResult? = null
            kotlinx.coroutines.withContext(worker) {
                val s = store ?: attach() ?: return@withContext
                result = TodoProviderSync.sync(app, s, taskApp, config.syncListId)
                publish()
                // A task app's dates remind too.
                TodoReminders.rearm(app)
            }
            _state.update { it.copy(sync = TodoSyncUi(enabled = true, running = false, failed = result !is TodoSyncResult.Done)) }
        }
    }

    companion object {
        private const val UNDO_MS = 6_000L
        private const val SYNC_AFTER_EDIT_MS = 2_500L
        private const val AI_PARTIAL_MS = 120L
        private const val HOUR_MS = 3_600_000L
    }
}

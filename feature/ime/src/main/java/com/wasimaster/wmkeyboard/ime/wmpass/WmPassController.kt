package com.wasimaster.wmkeyboard.ime.wmpass

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the WM Pass tool's text box is for. */
enum class WmPassTextField(val secret: Boolean = false) {
    /** The search over the vault: it narrows the list as it is typed. */
    SEARCH,

    /** A new login's name, username, password and website. */
    NAME,
    USERNAME,
    PASSWORD(secret = true),
    WEBSITE,

    /** The new password for the item that is open. */
    NEW_PASSWORD(secret = true),
}

/**
 * The text box the tool types into. Lives in `KeyboardUiState`, like
 * `TodoTextEntry`, because the keyboard's shared capture path (caret,
 * backspace, the strip) works on that state.
 */
data class WmPassTextEntry(val field: WmPassTextField, val text: String = "")

/** Which face the tool shows. */
enum class WmPassPage { LIST, ITEM, ADD, CHANGE }

/** A login being added, held here between its boxes and forgotten when the tool closes. */
data class WmPassDraft(
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val website: String = "",
) {
    fun of(field: WmPassTextField): String = when (field) {
        WmPassTextField.NAME -> name
        WmPassTextField.USERNAME -> username
        WmPassTextField.PASSWORD -> password
        WmPassTextField.WEBSITE -> website
        else -> ""
    }

    fun with(field: WmPassTextField, text: String): WmPassDraft = when (field) {
        WmPassTextField.NAME -> copy(name = text)
        WmPassTextField.USERNAME -> copy(username = text)
        WmPassTextField.PASSWORD -> copy(password = text)
        WmPassTextField.WEBSITE -> copy(website = text)
        else -> this
    }

    val blank: Boolean get() = name.isBlank() && username.isBlank() && password.isEmpty() && website.isBlank()
}

/** A line the tool shows for a moment after something it was asked to do. */
enum class WmPassNotice { COPIED, COPY_FAILED, TYPE_FAILED, SAVED, SAVE_FAILED, CHANGED, CHANGE_FAILED, NO_PASSWORD, GONE }

/** Everything the WM Pass panel draws, owned by [WmPassController]. */
data class WmPassUi(
    /** Null until WM Pass first answers. */
    val status: WmPassBridge.Status? = null,
    val rows: List<VaultWire.Row> = emptyList(),
    val query: String = "",
    val page: WmPassPage = WmPassPage.LIST,
    /** The item opened, with its secrets named but not held. */
    val item: VaultWire.Page? = null,
    val draft: WmPassDraft = WmPassDraft(),
    val newPassword: String = "",
    /** Whether a password being typed shows itself rather than dots. */
    val showPassword: Boolean = false,
    /** A save is on its way to WM Pass. */
    val busy: Boolean = false,
    val notice: WmPassNotice? = null,
)

sealed interface WmPassAction {
    data object Refresh : WmPassAction
    data object OpenApp : WmPassAction
    data object StartSearch : WmPassAction
    data object ClearSearch : WmPassAction
    data class Open(val id: String) : WmPassAction

    /** Back a face: an item, a new login or a new password to the list or the item. */
    data object Back : WmPassAction

    /** Types one field of the item [id], by its key on the item's page. */
    data class Type(val id: String, val key: String) : WmPassAction

    /** Has WM Pass copy one field of the item [id]. */
    data class Copy(val id: String, val key: String) : WmPassAction
    data object OpenInPass : WmPassAction
    data object StartAdd : WmPassAction
    data object StartChange : WmPassAction

    /** Hands the keys to one box of the form. */
    data class Edit(val field: WmPassTextField) : WmPassAction

    /** The Done chip: the box keeps what it holds, and the keys go back. */
    data object TextDone : WmPassAction
    data object Generate : WmPassAction
    data object ToggleShow : WmPassAction
    data object Save : WmPassAction
}

/** What the WM Pass panel hands back; one bundle, see `ToolHoldCallbacks.wmPass`. */
data class WmPassCallbacks(
    val state: StateFlow<WmPassUi> = MutableStateFlow(WmPassUi()),
    val onAction: (WmPassAction) -> Unit = {},
)

/**
 * The keyboard half of the WM Pass tool: a password book that is WM Pass's
 * vault, never a second one (#1077).
 *
 * It lists and searches the vault, opens an item, types or copies one field,
 * adds a login and changes a password, each by asking WM Pass through
 * [WmPassBridge]. Nothing is kept: a secret comes over only on the tap that
 * types it and goes straight to the field, a copy is made by WM Pass itself,
 * and the form's words are dropped when the tool closes. It unlocks nothing;
 * a locked vault gets "Open WM Pass". It types and copies nothing into a
 * field that asks the keyboard not to learn.
 */
internal class WmPassController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The field asks not to be learned from, as a private browser tab does. */
    private val incognito: () -> Boolean,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    /** Hands the keys to the panel's text box, with what it should start with. */
    private val beginText: (WmPassTextEntry) -> Unit,
    /** Gives the keys back to the field. */
    private val endText: () -> Unit,
    /** What is in the panel's text box right now. */
    private val currentText: () -> WmPassTextEntry?,
) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(WmPassUi())
    val state: StateFlow<WmPassUi> = _state

    val callbacks = WmPassCallbacks(state = state, onAction = ::dispatch)

    private var listJob: Job? = null
    private var noticeJob: Job? = null

    fun onPanelOpen() {
        _state.update { it.copy(page = WmPassPage.LIST, item = null, notice = null) }
        refresh(delayMs = 0)
    }

    /** Leaving the tool forgets the search, the item and every word of the form. */
    fun onPanelClose() {
        if (currentText() != null) endText()
        listJob?.cancel()
        noticeJob?.cancel()
        _state.update { WmPassUi(status = it.status) }
    }

    /** The box changed: a search narrows the list, a form keeps its words. */
    fun onText(entry: WmPassTextEntry) {
        when (entry.field) {
            WmPassTextField.SEARCH -> {
                _state.update { it.copy(query = entry.text) }
                refresh(delayMs = SEARCH_PAUSE_MS)
            }
            WmPassTextField.NEW_PASSWORD -> _state.update { it.copy(newPassword = entry.text) }
            else -> _state.update { it.copy(draft = it.draft.with(entry.field, entry.text)) }
        }
    }

    /** Enter in the box: the form moves on to its next box; anything else hands the keys back. */
    fun onTextEnter(entry: WmPassTextEntry) {
        onText(entry)
        val next = when (entry.field) {
            WmPassTextField.NAME -> WmPassTextField.USERNAME
            WmPassTextField.USERNAME -> WmPassTextField.PASSWORD
            WmPassTextField.PASSWORD -> WmPassTextField.WEBSITE
            else -> null
        }
        if (next != null) beginText(WmPassTextEntry(next, _state.value.draft.of(next))) else endText()
    }

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun dispatch(action: WmPassAction) {
        when (action) {
            WmPassAction.Refresh -> refresh(delayMs = 0)
            WmPassAction.OpenApp -> WmPassBridge.openApp(app)
            WmPassAction.StartSearch -> beginText(WmPassTextEntry(WmPassTextField.SEARCH, _state.value.query))
            WmPassAction.ClearSearch -> {
                if (currentText()?.field == WmPassTextField.SEARCH) endText()
                _state.update { it.copy(query = "") }
                refresh(delayMs = 0)
            }
            is WmPassAction.Open -> open(action.id)
            WmPassAction.Back -> back()
            is WmPassAction.Type -> type(action.id, action.key)
            is WmPassAction.Copy -> copy(action.id, action.key)
            WmPassAction.OpenInPass -> _state.value.item?.let { WmPassBridge.openItem(app, it.id) }
            WmPassAction.StartAdd -> {
                _state.update { it.copy(page = WmPassPage.ADD, draft = WmPassDraft(), showPassword = false, notice = null) }
                beginText(WmPassTextEntry(WmPassTextField.NAME))
            }
            WmPassAction.StartChange -> {
                if (_state.value.item?.changes == null) return
                _state.update { it.copy(page = WmPassPage.CHANGE, newPassword = "", showPassword = false, notice = null) }
                beginText(WmPassTextEntry(WmPassTextField.NEW_PASSWORD))
            }
            is WmPassAction.Edit -> {
                val ui = _state.value
                val text = if (action.field == WmPassTextField.NEW_PASSWORD) ui.newPassword else ui.draft.of(action.field)
                beginText(WmPassTextEntry(action.field, text))
            }
            WmPassAction.TextDone -> {
                currentText()?.let(::onText)
                endText()
            }
            WmPassAction.Generate -> generate()
            WmPassAction.ToggleShow -> _state.update { it.copy(showPassword = !it.showPassword) }
            WmPassAction.Save -> save()
        }
    }

    private fun refresh(delayMs: Long) {
        listJob?.cancel()
        listJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val query = _state.value.query
            val listing = withContext(Dispatchers.IO) { WmPassBridge.items(app, query.trim()) }
            _state.update { it.copy(status = listing.status, rows = listing.rows) }
        }
    }

    private fun open(id: String) {
        if (currentText()?.field == WmPassTextField.SEARCH) endText()
        scope.launch {
            val page = withContext(Dispatchers.IO) { WmPassBridge.item(app, id) }
            if (page == null) {
                // Gone, or the vault locked since the list was drawn.
                say(WmPassNotice.GONE)
                refresh(delayMs = 0)
            } else {
                haptic()
                _state.update { it.copy(page = WmPassPage.ITEM, item = page, notice = null) }
            }
        }
    }

    private fun back() {
        if (currentText() != null) endText()
        _state.update {
            when (it.page) {
                WmPassPage.CHANGE -> it.copy(page = WmPassPage.ITEM, newPassword = "", showPassword = false)
                else -> it.copy(page = WmPassPage.LIST, item = null, draft = WmPassDraft(), showPassword = false)
            }
        }
        if (_state.value.page == WmPassPage.LIST) refresh(delayMs = 0)
    }

    /**
     * Types one field: the tap that opened the tool and this one are the two
     * the field needs. A plain value already on the page is typed as it is;
     * a secret is asked for now and goes straight to the field.
     */
    private fun type(id: String, key: String) {
        if (incognito()) return
        if (currentText() != null) endText()
        val shown = _state.value.item?.takeIf { it.id == id }?.fields?.firstOrNull { it.key == key && !it.secret }?.value
        if (shown != null) {
            insertText(shown)
            return
        }
        scope.launch {
            val value = withContext(Dispatchers.IO) { WmPassBridge.value(app, id, key) }
            if (value != null) {
                insertText(value)
            } else {
                say(WmPassNotice.TYPE_FAILED)
                refresh(delayMs = 0)
            }
        }
    }

    private fun copy(id: String, key: String) {
        if (incognito()) return
        scope.launch {
            val copied = withContext(Dispatchers.IO) { WmPassBridge.copy(app, id, key) }
            if (copied) haptic()
            say(if (copied) WmPassNotice.COPIED else WmPassNotice.COPY_FAILED)
            if (!copied) refresh(delayMs = 0)
        }
    }

    private fun generate() {
        scope.launch {
            val made = withContext(Dispatchers.IO) { WmPassBridge.generate(app) }
            if (made == null) {
                say(WmPassNotice.NO_PASSWORD)
                return@launch
            }
            haptic()
            val field = if (_state.value.page == WmPassPage.CHANGE) WmPassTextField.NEW_PASSWORD else WmPassTextField.PASSWORD
            _state.update {
                if (field == WmPassTextField.NEW_PASSWORD) {
                    it.copy(newPassword = made, showPassword = true)
                } else {
                    it.copy(draft = it.draft.copy(password = made), showPassword = true)
                }
            }
            // The box being typed in takes it too, or its next keystroke would put the old words back.
            if (currentText()?.field == field) beginText(WmPassTextEntry(field, made))
        }
    }

    private fun save() {
        val ui = _state.value
        if (ui.busy) return
        currentText()?.let(::onText)
        val now = _state.value
        when (now.page) {
            WmPassPage.ADD -> {
                val draft = now.draft
                if (draft.blank) return
                _state.update { it.copy(busy = true) }
                scope.launch {
                    val id = withContext(Dispatchers.IO) {
                        WmPassBridge.addLogin(app, draft.name.trim(), draft.username.trim(), draft.password, draft.website.trim())
                    }
                    _state.update { it.copy(busy = false) }
                    if (id == null) {
                        say(WmPassNotice.SAVE_FAILED)
                        return@launch
                    }
                    haptic()
                    if (currentText() != null) endText()
                    _state.update { it.copy(page = WmPassPage.LIST, draft = WmPassDraft(), showPassword = false) }
                    say(WmPassNotice.SAVED)
                    refresh(delayMs = 0)
                }
            }
            WmPassPage.CHANGE -> {
                val item = now.item ?: return
                val password = now.newPassword
                if (password.isEmpty()) return
                _state.update { it.copy(busy = true) }
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { WmPassBridge.setPassword(app, item.id, password) }
                    _state.update { it.copy(busy = false) }
                    if (!saved) {
                        say(WmPassNotice.CHANGE_FAILED)
                        return@launch
                    }
                    haptic()
                    if (currentText() != null) endText()
                    _state.update { it.copy(page = WmPassPage.ITEM, newPassword = "", showPassword = false) }
                    say(WmPassNotice.CHANGED)
                }
            }
            else -> Unit
        }
    }

    private fun say(notice: WmPassNotice) {
        _state.update { it.copy(notice = notice) }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _state.update { if (it.notice == notice) it.copy(notice = null) else it }
        }
    }

    companion object {
        private const val SEARCH_PAUSE_MS = 180L
        private const val NOTICE_MS = 3_000L

        /** The longest a box holds: a password, a name, an address. */
        const val TEXT_MAX = 512
    }
}

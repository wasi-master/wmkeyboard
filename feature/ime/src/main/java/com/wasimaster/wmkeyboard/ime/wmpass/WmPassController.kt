package com.wasimaster.wmkeyboard.ime.wmpass

import android.content.Context
import android.os.SystemClock
import android.text.InputType
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

    /** The master password, typed here to open a locked vault. */
    MASTER(secret = true),
}

/**
 * The text box the tool types into. Lives in `KeyboardUiState`, like
 * `TodoTextEntry`, because the keyboard's shared capture path (caret,
 * backspace, the strip) works on that state.
 */
data class WmPassTextEntry(val field: WmPassTextField, val text: String = "")

/** Which face the tool shows. */
enum class WmPassPage { LIST, ITEM, ADD, CHANGE, GENERATE }

/** The chips over the list, which narrow it without a search. */
enum class WmPassFilter { ALL, FOR_APP, FAVOURITES, LOGINS, CODES, CARDS, NOTES, OTHER }

/** Whether [row] belongs under [filter]. */
fun WmPassFilter.admits(row: VaultWire.Row): Boolean = when (this) {
    WmPassFilter.ALL -> true
    WmPassFilter.FOR_APP -> row.matches
    WmPassFilter.FAVOURITES -> row.favourite
    WmPassFilter.LOGINS -> row.kind == "login"
    WmPassFilter.CODES -> row.hasCode
    WmPassFilter.CARDS -> row.kind == "card"
    WmPassFilter.NOTES -> row.kind == "note"
    WmPassFilter.OTHER -> row.kind != "login" && row.kind != "card" && row.kind != "note"
}

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
enum class WmPassNotice(val failure: Boolean = false) {
    COPIED,
    COPY_FAILED(failure = true),
    TYPE_FAILED(failure = true),
    SAVED,
    SAVE_FAILED(failure = true),
    CHANGED,
    CHANGE_FAILED(failure = true),
    NO_PASSWORD(failure = true),
    GONE(failure = true),
    UNLOCKED,
    LOCKED,
    PROMPT_FAILED(failure = true),
    NEEDS_DOOR(failure = true),
    UNLOCK_FAILED(failure = true),
    FILL_NEXT,
    NO_USERNAME(failure = true),
}

/** A secret shown for a few seconds on an item's page: one field's value, gone at [until]. */
data class WmPassPeek(val id: String, val key: String, val value: String, val until: Long)

/** Everything the WM Pass panel draws, owned by [WmPassController]. */
data class WmPassUi(
    /** Null until WM Pass first answers. */
    val status: WmPassBridge.Status? = null,
    val rows: List<VaultWire.Row> = emptyList(),
    val query: String = "",
    val filter: WmPassFilter = WmPassFilter.ALL,
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
    /** The field is in WM Pass itself, so Open in WM Pass would go nowhere. */
    val inPass: Boolean = false,
    /** The open vault's name. */
    val vault: String = "",
    /** Locked: whether WM Pass's fingerprint prompt can open it. */
    val fingerprint: Boolean = false,
    /** Locked after too many wrong passwords: when the next may be tried, by the wall clock; 0 for now. */
    val retryAt: Long = 0,
    /** A master password is on its way to WM Pass. */
    val unlocking: Boolean = false,
    /** Wrong passwords typed here since the tool opened; each one shakes the lock. */
    val wrongTries: Int = 0,
    /** When the vault last opened while the tool watched, by [SystemClock.elapsedRealtime]; 0 for never. */
    val openedAt: Long = 0,
    /** A secret shown on the item's page for a moment. */
    val peek: WmPassPeek? = null,
    /** The generator's latest password, on its own page. */
    val generated: String = "",
    /** The name of the app being typed in, for "Saved for …". */
    val appName: String = "",
)

sealed interface WmPassAction {
    data object Refresh : WmPassAction
    data object OpenApp : WmPassAction

    /** WM Pass is not installed: its store page. */
    data object GetApp : WmPassAction

    /** WM Pass's fingerprint prompt over the app in front, or its door when there is no fingerprint. */
    data object UnlockWithFingerprint : WmPassAction

    /** Hands the keys to the master password box. */
    data object StartPassword : WmPassAction

    /** Sends what the master password box holds. */
    data object Unlock : WmPassAction
    data object Lock : WmPassAction
    data object StartSearch : WmPassAction
    data object ClearSearch : WmPassAction
    data class Filter(val filter: WmPassFilter) : WmPassAction
    data class Open(val id: String) : WmPassAction

    /** Back a face: an item, a new login or a new password to the list or the item. */
    data object Back : WmPassAction

    /** Types one field of the item [id], by its key on the item's page. */
    data class Type(val id: String, val key: String) : WmPassAction

    /** Has WM Pass copy one field of the item [id]. */
    data class Copy(val id: String, val key: String) : WmPassAction

    /** Shows a secret field of the item [id] for a few seconds, or hides it. */
    data class Peek(val id: String, val key: String) : WmPassAction

    /** The login [id] into the form: its username here, its password in the next box. */
    data class Fill(val id: String) : WmPassAction
    data object OpenInPass : WmPassAction
    data object StartAdd : WmPassAction
    data object StartChange : WmPassAction

    /** The generator's own page. */
    data object StartGenerate : WmPassAction
    data object TypeGenerated : WmPassAction

    /** The generated password into a new login. */
    data object SaveGenerated : WmPassAction

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

/** Whether a field of [inputType] takes a password, the only kind a fill types one into unasked. */
internal fun isPasswordInput(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

/**
 * The keyboard half of the WM Pass tool: a password book that is WM Pass's
 * vault, never a second one (#1077).
 *
 * It lists and searches the vault, opens an item, types, copies or briefly
 * shows one field, fills a login's username and then its password, adds a
 * login, changes a password and makes one, each by asking WM Pass through
 * [WmPassBridge]. A locked vault opens without leaving the app: WM Pass's own
 * fingerprint prompt comes up over it, or the master password goes into the
 * panel's box and on to WM Pass. Nothing is kept: a secret comes over only on
 * the tap that types or shows it, a copy is made by WM Pass itself, and the
 * form's words and the master password are dropped as soon as they are used
 * or the tool closes. It types and copies nothing into a field that asks the
 * keyboard not to learn.
 */
@Suppress("detekt.TooManyFunctions", "detekt.LargeClass")
internal class WmPassController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The field asks not to be learned from, as a private browser tab does. */
    private val incognito: () -> Boolean,
    /** The app the field belongs to. */
    private val fieldPackage: () -> String?,
    /** The field's input type, to tell a password box. */
    private val fieldInputType: () -> Int,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    /** Hands the keys to the panel's text box, with what it should start with. */
    private val beginText: (WmPassTextEntry) -> Unit,
    /** Gives the keys back to the field. */
    private val endText: () -> Unit,
    /** What is in the panel's text box right now. */
    private val currentText: () -> WmPassTextEntry?,
    /** Moves on to the form's next box, as the field's Next or a Tab does. */
    private val nextField: () -> Unit,
    /** Opens the panel again, unless it is open. */
    private val reopenPanel: () -> Unit,
) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(WmPassUi())
    val state: StateFlow<WmPassUi> = _state

    val callbacks = WmPassCallbacks(state = state, onAction = ::dispatch)

    private var listJob: Job? = null
    private var noticeJob: Job? = null
    private var peekJob: Job? = null

    /** The fingerprint prompt was started from the app [unlockFor] at [unlockAskedAt]. */
    private var unlockAskedAt = 0L
    private var unlockFor: String? = null

    /** A fill typed the username and waits for the password box. */
    private class PendingFill(val id: String, val app: String?, val at: Long)

    private var pendingFill: PendingFill? = null

    fun onPanelOpen() {
        _state.update { it.copy(page = WmPassPage.LIST, item = null, notice = null, peek = null, filter = WmPassFilter.ALL) }
        refresh(delayMs = 0)
    }

    /** Leaving the tool forgets the search, the item, a shown secret and every word of the form. */
    fun onPanelClose() {
        if (currentText() != null) endText()
        listJob?.cancel()
        noticeJob?.cancel()
        peekJob?.cancel()
        _state.update { WmPassUi(status = it.status, vault = it.vault, fingerprint = it.fingerprint, retryAt = it.retryAt) }
    }

    /**
     * A field has the keys again: the app came back from WM Pass's fingerprint
     * prompt, or a fill moved on to the password box.
     */
    fun onKeyboardShown() {
        val now = SystemClock.elapsedRealtime()
        val here = fieldPackage()
        pendingFill?.let { fill ->
            when {
                now - fill.at > FILL_WAIT_MS -> pendingFill = null
                // Only into a password box of the same app, which is where the login was being filled.
                here == fill.app && isPasswordInput(fieldInputType()) -> {
                    pendingFill = null
                    typeSecret(fill.id, VaultWire.PASSWORD)
                }
            }
        }
        if (unlockAskedAt != 0L) {
            when {
                now - unlockAskedAt > UNLOCK_RETURN_MS -> unlockAskedAt = 0L
                // WM Pass's door has a field of its own; the tool waits for the app it was asked from.
                here == unlockFor -> {
                    unlockAskedAt = 0L
                    reopenPanel()
                    refresh(delayMs = 0)
                }
            }
        }
    }

    /** The box changed: a search narrows the list, a form keeps its words. */
    fun onText(entry: WmPassTextEntry) {
        when (entry.field) {
            WmPassTextField.SEARCH -> {
                _state.update { it.copy(query = entry.text) }
                refresh(delayMs = SEARCH_PAUSE_MS)
            }
            WmPassTextField.NEW_PASSWORD -> _state.update { it.copy(newPassword = entry.text) }
            // The master password lives only in the box, and goes with it.
            WmPassTextField.MASTER -> Unit
            else -> _state.update { it.copy(draft = it.draft.with(entry.field, entry.text)) }
        }
    }

    /** Enter in the box: the form moves on to its next box, the master password is sent; anything else hands the keys back. */
    fun onTextEnter(entry: WmPassTextEntry) {
        if (entry.field == WmPassTextField.MASTER) {
            unlock()
            return
        }
        onText(entry)
        val next = when (entry.field) {
            WmPassTextField.NAME -> WmPassTextField.USERNAME
            WmPassTextField.USERNAME -> WmPassTextField.PASSWORD
            WmPassTextField.PASSWORD -> WmPassTextField.WEBSITE
            else -> null
        }
        if (next != null) beginText(WmPassTextEntry(next, _state.value.draft.of(next))) else endText()
    }

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    private fun dispatch(action: WmPassAction) {
        when (action) {
            WmPassAction.Refresh -> refresh(delayMs = 0)
            WmPassAction.OpenApp -> WmPassBridge.openApp(app)
            WmPassAction.GetApp -> WmPassBridge.openStore(app)
            WmPassAction.UnlockWithFingerprint -> askFingerprint()
            WmPassAction.StartPassword -> {
                if (_state.value.retryAt > System.currentTimeMillis()) return
                beginText(WmPassTextEntry(WmPassTextField.MASTER))
            }
            WmPassAction.Unlock -> unlock()
            WmPassAction.Lock -> lock()
            WmPassAction.StartSearch -> beginText(WmPassTextEntry(WmPassTextField.SEARCH, _state.value.query))
            WmPassAction.ClearSearch -> {
                if (currentText()?.field == WmPassTextField.SEARCH) endText()
                _state.update { it.copy(query = "") }
                refresh(delayMs = 0)
            }
            is WmPassAction.Filter -> _state.update {
                it.copy(filter = if (it.filter == action.filter) WmPassFilter.ALL else action.filter)
            }
            is WmPassAction.Open -> open(action.id)
            WmPassAction.Back -> back()
            is WmPassAction.Type -> type(action.id, action.key)
            is WmPassAction.Copy -> copy(action.id, action.key)
            is WmPassAction.Peek -> peek(action.id, action.key)
            is WmPassAction.Fill -> fill(action.id)
            WmPassAction.OpenInPass -> _state.value.item?.let { WmPassBridge.openItem(app, it.id) }
            WmPassAction.StartAdd -> {
                _state.update { it.copy(page = WmPassPage.ADD, draft = WmPassDraft(), showPassword = false, notice = null) }
                beginText(WmPassTextEntry(WmPassTextField.NAME))
            }
            WmPassAction.StartChange -> {
                if (_state.value.item?.changes == null) return
                hidePeek()
                _state.update { it.copy(page = WmPassPage.CHANGE, newPassword = "", showPassword = false, notice = null) }
                beginText(WmPassTextEntry(WmPassTextField.NEW_PASSWORD))
            }
            WmPassAction.StartGenerate -> {
                if (currentText() != null) endText()
                _state.update { it.copy(page = WmPassPage.GENERATE, generated = "", notice = null) }
                generate()
            }
            WmPassAction.TypeGenerated -> {
                val made = _state.value.generated
                if (made.isNotEmpty() && !incognito()) {
                    insertText(made)
                    haptic()
                }
            }
            WmPassAction.SaveGenerated -> {
                val made = _state.value.generated
                _state.update {
                    it.copy(page = WmPassPage.ADD, draft = WmPassDraft(password = made), generated = "", showPassword = true, notice = null)
                }
                beginText(WmPassTextEntry(WmPassTextField.NAME))
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
            val pkg = fieldPackage()
            val listing = withContext(Dispatchers.IO) { WmPassBridge.items(app, query.trim()) }
            val appName = withContext(Dispatchers.IO) { WmPassBridge.appLabel(app, pkg) }
            val now = SystemClock.elapsedRealtime()
            val was = _state.value.status
            // Opened elsewhere while the tool showed it locked: by the fingerprint prompt, or in WM Pass.
            val opened = was != null && was != WmPassBridge.Status.OPEN && listing.status == WmPassBridge.Status.OPEN
            _state.update {
                it.copy(
                    status = listing.status,
                    rows = listing.rows,
                    vault = listing.vault.ifEmpty { it.vault },
                    fingerprint = listing.fingerprint,
                    retryAt = listing.retryAt,
                    inPass = pkg == WmPassBridge.PACKAGE,
                    appName = appName,
                    openedAt = if (opened) now else it.openedAt,
                    // Locked under an open page: back to the start, nothing of the item left.
                    page = if (listing.status == WmPassBridge.Status.OPEN) it.page else WmPassPage.LIST,
                    item = if (listing.status == WmPassBridge.Status.OPEN) it.item else null,
                    peek = if (listing.status == WmPassBridge.Status.OPEN) it.peek else null,
                )
            }
            if (opened) {
                haptic()
                say(WmPassNotice.UNLOCKED)
            }
        }
    }

    // ---- unlocking ----

    private fun askFingerprint() {
        if (currentText() != null) endText()
        val from = fieldPackage()
        scope.launch {
            val started = withContext(Dispatchers.IO) { WmPassBridge.startUnlockPrompt(app) }
            if (started) {
                unlockAskedAt = SystemClock.elapsedRealtime()
                unlockFor = from
            } else {
                say(WmPassNotice.PROMPT_FAILED)
                refresh(delayMs = 0)
            }
        }
    }

    private fun unlock() {
        val entry = currentText()?.takeIf { it.field == WmPassTextField.MASTER } ?: return
        if (_state.value.unlocking) return
        val password = entry.text
        if (password.isEmpty()) return
        // The box lets go of the password at once; this call holds it until it is sent.
        endText()
        _state.update { it.copy(unlocking = true) }
        scope.launch {
            val reply = withContext(Dispatchers.IO) { WmPassBridge.unlock(app, password) }
            _state.update { it.copy(unlocking = false) }
            when (reply.outcome) {
                WmPassBridge.Unlock.OPENED -> {
                    // The refresh sees locked → open, and says so.
                    _state.update { it.copy(status = WmPassBridge.Status.LOCKED, vault = reply.vault.ifEmpty { it.vault }) }
                    refresh(delayMs = 0)
                }
                WmPassBridge.Unlock.WRONG -> {
                    _state.update { it.copy(wrongTries = it.wrongTries + 1, retryAt = reply.retryAt) }
                    // Straight back into the box for another go, unless a wait has begun.
                    if (reply.retryAt <= System.currentTimeMillis()) beginText(WmPassTextEntry(WmPassTextField.MASTER))
                }
                WmPassBridge.Unlock.WAITING -> _state.update { it.copy(wrongTries = it.wrongTries + 1, retryAt = reply.retryAt) }
                WmPassBridge.Unlock.FACTOR -> say(WmPassNotice.NEEDS_DOOR)
                WmPassBridge.Unlock.FAILED -> {
                    say(WmPassNotice.UNLOCK_FAILED)
                    refresh(delayMs = 0)
                }
            }
        }
    }

    private fun lock() {
        if (currentText() != null) endText()
        hidePeek()
        scope.launch {
            val locked = withContext(Dispatchers.IO) { WmPassBridge.lock(app) }
            _state.update {
                it.copy(page = WmPassPage.LIST, item = null, draft = WmPassDraft(), newPassword = "", generated = "", rows = emptyList(), query = "")
            }
            if (locked) {
                haptic()
                say(WmPassNotice.LOCKED)
            }
            refresh(delayMs = 0)
        }
    }

    // ---- items ----

    private fun open(id: String) {
        if (currentText()?.field == WmPassTextField.SEARCH) endText()
        hidePeek()
        scope.launch {
            val page = withContext(Dispatchers.IO) { WmPassBridge.item(app, id) }
            if (page == null) {
                // Gone, or the vault locked since the list was drawn.
                say(WmPassNotice.GONE)
                refresh(delayMs = 0)
            } else {
                haptic()
                _state.update { it.copy(page = WmPassPage.ITEM, item = page, notice = null, inPass = fieldPackage() == WmPassBridge.PACKAGE) }
            }
        }
    }

    private fun back() {
        if (currentText() != null) endText()
        hidePeek()
        _state.update {
            when (it.page) {
                WmPassPage.CHANGE -> it.copy(page = WmPassPage.ITEM, newPassword = "", showPassword = false)
                else -> it.copy(page = WmPassPage.LIST, item = null, draft = WmPassDraft(), showPassword = false, generated = "")
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
        val shown = plainValue(id, key)
        if (shown != null) {
            insertText(shown)
            return
        }
        typeSecret(id, key)
    }

    private fun plainValue(id: String, key: String): String? =
        _state.value.item?.takeIf { it.id == id }?.fields?.firstOrNull { it.key == key && !it.secret }?.value

    private fun typeSecret(id: String, key: String) {
        if (incognito()) return
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

    /**
     * A login into a sign-in form in two taps. In a password box, its
     * password. Anywhere else, its username, then the form's Next, and the
     * password follows once a password box of the same app has the keys.
     */
    private fun fill(id: String) {
        if (incognito()) return
        if (currentText() != null) endText()
        if (isPasswordInput(fieldInputType())) {
            typeSecret(id, VaultWire.PASSWORD)
            return
        }
        val from = fieldPackage()
        scope.launch {
            val username = plainValue(id, VaultWire.USERNAME)
                ?: withContext(Dispatchers.IO) { WmPassBridge.value(app, id, VaultWire.USERNAME) }
            if (username.isNullOrEmpty()) {
                say(WmPassNotice.NO_USERNAME)
                return@launch
            }
            haptic()
            insertText(username)
            pendingFill = PendingFill(id, from, SystemClock.elapsedRealtime())
            say(WmPassNotice.FILL_NEXT)
            nextField()
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

    /** Shows one secret for [PEEK_MS], then forgets it; a second tap hides it sooner. */
    private fun peek(id: String, key: String) {
        val now = _state.value.peek
        if (now != null && now.id == id && now.key == key) {
            hidePeek()
            return
        }
        peekJob?.cancel()
        peekJob = scope.launch {
            val value = withContext(Dispatchers.IO) { WmPassBridge.value(app, id, key) }
            if (value == null) {
                say(WmPassNotice.TYPE_FAILED)
                refresh(delayMs = 0)
                return@launch
            }
            _state.update { it.copy(peek = WmPassPeek(id, key, value, SystemClock.elapsedRealtime() + PEEK_MS)) }
            delay(PEEK_MS)
            _state.update { it.copy(peek = null) }
        }
    }

    private fun hidePeek() {
        peekJob?.cancel()
        if (_state.value.peek != null) _state.update { it.copy(peek = null) }
    }

    private fun generate() {
        scope.launch {
            val made = withContext(Dispatchers.IO) { WmPassBridge.generate(app) }
            if (made == null) {
                say(WmPassNotice.NO_PASSWORD)
                return@launch
            }
            haptic()
            when (_state.value.page) {
                WmPassPage.GENERATE -> _state.update { it.copy(generated = made) }
                WmPassPage.CHANGE -> _state.update { it.copy(newPassword = made, showPassword = true) }
                else -> _state.update { it.copy(draft = it.draft.copy(password = made), showPassword = true) }
            }
            // The box being typed in takes it too, or its next keystroke would put the old words back.
            val field = if (_state.value.page == WmPassPage.CHANGE) WmPassTextField.NEW_PASSWORD else WmPassTextField.PASSWORD
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

        /** How long a secret stays shown on an item's page. */
        const val PEEK_MS = 10_000L

        /** How long a fill waits for the password box: a two-page sign-in takes a Next and a load. */
        private const val FILL_WAIT_MS = 15_000L

        /** How long after the fingerprint prompt started the tool still opens again by itself. */
        private const val UNLOCK_RETURN_MS = 120_000L

        /** The longest a box holds: a password, a name, an address. */
        const val TEXT_MAX = 512
    }
}

package com.wasimaster.wmkeyboard.ime

import android.os.Handler
import android.text.InputType
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.emoji.EmojiUsage
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.LanguageMixConfidence
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/*
 * What every Robolectric test of [WMKeyboardService] needs before it can ask
 * the service a single question: a service that is attached but not created, a
 * field to type into, a letter grid, a stroke, a way to plant state the service
 * would otherwise have spent onCreate building, and a way to wait for the one
 * thread hop a glide takes.
 *
 * It lives here rather than in each test because it was in each test — six
 * copies of the same hundred lines, drifting apart a line at a time. What is
 * deliberately NOT here is any assertion helper: a shared assertion is how a
 * test file stops saying what it is testing.
 *
 * The two rules that make these tests mean anything are enforced by
 * [glideReadyState] and documented on it, because they read as hygiene and are
 * not: without `glideReady` the service refuses the stroke at its gate and the
 * test body never runs, and without `learnFromTyping = false` the commit
 * reaches a `lateinit` that only `onCreate` assigns.
 */

/** Grid units, and the width every stroke below is measured against. */
internal const val GLIDE_KEY_WIDTH = 60f

/**
 * The service under test: attached to a context, handed a field, and never
 * created.
 *
 * `onCreate` is not called by any of these tests. Robolectric ships a shadow
 * for `InputMethodManager` and none for `InputMethodService`, so
 * `super.onCreate()` would run real AOSP window code — and nothing the typing
 * paths need comes from there. See `ServiceBootProbeTest`, which is the canary
 * for that claim.
 */
internal class GlideKeyboard(private val editor: InputConnection) : WMKeyboardService() {
    init { attachBaseContext(RuntimeEnvironment.getApplication()) }
    override fun getCurrentInputConnection(): InputConnection = editor

    /**
     * A plain text field, as an app's editor reports one. With none, the
     * service takes the window for one with no editor at all (issue #58)
     * and anything that reads the field first, Retype among them, stands down.
     */
    private val field = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
    override fun getCurrentInputEditorInfo(): EditorInfo = field
}

/**
 * The field the keyboard types into, and the tripwire every test asserts first.
 *
 * The glide scope carries no `CoroutineExceptionHandler` and its job is
 * private, so a throw anywhere inside a commit is silent and reads exactly like
 * the bug under test. Text arriving here is the proof that the coroutine got as
 * far as the state a test is about to judge — which is why the order is always
 * the field first, the keyboard's state second.
 *
 * [text] is what the field holds; [typed] is only what the keyboard put in,
 * which is what a test asserts when the field started with something in it.
 *
 * [mayArmRegion] opts in to the one behaviour that separates a same-process
 * editor from an ordinary one: the commit's own selection echo arming a
 * composing region over the word just written, so that the next commit
 * *replaces* it instead of following it. That is issue #113, and it is
 * unreproducible without this — so it is off by default, because a test not
 * about ordering should not have to reason about it.
 */
internal open class RecordingEditor(
    target: View = View(RuntimeEnvironment.getApplication()),
    initial: String = "",
    /** What sits behind the caret. A leading space here makes a trailing one unnecessary. */
    after: String = "",
) : BaseInputConnection(target, true) {

    /** What the field holds behind the caret, less what a deletion took from its front. */
    private val behind = StringBuilder(after)

    /** The text behind the caret now. */
    val textAfter: String get() = behind.toString()

    /** What the field holds in front of the caret, deletions and span replacements included. */
    val text = StringBuilder(initial)

    /** Every `commitText` argument in order — the log, not the field. */
    val commits = mutableListOf<String>()

    /** Key codes the service sent, downs and ups alike. */
    val keys = mutableListOf<Int>()
    /** The same events whole, for a test that reads their meta state. */
    val keyEvents = mutableListOf<KeyEvent>()

    /** Editor actions the service fired, in order — Send, Done, an app's own id. */
    val editorActions = mutableListOf<Int>()

    /** Whether the echo may arm a region; null leaves the editor unmodelled. */
    var mayArmRegion: (() -> Boolean)? = null

    /** Only what the keyboard committed, with whatever the field started with left out. */
    val typed: String get() = commits.joinToString("")

    /** Where the composing span starts, or -1. It always runs to the caret. */
    private var composingStart = -1

    private val echoes = Handler(Looper.getMainLooper())

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val committed = text?.toString().orEmpty()
        commits += committed
        replaceSpan(committed)
        composingStart = -1
        if (mayArmRegion != null) echoes.post { armRegionAtCaret() }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val start = if (composingStart >= 0) composingStart else this.text.length
        replaceSpan(text?.toString().orEmpty())
        composingStart = start
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composingStart = start.coerceIn(0, text.length)
        return true
    }

    override fun finishComposingText(): Boolean {
        composingStart = -1
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        text.setLength(text.length - beforeLength.coerceAtMost(text.length))
        behind.delete(0, afterLength.coerceAtMost(behind.length))
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        event?.let {
            keys += it.keyCode
            keyEvents += it
        }
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        editorActions += editorAction
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = text.takeLast(n)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = behind.take(n)

    /** The caret is always at the end here, so the span reaches it. */
    private fun replaceSpan(committed: String) {
        if (composingStart >= 0) text.setLength(composingStart)
        text.append(committed)
    }

    /**
     * The editor's answer to the echo: the word the caret sits at the end of
     * becomes the composing region, so it stays revisable. A caret after a
     * space has no word to resume and arms nothing — the same rule
     * `resumableWordAt` applies, and the reason a space that has already landed
     * makes the echo harmless.
     */
    private fun armRegionAtCaret() {
        if (mayArmRegion?.invoke() != true) return
        var start = text.length
        while (start > 0 && text[start - 1].isLetter()) start--
        if (start == text.length) return
        composingStart = start
    }
}

/**
 * Every text key's centre on the default QWERTY, in grid units.
 *
 * Straight off the layout — no Compose, no measure pass — because the geometry
 * a glide is decoded against is an *argument* to `onGesture` rather than
 * published state. That is the whole reason these tests need no input view.
 */
internal fun glideKeyCenters(): Map<Int, Pair<Float, Float>> = buildMap {
    val layouts = KeyboardUiState().layouts
    for ((rowIndex, row) in layouts.letters.rows.withIndex()) {
        var column = 0
        for (key in row) {
            if (key.action == KeyAction.Text) {
                (key.output ?: key.label).firstOrNull()?.let {
                    putIfAbsent(
                        it.lowercaseChar().code,
                        (column * GLIDE_KEY_WIDTH) to (rowIndex * GLIDE_KEY_WIDTH),
                    )
                }
            }
            column++
        }
    }
}

/**
 * The grid a stroke is read against.
 *
 * [apostropheAt] puts `'` on one key, which is the only way punctuation reaches
 * the grid at all: `keySpelling` admits letters and combining marks alone, so
 * without it a possessive flick has no key to start from.
 */
internal fun glideKeyGrid(apostropheAt: Pair<Float, Float>? = null): List<KeyCenter> {
    val centers = glideKeyCenters()
    return KeyboardUiState().layouts.glideKeys(apostropheCenter = apostropheAt) { centers[it] }
}

/**
 * A plain left-to-right stroke along [row].
 *
 * Its shape carries no meaning: with no dictionary in this module every decode
 * is empty, so the word a test gets is the one its `GlideVerdict.Word` names.
 * What the points are for is passing the length and sample-count floors that
 * sit in front of the decoder.
 */
internal fun straightStroke(row: Int = 0, steps: Int = STROKE_STEPS): List<GesturePoint> =
    (0..steps).map {
        GesturePoint(
            x = it * GLIDE_KEY_WIDTH,
            y = row * GLIDE_KEY_WIDTH,
            t = it * STROKE_STEP_MS,
        )
    }

/** A word planted on the keys before a stroke, to be looked for afterwards. */
internal fun octopusSentinel(
    word: String = "STALE",
    key: Char = 'q',
    kind: OctopusKind = OctopusKind.NEXT_WORD,
): OctopusBoard = mapOf(
    key.code to listOf(
        OctopusWord(
            keyCodePoint = key.code,
            word = word,
            typedChars = 0,
            kind = kind,
            rank = 0,
        ),
    ),
)

/**
 * The state a glide needs, without the half of the service that builds it.
 *
 * Both defaults here are load-bearing and read as hygiene, which is why they
 * are defaults rather than something each test remembers:
 *
 *  - `glideReady` is false in production until a watcher sees a loaded
 *    dictionary. Left false, `onGesture` returns at `glideAllowed` and the test
 *    body never executes — a green test guarding nothing, and the single
 *    easiest way to write a worthless service test.
 *  - `learnFromTyping = false` keeps `learningAllowed` false so `learn()`
 *    returns before it reaches the `lateinit var userLexicon` that only
 *    `onCreate` assigns. Without it the glide job dies with
 *    `UninitializedPropertyAccessException` — silently, since the scope has no
 *    handler. Note that `secureField = true` is NOT a substitute: it also turns
 *    off `allowsGestureTyping`, so the stroke is refused instead.
 *  - `fieldNoSuggestions` is not a gate a glide reads — `allowsGestureTyping`
 *    deliberately ignores it — but it is insurance for the day something on the
 *    commit path calls `refreshSuggestions`, which reaches a snippet store that
 *    is one more `lateinit` only `onCreate` assigns.
 */
internal fun glideReadyState(
    glideReady: Boolean = true,
    settings: KeyboardSettings = KeyboardSettings(learnFromTyping = false),
    shiftState: ShiftState = ShiftState.OFF,
    secureField: Boolean = false,
    fieldKind: FieldKind = FieldKind.TEXT,
    fieldNoSuggestions: Boolean = true,
    octopus: OctopusBoard = emptyMap(),
    octopusGlide: OctopusBoard = emptyMap(),
): KeyboardUiState = KeyboardUiState(
    glideReady = glideReady,
    settings = settings,
    shiftState = shiftState,
    secureField = secureField,
    fieldKind = fieldKind,
    fieldNoSuggestions = fieldNoSuggestions,
    octopus = octopus,
    octopusGlide = octopusGlide,
)

/**
 * Writes [state] into the service, and hands it back so a test can compare
 * against exactly what it planted.
 *
 * Reflection rather than a production seam: `_uiState` is written from around a
 * hundred places in the service, and widening it so six test files can write it
 * too would be a worse trade than this one line. Robolectric instruments
 * `android.*`, not app classes, so the field is the ordinary one and Kotlin
 * does not mangle its name; a rename breaks this loudly, with
 * `NoSuchFieldException`.
 */
internal fun seedState(service: WMKeyboardService, state: KeyboardUiState): KeyboardUiState {
    val field = WMKeyboardService::class.java.getDeclaredField("_uiState")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val flow = field.get(service) as MutableStateFlow<KeyboardUiState>
    flow.value = state
    return state
}

/** A service, a field, and the state it starts from, in one line. */
internal fun glideKeyboard(
    state: KeyboardUiState = glideReadyState(),
    editor: RecordingEditor = RecordingEditor(),
): Triple<GlideKeyboard, RecordingEditor, KeyboardUiState> {
    val service = GlideKeyboard(editor)
    plantPersonalStores(service)
    return Triple(service, editor, seedState(service, state))
}

/**
 * The stores `onCreate` attaches before a key can be typed, planted the way
 * the lock screen attaches them: in memory, backed by no file.
 *
 * The snippet store and the user lexicon. A mark that ends a word takes a second look
 * for a pattern snippet about the mark itself, and that look asks the store
 * before anything else, so without it every test that types punctuation after
 * a word died on a `lateinit` (the second look came after those tests did).
 * The service needs no guard for this: `attachPersonalStores` runs inside
 * `onCreate`, so on a device the store always exists, with a null file while
 * the phone is locked.
 */
internal fun plantPersonalStores(service: WMKeyboardService) {
    plant(service, "snippetStore", SnippetStore(null))
    // Learning a committed word asks whether the user already knows it.
    plant(service, "userLexicon", UserLexicon(null))
    // A snippet's variables expand against the clipboard.
    plant(service, "clipboardStore", ClipboardStore(null))
}

/**
 * What `onFinishInputView` reaches that only `onCreate` builds (#827), so a
 * test can put the keyboard away.
 *
 * The window's lifecycle owner is the one the hide itself reaches, before it
 * settles anything else: without it the hide stops at `lifecycleOwner.onPause()`
 * with an `UninitializedPropertyAccessException`. The two stores are only
 * saved afterwards, on the persist dispatcher, where a missing one prints a
 * stack trace on a worker thread and fails nothing; planted, nothing is
 * printed. Backed by no file, as on the lock screen, so nothing is written.
 */
internal fun plantWindowStores(service: WMKeyboardService) {
    plant(service, "lifecycleOwner", KeyboardViewLifecycleOwner())
    plant(service, "languageMixConfidence", LanguageMixConfidence(null))
    plant(service, "emojiUsage", EmojiUsage(null))
}

private fun plant(service: WMKeyboardService, name: String, value: Any) {
    val field = WMKeyboardService::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(service, value)
}

/**
 * Runs the main looper until [until] holds, or until the wait has plainly
 * failed.
 *
 * A glide publishes from `serviceScope.launch` on `Dispatchers.Main.immediate`,
 * which under Robolectric dispatches inline because the test body is already on
 * the main looper — so the job runs up to its first suspension without any help
 * here. What does need help is the resumption after `withContext(
 * Dispatchers.Default)`, which comes back through a post to that looper. Hence
 * idle, check, sleep: the sleep is for the real thread the work went to, and
 * the idle is for the post that brings it home.
 *
 * `Dispatchers.setMain` is deliberately not used. It would have to run before
 * the service is constructed to have any effect on a scope that captured
 * `Main.immediate` at field-init time, and it buys nothing that idling does not.
 */
internal fun settle(until: () -> Boolean) {
    repeat(SETTLE_ROUNDS) {
        shadowOf(Looper.getMainLooper()).idle()
        if (until()) return
        Thread.sleep(SETTLE_STEP_MS)
    }
    shadowOf(Looper.getMainLooper()).idle()
}

/** How many points a synthetic stroke carries past its start. */
private const val STROKE_STEPS = 5

/** Milliseconds between two samples of a synthetic stroke. */
private const val STROKE_STEP_MS = 20L

/** How many times [settle] pumps the looper before giving up. */
private const val SETTLE_ROUNDS = 200

/** How long [settle] waits between pumps, for the work that went to another thread. */
private const val SETTLE_STEP_MS = 10L

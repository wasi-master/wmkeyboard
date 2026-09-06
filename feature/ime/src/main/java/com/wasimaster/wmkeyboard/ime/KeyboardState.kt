package com.wasimaster.wmkeyboard.ime

import android.view.KeyEvent
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.otp.NotificationOtp
import com.wasimaster.wmkeyboard.core.emoji.AnimatedEmoji
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.emoji.EmojiEntry
import com.wasimaster.wmkeyboard.core.emoji.EmojiVariantIndex
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.Layouts
import com.wasimaster.wmkeyboard.core.media.MediaSnapshot
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.NoComposer
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.TransliterationHintMode
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import com.wasimaster.wmkeyboard.core.settings.interactiveTyping
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFolder
import com.wasimaster.wmkeyboard.core.tools.AiPhase
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.core.tools.ToolPrefill
import com.wasimaster.wmkeyboard.core.tools.TypedWord
import com.wasimaster.wmkeyboard.core.tools.TypingResult
import com.wasimaster.wmkeyboard.core.tools.TypingTestMode
import com.wasimaster.wmkeyboard.core.tools.WeatherInfo
import com.wasimaster.wmkeyboard.core.tools.WpmSample
import java.io.File

enum class ShiftState { OFF, ON, CAPS_LOCK }

/**
 * Re-cases a suggestion for display/commit to follow the live [shift] state, so
 * pressing shift while a word is composing walks the strip through
 * lower → Title → UPPER the way the keys themselves do. [ShiftState.OFF] leaves
 * the word as the engine produced it (its casing already mirrors the typed
 * text). Emails ('@') are left untouched — capitalizing an address is wrong.
 */
fun displayCaseForShift(word: String, shift: ShiftState): String {
    if (word.isEmpty() || '@' in word) return word
    return when (shift) {
        ShiftState.CAPS_LOCK -> word.uppercase()
        ShiftState.ON -> word.replaceFirstChar { it.uppercase() }
        ShiftState.OFF -> word
    }
}

/**
 * A Ctrl/Alt/Meta latch.
 *
 * Mirrors [ShiftState] deliberately — tap arms for one key, a quick second tap
 * locks, anything after that clears — so the modifier keys need no explanation
 * beyond the shift key the user already knows.
 */
enum class ModifierState { OFF, ARMED, LOCKED }

/**
 * Modifier latches pending for the next key.
 *
 * Three flat fields rather than a `Map<ModifierKey, ModifierState>`: the map
 * allocates on every keystroke that clears a latch, and makes the "nothing is
 * pending" test — which runs on literally every key press — a hash lookup
 * instead of three comparisons.
 */
data class Modifiers(
    val ctrl: ModifierState = ModifierState.OFF,
    val alt: ModifierState = ModifierState.OFF,
    val meta: ModifierState = ModifierState.OFF,
) {
    operator fun get(key: ModifierKey): ModifierState = when (key) {
        ModifierKey.CTRL -> ctrl
        ModifierKey.ALT -> alt
        ModifierKey.META -> meta
    }

    fun with(key: ModifierKey, value: ModifierState): Modifiers = when (key) {
        ModifierKey.CTRL -> copy(ctrl = value)
        ModifierKey.ALT -> copy(alt = value)
        ModifierKey.META -> copy(meta = value)
    }

    val isEmpty: Boolean
        get() = ctrl == ModifierState.OFF && alt == ModifierState.OFF && meta == ModifierState.OFF

    /**
     * Drops the one-shot latches after a key has spent them. Locked modifiers
     * survive, which is the entire point of locking them.
     */
    fun consumed(): Modifiers = Modifiers(
        ctrl = if (ctrl == ModifierState.ARMED) ModifierState.OFF else ctrl,
        alt = if (alt == ModifierState.ARMED) ModifierState.OFF else alt,
        meta = if (meta == ModifierState.ARMED) ModifierState.OFF else meta,
    )

    /**
     * The `KeyEvent.META_*` mask for these latches. Both the generic and the
     * left-hand bit for each, because editors test either one and a real
     * keyboard reports both.
     */
    fun metaFlags(): Int {
        var flags = 0
        if (ctrl != ModifierState.OFF) {
            flags = flags or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt != ModifierState.OFF) {
            flags = flags or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (meta != ModifierState.OFF) {
            flags = flags or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        }
        return flags
    }

    companion object { val None = Modifiers() }
}

/**
 * Which key map is showing. FN is the layout's own extra layer, reached from a
 * [KeyAction.Fn] key and absent from layouts that do not define one.
 */
/**
 * Which grid of the [LayoutSet] is on screen. [SECONDARY] is one of the user's
 * own secondary layouts, named by [KeyboardUiState.secondaryLayoutId]; it sits
 * beside the symbol layers rather than replacing the active layout, so the
 * language, dictionary and composer stay those of the layout underneath.
 */
enum class LayoutMode { LETTERS, SYMBOLS, SYMBOLS_SHIFTED, FN, SECONDARY }

/**
 * The layouts reachable from the focused field without a new `onStartInput`:
 * the layers the ?123 / ABC keys cycle between, plus the numeric pad when the
 * field has one.
 *
 * Resolved by the service — it owns the layout store — and handed over already
 * compiled, so the rendering code never touches storage and [keyRowsHeight] can
 * stay a pure function of state. The service caches instances by resolution key
 * rather than rebuilding per emission: [KeyboardUiState]'s generated `equals`
 * walks its fields, and a stable instance keeps that comparison cheap instead of
 * walking every key on every recomposition.
 */
data class LayoutSet(
    val letters: KeyboardLayout,
    val symbols: KeyboardLayout,
    val symbolsShifted: KeyboardLayout,
    /** The layout's own extra layer; null when it defines none. */
    val fn: KeyboardLayout? = null,
    /** Keypad for the focused field kind; null for TEXT/EMAIL/URI. */
    val numeric: KeyboardLayout? = null,
    /**
     * The layout's own Number layer, when it authored one; null inherits. What
     * the Numpad tool and a long press on ?123 draw (issue #55): before this
     * the panel had a pad of its own and a custom Number layer only ever
     * reached a field typed as numeric.
     */
    val number: KeyboardLayout? = null,
    /**
     * Number rows this layout authored, by the layer they belong to. Absent
     * entries take the digits the layer has always shown.
     */
    val numberRows: Map<LayoutMode, List<Key>> = emptyMap(),
    /**
     * The column count these grids were laid out against, when the tablet
     * expansion widened them; null on a phone and on any layout that declined it.
     *
     * Carried here rather than recomputed at draw time for two reasons. It is the
     * *declared* grid width, so every layer is drawn on the letters layer's pitch
     * — without it, the expanded letters layer would be twelve columns and the
     * untouched symbols layer ten, and every key would visibly resize on the way
     * into `?123`. And it doubles as the renderer's "the expansion is live"
     * signal, which is what keeps [DeviceForm] out of [KeyboardUiState] and out
     * of the per-keystroke equality walk.
     */
    val gridWidth: Float? = null,
    /**
     * The user's secondary layouts (issue #62), compiled, by id. The same map
     * for every layout set — a secondary grid belongs to the user, not to the
     * language layout — and handed over by reference so the cache can tell an
     * unchanged set from a re-decoded one.
     *
     * Deliberately not a term in [rowSpan]: see `reservedRowSpan`.
     */
    val secondaries: Map<String, KeyboardLayout> = emptyMap(),
) {
    /**
     * Rows the key grid reserves.
     *
     * The tallest reachable layer wins, so pressing ?123 never resizes the
     * keyboard under the user's fingers; the shorter layers pad instead. Sizing
     * each layer to its own row count was the alternative and was rejected for
     * exactly that reason — the layer key is pressed mid-word, constantly, and a
     * resize there reflows the whole host app.
     *
     * Not a constructor property, so it stays out of equals/hashCode/copy.
     */
    val rowSpan: Int = maxOf(
        letters.rows.size,
        symbols.rows.size,
        symbolsShifted.rows.size,
        fn?.rows?.size ?: 0,
        numeric?.rows?.size ?: 0,
    ).coerceAtLeast(1)

    /**
     * Every character the letter layer can produce: base labels, their shifted
     * forms, and whatever the long presses hold.
     *
     * This used to be a yes/no "does it have all of a-z", which was the English
     * question wearing a general name. The gesture decoder needs the alphabet
     * itself, and it needs the shifted and long-pressed characters in it: a
     * finger crossing Probhat's ক/খ key cannot say which it meant, and refusing
     * the second one would make most aspirated Bengali words unglidable. What
     * the old flag was really protecting against — a layout so sparse that
     * decoding it returns confident nonsense — is now a coverage measurement
     * against the language's own word list rather than a guess about a-z.
     */
    val letterAlphabet: Set<Char> = buildSet {
        for (row in letters.rows) {
            for (key in row) {
                if (key.action != KeyAction.Text) continue
                fun take(spelling: String?) {
                    val label = spelling ?: return
                    addAll(keySpelling(label) ?: return)
                    composedKeyChar(label)?.let { add(it) }
                    decomposedKeyChars(label)?.let(::addAll)
                }
                take(key.output ?: key.label)
                take(key.shiftLabel)
                for (alternate in key.longPress) take(alternate)
            }
        }
    }

    /**
     * The letter grid a glide is decoded against: one entry per character the
     * layer can produce, at the centre of the key that produces it.
     *
     * [centerOf] resolves a key's base label to a measured centre, which is what
     * the renderer reports — so a key's shifted character and its long-press
     * alternates borrow that same centre here. That is the point: a finger
     * crossing Probhat's ক/খ key, or QWERTZ's u/ü key, cannot say which
     * character it meant, and offering only the base one would put most
     * aspirated Bengali words out of reach of a swipe entirely.
     *
     * Base first, then shifted, then long presses. [GlideKeyMap] gives the first
     * claim on a character to whichever entry arrives first, so a character that
     * is one key's base label and another key's long-press alternate resolves to
     * the key that actually shows it.
     *
     * [apostropheCenter] is the key the user picked to stand for the apostrophe
     * (`GestureSettings.apostropheKey`), and it is the *only* way `'` reaches this
     * grid. [keySpelling] takes letters and combining marks and nothing else, so
     * punctuation is absent from every pass — including the apostrophe QWERTY
     * hides behind `c`'s long press, which is why a contraction cannot be drawn
     * at all until a key is chosen here. Emitted before the passes so it holds the
     * claim whatever a layout does, and emitted once: exactly one key on the board
     * means an apostrophe, and two would blur both of them. Null (the default)
     * leaves the grid exactly as it was.
     */
    fun glideKeys(
        apostropheCenter: Pair<Float, Float>? = null,
        centerOf: (Char) -> Pair<Float, Float>?,
    ): List<KeyCenter> {
        val out = ArrayList<KeyCenter>(letters.rows.sumOf { it.size } * 2)
        fun emit(spelling: String?, x: Float, y: Float) {
            val label = spelling ?: return
            for (ch in keySpelling(label) ?: return) out.add(KeyCenter(ch, x, y))
            composedKeyChar(label)?.let { out.add(KeyCenter(it, x, y)) }
            decomposedKeyChars(label)?.forEach { out.add(KeyCenter(it, x, y)) }
        }
        apostropheCenter?.let { (x, y) ->
            // Both spellings: a word list may hold either, and the two are the
            // same keystroke as far as a finger is concerned.
            out.add(KeyCenter('\'', x, y))
            out.add(KeyCenter('’', x, y))
        }
        for (pass in 0 until PASSES) {
            for (row in letters.rows) {
                for (key in row) {
                    if (key.action != KeyAction.Text) continue
                    val anchor = keySpelling(key.label)?.first() ?: continue
                    val (x, y) = centerOf(anchor) ?: continue
                    when (pass) {
                        BASE_PASS -> emit(key.output ?: key.label, x, y)
                        SHIFT_PASS -> emit(key.shiftLabel, x, y)
                        else -> for (alternate in key.longPress) emit(alternate, x, y)
                    }
                }
            }
        }
        return out
    }

    companion object {
        /** Base labels, then shifted, then long presses — see [glideKeys]. */
        private const val BASE_PASS = 0
        private const val SHIFT_PASS = 1
        private const val PASSES = 3

        /** The shipped grids, for a state built before the first resolution. */
        val Default = LayoutSet(Layouts.QWERTY, Layouts.SYMBOLS, Layouts.SYMBOLS_SHIFTED)
    }
}

/**
 * The number row this layout authored for [mode], or null to use the built-in
 * digits. Read off the resolved set so the rendering code stays clear of the
 * layout store.
 */
fun KeyboardUiState.authoredNumberRow(mode: LayoutMode): List<Key>? = layouts.numberRows[mode]

/**
 * What kind of field is focused, from EditorInfo.inputType. The numeric
 * kinds lock the keyboard to a purpose-built keypad; EMAIL/URI keep the
 * letter layouts but adapt the bottom row (@ or / key, .com long-press).
 */
enum class FieldKind { TEXT, EMAIL, URI, NUMBER, PHONE, DATE, TIME, DATETIME }

/** Kinds rendered as a 4-column keypad instead of the letter layouts. */
val FieldKind.isNumericPad: Boolean
    get() = this == FieldKind.NUMBER || this == FieldKind.PHONE ||
        this == FieldKind.DATE || this == FieldKind.TIME || this == FieldKind.DATETIME

/**
 * The layer a numeric field kind types on, or null for the kinds that keep the
 * letter layouts (TEXT, EMAIL, URI). The mapping lives here rather than in
 * `core/layout` because [FieldKind] is an IME concept — the layout package has
 * no business knowing what an EditorInfo is.
 */
val FieldKind.numericLayer: LayoutLayer?
    get() = when (this) {
        FieldKind.NUMBER -> LayoutLayer.NUMBER
        FieldKind.PHONE -> LayoutLayer.PHONE
        FieldKind.DATE -> LayoutLayer.DATE
        FieldKind.TIME -> LayoutLayer.TIME
        FieldKind.DATETIME -> LayoutLayer.DATETIME
        FieldKind.TEXT, FieldKind.EMAIL, FieldKind.URI -> null
    }

enum class PanelMode {
    NONE, EMOJI, CLIPBOARD, SNIPPETS, TOOLBOX, TEXT_EDIT, TRACKPAD,
    COMPASS, LEVEL, MOON_PHASE, WEATHER, CALENDAR,
    THEMES, SOUND_HAPTICS, NUMPAD, HANDWRITING, CAMERA, DICTIONARY,
    TRANSLATE, GIF, STICKER, WEB_SEARCH, IMAGE_SEARCH,
    OCR, QR_SCAN, VOICE, GRAMMAR,
    WIKIPEDIA, SYMBOLS, CALCULATOR, UNIT_CONVERT, CURRENCY, QR_GEN, PASSWORD_GEN, AI,
    MODES, TYPING_TEST, MEDIA_CONTROL, PLUGINS, APP_LAUNCHER,

    /** The CJK candidate grid: the strip's overflow, opened from its chevron. */
    CANDIDATES,
}

/**
 * Panels whose search box types into [KeyboardUiState.mediaQuery] (the same
 * key-rerouting trick as emoji search) instead of the editor.
 */
val PanelMode.hasMediaSearch: Boolean
    get() = this == PanelMode.GIF || this == PanelMode.STICKER ||
        this == PanelMode.WEB_SEARCH || this == PanelMode.IMAGE_SEARCH ||
        this == PanelMode.WIKIPEDIA || this == PanelMode.TRANSLATE ||
        this == PanelMode.APP_LAUNCHER ||
        // QR generator reuses the buffer as its own editable content, so it
        // encodes what the user types rather than the field's text.
        this == PanelMode.QR_GEN

/**
 * Which strip of an open panel the hardware focus ring is in. Tab cycles the
 * regions the open panel offers ([panelFocusRegions]); the arrows move within
 * one.
 */
enum class FocusRegion {
    /** The panel's search pill, whose only "item" is the pill itself. */
    SEARCH,

    /** Source or pack chips above the results. */
    CHIPS,

    /**
     * The scrollable category row in the GIF/sticker default view. Its own
     * region rather than a second [CHIPS] row: the focus controller keys a
     * region's activation by region, so two rows publishing one name would
     * leave Enter running the wrong row's action.
     */
    CATEGORIES,

    /** The grid or list of results. */
    RESULTS,

    /**
     * Command chips that act on the panel's current content — Replace, Insert,
     * Retry, Send, swap, refresh. Their own region rather than a [CHIPS] row
     * because activation is keyed by region, and because they usually live at
     * the panel's opposite edge from the chips that scope it.
     */
    ACTIONS,
}

/**
 * Where the hardware focus ring sits inside the open panel. Null on
 * [KeyboardUiState] means touch mode — nothing is drawn at all, so a phone user
 * never sees a highlight they did not ask for.
 */
data class PanelFocus(val region: FocusRegion, val index: Int)

/**
 * The leader was pressed and the next letter picks a tool. [cheatSheet] promotes
 * the compact hint to the full legend — deliberately the same state, so the
 * picker and the cheat sheet are one mechanism with one way out of them.
 */
data class ToolPickerState(val armedAt: Long, val cheatSheet: Boolean = false)

/**
 * A hardware language switch in progress. [browsing] true means Ctrl is still
 * held and releasing it commits the highlighted layout; false means the switch
 * already happened and the overlay is only flashing confirmation.
 *
 * [layoutIds] is a snapshot of the enabled cycle at session start, so a
 * settings emission mid-browse cannot reshuffle the candidates under the
 * user's fingers.
 */
data class LanguageSwitchState(
    val layoutIds: List<String>,
    val candidate: Int,
    val browsing: Boolean,
    val shownAt: Long,
)

/**
 * Tab order for a panel: the regions it actually has, results last so the ring
 * starts where the content is. Exhaustive on purpose — a new [PanelMode] is a
 * compile error here rather than a panel that silently cannot be navigated.
 */
fun panelFocusRegions(panel: PanelMode): List<FocusRegion> = when (panel) {
    // Query, then the chips that scope it, then what came back.
    PanelMode.EMOJI -> listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.RESULTS)
    PanelMode.GIF, PanelMode.STICKER -> listOf(
        FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.CATEGORIES, FocusRegion.RESULTS,
    )
    PanelMode.SYMBOLS -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS)
    // Chips switch between the theme grid and the icon pack grid.
    PanelMode.THEMES -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS)
    // The clipboard's chips are the fragments pulled out of its history.
    PanelMode.CLIPBOARD ->
        listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.RESULTS)
    PanelMode.DICTIONARY,
    PanelMode.WEB_SEARCH, PanelMode.IMAGE_SEARCH, PanelMode.WIKIPEDIA,
    -> listOf(FocusRegion.SEARCH, FocusRegion.RESULTS)
    // Query, the pinned/recents row, then the app grid.
    PanelMode.APP_LAUNCHER ->
        listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.RESULTS)
    PanelMode.TOOLBOX, PanelMode.SNIPPETS, PanelMode.MODES,
    // The candidate grid is a wall of choices and nothing else.
    PanelMode.CANDIDATES,
    -> listOf(FocusRegion.RESULTS)
    // Header command chips first — Replace/Insert are what a keyboard user
    // came back to the panel for — then the model row, then the action chips.
    PanelMode.AI -> listOf(FocusRegion.ACTIONS, FocusRegion.CHIPS, FocusRegion.RESULTS)
    // The query bar, the target-language chip, the picker's rows while it is
    // open (zero rows closed, so Tab skips it), then Replace/Insert.
    PanelMode.TRANSLATE -> listOf(
        FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.RESULTS, FocusRegion.ACTIONS,
    )
    // The keypads have no region on purpose: physical keys type straight into
    // the expression or amount, which beats arrow-driving a 5×4 grid. The
    // ring covers only what typing cannot reach.
    PanelMode.CALCULATOR -> listOf(FocusRegion.CHIPS, FocusRegion.ACTIONS)
    PanelMode.UNIT_CONVERT ->
        listOf(FocusRegion.CATEGORIES, FocusRegion.CHIPS, FocusRegion.ACTIONS)
    PanelMode.CURRENCY -> listOf(FocusRegion.CHIPS, FocusRegion.ACTIONS)
    // The transport (or the grant button); typing already reaches QR's buffer,
    // so its ring only needs Send. The typing test's ACTIONS is the results
    // screen's Restart — during a run the keys are the test.
    PanelMode.MEDIA_CONTROL, PanelMode.PLUGINS -> listOf(FocusRegion.RESULTS)
    PanelMode.QR_GEN, PanelMode.TYPING_TEST -> listOf(FocusRegion.ACTIONS)
    PanelMode.PASSWORD_GEN ->
        listOf(FocusRegion.CHIPS, FocusRegion.ACTIONS, FocusRegion.RESULTS)
    // The dialect and Fix-all chips, then the lint cards. Seed-only (see
    // [panelFocusSeedOnly]): the panel accompanies live field editing, so the
    // arrows keep moving the caret until the ring is asked for.
    PanelMode.GRAMMAR -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS)
    // One button, which one depends on the state: refresh, retry, or settings.
    PanelMode.WEATHER -> listOf(FocusRegion.ACTIONS)
    // Month navigation, then the day grid (seven columns wide).
    PanelMode.CALENDAR -> listOf(FocusRegion.ACTIONS, FocusRegion.RESULTS)
    // The two master switches, then the style chips. Sliders stay touch-only:
    // Enter means nothing on one, and the arrows are the ring's own keys.
    PanelMode.SOUND_HAPTICS -> listOf(FocusRegion.ACTIONS, FocusRegion.CHIPS)
    // Mic first, then whatever chips the state has (undo, language, model).
    PanelMode.VOICE -> listOf(FocusRegion.ACTIONS)
    // The confirm/result stages' buttons (Retake/Send, Scan again/Copy/
    // Insert). The live viewfinders publish nothing: a ring over video helps
    // nobody, and the shutter is a pointer affordance.
    PanelMode.CAMERA, PanelMode.QR_SCAN -> listOf(FocusRegion.ACTIONS)
    PanelMode.OCR -> listOf(FocusRegion.ACTIONS, FocusRegion.RESULTS)
    // Panels with nothing to drive. The sensor trio has zero controls at all;
    // TEXT_EDIT/NUMPAD duplicate keys a physical keyboard already sends to
    // the field directly (ringing a d-pad that itself moves the caret would
    // capture the very arrows it re-implements); HANDWRITING's canvas is
    // pointer-only and its three rare chips are not worth a ring over ink, and
    // TRACKPAD is a pointing surface and nothing else.
    PanelMode.NONE, PanelMode.TEXT_EDIT, PanelMode.TRACKPAD, PanelMode.COMPASS, PanelMode.LEVEL,
    PanelMode.MOON_PHASE, PanelMode.NUMPAD, PanelMode.HANDWRITING,
    -> emptyList()
}

/**
 * Panels whose ring appears only when the panel was opened from the keyboard
 * (the leader seeds it) — never summoned by a bare arrow press. The grammar
 * panel sits over a field the user is actively editing, and the first arrow
 * summoning a ring there would steal the caret keys mid-sentence. Tab stays
 * the field's too, for the same reason.
 */
fun panelFocusSeedOnly(panel: PanelMode): Boolean = panel == PanelMode.GRAMMAR

/**
 * The app-launcher panel's drill-down view: one app and the activities it
 * declares. Owned by the service so the enumeration runs off the main thread;
 * [activities] is empty while the load is in flight.
 */
data class LauncherDetailUi(
    val app: LauncherApp,
    val activities: List<LauncherActivity> = emptyList(),
    val loading: Boolean = true,
)

/** Readiness of the handwriting panel's recognition model. */
enum class HandwritingStatus { CHECKING, NEED_MODEL, DOWNLOADING, READY, ERROR }

/**
 * Handwriting panel state, owned by the service (it runs recognition).
 * Completed strokes live here so the service can rebuild the ink and clear
 * the canvas after a commit; the stroke being drawn stays local to the
 * panel and is appended through the stroke-finished callback.
 */
data class HandwritingUi(
    val status: HandwritingStatus = HandwritingStatus.CHECKING,
    /** BCP-47 tag of the active recognition model (en-US, bn). */
    val languageTag: String = "en-US",
    val strokes: List<com.wasimaster.wmkeyboard.core.handwriting.HwStroke> = emptyList(),
    /** Recognition in flight — strokes are frozen on screen until it lands. */
    val recognizing: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Where the voice input panel is in a dictation session. TRANSCRIBING is the
 * offline-Whisper-only state after recording stops while the model turns the
 * captured audio into text (the system recognizer streams instead, so it never
 * enters it).
 */
enum class VoiceStatus { IDLE, LISTENING, FINISHING, TRANSCRIBING, NEED_PERMISSION, UNAVAILABLE, ERROR }

/**
 * On-device recognition model availability for the active language
 * (API 33+). UNKNOWN doubles as "not applicable" — older Android, no
 * on-device recognizer, or the language isn't supported on-device at all.
 */
enum class VoiceModelState { UNKNOWN, INSTALLED, DOWNLOADABLE, DOWNLOADING }

/**
 * Voice input state, owned by the service (it runs the recognizer). The
 * utterance in progress lives in the editor as composing text; [partial]
 * mirrors it for the panel's status line.
 */
data class VoiceUi(
    val status: VoiceStatus = VoiceStatus.IDLE,
    val partial: String = "",
    /** Mic level 0..1, quantized by the service; drives the pulse ring. */
    val level: Float = 0f,
    /** BCP-47 tag sent to the recognizer (en-US, bn-BD). */
    val languageTag: String = "en-US",
    val errorMessage: String? = null,
    /** Dictation runs in the compact bar over the keys instead of the panel. */
    val strip: Boolean = false,
    /**
     * The collapsed voice bar has the keyboard's place (Gboard style). Mirrors
     * the persisted [com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.active]
     * so opening and closing take effect this frame, not a DataStore round trip
     * later; the settings collector re-syncs it from the stored value.
     */
    val bar: Boolean = false,
    /**
     * The bar was entered through a collapse button, not picked in settings —
     * it wears the expand button instead of the keyboard button. Mirrors
     * [com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.inline] the
     * same optimistic way as [bar], so the right button shows on the bar's
     * very first frame.
     */
    val barInline: Boolean = false,
    /** A just-dictated utterance is still at the cursor; the undo chip shows. */
    val canUndo: Boolean = false,
    /** Offline-model chip on the panel (download for offline dictation). */
    val modelState: VoiceModelState = VoiceModelState.UNKNOWN,
    /** Download percent while [modelState] is DOWNLOADING, -1 when unknown. */
    val modelProgress: Int = -1,
    /** Offline Whisper engine is active for this session (vs the system recognizer). */
    val whisper: Boolean = false,
    /** Whisper translate-to-English task is on (mirrors the setting for the panel chip). */
    val translate: Boolean = false,
    /** Offline Whisper is selected but no model is downloaded — panel prompts to get one. */
    val whisperNeedsModel: Boolean = false,
)

/** The persisted settings say the collapsed voice bar should be up. */
fun VoiceBarSettings.armed(): Boolean = mode == VoiceBarSettings.MODE_BAR && active

/**
 * An interactive voice typing session can be run from one microphone button,
 * which leaves the suggestion strip its room (see `VoiceMicChip`).
 *
 * False when the surface has something to say that does not fit on a button:
 * no microphone permission, no recognizer, no offline model, an error, or a
 * password field. Those fall back to the compact bar, which has a line of text
 * and an action.
 */
fun KeyboardUiState.voiceChipOnly(): Boolean =
    settings.voiceBar.interactiveTyping() &&
        !secureField &&
        !voice.whisperNeedsModel &&
        voice.status != VoiceStatus.NEED_PERMISSION &&
        voice.status != VoiceStatus.UNAVAILABLE &&
        voice.status != VoiceStatus.ERROR

/**
 * The keys are drawing what the transliterator is about to write with them:
 * the layout transliterates (Avro, not Probhat), and the user left the hints
 * on. The one gate both sides share — the service asks it before mirroring the
 * roman buffer into [KeyboardUiState.composingRoman], and the grid asks it
 * before reading that mirror, so the two can never disagree about whether the
 * buffer is live.
 */
fun KeyboardUiState.transliterationHintsShown(): Boolean =
    composer.isTransliterating &&
        settings.layoutBehavior.transliterationHints != TransliterationHintMode.OFF

/**
 * [VoiceUi.bar] and [VoiceUi.barInline] following the persisted flags —
 * applied only when the stored values changed ([sync]), so a stale emission
 * cannot undo a live toggle.
 */
fun VoiceUi.withBarSynced(sync: Boolean, armed: Boolean, inline: Boolean): VoiceUi =
    if (!sync || (bar == armed && barInline == inline)) {
        this
    } else {
        copy(bar = armed, barInline = inline)
    }

/**
 * Everything the voice surfaces ask of the service beyond the dedicated
 * callbacks, multiplexed over the one `onVoiceRailKey` slot: KeyboardScreen's
 * caller sits against the JVM's 64K method-size ceiling, so a new command
 * grows this type rather than the parameter list.
 */
sealed interface VoiceBarAction {
    /** A rail/bar key press routed through the dictation-safe path. */
    data class RailKey(val key: Key) : VoiceBarAction

    /** The collapsed bar should stand upright against a screen edge. */
    data class SetVertical(val vertical: Boolean) : VoiceBarAction

    /** The bar settled after a drag: its whole resting place, persisted at once. */
    data class SetRest(
        val snap: Int,
        val rightEdge: Boolean,
        val yBias: Float,
        val dockBias: Float,
    ) : VoiceBarAction

    /**
     * Inline switch between the voice surfaces: the panel's and strip's
     * collapse buttons pass MODE_BAR, the bar's expand button passes the
     * stored return mode. Persists as the new default — that is the point:
     * this one setting changes without a trip to the settings app.
     */
    data class SwitchSurface(val mode: String) : VoiceBarAction

    /**
     * The collapsed bar's on-screen rectangle in window coordinates — the
     * service's touchable region, so touches beside the bar reach the app.
     */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) : VoiceBarAction
}

/**
 * Every keyboard-geometry change the UI asks of the service, multiplexed over
 * the one sizing slot for the same 64K reason as [VoiceBarAction]: the slot
 * used to be `onFloatingResized(widthDp, heightScale)`, and the inline resize
 * tool grew this type rather than the parameter list.
 */
sealed interface SizingAction {
    /** The floating keyboard's resize grip settled (was onFloatingResized). */
    data class Floating(val widthDp: Int, val heightScale: Float) : SizingAction

    /**
     * The inline resize tool's Done: stored-space values for [variant]. A null
     * field means "unchanged since the tool opened" and must not be written —
     * an untouched key height keeps the tablet defaults alive.
     */
    data class ResizeCommit(
        val variant: ScreenVariant,
        val keyHeightDp: Int?,
        val numberRowHeightDp: Int?,
        val bottomPaddingDp: Int?,
        /** The edge pads (issue #82), as fractions of the window width. */
        val sidePadLeftScale: Float? = null,
        val sidePadRightScale: Float? = null,
    ) : SizingAction

    /** Leave the inline resize tool without persisting anything. */
    data object ResizeCancel : SizingAction
}

/** One change made from the password generator panel (all persisted). */
sealed interface PwSettingAction {
    data class PassphraseMode(val on: Boolean) : PwSettingAction
    data class Length(val value: Int) : PwSettingAction
    data class Upper(val on: Boolean) : PwSettingAction
    data class Digits(val on: Boolean) : PwSettingAction
    data class Symbols(val on: Boolean) : PwSettingAction
    data class ExcludeAmbiguous(val on: Boolean) : PwSettingAction
    data class Words(val value: Int) : PwSettingAction
    data class Separator(val value: String) : PwSettingAction
    data class Capitalize(val on: Boolean) : PwSettingAction
    data class IncludeDigit(val on: Boolean) : PwSettingAction
}

/** One change made from the sound & haptics quick panel. */
sealed interface SoundHapticAction {
    data class Haptics(val on: Boolean) : SoundHapticAction
    data class HapticStyleChange(val style: com.wasimaster.wmkeyboard.core.settings.HapticStyle) : SoundHapticAction
    data class HapticAmplitude(val amplitude: Int) : SoundHapticAction
    data class HapticDuration(val durationMs: Int) : SoundHapticAction
    data class Sound(val on: Boolean) : SoundHapticAction
    data class SoundStyleChange(val style: com.wasimaster.wmkeyboard.core.settings.KeySoundStyle) : SoundHapticAction
    data class SoundVolume(val volume: Float) : SoundHapticAction
}

/** GIF / sticker panel state, owned by the service (it does the fetching). */
sealed interface MediaUi {
    /** No GIF-provider key in the build and none pasted into settings. */
    data object NeedKey : MediaUi
    data object Loading : MediaUi
    data class Error(val message: String) : MediaUi

    /**
     * Held back by data saving. [canAllow] is the difference between the two
     * answers the user can have given: asking still offers the fetch on the
     * panel, turning it off does not offer anything, because they already
     * said no to this one.
     */
    data class Metered(val canAllow: Boolean) : MediaUi
    /** [query] is what produced [items]; blank means featured/trending. */
    data class Ready(
        val items: List<com.wasimaster.wmkeyboard.core.tools.GifItem>,
        val query: String,
    ) : MediaUi
}

/** Web search panel state. */
sealed interface WebSearchUi {
    data object NeedKey : WebSearchUi
    /** Key present, nothing searched yet. */
    data object Idle : WebSearchUi
    data object Loading : WebSearchUi
    data class Error(val message: String) : WebSearchUi
    /** Held back by data saving; see [MediaUi.Metered]. */
    data class Metered(val canAllow: Boolean) : WebSearchUi
    data class Ready(
        val results: List<com.wasimaster.wmkeyboard.core.tools.WebResult>,
        val query: String,
    ) : WebSearchUi
}

/** Image search panel state. */
sealed interface ImageSearchUi {
    data object NeedKey : ImageSearchUi
    data object Idle : ImageSearchUi
    data object Loading : ImageSearchUi
    data class Error(val message: String) : ImageSearchUi
    /** Held back by data saving; see [MediaUi.Metered]. */
    data class Metered(val canAllow: Boolean) : ImageSearchUi
    data class Ready(
        val results: List<com.wasimaster.wmkeyboard.core.tools.ImageResult>,
        val query: String,
    ) : ImageSearchUi
}

/**
 * Translate panel state. The panel is its own little window: what gets
 * translated is the query typed into it ([KeyboardUiState.mediaQuery], the
 * same key-rerouting trick as the media panels) — the focused field is
 * never read.
 */
data class TranslateUi(
    /** The typed query the current [translated] corresponds to. */
    val sourceText: String = "",
    val translated: String = "",
    /** ISO code of the auto-detected source language, "" while unknown. */
    val detectedSource: String = "",
    val translating: Boolean = false,
    val error: String? = null,
)

/**
 * Grammar strip state. Like translate, the strip follows the focused field:
 * the service re-extracts and re-lints (debounced) on every change while the
 * panel is open. All linting is offline via the bundled Harper engine.
 */
data class GrammarUi(
    /** Field text the current [lints] correspond to. */
    val sourceText: String = "",
    val lints: List<com.wasimaster.wmkeyboard.core.grammar.GrammarLint> = emptyList(),
    val checking: Boolean = false,
    /** False until the first check of the current field completes. */
    val checkedOnce: Boolean = false,
    /** Native Harper library present in this build. */
    val available: Boolean = true,
)

/** Wikipedia panel state, owned by the service (it does the fetching). */
sealed interface WikiUi {
    /** Nothing searched yet — the panel opens into its search box. */
    data object Idle : WikiUi
    data object Loading : WikiUi
    data class Error(val message: String) : WikiUi
    data class SearchResults(
        val results: List<com.wasimaster.wmkeyboard.core.tools.WikipediaClient.SearchResult>,
        val query: String,
    ) : WikiUi
    /**
     * One article: summary always present; [links] and [fullText] load
     * lazily when their tab is first opened. [canGoBack] returns to the
     * search results this article came from.
     */
    data class Article(
        val summary: com.wasimaster.wmkeyboard.core.tools.WikipediaClient.Summary,
        val links: List<String>? = null,
        val fullText: String? = null,
        val loadingExtra: Boolean = false,
        val canGoBack: Boolean = false,
    ) : WikiUi
}

/** Currency converter state; rates are cross-computed from one USD table. */
sealed interface CurrencyUi {
    data object Loading : CurrencyUi
    data class Error(val message: String) : CurrencyUi
    data class Ready(
        val rates: com.wasimaster.wmkeyboard.core.tools.CurrencyClient.Rates,
        val fetchedAtMs: Long,
        /** Coins run on their own, much shorter clock than the fiat table. */
        val cryptoFetchedAtMs: Long = 0L,
        /** The last coin fetch failed, so coin chips give up rather than spin. */
        val cryptoFailed: Boolean = false,
    ) : CurrencyUi
}

/** AI tool state, owned by the service (it makes the provider request). */
sealed interface AiUi {
    /** Selected provider has no key/URL configured yet. */
    data object NeedSetup : AiUi
    /** On-device provider selected but no model downloaded/selected. */
    data object NeedModel : AiUi
    data object Idle : AiUi
    /**
     * An action whose prompt is typed each run is picked, and the user is
     * typing that instruction on the key rows. [instruction] is the live buffer
     * — keystrokes edit it instead of the field until they run it.
     */
    data class CustomInput(
        val action: com.wasimaster.wmkeyboard.core.tools.AiActionSpec,
        val instruction: String = "",
    ) : AiUi
    data class Loading(
        val action: com.wasimaster.wmkeyboard.core.tools.AiActionSpec,
        /**
         * Which step the request is on. [AiPhase.THINKING] is the old
         * "a reasoning model is inside a `<think>` block" case.
         */
        val phase: AiPhase = AiPhase.PREPARING,
        /**
         * Reasoning characters received so far. Shown while [phase] is
         * [AiPhase.THINKING] — a number that climbs is the only evidence a
         * silent reasoning model is alive rather than hung.
         */
        val thinkingChars: Int = 0,
        /**
         * Clock start ([android.os.SystemClock.uptimeMillis]) for the elapsed
         * readout, so a slow provider looks slow instead of broken.
         */
        val startedAtMs: Long = 0L,
    ) : AiUi
    data class Ready(
        val action: com.wasimaster.wmkeyboard.core.tools.AiActionSpec,
        val result: String,
        /** Text the action ran on, for the retry button. */
        val sourceText: String,
        /**
         * The instruction this run used, for an action whose prompt is typed
         * each time. Carried here so a retry rebuilds the same prompt.
         */
        val instruction: String = "",
        /**
         * This run wrote from nothing: the field was empty, so the instruction
         * was the whole request rather than something to do to text. Also
         * carried for the retry, which would otherwise turn "write a haiku"
         * into an instruction to rewrite the haiku it just wrote.
         */
        val generated: Boolean = false,
        /** True while an on-device response is still streaming in. */
        val generating: Boolean = false,
        /**
         * Strip markdown syntax before showing/committing the result. Only
         * surfaced as a checkbox when the result actually contains markdown.
         */
        val stripMarkdown: Boolean = true,
        /**
         * The provider stopped writing because it ran out of tokens, so this
         * answer is cut off. Reported by the provider itself, not guessed from
         * the text: without it a truncated answer looks exactly like a finished
         * one, and the user finds out by reading their own half-rewritten text.
         */
        val truncated: Boolean = false,
        /** The panel is showing the comparison, not the plain result. */
        val showDiff: Boolean = false,
        /**
         * A comparison against [sourceText] means something for this run.
         *
         * False for an action that adds to the text rather than replacing it
         * (its source is only what came before), and for a run that wrote from
         * nothing (its "source" is the instruction, not text to compare with).
         * The panel cannot tell the second case apart on its own, so the
         * service sets this.
         */
        val diffable: Boolean = true,
    ) : AiUi
    data class Error(
        val action: com.wasimaster.wmkeyboard.core.tools.AiActionSpec,
        val message: String,
    ) : AiUi
}

/** Weather panel state, owned by the service (it does the fetching). */
sealed interface WeatherUi {
    /** No location configured in the weather tool's settings. */
    data object NoLocation : WeatherUi
    data object Loading : WeatherUi
    data object Error : WeatherUi
    data class Ready(val info: WeatherInfo) : WeatherUi
}

/** Dictionary panel state, owned by the service (it does the fetching). */
sealed interface DictionaryUi {
    /** Nothing looked up yet (no word at the cursor, or auto-lookup off). */
    data object Idle : DictionaryUi
    data class Loading(val word: String) : DictionaryUi
    data class Error(val word: String) : DictionaryUi
    /** The API knows no entry for this word. */
    data class NotFound(val word: String) : DictionaryUi
    data class Ready(val entries: List<com.wasimaster.wmkeyboard.core.tools.DictEntry>) : DictionaryUi
}

/**
 * What the enter key does in the focused field, from EditorInfo.imeOptions.
 *
 * [CUSTOM] is the app supplying its own [android.view.inputmethod.EditorInfo.actionLabel]
 * — the key draws that label as text and fires the app's
 * [android.view.inputmethod.EditorInfo.actionId] rather than a standard action.
 */
enum class EnterAction { DEFAULT, SEARCH, SEND, GO, NEXT, PREVIOUS, DONE, CUSTOM }

/**
 * Typing-speed panel state. The prompt and the typed text live here rather
 * than in the panel so the service can run the clock and the per-second
 * sampler against them; the panel is a pure rendering of this.
 *
 * The run has three phases, told apart without a separate enum: [result]
 * non-null means finished, otherwise [startedAtMs] non-null means running,
 * otherwise the test is armed and waiting for the first keystroke.
 */
data class TypingTestUi(
    val words: List<String> = emptyList(),
    /** Finished words, in prompt order — what the user actually typed for each. */
    val typedWords: List<TypedWord> = emptyList(),
    /**
     * The word being typed right now (the caret is at its end), as it reads
     * on screen. On a transliterating layout (Avro) this is the composed
     * script text; the Latin keystrokes behind it are [buffer].
     */
    val current: String = "",
    /**
     * The raw keystrokes of the current word. Equal to [current] on a
     * layout that types letters directly; the romanised input on one that
     * transliterates, where backspace removes one keystroke rather than one
     * composed character.
     */
    val buffer: String = "",
    /** The language the prompt was dealt in — also the personal-best key's suffix. */
    val languageId: String = "en",
    /**
     * True when the language has no word list to deal a prompt from: nothing
     * shipped, nothing downloaded, nothing imported. The panel explains
     * instead of showing an empty prompt.
     */
    val unavailable: Boolean = false,
    /** Word suggestions for [current], when the suggestions option is on. */
    val suggestions: List<String> = emptyList(),
    /** Elapsed-time clock start; null until the first keystroke arms it. */
    val startedAtMs: Long? = null,
    /** Milliseconds elapsed, refreshed by the service's ticker. */
    val elapsedMs: Long = 0,
    /** Every character key pressed, corrections included. */
    val totalKeystrokes: Int = 0,
    /** …of which landed on the right letter first time. */
    val correctKeystrokes: Int = 0,
    /**
     * Keystrokes spent on the word being typed, on a transliterating layout
     * where none of them can be judged as it lands: Avro's "k" is ক on its
     * way to খ. They are settled into [correctKeystrokes] when the word
     * closes. Always zero on a layout that types letters directly, which
     * scores each key as it is pressed.
     */
    val pendingKeystrokes: Int = 0,
    val samples: List<WpmSample> = emptyList(),
    /** Non-null once the run is over; the panel switches to the results view. */
    val result: TypingResult? = null,
    /** Set when the finished run beat the stored best for its config. */
    val personalBest: Boolean = false,
    /** Achievement badges this run unlocked for the first time. */
    val earnedAchievements: Set<String> = emptySet(),
) {
    val running: Boolean get() = startedAtMs != null && result == null
    /** Index of the word the caret is in. */
    val wordIndex: Int get() = typedWords.size
}

/** One control on the typing-speed panel. */
sealed interface TypingTestAction {
    /** Throw the run away and deal a fresh prompt with the same settings. */
    data object Restart : TypingTestAction
    data class Mode(val value: TypingTestMode) : TypingTestAction
    data class Duration(val seconds: Int) : TypingTestAction
    data class WordCount(val value: Int) : TypingTestAction
    data class Punctuation(val on: Boolean) : TypingTestAction
    data class Numbers(val on: Boolean) : TypingTestAction
    /** Allow glide gestures during a run. */
    data class Glide(val on: Boolean) : TypingTestAction
    /** Show word suggestions during a run. */
    data class Suggestions(val on: Boolean) : TypingTestAction
    /**
     * Switch the test — and the keyboard under it — to the layout with this
     * id. The panel picks a language; the layout is how the keyboard is told,
     * since the prompt has to be typed on that language's keys.
     */
    data class Language(val layoutId: String) : TypingTestAction
    /** A suggestion chip tapped: finishes the current word with [word]. */
    data class Suggestion(val word: String) : TypingTestAction
    /** Write the finished run's score into the field the user came from. */
    data object InsertResult : TypingTestAction
}

/**
 * A snippet whose trigger matched while [Snippet.confirm] is set: it is shown
 * as a chip on the suggestion strip and inserts nothing until it is tapped.
 *
 * [text] is already expanded — variables, capture groups and all — because it
 * is what the chip shows, and the promise of an offer is that tapping it gives
 * you what you were looking at. Nothing here is re-derived at the tap.
 *
 * [consumed] is what accepting takes back out. [composed] says whether that is
 * exactly the word still being composed, in which case committing over it
 * replaces it for free, or a span of the field — a pattern reaches back across
 * words the editor has long since taken, and only its tail is still composing.
 */
data class SnippetOffer(
    val id: Long,
    val label: String,
    val text: String,
    /** Where a `{cursor}` marker asked the caret to land inside [text]. */
    val cursorOffset: Int,
    val consumed: String,
    val composed: Boolean,
)

/** Which question the strip's snippet chips are asking. */
enum class SnippetOfferKind {
    /**
     * Nothing was inserted and one of these chips has to be tapped for anything
     * to happen. A snippet told to ask first, or one with several expansions
     * while the app is set to show them as chips.
     */
    PICK,

    /**
     * The first expansion is already in the field and these chips replace it.
     * The text is good as it stands; the chips are the rest of the answer.
     */
    SWAP,
}

/** One thing the strip is offering, and where the caret goes if it is taken. */
data class SnippetChip(
    /** The snippet the text belongs to: the matched one, or one it links to. */
    val snippetId: Long,
    val label: String,
    val text: String,
    /** Where a `{cursor}` marker asked the caret to land inside [text]. */
    val cursorOffset: Int,
    /** True when tapping into that linked snippet would show more. */
    val drillable: Boolean,
)

/**
 * Every snippet chip on the strip at once, and everything a tap on one of them
 * needs to know.
 *
 * One field rather than several because the strip has exactly one snippet
 * answer at a time, whether that answer has one part or nine: the alternative
 * is four parallel nullable fields that can disagree with each other, and a
 * keyboard screen that already sits at the method-size limit.
 *
 * [chips] is what the strip draws, which is [rootChips] until the user taps
 * into a linked snippet, and that snippet's own offering after. [path] is how
 * deep they have gone, ids in order, and doubles as the visited set that stops
 * a cycle from being walked forever.
 */
data class SnippetOfferSet(
    val kind: SnippetOfferKind,
    /** The snippet whose trigger matched. */
    val rootId: Long,
    val rootLabel: String,
    /** PICK: what taking a chip takes back out of the field. */
    val consumed: String = "",
    /** PICK: whether [consumed] is exactly the word still being composed. */
    val composed: Boolean = false,
    /** SWAP: what the expansion put in the field, so a swap can find it again. */
    val inserted: String = "",
    /** SWAP: where the caret was parked inside [inserted]. */
    val insertedCaret: Int = 0,
    /** SWAP: what the expansion replaced, so a swap can keep the undo honest. */
    val original: String? = null,
    /** SWAP: which of [rootChips] is in the field now, or -1 after a drill. */
    val current: Int = 0,
    val rootChips: List<SnippetChip> = emptyList(),
    val path: List<Long> = emptyList(),
    val chips: List<SnippetChip> = rootChips,
) {

    /**
     * The chips to draw. At the top of a SWAP set the one already in the field
     * is left out: offering to replace the text with what it already says is
     * not an offer.
     */
    fun visibleChips(): List<SnippetChip> =
        if (kind == SnippetOfferKind.SWAP && path.isEmpty() && current >= 0) {
            chips.filterIndexed { index, _ -> index != current }
        } else {
            chips
        }

    /** True for exactly the one ask-first chip the strip has always shown. */
    fun isSingle(): Boolean =
        kind == SnippetOfferKind.PICK && path.isEmpty() && rootChips.size == 1

    /** True when tapping into [id] would go somewhere it has not already been. */
    fun canDrill(id: Long): Boolean = id != rootId && id !in path

    /** The id one level up, or null at the top. */
    fun parentId(): Long? = if (path.size < 2) null else path[path.size - 2]
}

/**
 * What a tap on one of the strip's offer chips was.
 *
 * A sealed type rather than the boolean the strip used to send, because there
 * are now four answers and up to nine chips asking them, and the keyboard
 * screen cannot afford another parameter.
 */
sealed interface StripOfferAction {
    /** Take the chip at [index]. The learn-word chip and single offers use 0. */
    data class Accept(val index: Int = 0) : StripOfferAction

    /** Refuse the offer. Only the learn-word chip asks this. */
    data object Decline : StripOfferAction

    /** Show what the linked snippet behind the chip at [index] offers. */
    data class Drill(val index: Int) : StripOfferAction

    /** Go back up one level of [SnippetOfferSet.path]. */
    data object Back : StripOfferAction
}

/**
 * The snippets panel showing what one snippet offers, after a tile was held.
 *
 * [rows] are drawn instead of the tile grid, and [path] tracks a walk into
 * linked snippets exactly as [SnippetOfferSet.path] does on the strip.
 */
data class SnippetPickerUi(
    val rootId: Long,
    val title: String,
    val rows: List<SnippetChip> = emptyList(),
    val path: List<Long> = emptyList(),
) {
    /** True when tapping into [id] would go somewhere it has not already been. */
    fun canDrill(id: Long): Boolean = id != rootId && id !in path
}

/** What kind of dictionary a [DictionaryChip] stands for. */
enum class DictionaryKind {
    /** The language's shipped or downloaded word list, as one unit. */
    WORDS,
    /** The language's emoji keyword packs (download plus imports), as one unit. */
    EMOJI,
    /** One imported word list, switched by renaming its file. */
    IMPORTED,
}

/**
 * One dictionary on the device, as the dictionary bar (issue #51) lists it.
 *
 * Built by the service from the file system and the settings, never by the
 * renderer: which lists exist is a disk walk, and which are on is spread over
 * three stores (a settings set for word lists, another for emoji packs, a file
 * suffix for imports). The chip carries the answer, and its tap goes back to
 * the service to be applied wherever it lives.
 */
data class DictionaryChip(
    val langId: String,
    val kind: DictionaryKind,
    /** The imported list's file name, for [DictionaryKind.IMPORTED]; empty otherwise. */
    val fileName: String = "",
    /** The label drawn for an imported list; null for the two built-in kinds, which the strip names itself. */
    val label: String? = null,
    val enabled: Boolean = true,
)

/**
 * Immutable UI state rendered by the Compose keyboard. The service owns a
 * MutableStateFlow of this and mutates it via copy().
 */
data class KeyboardUiState(
    val settings: KeyboardSettings = KeyboardSettings(),
    /**
     * A physical keyboard is attached and not folded away. Drives the
     * toolbar-only view when [ToolbarBehavior.onlyWithHardwareKeyboard]
     * is on; updated from the service's configuration.
     */
    val hardwareKeyboardPresent: Boolean = false,
    /** The active layout's language — dictionary, dictation, script behaviour. */
    val language: LanguageDef = LanguageRegistry.byId("en"),
    /** The active language's script — direction, case, composer, font. */
    val script: ScriptDef = ScriptRegistry[ScriptId.LATIN],
    /** How keystrokes compose for the active layout (Avro transliteration, Indic clusters). */
    val composer: Composer = NoComposer,
    /** The layout being typed on; what the 🌐 cycle steps through. */
    val layoutId: String = BuiltInLayouts.DEFAULT_ID,
    /**
     * The layout's display name, for the spacebar label. Carried on the state
     * rather than looked up, because a custom layout has no [InputMode] of its
     * own to name it by — two layouts can share a base mode and still deserve
     * different labels.
     */
    val layoutName: String = "",
    /**
     * Key grids for the focused field, resolved from the user's layout choice.
     * Defaults to the shipped grids so a state built before the first resolution
     * — unit tests, the very first frame — still renders a working keyboard.
     */
    val layouts: LayoutSet = LayoutSet.Default,
    val layoutMode: LayoutMode = LayoutMode.LETTERS,
    /**
     * The secondary layout [LayoutMode.SECONDARY] shows, as a key into
     * [LayoutSet.secondaries]. Kept when the mode changes, so an Fn spring-back
     * returns to the same grid; an id the set no longer holds draws the letters.
     */
    val secondaryLayoutId: String? = null,
    /**
     * Power saving is in force, from either source: the manual switch or an
     * automatic trigger (low battery, the system's own battery saver).
     *
     * Purely for the indicator and the tool's lit state — [settings] has
     * already had the dropped features taken out of it, so nothing gates on
     * this. See `core.settings.underPowerSaving`.
     */
    val powerSavingOn: Boolean = false,
    /**
     * Data saving as it stands: whether it is in force, the policies it is
     * applying, and what the user has already allowed this session.
     *
     * Unlike [powerSavingOn] this is not just an indicator. The features it
     * covers are moments rather than settings — a search just run, a download
     * just started — so the panels read it to decide between fetching,
     * explaining, and offering. See `core.settings.DataSaverStatus`.
     */
    val dataSaver: DataSaverStatus = DataSaverStatus(),
    val shiftState: ShiftState = ShiftState.OFF,
    /**
     * The live [shiftState] came from the user pressing shift, not from
     * auto-capitalize arming it at a sentence start.
     *
     * The two are indistinguishable in [shiftState] itself, and they mean
     * opposite things to the one feature that asks: Shift+Enter's newline
     * override ([LayoutBehaviorSettings.shiftEnterNewline]). An empty chat box
     * arms auto-cap *and* is exactly where Enter must still send, so acting on
     * the state alone would stop the first line of every message from sending.
     */
    val shiftPressedByUser: Boolean = false,
    /**
     * Ctrl/Alt/Meta latches waiting to be folded into the next key event.
     * Session state, never persisted: a Ctrl left locked in one app must not
     * follow the user into the next one and turn their typing into shortcuts.
     */
    val modifiers: Modifiers = Modifiers.None,
    /**
     * The physical keyboard's leader was pressed and the next letter opens a
     * tool. Session state beside [modifiers] for the same reason: an armed
     * picker must not survive the field it was armed in.
     */
    val toolPicker: ToolPickerState? = null,
    /**
     * A hardware language switch in progress, or null. Session state for the
     * same reason as [toolPicker]: a browse must not survive the field it
     * started in.
     */
    val languageSwitch: LanguageSwitchState? = null,
    /**
     * The inline resize tool is open over the docked keyboard. Session state
     * like [toolPicker]: nothing persists until Done, so an abandoned session
     * (field change, rotation) leaves the stored sizing exactly as it was.
     */
    val resize: Boolean = false,
    /**
     * Where the hardware focus ring is inside the open panel, or null for touch
     * mode. Stays null until the user presses the leader, an arrow or Tab, so
     * the ring is only ever an answer to a keystroke.
     */
    val panelFocus: PanelFocus? = null,
    /**
     * Layer the Fn key was pressed from, so a one-shot Fn springs back to where
     * the user was rather than always to the letters. Null off the Fn layer.
     */
    val fnReturn: LayoutMode? = null,
    /** Fn was double-tapped: the layer holds until Fn or ABC is tapped. */
    val fnLocked: Boolean = false,
    val panel: PanelMode = PanelMode.NONE,
    /**
     * The grid each editable panel draws (issue #63): the user's own layout or
     * the shipped one, already repaired. Defaults to the shipped set so a
     * state built before the first emission — unit tests, the first frame —
     * still draws every panel.
     */
    val panelLayouts: Map<PanelKind, PanelLayoutSpec> = BuiltInPanelLayouts.byKind,
    /**
     * Conversion candidates beyond the handful the strip has room for, filled
     * only while [PanelMode.CANDIDATES] is open. The strip's own list stays in
     * [suggestions]; this is a widening of it, so the two agree on ordering.
     */
    val expandedCandidates: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /** Missing-space join offer ("some" + "thing" -> "something"), shown as
     * a distinct leading chip; tapping rewrites the committed text. */
    val joinSuggestion: String? = null,
    /**
     * Replacement for the word before the one just committed, when its
     * follower proves it a classic confusable slip ("their going" →
     * "they're"). Chip-only, like [joinSuggestion]: tapping rewrites the
     * committed word; nothing applies on its own.
     */
    val revisionSuggestion: String? = null,
    /**
     * A correction that came close to firing on the word just committed, but
     * not close enough to apply behind the user's back. Offered on the same
     * rewrite-chip slot as [joinSuggestion] and [revisionSuggestion]; null
     * whenever the last commit had no near miss, or the chip is switched off.
     */
    val correctionOffer: String? = null,
    /** Spacing form of the dead-key accent waiting for a letter, if any. */
    val pendingDeadKey: String? = null,
    /**
     * The morse sequence tapped so far ("·−−"), shown in the strip while the
     * commit pause runs — the morse cousin of [pendingDeadKey]. Empty when
     * nothing is pending or the morse layout is not active.
     */
    val morsePending: String = "",
    /** True for a few seconds after the morse layout decodes S, O, S in a row. */
    val morseSosEgg: Boolean = false,
    /**
     * Chips from the system autofill service, already inflated by *its*
     * process — the keyboard only hosts them. Views rather than data because
     * that is the whole point: their contents are never exposed to the IME.
     * These take the whole strip while they are up.
     */
    val autofillChips: List<android.view.View> = emptyList(),
    /**
     * Smart replies and other platform-sourced chips off the same inline
     * suggestions API, inflated by Android System Intelligence rather than an
     * autofill service. Unlike [autofillChips] these share the row with word
     * candidates — a proposed reply is a suggestion, not an answer to the
     * field — so they ride the tail of the strip.
     */
    val smartReplyChips: List<android.view.View> = emptyList(),
    /** Best gesture-typing candidate mid-swipe, shown floating above the finger. */
    val glideWord: String? = null,
    /**
     * The words a mid-swipe decode is choosing between, best first, when it is
     * close enough to be worth asking about — and empty when it is not, which
     * is the ordinary case. Holding the finger still while this is populated
     * puts them under the fingertip to pick from.
     */
    val glideChoices: List<String> = emptyList(),
    /**
     * Whether the active language and layout can be glided: a word list to
     * decode against, a layout that types letters rather than converting them,
     * and keys covering enough of the language to decode honestly.
     *
     * Computed in the service rather than derived here, because two of the three
     * questions need the live word sources to answer and one of them (coverage)
     * is a measurement over the dictionary, not a property of the layout.
     */
    val glideReady: Boolean = false,
    val composingPreview: String = "",
    /**
     * The roman buffer [composingPreview] was transliterated from, mirrored
     * here for the key hints on a transliterating layout: what a key writes
     * depends on the letters already typed, so the grid has to know them
     * ([Composer.keyPreview]).
     *
     * Kept only while those hints are actually drawn — a layout that
     * transliterates, with the hint mode on ([transliterationHintsShown]) —
     * because it is a key of the grid's `remember`, and every keystroke that
     * moves it rebuilds all ~40 key bodies. Empty everywhere else, so no other
     * board pays for a feature it does not draw.
     */
    val composingRoman: String = "",
    /**
     * Probability weight (0..1) of each letter being typed next, given the
     * current composing word. Drives smart key-hit detection, which nudges
     * boundary taps toward the high-weight letters. Empty when the feature is
     * off, the field is not composing, or nothing is predicted.
     */
    val nextLetterBias: Map<Char, Float> = emptyMap(),
    /** Text-edit panel: arrows extend the selection instead of moving the cursor. */
    val textEditSelecting: Boolean = false,
    /**
     * The toolbar's Selection mode tool, switched on by a tap and off by the
     * next one.
     *
     * The same question as [textEditSelecting] with a longer life: the panel's
     * toggle belongs to the panel and dies when one opens or closes, while this
     * one is a mode the user armed from the bar and can see lit there, so it
     * survives panels, caret moves and everything else until it is tapped off
     * or the field changes. Read them together through [selectingText].
     */
    val selectionMode: Boolean = false,
    /**
     * Selection mode for as long as the finger stays on the tool: a press and
     * hold arms it, the release disarms it. Only the *extending* stops there.
     * Whatever was selected stays selected, because the selection lives in the
     * editor and nothing here collapses it.
     */
    val selectionHold: Boolean = false,
    val emojiQuery: String = "",
    val emojiSearchActive: Boolean = false,
    val emojiResults: List<EmojiEntry> = emptyList(),
    val emojiRecents: List<String> = emptyList(),
    val emojiFrequents: List<String> = emptyList(),
    val emojiFavourites: List<String> = emptyList(),
    /** Palette-grid base emoji → the skin-tone/gender variant it renders as. */
    val emojiVariantPrefs: Map<String, String> = emptyMap(),
    /**
     * Emoji display names from the installed keyword packs, keyed by the
     * language that names them and then by emoji. The long-press popup reads
     * the entry for the language being typed and falls back to the catalog's
     * Unicode name — names must not stack the way keywords do, since only one
     * can be shown and every enabled language has a pack downloaded for it.
     */
    val emojiNamesByLang: Map<String, Map<String, String>> = emptyMap(),
    /**
     * Which emoji have a Noto animated version, and where to fetch it. Read by
     * the long-press popup to decide whether to offer one.
     */
    val animatedEmoji: AnimatedEmoji = AnimatedEmoji.EMPTY,
    /**
     * The animated GIF the open long-press popup is showing, once it has been
     * fetched. Cleared when the popup closes, so a second long press on a
     * different emoji never previews the last one.
     */
    val animatedEmojiFile: File? = null,
    /** True while [animatedEmojiFile] is on its way down. */
    val animatedEmojiLoading: Boolean = false,
    /** Emoji candidates for the suggestion strip (word being typed). */
    val emojiSuggestions: List<String> = emptyList(),
    /**
     * [suggestions] holds inline emoji-search results (a ":tada" buffer), not
     * words. The strip draws them as a scrolling emoji row in the emoji font
     * rather than three text chips — a shortcode search that found a dozen
     * matches has nothing to say in three word-shaped slots.
     */
    val inlineEmoji: Boolean = false,
    /**
     * Common punctuation marks offered as quick-insert chips in the tail of
     * the suggestion strip, beside the word candidates. Non-empty only while
     * word candidates are up and no emoji prediction has claimed the tail;
     * gated on the "Punctuation suggestions" setting. Tapping one commits the
     * mark exactly as typing its key would.
     */
    val punctuationSuggestions: List<String> = emptyList(),
    /**
     * The tool answer recognised in the text before the cursor — an inline
     * sum, currency or unit conversion, or a tool keyword. Rendered as a
     * chip over the suggestion strip.
     */
    val smart: SmartSuggest.SmartHit? = null,
    /** Input a chip loaded into the tool it is about to open; consumed once. */
    val toolPrefill: ToolPrefill? = null,
    /**
     * The calculator's live expression. Service state, not panel-local, so a
     * physical keyboard can type into it through the same routing every other
     * keyboard-owned buffer uses. Cleared when the panel closes.
     */
    val calcExpression: String = "",
    /**
     * The unit/currency converters' typed amount — one buffer, since the two
     * panels never show together. Digits and one dot only; reset on close.
     */
    val converterValue: String = "1",
    val emojiCatalog: List<EmojiEntry> = emptyList(),
    /** RGI toned-sequence lookup; loaded once with the catalog. */
    val emojiVariants: EmojiVariantIndex = EmojiVariantIndex.empty(),
    /**
     * Emoji the active font can't draw, hidden from the panel, search and
     * suggestions while [KeyboardSettings.emoji] hideUnrenderable is on. Empty
     * when the feature is off (nothing is filtered).
     */
    val hiddenEmoji: Set<String> = emptySet(),
    val clipboardItems: List<ClipItem> = emptyList(),
    /** Live query for the clipboard panel's search bar (feature-gated). */
    val clipboardQuery: String = "",
    /** Typing edits [clipboardQuery] instead of the field, like emoji search. */
    val clipboardSearchActive: Boolean = false,
    /**
     * Most recently copied text, offered as a paste chip on the suggestion strip
     * (Gboard style). Null when nothing recent, the chip expired, was dismissed,
     * or the feature is off. Cleared on paste/dismiss/timeout.
     */
    val clipboardSuggestion: ClipItem? = null,
    /**
     * One-time code lifted from a just-arrived notification, offered as a chip
     * on the suggestion strip. Null when there is none, it expired, it was
     * used or dismissed, or the current field fails the feature's gates
     * (number-fields-only, incognito). The code exists only here and on the
     * in-process bus — it is never written anywhere.
     */
    val otpSuggestion: NotificationOtp? = null,
    /**
     * The snippet chips on the strip: a match waiting to be chosen from, or the
     * alternatives to one that has already been inserted. Null whenever no
     * trigger has anything to offer — see [SnippetOfferSet].
     */
    val snippetOffers: SnippetOfferSet? = null,
    /**
     * A word nothing recognises, just committed, waiting for the user to say
     * whether it belongs in their dictionary. Null unless "ask before
     * learning" is on and such a word is on the strip right now.
     */
    val learnOffer: String? = null,
    val snippets: List<Snippet> = emptyList(),
    /** The folders [snippets] are filed under, in the order the panel draws them. */
    val snippetFolders: List<SnippetFolder> = emptyList(),
    /**
     * The folder the snippets panel has been opened into, or null while it is
     * showing the folders themselves.
     *
     * Panel state rather than composition state on purpose: back has to leave
     * the folder before it closes the panel, and the hardware focus ring has to
     * know how many things the panel is currently drawing. Neither can see a
     * `remember` inside the panel body.
     */
    val snippetFolderOpen: Long? = null,
    /**
     * The picker a held snippet tile opened, or null while the panel is showing
     * tiles. Panel state for the same reason [snippetFolderOpen] is: back has to
     * leave it before it leaves the folder.
     */
    val snippetPicker: SnippetPickerUi? = null,
    /**
     * How many things each snippet has to offer, by id, for the tiles that say
     * so. Only ids with more than one are listed; the panel cannot reach the
     * store to count for itself.
     */
    val snippetCandidateCounts: Map<Long, Int> = emptyMap(),
    /**
     * MIME types the focused editor advertises for commitContent
     * (EditorInfo.contentMimeTypes). Empty means the field takes text only —
     * a GIF or sticker sent there can never arrive, so the media panels say so
     * up front instead of the send failing into a clipboard fallback.
     */
    val fieldContentMimeTypes: List<String> = emptyList(),
    val secureField: Boolean = false,
    /**
     * The device lock screen (keyguard) is showing. Refreshed on each field
     * start from [android.app.KeyguardManager]. Only the "hide toolbar &
     * clipboard on lock screen" privacy setting reads it.
     */
    val deviceLocked: Boolean = false,
    /** Field class/variation of the focused editor, from EditorInfo. */
    val fieldKind: FieldKind = FieldKind.TEXT,
    /**
     * The field asked the keyboard to hide the *suggestion strip*
     * (TYPE_TEXT_FLAG_NO_SUGGESTIONS, or an email/URI/filter/password
     * variation). Strip visibility only — autocorrect, phonetic composing
     * and learning are gated on [allowsTypingIntelligence] and gesture typing
     * on [allowsGestureTyping] instead, so a field that silences the strip
     * keeps all of those. The
     * "Suggestions in every field" setting can override this for text fields.
     */
    val fieldNoSuggestions: Boolean = false,
    /**
     * The focused editor asked not to be learned from
     * (IME_FLAG_NO_PERSONALIZED_LEARNING), which is what a Chrome incognito
     * tab and most private-browsing modes set. Session-scoped: it follows the
     * field, never the persisted [KeyboardSettings.incognito] switch.
     */
    val fieldIncognito: Boolean = false,
    /**
     * Id of the keyboard mode currently applied to [settings] (per-app /
     * per-field overrides), null when no mode matched. The Modes panel
     * highlights it; the toolbar's mode tool lights up while set.
     */
    val activeModeId: String? = null,
    /**
     * Symbol set picked from the row's chip this session, overriding the
     * (possibly mode-supplied) persisted choice; cleared on field switch.
     */
    val activeSymbolSetId: String? = null,
    /**
     * Fancy Text style picked from the style strip this session, for instant
     * chip response while the persisted write lands; cleared on field switch
     * (the tap persists immediately, so nothing is lost).
     */
    val activeFancyStyleId: String? = null,
    /**
     * Every dictionary the dictionary bar can offer, for every enabled
     * language, refreshed by the service whenever the settings or the files
     * behind them change. Empty while the bar is off, so nothing is walked
     * for a row nobody sees.
     */
    val dictionaryBar: List<DictionaryChip> = emptyList(),
    val enterAction: EnterAction = EnterAction.DEFAULT,
    /**
     * The app's own label for the enter key (EditorInfo.actionLabel), set
     * only when [enterAction] is [EnterAction.CUSTOM]. Drawn as text on the
     * key in place of an action icon.
     */
    val enterActionLabel: String? = null,
    /** Torch state, mirrored from CameraManager for the flashlight tool. */
    val torchOn: Boolean = false,
    val weather: WeatherUi = WeatherUi.NoLocation,
    val dictionary: DictionaryUi = DictionaryUi.Idle,
    /** Word in the dictionary panel's search field. */
    val dictionaryQuery: String = "",
    /** Typing edits [dictionaryQuery] (letters show under the panel). */
    val dictionarySearchActive: Boolean = false,
    /**
     * What the vowel keys should produce given the character before the
     * cursor: kar (া, ি …) after a consonant, the য়-glide (য়া, য়ে) after
     * a vowel, and the independent letter (আ, ই …) at a word start. Fixed
     * Bengali layouts use it for both the committed output and the
     * displayed key labels.
     */
    val vowelForm: BengaliGraphemes.VowelKeyForm = BengaliGraphemes.VowelKeyForm.INDEPENDENT,
    val handwriting: HandwritingUi = HandwritingUi(),
    val voice: VoiceUi = VoiceUi(),
    /**
     * Now-playing snapshot for the media-control tool, mirrored from the
     * active [android.media.session.MediaSession]. Null means nothing is
     * playing — or notification access hasn't been granted, which the panel
     * detects itself (like the voice panel checks the mic permission).
     */
    val mediaControl: MediaSnapshot? = null,
    /**
     * Whether the media tool is currently auto-pinned to the toolbar because
     * music is playing (see [KeyboardSettings.mediaControl]).
     *
     * A latch rather than `mediaControl?.playing`: the pin arms the first time
     * an allowlisted player actually plays, then stays through a pause so the
     * transport is still reachable to resume, and only clears when the session
     * goes away. Held here rather than derived at the toolbar so pausing from
     * the panel does not pull the panel's own button out from under the thumb.
     */
    val mediaPinned: Boolean = false,
    /** Query buffer for the GIF/sticker/web/image search panels. */
    val mediaQuery: String = "",
    /** While true, key presses type into [mediaQuery] and the key rows stay visible. */
    val mediaSearchActive: Boolean = false,
    /** Id of the GIF/sticker/image currently downloading for insert, for a cell spinner. */
    val mediaDownloadingId: String? = null,
    /**
     * How far that download has got, 0f..1f, or null while it can't be known —
     * before the first byte, and for any server that doesn't declare a size.
     * Null is what keeps the cell on its indeterminate spinner instead of a
     * ring stuck at nothing.
     *
     * Published in whole percents so a fast GIF costs a couple of dozen
     * recompositions of one cell, not one per 8 KB buffer.
     */
    val mediaDownloadProgress: Float? = null,
    /** Selected provider chip on the GIF/sticker panel (tabs mode). */
    val mediaSource: com.wasimaster.wmkeyboard.core.tools.GifSource =
        com.wasimaster.wmkeyboard.core.tools.GifSource.KLIPY,
    val gif: MediaUi = MediaUi.Loading,
    val sticker: MediaUi = MediaUi.Loading,
    /** The user's own sticker packs, mirrored for the sticker panel. */
    val stickerPacks: List<com.wasimaster.wmkeyboard.core.stickers.StickerPack> = emptyList(),
    /** Pack chip on the "My stickers" tab; null shows every pack. */
    val stickerPackId: String? = null,
    /** Long-pressed GIF or sticker cell, showing its action sheet. */
    val mediaAction: com.wasimaster.wmkeyboard.core.tools.GifItem? = null,
    /** Categories to browse in the GIF/sticker default view; empty hides the row. */
    val mediaCategories: List<com.wasimaster.wmkeyboard.core.tools.MediaCategory> = emptyList(),
    /** The category chip in effect, or null for trending and for a typed query. */
    val mediaCategory: String? = null,
    /** What the Plugins panel is showing: the list, a running plugin, or a failure. */
    val plugins: PluginPanelUi = PluginPanelUi.List(emptyList()),
    /**
     * Text in each of the running plugin's input widgets, by widget id.
     *
     * The host owns these, not the script. A plugin's `render()` describes a box
     * and the keyboard fills it, so the plugin is told the contents of its own
     * input and never sees a keystroke.
     */
    val pluginInputs: Map<String, String> = emptyMap(),
    /** Which plugin input the keys are typing into, or null when they go to the field. */
    val pluginFocusedInput: String? = null,
    val webSearch: WebSearchUi = WebSearchUi.Idle,
    val imageSearch: ImageSearchUi = ImageSearchUi.Idle,
    val translate: TranslateUi = TranslateUi(),
    val grammar: GrammarUi = GrammarUi(),
    val wiki: WikiUi = WikiUi.Idle,
    val currency: CurrencyUi = CurrencyUi.Loading,
    val ai: AiUi = AiUi.Idle,
    /**
     * The focused field holds something an AI action could run on (a selection,
     * or any field text). Kept beside [ai] rather than inside it so it survives
     * a finished result — with an empty field, re-running an action is
     * impossible but Replace/Insert of the result the user already has is not.
     * Refreshed only while the AI panel is open; stale otherwise, and nothing
     * else reads it.
     */
    val aiHasText: Boolean = false,
    val typingTest: TypingTestUi = TypingTestUi(),
    /** Launchable apps for the app-launcher panel; empty until first opened. */
    val launcherApps: List<LauncherApp> = emptyList(),
    /** First enumeration in flight — the panel shows its loading state. */
    val launcherLoading: Boolean = false,
    /** The app whose activity list is open, or null for the grid. */
    val launcherDetail: LauncherDetailUi? = null,
) {
    /**
     * Whether incognito is in force right now, from either source: the
     * persisted switch the user flips, or a field that asked for it. Every
     * gate and indicator reads this rather than the setting alone.
     */
    val incognitoOn: Boolean get() = settings.incognito || fieldIncognito

    /**
     * Whether an on-screen Enter right now would type a newline instead of
     * firing the field's Send/Go/Search action.
     *
     * Only the one-shot shift the user armed themselves counts. Caps lock is
     * excluded on purpose: it says the *letters* are upper case, and reading it
     * here would silently stop Enter from ever sending while it is on.
     *
     * Enter does not spend the shift the way a letter does, so this stays true
     * for every Enter until the shift goes down of its own accord — a shift put
     * up to break lines is being held as a modifier, and a message written over
     * three lines would otherwise send itself on the second Enter.
     */
    val softShiftForcesNewline: Boolean
        get() = shiftState == ShiftState.ON && shiftPressedByUser

    /**
     * Whether shift is up as a *selection* modifier right now, which is what the
     * toolbar's caret-moving tools read to decide between moving the cursor and
     * extending the selection — shift+arrow, the way a physical keyboard does it.
     *
     * Both shift layers count, the one-shot and the lock: the user asked for
     * either by pressing a key, and holding a lock to drag out a long selection
     * is the whole point of the lock. What does *not* count is a shift the
     * keyboard armed by itself — auto-capitalize at a sentence start, or a field
     * flagged all-caps, both of which leave [shiftPressedByUser] false. Those are
     * statements about the next letter's case, not about the caret, and acting on
     * them would turn an ordinary cursor tap into a silent selection that the
     * next keystroke overwrites.
     */
    val shiftSelectsText: Boolean
        get() = shiftState != ShiftState.OFF && shiftPressedByUser

    /**
     * Whether a caret move right now extends the selection instead of collapsing
     * it, from any of the three places that can ask for it: the text-editing
     * panel's own toggle, the toolbar's Selection mode, and a hold on that tool.
     *
     * Every caret path reads this one property — the panel's arrows, the
     * toolbar's cursor tools, the spacebar cursor swipe, the volume keys and a
     * layout's own arrow keys — so a mode armed in one place is in force in all
     * of them. Shift is deliberately not folded in: it is spent on the next
     * letter, so the tools that read it say so themselves (see [shiftSelectsText]).
     */
    val selectingText: Boolean
        get() = textEditSelecting || selectionMode || selectionHold

    /**
     * Whether a caret move made with the keyboard's own gestures — the spacebar
     * cursor swipe and the volume keys — extends the selection.
     *
     * Selection mode or a shift the user put up, either of which says "extend".
     * Shift is folded in here and not into [selectingText] because these paths
     * have the shift key on screen beside them, the same as the toolbar's cursor
     * tools ([shiftSelectsText]) and a layout's own arrow keys: holding a lock
     * and scrubbing the spacebar with the other thumb is shift+arrow, and the
     * gestures that ignored it were the only caret paths that did.
     */
    val caretExtendsSelection: Boolean
        get() = selectingText || shiftSelectsText

    /**
     * The action the Enter key should draw and fire, folding in the
     * Shift+Enter override so the key never promises a Send it will not do.
     */
    val effectiveEnterAction: EnterAction
        get() = if (settings.layoutBehavior.shiftEnterNewline && softShiftForcesNewline) {
            EnterAction.DEFAULT
        } else {
            enterAction
        }

    /**
     * Whether to run the full typing engine — autocorrect, apostrophe fixes,
     * phonetic (Avro) composing and lexicon learning. (Gesture typing has its
     * own, slightly wider gate: [allowsGestureTyping].) These
     * belong to prose entry, so they apply to plain text fields only and are
     * deliberately independent of [fieldNoSuggestions]: an app that hides the
     * suggestion strip (Instagram, Google Keep) must not also lose autocorrect
     * or the ability to type Bengali. Password fields ([secureField]) and
     * structured fields (email, URI, number, phone, date) opt out — a keypad
     * has no words to correct and an address should not be second-guessed.
     */
    val allowsTypingIntelligence: Boolean
        get() = !secureField && fieldKind == FieldKind.TEXT

    /**
     * Whether a glide may be decoded and committed here. Wider than
     * [allowsTypingIntelligence] by one field kind: a URL bar is a search box
     * as much as an address box, and a browser's omnibox is typed
     * with a swipe as often as a chat is (#37). The rest of the engine stays
     * shut in it — the word is not learned, not autocorrected and not
     * second-guessed — and the space the glide types after itself comes back
     * out under the URL separators ([swallowsAutoSpace]), so "example" then
     * "/" lands as "example/". Email fields stay out: an address has no
     * dictionary words in it to decode.
     */
    val allowsGestureTyping: Boolean
        get() = !secureField && (fieldKind == FieldKind.TEXT || fieldKind == FieldKind.URI)

    /**
     * Whether the focused field takes any image at all through commitContent.
     * False for an ordinary text box: nothing an image tool sends can land
     * there, so the GIF and sticker panels say so rather than letting a tap
     * dead-end in "image copied, paste it instead".
     */
    val acceptsRichMedia: Boolean
        get() = fieldContentMimeTypes.any {
            android.content.ClipDescription.compareMimeTypes(it, "image/*")
        }

    /** Whether the field advertises something [mimeType] can be committed as. */
    fun acceptsMediaMime(mimeType: String): Boolean =
        fieldContentMimeTypes.any {
            android.content.ClipDescription.compareMimeTypes(mimeType, it)
        }

    /**
     * Whether keystrokes belong to a typing test rather than to the text
     * field. True from the moment the panel opens — the run is armed before
     * the first letter — and false again once the result is up, so the
     * results screen leaves the keyboard usable.
     */
    val typingTestActive: Boolean
        get() = panel == PanelMode.TYPING_TEST && typingTest.result == null

    /** Keystrokes belong to the calculator's expression while its panel is up. */
    val calcTypingActive: Boolean
        get() = panel == PanelMode.CALCULATOR

    /** Keystrokes belong to the converters' amount while either panel is up. */
    val converterTypingActive: Boolean
        get() = panel == PanelMode.UNIT_CONVERT || panel == PanelMode.CURRENCY

    /**
     * Whether keystrokes belong to the AI Custom-action instruction rather
     * than to the text field — true while its input box is up, so the key
     * rows compose the prompt instead of typing into the app behind.
     */
    val aiCustomInputActive: Boolean
        get() = panel == PanelMode.AI && ai is AiUi.CustomInput

    /**
     * Whether keystrokes belong to a plugin's own text box rather than to the
     * text field.
     *
     * This is the single most safety-critical property in this file. While it is
     * true, what the user types goes to a plugin instead of to the app they are
     * writing in — so it has to become false the instant the panel closes, the
     * field changes, or the keyboard hides, and the runtime is torn down at the
     * same moment so there is nothing left to receive anything. Requiring the
     * panel *and* a focused widget means neither condition alone can leak.
     */
    val pluginTypingActive: Boolean
        get() = panel == PanelMode.PLUGINS && pluginFocusedInput != null

    /**
     * Whether some buffer inside the keyboard owns the keys, so nothing typed
     * may reach the app behind it.
     *
     * The same set the typed-text path branches on, named once. Anything that
     * bypasses that path — the Keyman rule engine writes to the field directly —
     * has to ask this first, and asking a single property is the only way that
     * question stays answered the same way in both places. The two existing
     * copies of this list have already drifted apart once.
     */
    val keysTakenByKeyboard: Boolean
        get() = typingTestActive || calcTypingActive || converterTypingActive ||
            aiCustomInputActive || pluginTypingActive || emojiSearchActive

    /**
     * The item a panel should ring in [region], or null when the ring is
     * elsewhere or absent. Lets a panel ask `state.focusedIndex() == i` without
     * unpacking [panelFocus] itself.
     */
    fun focusedIndex(region: FocusRegion = FocusRegion.RESULTS): Int? =
        panelFocus?.takeIf { it.region == region }?.index

    /**
     * The snippet folder the panel is inside, or null at its top level.
     *
     * Resolved rather than stored, so a folder deleted in settings while the
     * panel sits inside it drops the panel back to the folder list instead of
     * leaving it looking at a name that no longer exists.
     */
    fun openSnippetFolder(): SnippetFolder? =
        snippetFolderOpen?.let { id -> snippetFolders.firstOrNull { it.id == id } }

    /**
     * The snippets the panel lists right now: a folder's contents when it is
     * open, otherwise the ones filed in no folder at all.
     *
     * With no folders anywhere this is every snippet, which is what makes the
     * panel identical to its pre-folder self for anyone who never makes one.
     */
    fun snippetsShown(): List<Snippet> = when {
        snippetFolders.isEmpty() -> snippets
        openSnippetFolder() != null -> snippets.filter { it.folderId == snippetFolderOpen }
        else -> snippets.filter { it.folderId == 0L }
    }
}

/**
 * What the Plugins panel is showing.
 *
 * The panel has two screens in one: the installed list, and whichever plugin is
 * running. Keeping that in the state rather than in composable-local memory is
 * what lets the service tear a plugin down — on panel close, on uninstall, on a
 * runaway script — and have the panel follow it back to the list.
 */
sealed interface PluginPanelUi {

    /** The installed plugins, ready to be opened. */
    data class List(
        val plugins: kotlin.collections.List<
            com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin,
            >,
        /** Set after a plugin was stopped, so the list can say why. */
        val notice: String? = null,
    ) : PluginPanelUi

    /** A plugin drawing its own UI. */
    data class Running(
        val plugin: com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin,
        val ui: com.wasimaster.wmkeyboard.core.plugins.RenderedUi =
            com.wasimaster.wmkeyboard.core.plugins.RenderedUi.EMPTY,
        /** A call is taking long enough to deserve a spinner. */
        val busy: Boolean = false,
        /** The script failed; the plugin is still loaded and can be retried. */
        val error: String? = null,
    ) : PluginPanelUi
}

/**
 * The characters one press of a key writes, lowercased, or null when the key
 * does not write a word character at all.
 *
 * Usually this is one character and the function is a formality. It is not a
 * formality wherever a key is stored the way Unicode also allows — a base letter
 * followed by a combining mark — so that one press writes *two* characters.
 * Anything that indexes the grid per character has to know that, or the whole key
 * vanishes from it: that is what once put every word containing য (214 of
 * Bengali's 1500 commonest, since the plain letter rides on that key's shift) out
 * of a glide's reach.
 *
 * The shipped Bengali layouts no longer spell ড় ঢ় য় that way — they are
 * precomposed now, and [decomposedKeyChars] is what keeps those keys reachable
 * from a word list that spells them the other way — but an imported or
 * hand-edited layout is still free to, and several scripts compose this way.
 *
 * A trailing run of combining marks counts as part of the character; anything
 * longer does not. The bound matters — a key whose label is a whole word, which
 * a custom layout is free to have, must not scatter its letters across the grid
 * and let the decoder spell them from one spot.
 */
fun keySpelling(label: String): List<Char>? {
    if (label.isEmpty() || label.length > MAX_KEY_SPELLING) return null
    if (!isLetterKeyChar(label[0])) return null
    for (i in 1 until label.length) {
        if (!isCombiningMark(label[i])) return null
    }
    return label.map { it.lowercaseChar() }
}

/**
 * Whether a character spells part of a word.
 *
 * `isLetter` alone is the Latin answer. Most of Probhat's and Jatiya's keys
 * carry Bengali vowel signs and the hasanta, which Unicode classes as combining
 * marks rather than letters — so a letters-only test finds no word character on
 * them at all, and every Bengali word containing one becomes unglidable, which
 * is very nearly all of them.
 */
private fun isLetterKeyChar(ch: Char): Boolean = ch.isLetter() || isCombiningMark(ch)

private fun isCombiningMark(ch: Char): Boolean = when (Character.getType(ch)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    -> true
    else -> false
}

/**
 * The single character [label] spells, when it is written as a base plus
 * combining marks and Unicode also has one code point for the same letter — and
 * null whenever it does not, which is nearly always.
 *
 * The Bengali letters ড় ঢ় য় have both spellings and both are in circulation:
 * the shipped layouts write them decomposed, and the shipped Bengali word list
 * writes most of its entries decomposed and about sixty of them composed. Rather
 * than pick a winner and rewrite one side of that — a silent change to what the
 * keyboard commits — the key answers to both, so a word stays reachable however
 * its list happens to spell it.
 *
 * Normalisation cannot answer this. Those three letters are in Unicode's
 * composition exclusion table, so NFC deliberately leaves them decomposed; it is
 * very likely what decomposed the word list in the first place. The lookup runs
 * the other way instead — over the code points around the base letter, asking
 * which of them decomposes to exactly this — and is cached, since a layout has a
 * handful of such keys and asks about each one repeatedly.
 */
fun composedKeyChar(label: String): Char? {
    if (label.length < 2) return null
    val cached = composedCache.getOrPut(label) { searchComposed(label) ?: NOT_COMPOSED }
    return cached.takeIf { it != NOT_COMPOSED }
}

/**
 * The other direction: the characters a *composed* label decomposes into.
 *
 * [composedKeyChar] answers a key written the decomposed way; this answers the
 * same key written the composed way, and the pair is what makes the grid
 * indifferent to which spelling a layout happens to use.
 *
 * It has to exist because the layouts and the word lists disagree on purpose.
 * The Bengali layouts write ড় ঢ় য় precomposed (U+09DC/09DD/09DF); the word
 * list is NFC, and NFC *decomposes* those three, because they are in Unicode's
 * composition exclusion table. So a glide over Jatiya's ড় key was offering the
 * decoder one character that the trie it walks does not contain anywhere, and
 * every word spelled with one fell out of reach — the same class of bug the
 * two-character key handling above was written for, arriving from the opposite
 * side.
 *
 * **Only composition exclusions qualify, and the restriction is load-bearing.**
 * Nearly every vowel sign on an Indic grid has a canonical decomposition — ো is
 * ে + া — but NFC puts those straight back together, so the word list holds the
 * composed form and there is nothing to bridge. Emitting the parts anyway lets
 * the ো key claim grid centres that belong to the real ে and া keys, and
 * Probhat's glide accuracy fell from 0.771 to 0.402 on exactly that. A character
 * NFC leaves alone is already the spelling the trie uses; only one it takes
 * apart needs both.
 */
fun decomposedKeyChars(label: String): List<Char>? {
    if (label.length != 1) return null
    val cached = decomposedCache.getOrPut(label) {
        java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFC)
            .takeIf { it != label && it.length in 2..MAX_KEY_SPELLING }
            .orEmpty()
    }
    return cached.takeIf { it.isNotEmpty() }?.map { it.lowercaseChar() }
}

private val composedCache = java.util.concurrent.ConcurrentHashMap<String, Char>()

private val decomposedCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Stands in for "looked, found nothing", so a miss is cached too. */
private val NOT_COMPOSED: Char = 0.toChar()

private fun searchComposed(label: String): Char? {
    // The same 256-point plane as the base letter. Every script that has these
    // duplicate-with-mark letters — Bengali, Devanagari, Gurmukhi, Oriya — puts
    // them a short way above their own base letters, well inside this window,
    // and scanning it is a few hundred comparisons done once per key.
    val start = label[0].code and 0xFF00
    for (cp in start until start + 0x100) {
        val candidate = cp.toChar()
        if (candidate == label[0]) continue
        val decomposed = java.text.Normalizer.normalize(
            candidate.toString(), java.text.Normalizer.Form.NFD,
        )
        if (decomposed == label) return candidate.lowercaseChar()
    }
    return null
}

/** A base character plus at most this many combining marks is still one key. */
private const val MAX_KEY_SPELLING = 4

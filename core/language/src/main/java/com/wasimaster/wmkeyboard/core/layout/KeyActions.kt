package com.wasimaster.wmkeyboard.core.layout

import androidx.compose.runtime.Immutable
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.repeats
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure

/**
 * What a key does when it is tapped.
 *
 * A sealed interface rather than the flat enum this started as, because the
 * actions a custom layout needs carry a payload — a modifier key names which
 * modifier it latches, a raw-key-event key names the Android key code it
 * injects. Keeping the enum and hanging nullable `modifier`/`keyCode` fields
 * off [Key] was the alternative and was rejected: it makes
 * `action = Shift, keyCode = 61` representable, and every reader would then
 * have to re-derive which fields its own branch is allowed to trust.
 *
 * The original values stay `data object`s so that `key.action ==
 * KeyAction.Text` and the three dozen other equality comparisons scattered
 * through the keyboard view keep working verbatim. Only `when (key.action)`
 * subjects have to grow branches, and only one of those — the dispatch in
 * `WMKeyboardService.onKey` — is exhaustive, which is exactly the site that
 * *should* fail to compile when an action is added.
 *
 * [Immutable] is load-bearing, not decoration: [Key] is a Compose parameter,
 * the Compose compiler infers a sealed interface as unstable, and an unstable
 * [Key] under strong skipping falls back to reference comparison — which
 * `currentLayout`'s rewrite pass defeats by allocating fresh keys on every
 * recomposition. Without this annotation every key recomposes on every
 * keystroke, and nothing about the app looks wrong while it happens.
 */
@Immutable
@Serializable
sealed interface KeyAction {

    /** Commits [Key.output], falling back to [Key.label]. The default. */
    @Serializable @SerialName("text") data object Text : KeyAction

    @Serializable @SerialName("shift") data object Shift : KeyAction

    /**
     * ⇪ — toggles caps lock outright, with no armed-for-one-letter middle step.
     *
     * Separate from [Shift] rather than a flag on it because a wide grid has room
     * for both keys at once, and they are then two different promises: the shift
     * key next to `z` still latches for one letter, while this one is the sticky
     * one you press before typing a word in capitals. Reusing [Shift] here would
     * also draw a second identical shift icon and single-tap to ON, which is not
     * what a key labelled ⇪ says it does.
     */
    @Serializable @SerialName("caps_lock") data object CapsLock : KeyAction

    @Serializable @SerialName("delete") data object Delete : KeyAction

    /**
     * ⌦ — deletes the character *after* the cursor, repeating while held.
     *
     * Its own action rather than a [SendKey] of `KEYCODE_FORWARD_DEL`: a raw
     * key event only lands in editors that handle the keycode themselves, and
     * it would skip the grapheme-cluster rules an IME is expected to apply, so
     * one press would peel a single code point off an emoji. Going through the
     * InputConnection works in every field and matches what backspace does.
     */
    @Serializable @SerialName("forward_delete") data object ForwardDelete : KeyAction

    @Serializable @SerialName("space") data object Space : KeyAction

    @Serializable @SerialName("enter") data object Enter : KeyAction

    /**
     * Types a line break and nothing else — never the field's Send/Go/Search
     * action, whatever the field declares.
     *
     * Its own action rather than a flag on [Enter] because the two are different
     * promises, and because the popup that offers it sits *on* the enter key: a
     * chat box declares Send, so Enter sends and this is how a line break gets
     * into the message. Also not `SendKey(KEYCODE_ENTER)`, which is the obvious
     * spelling and is exactly the thing that does not work: a raw ENTER lands in
     * the app's own key handling, and a single-line `TextView` — which is what a
     * field declaring an action almost always is — answers it by firing the
     * editor action, so the message sends anyway. Going through `commitText`
     * puts the character in the buffer with nothing left to intercept it.
     */
    @Serializable @SerialName("newline") data object Newline : KeyAction

    /** Steps LETTERS → SYMBOLS → SYMBOLS_SHIFTED → SYMBOLS. */
    @Serializable @SerialName("symbols") data object Symbols : KeyAction

    /** Jumps straight back to the letter layer from wherever you are. */
    @Serializable @SerialName("letters") data object Letters : KeyAction

    @Serializable @SerialName("language_switch") data object LanguageSwitch : KeyAction

    /**
     * Opens the system's keyboard picker — the "Choose input method" list that
     * holds every keyboard the device has turned on — so the field can be
     * handed to a different keyboard app.
     *
     * A separate action from [LanguageSwitch], not a long-press on it: that one
     * cycles *our* enabled layouts and never leaves this keyboard, and the two
     * answer different questions ("type in my other language" against "let me
     * use my other keyboard"). Keeping them apart is also what lets a layout
     * bind either one to whichever key it likes.
     *
     * The list itself is drawn by the framework and cannot be styled or
     * filtered: switching the active keyboard is a move only the platform is
     * allowed to make.
     */
    @Serializable @SerialName("input_method_picker") data object InputMethodPicker : KeyAction

    @Serializable @SerialName("emoji") data object Emoji : KeyAction

    /**
     * Opens the numeric keypad panel over the current field. Produced only at
     * runtime by a long-press on the ?123 / symbols key (opt-in via
     * `LayoutBehaviorSettings.symbolsLongPressNumpad`) — no built-in or custom
     * layout binds it, so it is never written to a serialized layout.
     */
    @Serializable @SerialName("numpad") data object Numpad : KeyAction

    /**
     * Opens one of the keyboard's tools — voice typing, the text-editing pad,
     * the clipboard, a scanner — exactly as tapping it on the toolbar does.
     *
     * The toolbar is a fixed strip with room for a handful of tools, and the
     * toolbox behind it costs two taps; a key (or a long-press alternate, see
     * [KeyAlternate]) is how a layout puts the one tool its author reaches for
     * every day where their thumb already is. Issue #21 asked for exactly this:
     * voice and the edit pad, on a press and hold.
     *
     * The tool is fired whether or not it is one of the tools shown on the
     * toolbar — a key bound by hand is its own reason for the tool to run — but
     * a tool this build does not ship (a full-flavour scanner on lite) or one
     * that is not usable yet (a search tool with no key configured) does
     * nothing, the same as it would on the toolbar.
     *
     * [tool] carries a default so a tool added by a *newer* build coerces to it
     * rather than throwing: the layout format's enums all take that route (see
     * `coerceInputValues` in `LayoutSpec`), and the emoji panel is the harmless
     * end of it — every build has one and a tap closes it.
     */
    @Serializable @SerialName("tool") data class Tool(
        val tool: ToolbarTool = ToolbarTool.EMOJI,
    ) : KeyAction

    /**
     * Shows one of the user's secondary layouts — a grid of their own that is
     * not a language (see `LayoutSpec.secondary`) — in place of the letters,
     * the way [Symbols] shows the symbol page. A second press of the same key
     * goes back to the letters, and so does any [Letters] key; a [Symbols] key
     * leaves for the symbol page (issue #62).
     *
     * [id] is the layout's id. A key naming a layout that has since been
     * deleted, or that is not secondary, does nothing — the same dead-key
     * outcome a [Tool] bound to a tool this build lacks gets — rather than
     * switching to something arbitrary. Carrying the id rather than an index
     * into "the secondary layouts" is what lets a layout be exported with its
     * keys intact and re-imported next to a different set of them.
     *
     * A blank [id] is the editor's placeholder while the picker is open, and
     * is what a file from a newer build with a missing field coerces to.
     */
    @Serializable @SerialName("layout") data class Layout(val id: String = "") : KeyAction

    /**
     * Latches a modifier for the next key, the way [Shift] latches case: tap to
     * arm, tap again to lock, a third tap to clear.
     *
     * Locking matters more here than it does for shift — Ctrl+Shift+arrow to
     * grow a selection is a sequence, not a single chord.
     *
     * Named [Mod] rather than Modifier because `androidx.compose.ui.Modifier` is
     * on nearly every line of the keyboard view, and every `is KeyAction.Modifier`
     * branch would have sat next to one and needed qualifying.
     */
    @Serializable @SerialName("mod") data class Mod(val key: ModifierKey) : KeyAction

    /**
     * Injects a raw key event instead of committing text: Tab, the arrow
     * cluster, Escape, or a fixed combo like Ctrl+Z.
     *
     * [meta] is a mask of `KeyEvent.META_*` flags, so a key can carry its own
     * modifiers without a latch first. A mask rather than the runtime latch type
     * because a spec must not depend on the IME package, and because a stored
     * shortcut can only ever mean "held" — there is no armed-versus-locked
     * distinction to preserve on disk.
     */
    @Serializable @SerialName("send_key") data class SendKey(
        val keyCode: Int,
        val meta: Int = 0,
    ) : KeyAction

    /**
     * Switches to the layout's Fn layer for one key, springing back after it; a
     * quick second tap sticks. Springing back is what makes a one-off Esc or F5
     * cheap — the common case is a single function key, not a run of them.
     *
     * A layer rather than a modifier because Android's `META_FUNCTION_ON` exists
     * but nothing in the framework or in any app reads it, so an Fn flag would
     * be inert everywhere. A layer is also what Fn physically is on a real
     * keyboard: a shift into a different key map.
     */
    @Serializable @SerialName("fn") data object Fn : KeyAction

    /**
     * Cycles the last kana in the composing buffer through its dakuten /
     * handakuten / small-kana variants (か→が, は→ば→ぱ, つ→っ→づ). The 小゛゜ key
     * on the Japanese flick pad; a no-op when nothing is composing.
     */
    @Serializable @SerialName("kana_variant") data object KanaVariant : KeyAction

    /**
     * Fires an Android broadcast with [action] as its intent action, so a key
     * can trigger an automation app (Tasker et al.) or any app with a matching
     * receiver — the keyboard equivalent of a physical macro key. Types no text
     * and moves no cursor.
     *
     * The action string is the only payload; a receiver keys off it. Nothing is
     * broadcast for a blank action. Purely user-authored — no built-in layout
     * binds it — so an app must register a receiver for the exact string to
     * observe anything, and a stray key on an imported layout does nothing on a
     * device with no such receiver.
     */
    @Serializable @SerialName("broadcast") data class Broadcast(val action: String) : KeyAction

    /**
     * One dot key of the six-key chorded braille layout. [dot] is the braille
     * dot number, 1..6. Unlike every other action, these keys fire on *press*,
     * not on release: the pointer handler reports the down and the up
     * separately (the up as a copy with [release] set), and the service's
     * chord engine commits the decoded cell when the last held dot lifts.
     *
     * [release] is runtime-only — the pointer handler synthesizes it; a layout
     * always writes `release = false` (the field's default) and the repair
     * pass never sees a true. It rides on the action rather than a separate
     * callback so the chord events flow through the exact same [Key] dispatch
     * as every other press, with no new plumbing through the view.
     */
    @Serializable @SerialName("braille_dot") data class BrailleDot(
        val dot: Int,
        val release: Boolean = false,
    ) : KeyAction

    /**
     * The dot (dit) key of the morse layout. Appends a short signal to the
     * service's pending morse sequence; the decoded character commits after a
     * pause, Gboard-style. Types nothing by itself.
     */
    @Serializable @SerialName("morse_dot") data object MorseDot : KeyAction

    /** The dash (dah) key of the morse layout; see [MorseDot]. */
    @Serializable @SerialName("morse_dash") data object MorseDash : KeyAction

    /** A deliberate gap in the grid: drawn as empty space, swallows its taps. */
    /**
     * A key of a converted Keyman layout. The key names a virtual key and the
     * keyboard's rule engine decides what it types.
     *
     * [Key.label] and [Key.output] are the cap and the fallback, not the answer:
     * the character a rule matches on comes from Keyman's own US virtual-key
     * table, so a Khmer key cap does not make the matched character Khmer. When
     * no engine is loaded — an exported layout on a device without the rules,
     * or a keyboard whose rules failed to parse — the key types its label, and
     * the grid stays an ordinary usable keyboard.
     *
     * [modifiers] is the touch key's `layer` attribute folded into a Keyman
     * modifier mask: "match rules as though this combination were held", which
     * is a different thing from [nextLayer], the layer to *show* afterwards. A
     * touch key's `nextlayer` wins over a rule's own `layer()` statement.
     *
     * Carried on the action rather than as a field on [Key] because [Key] is
     * serialised with `encodeDefaults`, so a new field there writes into every
     * key of every stored layout, and because [Key] is a Compose parameter under
     * strong skipping that should not grow. An older build decodes this as
     * [Unknown] and `repair` drops the key with a note, which is the format's
     * designed way of degrading.
     */
    @Serializable @SerialName("keyman_key") data class KeymanKey(
        val vkey: Int,
        val modifiers: Int = 0,
        val nextLayer: String? = null,
    ) : KeyAction

    @Serializable @SerialName("none") data object None : KeyAction

    /**
     * A cell of a panel layout that hosts a live component instead of a key:
     * the emoji grid, the clipboard history, a search pill, the trackpad
     * surface. Which component is [kind]; the cell's size is the key's own
     * [Key.width] and [Key.rowSpan], exactly as for any other key.
     *
     * This is what makes the tool panels editable in the layout editor (issue
     * #63): a panel is a grid of ordinary keys with one or more of these in it,
     * and the user places the component where they like, next to whatever
     * keys they like, rather than the panel being a fixed structure with an
     * editable bottom row. Only meaningful in a [PanelLayoutSpec]; a typing
     * layout carrying one is rejected by `validateLayout` and the key dropped
     * by repair.
     *
     * [kind] defaults to [PanelFieldKind.UNKNOWN] so a kind added by a newer
     * build coerces there and the panel repair drops the cell, rather than
     * masquerading as a real component. An older build that lacks this action
     * entirely decodes it as [Unknown] and drops it the same way.
     */
    @Serializable @SerialName("field") data class Field(
        val kind: PanelFieldKind = PanelFieldKind.UNKNOWN,
    ) : KeyAction

    /**
     * One text-editing operation — a caret move, a selection change, copy,
     * paste — as a key. The text-editing panel is a grid of these; a typing
     * layout may carry them too (an arrow cluster on a wide grid).
     *
     * Its own action rather than a [SendKey] because half the operations have
     * no key code (select word, select line, copy through the clipboard tool)
     * and the other half must honour the selection mode the toolbar keeps,
     * which a raw key event bypasses. The service runs them through the same
     * handler the toolbar's cursor tools use.
     *
     * [op] carries no default on purpose: an operation added by a newer build
     * failing this one key's decode is better than it silently becoming a
     * left-arrow.
     */
    @Serializable @SerialName("edit") data class Edit(val op: TextEditAction) : KeyAction

    /**
     * An action written by a build newer than this one. Decoding keeps the tag
     * so the failure is reportable ("2 keys use an action this version does
     * not know"), and [LayoutSpec.repair] drops the key so the row re-flows
     * around it rather than rendering a button that silently does nothing.
     *
     * The foreign payload is deliberately *not* retained. Keeping it would
     * mean a hand-written serializer that re-emits an arbitrary JSON object
     * inline, and a layout that survives a round trip down to an old build and
     * back is not worth that much machinery. Re-saving on an old build loses
     * the key, which is what the import report warns about.
     */
    @Serializable @SerialName("unknown") data class Unknown(val tag: String) : KeyAction
}

/**
 * What to draw on a key of this action that carries no label of its own.
 *
 * Both the keyboard and the layout editor's preview grid resolve a blank label
 * through here, because they used to disagree and the disagreement hid a hole:
 * the editor drew a placeholder dot for anything it had no glyph for, while the
 * keyboard drew the label and nothing else — so a Tab, Escape, Ctrl or Fn key
 * built from the action picker, which does not ask for a label and has no reason
 * to, came out of the editor looking fine and reached the keyboard **invisible**.
 * An unlabelled key is not a small cosmetic problem: it is a button the user
 * cannot find.
 *
 * Glyphs and key names rather than words, and deliberately untranslated: these
 * are the names printed on physical keyboards, the shipped layouts already spell
 * `?123` and `ABC` this way, and a layout file is a document people hand-edit
 * and swap between devices with different languages.
 *
 * Actions the keyboard draws from an icon slot — shift, caps lock, delete,
 * forward delete, enter, the globe, emoji — never reach here; their branches run
 * first. The entries below are the ones that fall through to a text label.
 */
fun KeyAction.fallbackLabel(): String = when (this) {
    KeyAction.Space -> " "
    KeyAction.Symbols -> "?123"
    KeyAction.Letters -> "ABC"
    KeyAction.Numpad -> "123"
    KeyAction.Fn -> "Fn"
    KeyAction.KanaVariant -> "小"
    KeyAction.MorseDot -> "·"
    KeyAction.MorseDash -> "–"
    // Drawn from the enter icon slot on the board, so the key itself never
    // reaches here. The alternates popup does: the entry the enter key offers
    // carries the icon, but one an author wrote by hand may not.
    KeyAction.Newline -> "⏎"
    KeyAction.None -> ""
    is KeyAction.Mod -> when (key) {
        ModifierKey.CTRL -> "Ctrl"
        ModifierKey.ALT -> "Alt"
        ModifierKey.META -> "Meta"
    }
    // The six the action picker offers by name. Any other code is something a
    // hand-written layout chose, and a bare arrow says "this key sends a key
    // press" without pretending to name it.
    is KeyAction.SendKey -> when (keyCode) {
        KEYCODE_TAB -> "⇥"
        KEYCODE_ESCAPE -> "esc"
        KEYCODE_DPAD_UP -> "↑"
        KEYCODE_DPAD_DOWN -> "↓"
        KEYCODE_DPAD_LEFT -> "←"
        KEYCODE_DPAD_RIGHT -> "→"
        else -> "⌨"
    }
    // The dot number is the whole identity of a braille key, and a chord is
    // typed by position, so the digit is what the user needs to see.
    is KeyAction.BrailleDot -> dot.toString()
    is KeyAction.Broadcast -> "⚡"
    // A Keyman key always carries its own cap from the touch layout, so this is
    // reached only by a hand-edited layout that left the label blank.
    is KeyAction.KeymanKey -> ""
    // A tool key draws the tool's own icon — the one it wears on the toolbar —
    // at both draw sites, so it never falls through to a text label. Naming the
    // tool here instead would put an untranslated enum name on the key.
    is KeyAction.Tool -> ""
    // The editor writes the layout's name onto the key when it is picked; this
    // is the grid glyph a hand-written layout that left the label blank gets.
    is KeyAction.Layout -> "▦"
    // A field is not a key: the cell draws its component, and the editor draws
    // the component's name from a string resource.
    is KeyAction.Field -> ""
    // The board and the editor both draw an edit key from its operation's icon
    // (see the ime layer's text-edit key faces); the glyph here is what a
    // hand-written alternate or an unlabelled JSON key falls back to.
    is KeyAction.Edit -> op.fallbackGlyph()
    // Reached only by a layout that repair has not been through yet.
    is KeyAction.Unknown -> "?"
    // Text keys have nothing to fall back to: a blank one is a blank key, which
    // `repair` deletes rather than draws. The icon-slot actions are listed so
    // this stays exhaustive and a new action cannot be added without deciding.
    KeyAction.Text, KeyAction.Shift, KeyAction.CapsLock, KeyAction.Delete,
    KeyAction.ForwardDelete, KeyAction.Enter, KeyAction.LanguageSwitch,
    KeyAction.InputMethodPicker, KeyAction.Emoji,
    -> ""
}

// Written as numbers rather than KeyEvent.KEYCODE_* so this module, which is
// pure layout data, needs no android.view import.
private const val KEYCODE_TAB = 61
private const val KEYCODE_ESCAPE = 111
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22

/**
 * One entry of a key's long-press popup that *does* something instead of typing:
 * Tab, the voice tool, the text-editing pad, a layer switch.
 *
 * Its own type rather than more entries in [Key.longPress], which is a list of
 * strings and can only ever mean "commit this text". The two lists sit side by
 * side on [Key] instead of being merged into one list of this type, because the
 * string list is what every shipped layout, every import format and the editor's
 * one-line alternates field are written in, and changing its element type would
 * rewrite all three to buy nothing a second field does not already give.
 *
 * [label] is what the popup draws; blank falls back to [KeyAction.fallbackLabel]
 * — which is blank in turn for the actions that wear an icon, and those draw the
 * icon. [icon] overrides both with a named icon from the ime layer's registry,
 * the same lookup [Key.icon] goes through.
 */
@Immutable
@Serializable
data class KeyAlternate(
    val action: KeyAction,
    val label: String = "",
    val icon: String? = null,
)

/** What a popup draws for this alternate when it has no icon; see [KeyAlternate]. */
fun KeyAlternate.drawnLabel(): String = label.ifBlank { action.fallbackLabel() }

/**
 * Whether a press and hold on this key is already spoken for by something other
 * than the alternates popup, so alternates on it would never be reachable.
 *
 * The two delete keys and the braille dots. Backspace repeats and swipes away
 * words, forward delete repeats, and a braille dot fires on the way down as part
 * of a chord and has no long press at all. Every other action — enter, the layer
 * switches, a modifier, a keycode sender — does nothing under a held finger,
 * which is the room issue #22 asked for.
 *
 * The spacebar is deliberately absent, though its hold does have two default
 * jobs (the language picker, and repeating spaces). Both are defaults rather
 * than parts of the key: alternates authored onto the spacebar take the hold
 * over, which is the whole of issue #57. Nothing ships alternates on a space
 * key, so the hold keeps both jobs until someone asks for them to go.
 *
 * Lives here because three places have to agree about it: the pointer handler
 * that opens the popup, the key that draws (or does not draw) a corner hint, and
 * the layout editor, which must not offer an author a field that will silently
 * do nothing.
 */
fun KeyAction.holdIsSpokenFor(): Boolean = when (this) {
    KeyAction.Delete, KeyAction.ForwardDelete -> true
    is KeyAction.BrailleDot -> true
    // The component owns every gesture inside its cell.
    is KeyAction.Field -> true
    // The moves and backspace repeat while held, as they did on the old
    // text-editing panel; Home, End and the selection commands do not, so their
    // hold is free for an alternate (the shipped grid puts Page Up on Home).
    is KeyAction.Edit -> op.repeats
    else -> false
}

/**
 * The glyph an edit key falls back to when nothing draws its icon.
 *
 * Untranslated for the reason [fallbackLabel] gives; the four selection
 * commands have no glyph and take a short English word, which is what the old
 * panel drew for Select too.
 */
private fun TextEditAction.fallbackGlyph(): String = when (this) {
    TextEditAction.UP -> "↑"
    TextEditAction.DOWN -> "↓"
    TextEditAction.LEFT -> "←"
    TextEditAction.RIGHT -> "→"
    TextEditAction.HOME -> "⇤"
    TextEditAction.END -> "⇥"
    TextEditAction.PAGE_UP -> "⇞"
    TextEditAction.PAGE_DOWN -> "⇟"
    TextEditAction.WORD_LEFT -> "⇠"
    TextEditAction.WORD_RIGHT -> "⇢"
    TextEditAction.SELECT_WORD -> "Word"
    TextEditAction.SELECT_LINE -> "Line"
    TextEditAction.SELECT -> "Sel"
    TextEditAction.SELECT_ALL -> "All"
    TextEditAction.COPY -> "⎘"
    TextEditAction.PASTE -> "⎗"
    TextEditAction.BACKSPACE -> "⌫"
    TextEditAction.DOC_START -> "⇱"
    TextEditAction.DOC_END -> "⇲"
    TextEditAction.CUT -> "✂"
}

/**
 * The component a [KeyAction.Field] cell hosts. Each belongs to one panel
 * ([panel]); a panel layout may hold each of its kinds at most once.
 *
 * Decomposed rather than one field per panel on purpose: the emoji panel's
 * tabs, search pill and grid are three cells, so a layout can put the tabs at
 * the bottom, drop the search pill, or give the grid the whole height.
 *
 * Serialized by the lowercase names so a layout file reads `"emoji_grid"`.
 * [UNKNOWN] is where a kind from a newer build lands under `coerceInputValues`;
 * the panel repair drops such a cell and the editor never offers it.
 */
@Serializable
enum class PanelFieldKind(val panel: PanelKind) {
    @SerialName("emoji_tabs") EMOJI_TABS(PanelKind.EMOJI),
    @SerialName("emoji_search") EMOJI_SEARCH(PanelKind.EMOJI),
    @SerialName("emoji_grid") EMOJI_GRID(PanelKind.EMOJI),
    @SerialName("clipboard_search") CLIPBOARD_SEARCH(PanelKind.CLIPBOARD),
    @SerialName("clipboard_entities") CLIPBOARD_ENTITIES(PanelKind.CLIPBOARD),
    @SerialName("clipboard_list") CLIPBOARD_LIST(PanelKind.CLIPBOARD),
    @SerialName("trackpad") TRACKPAD(PanelKind.TRACKPAD),
    @SerialName("unknown") UNKNOWN(PanelKind.EMOJI),
    ;

    /** Whether the editor may offer this kind; [UNKNOWN] is a decode artefact. */
    val isReal: Boolean get() = this != UNKNOWN
}

/**
 * A modifier a [KeyAction.Mod] key latches.
 *
 * Shift is deliberately absent: it has its own action, its own three-state latch
 * and its own glyph, and folding it in here would leave two ways to spell the
 * same key. Fn is absent too — it is a layer rather than a modifier, because
 * `META_FUNCTION_ON` exists but nothing in the framework or in apps consumes it,
 * so an Fn flag would be an inert no-op everywhere.
 */
enum class ModifierKey { CTRL, ALT, META }

/** Clipboard/undo/redo shortcut a letter key can perform on long press (A/C/V/X/Z/Y). */
enum class ClipboardKeyAction { SELECT_ALL, COPY, PASTE, CUT, UNDO, REDO }

/**
 * What a key means to the runtime beyond the character it types.
 *
 * Field adaptation used to find these slots by matching `label == ","` on the
 * last row, which silently skipped any custom layout whose bottom row is
 * arranged differently: an email box in such a layout would keep its comma and
 * never get its @ key, with nothing to tell the user why.
 */
enum class KeyRole {
    /** The sentence-punctuation slot; gains domain suffixes in EMAIL/URI fields. */
    Period,

    /** The secondary-punctuation slot; becomes @ in EMAIL, / in URI, or the emoji key. */
    Comma,
}

/**
 * What a key means to field adaptation: its explicit tag, or the old label match
 * as a fallback.
 *
 * The fallback stays rather than being dropped so layouts written before roles
 * existed — and anything imported from a build that predates them — keep their
 * email and URI adaptation instead of silently losing it. It remains scoped to
 * the bottom row for the reason it always was: Dvorak's *top* row has real "."
 * and "," letter keys, which must not be rewritten into an @ key.
 *
 * Lives here rather than beside the rendering code that reads it because it is a
 * property of the layout model, and because the tablet expansion in this module
 * has to locate the same two slots before it moves them.
 *
 * Both branches want a key that types text, the tag included: a role says which
 * punctuation slot this key fills, and a key that shifts or deletes fills
 * neither. The editor only offers the tag on text keys, but the tag outlives a
 * change of action, so a key retyped as shift kept a stamp it no longer had any
 * way to show — and turned into the URI field's "/" (issue #25).
 */
fun Key.roleIn(rowIndex: Int, lastRow: Int): KeyRole? = when {
    action != KeyAction.Text -> null
    role != null -> role
    rowIndex != lastRow -> null
    label == "," -> KeyRole.Comma
    label == "." -> KeyRole.Period
    else -> null
}

/**
 * Decodes an unregistered action tag into [KeyAction.Unknown], keeping the tag
 * and discarding whatever fields came with it.
 *
 * This is the only non-obvious serialization code in the layout model, which
 * is why `LayoutCodecTest` exercises it first: a layout whose second key has
 * `{"type":"teleport","destination":"mars"}` must decode to a three-key layout
 * with `Unknown("teleport")` in the middle, not throw and cost the user the
 * other two.
 */
internal class UnknownKeyActionSerializer(
    private val tag: String,
) : KSerializer<KeyAction.Unknown> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("wm.unknownKeyAction")

    override fun deserialize(decoder: Decoder): KeyAction.Unknown {
        // The descriptor has no elements, so with ignoreUnknownKeys on, the
        // decoder steps over the foreign payload and reports DECODE_DONE.
        decoder.decodeStructure(descriptor) {}
        return KeyAction.Unknown(tag)
    }

    override fun serialize(encoder: Encoder, value: KeyAction.Unknown): Nothing =
        throw UnsupportedOperationException(
            "unknown actions are dropped by repair and never written back",
        )
}

package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.repeats
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The eight flick directions a key reads off a swipe: the four edges a 12-key
 * kana pad uses and the four corners a MessagEase-style board adds (issue
 * #410). The centre (a plain tap) commits the key's own [Key.output]/[Key.label];
 * a directional flick commits the matching entry of [Key.flick]. Serialised
 * lowercase so a hand-authored layout reads `"flick": { "left": "い", "up": "う",
 * "up_right": "x", … }`.
 */
@Serializable
enum class FlickDirection(
    /** Where this arm points, as a compass bearing: up is 0°, clockwise. */
    val compassDegrees: Float,
) {
    @SerialName("left") LEFT(270f),

    @SerialName("up") UP(0f),

    @SerialName("right") RIGHT(90f),

    @SerialName("down") DOWN(180f),

    // The diagonals came later (issue #410), so they sit after the four a file
    // written before them could name: a pad's JSON keeps the order it was
    // written in.
    @SerialName("up_left") UP_LEFT(315f),

    @SerialName("up_right") UP_RIGHT(45f),

    @SerialName("down_left") DOWN_LEFT(225f),

    @SerialName("down_right") DOWN_RIGHT(135f),
    ;

    /** A corner arm rather than an edge one. */
    val isDiagonal: Boolean get() = compassDegrees % 90f != 0f

    companion object {
        /**
         * The eight arms in reading order around a 3×3 pad, with `null` for
         * the centre: what the flick cross, the key face and the editor's pad
         * all lay out, so none of them can put an arm on a different cell.
         */
        val gridOrder: List<FlickDirection?> = listOf(
            UP_LEFT, UP, UP_RIGHT,
            LEFT, null, RIGHT,
            DOWN_LEFT, DOWN, DOWN_RIGHT,
        )
    }
}

/**
 * One key of a layout grid.
 *
 * A key either outputs text ([output], falling back to [label]) or triggers an
 * [action]. [longPress] holds the alternate characters shown in the long-press
 * popup, whose first entry doubles as the corner hint. [width] is relative: 1.0
 * is a standard key, the spacebar is wider, shift/delete slightly wider.
 *
 * A text key can carry an [icon] instead of a glyph and an [iconHint] instead
 * of the character hint — both are named lookups resolved to a vector at render
 * time (see the icon registry in the ime layer), so this stays a plain
 * serializable value type with no Compose dependency. They sit alongside the
 * character system: a key with no icon renders exactly as before.
 *
 * This is both the stored and the rendered type. A parallel KeySpec DTO was the
 * alternative and was rejected: it would have been field-identical, so the only
 * thing it bought was a mapping function to forget to update. The stored/runtime
 * split that does exist is one level up, at [LayoutSpec], which is where layers
 * inherit and identity lives.
 *
 * Because this is a Compose parameter, everything reachable from it has to stay
 * stable — see the `@Immutable` annotation on [KeyAction].
 */
@Serializable
data class Key(
    val label: String,
    val output: String? = null,
    val shiftLabel: String? = null,
    val action: KeyAction = KeyAction.Text,
    val width: Float = 1f,
    /**
     * How many rows this key covers, counting its own: 1 (the usual case) is an
     * ordinary key, 2 makes it twice as tall and pushes the row below to flow
     * around the column it occupies. The vertical twin of [width], and the thing
     * an arrangement like ClearFlow's tall Enter cannot be written without.
     *
     * See [spanSlots] for the geometry and `KeyBand` in the ime layer for the
     * render. A span that runs past the last row is clamped rather than rejected,
     * so a grid the editor has just had a row deleted from still draws.
     */
    val rowSpan: Int = 1,
    /** Which way up the screen has to be for this key to exist. */
    val showOn: KeyScreen = KeyScreen.ALWAYS,
    val longPress: List<String> = emptyList(),
    /**
     * Long-press alternates that run an action instead of typing: Tab, the voice
     * tool, the editing pad (issue #21). Drawn in the same popup, after the
     * [longPress] characters, and never the corner hint — the hint is one glyph
     * and these are mostly icons.
     *
     * See [KeyAlternate] for why the two lists stay separate.
     */
    val actionAlternates: List<KeyAlternate> = emptyList(),
    /**
     * The older spelling of [actionAlternatesAt] = 0, from before the actions
     * could go anywhere but the front or the back (issue #570).
     *
     * Kept so a file that wrote it still reads the same, and because every key
     * an older build exported carries it: [Key] is written with its defaults,
     * so dropping the field would make each of those keys an unknown property
     * to the JSON editor. Nothing sets it any more; [actionAlternatesAt] beats
     * it when both are there.
     *
     * Read only through [alternateEntries].
     */
    val actionAlternatesFirst: Boolean = false,
    /**
     * Columns this key's alternates popup lays itself out on, or 0 to follow the
     * global setting (issue #64).
     *
     * Per key rather than only per keyboard because the popups differ in kind:
     * the `1` key of a fractions layout carries a dozen entries that want a grid,
     * while `e` carries four accents that want one row. A count that suits one is
     * wrong for the other, and the point of fixing the count at all is that a
     * given key's popup looks the same every time it opens.
     *
     * 0 is not a column count but the absence of one: the key defers to
     * `KeyPopupSettings.alternatesColumns`, which is itself 0 (automatic wrap) by
     * default. Anything else is in [AlternateColumnsRange].
     */
    val alternateColumns: Int = 0,
    /** Clipboard shortcut fired on long press instead of the alternates popup. */
    val clipboardAction: ClipboardKeyAction? = null,
    /** What this key means to field adaptation; null infers it from position. */
    val role: KeyRole? = null,
    /**
     * Named icon drawn as the key's main glyph in place of [label] (the label is
     * still used for accessibility and, unless [output] overrides it, for what
     * the key commits). Resolved through the ime-layer icon registry; an
     * unknown name falls back to drawing the text label.
     */
    val icon: String? = null,
    /**
     * Named icon drawn as the small corner hint in place of the character hint.
     * Shown under the same [KeyboardSettings.longPressHints] toggle as the text
     * hint. Resolved through the ime-layer icon registry.
     */
    val iconHint: String? = null,
    /**
     * Text drawn as the corner hint in place of the first long-press alternate
     * (issue #769): what the corner says, kept apart from what a hold types, so
     * a full stop can show `,?!` in its corner while its popup keeps whatever
     * order the hold wants. Shown under the same toggles as the character hint,
     * on any key, the way [iconHint] is; [iconHint] wins when both are set.
     */
    val hint: String? = null,
    /**
     * Draws no corner hint on this key, whatever it would otherwise have shown —
     * the first long-press alternate, or an [iconHint].
     *
     * Per-key rather than per-layout because the case it exists for is one key in
     * an otherwise hinted grid: an author who wants the alternates *reachable*
     * (so clearing [longPress] is not the answer) but the corner left clean.
     * A key's own field beats the global [KeyboardSettings.longPressHints]
     * toggle, which is the other direction — it turns every hint on the board off
     * at once.
     */
    val hideHint: Boolean = false,
    /**
     * Draws this key's corner hint even when the global
     * [KeyboardSettings.longPressHints] toggle is off (issue #33).
     *
     * The other half of [hideHint], and it exists for the mirror-image author:
     * someone who turned every hint off because a full grid of them is noise,
     * but wants the two or three keys whose alternates nobody would guess to
     * keep saying so. Per key for the same reason [hideHint] is.
     *
     * [hideHint] wins when both are set. The two are separate booleans rather
     * than one tri-state because that is what keeps a file written by an older
     * build reading the same: `hideHint` already means what it meant, and a
     * key that says nothing gets the toggle's answer, exactly as before.
     */
    val forceHint: Boolean = false,
    /**
     * Directional flick outputs: a short swipe from this key in one of the
     * eight compass directions commits that arm's text instead of the centre
     * tap, the way a 12-key kana pad reaches い from あ and a MessagEase-style
     * board (issue #410) keeps nine letters on one key. Empty (the usual case)
     * means the key has no flick behaviour and a drag off it just cancels the
     * press, exactly as before.
     */
    val flick: Map<FlickDirection, String> = emptyMap(),
    /**
     * What each [flick] arm types while Shift is on, the way [shiftLabel] does
     * for the tap (issue #550). A kana pad with a Shift key can then put its
     * katakana on the same keys as its hiragana, rather than on a second
     * layout behind a key of its own.
     *
     * An arm with no entry here does what a key with no [shiftLabel] does:
     * Shift makes a letter a capital and leaves everything else alone. An entry
     * for an arm with no [flick] text does nothing, since there is nothing for
     * it to be the shifted form of.
     */
    val flickShift: Map<FlickDirection, String> = emptyMap(),
    /**
     * Flick arms that run an action instead of typing (issue #549): a key whose
     * tap selects a word and whose four flicks move the caret, say.
     *
     * An arm here beats the [flick] text for the same direction. Unlike the text
     * arms these work on an action key too, which is the case they exist for;
     * see [takesFlickActions] for the few keys whose own drag or hold leaves no
     * room for them.
     */
    val flickActions: Map<FlickDirection, KeyAlternate> = emptyMap(),
    /**
     * How big this one key's label is drawn, as a multiple of an ordinary
     * letter's size — and null, the normal case, means the keyboard decides,
     * which is a letter's size for a letter and a smaller one for a
     * multi-character mode label like `?123`.
     *
     * Setting it *replaces* that decision rather than scaling it, so 1.0 is the
     * way to say "draw this multi-character label at full letter size" — which
     * is the thing the automatic rule cannot be talked out of, and the thing a
     * layout imported from a keyboard with per-key label flags needs (see
     * `ForeignLayouts`, and issue #18). [LayoutAppearance.fontScale] then scales
     * the result, the same as it scales every other key.
     *
     * Per key rather than per row because that is the granularity the formats
     * this has to read use, and because the case it exists for is one odd key in
     * an otherwise ordinary grid. Clamped at draw time to [KeyLabelScaleRange]
     * — the same treatment [LayerSpec.rowHeights] gets, and for the same reason:
     * a value from a file is not a value from a control.
     */
    val labelScale: Float? = null,
    /**
     * This key's corner hint as a multiple of the size every hint is drawn at
     * (#970), for the one key whose hint is a mark too small to read (Arabic
     * harakat). Null follows the setting. Clamped at draw time to
     * [KeyHintScaleRange].
     */
    val hintScale: Float? = null,
    /**
     * Every letter this one key stands for — `"abc"` on a T9 keypad's 2, `"qw"`
     * on a compact grid's first key — for a layout that puts more than one
     * letter on a key and lets the language model say which was meant
     * (discussion #103).
     *
     * Null, the normal case, is an ordinary 1:1 key: what it types is what it
     * typed. A single character is the same thing said explicitly, and is the
     * shape a mixed grid ends up in — a compact layout whose `L`, `M` and the
     * punctuation keys carry one letter each while the rest carry two.
     *
     * The key still commits [output] (its *anchor* letter, the first of these by
     * convention) on a tap, and that is deliberate: the composing buffer stays
     * one character per keystroke, so backspace, the caret, word boundaries and
     * every consumer of the buffer are untouched, and a build that ignores this
     * field types `adg` rather than breaking. What the field adds is the
     * side-channel — see `WMKeyboardService.composingKeyFrame` — that tells the
     * decoder those three keystrokes could equally have been any of
     * `abc`/`def`/`ghi`, which is what turns `adg` into "big".
     *
     * The letters are also published to the glide grid at this key's centre, so
     * a swipe across an ambiguous board decodes with no decoder change at all:
     * `GlideKeyMap` has always resolved several characters to one key.
     *
     * Additive and defaulted, so no format-version bump.
     */
    val letters: String? = null,
    /**
     * On the space bar, draws [icon] before the language name instead of in its
     * place (issue #187). False, the default, lets the icon replace the text, the
     * same as an icon does on every other key. Nothing on a key with no icon, and
     * nothing on any key but the space bar.
     */
    val iconBesideLabel: Boolean = false,
    /**
     * A held finger on this key fires it again and again, the way it always has
     * on backspace, at [KeyRepeatSettings.customKeyMs] and after the same
     * [KeyRepeatSettings.startDelayMs] every other repeat waits out (issue
     * #231).
     *
     * Per key rather than per action because the same action is worth repeating
     * on one board and not on another: the arrow cluster somebody adds a whole
     * row for is the case this exists for, and a `SendKey(DPAD_LEFT)` on a
     * function row they press once is not. Off by default, so no existing key
     * changes what a hold does.
     *
     * Turning it on spends the hold: the alternates popup, the clipboard
     * shortcut and every other long-press meaning are gone for as long as it is
     * set, which is what [holdIsSpokenFor] says on this key's behalf and what
     * the key sheet says in words before the fields disappear. Only offered
     * where a second press does something the first did not — see
     * [KeyAction.canRepeatOnHold], which the repair pass also clears a stored
     * value against, so a file cannot hang a repeat on a key that has no use
     * for one.
     */
    val repeatOnHold: Boolean = false,
    /**
     * While the reading being typed ends in a kana that has a small, dakuten
     * or handakuten form, this key stands in for the 小゛゜ key: it draws
     * `小゛゜` and a tap runs [KeyAction.KanaVariant] (issue #340). The rest of
     * the time it is the key it was authored as — a 🌐, an emoji key, anything.
     *
     * The way the phone kana pads save a key: the 小゛゜ key is only ever useful
     * right after a kana, which is exactly when nothing else on that key is
     * wanted. A flag on the key rather than an action of its own, so the key's
     * own action and everything else about it stay authored and editable, and
     * any key of the pad can be given the second job, not only the globe.
     *
     * Additive and defaulted, so no format-version bump.
     */
    val kanaVariantWhileComposing: Boolean = false,
    /**
     * What tapping this key again types in place of what the last tap typed —
     * the feature-phone multi-tap a Korean 천지인 (Cheonjiin) pad cycles its
     * consonants with (ㄱ → ㅋ → ㄲ) and Samsung's `.,?!` key its punctuation
     * with (discussion #372).
     *
     * KeymanWeb's shape: the first tap types the key itself ([output], falling
     * back to [label]), the second `multitap[0]`, and so on, wrapping back to the
     * key after the last entry. A tap only continues the cycle when it is the same
     * key, soon enough after the one before it, with the caret where that tap
     * left it; anything else starts over, which is how the same letter is typed
     * twice in a row. See `MultitapCycle` in `:core:input`.
     *
     * On a Keyman key these are the caps of the cycle's keys, and
     * [KeyAction.KeymanKey.multitap] holds the keys themselves in the same order.
     *
     * Empty, the normal case, is an ordinary key: every tap types it again.
     * Additive and defaulted, so no format-version bump.
     */
    val multitap: List<String> = emptyList(),
    /**
     * Where [actionAlternates] sit among the popup's entries (issue #570): the
     * index of the first of them, so 0 puts them in front of the [longPress]
     * characters, 1 after the first character, and so on. Null, the default,
     * is after every character, where they have always gone.
     *
     * The middle positions are what the issue asked for. At the front, a plain
     * hold-and-release runs the action and the corner hint (the first
     * character) names something the hold no longer types. One along, the hold
     * still types what the hint shows and the action is the next entry, a short
     * slide away rather than past every accent.
     *
     * An index past the characters is the same as null, so a position chosen
     * for a key with four accents stays meaningful on a key with two. The
     * actions move as one block, in their own order.
     *
     * Authored per key, or filled at resolve time from
     * [com.wasimaster.wmkeyboard.core.settings.LongPressLetterActions.actionPosition]
     * on a key the hold shortcuts were added to and whose author said nothing.
     * Read only through [alternateEntries].
     */
    val actionAlternatesAt: Int? = null,
    /**
     * An action a press and hold runs at once, with no popup: hold `x` to cut,
     * hold `.` for forward delete. A delete or a caret move keeps running
     * while the finger stays down, the way the same action does as a flick arm.
     *
     * The popup's [actionAlternates] run an action too, but only once the
     * popup has opened and the finger has picked one, which is a second step
     * for a key that only ever has the one. [clipboardAction] was this for six
     * clipboard shortcuts; this is the same thing for any action an alternate
     * may carry.
     *
     * It spends the hold, as [repeatOnHold] does: the popup and the corner hint
     * are gone for as long as it is set (see [hasHoldAction]). The space bar,
     * the keys whose hold repeats or chords and the flick keys keep their own
     * holds, so it does nothing on those; see [canHoldAction].
     */
    val holdAction: KeyAlternate? = null,
)

/** What a [Key.kanaVariantWhileComposing] key draws while it is the 小゛゜ key. */
const val KanaVariantKeyLabel = "小゛゜"

/**
 * This key as the 小゛゜ key, for as long as [Key.kanaVariantWhileComposing]
 * has it stand in for one. Everything about where and how big it is survives;
 * everything about what it did is replaced, down to its hold, so a press and
 * hold does not open the popup of the key it is covering for.
 */
fun Key.asKanaVariantKey(): Key = copy(
    label = KanaVariantKeyLabel,
    output = null,
    shiftLabel = null,
    action = KeyAction.KanaVariant,
    longPress = emptyList(),
    actionAlternates = emptyList(),
    clipboardAction = null,
    holdAction = null,
    icon = null,
    iconHint = null,
    hint = null,
    flick = emptyMap(),
    flickShift = emptyMap(),
    flickActions = emptyMap(),
    letters = null,
    repeatOnHold = false,
)

/** Whether any key of this grid can stand in for the 小゛゜ key. */
fun KeyboardLayout.hasKanaVariantKeys(): Boolean =
    rows.any { row -> row.any { it.kanaVariantWhileComposing } }

/**
 * The letters this key can stand for, as the decoder and the glide grid read
 * them: [Key.letters] when the layout authored one, otherwise the single letter
 * the key types.
 *
 * Empty for a key that types no letter at all — an action key, punctuation, a
 * multi-character output — which is the answer both callers want: such a key
 * contributes nothing to an alphabet and nothing to a key set.
 */
fun Key.letterSet(): String {
    if (action != KeyAction.Text) return ""
    letters?.let { return it }
    val text = output ?: label
    return if (text.length == 1 && text[0].isLetter()) text else ""
}

/**
 * Whether this key carries more than one letter, so a tap on it cannot say which
 * of them was meant. See [Key.letters].
 */
fun Key.isAmbiguous(): Boolean = letterSet().length > 1

/**
 * What a key's letter set may hold: a letter, or a combining mark that is part
 * of a written word (a matra, a virama, a nukta, a tone mark).
 */
fun Char.spellsAWord(): Boolean = isLetter() || category in SPELLING_MARKS

private val SPELLING_MARKS = setOf(
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
)

/**
 * This key rebuilt around the letter set [raw] spells, with the anchor invariant
 * applied rather than assumed.
 *
 * The one place a letter set is ever set. Both callers need the same three
 * things and neither can be trusted to remember them: the set holds letters and
 * nothing else, it holds each of them once, and [Key.output] is its first
 * member. That last one is the invariant the decode rests on — a tap commits the
 * anchor, so the composing buffer stays one character per keystroke — and it was
 * previously a rule written in a doc comment that the field's only author, a
 * person hand-editing JSON, had to read and obey (discussion #103). A rule a
 * caller can break is a rule that gets broken, so the rule now lives in the
 * function that writes the field, and the editor and the repair pass both go
 * through it.
 *
 * Letters are lowercased one character at a time, never with `lowercase()`,
 * which is not always 1:1 (Turkish `İ` lowercases to two characters) and would
 * quietly make the set longer than the label it came from.
 *
 * A combining mark counts as a letter here (issue #332). An Indic keypad's 1
 * carries the virama and the anusvara, and its 2 and 3 carry the vowel signs,
 * because that is where the phones of the Indian market put them: each is a
 * keystroke of its own that the word is spelled with, exactly like a letter,
 * and dropping them left those scripts with no way to spell a word at all.
 *
 * An empty result clears the field, which is what an ordinary key is.
 */
fun Key.withLetters(raw: String): Key {
    val set = StringBuilder()
    for (ch in raw) {
        val letter = ch.lowercaseChar()
        if (letter.spellsAWord() && letter !in set) set.append(letter)
    }
    return if (set.isEmpty()) {
        copy(letters = null)
    } else {
        copy(letters = set.toString(), output = set.take(1).toString())
    }
}

/**
 * Whether any key of this grid is ambiguous — whether the whole board needs the
 * key-set decode rather than reading its keystrokes literally.
 */
fun KeyboardLayout.hasAmbiguousKeys(): Boolean =
    rows.any { row -> row.any { it.isAmbiguous() } }

/**
 * Whether a press and hold on this key opens the alternates popup.
 *
 * Three things have to agree on the answer: the pointer handler that opens the
 * popup, the draw that puts the first alternate in the key's corner, and the
 * layout editor's key sheet, which offers the alternates fields only for a key
 * that can actually show them. They used to agree by accident — every site
 * tested `action == Text` — and that is what kept alternates off the enter key
 * for no reason anyone had chosen (issue #22).
 *
 * A clipboard shortcut or a [Key.holdAction] takes the long press over
 * outright, and the keys that repeat or chord under a held finger never get
 * one; see [holdIsSpokenFor].
 */
fun Key.opensAlternatesPopup(): Boolean =
    canHoldAlternates() && (longPress.isNotEmpty() || actionAlternates.isNotEmpty())

/**
 * Whether this key *could* hold alternates, whether or not it has any — the
 * question the layout editor asks before drawing the fields that author them.
 */
fun Key.canHoldAlternates(): Boolean = clipboardAction == null && !hasHoldAction() && !holdIsSpokenFor()

/**
 * Whether a [Key.holdAction] would run on this key: the question the key sheet
 * asks before offering one.
 *
 * Every key whose hold is free, less three. The space bar's hold is its
 * language picker and its repeat, read by a gesture handler of its own; a flick
 * key's hold belongs to its flick pad; and a hand-written [Key.clipboardAction]
 * is already the same idea, older.
 */
fun Key.canHoldAction(): Boolean =
    clipboardAction == null && action != KeyAction.Space && !holdIsSpokenFor() && !hasFlicks()

/** Whether a press and hold on this key runs its [Key.holdAction]. */
fun Key.hasHoldAction(): Boolean = holdAction != null && canHoldAction()

/**
 * The key a press and hold presses, for a key that [hasHoldAction]: built the
 * way a popup's action entry is, so the hold does exactly what the same action
 * does as an alternate or a flick arm.
 */
fun Key.holdActionKey(): Key? =
    holdAction?.takeIf { hasHoldAction() }?.let { Key(label = it.label, action = it.action) }

/**
 * [KeyAction.holdIsSpokenFor] asked of a whole key, which is the form every
 * caller wants: the action answers for the keys that have always repeated or
 * chorded, and [Key.repeatOnHold] answers for the one an author has just told
 * to repeat (issue #231).
 */
fun Key.holdIsSpokenFor(): Boolean = repeatOnHold || action.holdIsSpokenFor()

/**
 * Whether [Key.repeatOnHold] can be turned on for this key — the question the
 * key sheet asks before drawing the switch and the repair pass asks of a stored
 * one.
 *
 * [KeyAction.canRepeatOnHold] decides it for all but one case: a 12-key kana pad
 * key owns its whole gesture, tap and flick and hold together, and a repeat has
 * nowhere to live inside it. That is a fact about the key rather than about
 * typing text, which is why it is here and not there.
 *
 * Any other key keeps its repeat beside flick arms (#865): a still hold
 * repeats, and a flick that leaves the key before the repeat starts is an arm,
 * as on the delete and arrow keys.
 */
fun Key.canRepeatOnHold(): Boolean =
    (flick.isEmpty() || (action != KeyAction.Text && action !is KeyAction.KeymanKey)) && action.canRepeatOnHold()

/**
 * Whether a held finger on this key fires it over and over.
 *
 * The single answer three things need and used to each spell out: the pointer
 * handler that starts the repeat loop, the key sheet that offers the switch, and
 * the settings screen that names the cadence. Backspace and forward delete
 * always repeat; a text-editing key repeats when its operation does; the
 * spacebar repeats unless alternates have taken its hold (issue #57); and any
 * key at all repeats when its author said so ([Key.repeatOnHold]).
 */
fun Key.holdRepeats(): Boolean =
    repeatOnHold ||
        action == KeyAction.Delete ||
        action == KeyAction.ForwardDelete ||
        (action == KeyAction.Space && !opensAlternatesPopup()) ||
        (action as? KeyAction.Edit)?.op?.repeats == true

/**
 * Column counts a fixed alternates grid may be set to, on [Key.alternateColumns]
 * or on the global setting behind it.
 *
 * 0 is deliberately outside it: that value is "no count", not a count of none.
 * Below three a popup is a list rather than a grid, and past eleven the entries
 * are narrower than a fingertip on a phone.
 */
val AlternateColumnsRange: IntRange = 3..11

/** One entry of the alternates popup: a character to type, or an action to run. */
sealed interface AlternateEntry {
    /** One of [Key.longPress] — committed with the key's text path. */
    @JvmInline
    value class Character(val text: String) : AlternateEntry

    /** One of [Key.actionAlternates] — committed as a key press. */
    @JvmInline
    value class Action(val alternate: KeyAlternate) : AlternateEntry
}

/**
 * The popup's entries in the one order everything must agree on: the characters
 * with the actions as one block at [Key.actionAlternatesAt], which is after the
 * last character unless the key says otherwise.
 *
 * A function rather than a rule each reader re-implements, because the index is
 * the entry's identity. Two places consume it — the popup's draw and the
 * hold-to-select commit — and when they disagreed by one the popup highlighted
 * à and committed select-all. Position 0 also matters on its own: with
 * hold-to-select on it is what a plain hold-and-release fires, which is why an
 * action put first runs on a plain hold.
 */
fun Key.alternateEntries(): List<AlternateEntry> {
    // A Keyman key's long-press entries are keys of their own, with their own
    // id and layer for the rules to match on, so they go out as key presses
    // rather than as the text on their caps.
    val keyman = (action as? KeyAction.KeymanKey)?.longPress?.takeIf { it.size == longPress.size }
    val characters = longPress.mapIndexed { i, text ->
        keyman?.get(i)?.let { AlternateEntry.Action(KeyAlternate(action = it.toAction(), label = text)) }
            ?: AlternateEntry.Character(text)
    }
    val actions = actionAlternates.map { AlternateEntry.Action(it) }
    val at = actionsIndex(characters.size)
    if (at >= characters.size) return characters + actions
    return characters.subList(0, at) + actions + characters.subList(at, characters.size)
}

/**
 * Where the action block starts among [characters] popup characters; see
 * [Key.actionAlternatesAt]. A negative index is a broken file, read as the
 * default rather than as the front.
 */
private fun Key.actionsIndex(characters: Int): Int {
    val at = actionAlternatesAt?.takeIf { it >= 0 } ?: if (actionAlternatesFirst) 0 else characters
    return at.coerceAtMost(characters)
}

/**
 * The key a flick towards [direction] commits: the flick's own Keyman key when
 * this is a Keyman key that has one, else this key typing the flick's text.
 */
fun Key.flickKey(direction: FlickDirection): Key? {
    when (val arm = flickArm(direction)) {
        null -> return null
        // Pressed the way a popup's action entry is, so the arm does exactly
        // what the same action does as an alternate.
        is FlickArm.Action -> return Key(label = arm.alternate.label, action = arm.alternate.action)
        is FlickArm.Text -> {
            // A text arm on a key that does something else (#789): Enter's
            // flick up typing `?` types it, and is not Enter.
            if (action != KeyAction.Text && action !is KeyAction.KeymanKey) {
                return Key(label = arm.text, shiftLabel = flickShift[direction])
            }
            val target = (action as? KeyAction.KeymanKey)?.flick?.get(direction)
            // The arm's own shift form, never the centre's: under Shift the
            // flick used to type the tap's shiftLabel, whatever arm it took.
                ?: return copy(output = arm.text, shiftLabel = flickShift[direction])
            return Key(label = arm.text, output = target.text, action = target.toAction())
        }
    }
}

/**
 * The key tap [step] of a multitap run presses: this key for step 0, else the
 * cycle's entry. On a Keyman key that is the entry's own Keyman key, typing its
 * [KeymanTarget.text] with no rules loaded; otherwise this key typing the
 * entry's text. Either way the result carries no cycle of its own.
 */
fun Key.multitapKey(step: Int): Key {
    val text = multitap.getOrNull(step - 1) ?: return this
    val target = (action as? KeyAction.KeymanKey)?.multitap?.takeIf { it.size == multitap.size }?.get(step - 1)
        ?: return copy(output = text, shiftLabel = null, multitap = emptyList())
    return Key(label = text, output = target.text, action = target.toAction(), width = width)
}

/**
 * What a [Key.labelScale] is honoured at.
 *
 * The floor is below the corner-hint size, so a layout can genuinely annotate a
 * key rather than only shrink it a little; the ceiling is where a label stops
 * fitting a key it shares a row with.
 */
val KeyLabelScaleRange = 0.3f..2f

/** What a [Key.hintScale] is honoured at. */
val KeyHintScaleRange = 0.5f..3f

/** This key's [Key.hintScale], clamped; 1 when it follows the setting. */
fun Key.drawnHintScale(): Float =
    hintScale?.takeIf { it.isFinite() }?.coerceIn(KeyHintScaleRange) ?: 1f

/**
 * This key's [Key.labelScale] as a drawer should use it: clamped, and null when
 * the key wants the automatic size.
 *
 * Both the keyboard and the layout editor's preview go through here so a key
 * cannot be shown at one size and typed on at another, which is the same
 * arrangement [rowScaledKeyHeight] exists for.
 */
fun Key.drawnLabelScale(): Float? =
    labelScale?.takeIf { it.isFinite() }?.coerceIn(KeyLabelScaleRange)

/**
 * The multiplier a layout's [LayoutAppearance.fontScale] puts on every label of
 * its grid: clamped to [LayoutFontScaleRange], and 1.0 for the layouts (all the
 * shipped ones) that ask for nothing.
 */
fun LayoutAppearance?.drawnFontScale(): Float =
    this?.fontScale?.takeIf { it.isFinite() }?.coerceIn(LayoutFontScaleRange) ?: 1f

/**
 * A compiled grid, ready to render. Produced by [LayoutSpec.compile] and never
 * stored — deliberately *not* `@Serializable`, so the type system says which of
 * the two is the wire format.
 */
data class KeyboardLayout(
    val name: String,
    val rows: List<List<Key>>,
    /**
     * Per-row height multipliers, positionally aligned with [rows]; null (the
     * usual case) means every row uses the standard key height. A missing or
     * short entry defaults to 1.0. Carried over verbatim from [LayerSpec].
     */
    val rowHeights: List<Float>? = null,
    /**
     * The label font and size the layout this grid came from asked for, carried
     * here for the same reason [rowHeights] is: the renderer is handed the
     * compiled grid, not the spec, and the editor's preview has to be able to
     * draw the same thing the keyboard will.
     */
    val appearance: LayoutAppearance? = null,
    /**
     * Whether this grid stays on screen across a close and reopen of the
     * keyboard; see [LayerSpec.persistent]. Carried on the compiled grid for
     * the same reason [rowHeights] is: the service decides what to do when a
     * field opens by looking at the grid it is showing, not at the spec.
     */
    val persistent: Boolean = false,
    /**
     * Whether the Bottom row settings leave this grid alone; see
     * [LayerSpec.bottomRowAsLaidOut]. On the compiled grid because
     * [arrangedBy] is handed the grid, not the spec.
     */
    val bottomRowAsLaidOut: Boolean = false,
    /**
     * The theme this grid asks to be drawn in: the layer's own, else the
     * layout's, else null for "whatever is set" (issue #61). Resolved here so
     * the one place that picks the board's theme reads one field of the grid
     * on screen rather than re-deriving the layer-beats-layout rule.
     */
    val themeId: String? = null,
    /** [LayerSpec.flickHints], carried onto the grid on screen; null follows the setting. */
    val flickHints: Boolean? = null,
    /** [LayerSpec.keymanFrames], carried onto the grid on screen. */
    val keymanFrames: Map<String, KeymanTarget> = emptyMap(),
)

/**
 * The width every row is laid out against: the width the most rows share.
 *
 * Rows narrower than this are centred with equal padding on both sides, which
 * is how QWERTY's nine-key home row sits under its ten-key top row. Rows *wider*
 * than it deliberately overflow into narrower keys rather than being scaled —
 * Dvorak's third row is eleven wide against a grid weight of ten and has shipped
 * that way.
 *
 * Keying off the *most common* width rather than the first row's makes a lone
 * outlier row an outlier and nothing more. The first row was tempting but
 * breaks the moment a narrow row is inserted at the top: a single width-1 key
 * would set the grid weight to 1 and then fill the whole width. The maximum
 * breaks the other way, padding Dvorak's ten-key top rows to match its eleven-
 * key third row. The mode leaves every shipped layout on its historical grid
 * (each has a clear majority of ten-wide rows, Dvorak included) while treating
 * both a narrow top insert and a wide overflow row as the outliers they are.
 *
 * Widths are bucketed on a rounded key so accumulated-float jitter can't split
 * one width into two buckets; ties between equally common widths resolve to the
 * wider one.
 *
 * A row under a spanning key is measured *including* the column that key holds
 * over it — see [spanRowWidths]. Without that, the row below a two-row Enter
 * reads as one column narrower than it is drawn, and a grid with two such rows
 * would elect the wrong width for every other row on the board.
 */
fun gridWeightOf(rows: List<List<Key>>): Float {
    if (rows.isEmpty()) return 0f
    val widths = spanRowWidths(rows).toList()
    return widths
        .groupBy { (it * 100f).roundToInt() }
        .entries
        .maxWith(compareBy({ it.value.size }, { it.key }))
        .value.first()
}

/**
 * Half the slack between [gridWeight] and this row's own width, as a layout
 * weight. Negative for an over-wide row; callers drop the spacer below a small
 * epsilon rather than testing for zero, because these are accumulated floats.
 *
 * For rows a spanning key reaches into, [spanSlots] does this and the flow around
 * the span in one pass — a row cannot be centred from its own contents alone once
 * something above it is standing in one of its columns.
 */
fun sidePadFor(row: List<Key>, gridWeight: Float): Float =
    (gridWeight - row.sumOf { it.width.toDouble() }.toFloat()) / 2f

/**
 * [rows] with the spacebar of any short row widened to fill the grid.
 *
 * Centring a short row is right for a row of letters — that is how QWERTY's
 * nine-key home row sits under its ten-key top row — and wrong for the row the
 * spacebar is on. No keyboard puts a gap either side of its bottom row, and the
 * gap is what a grid wider than ten columns produced: the bottom row is written
 * from the same ten-column template on every layout, so a board whose letters
 * are eleven or twelve wide drew `?123` and Enter floating a whole column in
 * from the edges (issue: the Persian grid, and 256 others with it).
 *
 * Widening the spacebar rather than scaling the whole row keeps `?123`, Enter
 * and the punctuation keys the width the author gave them, which is the width
 * they have on every other layout. It is the rule [expandForTablet] already
 * applies to the row it rebuilds, now applied to the row as written.
 *
 * A row with no spacebar, or with more than one, is left to [sidePadFor]: there
 * is no single key that obviously owns the slack, and centring is never wrong,
 * only narrow. Rows are measured with the columns a spanning key holds over
 * them, so a row under a two-row Enter is not widened into it.
 */
fun fillSpaceRows(rows: List<List<Key>>, gridWeight: Float): List<List<Key>> {
    if (rows.isEmpty() || !gridWeight.isFinite() || gridWeight <= 0f) return rows
    val widths = spanRowWidths(rows)
    var changed = false
    val filled = rows.mapIndexed { index, row ->
        val slack = gridWeight - widths[index]
        if (slack <= SpaceFillEpsilon) return@mapIndexed row
        val at = row.indexOfSingle { it.action == KeyAction.Space } ?: return@mapIndexed row
        changed = true
        row.mapIndexed { column, key ->
            if (column == at) key.copy(width = roundGridUnit(key.width + slack)) else key
        }
    }
    return if (changed) filled else rows
}

/** The slack below which a row already fills the grid; these are float sums. */
private const val SpaceFillEpsilon = 0.01f

/** The index of the one element matching [predicate], or null for none or many. */
private inline fun <T> List<T>.indexOfSingle(predicate: (T) -> Boolean): Int? {
    var found = -1
    for (index in indices) {
        if (!predicate(this[index])) continue
        if (found >= 0) return null
        found = index
    }
    return found.takeIf { it >= 0 }
}

/**
 * The resolution a [Key.width] or a [LayerSpec.rowHeights] entry is edited at:
 * two decimals.
 *
 * The editor used to move both in quarter steps, which cannot express the width
 * a row of seven keys needs to fill a ten-wide grid (10 ÷ 7 = 1.43). Two
 * decimals can, and it is also the precision every one of these numbers is
 * *shown* at, so what the screen says is what the file holds. A free float is
 * the thing this is not: people hand-edit these layouts as JSON, and 1.4285715
 * in a file is noise no one can read or type back.
 *
 * One hundredth of a grid unit is about a third of a dp on a phone, so nothing
 * is lost by rounding to it.
 */
const val GridUnitStep = 0.01f

/**
 * [value] at [GridUnitStep] resolution.
 *
 * Non-finite and absurd values pass through untouched rather than saturating
 * through `Int`: a layout from a file can hold anything, and clamping it is
 * `repair`'s job, not this function's.
 */
fun roundGridUnit(value: Float): Float =
    if (!value.isFinite() || value > 1e6f || value < -1e6f) {
        value
    } else {
        (value * 100f).roundToInt() / 100f
    }

/**
 * [row] scaled so its keys add up to [gridWeight], keeping every key's share of
 * the row.
 *
 * The fix for the one thing quarter steps made unreachable: a row of keys of
 * *different* sizes that has to land exactly on the grid. Scaling keeps the
 * proportions the author chose, where widening one key to fill the slack (the
 * key sheet's "Fill the row") changes them.
 *
 * The row has to land *exactly*, or the warning this is the button for stays on
 * screen after pressing it. Rounding each key on its own does not: twenty-four
 * keys each losing up to half a hundredth leave the row an eighth of a key
 * short. So the arithmetic is done in whole hundredths and the leftover ones are
 * handed out largest-fraction-first, the way seats are apportioned — every key
 * ends within one hundredth of its exact share, and the row sums to the target
 * by construction rather than by luck.
 */
fun fitRowToGrid(row: List<Key>, gridWeight: Float): List<Key> {
    if (row.isEmpty() || !gridWeight.isFinite() || gridWeight <= 0f) return row
    // Far past MaxRowWidth. A grid weight this size is a broken file rather than
    // a wide layout, and scaling to it would write widths repair has to undo.
    if (gridWeight > MaxRowWidth * 10f) return row
    val total = row.sumOf { it.width.toDouble() }
    if (!total.isFinite() || total <= 0.0) return row
    val target = (gridWeight.toDouble() * 100.0).roundToInt()
    // Not enough to give every key the hundredth that keeps it from validating
    // as a zero-width key.
    if (target < row.size) return row

    val scale = target / total
    val exact = row.map { it.width.toDouble() * scale }
    val units = exact.mapTo(ArrayList(row.size)) { floor(it).toInt().coerceAtLeast(1) }
    var slack = target - units.sum()

    // The hundredths that fell off the floor go to the keys that lost the most.
    if (slack > 0) {
        val byFraction = exact.indices.sortedByDescending { exact[it] - floor(exact[it]) }
        var i = 0
        while (slack > 0) {
            units[byFraction[i % byFraction.size]]++
            slack--
            i++
        }
    }
    // The other direction: keys floored up to one hundredth can overshoot the
    // target, and the widest keys are where a hundredth shows least.
    if (slack < 0) {
        val byWidth = exact.indices.sortedByDescending { exact[it] }
        var i = 0
        while (slack < 0 && i < byWidth.size * (target + 1)) {
            val index = byWidth[i % byWidth.size]
            if (units[index] > 1) {
                units[index]--
                slack++
            }
            i++
        }
    }
    return row.mapIndexed { index, key -> key.copy(width = units[index] / 100f) }
}

/**
 * A row's key height in dp after applying its optional [scale] multiplier,
 * clamped to a sane range and rounded to whole dp. A null or 1.0 scale (the
 * common case) returns [baseKeyHeightDp] untouched.
 *
 * Lives here rather than beside the render loop because three places need the
 * same answer: the loop that draws the row, the pass that reserves height for
 * it, and the layout editor's preview. The editor is in another module, and a
 * preview that disagreed with the keyboard about how tall a row is would be
 * showing the user a keyboard they do not have.
 */
fun rowScaledKeyHeight(baseKeyHeightDp: Int, scale: Float?): Int =
    if (scale == null || scale == 1f) {
        baseKeyHeightDp
    } else {
        (baseKeyHeightDp * scale.coerceIn(MinRowHeightScale, MaxRowHeightScale))
            .roundToInt()
            .coerceAtLeast(1)
    }

/** The shortest a per-row height multiplier is honoured at. */
const val MinRowHeightScale = 0.4f

/** The tallest a per-row height multiplier is honoured at. */
const val MaxRowHeightScale = 2.5f

/**
 * The flick arms this key actually types: [Key.flick] on a key that commits
 * text (issue #547) and, since #789, on any key that [takesFlickActions], so
 * Enter can type `?` on its flick up. A key whose arms are not read (a braille
 * dot, a key told to repeat) draws and reserves none. The layout editor drops a
 * key's text arms when its action changes (#838), so a letter turned into an
 * action key does not bring the letter's arms along.
 */
val Key.activeFlick: Map<FlickDirection, String>
    get() = if (action == KeyAction.Text || action is KeyAction.KeymanKey || takesFlickActions()) flick else emptyMap()

/** One arm of a key's flick cross: text it types, or an action it runs. */
sealed interface FlickArm {
    /** One of [Key.flick]. */
    @JvmInline
    value class Text(val text: String) : FlickArm

    /** One of [Key.flickActions]. */
    @JvmInline
    value class Action(val alternate: KeyAlternate) : FlickArm
}

/**
 * Whether [Key.flickActions] work on this key (issue #549).
 *
 * Every key but the ones whose drag or hold is already the gesture: a braille
 * dot's chord and a component's cell. A flick arm on one of those would either
 * never be reached or take away what the key is for, so the editor does not
 * offer them and the keyboard does not read them.
 *
 * The delete keys are the exception (issue #769): their hold repeats and their
 * sideways drag deletes by the word, but a delete only lands on the lift, so a
 * flick that has left the key before the repeat starts can still be read as
 * one. Forward delete on a flick of ⌫ is the arm people ask for. The delete
 * key's own handler gives an armed direction to the arm and leaves the swipe
 * the directions that have none.
 *
 * So are the caret keys whose hold repeats (#865): a still hold still
 * repeats, and a flick before the repeat starts is an arm, word left or
 * right on an arrow, page up or down. A key its author told to repeat while
 * held is one too (#865): the arrow row's keys are raw arrow key presses
 * told to repeat, and they are the arrows people put word and page moves on.
 *
 * And so is the space bar (#863), on the delete key's terms: its swipes and
 * hold are its own, but an arm takes the directions it covers, and the swipes
 * keep the rest. No space on a flick of the space bar is the arm asked for.
 */
fun Key.takesFlickActions(): Boolean =
    action == KeyAction.Space || !action.holdIsSpokenFor() || action.deletesBackward() || action.deletesForward() ||
        (action as? KeyAction.Edit)?.op?.repeats == true

/**
 * What a flick towards [direction] does: an action arm where the key has one
 * it can use, else the text arm, else null for no arm that way. The one answer
 * the gesture, the cross popup and the editor's preview all read, so none of
 * them can show an arm another ignores.
 */
fun Key.flickArm(direction: FlickDirection): FlickArm? {
    flickActions[direction]?.takeIf { takesFlickActions() }?.let { return FlickArm.Action(it) }
    return activeFlick[direction]?.takeIf { it.isNotEmpty() }?.let { FlickArm.Text(it) }
}

/** Whether a flick off this key does anything at all; see [flickArm]. */
fun Key.hasFlicks(): Boolean =
    activeFlick.isNotEmpty() || (flickActions.isNotEmpty() && takesFlickActions())

/**
 * Whether this key is all swipes and no tap (issue #410): a text key with no
 * centre to draw or type, only flick arms. Unexpected Keyboard has these, a key
 * with corner swipes and no `c`, and the layout editor makes one when the
 * label is left empty on a key that has arms. A tap on it types nothing; the
 * swipes are the key.
 */
fun Key.isSwipeOnly(): Boolean =
    action == KeyAction.Text && label.isEmpty() && output.isNullOrEmpty() && hasFlicks()

/**
 * Whether a finger held on this flick arm runs it again and again: the arms
 * that move the caret or delete, which repeat the same way the keys for them do.
 * Asked of the key [flickKey] hands back.
 */
fun Key.flickArmRepeats(): Boolean =
    action.deletesBackward() || action.deletesForward() || (action as? KeyAction.Edit)?.op?.repeats == true

/**
 * Which way up the screen has to be for a key to exist. A duplicate `g` and `v`
 * for two thumbs is worth a key sideways, where the board is wide, and costs one
 * upright, where it isn't.
 */
@Serializable
enum class KeyScreen { ALWAYS, PORTRAIT, LANDSCAPE }

/**
 * This grid with only the keys [landscape] calls for. A row left with no keys
 * goes, with its height. The same grid back when no key asks, which is nearly
 * every grid, so a rotation costs them nothing.
 */
fun KeyboardLayout.forScreen(landscape: Boolean): KeyboardLayout {
    val gone = if (landscape) KeyScreen.PORTRAIT else KeyScreen.LANDSCAPE
    if (rows.none { row -> row.any { it.showOn == gone } }) return this
    val kept = rows.withIndex()
        .map { (index, row) -> index to row.filterNot { it.showOn == gone } }
        .filter { (_, row) -> row.isNotEmpty() }
    return copy(
        rows = kept.map { (_, row) -> row },
        rowHeights = rowHeights?.let { heights -> kept.map { (index, _) -> heights.getOrElse(index) { 1f } } },
    )
}

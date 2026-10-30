package com.wasimaster.wmkeyboard.ime

import android.view.KeyEvent
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.otp.NotificationOtp
import com.wasimaster.wmkeyboard.core.emoji.AnimatedEmoji
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
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
import com.wasimaster.wmkeyboard.core.layout.hasAmbiguousKeys
import com.wasimaster.wmkeyboard.core.layout.hasKanaVariantKeys
import com.wasimaster.wmkeyboard.core.layout.isAmbiguous
import com.wasimaster.wmkeyboard.core.layout.letterSet
import com.wasimaster.wmkeyboard.core.media.MediaSnapshot
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.NoComposer
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymGroup
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymSource
import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.prediction.GlideSandboxPolicy
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord
import com.wasimaster.wmkeyboard.core.prediction.WordFacts
import com.wasimaster.wmkeyboard.core.settings.GrammarLintKind
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.RankControl
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.TransliterationHintMode
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import com.wasimaster.wmkeyboard.core.settings.interactiveTyping
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFolder
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.AiPhase
import com.wasimaster.wmkeyboard.core.tools.ReplyContext
import com.wasimaster.wmkeyboard.core.tools.ReplySource
import com.wasimaster.wmkeyboard.core.tools.ReplyTurn
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
 * Whether shift turns what a key types into its capital right now: shift on or
 * locked, on a board that does not shape clusters (a Bengali fixed layout reads
 * shift as a different letter, never as case).
 *
 * The one rule the service's key output and the long-press popup both read
 * (issue #211). An alternate picked out of a popup is typed as a plain key, so
 * shift has always capitalised it on the way out; the popup drew it lowercase
 * all the same, and on a board full of accents that hid the capitals.
 */
fun KeyboardUiState.shiftCasesText(): Boolean =
    shiftState != ShiftState.OFF && !composer.isClusterShaping

/** [text] as a key with no shift label of its own types it: see [shiftCasesText]. */
fun shiftCased(text: String, shifted: Boolean): String = if (shifted) text.uppercase() else text

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
 * [word] wearing the capitals of [replaced] — the word already standing in the
 * field that a strip pick is about to overwrite (issue #212).
 *
 * [displayCaseForShift] cannot answer for these picks. It reads the live shift
 * state, and by the time the strip is showing a committed word's alternates
 * that shift is spent: the commit that wrote the word consumed it. Auto-
 * capitalize is where it shows, because there the capital is never a keystroke
 * the user could still be holding — glide "van" at the start of a sentence,
 * take "can" off the strip, and the sentence used to restart in lower case.
 *
 * Only the shape of the capitals carries over, never the spelling: a shout
 * ("VAN") shouts the replacement, a leading capital capitalizes it, and
 * anything else leaves the word as the engine offered it. A null or empty
 * [replaced] means the pick is not replacing anything, so it says nothing.
 *
 * Letters are counted by code point, or a cased script outside the BMP reads
 * as "no letters at all" and every replacement for it would be shouted — the
 * same trap `WordCase.kt` in :core:prediction was written for, mirrored here
 * because those helpers are internal to that module.
 *
 * [caseIsSpelling] is the language's [LanguageDef.letterCaseIsSpelling]: in
 * Klingon a capital is a letter, so the shape of the word being replaced says
 * nothing about the one replacing it, and `qaH` picked over `QaH` stays `qaH`.
 */
fun caseLike(word: String, replaced: String?, caseIsSpelling: Boolean = false): String {
    if (caseIsSpelling || word.isEmpty() || replaced.isNullOrEmpty() || '@' in word) return word
    var at = 0
    var letters = 0
    var allUpper = true
    while (at < replaced.length) {
        val cp = replaced.codePointAt(at)
        if (Character.isLetter(cp)) {
            letters++
            if (!Character.isUpperCase(cp)) allUpper = false
        }
        at += Character.charCount(cp)
    }
    // No letters at all ("42", "--") says nothing about capitals.
    if (letters == 0) return word
    if (letters > 1 && allUpper) return word.uppercase()
    if (!Character.isUpperCase(replaced.codePointAt(0))) return word
    val first = word.codePointAt(0)
    val upper = Character.toUpperCase(first)
    if (upper == first) return word
    return String(Character.toChars(upper)) + word.substring(Character.charCount(first))
}

/**
 * The shift a glide commits under, and is previewed under.
 *
 * The board's own state, unless the stroke drew through the shift key and
 * answered for itself (#115): once for a capital, twice for a shout, the same
 * ladder tapping the key walks up. It overrides rather than combines, so a
 * stroke that says "capital" gets a capital whatever the board was doing — the
 * user drew the instruction after they saw the board.
 *
 * Under the per-letter reading (#163) the crossings name letters rather than
 * a shift state, and those are applied after this — see
 * [com.wasimaster.wmkeyboard.core.gesture.GlideCase.Letters.caseWord] — so
 * the board's own shift still stands; only a stroke that *ended* on the key
 * speaks here, as a shout.
 *
 * Here, beside [displayCaseForShift], rather than in the service: the pill
 * and the strip case the mid-stroke preview with it too (#162), and the one
 * ladder is what keeps the word on screen the word that lands.
 */
fun shiftForGlide(board: ShiftState, case: GlideCase): ShiftState = when (case) {
    is GlideCase.Word -> when {
        case.times >= 2 -> ShiftState.CAPS_LOCK
        case.times == 1 -> ShiftState.ON
        else -> board
    }
    is GlideCase.Letters -> if (case.shout) ShiftState.CAPS_LOCK else board
    GlideCase.None -> board
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
enum class LayoutMode {
    LETTERS,
    SYMBOLS,
    SYMBOLS_SHIFTED,
    FN,
    SECONDARY,

    /**
     * One of a layout's own further layers — a converted Keyman layout's
     * right-Alt page or page of one consonant's vowel forms, or page three of
     * four on a paginated alphabet (issue #498) — named by
     * [KeyboardUiState.namedLayer] and drawn from [LayoutSet.named]. Keyman
     * reaches these by key and by rule, so they are part of the layout rather
     * than something the user switched to, and the grid is sized for them.
     */
    NAMED,
}

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
     * Whether the layout authored [numeric] itself rather than inheriting the
     * shipped keypad. An authored one is drawn as laid out; a shipped one takes
     * its backspace and enter from the user's own Numpad (issue #508).
     */
    val numericAuthored: Boolean = false,
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
     * The row the symbols layer draws in place of its own digit row while the
     * number row is on, when this layout authored one; null takes
     * [com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.SYMBOLS_FILL_ROW].
     */
    val symbolsFillRow: List<Key>? = null,
    /**
     * Arrow keys rows this layout authored, by the layer they sit under
     * (issue #769). Absent entries draw the four arrows in the setting's order.
     */
    val arrowRows: Map<LayoutMode, List<Key>> = emptyMap(),
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
    /**
     * The panel grids this layout carries of its own (issue #63), repaired,
     * by panel. Absent panels draw the user's shared panel layout, or the
     * shipped one — see `KeyboardUiState.panelLayout`.
     */
    val panels: Map<PanelKind, PanelLayoutSpec> = emptyMap(),
    /**
     * The layout's own theme (`LayoutSpec.themeId`), before any layer lays its
     * own over it. The compiled grids carry the merged answer, which is right
     * for drawing the grid and wrong for a panel opened over it: a panel with
     * no theme of its own inherits the *layout's*, as the editor shows it, not
     * the letters layer's (issue #196).
     */
    val themeId: String? = null,
    /**
     * A converted Keyman layout's `shift` layer, drawn in place of [letters]
     * while shift is on. It is the author's own set of shifted keys — often
     * with other ids, layers and long presses than the keys under them — so it
     * cannot be derived from [letters]. Null for every other layout.
     */
    val keymanShift: KeyboardLayout? = null,
    /** The same for Keyman's `caps` layer, drawn while caps lock is on. */
    val keymanCaps: KeyboardLayout? = null,
    /**
     * A layout's further grids, by their key in the layout (see
     * [LayoutMode.NAMED]): a converted Keyman layout's extra layers, and the
     * pages of a paginated layout. Empty for every other layout.
     */
    val named: Map<String, KeyboardLayout> = emptyMap(),
    /**
     * Every layer a converted Keyman layout defines, by its key; null for any
     * other layout. A Keyman switch to a layer not in here lands on the
     * letters, as KeymanWeb's does — the symbols pages our own grids would
     * otherwise lend it are not the keyboard's.
     */
    val keymanLayerKeys: Set<String>? = null,
    /**
     * Whether the letters layer is a handwriting canvas over [letters]' one
     * row (`LayoutSpec.handwriting`, issue #557).
     */
    val handwriting: Boolean = false,
    /**
     * The letter keys whose long-press order the user wrote (issue #622), which
     * the "your language's letters first" pass leaves as written. See
     * [authoredHolds].
     */
    val authoredHolds: Set<Key> = emptySet(),
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
        keymanShift?.rows?.size ?: 0,
        keymanCaps?.rows?.size ?: 0,
        named.values.maxOfOrNull { it.rows.size } ?: 0,
    ).coerceAtLeast(1)

    /**
     * Whether a key on one of the layers the ?123 and Fn keys cycle through can
     * stand in for the 小゛゜ key (see [Key.kanaVariantWhileComposing]). The
     * service publishes [KeyboardUiState.kanaVariantReady] only when this holds,
     * because that flag is a key of the grid's `remember`: every other board
     * would otherwise rebuild its keys on the kana-by-kana flips it makes.
     *
     * Not a constructor property, so it stays out of equals/hashCode/copy.
     */
    val hasKanaVariantKeys: Boolean = letters.hasKanaVariantKeys() ||
        symbols.hasKanaVariantKeys() ||
        symbolsShifted.hasKanaVariantKeys() ||
        fn?.hasKanaVariantKeys() == true

    /**
     * The letter each multi-letter key is anchored by on the glide grid, for
     * the keys [glideAnchor] gives none: Klingon's `ch`, `gh`, `ng` and `tlh`
     * keys each write a whole letter of its alphabet that Latin spells with two
     * or three. Such a key stands for the first of its letters that no
     * single-letter key on the board writes, so `ch` carries the `c` a Klingon
     * board has nowhere else, while `gh`, whose g and h both have keys of their
     * own, stays off the grid and a swipe takes those from their own keys.
     * Without this, every word with a `ch` was out of a swipe's reach.
     *
     * Declared before [letterAlphabet], whose initializer reads it, and lazy so
     * it stays out of equals/hashCode/copy.
     */
    val digraphAnchors: Map<Key, Int> by lazy {
        val singles = HashSet<Int>()
        for (row in letters.rows) {
            for (key in row) {
                if (key.action != KeyAction.Text) continue
                key.glideAnchor()?.let(singles::add)
                for (letter in key.letterSet()) singles.add(Character.toLowerCase(letter.code))
            }
        }
        val out = HashMap<Key, Int>()
        val claimed = HashSet<Int>()
        for (row in letters.rows) {
            for (key in row) {
                if (key.action != KeyAction.Text || key.glideAnchor() != null) continue
                val text = key.output ?: key.label
                if (text.length !in 2..MAX_DIGRAPH_LETTERS || !text.all { it.isLetter() }) continue
                val free = text.lowercase().firstOrNull { it.code !in singles && it.code !in claimed } ?: continue
                claimed.add(free.code)
                out[key] = free.code
            }
        }
        out
    }

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
    val letterAlphabet: Set<Int> = buildSet {
        for (row in letters.rows) {
            for (key in row) {
                if (key.action != KeyAction.Text) continue
                fun take(spelling: String?) {
                    val label = spelling ?: return
                    addAll(keySpelling(label) ?: return)
                    composedKeyChar(label)?.let { add(it.code) }
                    decomposedKeyChars(label)?.forEach { add(it.code) }
                }
                // Every letter an ambiguous key stands for, not only the anchor
                // it commits: a T9 keypad has eight letter keys and twenty-six
                // letters, and taking the anchors alone would tell the decoder
                // the board cannot write most of the alphabet. A set is a set,
                // so the duplicate the anchor makes here costs nothing.
                for (letter in key.letterSet()) add(Character.toLowerCase(letter.code))
                digraphAnchors[key]?.let(::add)
                take(key.output ?: key.label)
                take(key.shiftLabel)
                for (alternate in key.longPress) take(alternate)
            }
        }
    }

    /**
     * Every character the letters layer can write, mapped to the anchor code
     * point of the key that writes it — the octopus's lookup (discussion #102).
     *
     * Answers "which key would type this next character", which is the whole of
     * the octopus's promise: a word floats over the key that continues it, and
     * a character with no key here simply does not get a word.
     *
     * The same three passes and the same spelling helpers as [glideKeys], so
     * composed and decomposed spellings (ড়, ঢ়, য়), combining marks and letters
     * outside the BMP all resolve to a key. Base and shift always; long presses
     * only when [longPress] asks, because a press of that key would not produce
     * the character and a word promising otherwise is a lie about the key.
     *
     * Earlier passes win, matching [glideKeys]: a character that is one key's
     * base label and another's long-press alternate belongs to the key that
     * shows it.
     *
     * `by lazy` rather than a constructor property, unlike its two neighbours:
     * the octopus is off by default and a board that never draws it should not
     * pay for this, and a lazy field stays out of equals/hashCode/copy.
     */
    fun keyAnchors(longPress: Boolean): Map<Int, Int> =
        if (longPress) anchorsWithLongPress else anchorsByPress

    private val anchorsByPress: Map<Int, Int> by lazy { buildKeyAnchors(longPress = false) }
    private val anchorsWithLongPress: Map<Int, Int> by lazy { buildKeyAnchors(longPress = true) }

    private fun buildKeyAnchors(longPress: Boolean): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        fun claim(spelling: String?, anchor: Int) {
            val label = spelling ?: return
            fun put(codePoint: Int) = out.putIfAbsent(codePoint, anchor)
            keySpelling(label)?.forEach(::put) ?: return
            composedKeyChar(label)?.let { put(it.code) }
            decomposedKeyChars(label)?.forEach { put(it.code) }
        }
        val passes = if (longPress) PASSES else LONG_PRESS_PASS
        for (pass in 0 until passes) {
            for (row in letters.rows) {
                for (key in row) {
                    if (key.action != KeyAction.Text) continue
                    val anchor = key.glideAnchor() ?: digraphAnchors[key] ?: continue
                    when (pass) {
                        // An ambiguous key's whole set answers to its one
                        // anchor, so a word continuing with any letter of a T9
                        // key floats over that key.
                        BASE_PASS -> if (key.isAmbiguous()) {
                            for (letter in key.letterSet()) {
                                out.putIfAbsent(Character.toLowerCase(letter.code), anchor)
                            }
                        } else {
                            claim(key.output ?: key.label, anchor)
                            digraphAnchors[key]?.let { out.putIfAbsent(it, anchor) }
                        }
                        SHIFT_PASS -> claim(key.shiftLabel, anchor)
                        else -> for (alternate in key.longPress) claim(alternate, anchor)
                    }
                }
            }
        }
        return out
    }

    /**
     * Whether the letters grid puts more than one letter on a key, so a tap
     * cannot say which of them was meant and the decoder has to read the
     * keystrokes as key sets rather than as characters (discussion #103).
     *
     * Computed once per layout set, like [rowSpan] and [letterAlphabet], and
     * read on every keystroke by the composing path — walking the grid there
     * would be a row scan per character typed.
     */
    val ambiguousKeys: Boolean = letters.hasAmbiguousKeys()

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
     *
     * [carriers] are the keys that type no letter themselves but carry one on
     * Shift, in the corner or behind a hold (#970), each at its measured
     * centre: a number-row digit with a letter on its hold, a symbol whose
     * shifted form is a letter. Emitted after every letter key's passes, and
     * only for a letter nothing on the grid claims yet, so a letter key always
     * keeps its own letters and a carrier whose letters are all taken adds no
     * key at all (see [carriedGlideSpellings]).
     */
    fun glideKeys(
        apostropheCenter: Pair<Float, Float>? = null,
        /** Non-letter keys the composer spells through, by character, at their centres (Khipro's `/`). */
        extraCenters: List<Pair<Int, Pair<Float, Float>>> = emptyList(),
        carriers: List<Pair<Key, Pair<Float, Float>>> = emptyList(),
        centerOf: (Int) -> Pair<Float, Float>?,
    ): List<KeyCenter> {
        val out = ArrayList<KeyCenter>(letters.rows.sumOf { it.size } * 2)
        fun emit(spelling: String?, x: Float, y: Float) {
            val label = spelling ?: return
            for (codePoint in keySpelling(label) ?: return) out.add(KeyCenter(codePoint, x, y))
            composedKeyChar(label)?.let { out.add(KeyCenter(it, x, y)) }
            decomposedKeyChars(label)?.forEach { out.add(KeyCenter(it, x, y)) }
        }
        apostropheCenter?.let { (x, y) ->
            // Both spellings: a word list may hold either, and the two are the
            // same keystroke as far as a finger is concerned.
            out.add(KeyCenter('\'', x, y))
            out.add(KeyCenter('’', x, y))
        }
        for ((codePoint, center) in extraCenters) out.add(KeyCenter(codePoint, center.first, center.second))
        for (pass in 0 until PASSES) {
            for (row in letters.rows) {
                for (key in row) {
                    if (key.action != KeyAction.Text) continue
                    val anchor = key.glideAnchor() ?: digraphAnchors[key] ?: continue
                    val (x, y) = centerOf(anchor) ?: continue
                    when (pass) {
                        // An ambiguous key's whole letter set lands on its one
                        // centre, which is all a swipe across a T9 or compact
                        // board needs: the decoder has resolved several
                        // characters to one key since Probhat's ক/খ, so nothing
                        // downstream of here changes at all.
                        //
                        // It replaces the ordinary emit rather than joining it,
                        // because the set already holds what that would have
                        // written — the key's anchor is the first letter of it —
                        // and a character listed twice is a wasted entry on a
                        // grid the decoder walks per trie edge. Only a key that
                        // carries a *choice* takes this path; a one-letter key
                        // is emitted exactly as it always was, composed and
                        // decomposed spellings and all.
                        BASE_PASS -> if (key.isAmbiguous()) {
                            for (letter in key.letterSet()) {
                                out.add(KeyCenter(Character.toLowerCase(letter.code), x, y))
                            }
                        } else {
                            emit(key.output ?: key.label, x, y)
                            digraphAnchors[key]?.let { out.add(KeyCenter(it, x, y)) }
                        }
                        SHIFT_PASS -> emit(key.shiftLabel, x, y)
                        else -> for (alternate in key.longPress) emit(alternate, x, y)
                    }
                }
            }
        }
        if (carriers.isNotEmpty()) {
            val claimed = out.mapTo(HashSet()) { Character.toLowerCase(it.codePoint) }
            for ((key, center) in carriers) {
                for (spelling in key.carriedGlideSpellings()) {
                    val letters = keySpelling(spelling) ?: continue
                    if (letters.all { it in claimed }) continue
                    val before = out.size
                    emit(spelling, center.first, center.second)
                    for (i in before until out.size) claimed.add(Character.toLowerCase(out[i].codePoint))
                }
            }
        }
        return out
    }

    companion object {
        /** Base labels, then shifted, then long presses — see [glideKeys]. */
        private const val BASE_PASS = 0
        private const val SHIFT_PASS = 1

        /** Where the long-press pass begins, and so how many passes precede it. */
        private const val LONG_PRESS_PASS = 2
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

/** A word on the recent-word bar (#787, #842): where it starts in the field, its spelling, and what else it could be. */
data class RecentBarWord(val start: Int, val word: String, val offers: List<String>)

enum class PanelMode {
    NONE, EMOJI, CLIPBOARD, SNIPPETS, TOOLBOX, TEXT_EDIT, TRACKPAD,
    COMPASS, LEVEL, MOON_PHASE, WEATHER, CALENDAR,
    THEMES, SOUND_HAPTICS, NUMPAD, HANDWRITING, CAMERA, DICTIONARY, VOCABULARY,
    TRANSLATE, GIF, STICKER, WEB_SEARCH, IMAGE_SEARCH,
    OCR, QR_SCAN, VOICE, GRAMMAR,
    WIKIPEDIA, SYMBOLS, CALCULATOR, UNIT_CONVERT, CURRENCY, QR_GEN, PASSWORD_GEN, AI,
    MODES, TYPING_TEST, MEDIA_CONTROL, PLUGINS, APP_LAUNCHER,

    /** A paired computer over KDE Connect (issue #285). See [KdeUi]. */
    KDE_CONNECT,

    /** The CJK candidate grid: the strip's overflow, opened from its chevron. */
    CANDIDATES,

    /**
     * The Secondary layout tool's picker: one button per secondary layout, shown
     * when the user has several and has not pinned one to the tool (issue
     * #62). A tap opens that layout and closes this.
     */
    CUSTOM_LAYOUTS,

    /**
     * Find and replace over the field, opened from the selection bar's Replace
     * chip. Two buffers of its own (see [FindReplaceUi]); the key rows stay up
     * to type into them.
     */
    FIND_REPLACE,

    /**
     * Learn from text (#174): the words in the field, or the selection, that the
     * keyboard does not know yet, to add in one go. See [LearnFromTextUi].
     */
    LEARN_FROM_TEXT,

    /**
     * The Notes tool (#560): a list of the user's notes, one open to read, or
     * one being written. See [NotesPanelUi]; its fields take the keys through
     * the `NOTE_*` capture targets.
     */
    NOTES,

    /**
     * The AI reply tool (#234): the other person's message, a tone, and the
     * drafts to pick from. See [AiReplyUi]; its own box takes the keys through
     * [CaptureTarget.AI_REPLY].
     */
    AI_REPLY,

    /** Emoji mixes: two emoji put together, beside emoji, GIFs and stickers. See [EmojiMixUi]. */
    EMOJI_MIX,

    /**
     * The internet speed test: a run against the chosen server, then the
     * result as a card to send. Its state is the controller's, see
     * `ime.speedtest.SpeedTestUi`.
     */
    SPEED_TEST,

    /**
     * The Location tool (#350): the phone's position or a saved place, to
     * insert as a map link, an address, coordinates or a Plus code. Its state
     * is the controller's, see `ime.location.LocationUi`.
     */
    LOCATION,

    /**
     * The Clock tool: world clocks, alarms, timers, stopwatch, planner, dates
     * and history. Its state is the controller's, see `ime.clock.ClockUi`; its
     * text box takes the keys through the `CLOCK_*` capture targets.
     */
    CLOCK,

    /**
     * The To-do tool (#920): tasks and their steps. Its state is the
     * controller's, see `ime.todo.TodoUi`; its text box takes the keys
     * through [CaptureTarget.TODO_TEXT].
     */
    TODO,

    /**
     * The Home Assistant tool: the house's entities as tiles, live from the
     * server, and a typed Assist bar. Its state is the controller's, see
     * `ime.homeassistant.HaUi`; its search, Assist and code boxes take the keys
     * through the `HA_*` capture targets.
     */
    HOME_ASSISTANT,

    /**
     * The gallery tool (#708): the phone's photos and videos, picked, viewed,
     * edited and sent. Its state is the controller's, see
     * `ime.gallery.GalleryUi`; its caption box and the editor's text take the
     * keys through [CaptureTarget.GALLERY_TEXT].
     */
    GALLERY,

    /**
     * The Text repeat tool: a piece of text written out many times. Its text
     * and custom separator take the keys through [CaptureTarget.REPEAT_TEXT]
     * and [CaptureTarget.REPEAT_SEPARATOR]; see [TextRepeatUi].
     */
    TEXT_REPEAT,

    /**
     * The Send files tool (#729): files out as an encrypted link or to a device
     * nearby, and links in. Its state is the controller's, see `ime.send.SendUi`;
     * its password, link and address boxes take the keys through the `SEND_*`
     * capture targets.
     */
    SEND_FILES,

    /**
     * The Music ID tool: listens, names the song, types it or sends it as a
     * picture. Its state is the controller's, see `ime.music.MusicUi`.
     */
    MUSIC_ID,

    /**
     * The keyboard's own share sheet (#970): every share the keyboard makes
     * opens here, not as a tool of its own. Its state is the controller's,
     * see `ime.share.ShareUi`; the search box types into [KeyboardUiState.mediaQuery].
     */
    SHARE,
}

/**
 * The panel layout behind a panel mode, for the panels that are layouts
 * (issue #63, the GIF and sticker panels since #538); null for every other
 * panel and for none. Shared by the
 * service's "keep this panel open" check and the theme resolution, so the two
 * cannot disagree about which panels have a grid of their own.
 */
val PanelMode.layoutKind: PanelKind?
    get() = when (this) {
        PanelMode.EMOJI -> PanelKind.EMOJI
        PanelMode.GIF -> PanelKind.GIF
        PanelMode.STICKER -> PanelKind.STICKER
        PanelMode.CLIPBOARD -> PanelKind.CLIPBOARD
        PanelMode.TEXT_EDIT -> PanelKind.TEXT_EDIT
        PanelMode.TRACKPAD -> PanelKind.TRACKPAD
        PanelMode.NUMPAD -> PanelKind.NUMPAD
        PanelMode.HANDWRITING -> PanelKind.HANDWRITING
        PanelMode.EMOJI_MIX -> PanelKind.EMOJI_MIX
        else -> null
    }

/**
 * Panels whose search box types into [KeyboardUiState.mediaQuery] (the same
 * key-rerouting trick as emoji search) instead of the editor.
 */
val PanelMode.hasMediaSearch: Boolean
    get() = this == PanelMode.GIF || this == PanelMode.STICKER ||
        this == PanelMode.WEB_SEARCH || this == PanelMode.IMAGE_SEARCH ||
        this == PanelMode.WIKIPEDIA || this == PanelMode.TRANSLATE ||
        this == PanelMode.APP_LAUNCHER || this == PanelMode.SHARE ||
        // Searches the emoji its picker offers, locally.
        this == PanelMode.EMOJI_MIX ||
        // The vocabulary panel's Browse tab searches the installed packs locally.
        this == PanelMode.VOCABULARY ||
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
    // The two emoji and their mix, every mix of the first, then the emoji to pick.
    PanelMode.EMOJI_MIX -> listOf(FocusRegion.ACTIONS, FocusRegion.RESULTS, FocusRegion.CHIPS)
    // Chips switch between the grids; the fonts grid adds a second chip row
    // for the script whose font it is showing. Listed whether or not that tab
    // is open, the same way the GIF panel lists a row only its default view
    // has: the region is empty until something publishes into it.
    PanelMode.THEMES ->
        listOf(FocusRegion.CHIPS, FocusRegion.CATEGORIES, FocusRegion.RESULTS)
    // The clipboard's chips are the fragments pulled out of its history; its
    // categories are the unpinned and pinned tabs, when those are on (#371).
    PanelMode.CLIPBOARD ->
        listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.CATEGORIES, FocusRegion.RESULTS)
    PanelMode.DICTIONARY,
    PanelMode.WEB_SEARCH, PanelMode.IMAGE_SEARCH, PanelMode.WIKIPEDIA,
    -> listOf(FocusRegion.SEARCH, FocusRegion.RESULTS)
    // Browse's search pill, the pack and filter chips, the word rows (or the
    // card's related words), then the card's or the flashcard's action row.
    PanelMode.VOCABULARY -> listOf(
        FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.RESULTS, FocusRegion.ACTIONS,
    )
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
    // The tab rail, then whatever the open tab offers to press. The touchpad
    // itself publishes nothing: it is a pointing surface.
    PanelMode.KDE_CONNECT -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS)
    PanelMode.QR_GEN, PanelMode.TYPING_TEST -> listOf(FocusRegion.ACTIONS)
    // The two fields, the three toggles, then the buttons.
    PanelMode.FIND_REPLACE -> listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.ACTIONS)
    // The header chips, then the word rows.
    PanelMode.LEARN_FROM_TEXT -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS)
    // The search pill, the filter chips, the note rows (or the read view's
    // tag chips), then the action row under an open note or the editor.
    PanelMode.NOTES -> listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.RESULTS, FocusRegion.ACTIONS)
    // The format chips, the saved places, then Insert and its row (or the one
    // button an empty state has).
    PanelMode.LOCATION -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS, FocusRegion.ACTIONS)
    // The tab row; each tab's own controls are touch, and typing reaches its box.
    PanelMode.CLOCK -> listOf(FocusRegion.CHIPS)
    // The task rows; the header's chips are touch, and typing reaches the box.
    PanelMode.TODO -> listOf(FocusRegion.RESULTS)
    // The room tabs, the tiles, then the open tile's controls.
    PanelMode.HOME_ASSISTANT -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS, FocusRegion.ACTIONS)
    // The album and filter chips, the grid, then the send bar.
    PanelMode.SEND_FILES -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS, FocusRegion.ACTIONS)
    PanelMode.GALLERY -> listOf(FocusRegion.CHIPS, FocusRegion.RESULTS, FocusRegion.ACTIONS)
    PanelMode.PASSWORD_GEN ->
        listOf(FocusRegion.CHIPS, FocusRegion.ACTIONS, FocusRegion.RESULTS)
    // The mode tabs, the text pills, Insert/Copy/Undo, then the option chips.
    PanelMode.TEXT_REPEAT ->
        listOf(FocusRegion.CHIPS, FocusRegion.SEARCH, FocusRegion.ACTIONS, FocusRegion.RESULTS)
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
    // The two switches, the style chips, then the installed sounds or packs
    // the chosen style picks from (absent under the five built-in styles).
    PanelMode.SOUND_HAPTICS ->
        listOf(FocusRegion.ACTIONS, FocusRegion.CHIPS, FocusRegion.RESULTS)
    // Mic first, then whatever chips the state has (undo, language, model).
    PanelMode.VOICE -> listOf(FocusRegion.ACTIONS)
    // The confirm/result stages' buttons (Retake/Send, Scan again/Copy/
    // Insert). The live viewfinders publish nothing: a ring over video helps
    // nobody, and the shutter is a pointer affordance.
    PanelMode.CAMERA, PanelMode.QR_SCAN -> listOf(FocusRegion.ACTIONS)
    PanelMode.OCR -> listOf(FocusRegion.ACTIONS, FocusRegion.RESULTS)
    // The tab chips, then the result card's insert chips.
    PanelMode.MUSIC_ID -> listOf(FocusRegion.CHIPS, FocusRegion.ACTIONS)
    // The search, Copy/Open/More, the pinned and recent row, then every app.
    PanelMode.SHARE -> listOf(FocusRegion.SEARCH, FocusRegion.ACTIONS, FocusRegion.CHIPS, FocusRegion.RESULTS)
    // Panels with nothing to drive. The sensor trio has zero controls at all;
    // TEXT_EDIT/NUMPAD duplicate keys a physical keyboard already sends to
    // the field directly (ringing a d-pad that itself moves the caret would
    // capture the very arrows it re-implements); HANDWRITING's canvas is
    // pointer-only and its three rare chips are not worth a ring over ink, and
    // TRACKPAD is a pointing surface and nothing else.
    PanelMode.NONE, PanelMode.TEXT_EDIT, PanelMode.TRACKPAD, PanelMode.COMPASS, PanelMode.LEVEL,
    PanelMode.MOON_PHASE, PanelMode.NUMPAD, PanelMode.HANDWRITING, PanelMode.CUSTOM_LAYOUTS,
    // The speed test's chips are touch targets with no [PanelFocusTarget]
    // yet; a ring here would be an empty one. Add the regions with the targets.
    PanelMode.SPEED_TEST,
    // AI reply publishes no ring yet (#234): its three bands are all touch
    // targets and none of them has a [PanelFocusTarget], so a ring here would
    // be an empty one. Add the regions with the targets, not before.
    PanelMode.AI_REPLY,
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

/**
 * Where the app-launcher grid is (#919): [folderOpen] is the id of the open
 * folder, or null for the whole grid, and [nameDraft] the folder's name while
 * the keys are typing a new one into it.
 */
data class LauncherUi(
    val folderOpen: String? = null,
    val nameDraft: String? = null,
)

/**
 * Readiness of the handwriting panel's recognition model. UNSUPPORTED: no
 * recogniser in this build reads any enabled language (F-Droid's build reads
 * only Chinese and Japanese), so there is nothing to download either.
 */
enum class HandwritingStatus { CHECKING, NEED_MODEL, DOWNLOADING, READY, ERROR, UNSUPPORTED }

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
    /**
     * Measured progress while [status] is DOWNLOADING, null otherwise. ML Kit
     * publishes no percentage, so the panel counts the megabytes it can see
     * arriving against the size ML Kit ships in its own asset manifest.
     */
    val download: com.wasimaster.wmkeyboard.core.handwriting.HandwritingDownloadProgress? = null,
    /** What this language's model installs to, or 0 when ML Kit lists no size. */
    val modelBytes: Long = 0,
    /**
     * The panel is blown up to a see-through canvas over the whole app with
     * the keyboard shrunk to a small bar (issue #386). Kept across a status
     * reset, so a language switch does not drop the user out of it.
     */
    val fullScreen: Boolean = false,
    /**
     * Pen-button mode only: the full-screen canvas is catching touches (true)
     * or letting them through to the app (false).
     */
    val fullScreenInk: Boolean = true,
)

/**
 * Where the voice input panel is in a dictation session. TRANSCRIBING is the
 * state after recording stops while offline Whisper or the transcription
 * server turns the captured audio into text (the system recognizer streams
 * instead, so it never enters it). MIC_BLOCKED is a session Android fed silence: the Microphone
 * access or Sensors off tile in Quick Settings is on (see MicBlockWatcher).
 */
enum class VoiceStatus { IDLE, LISTENING, FINISHING, TRANSCRIBING, NEED_PERMISSION, MIC_BLOCKED, UNAVAILABLE, ERROR }

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
    /**
     * The phrase just dictated is with the AI tool's model being tidied (#499).
     * Rides [VoiceStatus.TRANSCRIBING], which already means "the words are
     * coming, the mic is shut"; this only changes what the status line says.
     */
    val tidying: Boolean = false,
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
    /**
     * The transcription server engine runs this session (#286). Like [whisper]
     * it records a clip and transcribes it after the user stops, so the
     * surfaces show the same "tap to finish" hint for both.
     */
    val remote: Boolean = false,
    /** The server engine is selected but has no address yet — panel points to settings. */
    val serverNeedsSetup: Boolean = false,
    /**
     * Seconds until a [clipBased] recording stops by itself, over its last
     * few; 0 the rest of the time. The clip has a fixed length and anything
     * said past it is lost, so the surfaces count it down (#315).
     */
    val secondsLeft: Int = 0,
    /**
     * The [clipBased] recording has picked up speech (#921). A clip has no
     * words to show until it is transcribed, so this is what tells the
     * surfaces there is a phrase in hand for Undo to take back.
     */
    val clipHeard: Boolean = false,
    /**
     * The keyboard-owned field this dictation types into (#353), as its
     * [KeyboardUiState.captureKey], or null when it types into the app's field.
     * Set by the microphone on that field's strip and kept until the field
     * gives the keys back or the strip's close button puts it away, so an
     * error has somewhere to be said.
     */
    val field: String? = null,
    /**
     * The kinds of microphone plugged in now (#581), as
     * [com.wasimaster.wmkeyboard.core.voice.VoiceMics] names them, for the
     * panel's microphone chip. Kept current by the service's device watcher.
     */
    val mics: List<String> = emptyList(),
    /**
     * The microphone the last session listened through (#581): its
     * [com.wasimaster.wmkeyboard.core.voice.VoiceMics] kind, never auto, and
     * the name the device gives (the headset's, or the phone model). Empty
     * until a session has started.
     */
    val micKind: String = "",
    val micName: String = "",
    /**
     * When the surfaces were last asked to say which microphone is in use, in
     * `SystemClock.uptimeMillis`: when it changes, when the switch is
     * pressed, and on the first session after the keyboard comes up, not on
     * every chained phrase. A time rather than a count, so a surface that
     * mounts a frame after the session started still shows the rest of it.
     */
    val micNoticeAt: Long = 0L,
    /**
     * The engine the language being dictated uses, its own pick or the global
     * one, as a `voice_engine` value. Empty before the first session.
     */
    val engine: String = "",
    /** The language id [engineChoices] are for. */
    val engineLanguage: String = "",
    /**
     * What the panel's engine chip offers for [engineLanguage], when that
     * chip is switched on: engines and downloaded models that serve it.
     */
    val engineChoices: List<com.wasimaster.wmkeyboard.core.voice.VoiceChoice> = emptyList(),
    /** [engineLanguage]'s stored pick (`engine` or `engine:modelId`), empty when it follows the global engine. */
    val engineChoice: String = "",
)

/** The session records a whole clip and transcribes it after the stop tap. */
val VoiceUi.clipBased: Boolean get() = whisper || remote

/**
 * The voice typing modes that do something different with the current setup,
 * for the Voice tool's hold menu. Typing while you speak needs the keys on
 * screen during dictation, which only the compact bar over the keys leaves
 * there: the panel and the collapsed bar both take the keyboard's place. Plain
 * text also needs an engine that can be asked for no punctuation, which is the
 * system recognizer; the offline models and a server punctuate on their own.
 */
fun KeyboardUiState.supportedVoiceModes(): List<String> {
    val keysShown = settings.voiceBar.mode == com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP
    val engine = voice.engine.ifEmpty { settings.whisper.engine }
    return buildList {
        add(com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_BLOCK)
        if (keysShown) {
            add(com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_INTERACTIVE)
            if (engine == "system") add(com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN)
        }
    }
}

/**
 * The persisted settings say the collapsed voice bar should be up. Never while
 * voice typing is switched off (#578); [active] is kept, so the bar comes back
 * if it is switched on again.
 */
fun VoiceBarSettings.armed(): Boolean = enabled && mode == VoiceBarSettings.MODE_BAR && active

/**
 * An interactive voice typing session can be run from one microphone button,
 * which leaves the suggestion strip its room (see `VoiceMicChip`).
 *
 * False when the surface has something to say that does not fit on a button:
 * no microphone permission, no recognizer, no offline model, an error, or a
 * password field. Those fall back to the compact bar, which has a line of text
 * and an action.
 */
/**
 * The compact dictation bar holds the strip row. It steps aside while the mic
 * is idle and a word is being typed (#887): after a phrase lands, the keys are
 * how its mistakes get fixed, and the candidates for that word (or a Chinese
 * reading's characters) have nowhere else to show. The bar comes back as the
 * word is committed. Listening, transcribing or with something to report, it
 * keeps the row, since it is the session's status and its way out.
 */
fun KeyboardUiState.voiceStripShown(): Boolean =
    voice.strip && !(voice.status == VoiceStatus.IDLE && (composingPreview.isNotEmpty() || wordComposing))

/** A Chinese or Cantonese layout converting a reading: the boards #973's two settings speak for. */
fun KeyboardUiState.hanReading(): Boolean =
    composer.isConversion && (language.id == "zh" || language.id == "yue")

/**
 * The reading being converted is drawn at the start of the candidate strip and
 * kept out of the app's field (#973), as fcitx5 has it. A keyboard-owned field
 * composes in its own box, which is the keyboard already, so it never applies.
 */
fun KeyboardUiState.readingInKeyboard(): Boolean =
    !settings.cjk.readingInField && hanReading() && captureTarget() == null

fun KeyboardUiState.voiceChipOnly(): Boolean =
    settings.voiceBar.interactiveTyping() &&
        !secureField &&
        !voice.whisperNeedsModel &&
        !voice.serverNeedsSetup &&
        voice.status != VoiceStatus.NEED_PERMISSION &&
        voice.status != VoiceStatus.MIC_BLOCKED &&
        voice.status != VoiceStatus.UNAVAILABLE &&
        voice.status != VoiceStatus.ERROR

/**
 * A dictation into the focused keyboard-owned field (#353) is running, or has
 * something to say, so that field's strip gives its chips' room to the line
 * that says it: listening, the words heard so far, transcribing, or what went
 * wrong and what to do about it. Idle and fine, the strip is its chips again.
 */
fun KeyboardUiState.fieldVoiceSpeaks(): Boolean {
    val field = voice.field ?: return false
    if (field != captureKey()) return false
    return voice.status != VoiceStatus.IDLE || voice.whisperNeedsModel || voice.serverNeedsSetup
}

/**
 * The keys are drawing what the transliterator is about to write with them:
 * the layout transliterates (Avro, not Probhat), and the user left the hints
 * on. The one gate both sides share — the service asks it before mirroring the
 * roman buffer into [KeyboardUiState.composingRoman], and the grid asks it
 * before reading that mirror, so the two can never disagree about whether the
 * buffer is live.
 */
/**
 * The grammar issues the panel shows: everything the last check found, minus
 * the kinds the filter hides ([KeyboardSettings.grammarHiddenKinds]).
 *
 * The one gate both sides share. The panel draws these and counts them, and
 * "Fix all" rewrites exactly these — filtering only the cards would leave
 * "Fix all" silently applying fixes for issues the user asked not to see.
 */
val KeyboardUiState.visibleGrammarLints: List<com.wasimaster.wmkeyboard.core.grammar.GrammarLint>
    get() {
        val hidden = settings.grammarHiddenKinds
        if (hidden.isEmpty()) return grammar.lints
        return grammar.lints.filter { GrammarLintKind.isVisible(it.kind, hidden) }
    }

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
     * The strip's close button: the session is abandoned and the strip goes.
     * It used to be a press of the voice tool, until that press over a running
     * dictation became the stop button (#283) and the two had to part.
     */
    data object CloseStrip : VoiceBarAction

    /**
     * The collapsed bar's on-screen rectangle in window coordinates — the
     * service's touchable region, so touches beside the bar reach the app.
     */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) : VoiceBarAction

    /**
     * A pick off the voice panel's language chip (#416): the languages
     * dictation listens for, in order, or none to follow the keyboard.
     */
    data class PickLanguages(val ids: List<String>) : VoiceBarAction

    /** The voice panel's microphone chip (#581): listen through the next microphone plugged in. */
    data object CycleMic : VoiceBarAction

    /**
     * A pick off the voice panel's engine chip: [languageId] dictates with
     * [choice] (`engine` or `engine:modelId`) from now on; blank goes back to
     * the engine picked in settings.
     */
    data class PickEngine(val languageId: String, val choice: String) : VoiceBarAction
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

    /** The PIN tab (#899); the other two tabs send [PassphraseMode], which leaves it. */
    data object PinMode : PwSettingAction
    data class PinLength(val value: Int) : PwSettingAction
    data class PinAvoidWeak(val on: Boolean) : PwSettingAction
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

    /**
     * An installed key sound, picked from the panel's own list.
     *
     * Carries the id rather than only switching the style, because the two are
     * one decision: the repository pairs them in a single write so that picking
     * a sound and hearing it are the same press.
     */
    data class SoundCustomChange(val id: String) : SoundHapticAction

    /** An installed sound pack, the [SoundCustomChange] of the other style. */
    data class SoundPackChange(val id: String) : SoundHapticAction
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
        /** The backend's own short answer, shown above the results (#470). */
        val answer: String? = null,
        /** Pictures for the query under the answer on the All tab (#879). */
        val images: List<com.wasimaster.wmkeyboard.core.tools.ImageResult> = emptyList(),
        /** Further pages loaded by More results (#879), 0 for none. */
        val page: Int = 0,
        /** A further page is on its way. */
        val loadingMore: Boolean = false,
        /** The last further page brought nothing new: More results goes. */
        val noMore: Boolean = false,
        /** The SearXNG category these came from (#934); null, the general search. */
        val category: String? = null,
    ) : WebSearchUi
}

/**
 * The search panel's tabs (#879). Image search used to be a panel of its own;
 * its tool, key and shortcut now open the [IMAGES] tab of web search.
 */
enum class SearchTab { ALL, IMAGES }

/** Image search state: the search panel's Images tab. */
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
        /** As on [WebSearchUi.Ready]: More results' pages (#879). */
        val page: Int = 0,
        val loadingMore: Boolean = false,
        val noMore: Boolean = false,
    ) : ImageSearchUi
}

/**
 * Translate panel state. The panel is its own little window: what gets
 * translated is the query typed into it ([KeyboardUiState.mediaQuery], the
 * same key-rerouting trick as the media panels). The focused field is read
 * once, as the panel opens, and only for its selection (see [selection]).
 */
data class TranslateUi(
    /**
     * The field's selection the panel opened on, which also became the query
     * (#434), or "" when it opened on nothing selected. Replace puts the
     * translation over this selection rather than over the whole field, as
     * long as it is still what is selected.
     */
    val selection: String = "",
    /** The typed query the current [translated] corresponds to. */
    val sourceText: String = "",
    val translated: String = "",
    /** ISO code of the auto-detected source language, "" while unknown. */
    val detectedSource: String = "",
    val translating: Boolean = false,
    val error: String? = null,
    /**
     * The source language the user picked for this visit to the panel, as a
     * picker code, or "" to detect it. Session state on purpose: a wrong
     * detection is a fact about one piece of text, not a preference.
     */
    val sourceOverride: String = "",
    /**
     * [detectedSource] is a guess taken from the keyboard's own language,
     * because the on-device identifier could not tell. Drawn with a "?".
     */
    val sourceGuessed: Boolean = false,
    /** [translated] came from the on-device engine, not from a server. */
    val onDevice: Boolean = false,
    /** [translated] came from DeepL, the user's own opt-in service (#331). */
    val viaDeepL: Boolean = false,
    /** [translated] came from the user's own translation server (#435). */
    val viaServer: Boolean = false,
    /**
     * [translated] came from the on-device engine standing in for the online
     * one, because there was no connection or the service did not answer (#452).
     */
    val offlineStandIn: Boolean = false,
    /**
     * Model codes the on-device engine needs before it can translate the
     * current query. Non-empty is what puts the download offer on screen.
     */
    val missingModels: List<String> = emptyList(),
    /**
     * The download was held by data saver and the panel is asking. The next
     * press of the same button is the yes.
     */
    val meteredAsk: Boolean = false,
    /**
     * The download needs a licence agreed to first (NLLB-200's weights,
     * CC BY-NC 4.0), and the panel is showing it. The next press of the same
     * button is the yes.
     */
    val licenceAsk: Boolean = false,
    /** The on-device models' states, mirrored here while the panel is open. */
    val models: Map<String, com.wasimaster.wmkeyboard.core.translate.OfflineModelState> = emptyMap(),
    /**
     * The on-device engine itself is what the query is waiting for: a Play
     * install that has not fetched the on-demand module yet. Puts the same
     * download offer on screen as [missingModels], one step earlier.
     */
    val moduleMissing: Boolean = false,
    /** Where that module stands, mirrored from its gate while the panel is open. */
    val module: com.wasimaster.wmkeyboard.core.translate.TranslateModuleState =
        com.wasimaster.wmkeyboard.core.translate.TranslateModuleState.Installed,
)

/**
 * The panel with its result wiped and everything that outlives a result kept:
 * the user's source pick and the mirrored model states. What every "start
 * over" in the translate flow wants instead of a bare `TranslateUi()`, which
 * would quietly put the source chip back on detect.
 */
fun TranslateUi.cleared(): TranslateUi =
    TranslateUi(selection = selection, sourceOverride = sourceOverride, models = models, module = module)

/**
 * Grammar strip state. Like translate, the strip follows the focused field:
 * the service re-extracts and re-lints (debounced) on every change while the
 * panel is open. The grammar tool's provider list says who checks: the
 * bundled Harper engine, or a LanguageTool server the user switched on.
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
    /**
     * Play only: why the library is not here yet (still downloading, held by
     * data saver, or the download failed), shown in place of the issues.
     */
    @androidx.annotation.StringRes
    val moduleHint: Int? = null,
    /**
     * DeepL Write's rewrite of the field, while one was asked for (#331).
     * Every fresh lint builds a new [GrammarUi] without it, which is what
     * drops a rewrite of text the user has since changed.
     */
    val rephrase: DeepLWriteUi? = null,
    /** The LanguageTool server that found [lints], by name; null when Harper did. */
    val via: String? = null,
    /**
     * Why there are no issues to show: a server that failed, or one that was
     * not asked (a password field, data saver). Shown in the issues' place.
     */
    val notice: String? = null,
)

/** One DeepL Write request from the grammar panel, and what came of it. */
data class DeepLWriteUi(
    /** The text sent: the start of the field, [com.wasimaster.wmkeyboard.core.tools.DeepLClient.MAX_CHARS] at most. */
    val source: String,
    val result: String = "",
    val working: Boolean = true,
    val error: String? = null,
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
        /**
         * The tools the model has called so far (#470); the newest may still
         * be running, and the progress readout then says which and on what.
         */
        val tools: List<com.wasimaster.wmkeyboard.core.tools.AiToolActivity> = emptyList(),
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
         * [sourceText] was the selection, not the whole field, so Replace
         * swaps the selection alone. Without it, running an action on a
         * selected sentence and pressing Replace overwrote the whole field
         * with one rewritten sentence.
         */
        val sourceFromSelection: Boolean = false,
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
        /**
         * The searches and pages the answer was built on (#470), drawn over
         * it. Carried over from [Loading] as the run streams.
         */
        val tools: List<com.wasimaster.wmkeyboard.core.tools.AiToolActivity> = emptyList(),
    ) : AiUi
    data class Error(
        val action: com.wasimaster.wmkeyboard.core.tools.AiActionSpec,
        val message: String,
    ) : AiUi
}

/**
 * The AI panel's chat mode (#280): which conversation is up and what is in the
 * composer.
 *
 * Deliberately small. The transcript and the answer that is forming are not
 * here: the panel reads those from
 * [com.wasimaster.wmkeyboard.ime.aichat.AiChatController], the same object the
 * chat screen in the settings app reads, which is what lets a chat move between
 * the two with nothing to hand over. What is here is only what the keyboard
 * alone knows: the draft its keys are writing, and whether they are writing it.
 */
data class AiChatUi(
    /**
     * Chat can be offered at all. False on the lock screen and before the first
     * unlock: the conversations are in credential-encrypted storage, and a
     * transcript is not something to show over a keyguard.
     */
    val available: Boolean = true,
    /** The panel is in its chat mode rather than showing the actions. */
    val open: Boolean = false,
    /** Below zero is a chat not started yet; the first send creates it. */
    val conversationId: Long = -1L,
    val draft: String = "",
    /** The composer has the keys, so the key rows are up under the panel. */
    val composing: Boolean = false,
    /** The conversation list is up in place of the transcript. */
    val showSessions: Boolean = false,
    val attachment: AiChatAttachment? = null,
    /** [com.wasimaster.wmkeyboard.ime.aichat.AiChatController.ModelChoice.key]. */
    val modelKey: String = "",
    /**
     * Pictures to send with the next message (#698), by the names
     * [com.wasimaster.wmkeyboard.ime.aichat.AiChatImages.import] gave them.
     */
    val images: List<String> = emptyList(),
    /**
     * The "+" menu is up over the composer (#882): Image, File, Photo, the
     * clipboard's picture and the newest screenshot, the field's text, Paste.
     */
    val attachMenu: Boolean = false,
    /** Why the last picture or file asked for did not arrive, as a string resource; null when it did. */
    val pictureNotice: Int? = null,
    /** Files to send with the next message (#882), already read or copied in. */
    val files: List<com.wasimaster.wmkeyboard.core.aichat.AiChatFile> = emptyList(),
    /** Chats picked in the list with a long press (#882), for deleting together. */
    val picked: Set<Long> = emptySet(),
    /**
     * The chat whose name is being typed in the list (#882), or -1. While
     * one is, the composer's keys write [renameDraft] instead of the draft.
     */
    val renaming: Long = -1L,
    val renameDraft: String = "",
    /**
     * The next message's mode and answer format, by
     * [com.wasimaster.wmkeyboard.core.tools.AiChatMode.id] and
     * [com.wasimaster.wmkeyboard.core.tools.AiAnswerFormat.id]. Kept from
     * one message to the next, like the app's message box.
     */
    val mode: String = "chat",
    val format: String = "auto",
    /** The row of modes and formats is up over the composer, in place of the "+" menu. */
    val optionsMenu: Boolean = false,
)

/**
 * Text from the field, quoted along with the next message. A snapshot taken
 * when the user asked for it: while the composer has the keys nothing can type
 * into the field, so it cannot go stale under them.
 */
data class AiChatAttachment(
    val text: String,
    val fromSelection: Boolean,
    /**
     * Where a tool's text came from, as the composer names it ("Wikipedia:
     * Cat"), when a tool handed it over with Ask AI (#352); blank for the
     * field's own text.
     */
    val label: String = "",
)

/**
 * Everything the chat mode can ask of the service, as one type.
 *
 * One callback rather than twenty for the reason [TypingTestAction] gives, and
 * a harder one: the composable that passes the service's callbacks down is at
 * the JVM's 64K method ceiling, where a parameter is not free.
 */
sealed interface AiChatAction {
    data class SetMode(val chat: Boolean) : AiChatAction
    data object FocusComposer : AiChatAction
    data object BlurComposer : AiChatAction
    data object Send : AiChatAction
    data object Stop : AiChatAction
    data object Retry : AiChatAction
    data object Regenerate : AiChatAction
    data object EditLast : AiChatAction
    data object NewChat : AiChatAction
    data class Open(val conversationId: Long) : AiChatAction
    data class Delete(val conversationId: Long) : AiChatAction
    data object ToggleSessions : AiChatAction
    data class PickModel(val key: String) : AiChatAction
    data object ToggleAttachment : AiChatAction
    data object Paste : AiChatAction
    /** [raw] is for code, which goes in as written; an answer loses its markdown. */
    data class Insert(val text: String, val raw: Boolean = false) : AiChatAction
    data class Copy(val text: String) : AiChatAction
    /** A suggested opening: puts [text] in the composer, with the field's text if [attach]. */
    data class Starter(val text: String, val attach: Boolean) : AiChatAction
    /** The chat screen in the settings app: this conversation, or the list. */
    data class OpenInApp(val list: Boolean = false) : AiChatAction
    /** The answer at [index] of the conversation, reported (Play builds). */
    data class Report(val index: Int) : AiChatAction
    /** Picks a chat in the list, or takes it back out of the selection (#882). */
    data class TogglePick(val conversationId: Long) : AiChatAction
    data object PickAll : AiChatAction
    data object ClearPicks : AiChatAction
    /** Deletes every picked chat; the list asks twice first. */
    data object DeletePicked : AiChatAction
    /** Starts typing a new name for a chat in the list (#882). */
    data class StartRename(val conversationId: Long) : AiChatAction
    /** Keeps the name typed; Back, or [CancelRename], drops it. */
    data object CommitRename : AiChatAction
    data object CancelRename : AiChatAction
    /** Flips the chat's Reasoning switch (#882), the setting both chats share. */
    data object ToggleReasoning : AiChatAction
    /** Shows or hides the composer's "+" menu (#882). */
    data object ToggleAttachMenu : AiChatAction
    /** The "+" menu's Image (the photo picker), File (the document picker) or Photo (the camera), by [mode]. */
    data class PickFromSystem(val mode: Int) : AiChatAction
    /** Takes a file back off the next message. */
    data class RemoveFile(val file: com.wasimaster.wmkeyboard.core.aichat.AiChatFile) : AiChatAction
    /** The clipboard's newest picture, or else the newest screenshot, for the next message. */
    data class AddPicture(val fromClipboard: Boolean) : AiChatAction
    /** Takes a picture back off the next message. */
    data class RemovePicture(val name: String) : AiChatAction

    /** Shows or hides the row of modes and answer formats over the composer. */
    data object ToggleOptions : AiChatAction

    /** The next message's mode, by `AiChatMode.id`. */
    data class SetChatMode(val id: String) : AiChatAction

    /** The next message's answer format, by `AiAnswerFormat.id`. */
    data class SetAnswerFormat(val id: String) : AiChatAction

    /** The answer at [index] of the conversation, shared as a Markdown file. */
    data class Share(val index: Int) : AiChatAction

    /**
     * Ask AI from another tool (#352): the AI panel opens on a new chat with
     * [text] attached and the composer taking the keys. [label] names where the
     * text came from, for the composer; [fromSelection] is a passage rather
     * than the whole.
     */
    data class AskAbout(val text: String, val label: String, val fromSelection: Boolean) : AiChatAction

    /**
     * The user's answer to an MCP call that asked first (#573), from the
     * chat or from the AI panel's actions: both ride this one callback.
     */
    data class ToolApproval(val choice: com.wasimaster.wmkeyboard.core.tools.AiToolApproval) : AiChatAction

    /** Takes back an AI memory save: the call at [useIndex] under the message at [messageIndex]. */
    data class UndoMemory(val messageIndex: Int, val useIndex: Int) : AiChatAction
}


/**
 * One message the user has added to a drafted reply's context (#234), and
 * everything needed to put it back afterwards.
 *
 * The bookkeeping fields are not decoration. The whole point of the tool is to
 * read somebody else's message, and the usual way to get one into the keyboard
 * is to copy it or screenshot it — which leaves halves of other people's
 * conversations lying in the clipboard and the gallery. [clipId], [shotUri] and
 * [shotFile] are what lets the tidy-up take back exactly what this reply used,
 * and nothing else.
 */
data class ReplyEntry(
    val text: String,
    /** Who said it. The one thing OCR and the clipboard cannot know; a tap flips it. */
    val fromMe: Boolean = false,
    val source: ReplySource,
    /** The clipboard row this came from, or 0. */
    val clipId: Long = 0L,
    /** The gallery entry a screenshot came from, as a string so the state stays comparable. */
    val shotUri: String? = null,
    /** A copy of the screenshot the keyboard made, if it made one. */
    val shotFile: String? = null,
    /**
     * The picture itself goes to the model, so [text] is a placeholder the
     * panel draws rather than anything that is sent.
     */
    val image: Boolean = false,
    /**
     * Where the keyboard's top edge was on screen, in pixels, when the
     * screenshot was taken; 0 when the keyboard was not in the picture. Kept
     * on a screenshot's entries so that reading it on the phone — now or
     * after a model turns out not to see — cuts off the keyboard that was
     * actually in the picture, not whatever is on screen when it is read.
     */
    val boardTopPx: Int = 0,
    /**
     * No word in it is one the keyboard knows ([ReplyText.looksUnclear]):
     * what OCR makes of a sticker or a cut-off line. Drawn in red, with one
     * button on the panel to clear them all.
     */
    val unclear: Boolean = false,
    /**
     * For a line read off a screenshot: the screenshot it came from, on every
     * line of it (unlike [shotUri], which only the first carries so the
     * tidy-up and the duplicate check see the picture once). It is what lets
     * the panel's Picture/Text switch turn a screenshot's lines back into the
     * picture they were read from.
     */
    val fromShot: String? = null,
)

/** A screenshot offered in the reply panel's picker. */
data class ReplyShot(val uri: String, val name: String, val addedAt: Long)

/**
 * The AI reply tool (#234): what has been gathered to reply to, and what came
 * back.
 *
 * The run itself lives on [KeyboardUiState.ai], the same [AiUi] the AI tool
 * uses — only one panel is open at a time, and reusing it means the phases, the
 * error wording, the metered notice and the stale-run guard are all the ones
 * that already work. What is here is everything that is this tool's alone.
 */
data class AiReplyUi(
    /** The conversation so far, oldest first. */
    val entries: List<ReplyEntry> = emptyList(),
    /** The panel's own text box, for a message typed or pasted by hand. */
    val typed: String = "",
    /** That box has the keys. */
    val typing: Boolean = false,
    /**
     * Take the clips and screenshots this reply used back out afterwards. Seeded
     * from the setting and changeable for the run in hand, because "this one is
     * worth keeping" is a decision about one clip, not a preference.
     */
    val tidyUp: Boolean = true,
    /** The screenshot picker is open over the panel. */
    val picking: Boolean = false,
    /** What the picker is showing; read when it opens, never on every frame. */
    val recentShots: List<ReplyShot> = emptyList(),
    /** A screenshot is being read on the device right now. */
    val reading: Boolean = false,
    /** The tone the drafts on screen were written in. */
    val tone: AiActionSpec? = null,
    /** The drafts themselves. While a run is going these are the ones finished so far. */
    val candidates: List<String> = emptyList(),
    /**
     * What the user had already typed in the field when the run started: the
     * drafts say that, in the tone picked, and the one chosen replaces it
     * rather than landing beside it. Blank when the field was empty.
     */
    val draft: String = "",
    /**
     * The model the next run goes to can take a picture. Worked out by the
     * service — it knows which on-device model refused one this session — and
     * refreshed when the panel opens and when the model is switched.
     */
    val canSendPicture: Boolean = false,
    /** The message box is editing this message rather than adding a new one. */
    val editing: Int? = null,
    /**
     * A tone that asks for its instruction each run (the shipped Custom…, or
     * one the user wrote that way) is waiting for it: the message box is the
     * instruction box until it is sent or closed.
     */
    val askTone: AiActionSpec? = null,
    /** A one-line message under the header: nothing copied, no permission, nothing read. */
    @androidx.annotation.StringRes val notice: Int? = null,
) {
    /** Something to reply to. */
    val hasContext: Boolean get() = entries.any { it.text.isNotBlank() || it.image }

    /** Pictures being sent as pictures. */
    val images: List<ReplyEntry> get() = entries.filter { it.image }

    /** Some of the conversation came from a screenshot, as a picture or as its lines. */
    val hasShots: Boolean get() = entries.any { it.image || it.fromShot != null }

    /** How many messages read as noise rather than words. */
    val unclearCount: Int get() = entries.count { it.unclear }

    /** The conversation as the prompt builder wants it. */
    fun context(source: ReplySource = ReplySource.TYPED, app: String? = null): ReplyContext =
        ReplyContext(
            turns = entries.filter { !it.image && it.text.isNotBlank() }
                .map { ReplyTurn(fromMe = it.fromMe, text = it.text) },
            source = entries.firstOrNull()?.source ?: source,
            app = app,
        )
}

/** The KDE Connect panel's tabs, in rail order. */
enum class KdeTab { INPUT, MEDIA, SEND, RUN, SLIDES, DEVICE }

/**
 * The KDE Connect panel (issue #285): what only the keyboard knows.
 *
 * Deliberately small, for the reason [AiChatUi] is. Devices, pairing, players,
 * volumes, transfers — everything that arrives over the network — is read by
 * the panel straight from `KdeConnectHub.state`, so a pointer drag or a
 * position tick from the computer never enters the state the key rows compare
 * on every keystroke. What is here is what the keys need: which buffer they are
 * writing, and what is in it.
 */
data class KdeUi(
    val tab: KdeTab = KdeTab.INPUT,
    /** The computer the panel is about; null follows the first one connected. */
    val deviceId: String? = null,
    /** The list of nearby and paired devices is up over the tabs. */
    val showDevices: Boolean = false,
    /** The keys type on the computer instead of into the app. */
    val typing: Boolean = false,
    /**
     * Live mode: the text the computer has been sent on this line, which the
     * next keystroke is diffed against. Compose mode: the draft.
     */
    val line: String = "",
    /** The "add by address" box has the keys. */
    val hostEntry: Boolean = false,
    val hostDraft: String = "",
    /** One line of feedback under the header — "Clipboard sent", "Ping from Desk" — that fades. */
    val notice: String = "",
    val noticeAtMs: Long = 0,
    /**
     * Ctrl / Alt / Super / Shift armed on the panel's key strip, for the next
     * key sent to the computer and no further. The board's own modifier keys
     * work too, where a layout has them; most phone layouts do not.
     */
    val mods: com.wasimaster.wmkeyboard.core.kdeconnect.KdeModifiers =
        com.wasimaster.wmkeyboard.core.kdeconnect.KdeModifiers.None,
)

enum class KdeModKey { SHIFT, CTRL, ALT, META }

/**
 * What the KDE Connect panel asks of the service. Only what touches the
 * keyboard's own state, the field, or the settings comes through here; remote
 * control (pointer, media, volume, commands) goes from the panel straight to the
 * hub, which keeps a touchpad drag off the main state path entirely.
 */
sealed interface KdeAction {
    data object TurnOn : KdeAction
    data class SetTab(val tab: KdeTab) : KdeAction
    data class SelectDevice(val deviceId: String) : KdeAction
    data class ShowDevices(val show: Boolean) : KdeAction
    data class SetTyping(val on: Boolean) : KdeAction
    data class SetCompose(val on: Boolean) : KdeAction
    data class ToggleModifier(val key: KdeModKey) : KdeAction
    data class HostEntry(val open: Boolean) : KdeAction
    data object SubmitHost : KdeAction
    data object SendClipboard : KdeAction

    /** Hold clipboard sharing with [deviceId], or every computer when null, for [minutes]; 0 until resumed (#918). */
    data class PauseClipboard(val deviceId: String?, val minutes: Int) : KdeAction
    data class ResumeClipboard(val deviceId: String?) : KdeAction

    /** The selection if there is one, otherwise the whole field. */
    data object SendFieldText : KdeAction
    data object PickFiles : KdeAction
    data class Insert(val text: String) : KdeAction
    data class Copy(val text: String) : KdeAction
    data class Open(val location: String, val fileName: String) : KdeAction
    data class Notice(val text: String) : KdeAction
    data object OpenSettings : KdeAction
    data object OpenDevices : KdeAction

    /** Open the QR scanner for a computer's pairing code (not on F-Droid). */
    data object ScanPairCode : KdeAction
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

    /** No source could be reached. */
    data class Error(val word: String) : DictionaryUi

    /** Every source that answered knows no entry for this word. */
    data class NotFound(val word: String) : DictionaryUi

    /**
     * The vocabulary packs do not have it, and no online source could be
     * reached to ask (#665): not a verdict on the word.
     */
    data class NotOnDevice(val word: String) : DictionaryUi

    /** The user turned every source off. */
    data object NoSources : DictionaryUi
    data class Ready(
        val entries: List<com.wasimaster.wmkeyboard.core.tools.DictEntry>,
        val source: com.wasimaster.wmkeyboard.core.tools.DictionarySource,
        /**
         * The word's meaning in the user's language, from an installed
         * vocabulary pack's translations (#651); null for a word no pack has.
         */
        val meaning: com.wasimaster.wmkeyboard.core.vocab.VocabMeaning? = null,
        /**
         * The first entry's word in the languages the user picked (#764), in
         * their order: language code to its glosses. Empty with translations
         * off or none found.
         */
        val translations: List<Pair<String, com.wasimaster.wmkeyboard.core.vocab.VocabTranslation>> = emptyList(),
        /** Wiktionary is still being asked for [translations], the entry's own source having none. */
        val translationsLoading: Boolean = false,
    ) : DictionaryUi
}

/** The vocabulary panel's three views. */
enum class VocabTab { CARD, BROWSE, REVIEW }

/** Which words the Browse tab lists. */
enum class VocabBrowseFilter { ALL, UNLEARNT, LEARNT }

/** What the Card tab shows. */
sealed interface VocabCardUi {
    /** No word yet: nothing at the cursor and nothing opened before. */
    data object Idle : VocabCardUi
    data class NotFound(val word: String) : VocabCardUi
    data class Ready(
        val word: com.wasimaster.wmkeyboard.core.vocab.VocabWord,
        val learnt: Boolean,
        /** Leitner box, or null when the word was never reviewed. */
        val box: Int?,
        /** The lists the word is in, as display names. */
        val sources: List<String>,
        val inMyList: Boolean,
    ) : VocabCardUi
}

data class VocabBrowseUi(
    val packs: List<com.wasimaster.wmkeyboard.core.vocab.VocabPackMeta> = emptyList(),
    /** The pack being browsed, or null for every pack. */
    val packId: String? = null,
    val filter: VocabBrowseFilter = VocabBrowseFilter.ALL,
    val rows: List<com.wasimaster.wmkeyboard.core.vocab.VocabWord> = emptyList(),
    /** Lemmas among [rows] the user marked learnt, for the tick. */
    val learnt: Set<String> = emptySet(),
)

data class VocabReviewUi(
    val queue: List<com.wasimaster.wmkeyboard.core.vocab.VocabWord> = emptyList(),
    val index: Int = 0,
    val flipped: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
) {
    val current: com.wasimaster.wmkeyboard.core.vocab.VocabWord? get() = queue.getOrNull(index)
}

/**
 * Vocabulary panel state, owned by the service: it holds the pack index and
 * the learning record, and the card must survive the panel closing.
 */
data class VocabPanelUi(
    /** At least one enabled pack is loaded. */
    val available: Boolean = false,
    val tab: VocabTab = VocabTab.CARD,
    val card: VocabCardUi = VocabCardUi.Idle,
    /** Cards opened from a related-word chip, for the header's back button. */
    val stack: List<String> = emptyList(),
    val browse: VocabBrowseUi = VocabBrowseUi(),
    val review: VocabReviewUi = VocabReviewUi(),
)

/** The word-of-the-day chip on the strip; [day] is the local epoch day it was drawn for. */
/** The word-of-the-day chip: its word and the slot ([com.wasimaster.wmkeyboard.core.vocab.WordOfDay.slot]) it was drawn for. */
data class VocabDailyChip(val word: String, val slot: Int)

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

    /** The chip was held: the setting it asks about, in the app (#312). */
    data object Explain : StripOfferAction

    /**
     * The ANSI button beside the words on a Bengali layout: write Bijoy-era
     * ANSI, or Unicode again. Not an offer, but a strip button all the same,
     * and riding this type costs the screen's call chain no parameter.
     */
    data object ToggleAnsi : StripOfferAction

    /** The Zawgyi button beside the words on a Burmese layout, the same kind of button as [ToggleAnsi]. */
    data object ToggleZawgyi : StripOfferAction

    /** The 繁 button beside the candidates on a Chinese or Cantonese layout (#962), the same kind of button as [ToggleAnsi]. */
    data object ToggleTraditional : StripOfferAction

    /** The password manager chip in a password field (#1003): open the manager's app. */
    data object OpenPasswordManager : StripOfferAction

    /** The same chip's ✕: not in this field. */
    data object DismissPasswordManager : StripOfferAction
}

/**
 * What the strip's Zawgyi chip is offering: text the keyboard found in
 * Zawgyi, the pre-Unicode Burmese encoding, where it would otherwise be read
 * or left as Unicode (see [com.wasimaster.wmkeyboard.core.transliteration.Zawgyi]).
 */
sealed interface ZawgyiOffer {
    /** [text] was just pasted and is Zawgyi: tap to put its Unicode in its place. */
    data class Pasted(val text: String) : ZawgyiOffer

    /** The Burmese field being typed in already holds Zawgyi: tap to write Zawgyi in it too. */
    data object Field : ZawgyiOffer
}

/**
 * What the menu on a held suggestion can ask of the service (#99).
 *
 * A sealed type, and a bundle of callbacks around it ([SuggestionHoldCallbacks]),
 * rather than one parameter per action: the keyboard screen's call chain is
 * against the 64K method ceiling, so the hold grew from one lambda to five
 * actions without costing a parameter (see [StripOfferAction]).
 */
sealed interface WordMenuAction {
    /** Put [word] on the never-suggest list (#28). */
    data class NeverSuggest(val word: String) : WordMenuAction

    /** Take [word] back off the never-suggest list. */
    data class AllowAgain(val word: String) : WordMenuAction

    /**
     * Add the word being typed to the personal dictionary, spelled as
     * [typed]. About the composing word, not the held chip: the chips are
     * already known words, and the one the user wants in is the one under
     * the caret (#100).
     */
    data class Add(val typed: String) : WordMenuAction

    /** Forget [word] everywhere the keyboard can, and hide it where it cannot. */
    data class Delete(val word: String) : WordMenuAction

    /**
     * Forget what the keyboard learned about [word], and leave it a word: the
     * lists still offer it, at their own rank, with their own capitals (#679).
     */
    data class Forget(val word: String) : WordMenuAction

    /** Open the word card for [word]. */
    data class Open(val word: String) : WordMenuAction

    /**
     * Decode the swipe that wrote the word the caret is in again, against
     * every word list this time (#135). [word] is that word, for the service
     * to check the caret has not moved on under the menu.
     */
    data class SearchAllWords(val word: String) : WordMenuAction

    /**
     * Retype (#791, #844) on the word the caret is behind: about that word,
     * like [SearchAllWords], not the chip held to open the menu. [word] is it,
     * as the menu named it.
     */
    data class Retype(val word: String) : WordMenuAction

    /** Look up synonyms for [word] and offer them in its place (#321). */
    data class Synonyms(val word: String) : WordMenuAction

    /**
     * Set [word]'s rank adjustment to [steps], one up or down from where the
     * menu found it (#970): the word card's rank, without opening the card.
     */
    data class RankStep(val word: String, val steps: Int) : WordMenuAction
}

/** What the word card can ask of the service, once open (#99). */
sealed interface WordCardAction {
    /**
     * Set the card's word's learned weight: 0 forgets it, a positive count
     * on an unlearned word adds it at that weight.
     */
    data class SetLearnedWeight(val count: Int) : WordCardAction

    /** Set the card's word's rank adjustment, in steps. */
    data class SetOffset(val steps: Int) : WordCardAction

    /**
     * Start respelling the card's word on the keys (#138): the card steps
     * aside for the key rows and the spelling bar takes the keystrokes.
     */
    data object EditSpelling : WordCardAction

    /** Keep, or stop keeping, the card's word spelled the way it is now (#100). */
    data class SetCasePinned(val pinned: Boolean) : WordCardAction

    /** Respell the card's word as what the spelling bar now holds (#138). */
    data object CommitSpelling : WordCardAction

    /** Leave the spelling bar without respelling anything (#138). */
    data object CancelSpelling : WordCardAction

    /**
     * Put the spelling bar's selection from [start] to [end] — a tap is a
     * caret, a drag a selection (#204). [end] is the end that moves.
     */
    data class SelectSpelling(val start: Int, val end: Int) : WordCardAction

    /** Add the typed word the card names, like [WordMenuAction.Add]. */
    data object Add : WordCardAction

    /** Like [WordMenuAction.Delete], for the card's word. */
    data object Delete : WordCardAction

    /** Like [WordMenuAction.NeverSuggest], for the card's word. */
    data object NeverSuggest : WordCardAction

    /** Like [WordMenuAction.AllowAgain], for the card's word. */
    data object AllowAgain : WordCardAction

    /** Forget how the user draws the card's word, keeping the word itself. */
    data object ForgetShapes : WordCardAction

    /** Close the card. */
    data object Dismiss : WordCardAction
}

/**
 * What the held-word menu needs to know to decide its items, answered
 * synchronously by the service when a chip is held (#99): cheap lookups
 * only, never a trie walk.
 */
data class WordMenuFacts(
    /**
     * The word the user is working on — the one being typed, or the one the
     * caret is parked inside while proofreading (#263) — when it is not in
     * the personal dictionary; else null. Deliberately not about the *held*
     * word, like [searchableStroke] below: the chips are known words already.
     */
    val typedAddable: String? = null,
    /** Whether the held word is somewhere the keyboard can forget it from. */
    val deletable: Boolean = false,
    /**
     * Whether the keyboard has learned something about the held word that a
     * word list also holds, so forgetting the learning leaves a word behind
     * and is not the same as deleting it.
     */
    val resettable: Boolean = false,
    /** Whether the held word is on the never-suggest list. */
    val blacklisted: Boolean = false,
    /**
     * The word the caret is inside, when a swipe wrote it and its path is
     * still kept, so the menu can offer to search that path against every
     * word list (#135); null when there is no such word. Deliberately not
     * about the *held* word: the search is about the text being read back,
     * which is what the caret is in, exactly like [typedAddable] is about the
     * word being typed rather than the chip.
     */
    val searchableStroke: String? = null,
    /**
     * The word Retype (#791) would take back out of the field, so the menu
     * can offer it with no binding of its own (#844); null when there is none.
     */
    val retypable: String? = null,
    /**
     * Whether Synonyms may be offered for the held word (#321): a word in
     * letters, typed where the sources can answer (they are English).
     */
    val synonyms: Boolean = false,
    /**
     * The held word's rank adjustment, in the word card's steps, so a hold
     * that goes on to slide (#790) starts from where the word already sits.
     */
    val rankSteps: Int = 0,
)

/**
 * The word card (#99): everything the strip draws for one held word. [facts]
 * is null while the engine is still describing the word, so the card can
 * open at once and fill in. [learnedCount] and [rankOffset] are the two
 * numbers the rank controls edit; [rankControl] says which of them the card
 * draws.
 */
data class WordCard(
    /** The word as the chip showed it. */
    val word: String,
    /** The word being typed or read back, when the card may add it; else null. */
    val typed: String? = null,
    val facts: WordFacts? = null,
    /** Language id -> the name of that language's word list, for the sources. */
    val packLabels: Map<String, String> = emptyMap(),
    /** Personal-dictionary count, 0 when the word is not learned. */
    val learnedCount: Int = 0,
    /** The rank adjustment in steps, 0 when none. */
    val rankOffset: Int = 0,
    val rankControl: RankControl = RankControl.BOTH,
    /** On the never-suggest list right now. */
    val blacklisted: Boolean = false,
    /**
     * Whether the word's spelling is pinned against the case vote (#100).
     * Read cheaply when the card opens rather than waited for with [facts],
     * because it is a switch the user flips and must not arrive late (#138).
     */
    val casePinned: Boolean = false,
    /**
     * Opened by the selection bar's Edit word rather than by a hold on the
     * strip. The bar hosts that card, since the strip may not be drawn while
     * text is selected, and the card closes with the selection.
     */
    val fromSelection: Boolean = false,
)

/**
 * The synonyms the held-word menu asked for (#321), shown over the keyboard
 * until one is picked or the sheet is closed. [status] fills in as the
 * sources answer, one after another.
 */
data class SynonymsSheet(
    /** The held word, as the chip showed it. */
    val word: String,
    /**
     * The text whose capitals a pick wears: the word it replaces, so "Happy"
     * at the start of a sentence gives "Glad". Empty when the pick replaces
     * nothing (a next-word prediction was held), and the shift decides, as
     * for any pick off the strip.
     */
    val caseModel: String = "",
    val status: SynonymsStatus = SynonymsStatus.Loading,
)

/** How far the look-up behind a [SynonymsSheet] has got. */
sealed interface SynonymsStatus {
    data object Loading : SynonymsStatus

    /** [source] had synonyms; the ones before it in the user's order did not. */
    data class Found(val groups: List<SynonymGroup>, val source: SynonymSource) : SynonymsStatus

    /** Every source asked answered, and none listed synonyms for the word. */
    data object NotFound : SynonymsStatus

    /** No source could be reached. */
    data object Failed : SynonymsStatus

    /** Every source is switched off in settings. */
    data object NoSources : SynonymsStatus
}

/** What the synonyms sheet can ask of the service (#321). */
sealed interface SynonymAction {
    /** Put [word] in place of the held one, as picking it off the strip would. */
    data class Pick(val word: String) : SynonymAction

    /** Ask the sources again, after none could be reached. */
    data object Retry : SynonymAction

    data object Dismiss : SynonymAction
}

/**
 * A clip being edited in the clipboard panel's editor dialog.
 *
 * The same shape as [WordSpell]: an IME has no text field of its own to raise,
 * so the keys type into [draft], a buffer the service owns, and nothing
 * reaches the app behind the keyboard while it exists. The caret is the shared
 * capture caret, and Enter types a newline — a clip is free text, so saving is
 * the dialog's own button rather than a key.
 */
data class ClipEdit(
    /** The clip being edited, by [ClipItem.id]. */
    val id: Long,
    /** Its text when the editor opened, so an unchanged draft saves nothing. */
    val original: String,
    /** What the keys have made of it so far. */
    val draft: String = original,
    /** The clip was rich text, whose formatting a changed draft gives up. */
    val rich: Boolean = false,
    /**
     * The editor is editing the clip's tags (#612), as a list separated by
     * commas, rather than its text. Enter saves, and an empty list is a fair
     * thing to save: it takes every tag off.
     */
    val tags: Boolean = false,
) {
    /** Whether saving would change anything. Blank never saves a text. */
    val canSave: Boolean get() = if (tags) draft.trim().trimEnd(',').trim() != original else draft.isNotBlank() && draft.trim() != original

    companion object {
        /**
         * The editor's ceiling, in UTF-16 units. Generous for anything copied
         * by hand, and small enough that one draft cannot make every
         * keystroke re-lay-out a book.
         */
        const val MAX_LENGTH = 20_000
    }
}

/** Which of [ClipCombineDraft]'s three boxes the keys type into. */
/**
 * Combine's boxes (#612), in the order they are drawn after the joiner:
 * the prefix, the pattern, what each match becomes, and the result itself
 * once it is edited by hand. [TEMPLATE] and [RESULT] type through
 * [CaptureTarget.CLIP_BASE], the prefix's target, rather than targets of
 * their own: every capture `when` would otherwise need two more branches.
 */
enum class ClipCombineField { JOINER, BASE, APPEND, TEMPLATE, RESULT }

/**
 * Several clips being joined into one in the clipboard panel (#612): the clips
 * in the order they were picked, and the rule, which the keys type into the way
 * they type into the clip editor. See `ClipCombine.combine` for what each part
 * of the rule does.
 */
data class ClipCombineDraft(
    val ids: List<Long>,
    /** What goes between the clips. */
    val joiner: String,
    /** What goes in front of them all. */
    val base: String = "",
    /** A pattern: each clip gives only its first match, when not empty. */
    val append: String = "",
    /** What each match becomes, `$1` for its groups; empty keeps the match whole. */
    val template: String = "",
    /** The result as edited by hand, or null while it follows the rule (#612). */
    val result: String? = null,
    /** Combine deletes the clips it joined (#612). */
    val deleteOriginals: Boolean = false,
    val focused: ClipCombineField = ClipCombineField.JOINER,
) {
    /** The box [focused] names. */
    val focusedText: String
        get() = when (focused) {
            ClipCombineField.JOINER -> joiner
            ClipCombineField.BASE -> base
            ClipCombineField.APPEND -> append
            ClipCombineField.TEMPLATE -> template
            ClipCombineField.RESULT -> result.orEmpty()
        }

    /**
     * This, with [text] in the box [focused] names, cut to [MAX_LENGTH]. A
     * change to the rule lets go of a result edited by hand: it was an edit of
     * what the rule made before.
     */
    fun withFocusedText(text: String): ClipCombineDraft {
        val cut = text.take(if (focused == ClipCombineField.RESULT) ClipEdit.MAX_LENGTH else MAX_LENGTH)
        return when (focused) {
            ClipCombineField.JOINER -> copy(joiner = cut, result = null)
            ClipCombineField.BASE -> copy(base = cut, result = null)
            ClipCombineField.APPEND -> copy(append = cut, result = null)
            ClipCombineField.TEMPLATE -> copy(template = cut, result = null)
            ClipCombineField.RESULT -> copy(result = cut)
        }
    }

    companion object {
        /** Each box's ceiling: a rule, not a document. */
        const val MAX_LENGTH = 500
    }
}

/**
 * The clipboard panel's Undo bar after a delete (#327): the clips it would put
 * back, oldest delete first. Deleting another clip while the bar is up joins
 * it, so one Undo reverses a quick run of swipes rather than only the last.
 * The clips are held detached in the store until the bar goes (see
 * `ClipboardStore.detach`).
 */
data class ClipUndo(val items: List<ClipItem>)

/**
 * The word card's spelling editor (#138): the word being respelled, and what
 * the keys have made of it so far.
 *
 * The card is a window over the whole keyboard, so it cannot be typed into —
 * it hides while this is up and the bar takes the suggestion strip's row, the
 * same trick the AI Custom instruction plays with its panel. [draft] is a
 * buffer the service owns, exactly like the emoji query: while it exists the
 * keys never reach the app behind the keyboard.
 *
 * The draft has its own caret and selection (#204), because the keys that
 * normally edit a field's text — the spacebar scrub, shift over a selection,
 * a glide — would otherwise have nothing to act on but the app behind. The
 * selection runs from [anchor] to [cursor]; the two are equal when nothing is
 * selected, and [cursor] is the end that moves.
 */
data class WordSpell(
    /** The spelling the card was opened on, restored on cancel. */
    val word: String,
    /** What the keys have typed so far. */
    val draft: String,
    /** Where the caret stands in [draft], in UTF-16 units. */
    val cursor: Int = draft.length,
    /** The fixed end of the selection; [cursor] when nothing is selected. */
    val anchor: Int = cursor,
) {
    val selectionStart: Int get() = minOf(cursor, anchor).coerceIn(0, draft.length)
    val selectionEnd: Int get() = maxOf(cursor, anchor).coerceIn(0, draft.length)
    val hasSelection: Boolean get() = selectionStart != selectionEnd

    /**
     * [text] typed at the caret, over the selection if there is one. What
     * would push the draft past [maxLength] is cut from [text], never from
     * the letters already there.
     */
    fun typed(text: String, maxLength: Int): WordSpell {
        val kept = draft.length - (selectionEnd - selectionStart)
        val room = (maxLength - kept).coerceAtLeast(0)
        val insert = if (text.length <= room) text else text.take(safeCut(text, room))
        val next = draft.substring(0, selectionStart) + insert + draft.substring(selectionEnd)
        return collapsedAt(next, selectionStart + insert.length)
    }

    /** Backspace: the selection, else the character before the caret. */
    fun deletedBackward(): WordSpell = when {
        hasSelection -> typed("", Int.MAX_VALUE)
        cursor <= 0 -> this
        else -> {
            val from = stepBack(draft, cursor)
            collapsedAt(draft.substring(0, from) + draft.substring(cursor), from)
        }
    }

    /** Forward delete: the selection, else the character after the caret. */
    fun deletedForward(): WordSpell = when {
        hasSelection -> typed("", Int.MAX_VALUE)
        cursor >= draft.length -> this
        else -> collapsedAt(draft.substring(0, cursor) + draft.substring(stepForward(draft, cursor)), cursor)
    }

    /**
     * The caret moved [delta] characters. With [extend] the selection grows
     * or shrinks from its anchor, shift+arrow style; without, a selection
     * collapses to the side the move points at before any step is taken.
     */
    fun caretMoved(delta: Int, extend: Boolean): WordSpell {
        if (!extend && hasSelection) {
            return copy(cursor = if (delta < 0) selectionStart else selectionEnd).let { it.copy(anchor = it.cursor) }
        }
        var at = cursor.coerceIn(0, draft.length)
        repeat(kotlin.math.abs(delta)) {
            at = if (delta < 0) stepBack(draft, at) else stepForward(draft, at)
        }
        return copy(cursor = at, anchor = if (extend) anchor else at)
    }

    /** The caret put at [index], the start or end of the draft for the vertical moves. */
    fun caretAt(index: Int, extend: Boolean): WordSpell {
        val at = index.coerceIn(0, draft.length)
        return copy(cursor = at, anchor = if (extend) anchor else at)
    }

    /** A selection from [start] to [end], as a touch on the bar drew it. */
    fun selected(start: Int, end: Int): WordSpell =
        copy(anchor = start.coerceIn(0, draft.length), cursor = end.coerceIn(0, draft.length))

    /**
     * The selection run through [recase], still selected so another press
     * takes the next step; null when there is no selection or nothing about
     * it changes (a caseless script).
     */
    fun recased(recase: (String) -> String): WordSpell? {
        if (!hasSelection) return null
        val selected = draft.substring(selectionStart, selectionEnd)
        val next = recase(selected)
        if (next == selected) return null
        return copy(
            draft = draft.substring(0, selectionStart) + next + draft.substring(selectionEnd),
            anchor = selectionStart,
            cursor = selectionStart + next.length,
        )
    }

    private fun collapsedAt(text: String, at: Int) = copy(draft = text, cursor = at, anchor = at)

    private companion object {
        fun stepBack(s: String, at: Int): Int =
            if (at >= 2 && Character.isLowSurrogate(s[at - 1]) && Character.isHighSurrogate(s[at - 2])) at - 2
            else (at - 1).coerceAtLeast(0)

        fun stepForward(s: String, at: Int): Int =
            if (at + 1 < s.length && Character.isHighSurrogate(s[at]) && Character.isLowSurrogate(s[at + 1])) at + 2
            else (at + 1).coerceAtMost(s.length)

        /** [length], moved back one if it would split a surrogate pair. */
        fun safeCut(s: String, length: Int): Int =
            if (length in 1 until s.length && Character.isHighSurrogate(s[length - 1])) length - 1 else length
    }
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
 * One of the user's own stickers, offered because the text before the cursor
 * asked for it (#329). [span] is how many characters the trigger that matched
 * it occupies, which is what a send takes back when the setting says to.
 */
data class StickerOfferItem(
    val item: com.wasimaster.wmkeyboard.core.tools.GifItem,
    val span: Int,
)

/**
 * The stickers the text before the cursor asks for: their title typed out, a
 * keyword, or an emoji they carry (see `StickerTriggerIndex`).
 *
 * [expanded] is the tray opened from the narrower styles' chip or their "more"
 * button; [inEmojiPanel] marks an offer raised by an emoji picked in the emoji
 * panel, which always shows as the tray, since the panel has no strip of words
 * to share.
 */
data class StickerOffer(
    /** The trigger as it was typed, for the chip's label and the tray's description. */
    val trigger: String,
    val stickers: List<StickerOfferItem>,
    val expanded: Boolean = false,
    val inEmojiPanel: Boolean = false,
    /**
     * Emoji mixes rather than the user's stickers: the text ended in emoji, or
     * one was picked in the panel. [trigger] is then the emoji themselves.
     */
    val mix: Boolean = false,
)

/**
 * One all-thumbs-up pause (#747): how long it waits, and a number of its own so
 * the band restarts for a new pause even when it is just as long.
 */
data class AllUpCountdown(val durationMs: Long, val id: Int)

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
    /**
     * The input view is up only so a physical keyboard's conversion reading
     * has somewhere to show its candidates (#419): the strip, never the keys.
     */
    val hardwareCandidateWindow: Boolean = false,
    /**
     * Pick mode is on (#701): the physical number row selects suggestions,
     * Shift with a number types the number. Toggled by the user's pick-mode
     * key and kept until they press it again, across fields, but never saved.
     */
    val hardwarePickMode: Boolean = false,
    /**
     * The conversion candidate the arrow keys (or a stepping space bar) have
     * moved to, as an index into [suggestions]; -1 while nobody has moved, when
     * the space bar's pick is simply the first one. What Enter and the space bar
     * commit while it is set (#419). Reset whenever the candidates are redrawn.
     */
    val candidateCursor: Int = -1,
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
     * The layer [LayoutMode.NAMED] shows — a Keyman page, or a page of a
     * paginated layout — as a key into [LayoutSet.named]. An id the set no
     * longer holds draws the letters.
     */
    val namedLayer: String? = null,
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
    /**
     * The words the swipe-up popup offers over the strip, while a swipe up
     * holds it open (see [com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings.swipeUpForMore]).
     * Empty whenever no finger holds it.
     */
    val moreSuggestions: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /**
     * The word being typed, as typed, beside [suggestions] it was ranked for,
     * so the strip can draw it first ([com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings.typedWordFirst]).
     * Null between words, and on a board that composes something other than
     * its keys (Hangul, transliteration), where the keys are not the word.
     */
    val stripTyped: String? = null,
    /**
     * What the next undecided syllable of a conversion reading could be, for a
     * reading column to offer (`Composer.readings`, issue #635). Empty for every
     * composer but T9 pinyin, and once every syllable is settled.
     */
    val conversionReadings: List<String> = emptyList(),
    /**
     * Desktop Avro's candidate list for the word being typed, for the row
     * above the strip; empty unless the phonetic language is set to show it
     * there (`SuggestionStripSettings.phoneticCandidateLists`).
     */
    val phoneticCandidates: List<String> = emptyList(),
    /**
     * The word the next commit will really put in, as opposed to the word
     * merely leading the strip. The strip colours that chip and no other, so
     * the colour is a promise the commit keeps.
     *
     * Two things claim it. While text is being typed it is the word a space
     * will put in place of it, once autocorrect has actually decided to (#90):
     * null while nothing is composing, while the word stands as typed, and
     * while the decision is still being made. While a glide is being drawn it
     * is that stroke's leader, published whenever
     * [com.wasimaster.wmkeyboard.core.settings.GlideCommitColor] says a lift
     * would type it and the user should be able to see that (#121) — which is
     * how the pill and the floating words come to carry the same colour,
     * without a second field saying the same thing.
     */
    val autocorrectWord: String? = null,
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
    /**
     * The word the user actually typed before autocorrect rewrote it, offered
     * back on the same rewrite-chip slot as the three above — last in that
     * queue, since taking an old correction back can wait behind anything
     * about the word being typed right now.
     *
     * The one chip on that slot that undoes rather than rewrites, and the only
     * one that outlives the commit it belongs to: it stands until the sentence
     * ends. It names the newest of a short chain of corrections, and a tap
     * moves it on to the one before. Null whenever no correction in the
     * sentence was doubtful enough to be worth mentioning, or the chip is
     * switched off.
     */
    val correctionUndo: String? = null,
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
     * The app of the phone's autofill service (#1003), by name, while the
     * field is a password field and the chip for it is on; null otherwise.
     * The keyboard cannot ask the service to fill a field, only the app can,
     * so when no saved login comes up by itself the chip opens the manager
     * to copy one from.
     */
    val passwordManagerApp: String? = null,
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
     * What the stroke behind [glideWord] has asked of the shift key so far —
     * the capitals the lift will commit under (#115) — so the pill and the
     * strip can show the word cased the way it will land (#162).
     * [GlideCase.None] between strokes; see [shiftForGlide].
     */
    val glideCase: GlideCase = GlideCase.None,
    /**
     * The stroke's candidates as the per-letter reading of its shift crossings
     * cases them (#163), keyed by the raw word: `hello` to `HeLLo`. Only the
     * draw sites read it — the candidates themselves stay raw, because a
     * picked word is compared against the decoder's list on the way back in.
     * Empty under the whole-word reading, whose casing is one [ShiftState]
     * every surface can apply itself.
     */
    val glideCased: Map<String, String> = emptyMap(),
    /**
     * The stroke's candidates that are text-expansion triggers, keyed by the
     * raw word, with what a lift would type in their place (#205). The pill,
     * the strip and the picker's targets draw "fk = FUTO Keyboard" for these, since the trigger
     * itself is not what lands. Empty between strokes.
     */
    val glideExpansions: Map<String, GlideExpansion> = emptyMap(),
    /**
     * The words a mid-swipe decode is choosing between, best first, capped at
     * [GestureSettings.pickerChoices]. Populated for every preview while the
     * picker is on (empty when it is off), because a stroke that is not a
     * close call can still be asked about by holding longer; [glideCloseCall]
     * says which kind this one is. Holding the finger still while this is
     * populated puts them under the fingertip to pick from.
     */
    val glideChoices: List<String> = emptyList(),
    /**
     * Whether the latest preview's leader barely beats its runner-up under
     * the user's [GestureSettings.pickerSensitivity]. Drives the short dwell;
     * false with [glideChoices] populated is the long "hold to ask" case.
     * Always true under [GlidePickerSensitivity.EVERY_PAUSE] once there are
     * two readings.
     */
    val glideCloseCall: Boolean = false,
    /**
     * The two-thumb all-thumbs-up pause running now, for the band on the strip
     * that shrinks until the word is typed (#747); null while none is.
     */
    val allUpCountdown: AllUpCountdown? = null,
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
    /**
     * The caret sits collapsed at the very start of the field, as last reported
     * by the editor. Nothing has been typed in front of it, so any word on the
     * strip is a sentence opener predicted before the first keystroke, and the
     * bar only rests on those when "Suggestion strip always visible" is on.
     */
    val caretAtFieldStart: Boolean = false,
    /**
     * The list the strip was last given as sentence openers: words predicted
     * with nothing typed after a sentence end or a line break (#889). Compared
     * by identity with [suggestions], so any other path that writes the strip
     * replaces the list and the two stop matching without a flag to clear.
     */
    val sentenceOpeners: List<String> = emptyList(),
    val composingPreview: String = "",
    /**
     * A word is being typed into the service's own buffer: the Latin path,
     * which composes in the field and so leaves [composingPreview] empty.
     * Read by [voiceStripShown], which hands the strip back from the compact
     * dictation bar while a word is being typed (#910); a reading being
     * converted shows up in [composingPreview] instead.
     */
    val wordComposing: Boolean = false,
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
     * The reading being typed ends in a kana that has a small, dakuten or
     * handakuten form, so a [Key.kanaVariantWhileComposing] key is showing as
     * the 小゛゜ key (issue #340).
     *
     * Only ever true on a board that has such a key ([LayoutSet.hasKanaVariantKeys]):
     * it is a key of the grid's `remember`, and it moves kana by kana.
     */
    val kanaVariantReady: Boolean = false,
    /**
     * Probability weight (0..1) of each letter being typed next, given the
     * current composing word. Drives smart key-hit detection, which nudges
     * boundary taps toward the high-weight letters. Empty when the feature is
     * off, the field is not composing, or nothing is predicted.
     */
    val nextLetterBias: Map<Char, Float> = emptyMap(),
    /**
     * The word floating over each key: the prediction a press of that key is on
     * its way to (discussion #102). Keyed by the key's anchor code point — an
     * Int, not a Char, so a letter outside the BMP is one key and not two
     * halves of a surrogate pair.
     *
     * At most one word per key, and no word appears twice. Empty when the
     * octopus is off, in a conversion or transliterating composer, off the
     * letters layer, or when nothing is confident enough to float.
     *
     * Read by the overlay and by the flick, never by a key — see [KeyVisual].
     * It changes on every keystroke, so a key that read it would cost the whole
     * board the recomposition skip that class exists to buy.
     */
    val octopus: OctopusBoard = emptyMap(),
    /**
     * The same, for a stroke that is still being drawn: each alternate the
     * decoder is still holding, over the key that would reach it.
     *
     * Its own field rather than sharing [octopus], and that separation is
     * load-bearing. The flick asks, at the lift, what was floating over the key
     * the finger went down on — and a stroke long enough to be a flick is long
     * enough to have published a preview, so sharing one field meant the answer
     * had already been overwritten by the alternates and every flick fell
     * through to the decoder.
     */
    val octopusGlide: OctopusBoard = emptyMap(),
    /** Text-edit panel: arrows extend the selection instead of moving the cursor. */
    val textEditSelecting: Boolean = false,
    /**
     * No space is armed for the next key (#863), by a hold or a double tap of
     * its key or by its own action: that key gets no space the keyboard would
     * add. Spent by it, and called off by Space, Enter, another tap or a new
     * field. Lights the No space keys while it lasts.
     */
    val noSpaceArmed: Boolean = false,
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
    /**
     * Tools by use, for the toolbox's orders by use
     * ([com.wasimaster.wmkeyboard.core.settings.ToolboxSort]): latest
     * first, and most runs first. Published as the toolbox opens, so a tool
     * tapped inside it never moves its neighbours under the finger.
     */
    val toolsRecent: List<ToolbarTool> = emptyList(),
    val toolsMostUsed: List<ToolbarTool> = emptyList(),
    /** Every tool by its name in the keyboard's language, for the toolbox's A to Z order. */
    val toolsAlphabetical: List<ToolbarTool> = emptyList(),
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
    /**
     * True when no preview is coming for the open popup: data saving held it
     * back, or the fetch failed. The popup offers Send anyway then, since
     * pressing it is the answer data saving waits for, and a failed preview
     * is no reason to refuse a GIF that may well come down on a second try.
     */
    val animatedEmojiNoPreview: Boolean = false,
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
    /**
     * A continuation of the text before the cursor from the AI provider (#944),
     * offered as a marked chip beside the word candidates. Null with the
     * setting off, in a field it may not read, or while nothing came back.
     */
    val aiSuggest: String? = null,
    /** Stickers of the user's own that the text before the cursor asks for (#329). */
    val stickerOffer: StickerOffer? = null,
    /** The emoji mixes panel; see [EmojiMixUi]. */
    val emojiMix: com.wasimaster.wmkeyboard.ime.emojimix.EmojiMixUi = com.wasimaster.wmkeyboard.ime.emojimix.EmojiMixUi(),
    /** Input a chip loaded into the tool it is about to open; consumed once. */
    val toolPrefill: ToolPrefill? = null,
    /** The Text repeat panel's text, boxes and undo; see [TextRepeatUi]. */
    val textRepeat: TextRepeatUi = TextRepeatUi(),
    /** The word-of-the-day chip, offered once on the first field of the day. */
    val vocabDaily: VocabDailyChip? = null,
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
    /**
     * What search by meaning found for the clipboard, notes and snippets
     * boxes, listed after their keyword matches (see [MeaningHits]).
     */
    val meaning: com.wasimaster.wmkeyboard.ime.semantic.MeaningHits = com.wasimaster.wmkeyboard.ime.semantic.MeaningHits(),
    /** Typing edits [clipboardQuery] instead of the field, like emoji search. */
    val clipboardSearchActive: Boolean = false,
    /**
     * The clipboard is stacked over the live keys (#414): a short strip of
     * clips with the key rows under it, typing into the app, so pasting and
     * typing can alternate without opening and closing the panel. Toggled
     * from the panel's header and kept until toggled back.
     */
    val clipboardWithKeys: Boolean = false,
    /** The clip open in the clipboard panel's editor; see [clipEditActive]. */
    val clipEdit: ClipEdit? = null,
    /** The clips being combined into one, and the rule (#612); see [clipCombineActive]. */
    val clipCombine: ClipCombineDraft? = null,
    /** The clipboard panel's Undo bar, while a delete can still be taken back. */
    val clipboardUndo: ClipUndo? = null,
    /**
     * Most recently copied text, offered as a paste chip on the suggestion strip
     * (Gboard style). Null when nothing recent, the chip expired, was dismissed,
     * or the feature is off. Cleared on paste/dismiss/timeout.
     */
    val clipboardSuggestion: ClipItem? = null,
    /**
     * The row of recent copies is on the strip (#414, #612). Only ever put
     * there by hand, by the button beside the last-copy chip or the Recent
     * copies tool, and taken down by the first key typed, its ✕, or another
     * field: a row that came and went by itself flickered every time the
     * strip had nothing else to show.
     */
    val recentClipsOpen: Boolean = false,
    /**
     * [clipboardSuggestion] is a copy the keyboard itself just made and kept
     * private (#679). A private copy gets no system "Copied" bubble, so the
     * chip is the one sign it worked, and it holds the strip, over any words
     * and any smart chip, until the next key typed or the next field. Before,
     * the caret settling after the copy put the strip's words back up and the
     * chip shrank beside them, or went altogether behind a smart chip, which
     * read as the copy having done nothing.
     */
    val clipChipHeld: Boolean = false,
    /**
     * The [ClipItem.id] of the copy whose parts no longer hold the strip
     * (#612, `fragmentsOnStrip`): typing or the row's ✕ put them away. Kept
     * as an id rather than a flag so a field change does not bring them back,
     * and the next copy does.
     */
    val clipFragmentsDoneId: Long? = null,
    /**
     * One-time code lifted from a just-arrived notification, offered as a chip
     * on the suggestion strip. Null when there is none, it expired, it was
     * used or dismissed, or the current field fails the feature's gates
     * (number-fields-only, incognito). The code exists only here and on the
     * in-process bus — it is never written anywhere.
     */
    val otpSuggestion: NotificationOtp? = null,
    /** A task that has fallen due, waiting on the strip. See [TodoDueChip]. */
    val todoDueChip: TodoDueChip? = null,
    /**
     * A Send share link that just reached the clipboard (#729), offered on the
     * strip until it is opened, dismissed or stale. Null when there is none.
     */
    val sendLinkOffer: com.wasimaster.wmkeyboard.ime.send.SendLinkOffer? = null,
    /**
     * Whether the focused field reads as a box a verification code goes into
     * — by its input class or by the words the app gave it. Gates both code
     * chips when the user has narrowed them to code boxes; see
     * [looksLikeCodeField].
     */
    val codeField: Boolean = false,
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
    /**
     * A sandbox rung the ladder has earned and is asking about, or null while
     * nothing is being offered. Only ever set under
     * [com.wasimaster.wmkeyboard.core.settings.GlideSandbox.AUTOMATIC]: a
     * policy picked by hand is the user's answer already.
     */
    val sandboxOffer: GlideSandboxPolicy? = null,
    /**
     * The language glide typing has no word list for, while the strip is
     * saying so (#219), or null. A swipe on such a language goes nowhere, and
     * with nothing on screen to explain it the keyboard looked broken. Put up
     * by the readiness watcher, once per language per process; taken down by
     * the next keystroke or its own ✕. Tapping it opens the language's page,
     * where the list downloads.
     */
    val glideWordListOffer: LanguageDef? = null,
    /**
     * Whether [glideWordListOffer] is up for a phonetic layout typing on its
     * rules alone (#239) rather than for glide. Same chip, same tap, same
     * dismissal — only the sentence on it differs.
     */
    val wordListOfferIsPhonetic: Boolean = false,
    /**
     * The [com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog] pack a
     * conversion IME needs and does not have, while the strip is saying so
     * (#260), or null.
     *
     * The conversion tables are too big to bundle, so Chinese and Japanese
     * convert nothing until their pack is downloaded: the reading keeps
     * composing and no character is ever offered for it, which on screen is a
     * keyboard that does not work. Put up by the strip refresh once a reading
     * has actually been typed, once per pack per process; tapping it opens the
     * language's page, where the pack downloads.
     */
    val conversionPackOffer: String? = null,
    /**
     * The strip's Zawgyi chip, or null: pasted text, or the field, found to
     * be Zawgyi. Taken down by its ✕, by the next field, or by being answered.
     */
    val zawgyiOffer: ZawgyiOffer? = null,
    /**
     * Whether the Burmese layout on screen writes Zawgyi now, from its setting
     * or from the strip's offer for this field; what lights the Zawgyi button.
     */
    val zawgyiOutput: Boolean = false,
    /**
     * The word the caret is sitting in that a swipe wrote, while the strip is
     * offering to search that swipe's path against every word list (#135), or
     * null. Only ever set with `GestureSettings.searchAllChip` on: without it
     * the same search is reached from the held-word menu and nothing claims a
     * strip slot. Taken down by the caret leaving the word, and by the search
     * itself once it has answered.
     */
    val glideSearchChip: String? = null,
    /** The word card a held suggestion opened, or null while none is up (#99). */
    val wordCard: WordCard? = null,
    /** The card's spelling editor while it is up; see [WordSpell] (#138). */
    val wordSpell: WordSpell? = null,
    /** The synonyms a held word's menu asked for, or null while none are up (#321). */
    val synonyms: SynonymsSheet? = null,
    /**
     * The one-tap actions offered for the current selection, or null when
     * there is no selection to act on (or the feature is off).
     *
     * Held as a resolved offer rather than as the selected text alone: what the
     * selection *is* costs a set of regexes, the answer decides whether a whole
     * row of the keyboard exists, and both are read on every frame the bar
     * draws. See [SelectionMacroOffer].
     */
    val selectionMacros: SelectionMacroOffer? = null,
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
     * The snippets panel's search text (#471), matched against every snippet's
     * label, text and triggers, folders or not. Empty when not searching.
     */
    val snippetQuery: String = "",
    /** Typing edits [snippetQuery]; the panel collapses so the keys fit under it. */
    val snippetSearchActive: Boolean = false,
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
    /** The toolbox panel's own layers: its search (#687) and the folder open in it (#709). */
    val toolboxView: ToolboxUi = ToolboxUi(),
    /**
     * MIME types the focused editor advertises for commitContent
     * (EditorInfo.contentMimeTypes). Empty means the field takes text only —
     * a GIF or sticker sent there can never arrive, so the media panels say so
     * up front instead of the send failing into a clipboard fallback.
     */
    val fieldContentMimeTypes: List<String> = emptyList(),
    val secureField: Boolean = false,
    /** Read back is reading the field aloud; its tool stays lit until it ends or is tapped again. */
    val readingBack: Boolean = false,
    /**
     * The device lock screen (keyguard) is showing. Refreshed on each field
     * start from [android.app.KeyguardManager]. Only the "hide toolbar &
     * clipboard on lock screen" privacy setting reads it.
     */
    val deviceLocked: Boolean = false,
    /** Field class/variation of the focused editor, from EditorInfo. */
    val fieldKind: FieldKind = FieldKind.TEXT,
    /**
     * The focused editor declares the TYPE_NULL class: a terminal emulator
     * (Termux, issue #220) or the framework's fallback connection for a window
     * with no editor at all, which is what a pinned keyboard (issue #58) sits
     * over. Such an editor takes *committed* text and nothing else — Termux
     * only flushes its buffer to the terminal on commitText/finishComposingText,
     * and the fallback connection's buffer is read by nobody — so a composing
     * region put there is invisible until something ends it. Typing "echo"
     * showed nothing until the space after it, which then dumped the whole word
     * at once. Nothing composes in such a field
     * ([composesWords]) and none of the prose rules run in it
     * ([allowsTypingIntelligence]): a shell prompt is not prose, and a space
     * the keyboard types after a comma is a changed command line.
     *
     * Deliberately *not* extended to a transliterating composer (Avro): its
     * roman keys have to buffer somewhere to become Bengali, and committing
     * each intermediate form would need a delete-and-recommit that a terminal's
     * connection cannot answer either. Avro in a terminal stays as it was.
     */
    val nullField: Boolean = false,
    /**
     * The keyboard is running on a television. A device fact rather than a
     * setting, so it lives here and not in [KeyboardSettings]: the docked
     * board is drawn as a card floating over the app, the way Gboard draws
     * it on a TV, instead of a strip across the whole screen.
     */
    val television: Boolean = false,
    /**
     * Where the focused field asked the board to sit, from a
     * `horizontalAlignment=left|center|right` in its
     * [android.view.inputmethod.EditorInfo.privateImeOptions] — the hint
     * Android TV documents for Gboard, so a sign-up form down the right of
     * the screen keeps the board under it. Null when the field says nothing,
     * and always null off a television. Beats
     * [KeyboardSettings.keyboardAlignment] for this field only.
     */
    val fieldAlignment: KeyboardAlignment? = null,
    /**
     * The field asked the keyboard to hide the *suggestion strip*
     * (TYPE_TEXT_FLAG_NO_SUGGESTIONS, or an email/URI/filter/password
     * variation). Strip visibility only — autocorrect, phonetic composing
     * and learning are gated on [allowsTypingIntelligence] and gesture typing
     * on [allowsGestureTyping] instead, so a field that silences the strip
     * keeps all of those. The
     * "Suggestions in every field" setting can override this for text and URL
     * fields; in a URL field the strip it shows is also what lets the words
     * typed there compose ([composesWords]).
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
    /** The Modes tool's None row is picked: no mode, whatever the bindings say. */
    val noModePicked: Boolean = false,
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
    /** The Notes panel (#560); survives the panel closing like [vocab]. */
    val notes: NotesPanelUi = NotesPanelUi(),
    /**
     * The Clock panel's text box while it has the keys: a zone search or a
     * label. The rest of the panel's state is the controller's.
     */
    val clockText: com.wasimaster.wmkeyboard.ime.clock.ClockTextEntry? = null,
    /**
     * The To-do panel's text box while it has the keys: a new task, a new
     * step, or a task's words being edited (#920).
     */
    val todoText: com.wasimaster.wmkeyboard.ime.todo.TodoTextEntry? = null,
    /**
     * The Home Assistant panel's text box while it has the keys: a search, an
     * Assist sentence, a lock code, a text entity's value. The rest of the
     * panel's state is the controller's.
     */
    val haText: com.wasimaster.wmkeyboard.ime.homeassistant.HaTextEntry? = null,
    /**
     * The gallery panel's text box while it has the keys: the caption, or a
     * text layer in the editor. The rest of the panel's state is the controller's.
     */
    val galleryText: com.wasimaster.wmkeyboard.ime.gallery.GalleryTextEntry? = null,
    /**
     * The Calendar composer's text box while it has the keys: an event's
     * title, place, notes, or a guest being added. The rest of the panel's
     * state is the controller's.
     */
    val calendarText: com.wasimaster.wmkeyboard.ime.calendar.CalendarTextEntry? = null,
    /**
     * The emoji panel's kaomoji tab's text box while it has the keys: its
     * search, a face being added or changed, a tag's name. The rest of the
     * tab's state is the controller's.
     */
    val kaomojiText: com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiTextEntry? = null,

    /**
     * The Send files panel's text box while it has the keys: a password, a
     * link to receive, an instance address. The rest is the controller's.
     */
    val sendText: com.wasimaster.wmkeyboard.ime.send.SendTextEntry? = null,
    val weather: WeatherUi = WeatherUi.NoLocation,
    val dictionary: DictionaryUi = DictionaryUi.Idle,
    /** Word in the dictionary panel's search field. */
    val dictionaryQuery: String = "",
    /** Typing edits [dictionaryQuery] (letters show under the panel). */
    val dictionarySearchActive: Boolean = false,
    val vocab: VocabPanelUi = VocabPanelUi(),
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
    /** The KDE Connect panel: tab, and the buffers its keys write (see [KdeUi]). */
    val kde: KdeUi = KdeUi(),
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
    /**
     * Ids of GIFs/stickers tapped while another was still sending, in the
     * order they will go out. A cell shows its place in this line.
     */
    val mediaQueue: List<String> = emptyList(),
    /**
     * Selected provider chip on the GIF/sticker panel: the provider row's key
     * (`ProviderEntry.key`). Blank, or a row since removed, is the first chip.
     */
    val mediaProvider: String = "",
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
    /**
     * The category chip in effect, or null for trending and for a typed query.
     * Also the GIF panel's favourites and recent chips (#597), as
     * [com.wasimaster.wmkeyboard.core.tools.MediaCategories.FAVOURITES] and `RECENT`.
     */
    val mediaCategory: String? = null,
    /** Ids of the GIF favourites, for the long-press menu's star and the favourites chip (#597). */
    val gifFavouriteIds: Set<String> = emptySet(),
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
    /** The Find and replace panel's fields and matches; null while it is closed. */
    val findReplace: FindReplaceUi? = null,
    /** The Learn from text panel's rows and editor (#174); null while it is closed. */
    val learnFromText: LearnFromTextUi? = null,
    val webSearch: WebSearchUi = WebSearchUi.Idle,
    val imageSearch: ImageSearchUi = ImageSearchUi.Idle,
    /**
     * Which of the search panel's tabs is up (#879): web results or images.
     * The two searches keep their own results, so switching back costs nothing.
     */
    val searchTab: SearchTab = SearchTab.ALL,
    /**
     * The SearXNG category chip picked on the All tab (#934), or null for
     * the general search. Its results take the All tab's place.
     */
    val searchCategory: String? = null,
    /**
     * The camera was opened from the search panel's camera button
     * (#349): its confirm step offers Search in place of Send, and its back
     * button returns to image search. Set by [WMKeyboardService.onPanelChange]
     * on every panel change, so it only ever means anything while the camera
     * panel is open.
     */
    val cameraSearchOnly: Boolean = false,
    /**
     * The picture the OCR panel reads instead of the camera: the file of an
     * image clip whose Extract text was pressed in the clipboard panel (#371).
     * Its back button returns to the clipboard. Cleared by
     * [WMKeyboardService.onPanelChange] on every panel change, like
     * [cameraSearchOnly].
     */
    val ocrImage: String? = null,
    /**
     * The picture the camera's confirm step opens on instead of a capture: the
     * file of an image clip whose Translate was pressed in the clipboard panel
     * (#807). The photo is translated as soon as it is up, and the back button
     * returns to the clipboard. Cleared on every panel change, like [ocrImage].
     */
    val cameraImage: String? = null,
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
    val aiChat: AiChatUi = AiChatUi(),
    /** The AI reply tool's own state (#234); the run itself is on [ai]. */
    val aiReply: AiReplyUi = AiReplyUi(),
    val typingTest: TypingTestUi = TypingTestUi(),
    /** The internet speed test panel; see [com.wasimaster.wmkeyboard.ime.speedtest.SpeedTestUi]. */
    val speedTest: com.wasimaster.wmkeyboard.ime.speedtest.SpeedTestUi = com.wasimaster.wmkeyboard.ime.speedtest.SpeedTestUi(),
    /** Launchable apps for the app-launcher panel; empty until first opened. */
    val launcherApps: List<LauncherApp> = emptyList(),
    /** First enumeration in flight — the panel shows its loading state. */
    val launcherLoading: Boolean = false,
    /** The app whose activity list is open, or null for the grid. */
    val launcherDetail: LauncherDetailUi? = null,
    /** The open app folder and its name box (#919). */
    val launcherView: LauncherUi = LauncherUi(),
    /**
     * The caret in whichever keyboard-owned field has the keys (#161).
     *
     * One caret, not one per buffer: exactly one of those fields is focused at
     * a time, and the buffer it belongs to is named in [CaptureCaret.key], so
     * a caret left behind by a field that has since closed simply stops
     * matching and the next field starts at its own end. See [captureTarget].
     */
    val captureCaret: CaptureCaret? = null,
    /**
     * The suggestion strip for the focused keyboard-owned field — drawn in the
     * row above the keys, where a panel has taken the toolbar. Empty whenever
     * no such field is focused, so nothing of the field behind the keyboard
     * can leak into it and nothing of it can leak out.
     */
    val captureSuggestions: List<String> = emptyList(),
    /**
     * The recent-word bar's words (#787), oldest first: the last words
     * written in this field before the caret, not counting the one being
     * typed, each with what else it could have been (#842). Empty while the
     * bar is off or has nothing to show.
     */
    val recentBar: List<RecentBarWord> = emptyList(),
    /** The focused keyboard-owned field's undo history, while it has one (#665). */
    val captureUndo: CaptureUndoMark? = null,
    /**
     * The tool field whose keys the focus switch handed to the app's field
     * (#665), as its [panelFieldKey], or null while every tool field keeps
     * the keys it took. Keyed to the field rather than a plain flag, so moving
     * to another box of the tool, or opening another tool, gives that box the
     * keys again without anything having to remember to clear this.
     */
    val captureToApp: String? = null,
    /**
     * The tool field whose key rows were put down (#961), as its
     * [panelFieldKey], or null while every focused field has its keys up.
     * The field keeps its text and its caret, and the panel gets the rows'
     * height back to show what it holds; a tap on the field brings the keys
     * up again. Keyed to the field for the same reason as [captureToApp].
     */
    val captureLowered: String? = null,
) {
    /**
     * Whether incognito is in force right now, from either source: the
     * persisted switch the user flips, or a field that asked for it. Every
     * gate and indicator reads this rather than the setting alone.
     */
    val incognitoOn: Boolean get() = settings.incognito || fieldIncognito

    /**
     * Whether the spacebar's typing speed (#752) is kept out of this field: a
     * password field or incognito (#796). Its words are not timed and no
     * speed shows.
     */
    val liveWpmPrivate: Boolean get() = incognitoOn || secureField

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
     * phonetic (Avro) composing and lexicon learning. (Gesture typing and word
     * completion have their own, slightly wider gates: [allowsGestureTyping]
     * and [composesWords].) These
     * belong to prose entry, so they apply to plain text fields only and are
     * deliberately independent of [fieldNoSuggestions]: an app that hides the
     * suggestion strip (Instagram, Google Keep) must not also lose autocorrect
     * or the ability to type Bengali. Password fields ([secureField]),
     * structured fields (email, URI, number, phone, date) and editors that
     * take committed text only ([nullField] — a terminal) opt out — a keypad
     * has no words to correct, an address should not be second-guessed, and a
     * shell prompt is not prose.
     */
    val allowsTypingIntelligence: Boolean
        get() = !secureField && !nullField && fieldKind == FieldKind.TEXT

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
     * Whether an opening bracket may type its closer behind the caret, and the
     * two presses that go with it (stepping over a closer, a backspace taking
     * an empty pair). Wider than [allowsTypingIntelligence] by the URL field,
     * for the reason [allowsGestureTyping] is: a browser's address bar is a
     * search box as often as an address box, and "(" typed there is somebody
     * opening a bracket (#617). Nothing in the pair is a guess about a word, so
     * nothing here second-guesses an address. Email fields, passwords, keypads
     * and terminals stay out.
     */
    val allowsBracketPairs: Boolean
        get() = !secureField && !nullField && (fieldKind == FieldKind.TEXT || fieldKind == FieldKind.URI)

    /**
     * Whether the letters typed here are gathered into a word, for the strip to
     * complete or autocorrect to fix. Autocorrect reads nothing but this
     * buffer, so in a text field it composes with the strip switched off too:
     * gated on the strip alone, turning suggestions off silently turned
     * autocorrect, apostrophe fixes and learning off with it. Plain text
     * fields, and a URL bar once "Suggestions in every
     * field" has put the strip on it (#98): a browser's address bar is typed
     * as a search box as often as an address box, the same reading #37 gave
     * [allowsGestureTyping]. The word composed there feeds the strip and
     * nothing else — autocorrect, learning and the caret-word resume keep to
     * [allowsTypingIntelligence] — so an address is completed on request and
     * never second-guessed. Email fields stay out with the rest of the
     * structured kinds: an address has no dictionary words to complete. With
     * the strip hidden ([fieldNoSuggestions]) a URL bar does not compose at
     * all, since composing there would have nothing to show; a text field
     * still does, because autocorrect reads the same buffer. An editor that
     * cannot show a composing region at all ([nullField]) stays out whatever
     * the strip is doing: there the buffer is not merely unhelpful, it is
     * text the user typed and cannot see (issue #220).
     */
    val composesWords: Boolean
        get() = !secureField && !nullField && (
            fieldKind == FieldKind.TEXT && (settings.suggestions || settings.correction.enabledFor(language.id)) ||
                (fieldKind == FieldKind.URI && settings.suggestions && !fieldNoSuggestions)
            )

    /**
     * Whether a word the caret comes back to is taken up again as the word
     * being typed: backspace into "excepted", type on, and the whole word is
     * the one being finished. Wherever words are composed at all, the URL bar
     * included: there [allowsTypingIntelligence] said no, so a word backspaced
     * into and typed on in a browser's address bar started over from the new
     * letters, and the strip offered words for "io" instead of "exceptio"
     * (#662, #679). Taking the word up changes nothing in the field, and the
     * corrections that do stay on [allowsTypingIntelligence].
     */
    val resumesWords: Boolean
        get() = allowsTypingIntelligence || (fieldKind == FieldKind.URI && composesWords)

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

    /** The AI panel is showing its chat mode rather than its actions (#280). */
    val aiChatShown: Boolean
        get() = panel == PanelMode.AI && aiChat.open && aiChat.available

    /**
     * Whether keystrokes belong to the chat composer on the AI panel. Panel
     * *and* mode *and* a focused composer, the [findReplaceTypingActive]
     * contract: no one of them alone can keep the keys from the app behind.
     */
    val aiChatComposing: Boolean
        get() = aiChatShown && aiChat.composing && (!aiChat.showSessions || aiChat.renaming >= 0)

    /**
     * Whether keystrokes belong to the reply panel's own message box (#234).
     * Panel *and* a focused box, the [findReplaceTypingActive] contract.
     */
    val aiReplyTypingActive: Boolean
        get() = panel == PanelMode.AI_REPLY && aiReply.typing

    /**
     * Whether keystrokes belong to the KDE Connect panel's "add by address"
     * box. Panel *and* an open box, the [findReplaceTypingActive] contract.
     */
    val kdeHostEntryActive: Boolean
        get() = panel == PanelMode.KDE_CONNECT && kde.hostEntry

    /**
     * Whether keystrokes are being typed on the paired computer instead of
     * into the app behind the keyboard (issue #285). Panel *and* the Input tab
     * *and* the switch: closing the panel, changing tab or opening the device
     * list each hand the keys back on their own.
     */
    val kdeTypingActive: Boolean
        get() = panel == PanelMode.KDE_CONNECT && kde.typing && kde.tab == KdeTab.INPUT &&
            !kde.showDevices && !kde.hostEntry

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
     * Whether keystrokes belong to the Find and replace panel's fields. The
     * same contract as [pluginTypingActive]: panel *and* state, so neither
     * alone can leak a keystroke into the app behind the keyboard.
     */
    val findReplaceTypingActive: Boolean
        get() = panel == PanelMode.FIND_REPLACE && findReplace != null

    /**
     * Whether keystrokes belong to the Learn from text panel's spelling editor
     * (#174). Panel *and* an open editor, like [findReplaceTypingActive].
     */
    val learnEditActive: Boolean
        get() = panel == PanelMode.LEARN_FROM_TEXT && learnFromText?.editing != null

    /**
     * Whether keystrokes belong to the word card's spelling editor rather
     * than to the text field (#138) — true while the spelling bar is up, so
     * respelling a suggestion never writes into the app behind the keyboard.
     */
    val wordSpellActive: Boolean
        get() = wordSpell != null

    /**
     * Whether keystrokes belong to the Notes panel's editor (#560). Panel
     * *and* an open edit, like [clipEditActive], so an edit left behind by a
     * panel that closed some other way cannot keep the keys.
     */
    val notesEditActive: Boolean
        get() = panel == PanelMode.NOTES && notes.editing != null

    /**
     * Whether one of the Notes panel's fields has the keys: the editor, or the
     * search pill. The same contract as [notesEditActive].
     */
    val notesTypingActive: Boolean
        get() = panel == PanelMode.NOTES && (notes.editing != null || notes.searchActive)

    /** Whether the Clock panel's text box has the keys; panel *and* box, as [notesTypingActive]. */
    val clockTypingActive: Boolean
        get() = panel == PanelMode.CLOCK && clockText != null

    /** Whether the To-do panel's text box has the keys; panel *and* box, as [clockTypingActive]. */
    val todoTypingActive: Boolean
        get() = panel == PanelMode.TODO && todoText != null

    /** Whether the Home Assistant panel's text box has the keys; panel *and* box, as [clockTypingActive]. */
    val haTypingActive: Boolean
        get() = panel == PanelMode.HOME_ASSISTANT && haText != null

    /** Whether the Calendar composer's text box has the keys; panel *and* box, as [clockTypingActive]. */
    val calendarTypingActive: Boolean
        get() = panel == PanelMode.CALENDAR && calendarText != null

    /** Whether the gallery panel's text box has the keys; panel *and* box, as [clockTypingActive]. */
    val galleryTypingActive: Boolean
        get() = panel == PanelMode.GALLERY && galleryText != null

    /** Whether the kaomoji tab's text box has the keys; panel *and* box, as [clockTypingActive]. */
    val kaomojiTypingActive: Boolean
        get() = panel == PanelMode.EMOJI && kaomojiText != null

    /** Whether keystrokes belong to one of the Text repeat panel's boxes. */
    val textRepeatTypingActive: Boolean
        get() = panel == PanelMode.TEXT_REPEAT && textRepeat.focused != null
    /** Whether the Send files panel's text box has the keys; panel *and* box, as [galleryTypingActive]. */
    val sendTypingActive: Boolean
        get() = panel == PanelMode.SEND_FILES && sendText != null

    /**
     * Whether keystrokes belong to the clipboard panel's clip editor. Panel
     * *and* an open edit, like [learnEditActive], so an edit left behind by a
     * panel that closed some other way cannot keep the keys.
     */
    val clipEditActive: Boolean
        get() = panel == PanelMode.CLIPBOARD && clipEdit != null

    /** Whether keystrokes belong to the clipboard panel's Combine rule (#612); as [clipEditActive]. */
    val clipCombineActive: Boolean
        get() = panel == PanelMode.CLIPBOARD && clipCombine != null

    /**
     * Whether the clipboard panel has handed the key rows back for one of its
     * own fields — the search pill or the clip editor — and shrunk to make
     * room for them.
     */
    val clipboardTakesKeys: Boolean
        get() = when (panelFieldTarget()) {
            CaptureTarget.CLIPBOARD_SEARCH, CaptureTarget.CLIP_EDIT,
            CaptureTarget.CLIP_JOINER, CaptureTarget.CLIP_BASE, CaptureTarget.CLIP_APPEND,
            -> panel == PanelMode.CLIPBOARD
            else -> false
        }

    /**
     * Whether some buffer inside the keyboard owns the keys, so nothing typed
     * may reach the app behind it.
     *
     * The same set the typed-text path branches on, named once. Anything that
     * bypasses that path — the Keyman rule engine writes to the field directly —
     * has to ask this first, and asking a single property is the only way that
     * question stays answered the same way in both places. The two existing
     * copies of this list have already drifted apart once — and a third copy,
     * this one, used to omit the three search fields, which is part of why
     * none of them could be glided into (#161). It is now [captureTarget]'s
     * answer rather than a list of its own, so there is nothing left to drift.
     */
    val keysTakenByKeyboard: Boolean
        get() = captureTarget() != null

    /**
     * Which keyboard-owned field has the keys, or null when they belong to the
     * app behind the keyboard.
     *
     * **The one gate.** Every path that has to know — typed text, backspace,
     * forward delete, space, enter, the caret keys, undo and redo, paste,
     * glide, the suggestion strip, the Keyman seam — asks this instead of
     * re-deriving it. So the focus switch (#665) only has to answer here for
     * every one of those to follow it at once: a tool field whose keys were
     * handed to the app's field is no target at all, and the keys go where
     * they go with no panel open.
     */
    fun captureTarget(): CaptureTarget? = if (captureHandedToApp) null else panelFieldTarget()

    /**
     * The open tool's focused field, whether or not it has the keys right now:
     * what the panel draws as its field and lays itself out around. Differs
     * from [captureTarget] only while the focus switch has sent the keys to
     * the app's field (#665).
     *
     * **The ladder.** The order here is the order `processTypedText` always
     * used, so nothing changes hands.
     */
    fun panelFieldTarget(): CaptureTarget? = when {
        typingTestActive -> CaptureTarget.TYPING_TEST
        aiCustomInputActive -> CaptureTarget.AI_CUSTOM
        aiChatComposing -> CaptureTarget.AI_CHAT
        aiReplyTypingActive -> CaptureTarget.AI_REPLY
        kdeHostEntryActive -> CaptureTarget.KDE_HOST
        kdeTypingActive ->
            if (settings.kdeConnect.composeMode) CaptureTarget.KDE_COMPOSE else CaptureTarget.KDE_REMOTE
        pluginTypingActive -> CaptureTarget.PLUGIN
        findReplaceTypingActive ->
            if (findReplace?.focused == FindReplaceField.REPLACE) {
                CaptureTarget.FIND_REPLACEMENT
            } else {
                CaptureTarget.FIND_QUERY
            }
        learnEditActive -> CaptureTarget.LEARN_EDIT
        notesTypingActive -> when (notes.editing?.focused) {
            NoteEditField.TITLE -> CaptureTarget.NOTE_TITLE
            NoteEditField.BODY -> CaptureTarget.NOTE_BODY
            NoteEditField.TAG -> CaptureTarget.NOTE_TAG
            null -> CaptureTarget.NOTE_SEARCH
        }
        clockTypingActive ->
            if (clockText?.field == com.wasimaster.wmkeyboard.ime.clock.ClockTextField.SEARCH) {
                CaptureTarget.CLOCK_SEARCH
            } else {
                CaptureTarget.CLOCK_LABEL
            }
        todoTypingActive -> CaptureTarget.TODO_TEXT
        galleryTypingActive -> CaptureTarget.GALLERY_TEXT
        calendarTypingActive -> when (calendarText?.field) {
            com.wasimaster.wmkeyboard.ime.calendar.CalendarTextField.LOCATION -> CaptureTarget.CALENDAR_LOCATION
            com.wasimaster.wmkeyboard.ime.calendar.CalendarTextField.NOTES -> CaptureTarget.CALENDAR_NOTES
            com.wasimaster.wmkeyboard.ime.calendar.CalendarTextField.GUEST -> CaptureTarget.CALENDAR_GUEST
            else -> CaptureTarget.CALENDAR_TITLE
        }
        haTypingActive -> when (haText?.field) {
            com.wasimaster.wmkeyboard.ime.homeassistant.HaTextField.SEARCH -> CaptureTarget.HA_SEARCH
            com.wasimaster.wmkeyboard.ime.homeassistant.HaTextField.ASSIST -> CaptureTarget.HA_ASSIST
            else -> CaptureTarget.HA_TEXT
        }
        textRepeatTypingActive ->
            if (textRepeat.focused == TextRepeatField.SEPARATOR) {
                CaptureTarget.REPEAT_SEPARATOR
            } else {
                CaptureTarget.REPEAT_TEXT
            }
        sendTypingActive -> (sendText?.field ?: com.wasimaster.wmkeyboard.ime.send.SendTextField.LINK).captureTarget
        calcTypingActive -> CaptureTarget.CALC
        converterTypingActive -> CaptureTarget.CONVERTER
        wordSpellActive -> CaptureTarget.WORD_SPELL
        clipEditActive -> CaptureTarget.CLIP_EDIT
        clipCombineActive -> when (clipCombine?.focused) {
            ClipCombineField.BASE, ClipCombineField.TEMPLATE, ClipCombineField.RESULT -> CaptureTarget.CLIP_BASE
            ClipCombineField.APPEND -> CaptureTarget.CLIP_APPEND
            else -> CaptureTarget.CLIP_JOINER
        }
        kaomojiTypingActive -> when (kaomojiText?.field) {
            com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiTextField.FACE -> CaptureTarget.KAOMOJI_FACE
            com.wasimaster.wmkeyboard.ime.kaomoji.KaomojiTextField.TAG -> CaptureTarget.KAOMOJI_TAG
            else -> CaptureTarget.KAOMOJI_SEARCH
        }
        emojiSearchActive -> CaptureTarget.EMOJI_SEARCH
        panel == PanelMode.APP_LAUNCHER && launcherView.nameDraft != null -> CaptureTarget.LAUNCHER_FOLDER_NAME
        mediaSearchActive && panel.hasMediaSearch -> CaptureTarget.MEDIA_SEARCH
        dictionarySearchActive -> CaptureTarget.DICTIONARY_SEARCH
        clipboardSearchActive -> CaptureTarget.CLIPBOARD_SEARCH
        snippetSearchActive -> CaptureTarget.SNIPPET_SEARCH
        panel == PanelMode.TOOLBOX && toolboxView.nameDraft != null -> CaptureTarget.TOOLBOX_FOLDER_NAME
        panel == PanelMode.TOOLBOX && toolboxView.searchActive -> CaptureTarget.TOOLBOX_SEARCH
        else -> null
    }

    /**
     * A stable identity for the focused buffer, for [CaptureCaret] to hang on.
     * A plugin has one box per widget, so its id is part of the key; everything
     * else is one buffer per target.
     */
    fun captureKey(): String? = fieldKey(captureTarget())

    /** [captureKey] for [panelFieldTarget]: the tool's focused field, keys or not. */
    fun panelFieldKey(): String? = fieldKey(panelFieldTarget())

    private fun fieldKey(target: CaptureTarget?): String? = when (target) {
        null -> null
        CaptureTarget.PLUGIN -> "PLUGIN:${pluginFocusedInput}"
        // Three of Combine's boxes share the target; each keeps its own caret.
        CaptureTarget.CLIP_BASE -> "CLIP_BASE:${clipCombine?.focused}"
        else -> target.name
    }

    /**
     * The focus switch (#665) has the open tool's field typing into the app's
     * field instead. Null-checked first, so the ladder is only walked while
     * the switch has been used.
     */
    val captureHandedToApp: Boolean
        get() = captureToApp != null && captureToApp == panelFieldKey()

    /**
     * The open tool's field has had its key rows put down (#961): no rows
     * under the panel, which takes their height back. Only the layout asks;
     * the field is still the one a physical keyboard types into.
     */
    val captureKeysLowered: Boolean
        get() = captureLowered != null && captureLowered == panelFieldKey()

    /** What is in the focused keyboard-owned buffer right now. */
    fun captureBuffer(): String = when (captureTarget()) {
        null -> ""
        CaptureTarget.TYPING_TEST -> typingTest.current
        CaptureTarget.AI_CUSTOM -> (ai as? AiUi.CustomInput)?.instruction.orEmpty()
        CaptureTarget.AI_CHAT -> if (aiChat.renaming >= 0) aiChat.renameDraft else aiChat.draft
        CaptureTarget.AI_REPLY -> aiReply.typed
        CaptureTarget.KDE_HOST -> kde.hostDraft
        CaptureTarget.KDE_REMOTE, CaptureTarget.KDE_COMPOSE -> kde.line
        CaptureTarget.PLUGIN -> pluginFocusedInput?.let { pluginInputs[it] }.orEmpty()
        CaptureTarget.FIND_QUERY -> findReplace?.query.orEmpty()
        CaptureTarget.FIND_REPLACEMENT -> findReplace?.replacement.orEmpty()
        CaptureTarget.LEARN_EDIT -> learnFromText?.editText.orEmpty()
        CaptureTarget.CALC -> calcExpression
        CaptureTarget.CONVERTER -> converterValue
        CaptureTarget.WORD_SPELL -> wordSpell?.draft.orEmpty()
        CaptureTarget.EMOJI_SEARCH -> emojiQuery
        CaptureTarget.MEDIA_SEARCH -> mediaQuery
        CaptureTarget.DICTIONARY_SEARCH -> dictionaryQuery
        CaptureTarget.CLIPBOARD_SEARCH -> clipboardQuery
        CaptureTarget.CLIP_EDIT -> clipEdit?.draft.orEmpty()
        CaptureTarget.CLIP_JOINER -> clipCombine?.joiner.orEmpty()
        CaptureTarget.CLIP_BASE -> clipCombine?.focusedText.orEmpty()
        CaptureTarget.CLIP_APPEND -> clipCombine?.append.orEmpty()
        CaptureTarget.SNIPPET_SEARCH -> snippetQuery
        CaptureTarget.NOTE_SEARCH -> notes.query
        CaptureTarget.NOTE_TITLE -> notes.editing?.titleDraft.orEmpty()
        CaptureTarget.NOTE_BODY -> notes.editing?.bodyDraft.orEmpty()
        CaptureTarget.NOTE_TAG -> notes.editing?.tagDraft.orEmpty()
        CaptureTarget.CLOCK_SEARCH, CaptureTarget.CLOCK_LABEL -> clockText?.text.orEmpty()
        CaptureTarget.TODO_TEXT -> todoText?.text.orEmpty()
        CaptureTarget.HA_SEARCH, CaptureTarget.HA_ASSIST, CaptureTarget.HA_TEXT -> haText?.text.orEmpty()
        CaptureTarget.REPEAT_TEXT -> textRepeat.text
        CaptureTarget.REPEAT_SEPARATOR -> textRepeat.customSeparator
        CaptureTarget.GALLERY_TEXT -> galleryText?.text.orEmpty()
        CaptureTarget.CALENDAR_TITLE, CaptureTarget.CALENDAR_LOCATION,
        CaptureTarget.CALENDAR_NOTES, CaptureTarget.CALENDAR_GUEST,
        -> calendarText?.text.orEmpty()
        CaptureTarget.SEND_PASSWORD, CaptureTarget.SEND_LINK, CaptureTarget.SEND_INSTANCE -> sendText?.text.orEmpty()
        CaptureTarget.TOOLBOX_SEARCH -> toolboxView.query
        CaptureTarget.TOOLBOX_FOLDER_NAME -> toolboxView.nameDraft.orEmpty()
        CaptureTarget.LAUNCHER_FOLDER_NAME -> launcherView.nameDraft.orEmpty()
        CaptureTarget.KAOMOJI_SEARCH, CaptureTarget.KAOMOJI_FACE, CaptureTarget.KAOMOJI_TAG ->
            kaomojiText?.text.orEmpty()
    }

    /**
     * The focused buffer with its caret, or null when the keys belong to the
     * field. The caret is the end of the text whenever [captureCaret] is about
     * some other buffer, which is what makes focus changes need no reset.
     */
    fun captureCaretText(): CaretText? {
        val key = captureKey() ?: return null
        val text = captureBuffer()
        // The word card runs its own caret and selection (#204); reporting a
        // second one here would let two of them disagree about the draft.
        if (captureTarget()?.ownsCaret == true) return CaretText(text, wordSpell?.cursor ?: text.length)
        val caret = captureCaret?.takeIf { it.key == key && it.text == text }
            ?: return CaretText(text, text.length)
        return CaretText(text, caret.at, caret.anchor)
    }

    /** Where the caret is drawn in the focused buffer, for the panels' fields. */
    fun captureCaretIndex(): Int = captureCaretText()?.at ?: 0

    /** The other end of the focused buffer's selection; [captureCaretIndex] when none (#352). */
    fun captureAnchorIndex(): Int = captureCaretText()?.anchorAt ?: 0

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
    fun snippetsShown(): List<Snippet> {
        // A search looks through every snippet, whatever folder it is filed
        // in (#471): the point of typing a name is not having to remember
        // where it was put.
        val query = snippetQuery.trim()
        if (query.isNotEmpty()) {
            // Then what only search by meaning found, when it is on.
            return com.wasimaster.wmkeyboard.ime.semantic.MeaningHits.append(
                snippets.filter { it.matchesQuery(query) },
                snippets,
                meaning.snippets.forQuery(query),
            ) { it.id.toString() }
        }
        return when {
            snippetFolders.isEmpty() -> snippets
            openSnippetFolder() != null -> snippets.filter { it.folderId == snippetFolderOpen }
            else -> snippets.filter { it.folderId == 0L }
        }
    }
}

/** Whether [query] appears in this snippet's label, its text, or any of its triggers. */
private fun Snippet.matchesQuery(query: String): Boolean =
    label.contains(query, ignoreCase = true) ||
        text.contains(query, ignoreCase = true) ||
        trigger?.contains(query, ignoreCase = true) == true ||
        aliases.any { it.contains(query, ignoreCase = true) }

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
 *
 * Code points, not `Char`s. A key in Osage, Adlam or Warang Citi writes one
 * letter that is two UTF-16 units, and a lone surrogate is not a letter — so
 * indexed per `Char` every such key vanished from the glide grid and from
 * [LayoutSet.letterAlphabet], and typing worked while glide and the coverage
 * gate silently did not.
 */
fun keySpelling(label: String): List<Int>? {
    if (label.isEmpty()) return null
    val out = ArrayList<Int>(MAX_KEY_SPELLING)
    var at = 0
    while (at < label.length) {
        val codePoint = label.codePointAt(at)
        at += Character.charCount(codePoint)
        if (out.size >= MAX_KEY_SPELLING) return null
        val allowed = if (out.isEmpty()) isLetterKeyCodePoint(codePoint) else isCombiningMark(codePoint)
        if (!allowed) return null
        out.add(Character.toLowerCase(codePoint))
    }
    return out
}

/**
 * The code point a letter key is identified by on the glide and touch grids:
 * the character the renderer reports its measured centre under, and the
 * character every other spelling on that key borrows a centre from.
 *
 * An ambiguous key ([Key.letters]) is anchored by the first letter of its set
 * rather than by its label, and it has to be: a T9 key reads `ABC`, which is
 * not a spelling at all — [keySpelling] rejects it at the second character —
 * so anchoring by the label would drop the key off both grids and take glide,
 * the touch model and the coverage gate with it.
 *
 * The one derivation for both sides of the wire. The renderer measures under
 * this code point and [LayoutSet.glideKeys] looks up under it; when the two
 * disagreed about a key, that key silently vanished from the grid.
 */
fun Key.glideAnchor(): Int? {
    if (action != KeyAction.Text) return null
    letters?.takeIf { it.isNotEmpty() }?.let { return Character.toLowerCase(it.codePointAt(0)) }
    return keySpelling(label)?.first()
}

/**
 * What a key that types no letter carries in letters (#970): its shifted label,
 * its corner hint and its long presses, each one that [keySpelling] reads as a
 * letter. A number row's `8` with `i` behind its hold, an Arabic layout's
 * punctuation key with a letter on Shift. Empty for a letter key, whose own
 * passes in [LayoutSet.glideKeys] already cover every one of these, and for a
 * key that carries nothing a word could hold.
 *
 * The test the renderer puts a key through before measuring it for the glide
 * grid, so it has to be cheap on the common key: one anchor check, then three
 * short lists.
 */
fun Key.carriedGlideSpellings(): List<String> {
    if (action != KeyAction.Text || glideAnchor() != null) return emptyList()
    val out = ArrayList<String>(0)
    for (spelling in listOfNotNull(shiftLabel, hint) + longPress) {
        if (keySpelling(spelling) != null && spelling !in out) out.add(spelling)
    }
    return out
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
private fun isLetterKeyCodePoint(codePoint: Int): Boolean =
    Character.isLetter(codePoint) || isCombiningMark(codePoint)

private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
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
    // Two units that are one letter outside the BMP, not a base plus a mark.
    if (label.length < 2 || label[0].isSurrogate()) return null
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

/** The longest key [LayoutSet.digraphAnchors] reads as one letter: Klingon's `tlh`. */
private const val MAX_DIGRAPH_LETTERS = 3

/**
 * What the toolbox panel is showing beyond its grid, on [KeyboardUiState] for
 * the reason [KeyboardUiState.snippetFolderOpen] is: back has to close these
 * one at a time before it closes the panel, and the capture ladder has to know
 * when one of them has the keys.
 */
data class ToolboxUi(
    /** The folder opened in the grid (#709), by [com.wasimaster.wmkeyboard.core.settings.ToolboxFolder.id]. */
    val folderOpen: String? = null,
    /** Typing edits [query] and the grid lists the tools whose names match it (#687). */
    val searchActive: Boolean = false,
    val query: String = "",
    /** The open folder's name while it is being typed; null when it is not being renamed. */
    val nameDraft: String? = null,
)

/** Which keyboard-owned buffer a Send files text box is (#729). */
val com.wasimaster.wmkeyboard.ime.send.SendTextField.captureTarget: CaptureTarget
    get() = when (this) {
        com.wasimaster.wmkeyboard.ime.send.SendTextField.PASSWORD -> CaptureTarget.SEND_PASSWORD
        com.wasimaster.wmkeyboard.ime.send.SendTextField.LINK -> CaptureTarget.SEND_LINK
        com.wasimaster.wmkeyboard.ime.send.SendTextField.INSTANCE -> CaptureTarget.SEND_INSTANCE
    }

/** A task on the suggestion strip: its words, and which task the chip's buttons act on. */
data class TodoDueChip(val id: Long, val text: String)

/** What the due task chip was asked to do. */
enum class TodoChipAction { OPEN, DONE, SNOOZE, CLOSE }

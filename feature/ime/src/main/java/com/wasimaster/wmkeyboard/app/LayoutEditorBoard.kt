package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Immutable
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.leadsWithDigitRow
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.layout.shiftLabelReplacesIcon
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.EnterAction
import com.wasimaster.wmkeyboard.ime.FieldKind
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.authoredHolds
import com.wasimaster.wmkeyboard.ime.ui.ArrowGlyphs
import com.wasimaster.wmkeyboard.ime.ui.IconDefaults
import com.wasimaster.wmkeyboard.ime.ui.KeyIcons
import com.wasimaster.wmkeyboard.ime.ui.arrowKeySlot
import com.wasimaster.wmkeyboard.ime.ui.arrowRowShown
import com.wasimaster.wmkeyboard.ime.ui.currentLayout
import com.wasimaster.wmkeyboard.ime.ui.numberRowShown
import com.wasimaster.wmkeyboard.ime.ui.spacebarText

/**
 * The kind of text field the layout editor's preview draws the keyboard for
 * (issue #769). An email box and a browser's address bar change keys on the
 * letters layer (an @ on the comma, / leading its popup, web endings on the
 * full stop), and a layout author could only find that out by typing in one.
 */
enum class EditorPreviewField { TEXT, EMAIL, WEB_ADDRESS }

/**
 * One layer of a layout as the keyboard draws it, for the layout editor's
 * preview (issue #769).
 *
 * [rows] is the grid run through `currentLayout`, the very pass the keyboard
 * draws every grid through: the Bottom row settings, the field's adaptations,
 * the hold shortcuts the settings add to a/c/v/x/z/y, accents merged in,
 * digits taken off the letters while the number row is up. So the corner hint
 * on a preview key and the popup the editor lists for it are the board's own,
 * not a guess at them. Keys keep their seats: every pass but one rewrites a key
 * where it stands, and the one that adds a key (the symbols pages' 1234 key)
 * makes a row longer, which the caller can see and fall back on.
 */
@Immutable
class EditorBoardView(
    val rows: List<List<Key>>,
    /** The number row is drawn above this layer. */
    val numberRowShown: Boolean,
    /** The symbols layer's first row gives way to its fill row. */
    val fillRowShown: Boolean,
    /** The arrow keys row is drawn under this layer. */
    val arrowRowShown: Boolean,
    /** The icon slot the enter key draws in this field. */
    val enterSlot: String,
    /** What the spacebar says. */
    val spaceText: String,
)

/**
 * [grid], the editor's compiled copy of [layer] of [spec], as the keyboard
 * would draw it in a [field] with [settings] in force. The numeric pads come
 * back as they are: a number field draws them as laid out.
 */
fun editorBoardView(
    settings: KeyboardSettings,
    spec: LayoutSpec,
    layer: LayoutLayer,
    grid: KeyboardLayout,
    field: EditorPreviewField,
): EditorBoardView {
    val mode = when {
        spec.secondary -> LayoutMode.SECONDARY
        layer == LayoutLayer.LETTERS -> LayoutMode.LETTERS
        layer == LayoutLayer.SYMBOLS -> LayoutMode.SYMBOLS
        layer == LayoutLayer.SYMBOLS_SHIFTED -> LayoutMode.SYMBOLS_SHIFTED
        layer == LayoutLayer.FN -> LayoutMode.FN
        else -> null
    }
    val enter = if (field == EditorPreviewField.WEB_ADDRESS) EnterAction.GO else EnterAction.DEFAULT
    val enterSlot = IconDefaults.enterActionSlot(enter) ?: IconSlots.KEY_ENTER
    val script = spec.script()
    val letters = if (layer == LayoutLayer.LETTERS) grid else spec.compile(LayoutLayer.LETTERS)
    val layouts = LayoutSet(
        letters = letters,
        symbols = if (layer == LayoutLayer.SYMBOLS) grid else spec.compile(LayoutLayer.SYMBOLS),
        symbolsShifted = if (layer == LayoutLayer.SYMBOLS_SHIFTED) grid else spec.compile(LayoutLayer.SYMBOLS_SHIFTED),
        fn = grid.takeIf { layer == LayoutLayer.FN },
        secondaries = if (spec.secondary) mapOf(spec.id to grid) else emptyMap(),
        authoredHolds = authoredHolds(spec, letters),
    )
    val state = KeyboardUiState(
        settings = settings,
        language = spec.language(),
        script = script,
        composer = composerFor(script, spec.composerType(), spec.langId),
        layoutId = spec.id,
        layoutName = spec.name,
        layouts = layouts,
        layoutMode = mode ?: LayoutMode.LETTERS,
        secondaryLayoutId = spec.id.takeIf { spec.secondary },
        fieldKind = when (field) {
            EditorPreviewField.TEXT -> FieldKind.TEXT
            EditorPreviewField.EMAIL -> FieldKind.EMAIL
            EditorPreviewField.WEB_ADDRESS -> FieldKind.URI
        },
        enterAction = enter,
    )
    val spaceText = spacebarText(state)
    if (mode == null) return EditorBoardView(grid.rows, false, false, false, enterSlot, spaceText)
    val drawn = currentLayout(state).rows
    return EditorBoardView(
        rows = drawn,
        numberRowShown = numberRowShown(state),
        fillRowShown = settings.numberRow && mode == LayoutMode.SYMBOLS && leadsWithDigitRow(drawn),
        arrowRowShown = arrowRowShown(state),
        enterSlot = enterSlot,
        spaceText = spaceText,
    )
}

/**
 * The icon slot [key] draws its face from on the keyboard, or null for a key
 * that draws its label, a named icon or an icon of its own. The same choices
 * `KeyContent` makes, so the editor can draw the face through the slot and an
 * icon pack redresses the preview with the board.
 */
fun editorKeySlot(key: Key, enterSlot: String): String? {
    if (KeyIcons.byName(key.icon) != null || KeyIcons.libraryIdOf(key.icon) != null) return null
    return when (val action = key.action) {
        KeyAction.Shift -> IconSlots.KEY_SHIFT.takeUnless { shiftLabelReplacesIcon(key.label) }
        KeyAction.CapsLock -> IconSlots.KEY_CAPS_LOCK.takeUnless { shiftLabelReplacesIcon(key.label) }
        KeyAction.Delete -> IconSlots.KEY_BACKSPACE
        KeyAction.ForwardDelete -> IconSlots.KEY_FORWARD_DELETE
        KeyAction.Enter -> enterSlot
        KeyAction.Newline -> IconSlots.KEY_ENTER
        KeyAction.LanguageSwitch -> IconSlots.KEY_GLOBE
        KeyAction.InputMethodPicker, is KeyAction.SwitchInputMethod -> IconSlots.KEY_INPUT_METHOD_PICKER
        KeyAction.Emoji -> IconSlots.KEY_EMOJI
        KeyAction.Toolbox -> IconSlots.CHROME_TOOLBOX.takeIf { key.label.isBlank() }
        is KeyAction.Tool -> IconSlots.forTool(action.tool).takeIf { key.label.isBlank() }
        is KeyAction.SendKey -> arrowKeySlot(action.keyCode)
            ?.takeIf { key.label.isBlank() || key.label in ArrowGlyphs }
        else -> null
    }
}

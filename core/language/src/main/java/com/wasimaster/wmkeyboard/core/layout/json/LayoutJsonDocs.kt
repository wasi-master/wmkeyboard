package com.wasimaster.wmkeyboard.core.layout.json

/**
 * One short paragraph for each property and each action a layout document can
 * hold, shown in the JSON editor's doc strip for the name under the caret.
 *
 * Kept in Kotlin rather than in string resources, for the reason `LuaApiDocs`
 * gives: this documents a file format whose property names are never translated,
 * and a hundred paragraphs in the translation pipeline would cost more than they
 * give. Filed by [PropertyShape.docKey]; an action is `action:<tag>` and its
 * fields `action:<tag>.<field>`. `LayoutJsonSchemaDriftTest` checks that every
 * property and every action has a paragraph, and that nothing here names one the
 * model lacks.
 */
object LayoutJsonDocs {

    fun of(docKey: String): String? = docs[docKey]

    val keys: Set<String> get() = docs.keys

    /** The discriminator of an action object. */
    const val ACTION_TYPE = "action.type"

    private val docs: Map<String, String> = mapOf(
        "LayerSpec.keymanFrames" to "A converted Keyman layer's space, backspace and enter: the modifiers they press with and the layer they switch to.",
        "KeymanTarget.vkey" to "The Keyman virtual key behind a long press or flick, or 0 for a named key.",
        "KeymanTarget.modifiers" to "The Keyman modifier mask that the rules match as held.",
        "KeymanTarget.nextLayer" to "The layer to show after it, unless a rule picks one.",
        "KeymanTarget.id" to "A T_ or U_ key's own name, looked up in the Keyman keyboard's key list.",
        "KeymanTarget.text" to "What it types when no rules are loaded.",
        "LayoutFile.format" to "Always \"wmkeyboard-layout\". It marks the file as a layout, so an import can tell it from any other JSON.",
        "LayoutFile.version" to "The revision of the file format. Leave it as the app wrote it.",
        "LayoutFile.appVersion" to "The version code of the app that exported the file. Only for information.",
        "LayoutFile.appVersionName" to "The version name of the app that exported the file. Only for information.",
        "LayoutFile.layout" to "The layout itself. Apply reads this and ignores the rest of the file.",

        "LayoutSpec.id" to
            "The layout's identity. Apply keeps the id of the layout you are editing, whatever is written here, so a pasted id cannot " +
            "overwrite another layout.",
        "LayoutSpec.name" to "The name the layout has in the list and in the language switcher.",
        "LayoutSpec.langId" to
            "The language this grid types, such as \"en\" or \"bn\". It picks the dictionary, autocorrect, the script rules, voice " +
            "typing and how Shift behaves.",
        "LayoutSpec.composer" to
            "How keystrokes compose, in place of the language's default. Leave it out unless one language has two methods, like Avro " +
            "and Probhat for Bangla.",
        "LayoutSpec.baseMode" to
            "Written by old versions before languages existed. The app turns it into langId when it reads the layout. Do not write it.",
        "LayoutSpec.layers" to
            "The grids this layout draws, by layer name. A layer you leave out uses the built-in grid, so a layout that only changes " +
            "the letters still has symbols and numbers.",
        "LayoutSpec.proximityRows" to
            "One string of characters per physical row, for typo correction on a staggered or split grid. Leave it out and the rows " +
            "come from the letters layer.",
        "LayoutSpec.tabletExpand" to
            "Whether a big screen may widen this grid with Tab, caps lock, arrows and the rest. Turn it off for a grid you already laid " +
            "out wide by hand.",
        "LayoutSpec.keyman" to "The Keyman keyboard whose rules decide what the keys type. Only a converted Keyman layout has one.",
        "LayoutSpec.appearance" to "This layout's own label font and size, over the theme and the settings.",
        "LayoutSpec.secondary" to
            "A grid that a key or the Secondary layout tool opens, not a language: a symbols page of your own, or a macro pad. Only its " +
            "letters layer is used.",
        "LayoutSpec.themeId" to "A theme of this layout's own, used while the layout is on screen. A layer's themeId beats it.",
        "LayoutSpec.version" to "The revision of the layout format. The app brings an older one up to date when it reads it.",

        "LayerSpec.rows" to "The rows of keys, top to bottom. Each row is a list of keys, left to right.",
        "LayerSpec.numberRow" to
            "The number row above this grid, when that setting is on. Leave it out for the digits the layer always shows.",
        "LayerSpec.fillRow" to
            "On the symbols layer, the row shown in place of the digit row while the number row is on. Leave it out for = \\ < > [ ] { } | ~.",
        "LayerSpec.rowHeights" to "A height for each row, in the order of rows. 1 is the standard height, and a row with no entry is 1.",
        "LayerSpec.fontScale" to
            "This layer's own label size, in place of the layout's. Useful for a symbols page that stays small while the letters grow.",
        "LayerSpec.persistent" to
            "Keeps this layer on screen when the keyboard closes and opens again, instead of going back to the letters.",
        "LayerSpec.bottomRowAsLaidOut" to
            "Draws the bottom row exactly as it is here. The Bottom row settings do not hide, move, swap or change its keys.",
        "LayerSpec.themeId" to "A theme for while this layer is on screen, over the layout's theme and the settings.",

        "LayoutAppearance.fontId" to
            "The label font: \"default\", \"google:<Name>\", \"installed:<name>\" or \"custom\". A font this device does not have falls " +
            "back to the usual one.",
        "LayoutAppearance.fontScale" to "Multiplies the label size the settings chose. 0.8 draws the labels a fifth smaller.",

        "Key.label" to "What the key shows, and what it types unless output says something else.",
        "Key.output" to "What the key types, when that is not the label.",
        "Key.shiftLabel" to "What the key shows and types while Shift is on. Leave it out and Shift makes the label a capital letter.",
        "Key.action" to "What a tap does. Leave it out for a key that types its text.",
        "Key.width" to "How wide the key is, in standard keys. A space bar is often 4 and Shift 1.5.",
        "Key.rowSpan" to "How many rows the key covers, its own included. 2 makes a tall key, and the row below fits around it.",
        "Key.longPress" to
            "The characters a press and hold offers, in order. The first one is also the small hint in the corner of the key.",
        "Key.actionAlternates" to
            "Press and hold entries that run an action instead of typing, such as Tab or a tool. They come after the longPress " +
            "characters.",
        "Key.actionAlternatesFirst" to
            "The keyboard sets this from the settings. Do not write it. It puts the action entries before the characters.",
        "Key.alternateColumns" to "How many columns this key's hold popup uses. 0 follows the setting.",
        "Key.clipboardAction" to "A clipboard shortcut that a press and hold runs instead of opening the popup.",
        "Key.role" to "Which punctuation slot this key fills, so an email or web address field can change it to @ or /.",
        "Key.icon" to "The name of an icon to draw in place of the label. The label still names the key for a screen reader.",
        "Key.iconHint" to "The name of an icon to draw as the corner hint, in place of the first alternate.",
        "Key.iconBesideLabel" to "On the space bar, draws the icon before the language name instead of in its place.",
        "Key.hideHint" to "Draws no corner hint on this key, even when it has alternates.",
        "Key.forceHint" to "Draws this key's corner hint even when hints are off in the settings. hideHint wins when both are on.",
        "Key.flick" to "What a flick in each direction types, for a 12-key kana pad.",
        "Key.multitap" to "What tapping the key again types in place of the last tap, in order. After the last entry the cycle goes back to the key itself.",
        "Key.labelScale" to "This key's label size, as a multiple of a letter's. Leave it out and the keyboard decides.",
        "Key.letters" to "Every letter this key stands for, such as \"abc\" on a T9 key. The prediction works out which one you meant.",
        "Key.repeatOnHold" to
            "Holding the key does its action over and over, the way holding delete does. It spends the press and hold, so " +
            "the key's alternates stop opening.",
        "Key.kanaVariantWhileComposing" to
            "While the kana you are typing has a small, ゛ or ゜ form, the key shows 小゛゜ and a tap changes that kana. " +
            "The rest of the time the key does its own action.",

        "KeyAlternate.action" to "What choosing this entry does.",
        "KeyAlternate.label" to "What the popup draws for this entry. Blank draws the action's own glyph or icon.",
        "KeyAlternate.icon" to "The name of an icon to draw in place of the label.",

        "KeymanBinding.keyboardId" to "The id of the Keyman keyboard.",
        "KeymanBinding.version" to "The version of the Keyman keyboard the layout came from.",

        "PanelLayoutSpec.panel" to "Which panel this layout is for. Apply keeps the panel you are editing.",
        "PanelLayoutSpec.grid" to "The panel's grid: rows of keys and of component cells.",
        "PanelLayoutSpec.appearance" to "This panel's own label font and size.",
        "PanelLayoutSpec.version" to "The revision of the panel format.",

        ACTION_TYPE to "Which action the key runs. The other fields of this object depend on it.",
        "action:text" to "Types the key's output, or its label. It is the default, so it is usually left out.",
        "action:shift" to "Shift. One tap gives one capital letter, a double tap gives caps lock.",
        "action:caps_lock" to "Turns caps lock on or off in one tap, with no one-letter step.",
        "action:delete" to "Deletes the character before the caret. It repeats while held, and a swipe deletes words.",
        "action:forward_delete" to "Deletes the character after the caret. It repeats while held.",
        "action:space" to "Types a space. Holding it opens the language picker, unless the key has alternates.",
        "action:enter" to "Enter, or the field's own action, such as Send or Search.",
        "action:newline" to "Types a line break, and never the field's Send or Search action.",
        "action:symbols" to "Goes from the letters to the symbols, and between the two symbol pages.",
        "action:letters" to "Goes straight back to the letters.",
        "action:language_switch" to "Goes to the next layout that is on.",
        "action:input_method_picker" to "Opens the system list of keyboards, to change to another keyboard app.",
        "action:switch_input_method" to "Switches to another installed input method or subtype.",
        "action:switch_input_method.id" to "The input method ID or subtype to switch to.",
        "action:editor_action" to "Triggers the current text field's action button, such as Go, Search or Send.",
        "action:emoji" to "Opens the emoji panel.",
        "action:numpad" to "Opens the number pad. The keyboard makes this key by itself, and a layout does not use it.",
        "action:tool" to "Opens one of the keyboard's tools, the same as its toolbar button.",
        "action:tool.tool" to "Which tool the key opens.",
        "action:layout" to "Shows one of your secondary layouts in place of the letters. A second press goes back.",
        "action:layout.id" to "The id of the secondary layout.",
        "action:mod" to "Ctrl, Alt or Meta for the next key. Tap to arm it, and tap again to lock it.",
        "action:mod.key" to "Which modifier.",
        "action:send_key" to "Sends a key press to the app, such as Tab, Escape or an arrow.",
        "action:send_key.keyCode" to "The Android key code, such as 61 for Tab or 111 for Escape.",
        "action:send_key.meta" to "The modifiers held with the key, as Android meta flags. 4096 is Ctrl, 2 is Alt and 1 is Shift.",
        "action:fn" to "Shows the Fn layer for one key, then goes back. A quick second tap keeps it.",
        "action:kana_variant" to "Changes the last kana to its dakuten, handakuten and small forms, one after another.",
        "action:broadcast" to "Sends an Android broadcast, for an automation app like Tasker. It types nothing.",
        "action:broadcast.action" to "The intent action of the broadcast. An app must listen for this exact text.",
        "action:braille_dot" to
            "One dot key of the six-key braille layout. It fires when pressed, and the cell types when the last dot lifts.",
        "action:braille_dot.dot" to "The braille dot number, from 1 to 6.",
        "action:braille_dot.release" to "The keyboard sets this while the key is held. A layout never writes it.",
        "action:morse_dot" to "The dot key of the morse layout.",
        "action:morse_dash" to "The dash key of the morse layout.",
        "action:keyman_key" to "A key of a converted Keyman layout. The Keyman rules decide what it types.",
        "action:keyman_key.vkey" to "The Keyman virtual key.",
        "action:keyman_key.modifiers" to "The Keyman modifier mask that the rules match as held.",
        "action:keyman_key.nextLayer" to "The layer to show after the key, unless a rule picks one.",
        "action:keyman_key.id" to "A T_ or U_ key's own name, looked up in the Keyman keyboard's key list.",
        "action:keyman_key.text" to "What a long-press or flick key types when no rules are loaded.",
        "action:keyman_key.longPress" to "The Keyman keys behind the key's long-press entries, in the same order.",
        "action:keyman_key.flick" to "The Keyman keys behind the key's flicks, by direction.",
        "action:keyman_key.multitap" to "The Keyman keys that later taps of the key press, in the same order as the key's multitap list.",
        "action:none" to "A gap in the grid. It draws as empty space and does nothing.",
        "action:field" to "A panel cell that holds a live component, such as the emoji grid. Only a panel layout can hold one.",
        "action:field.kind" to "Which component the cell holds.",
        "action:edit" to "One text editing operation: an arrow, a selection, copy or paste.",
        "action:edit.op" to "Which operation.",
        "action:unknown" to "An action from a newer version of the app. Apply deletes the key.",
        "action:unknown.tag" to "The action type that this version does not know.",
    )
}

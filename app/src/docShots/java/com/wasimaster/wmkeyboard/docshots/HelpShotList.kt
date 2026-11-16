package com.wasimaster.wmkeyboard.docshots

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.BottomRowPreset
import com.wasimaster.wmkeyboard.core.settings.ClipCardShortcut
import com.wasimaster.wmkeyboard.core.settings.ClipTimeLabel
import com.wasimaster.wmkeyboard.core.settings.ColorVisionFilter
import com.wasimaster.wmkeyboard.core.settings.FloatingHandlePosition
import com.wasimaster.wmkeyboard.core.settings.HintCorner
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.settings.MediaSwitcher
import com.wasimaster.wmkeyboard.core.settings.OctopusPlacement
import com.wasimaster.wmkeyboard.core.settings.PrivacyShadeStyle
import com.wasimaster.wmkeyboard.core.settings.RecentWordPlacement
import com.wasimaster.wmkeyboard.core.settings.RetypeButtonPlace
import com.wasimaster.wmkeyboard.core.settings.SelectionMacroPlacement
import com.wasimaster.wmkeyboard.core.settings.TopRowHolds
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.ui.LongWordFit
import com.wasimaster.wmkeyboard.core.settings.LauncherIconShape
import com.wasimaster.wmkeyboard.core.theme.BoardEffectSpec
import com.wasimaster.wmkeyboard.core.theme.KeyLightingSpec
import com.wasimaster.wmkeyboard.core.theme.KeyMaterialSpec
import com.wasimaster.wmkeyboard.core.theme.KeyShapeKind
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.TouchBloomSpec
import com.wasimaster.wmkeyboard.core.tools.CurrencyLabel
import com.wasimaster.wmkeyboard.core.tools.TextRepeat
import com.wasimaster.wmkeyboard.core.settings.ClipboardView
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.KeyLabelCase
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.SpacebarDisplay
import com.wasimaster.wmkeyboard.core.settings.ToolboxLayout
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.PanelMode

/*
 * Every help picture, by the resource name of the setting's title. One entry
 * per setting: adding a line here and running HelpShots, then
 * tools/helpvisuals/build.py, is all it takes for a "?" to gain a picture.
 *
 * What to show. A switch that changes how the keyboard looks is a [toggle],
 * with no steps at all. A switch that changes what typing does needs steps
 * that make the difference visible, and [HelpCrop.TEXT] when the difference
 * lands in the text. Something that moves is a [clip]. A setting with several
 * named options is a [gallery]. A setting whose two frames come out identical
 * is reported by build.py and gets no picture, so a candidate that turns out
 * to show nothing costs a render and nothing else.
 *
 * Tried, and nothing to see on the JVM, so not listed: the field the harness
 * types into asks for no capitals, which leaves auto-capitalise and the
 * number row's shifted symbols with nothing to change; flick hints and the
 * kaomoji tabs need a layout or a tab that is off screen; the rest drew the
 * same frame twice (extended edge keys, the board's top corners, the strip
 * under the toolbar, typed word first, the more arrow, next-word suggestions,
 * the sum chip, a space after punctuation, the composing underline, clipboard
 * grid rows, the clear-recents button, the numpad's calculator order, the
 * mark under a corrected word, balanced and centred alternates). Those want a
 * recording from a phone: build.py --take.
 */

/** Two layouts turned on, so the keys that switch language have something to switch to. */
private val twoLanguages: suspend Seed.() -> Unit = {
    repo.setEnabledLayoutIds(listOf("builtin_qwerty", "builtin_avro"))
}

private val withNumberRow: suspend Seed.() -> Unit = { repo.setNumberRow(true) }

/** A clipboard with a few clips in it, open. */
private val clips = typed {
    copy("Pick up milk and eggs on the way home")
    copy("https://wmkeyboard.app/tools/clipboard/")
    copy("Meeting moved to 3 pm, room 204")
    panel(PanelMode.CLIPBOARD)
}

private val toolbox = typed { panel(PanelMode.TOOLBOX) }
private val emoji = typed { panel(PanelMode.EMOJI) }
private val holdE = typed { hold("e") }

/** A word half typed, so the strip has suggestions on it. */
private val strip = typed { type("hel") }
private val holdA = typed { hold("a") }
private val pressG = typed { press("g") }

/** A clipboard with a code, a link and a sentence in it, open. */
private val clipsWithParts = typed {
    copy("Pick up milk and eggs on the way home")
    copy("Your code is 483920")
    copy("Call me at +1 555 0100 or see https://wmkeyboard.app")
    panel(PanelMode.CLIPBOARD)
}

/** Three copies made, none pasted: the strip offers them. */
private val copiedThree = typed {
    copy("Pick up milk and eggs on the way home")
    copy("https://wmkeyboard.app/tools/clipboard/")
    copy("Meeting moved to 3 pm, room 204 on the second floor")
}

/** "hello world" typed and "world" selected. */
private val selected = typed {
    type("hello world")
    select("world")
}

private val octopusOn: suspend Seed.() -> Unit = { repo.setOctopusEnabled(true) }
private val autopilotOn: suspend Seed.() -> Unit = { repo.setSmartHitDetection(true) }
private val floatingOn: suspend Seed.() -> Unit = { repo.setFloatingKeyboard(false, true) }
private val narrowBoard: suspend Seed.() -> Unit = { repo.setKeyboardWidthPercent(75) }

/** A plain dark theme for the theme editor's pictures: each option is this with one thing changed. */
private val helpTheme = ThemeSpec(id = "help-look", name = "Help")

/** Keys showing [theme], stored as a custom theme and put on. */
private fun wearing(theme: ThemeSpec, base: KbShot = KbShot("")) = base.seededWith {
    repo.upsertCustomTheme(theme)
    repo.setKeyboardThemeId(theme.id)
}

private fun material(kind: String, label: String? = null) =
    wearing(helpTheme.copy(keyMaterial = KeyMaterialSpec(kind = kind).let { if (label == null) it else it.copy(label = label) }))

private fun lighting(pattern: String, target: String = "GLOW") =
    wearing(helpTheme.copy(keyLighting = KeyLightingSpec(pattern = pattern, target = target)))

val HELP_SHOTS: List<HelpShot> = listOf(
    // ------------------------------------------------------------ rows
    toggle("layout_number_row_title") { setNumberRow(it) },
    toggle("layout_number_row_hints_title", KbShot("", seed = withNumberRow)) { setNumberRowHints(it) },
    toggle("layout_arrow_row_title") { setArrowRow(it) },
    toggle("layout_arrow_row_on_top_title", KbShot("", seed = { repo.setArrowRow(true) }), crop = HelpCrop.KEYBOARD) { setArrowRowOnTop(it) },
    toggle("rows_symbol_row_title") { setSymbolRowEnabled(it) },
    compare(
        "rows_symbol_row_lines_title", KbShot("", seed = { repo.setSymbolRowEnabled(true) }),
        off = { repo.setSymbolRowLines(1) }, on = { repo.setSymbolRowLines(2) }, labels = "=1" to "=2",
    ),
    toggle("typing_recent_word_bar_title", typed { type("see you at six ") }) { setRecentWordBar(it) },
    toggle("rows_dictionary_bar_title", typed { type("serendipity") }) { setDictionaryBarEnabled(it) },
    gallery(
        "langemoji_emoji_row_title",
        "common_off" to KbShot("", seed = { repo.setEmojiBarMode(EmojiBarMode.OFF) }),
        "=Button" to KbShot("", seed = { repo.setEmojiBarMode(EmojiBarMode.BUTTON) }),
        "=Always" to KbShot("", seed = { repo.setEmojiBarMode(EmojiBarMode.ALWAYS) }),
    ),
    compare(
        "langemoji_emoji_bar_count_title", KbShot("", seed = { repo.setEmojiBarMode(EmojiBarMode.ALWAYS) }),
        off = { repo.setEmojiBarCount(6) }, on = { repo.setEmojiBarCount(10) }, labels = "=6" to "=10",
    ),

    // ------------------------------------------------------------ size and shape
    compare("layout_key_height_title", off = { repo.setKeyHeightDp(40) }, on = { repo.setKeyHeightDp(64) }, crop = HelpCrop.KEYBOARD, labels = "=40 dp" to "=64 dp"),
    compare("layout_bottom_row_height_title", off = { repo.setBottomRowHeightDp(40) }, on = { repo.setBottomRowHeightDp(64) }, crop = HelpCrop.KEYBOARD, labels = "=40 dp" to "=64 dp"),
    compare("layout_number_row_height_title", KbShot("", seed = withNumberRow), off = { repo.setNumberRowHeightDp(32) }, on = { repo.setNumberRowHeightDp(56) }, crop = HelpCrop.KEYBOARD, labels = "=32 dp" to "=56 dp"),
    compare("layout_toolbar_height_title", off = { repo.setToolbarHeightDp(36) }, on = { repo.setToolbarHeightDp(56) }, crop = HelpCrop.KEYBOARD, labels = "=36 dp" to "=56 dp"),
    compare("layout_key_spacing_title", off = { repo.setKeyGapScale(0.5f) }, on = { repo.setKeyGapScale(2f) }, crop = HelpCrop.KEYBOARD, labels = "=Tight" to "=Wide"),
    compare("layout_side_padding_left_title", off = { repo.setSidePadLeftScale(0f) }, on = { repo.setSidePadLeftScale(3f) }, crop = HelpCrop.KEYBOARD, labels = "=None" to "=Most"),
    compare("layout_side_padding_right_title", off = { repo.setSidePadRightScale(0f) }, on = { repo.setSidePadRightScale(3f) }, crop = HelpCrop.KEYBOARD, labels = "=None" to "=Most"),
    compare("layout_bottom_padding_title", off = { repo.setBottomPaddingDp(0) }, on = { repo.setBottomPaddingDp(32) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=32 dp"),
    compare("layout_keyboard_width_title", KbShot("", mode = "blank"), off = { repo.setKeyboardWidthPercent(100) }, on = { repo.setKeyboardWidthPercent(75) }, crop = HelpCrop.KEYBOARD, labels = "=100%" to "=75%"),
    toggle("layout_split_title", KbShot("", mode = "blank"), crop = HelpCrop.KEYBOARD) { setSplitKeyboard(it) },
    compare("layout_split_gap_title", KbShot("", mode = "blank", seed = { repo.setSplitKeyboard(true) }), off = { repo.setSplitGapPercent(10) }, on = { repo.setSplitGapPercent(40) }, crop = HelpCrop.KEYBOARD, labels = "=10%" to "=40%"),
    toggle("layout_split_spacebar_title", KbShot("", mode = "blank", seed = { repo.setSplitKeyboard(true) }), crop = HelpCrop.KEYBOARD) { setSplitSpacebar(it) },
    toggle("layout_floating_title", KbShot("", mode = "blank"), crop = HelpCrop.SCREEN) { setFloatingKeyboard(false, it) },
    gallery(
        "layout_one_handed_title",
        "common_off" to KbShot("", mode = "blank", seed = { repo.setOneHandedMode(OneHandedMode.OFF) }),
        "=Left" to KbShot("", mode = "blank", seed = { repo.setOneHandedMode(OneHandedMode.LEFT) }),
        "=Right" to KbShot("", mode = "blank", seed = { repo.setOneHandedMode(OneHandedMode.RIGHT) }),
    ),

    // ------------------------------------------------------------ key faces
    toggle("accessibility_high_contrast_title", crop = HelpCrop.KEYBOARD) { setHighContrastKeys(it) },
    toggle("accessibility_key_outlines_title", crop = HelpCrop.KEYBOARD) { setKeyOutlines(it) },
    toggle("accessibility_bold_labels_title", crop = HelpCrop.KEYBOARD) { setBoldKeyLabels(it) },
    compare("appearance_key_corner_radius_title", off = { repo.setKeyCornerRadiusDp(0) }, on = { repo.setKeyCornerRadiusDp(20) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=20 dp"),
    compare("appearance_key_label_size_title", off = { repo.setFontScale(0.8f) }, on = { repo.setFontScale(1.4f) }, crop = HelpCrop.KEYBOARD, labels = "=Small" to "=Large"),
    compare("appearance_key_hint_size_title", off = { repo.setHintFontScale(0.7f) }, on = { repo.setHintFontScale(1.5f) }, crop = HelpCrop.KEYBOARD, labels = "=Small" to "=Large"),
    gallery(
        "appearance_key_label_case_title",
        "appearance_key_label_case_follow" to KbShot("", seed = { repo.setKeyLabelCase(KeyLabelCase.FOLLOW_SHIFT) }),
        "appearance_key_label_case_lower" to KbShot("", seed = { repo.setKeyLabelCase(KeyLabelCase.LOWER) }),
        "appearance_key_label_case_upper" to KbShot("", seed = { repo.setKeyLabelCase(KeyLabelCase.UPPER) }),
    ),
    toggle("keypress_long_press_hints_title", crop = HelpCrop.KEYBOARD) { setLongPressHints(it) },

    // ------------------------------------------------------------ bottom row
    toggle("layout_comma_emoji_title") { setCommaAsEmoji(it) },
    toggle("layout_show_globe_title", KbShot("", seed = twoLanguages)) { setShowGlobeKey(it) },
    toggle("layout_globe_emoji_title", KbShot("", seed = twoLanguages)) { setGlobeAsEmoji(it) },
    toggle("layout_swap_comma_globe_title", KbShot("", seed = twoLanguages)) { setSwapCommaAndGlobe(it) },
    toggle("typing_spacebar_language_arrows_title", KbShot("", seed = twoLanguages)) { setSpacebarLanguageArrows(it) },
    gallery(
        "typing_spacebar_display_title",
        "typing_spacebar_display_language_label" to KbShot("", seed = { twoLanguages(); repo.setSpacebarDisplay(SpacebarDisplay.LANGUAGE) }),
        "typing_spacebar_display_layout_label" to KbShot("", seed = { twoLanguages(); repo.setSpacebarDisplay(SpacebarDisplay.LAYOUT) }),
        "typing_spacebar_display_both_label" to KbShot("", seed = { twoLanguages(); repo.setSpacebarDisplay(SpacebarDisplay.BOTH) }),
        "typing_spacebar_display_none_label" to KbShot("", seed = { twoLanguages(); repo.setSpacebarDisplay(SpacebarDisplay.NONE) }),
        crop = HelpCrop.DIFF,
    ),
    toggle("layout_symbols_numpad_key_title", typed { key(Key(label = "?123", action = KeyAction.Symbols)); settle() }) { setSymbolsNumpadKey(it) },
    toggle("layout_symbols_text_edit_key_title", typed { key(Key(label = "?123", action = KeyAction.Symbols)); settle() }) { setSymbolsTextEditKey(it) },
    toggle("layout_number_row_in_symbols_title", typed { key(Key(label = "?123", action = KeyAction.Symbols)); settle() }.copy(seed = withNumberRow)) { setNumberRowInSymbols(it) },

    // ------------------------------------------------------------ press and hold
    toggle("keypress_popup_title", typed { press("g") }, aboveDp = 72, crop = HelpCrop.KEYBOARD) { setKeyPopup(it) },
    toggle("keypress_popup_on_key_title", typed { press("g") }, aboveDp = 72, crop = HelpCrop.KEYBOARD) { setKeyPopupOnKey(it) },
    toggle("keypress_all_accents_title", holdE, aboveDp = 96, crop = HelpCrop.KEYBOARD) { setShowAllPopupKeys(it) },
    toggle("keypress_no_accents_title", holdE, aboveDp = 96, crop = HelpCrop.KEYBOARD) { setNoLetterAccents(it) },
    toggle("keypress_shifted_popup_title", holdE, aboveDp = 96, crop = HelpCrop.KEYBOARD) { setShiftedPopupKeys(it) },
    compare("keypress_alternates_columns_title", typed { hold("a") }, off = { repo.setAlternatesColumns(3) }, on = { repo.setAlternatesColumns(6) }, crop = HelpCrop.KEYBOARD, labels = "=3" to "=6", aboveDp = 140),
    compare("keypress_alternates_size_title", holdE, off = { repo.setAlternatesFontScale(0.8f) }, on = { repo.setAlternatesFontScale(1.4f) }, crop = HelpCrop.KEYBOARD, labels = "=Small" to "=Large", aboveDp = 120),
    compare("keypress_popup_font_size_title", typed { press("g") }, off = { repo.setPopupFontScale(0.8f) }, on = { repo.setPopupFontScale(1.5f) }, crop = HelpCrop.KEYBOARD, labels = "=Small" to "=Large", aboveDp = 96),
    toggle("accessibility_classic_popup_title", holdE, aboveDp = 120, crop = HelpCrop.KEYBOARD) { setAlternatesClassic(it) },

    // ------------------------------------------------------------ toolbar and toolbox
    toggle("appearance_toolbar_show_title") { setToolbarEnabled(it) },
    toggle("appearance_toolbar_labels_title") { setToolbarLabels(it) },
    toggle("appearance_second_toolbar_title") { setSecondToolbar(it) },
    toggle("appearance_strip_buttons_show_title", typed { type("hel") }) { setEmojiToolbar(it) },
    toggle("appearance_toolbar_pinned_colors_title") { setToolbarPinnedToolColors(it) },
    toggle("tools_colored_icons_title", toolbox, crop = HelpCrop.KEYBOARD) { setColoredToolIcons(it) },
    toggle("tools_gradient_icons_title", toolbox, crop = HelpCrop.KEYBOARD) { setToolIconGradients(it) },
    gallery(
        "appearance_toolbox_layout_title",
        "appearance_toolbox_layout_icons_label" to toolbox.copy(seed = { repo.setToolboxLayout(ToolboxLayout.ICONS) }),
        "appearance_toolbox_layout_pills_label" to toolbox.copy(seed = { repo.setToolboxLayout(ToolboxLayout.PILLS) }),
    ),
    compare("appearance_toolbox_columns_title", toolbox, off = { repo.setToolboxColumns(4) }, on = { repo.setToolboxColumns(6) }, crop = HelpCrop.KEYBOARD, labels = "=4" to "=6"),
    toggle("appearance_toolbox_pill_filled_title", toolbox.copy(seed = { repo.setToolboxLayout(ToolboxLayout.PILLS) }), crop = HelpCrop.KEYBOARD) { setToolboxPillFilled(it) },
    toggle("appearance_toolbox_search_button_title", toolbox, crop = HelpCrop.KEYBOARD) { setToolboxSearchButton(it) },
    toggle("appearance_toolbox_paginate_title", toolbox, crop = HelpCrop.KEYBOARD) { setToolboxPaginate(it) },

    // ------------------------------------------------------------ the strip
    toggle("typing_suggestions_title", typed { type("hel") }) { setSuggestions(it) },
    toggle("typing_punctuation_suggestions_title", typed { type("testing") }) { setPunctuationSuggestions(it) },
    toggle("typing_chips_at_end_title", typed { copy("Your verification code is 483920") }) { setStripChipsAtEnd(it) },
    compare("typing_suggestion_slots_title", typed { type("hel") }, off = { repo.setSuggestionSlotCount(3) }, on = { repo.setSuggestionSlotCount(5) }, labels = "=3" to "=5"),
    toggle("typing_primary_center_title", typed { type("helo") }) { setSuggestionPrimaryCenter(it) },
    toggle("typing_suggestion_tinted_title", typed { type("helo") }) { setSuggestionTintedSlots(it) },
    toggle("typing_suggestion_emoji_slot_title", typed { type("birthday") }) { setSuggestionEmojiTakesSlot(it) },
    compare("appearance_suggestion_text_size_title", typed { type("hel") }, off = { repo.setSuggestionTextScale(0.8f) }, on = { repo.setSuggestionTextScale(1.3f) }, labels = "=Small" to "=Large"),
    toggle("langemoji_emoji_prediction_title", typed { type("birthday") }) { setEmojiPrediction(it) },
    toggle("typing_inline_emoji_search_title", typed { type(":cat") }) { setInlineEmojiSearch(it) },
    toggle("typing_octopus_enabled_title", typed { type("hel") }, crop = HelpCrop.KEYBOARD) { setOctopusEnabled(it) },

    // ------------------------------------------------------------ smart chips
    toggle("typing_smart_chips_title", typed { type("1 ft") }) { setSmartSuggestions(it) },
    toggle("typing_smart_units_title", typed { type("1 ft") }) { setSmartUnits(it) },

    // ------------------------------------------------------------ what typing does
    toggle("typing_autocorrect_title", typed { type("teh ") }, crop = HelpCrop.TEXT) { setAutocorrect(it) },
    toggle("typing_auto_close_brackets_title", typed { type("(") }, crop = HelpCrop.TEXT) { setAutoCloseBrackets(it) },
    toggle("typing_auto_close_quotes_title", typed { type("\"") }, crop = HelpCrop.TEXT) { setAutoCloseQuotes(it) },

    // ------------------------------------------------------------ glide
    // Recorded on a phone (build.py --take / --stills): a glide injected on
    // the JVM stops after a letter or two, so the clip, the trail's width and
    // opacity, and the word preview are filed from a recording of a real one.

    // ------------------------------------------------------------ clipboard panel
    gallery(
        "clipboard_view_title",
        "=Grid" to clips.copy(seed = { repo.setClipboardView(ClipboardView.GRID) }),
        "=List" to clips.copy(seed = { repo.setClipboardView(ClipboardView.LIST) }),
    ),
    toggle("clipboard_numbers_title", clips, crop = HelpCrop.KEYBOARD) { setClipboardShowNumbers(it) },
    toggle("clipboard_card_buttons_title", clips, crop = HelpCrop.KEYBOARD) { setClipboardCardButtons(it) },
    toggle("clipboard_pinned_tabs_title", clips, crop = HelpCrop.KEYBOARD) { setClipboardPinnedTabs(it) },
    toggle("clipboard_key_row_title", clips, crop = HelpCrop.KEYBOARD) { setClipboardKeyRow(it) },
    toggle("clipboard_search_title", clips, crop = HelpCrop.KEYBOARD) { setClipboardSearch(it) },
    compare("clipboard_lines_title", clips, off = { repo.setClipboardPreviewLines(1) }, on = { repo.setClipboardPreviewLines(4) }, crop = HelpCrop.KEYBOARD, labels = "=1" to "=4"),
    toggle("clipboard_suggest_recent_title", typed { copy("Meeting moved to 3 pm, room 204") }) { setClipboardSuggestRecent(it) },

    // ------------------------------------------------------------ emoji panel
    toggle("langemoji_emoji_continuous_title", emoji, crop = HelpCrop.KEYBOARD) { setEmojiContinuousScroll(it) },
    compare("langemoji_emoji_grid_size_title", emoji, off = { repo.setEmojiGridCellSize(36) }, on = { repo.setEmojiGridCellSize(56) }, crop = HelpCrop.KEYBOARD, labels = "=Small" to "=Large"),
    compare("langemoji_emoji_size_title", emoji, off = { repo.setEmojiGridEmojiSize(20) }, on = { repo.setEmojiGridEmojiSize(34) }, crop = HelpCrop.KEYBOARD, labels = "=Small" to "=Large"),

    // ------------------------------------------------------------ more key faces and rows
    gallery(
        "accessibility_color_vision_title",
        "common_off" to KbShot("", seed = { repo.setColorVisionFilter(ColorVisionFilter.NONE) }),
        "accessibility_color_vision_deutan" to KbShot("", seed = { repo.setColorVisionFilter(ColorVisionFilter.DEUTERANOPIA) }),
        "accessibility_color_vision_tritan" to KbShot("", seed = { repo.setColorVisionFilter(ColorVisionFilter.TRITANOPIA) }),
        "accessibility_color_vision_grey" to KbShot("", seed = { repo.setColorVisionFilter(ColorVisionFilter.GRAYSCALE) }),
    ),
    gallery(
        "appearance_key_hint_corner_title",
        "appearance_key_hint_corner_top_end" to KbShot("", seed = { repo.setHintCorner(HintCorner.TOP_END) }),
        "appearance_key_hint_corner_top_start" to KbShot("", seed = { repo.setHintCorner(HintCorner.TOP_START) }),
        "appearance_key_hint_corner_bottom_center" to KbShot("", seed = { repo.setHintCorner(HintCorner.BOTTOM_CENTER) }),
        crop = HelpCrop.DIFF,
    ),
    compare("appearance_key_hint_offset_title", off = { repo.setHintOffsetDp(0) }, on = { repo.setHintOffsetDp(8) }, labels = "=0 dp" to "=8 dp"),
    compare("appearance_key_hint_offset_x_title", off = { repo.setHintOffsetXDp(0) }, on = { repo.setHintOffsetXDp(8) }, labels = "=0 dp" to "=8 dp"),
    compare("appearance_key_label_offset_x_title", off = { repo.setLabelOffsetXDp(0) }, on = { repo.setLabelOffsetXDp(8) }, labels = "=0 dp" to "=8 dp"),
    compare("appearance_key_label_offset_y_title", off = { repo.setLabelOffsetYDp(0) }, on = { repo.setLabelOffsetYDp(8) }, labels = "=0 dp" to "=8 dp"),
    gallery(
        "keypress_top_row_holds_title",
        "keypress_top_row_holds_digits" to KbShot("", seed = { repo.setTopRowHolds(TopRowHolds.DIGITS) }),
        "keypress_top_row_holds_symbols" to KbShot("", seed = { repo.setTopRowHolds(TopRowHolds.SYMBOLS) }),
        "keypress_top_row_holds_none" to KbShot("", seed = { repo.setTopRowHolds(TopRowHolds.NONE) }),
        crop = HelpCrop.DIFF,
    ),
    compare("rows_symbol_row_height_title", KbShot("", seed = { repo.setSymbolRowEnabled(true) }), off = { repo.setSymbolRowHeightDp(32) }, on = { repo.setSymbolRowHeightDp(56) }, crop = HelpCrop.KEYBOARD, labels = "=32 dp" to "=56 dp"),
    gallery(
        "rows_recent_words_placement_title",
        "rows_recent_words_placement_row" to typed { type("see you at six ") }.copy(seed = { repo.setRecentWordBar(true); repo.setRecentWordPlacement(RecentWordPlacement.OWN_ROW) }),
        "rows_recent_words_placement_inline" to typed { type("see you at six ") }.copy(seed = { repo.setRecentWordBar(true); repo.setRecentWordPlacement(RecentWordPlacement.INLINE) }),
        crop = HelpCrop.DIFF,
    ),
    toggle("privacy_incognito_title") { setIncognito(it) },

    // ------------------------------------------------------------ more of the bottom row and the board
    gallery(
        "layout_bottom_row_preset_title",
        "layout_bottom_row_preset_as_laid_out" to KbShot("", seed = { repo.setBottomRowPreset(BottomRowPreset.AS_LAID_OUT) }),
        "layout_bottom_row_preset_classic" to KbShot("", seed = { repo.setBottomRowPreset(BottomRowPreset.CLASSIC) }),
        "layout_bottom_row_preset_iphone" to KbShot("", seed = { repo.setBottomRowPreset(BottomRowPreset.IPHONE) }),
        "layout_bottom_row_preset_minimal" to KbShot("", seed = { repo.setBottomRowPreset(BottomRowPreset.MINIMAL) }),
        "layout_bottom_row_preset_with_emoji" to KbShot("", seed = { repo.setBottomRowPreset(BottomRowPreset.WITH_EMOJI) }),
        crop = HelpCrop.DIFF,
    ),
    toggle("layout_globe_in_one_place_title", KbShot("", seed = twoLanguages)) { setGlobeInOnePlace(it) },
    toggle("layout_number_row_on_keypad_title", KbShot("", kind = "number", seed = withNumberRow), crop = HelpCrop.KEYBOARD) { setNumberRowOnKeypad(it) },
    compare("layout_board_inverse_corner_title", KbShot("", mode = "blank"), off = { repo.setBoardInverseCornerDp(0) }, on = { repo.setBoardInverseCornerDp(24) }, crop = HelpCrop.KEYBOARD, labels = "common_off" to "=24 dp", aboveDp = 40),
    toggle("layout_full_width_bars_title", KbShot("", mode = "blank", seed = narrowBoard), crop = HelpCrop.KEYBOARD) { setFullWidthBars(it) },
    gallery(
        "layout_keyboard_position_title",
        "layout_edge_left_label" to KbShot("", mode = "blank", seed = { narrowBoard(); repo.setKeyboardAlignment(KeyboardAlignment.LEFT) }),
        "layout_edge_centre_label" to KbShot("", mode = "blank", seed = { narrowBoard(); repo.setKeyboardAlignment(KeyboardAlignment.CENTER) }),
        "layout_edge_right_label" to KbShot("", mode = "blank", seed = { narrowBoard(); repo.setKeyboardAlignment(KeyboardAlignment.RIGHT) }),
    ),
    toggle("layout_split_see_through_title", KbShot("", seed = { repo.setSplitKeyboard(true) }), crop = HelpCrop.KEYBOARD) { setSplitGapSeeThrough(it) },
    gallery(
        "layout_floating_handle_title",
        "layout_floating_handle_bottom_label" to KbShot("", mode = "blank", seed = { floatingOn(); repo.setFloatingHandle(FloatingHandlePosition.BOTTOM) }),
        "layout_floating_handle_top_label" to KbShot("", mode = "blank", seed = { floatingOn(); repo.setFloatingHandle(FloatingHandlePosition.TOP) }),
        crop = HelpCrop.SCREEN,
    ),
    compare("layout_floating_width_title", KbShot("", mode = "blank", seed = floatingOn), off = { repo.setFloatingWidthDp(260) }, on = { repo.setFloatingWidthDp(380) }, crop = HelpCrop.SCREEN, labels = "=260 dp" to "=380 dp"),
    compare("layout_floating_height_title", KbShot("", mode = "blank", seed = floatingOn), off = { repo.setFloatingHeightScale(0.7f) }, on = { repo.setFloatingHeightScale(1.3f) }, crop = HelpCrop.SCREEN, labels = "=70%" to "=130%"),

    // ------------------------------------------------------------ more press and hold
    toggle("keypress_alternates_nearest_title", holdA, aboveDp = 140, crop = HelpCrop.KEYBOARD) { setAlternatesNearestFirst(it) },
    compare("keypress_alternates_padding_title", holdA, off = { repo.setAlternatesPaddingDp(0) }, on = { repo.setAlternatesPaddingDp(16) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=16 dp", aboveDp = 140),
    compare("keypress_popup_radius_title", holdE, off = { repo.setKeyPopupCornerRadiusDp(0) }, on = { repo.setKeyPopupCornerRadiusDp(32) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=32 dp", aboveDp = 120),
    compare("keypress_popup_height_title", pressG, off = { repo.setKeyPopupHeightDp(48) }, on = { repo.setKeyPopupHeightDp(120) }, crop = HelpCrop.KEYBOARD, labels = "=48 dp" to "=120 dp", aboveDp = 96),
    compare("keypress_popup_offset_y_title", pressG.copy(seed = { repo.setKeyPopupOnKey(false) }), off = { repo.setKeyPopupFloatingOffsetYDp(0) }, on = { repo.setKeyPopupFloatingOffsetYDp(48) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=48 dp", aboveDp = 96),
    compare("keypress_popup_offset_x_title", pressG.copy(seed = { repo.setKeyPopupOnKey(false) }), off = { repo.setKeyPopupFloatingOffsetXDp(-40) }, on = { repo.setKeyPopupFloatingOffsetXDp(40) }, crop = HelpCrop.KEYBOARD, labels = "=-40 dp" to "=40 dp", aboveDp = 96),
    compare("keypress_preview_width_title", pressG, off = { repo.setKeyPreviewWidthScale(0.8f) }, on = { repo.setKeyPreviewWidthScale(1.6f) }, crop = HelpCrop.KEYBOARD, labels = "=0.8×" to "=1.6×", aboveDp = 96),
    compare("keypress_preview_radius_title", pressG, off = { repo.setKeyPreviewCornerRadiusDp(0) }, on = { repo.setKeyPreviewCornerRadiusDp(24) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=24 dp", aboveDp = 96),

    // ------------------------------------------------------------ more toolbar and toolbox
    gallery(
        "appearance_toolbar_placement_title",
        "appearance_toolbar_placement_strip_label" to strip.copy(seed = { repo.setToolbarPlacement(ToolbarPlacement.STRIP) }),
        "appearance_toolbar_placement_button_label" to strip.copy(seed = { repo.setToolbarPlacement(ToolbarPlacement.ON_DEMAND_ROW) }),
        "appearance_toolbar_placement_always_label" to strip.copy(seed = { repo.setToolbarPlacement(ToolbarPlacement.ALWAYS_ROW) }),
        crop = HelpCrop.DIFF,
    ),
    toggle("appearance_toolbar_strip_only_words_title", KbShot("", seed = { repo.setToolbarPlacement(ToolbarPlacement.ALWAYS_ROW) })) { setToolbarStripOnlyWithWords(it) },
    toggle("appearance_toolbar_bar_toolbox_title") { setToolbarBarToolbox(it) },
    toggle("appearance_toolbar_tools_arrow_title", strip) { setToolbarToolsArrow(it) },
    compare("appearance_toolbar_padding_top_title", off = { repo.setToolbarPaddingTopDp(0) }, on = { repo.setToolbarPaddingTopDp(12) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=12 dp"),
    compare("appearance_toolbar_padding_bottom_title", off = { repo.setToolbarPaddingBottomDp(0) }, on = { repo.setToolbarPaddingBottomDp(12) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=12 dp"),
    compare("appearance_toolbar_label_size_title", KbShot("", seed = { repo.setToolbarLabels(true) }), off = { repo.setToolbarLabelSize(8) }, on = { repo.setToolbarLabelSize(14) }, labels = "=8 sp" to "=14 sp"),
    toggle("appearance_toolbar_labels_fixed_title", KbShot("", seed = { repo.setToolbarLabels(true) })) { setToolbarLabelsFixedWidth(it) },
    compare("appearance_tool_circle_title", off = { repo.setToolCircleRadiusDp(0) }, on = { repo.setToolCircleRadiusDp(20) }, labels = "common_off" to "=20 dp"),
    compare("appearance_tool_width_title", off = { repo.setToolbarToolWidthDp(36) }, on = { repo.setToolbarToolWidthDp(64) }, labels = "=36 dp" to "=64 dp"),
    compare("appearance_tool_height_title", off = { repo.setToolbarToolHeightDp(32) }, on = { repo.setToolbarToolHeightDp(52) }, crop = HelpCrop.KEYBOARD, labels = "=32 dp" to "=52 dp"),
    compare("appearance_tool_icon_size_title", off = { repo.setToolbarIconSizeDp(16) }, on = { repo.setToolbarIconSizeDp(30) }, labels = "=16 dp" to "=30 dp"),
    compare("appearance_toolbox_pill_columns_title", toolbox.copy(seed = { repo.setToolboxLayout(ToolboxLayout.PILLS) }), off = { repo.setToolboxPillColumns(1) }, on = { repo.setToolboxPillColumns(3) }, crop = HelpCrop.KEYBOARD, labels = "=1" to "=3"),
    compare("appearance_toolbox_label_size_title", toolbox, off = { repo.setToolboxLabelSize(9) }, on = { repo.setToolboxLabelSize(16) }, crop = HelpCrop.KEYBOARD, labels = "=9 sp" to "=16 sp"),
    compare("appearance_toolbox_cell_spacing_title", toolbox, off = { repo.setToolboxCellSpacing(0) }, on = { repo.setToolboxCellSpacing(16) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=16 dp"),
    toggle("appearance_toolbox_search_at_end_title", toolbox) { setToolboxSearchAtEnd(it) },

    // ------------------------------------------------------------ more of the strip
    compare("appearance_suggestion_spacing_title", strip, off = { repo.setSuggestionChipPadding(0) }, on = { repo.setSuggestionChipPadding(24) }, labels = "=0 dp" to "=24 dp"),
    gallery(
        "appearance_long_words_title",
        "appearance_long_words_narrow_only" to typed { type("internationaliz") }.copy(seed = { repo.setSuggestionLongWords(LongWordFit.NARROW_ONLY) }),
        "appearance_long_words_shrink_only" to typed { type("internationaliz") }.copy(seed = { repo.setSuggestionLongWords(LongWordFit.SHRINK_ONLY) }),
        "appearance_long_words_cut_off" to typed { type("internationaliz") }.copy(seed = { repo.setSuggestionLongWords(LongWordFit.CUT_OFF) }),
        crop = HelpCrop.DIFF,
    ),
    compare("appearance_narrowest_letters_title", typed { type("internationaliz") }, off = { repo.setSuggestionNarrowestLetters(0.5f) }, on = { repo.setSuggestionNarrowestLetters(1f) }, labels = "=50%" to "=100%"),
    compare("appearance_smallest_text_title", typed { type("internationaliz") }, off = { repo.setSuggestionSmallestText(0.5f) }, on = { repo.setSuggestionSmallestText(1f) }, labels = "=50%" to "=100%"),
    toggle("typing_suggestions_first_title") { setSuggestionsFirst(it) },
    toggle("typing_fit_more_words_title", typed { type("a") }) { setFitMoreWords(it) },
    toggle("typing_suggestion_fixed_slots_title", strip) { setSuggestionFixedSlots(it) },
    toggle("typing_suggestion_scroll_title", strip) { setSuggestionScrollable(it) },
    toggle("typing_skip_typed_word_title", strip) { setSkipTypedWord(it) },
    compare("typing_suggestion_emoji_count_title", typed { type("birthday") }, off = { repo.setSuggestionEmojiCount(1) }, on = { repo.setSuggestionEmojiCount(4) }, labels = "=1" to "=4"),
    gallery(
        "typing_retype_button_title",
        "typing_retype_button_off" to typed { type("hello") }.copy(seed = { repo.setRetypeButton(RetypeButtonPlace.OFF) }),
        "typing_retype_button_strip" to typed { type("hello") }.copy(seed = { repo.setRetypeButton(RetypeButtonPlace.SUGGESTION_BAR) }),
        crop = HelpCrop.DIFF,
    ),
    toggle("typing_number_prediction_title", typed { type("qwe") }) { setNumberPrediction(it) },
    toggle("typing_strip_splits_title", typed { type("thecat") }) { setStripSplits(it) },
    toggle("typing_undo_chip_title", typed { type("teh ") }.copy(seed = { repo.setAutocorrect(true); repo.setUndoChipObviousness(1f) })) { setUndoCorrectionChip(it) },
    toggle("typing_smart_punctuation_title", typed { type("hello") }, crop = HelpCrop.KEYBOARD) { setSmartPunctuation(it) },
    toggle("typing_autopilot_show_title", typed { type("th") }.copy(seed = autopilotOn), crop = HelpCrop.KEYBOARD) { setAutopilotShowEffect(it) },
    toggle("typing_autopilot_outline_title", typed { type("th") }.copy(seed = autopilotOn), crop = HelpCrop.KEYBOARD) { setAutopilotOutline(it) },
    gallery(
        "typing_octopus_placement_title",
        "core_settings_octopus_placement_float_label" to strip.copy(seed = { octopusOn(); repo.setOctopusPlacement(OctopusPlacement.FLOAT) }),
        "core_settings_octopus_placement_strip_label" to strip.copy(seed = { octopusOn(); repo.setOctopusPlacement(OctopusPlacement.STRIP) }),
        "core_settings_octopus_placement_in_key_label" to strip.copy(seed = { octopusOn(); repo.setOctopusPlacement(OctopusPlacement.IN_KEY) }),
    ),
    compare("typing_octopus_density_title", strip.copy(seed = octopusOn), off = { repo.setOctopusDensity(2) }, on = { repo.setOctopusDensity(6) }, crop = HelpCrop.KEYBOARD, labels = "=2" to "=6"),
    compare("typing_octopus_size_title", strip.copy(seed = octopusOn), off = { repo.setOctopusFontScale(0.8f) }, on = { repo.setOctopusFontScale(1.4f) }, crop = HelpCrop.KEYBOARD, labels = "=0.8" to "=1.4"),
    toggle("typing_octopus_hints_title", strip.copy(seed = octopusOn), crop = HelpCrop.KEYBOARD) { setOctopusSuppressHints(it) },
    toggle("typing_hw_suggestion_hints_title", KbShot("", hardwareKeyboard = true, steps = { type("hel") })) { setHwSuggestionHintsAlways(it) },

    // ------------------------------------------------------------ more smart chips
    toggle("typing_smart_dates_title", typed { type("next friday") }) { setSmartChipDates(it) },
    toggle("typing_smart_numbers_title", typed { type("1234567") }) { setSmartChipNumbers(it) },
    toggle("typing_smart_tool_keywords_title", typed { type("wiki") }) { setSmartToolKeywords(it) },
    toggle("typing_smart_lookups_title", typed { type("define serendipity") }) { setSmartChipLookups(it) },
    toggle("typing_smart_intents_title", typed { type("good morning in spanish") }) { setSmartChipIntents(it) },
    toggle("typing_smart_gifs_title", typed { type("happy birthday") }) { setSmartChipGifs(it) },
    toggle("typing_smart_repeats_title", typed { type("ha ha ha ha ") }) { setSmartChipRepeats(it) },

    // ------------------------------------------------------------ more of what typing does
    toggle("typing_autocorrect_splits_title", typed { type("thecat ") }, crop = HelpCrop.TEXT) { setAutocorrectSplits(it) },
    toggle("typing_auto_apostrophe_title", typed { type("dont ") }, crop = HelpCrop.TEXT) { setAutoApostrophe(it) },
    toggle("typing_hug_punctuation_title", typed { type("Hey .") }, crop = HelpCrop.TEXT) { setHugPunctuation(it) },
    toggle("typing_continue_lists_title", typed { type("1. milk\n") }, crop = HelpCrop.TEXT) { setContinueLists(it) },
    toggle("typing_wrap_selection_title", typed { type("hello"); select("hello"); type("(") }, crop = HelpCrop.TEXT) { setWrapSelectionWithPair(it) },
    toggle("selection_macros_title", selected) { setSelectionMacrosEnabled(it) },
    gallery(
        "selection_macros_placement_title",
        "selection_macros_placement_row" to selected.copy(seed = { repo.setSelectionMacroPlacement(SelectionMacroPlacement.OWN_ROW) }),
        "selection_macros_placement_strip" to selected.copy(seed = { repo.setSelectionMacroPlacement(SelectionMacroPlacement.STRIP) }),
        crop = HelpCrop.DIFF,
    ),
    toggle("selection_macros_caret_title", typed { type("hello") }) { setSelectionMacrosWithoutSelection(it) },

    // ------------------------------------------------------------ more of the clipboard
    toggle("clipboard_full_bleed_title", clips, crop = HelpCrop.KEYBOARD) { setClipboardFullBleed(it) },
    toggle("clipboard_clear_button_title", clips, crop = HelpCrop.KEYBOARD) { setClipboardClearButton(it) },
    compare("clipboard_panel_height_title", clips, off = { repo.setClipboardPanelExtraHeightDp(0) }, on = { repo.setClipboardPanelExtraHeightDp(160) }, crop = HelpCrop.KEYBOARD, labels = "common_off" to "=160 dp"),
    compare("clipboard_columns_title", clips, off = { repo.setClipboardGridColumns(1) }, on = { repo.setClipboardGridColumns(3) }, crop = HelpCrop.KEYBOARD, labels = "=1" to "=3"),
    gallery(
        "clipboard_time_title",
        "core_settings_clip_time_off_label" to clips.copy(seed = { repo.setClipboardTimeLabel(ClipTimeLabel.OFF) }),
        "core_settings_clip_time_copied_label" to clips.copy(seed = { repo.setClipboardTimeLabel(ClipTimeLabel.COPIED) }),
        "core_settings_clip_time_expires_label" to clips.copy(seed = { repo.setClipboardTimeLabel(ClipTimeLabel.EXPIRES) }),
    ),
    gallery(
        "clipboard_card_shortcut_title",
        "core_settings_clip_shortcut_none_label" to clips.copy(seed = { repo.setClipboardCardShortcut(ClipCardShortcut.NONE) }),
        "core_settings_clip_shortcut_edit_label" to clips.copy(seed = { repo.setClipboardCardShortcut(ClipCardShortcut.EDIT) }),
        "core_settings_clip_shortcut_select_label" to clips.copy(seed = { repo.setClipboardCardShortcut(ClipCardShortcut.SELECT) }),
        "core_settings_clip_shortcut_qr_label" to clips.copy(seed = { repo.setClipboardCardShortcut(ClipCardShortcut.QR) }),
    ),
    toggle("clipboard_entities_title", clipsWithParts, crop = HelpCrop.KEYBOARD) { setClipboardDetectEntities(it) },
    toggle("clipboard_entity_icons_title", clipsWithParts, crop = HelpCrop.KEYBOARD) { setClipboardEntityIcons(it) },
    compare("clipboard_recent_chips_title", copiedThree, off = { repo.setClipboardRecentChips(1) }, on = { repo.setClipboardRecentChips(3) }, labels = "=1" to "=3"),
    toggle("clipboard_compact_paste_chip_title", typed { copy("Meeting moved to 3 pm, room 204 on the second floor") }) { setClipboardCompactPasteChip(it) },
    toggle("clipboard_fragments_on_strip_title", typed { copy("Call me at +1 555 0100 or see https://wmkeyboard.app") }) { setClipboardFragmentsOnStrip(it) },

    // ------------------------------------------------------------ more of the emoji panel
    toggle("langemoji_emoji_full_bleed_title", emoji, crop = HelpCrop.KEYBOARD) { setEmojiFullBleed(it) },
    compare("langemoji_panel_height_title", emoji, off = { repo.setMediaPanelExtraHeightDp(0) }, on = { repo.setMediaPanelExtraHeightDp(160) }, crop = HelpCrop.KEYBOARD, labels = "common_off" to "=160 dp"),
    gallery(
        "langemoji_media_switcher_title",
        "langemoji_media_switcher_top" to emoji.copy(seed = { repo.setEmojiMediaSwitcher(MediaSwitcher.TOP) }),
        "langemoji_media_switcher_bottom" to emoji.copy(seed = { repo.setEmojiMediaSwitcher(MediaSwitcher.BOTTOM) }),
    ),

    // ------------------------------------------------------------ tool panels, on the docs harness's own seeded data
    toggle("tooldetail_launcher_labels_title", docs("tools/app-launcher-grid"), crop = HelpCrop.KEYBOARD) { setLauncherShowLabels(it) },
    toggle("tooldetail_launcher_recents_title", docs("tools/app-launcher-grid"), crop = HelpCrop.KEYBOARD) { setLauncherRecentsEnabled(it) },
    compare("tooldetail_launcher_columns_title", docs("tools/app-launcher-grid"), off = { repo.setLauncherGridColumns(4) }, on = { repo.setLauncherGridColumns(6) }, crop = HelpCrop.KEYBOARD, labels = "=4" to "=6"),
    compare("tooldetail_launcher_icon_size_title", docs("tools/app-launcher-grid"), off = { repo.setLauncherIconSize(36) }, on = { repo.setLauncherIconSize(56) }, crop = HelpCrop.KEYBOARD, labels = "=36 dp" to "=56 dp"),
    gallery(
        "tooldetail_launcher_icon_shape_title",
        "tooldetail_launcher_icon_shape_circle_label" to docs("tools/app-launcher-grid").seededWith { repo.setLauncherIconShape(LauncherIconShape.CIRCLE) },
        "tooldetail_launcher_icon_shape_rounded_label" to docs("tools/app-launcher-grid").seededWith { repo.setLauncherIconShape(LauncherIconShape.ROUNDED) },
    ),
    compare("expander_grid_columns_title", docs("tools/snippets-panel"), off = { repo.setSnippetGridColumns(1) }, on = { repo.setSnippetGridColumns(3) }, crop = HelpCrop.KEYBOARD, labels = "=1" to "=3"),
    toggle("expander_titles_only_title", docs("tools/snippets-panel"), crop = HelpCrop.KEYBOARD) { setSnippetTitlesOnly(it) },
    toggle("tooldetail_compass_degrees_title", docs("tools/compass-panel-qibla"), crop = HelpCrop.KEYBOARD) { setCompassShowDegrees(it) },
    toggle("tooldetail_compass_qibla_title", docs("tools/compass-panel-qibla"), crop = HelpCrop.KEYBOARD) { setCompassShowQibla(it) },
    toggle("tooldetail_level_angles_title", docs("tools/level-panel"), crop = HelpCrop.KEYBOARD) { setLevelShowAngles(it) },
    toggle("tooldetail_moon_southern_title", docs("tools/moon-phase-panel"), crop = HelpCrop.KEYBOARD) { setMoonSouthernHemisphere(it) },
    toggle("tooldetail_moon_arabic_title", docs("tools/moon-phase-panel"), crop = HelpCrop.KEYBOARD) { setMoonArabicNames(it) },
    toggle("tooldetail_weather_fahrenheit_title", docs("tools/weather-panel-ready"), crop = HelpCrop.KEYBOARD) { setWeatherFahrenheit(it) },
    compare("tooldetail_calendar_hijri_title", docs("tools/calendar-month-grid"), off = { repo.setHijriAdjustDays(-1) }, on = { repo.setHijriAdjustDays(1) }, crop = HelpCrop.KEYBOARD, labels = "=-1" to "=+1"),
    toggle("tooldetail_units_compound_title", docs("tools/unit-converter-panel"), crop = HelpCrop.KEYBOARD) { setCompoundUnits(it) },
    toggle("tooldetail_converter_tabs_title", docs("tools/unit-converter-panel"), crop = HelpCrop.KEYBOARD) { setToolboxConverterTabs(it) },
    compare("tooldetail_currency_decimals_title", docs("tools/currency-converter-panel"), off = { repo.setCurrencyDecimals(0) }, on = { repo.setCurrencyDecimals(4) }, crop = HelpCrop.KEYBOARD, labels = "=0" to "=4"),
    gallery(
        "tooldetail_currency_label_title",
        "tooldetail_currency_label_name" to docs("smart/currency-chip").seededWith { repo.setCurrencyLabel(CurrencyLabel.NAME) },
        "tooldetail_currency_label_symbol" to docs("smart/currency-chip").seededWith { repo.setCurrencyLabel(CurrencyLabel.SYMBOL) },
        "tooldetail_currency_label_code" to docs("smart/currency-chip").seededWith { repo.setCurrencyLabel(CurrencyLabel.CODE) },
        crop = HelpCrop.DIFF,
    ),
    toggle("tooldetail_calc_degrees_title", typed { panel(PanelMode.CALCULATOR); type("sin(90)") }, crop = HelpCrop.KEYBOARD) { setCalcDegrees(it) },
    toggle("tooldetail_password_uppercase_title", docs("tools/password-generator-panel"), crop = HelpCrop.KEYBOARD) { setPwUppercase(it) },
    toggle("tooldetail_password_digits_title", docs("tools/password-generator-panel"), crop = HelpCrop.KEYBOARD) { setPwDigits(it) },
    toggle("tooldetail_password_symbols_title", docs("tools/password-generator-panel"), crop = HelpCrop.KEYBOARD) { setPwSymbols(it) },
    compare("tooldetail_password_length_title", docs("tools/password-generator-panel"), off = { repo.setPwLength(8) }, on = { repo.setPwLength(32) }, crop = HelpCrop.KEYBOARD, labels = "=8" to "=32"),
    toggle("tooldetail_repeat_numbered_title", docs("tools/text-repeat-panel"), crop = HelpCrop.KEYBOARD) { setTextRepeatNumbered(it) },
    toggle("tooldetail_repeat_alternate_title", docs("tools/text-repeat-panel"), crop = HelpCrop.KEYBOARD) { setTextRepeatAlternateCase(it) },
    compare("tooldetail_repeat_count_title", docs("tools/text-repeat-panel"), off = { repo.setTextRepeatCount(3) }, on = { repo.setTextRepeatCount(8) }, crop = HelpCrop.KEYBOARD, labels = "=3" to "=8"),
    gallery(
        "tooldetail_repeat_mode_title",
        "tooldetail_repeat_mode_repeat" to docs("tools/text-repeat-panel").seededWith { repo.setTextRepeatMode(TextRepeat.Mode.REPEAT) },
        "tooldetail_repeat_mode_stretch" to docs("tools/text-repeat-panel").seededWith { repo.setTextRepeatMode(TextRepeat.Mode.STRETCH) },
        "tooldetail_repeat_mode_stairs" to docs("tools/text-repeat-panel").seededWith { repo.setTextRepeatMode(TextRepeat.Mode.STAIRS) },
    ),
    gallery(
        "tooldetail_repeat_separator_title",
        "tooldetail_repeat_sep_space" to docs("tools/text-repeat-panel").seededWith { repo.setTextRepeatSeparator(TextRepeat.Separator.SPACE) },
        "tooldetail_repeat_sep_newline" to docs("tools/text-repeat-panel").seededWith { repo.setTextRepeatSeparator(TextRepeat.Separator.NEWLINE) },
        "tooldetail_repeat_sep_comma" to docs("tools/text-repeat-panel").seededWith { repo.setTextRepeatSeparator(TextRepeat.Separator.COMMA) },
    ),
    toggle("toolai_typing_punctuation_title", docs("tools/typing-test-panel"), crop = HelpCrop.KEYBOARD) { setTypingTestPunctuation(it) },
    toggle("toolai_typing_numbers_title", docs("tools/typing-test-panel"), crop = HelpCrop.KEYBOARD) { setTypingTestNumbers(it) },
    compare("tooldetail_image_columns_title", docs("tools/image-search-grid"), off = { repo.setMediaGridColumns(2) }, on = { repo.setMediaGridColumns(4) }, crop = HelpCrop.KEYBOARD, labels = "=2" to "=4"),
    toggle("tooldetail_search_answer_title", docs("tools/web-search-results"), crop = HelpCrop.KEYBOARD) { setSearchShowAnswer(it) },
    toggle("tooldetail_media_full_bleed_title", docs("emoji/gif-panel-trending"), crop = HelpCrop.KEYBOARD) { setMediaFullBleed(it) },
    compare("tooldetail_media_sticker_per_row_title", docs("emoji/sticker-panel-unsupported-field"), off = { repo.setStickerPerRow(3) }, on = { repo.setStickerPerRow(5) }, crop = HelpCrop.KEYBOARD, labels = "=3" to "=5"),
    compare("tooldetail_media_spacing_title", docs("emoji/gif-panel-trending"), off = { repo.setMediaGridSpacing(0) }, on = { repo.setMediaGridSpacing(12) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=12 dp"),
    toggle("tooldetail_power_now_title", crop = HelpCrop.KEYBOARD) { setPowerSavingManual(it) },
    toggle("tooldetail_power_dark_theme_title", KbShot("", seed = { repo.setPowerSavingManual(true) }), crop = HelpCrop.KEYBOARD) { setPowerSavingDarkTheme(it) },
    toggle("clipboard_text_only_title", docs("tools/clipboard-rich-media"), crop = HelpCrop.KEYBOARD) { setClipboardTextOnly(it) },
    toggle("selection_macros_detect_title", docs("smart/selection-actions-phone")) { setSelectionMacroDetectEntities(it) },
    toggle("typing_language_detection_title", docs("smart/language-detection-switch")) { setLanguageDetection(it) },
    toggle("languages_cjk_traditional_title", docs("languages/cjk-pinyin-candidate-strip")) { setCjkTraditionalOutput(it) },
    toggle("languages_cjk_traditional_button_title", docs("languages/cjk-pinyin-candidate-strip")) { setCjkTraditionalButton(it) },
    toggle("languages_cjk_reading_in_field_title", docs("languages/cjk-pinyin-candidate-strip"), crop = HelpCrop.TEXT) { setCjkReadingInField(it) },

    // ------------------------------------------------------------ the theme editor
    gallery(
        "theme_key_shape_title",
        "theme_key_shape_rounded_label" to wearing(helpTheme.copy(keyShape = KeyShapeKind.ROUNDED)),
        "theme_key_shape_sharp_label" to wearing(helpTheme.copy(keyShape = KeyShapeKind.SHARP)),
        "theme_key_shape_pill_label" to wearing(helpTheme.copy(keyShape = KeyShapeKind.PILL)),
        "theme_key_shape_squircle_label" to wearing(helpTheme.copy(keyShape = KeyShapeKind.SQUIRCLE)),
        "theme_key_shape_hexagon_label" to wearing(helpTheme.copy(keyShape = KeyShapeKind.HEXAGON)),
        "theme_key_shape_ticket_label" to wearing(helpTheme.copy(keyShape = KeyShapeKind.TICKET)),
    ),
    gallery(
        "theme_material_title",
        "theme_material_kind_cap_label" to material("CAP"),
        "theme_material_kind_metal_label" to material("METAL"),
        "theme_material_kind_wood_label" to material("WOOD"),
        "theme_material_kind_leather_label" to material("LEATHER"),
        "theme_material_kind_paper_label" to material("PAPER"),
        "theme_material_kind_neon_label" to material("NEON"),
        "theme_material_kind_terminal_label" to material("TERMINAL"),
        "theme_material_kind_cartoon_label" to material("CARTOON"),
    ),
    gallery(
        "theme_material_label_title",
        "theme_material_label_none_label" to material("CAP", "NONE"),
        "theme_material_label_glow_label" to material("CAP", "GLOW"),
        "theme_material_label_deboss_label" to material("CAP", "DEBOSS"),
        "theme_material_label_emboss_label" to material("CAP", "EMBOSS"),
        "theme_material_label_dots_label" to material("CAP", "DOTS"),
    ),
    gallery(
        "theme_board_effect_title",
        "theme_board_effect_kind_snow_label" to wearing(helpTheme.copy(boardEffect = BoardEffectSpec(kind = "SNOW"))),
        "theme_board_effect_kind_starfield_label" to wearing(helpTheme.copy(boardEffect = BoardEffectSpec(kind = "STARFIELD"))),
        "theme_board_effect_kind_bubbles_label" to wearing(helpTheme.copy(boardEffect = BoardEffectSpec(kind = "BUBBLES"))),
        "theme_board_effect_kind_code_rain_label" to wearing(helpTheme.copy(boardEffect = BoardEffectSpec(kind = "CODE_RAIN"))),
        "theme_board_effect_kind_aurora_label" to wearing(helpTheme.copy(boardEffect = BoardEffectSpec(kind = "AURORA"))),
        "theme_board_effect_kind_confetti_label" to wearing(helpTheme.copy(boardEffect = BoardEffectSpec(kind = "CONFETTI"))),
    ),
    gallery(
        "theme_lighting_title",
        "theme_lighting_pattern_steady_label" to lighting("STEADY"),
        "theme_lighting_pattern_wave_label" to lighting("WAVE"),
        "theme_lighting_pattern_sweep_label" to lighting("SWEEP"),
        "theme_lighting_pattern_twinkle_label" to lighting("TWINKLE"),
    ),
    gallery(
        "theme_lighting_target_title",
        "theme_lighting_target_face_label" to lighting("STEADY", "FACE"),
        "theme_lighting_target_edge_label" to lighting("STEADY", "EDGE"),
        "theme_lighting_target_glow_label" to lighting("STEADY", "GLOW"),
        "theme_lighting_target_fill_label" to lighting("STEADY", "FILL"),
        "theme_lighting_target_gradient_label" to lighting("STEADY", "GRADIENT"),
        "theme_lighting_target_glitter_label" to lighting("STEADY", "GLITTER"),
    ),
    compare(
        "theme_touch_bloom_title", pressG,
        off = { repo.upsertCustomTheme(helpTheme); repo.setKeyboardThemeId(helpTheme.id) },
        on = { repo.upsertCustomTheme(helpTheme.copy(touchBloom = TouchBloomSpec())); repo.setKeyboardThemeId(helpTheme.id) },
        crop = HelpCrop.KEYBOARD, aboveDp = 72,
    ),

    // ------------------------------------------------------------ more of typing, on states the strip and field show
    toggle("typing_auto_capitalize_title", typed { type("hello. how are you") }.copy(seed = { repo.setAutoCapitalizeAnyField(true) }), crop = HelpCrop.TEXT) { setAutoCapitalize(it) },
    toggle("typing_auto_capitalize_any_field_title", typed { type("hello. how are you") }, crop = HelpCrop.TEXT) { setAutoCapitalizeAnyField(it) },
    toggle("typing_double_space_title", typed { type("hello  ") }, crop = HelpCrop.TEXT) { setDoubleSpacePeriod(it) },
    toggle("typing_skip_all_caps_title", typed { type("TEH teh ") }, crop = HelpCrop.TEXT) { setAutocorrectSkipAllCaps(it) },
    toggle("typing_undo_autocorrect_title", typed { type("teh "); key(Key(label = "", action = KeyAction.Delete)); settle() }, crop = HelpCrop.TEXT) { setRevertAutocorrectOnBackspace(it) },
    toggle("typing_number_row_corrections_title", typed { type("h3llo ") }.copy(seed = withNumberRow), crop = HelpCrop.TEXT) { setNumberRowCorrections(it) },
    toggle("typing_ask_before_learning_title", typed { type("zelkodyne ") }) { setAskBeforeLearning(it) },
    toggle("typing_undo_delete_chip_title", typed { type("see you tomorrow"); repeat(8) { key(Key(label = "", action = KeyAction.Delete)) }; settle() }) { setUndoDeleteChip(it) },
    toggle("typing_phrase_completion_title", typed { type("see you ") }) { setPhraseCompletion(it) },
    toggle("typing_offer_near_miss_title", typed { type("thier ") }) { setOfferNearMissCorrections(it) },
    toggle("typing_shift_recase_title", typed { type("hello world"); select("world"); key(Key(label = "", action = KeyAction.Shift)); settle() }, crop = HelpCrop.TEXT) { setRecapitalizeSelectionWithShift(it) },
    toggle("typing_app_names_title", docs("tools/app-launcher-grid").copy(steps = { type("Pod") })) { setAppNameSuggestions(it) },
    toggle("typing_smart_apps_title", docs("tools/app-launcher-grid").copy(steps = { type("Podcasts") })) { setSmartChipApps(it) },
    toggle("typing_smart_contacts_title", docs("smart/contact-name-suggestion").copy(steps = { type("Wasi Mollik") })) { setSmartChipContacts(it) },
    toggle("langemoji_emoji_unicode_suggestions_title", typed { type("snowman") }) { setEmojiUnicodeSuggestions(it) },
    toggle("keypress_popup_numeric_title", KbShot("", kind = "number", steps = { press("5") }), aboveDp = 72, crop = HelpCrop.KEYBOARD) { setKeyPopupInNumericFields(it) },
    toggle("appearance_toolbar_hardware_only_title", KbShot("", hardwareKeyboard = true), crop = HelpCrop.SCREEN) { setToolbarOnlyWithHardwareKeyboard(it) },
    toggle("layout_portrait_fullscreen_title", KbShot("", mode = "chat"), crop = HelpCrop.SCREEN) { setPortraitFullscreen(it) },
    compare("layout_board_corner_bottom_title", KbShot("", mode = "blank"), off = { repo.setBoardCornerBottomDp(0) }, on = { repo.setBoardCornerBottomDp(28) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=28 dp"),
    compare("langemoji_emoji_tab_width_title", emoji, off = { repo.setEmojiTabMinWidth(32) }, on = { repo.setEmojiTabMinWidth(64) }, crop = HelpCrop.KEYBOARD, labels = "=32" to "=64"),

    // ------------------------------------------------------------ voice panel
    toggle("voice_strip_language_title", docs("tools/voice-panel-listening"), crop = HelpCrop.KEYBOARD) { setVoiceStripLanguageChip(it) },
    toggle("voice_mic_switch_title", docs("tools/voice-panel-listening"), crop = HelpCrop.KEYBOARD) { setVoiceMicSwitch(it) },

    // ------------------------------------------------------------ more tools
    toggle("tooldetail_power_drop_suggestions_title", strip.copy(seed = { repo.setPowerSavingManual(true) })) { setPowerSavingDropSuggestions(it) },
    toggle("tooldetail_power_drop_chips_title", typed { type("1 ft") }.copy(seed = { repo.setPowerSavingManual(true) })) { setPowerSavingDropSmartChips(it) },
    toggle("tooldetail_power_drop_emoji_title", typed { type("birthday") }.copy(seed = { repo.setPowerSavingManual(true) })) { setPowerSavingDropEmojiPrediction(it) },
    compare("tooldetail_calc_precision_title", typed { panel(PanelMode.CALCULATOR); type("1/3=") }, off = { repo.setCalcPrecision(2) }, on = { repo.setCalcPrecision(10) }, crop = HelpCrop.KEYBOARD, labels = "=2" to "=10"),
    compare("tooldetail_crypto_decimals_title", docs("smart/currency-chip").copy(steps = { type("1 btc"); pause(1500); settle() }), off = { repo.setCryptoDecimals(0) }, on = { repo.setCryptoDecimals(6) }, labels = "=0" to "=6"),
    toggle("toolai_typing_suggestions_title", docs("tools/typing-test-panel"), crop = HelpCrop.KEYBOARD) { setTypingTestSuggestions(it) },
    toggle("tooldetail_privacy_shade_on_title", KbShot("", mode = "chat"), crop = HelpCrop.SCREEN) { setPrivacyShadeOn(it) },
    compare("tooldetail_privacy_shade_opacity_title", KbShot("", mode = "chat", seed = { repo.setPrivacyShadeOn(true) }), off = { repo.setPrivacyShadeOpacity(0.5f) }, on = { repo.setPrivacyShadeOpacity(0.95f) }, crop = HelpCrop.SCREEN, labels = "=50%" to "=95%"),
    gallery(
        "tooldetail_privacy_shade_style_title",
        "tooldetail_privacy_shade_style_dark" to KbShot("", mode = "chat", seed = { repo.setPrivacyShadeOn(true); repo.setPrivacyShadeStyle(PrivacyShadeStyle.DARK) }),
        "tooldetail_privacy_shade_style_grey" to KbShot("", mode = "chat", seed = { repo.setPrivacyShadeOn(true); repo.setPrivacyShadeStyle(PrivacyShadeStyle.GREY) }),
        "tooldetail_privacy_shade_style_warm" to KbShot("", mode = "chat", seed = { repo.setPrivacyShadeOn(true); repo.setPrivacyShadeStyle(PrivacyShadeStyle.WARM) }),
        crop = HelpCrop.SCREEN,
    ),

    // ------------------------------------------------------------ tools
    toggle("tooldetail_calc_phone_layout_title", typed { panel(PanelMode.CALCULATOR) }, crop = HelpCrop.KEYBOARD) { setCalcPhoneLayout(it) },
    toggle("tooldetail_incognito_watermark_title", typed { tool(ToolbarTool.INCOGNITO) }, crop = HelpCrop.KEYBOARD) { setIncognitoWatermark(it) },
    toggle("tooldetail_incognito_badge_title", typed { tool(ToolbarTool.INCOGNITO) }) { setIncognitoBadge(it) },
)

package com.wasimaster.wmkeyboard.docshots

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
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

/** A glide stopped part of the way, the finger still down. */
private val midGlide = typed { glide("hello", lift = false) }

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
    clip(
        "typing_glide_typing_title",
        typed {
            record {
                hold(300)
                glide("hello")
                hold(900)
            }
        },
        aboveDp = 96,
    ),
    toggle("typing_glide_preview_title", midGlide, aboveDp = 96, crop = HelpCrop.KEYBOARD) { setGestureWordPreview(it) },
    compare("typing_trail_width_title", midGlide, off = { repo.setGestureTrailWidthDp(4f) }, on = { repo.setGestureTrailWidthDp(18f) }, crop = HelpCrop.KEYBOARD, labels = "=4 dp" to "=18 dp"),
    compare("typing_trail_opacity_title", midGlide, off = { repo.setGestureTrailOpacity(0.25f) }, on = { repo.setGestureTrailOpacity(1f) }, crop = HelpCrop.KEYBOARD, labels = "=25%" to "=100%"),
    toggle("typing_glide_strip_preview_title", midGlide, aboveDp = 96, crop = HelpCrop.KEYBOARD) { setGestureStripPreviewOnly(it) },

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

    // ------------------------------------------------------------ tools
    toggle("tooldetail_calc_phone_layout_title", typed { panel(PanelMode.CALCULATOR) }, crop = HelpCrop.KEYBOARD) { setCalcPhoneLayout(it) },
    toggle("tooldetail_incognito_watermark_title", typed { tool(ToolbarTool.INCOGNITO) }, crop = HelpCrop.KEYBOARD) { setIncognitoWatermark(it) },
    toggle("tooldetail_incognito_badge_title", typed { tool(ToolbarTool.INCOGNITO) }) { setIncognitoBadge(it) },
)

package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.ime.R as ImeR

/**
 * Words a settings row is found by and never draws, keyed by the row's own
 * title resource.
 *
 * The keyboard has features whose names nobody would guess. "Words on the keys"
 * is the BlackBerry Z10's in-letter prediction and the Octopus keyboard's whole
 * reason for existing, and a user who knows it by either of those names finds
 * nothing at all — which is the report that prompted this. The same is true of
 * every feature another keyboard ships under a different name: Flow, Swype,
 * Blitz, cursor control, key preview.
 *
 * A table rather than a parameter on each of the sixty-odd row builders, so
 * adding a name for a feature is one line in one place and no screen's index
 * has to be touched to give one of its rows a synonym.
 *
 * ## Why this cannot degrade a direct search
 *
 * [MatchField.KEYWORDS] scores below [MatchField.TITLE], so a row whose *name*
 * is the query always outranks a row that merely lists it as a keyword. The
 * words here can therefore only fill in for queries that currently find the
 * wrong row or nothing; they cannot take a query away from the row it already
 * finds. `SettingsSearchRankingTest` holds that promise against the real index.
 *
 * ## What belongs here
 *
 * Names, not descriptions. A keyword earns its place when someone would
 * plausibly type it *instead of* the row's name:
 *
 * * what another keyboard calls the same feature ("flow", "swype", "octopus"),
 * * what the hardware or the platform calls it ("z10", "blackberry"),
 * * the shape of the thing rather than its name ("word on key", "arrow keys"),
 * * the problem it solves, when that is what sends someone looking ("stop
 *   changing my words", "typo").
 *
 * What does not belong: words already in the row's own title or subtitle, which
 * the matcher reads anyway; generic vocabulary that
 * `R.array.search_word_groups` already relates across the whole index; and
 * anything so broad it would answer a question the row cannot ("keyboard",
 * "settings", "text").
 */
@Suppress("MaxLineLength")
internal val SettingsSearchKeywords: Map<Int, Int> = buildMap {
    put(R.string.about_app_language_title, R.string.search_kw_about_app_language)
    put(R.string.about_diagnostics_title, R.string.search_kw_about_diagnostics)
    put(R.string.about_launcher_name_title, R.string.search_kw_about_launcher_name)
    put(R.string.about_licences_title, R.string.search_kw_about_licences)
    put(R.string.about_replay_onboarding_title, R.string.search_kw_about_replay_onboarding)
    put(R.string.about_report_bug_title, R.string.search_kw_about_report_bug)
    put(R.string.about_source_title, R.string.search_kw_about_source)
    put(R.string.about_storage_title, R.string.search_kw_about_storage)
    put(R.string.about_user_guide_title, R.string.search_kw_about_user_guide)
    put(R.string.accessibility_color_vision_title, R.string.search_kw_accessibility_color_vision)
    put(R.string.accessibility_debounce_title, R.string.search_kw_accessibility_debounce)
    put(R.string.accessibility_readable_font_title, R.string.search_kw_accessibility_readable_font)
    put(R.string.accessibility_row_icons_title, R.string.search_kw_accessibility_row_icons)
    put(R.string.accessibility_screen_transitions_title, R.string.search_kw_accessibility_screen_transitions)
    put(R.string.accessibility_talkback_title, R.string.search_kw_accessibility_talkback)
    put(R.string.appearance_key_corner_radius_title, R.string.search_kw_appearance_key_corner_radius)
    put(R.string.appearance_themes_title, R.string.search_kw_appearance_themes)
    put(R.string.appearance_toolbar_hardware_only_title, R.string.search_kw_appearance_toolbar_hardware_only)
    put(R.string.backup_auto_enabled_title, R.string.search_kw_backup_auto_enabled)
    put(R.string.fonts_tool_app_launcher_title, R.string.search_kw_fonts_tool_app_launcher)
    put(R.string.fonts_tool_currency_title, R.string.search_kw_fonts_tool_currency)
    put(R.string.fonts_tool_floating_title, R.string.search_kw_fonts_tool_floating)
    put(R.string.fonts_tool_grammar_title, R.string.search_kw_fonts_tool_grammar)
    put(R.string.fonts_tool_level_title, R.string.search_kw_fonts_tool_level)
    put(R.string.fonts_tool_media_control_title, R.string.search_kw_fonts_tool_media_control)
    put(R.string.fonts_tool_ocr_title, R.string.search_kw_fonts_tool_ocr)
    put(R.string.fonts_tool_one_handed_title, R.string.search_kw_fonts_tool_one_handed)
    put(R.string.fonts_tool_split_title, R.string.search_kw_fonts_tool_split)
    put(R.string.fonts_tool_symbols_title, R.string.search_kw_fonts_tool_symbols)
    put(R.string.fonts_tool_typing_test_title, R.string.search_kw_fonts_tool_typing_test)
    put(R.string.fonts_tool_unit_convert_title, R.string.search_kw_fonts_tool_unit_convert)
    put(R.string.fonts_tool_web_search_title, R.string.search_kw_fonts_tool_web_search)
    put(R.string.home_addons_title, R.string.search_kw_home_addons)
    put(R.string.home_datasaver_title, R.string.search_kw_home_datasaver)
    put(R.string.home_modes_title, R.string.search_kw_home_modes)
    put(R.string.home_privacy_title, R.string.search_kw_home_privacy)
    put(ImeR.string.ime_tool_dictionary, R.string.search_kw_ime_tool_dictionary)
    put(ImeR.string.ime_tool_fancy, R.string.search_kw_ime_tool_fancy)
    put(ImeR.string.ime_tool_gif, R.string.search_kw_ime_tool_gif)
    put(ImeR.string.ime_tool_numpad, R.string.search_kw_ime_tool_numpad)
    put(ImeR.string.ime_tool_persistent, R.string.search_kw_ime_tool_persistent)
    put(ImeR.string.ime_tool_selection_actions, R.string.search_kw_ime_tool_selection_actions)
    put(ImeR.string.ime_tool_resize, R.string.search_kw_ime_tool_resize)
    put(ImeR.string.ime_tool_snippets, R.string.search_kw_ime_tool_snippets)
    put(ImeR.string.ime_tool_sticker, R.string.search_kw_ime_tool_sticker)
    put(ImeR.string.ime_tool_text_edit, R.string.search_kw_ime_tool_text_edit)
    put(ImeR.string.ime_tool_trackpad, R.string.search_kw_ime_tool_trackpad)
    put(ImeR.string.ime_tool_kde_connect, R.string.search_kw_ime_tool_kde_connect)
    put(ImeR.string.ime_tool_translate, R.string.search_kw_ime_tool_translate)
    put(ImeR.string.ime_tool_vocabulary, R.string.search_kw_ime_tool_vocabulary)
    put(ImeR.string.ime_tool_weather, R.string.search_kw_ime_tool_weather)
    put(ImeR.string.ime_tool_wikipedia, R.string.search_kw_ime_tool_wikipedia)
    put(R.string.keypress_all_accents_title, R.string.search_kw_keypress_all_accents)
    put(R.string.keypress_ctrl_raw_title, R.string.search_kw_keypress_ctrl_raw)
    put(R.string.keypress_shifted_popup_title, R.string.search_kw_keypress_shifted_popup)
    put(R.string.keypress_currency_keys_title, R.string.search_kw_keypress_currency_keys)
    put(R.string.keypress_dnd_mute_title, R.string.search_kw_keypress_dnd_mute)
    put(R.string.keypress_long_press_delay_title, R.string.search_kw_keypress_long_press_delay)
    put(R.string.keypress_long_press_hints_title, R.string.search_kw_keypress_long_press_hints)
    put(R.string.keypress_popup_title, R.string.search_kw_keypress_popup)
    put(R.string.keypress_symbols_numpad_title, R.string.search_kw_keypress_symbols_numpad)
    put(R.string.keypress_enter_emoji_title, R.string.search_kw_keypress_enter_emoji)
    put(R.string.langemoji_emoji_animated_title, R.string.search_kw_langemoji_emoji_animated)
    put(R.string.langemoji_emoji_font_title, R.string.search_kw_langemoji_emoji_font)
    put(R.string.langemoji_emoji_hide_unrenderable_title, R.string.search_kw_langemoji_emoji_hide_unrenderable)
    put(R.string.langemoji_emoji_kaomoji_title, R.string.search_kw_langemoji_emoji_kaomoji)
    put(R.string.langemoji_emoji_skin_tone_title, R.string.search_kw_langemoji_emoji_skin_tone)
    put(R.string.langemoji_emoji_sticker_title, R.string.search_kw_langemoji_emoji_sticker)
    put(R.string.langemoji_lang_os_switcher_title, R.string.search_kw_langemoji_lang_os_switcher)
    put(R.string.languages_cjk_fuzzy_title, R.string.search_kw_languages_cjk_fuzzy)
    put(R.string.languages_cjk_lazy_title, R.string.search_kw_languages_cjk_lazy)
    put(R.string.languages_cjk_loose_marks_title, R.string.search_kw_languages_cjk_loose_marks)
    put(R.string.languages_cjk_full_width_space_title, R.string.search_kw_languages_cjk_full_width_space)
    put(R.string.languages_cjk_traditional_title, R.string.search_kw_languages_cjk_traditional)
    put(R.string.languages_conjunct_backspace_title, R.string.search_kw_languages_conjunct_backspace)
    put(R.string.languages_fancy_style_row_title, R.string.search_kw_languages_fancy_style_row)
    put(R.string.languages_translit_hints_row_title, R.string.search_kw_languages_translit_hints_row)
    put(R.string.languages_phonetic_strip_fixed_title, R.string.search_kw_languages_phonetic_strip_fixed)
    put(R.string.languages_phonetic_guide_title, R.string.search_kw_languages_phonetic_guide)
    put(R.string.layout_floating_title, R.string.search_kw_layout_floating)
    put(R.string.layout_globe_emoji_title, R.string.search_kw_layout_globe_emoji)
    put(R.string.layout_key_height_title, R.string.search_kw_layout_key_height)
    put(R.string.layout_keyboard_scale_title, R.string.search_kw_layout_keyboard_scale)
    put(R.string.layout_one_handed_title, R.string.search_kw_layout_one_handed)
    put(R.string.layout_persistent_title, R.string.search_kw_layout_persistent)
    put(R.string.layout_show_globe_title, R.string.search_kw_layout_show_globe)
    put(R.string.layout_globe_recent_title, R.string.search_kw_layout_globe_recent)
    put(R.string.layout_size_position_title, R.string.search_kw_layout_size_position)
    put(R.string.layout_split_title, R.string.search_kw_layout_split)
    put(R.string.plugin_ide_entry_title, R.string.search_kw_plugin_ide)
    put(R.string.privacy_backup_title, R.string.search_kw_privacy_backup)
    put(R.string.privacy_learn_typing_title, R.string.search_kw_privacy_learn_typing)
    put(R.string.privacy_lock_title, R.string.search_kw_privacy_lock)
    put(R.string.selection_macros_title, R.string.search_kw_selection_macros)
    put(R.string.selection_macros_actions_title, R.string.search_kw_selection_macros_actions)
    put(R.string.statistics_title, R.string.search_kw_statistics)
    put(R.string.theme_auto_title, R.string.search_kw_theme_auto)
    put(R.string.theme_shuffle_interval_title, R.string.search_kw_theme_shuffle_interval)
    put(R.string.tooldetail_crypto_enable_title, R.string.search_kw_tooldetail_crypto_enable)
    put(R.string.tooldetail_currency_auto_fetch_title, R.string.search_kw_tooldetail_currency_auto_fetch)
    put(R.string.tooldetail_weather_auto_fetch_title, R.string.search_kw_tooldetail_weather_auto_fetch)
    put(R.string.tooldetail_camera_search_button_title, R.string.search_kw_tooldetail_camera_search_button)
    put(R.string.typing_auto_capitalize_title, R.string.search_kw_typing_auto_capitalize)
    put(R.string.typing_autocorrect_confidence_title, R.string.search_kw_typing_autocorrect_confidence)
    put(R.string.typing_autocorrect_title, R.string.search_kw_typing_autocorrect)
    put(R.string.typing_blacklist_title, R.string.search_kw_typing_blacklist)
    put(R.string.typing_block_offensive_title, R.string.search_kw_typing_block_offensive)
    put(R.string.typing_contact_names_title, R.string.search_kw_typing_contact_names)
    put(R.string.typing_double_space_title, R.string.search_kw_typing_double_space)
    put(R.string.typing_glide_typing_title, R.string.search_kw_typing_glide_typing)
    put(R.string.typing_group_autopilot_title, R.string.search_kw_typing_group_autopilot)
    put(R.string.typing_group_hardware_title, R.string.search_kw_typing_group_hardware)
    put(R.string.typing_group_octopus_title, R.string.search_kw_octopus)
    put(R.string.typing_group_otp_title, R.string.search_kw_typing_group_otp)
    put(R.string.typing_hint_flick_title, R.string.search_kw_typing_hint_flick)
    put(R.string.typing_capital_flick_title, R.string.search_kw_typing_capital_flick)
    put(R.string.layout_globe_guard_title, R.string.search_kw_layout_globe_guard)
    put(R.string.keypress_globe_drag_title, R.string.search_kw_keypress_globe_drag)
    put(R.string.typing_possessive_swipe_title, R.string.search_kw_typing_possessive_swipe)
    put(R.string.typing_hw_mac_title, R.string.search_kw_typing_hw_mac)
    put(R.string.typing_inline_autofill_title, R.string.search_kw_typing_inline_autofill)
    put(R.string.typing_inline_emoji_search_title, R.string.search_kw_typing_inline_emoji_search)
    put(R.string.typing_language_detection_title, R.string.search_kw_typing_language_detection)
    put(R.string.typing_letter_swipe_action_title, R.string.search_kw_typing_letter_swipe_action)
    put(R.string.typing_octopus_enabled_title, R.string.search_kw_octopus)
    put(R.string.typing_octopus_glide_title, R.string.search_kw_octopus_glide)
    put(R.string.typing_personal_dictionary_title, R.string.search_kw_typing_personal_dictionary)
    put(R.string.typing_shift_enter_title, R.string.search_kw_typing_shift_enter)
    put(R.string.typing_smart_currency_title, R.string.search_kw_typing_smart_currency)
    put(R.string.typing_smart_hit_detection_title, R.string.search_kw_typing_smart_hit_detection)
    put(R.string.typing_smart_numbers_title, R.string.search_kw_typing_smart_numbers)
    put(R.string.typing_number_prediction_title, R.string.search_kw_typing_number_prediction)
    put(R.string.typing_smart_replies_title, R.string.search_kw_typing_smart_replies)
    put(R.string.typing_smart_units_title, R.string.search_kw_typing_smart_units)
    put(R.string.typing_space_swipe_down_hide_title, R.string.search_kw_typing_space_swipe_down_hide)
    put(R.string.typing_suggestions_title, R.string.search_kw_typing_suggestions)
    put(R.string.typing_glide_start_radius_title, R.string.search_kw_typing_glide_start_radius)
    put(R.string.typing_glide_dwell_title, R.string.search_kw_typing_glide_dwell)
    put(R.string.typing_glide_loop_title, R.string.search_kw_typing_glide_loop)
    put(R.string.typing_glide_wiggle_title, R.string.search_kw_typing_glide_wiggle)
    put(R.string.typing_glide_end_radius_title, R.string.search_kw_typing_glide_end_radius)
    put(R.string.typing_glide_near_radius_title, R.string.search_kw_typing_glide_near_radius)
    put(R.string.typing_swipe_start_distance_title, R.string.search_kw_typing_swipe_start_distance)
    put(R.string.typing_undo_autocorrect_title, R.string.search_kw_typing_undo_autocorrect)
    put(R.string.typing_auto_close_brackets_title, R.string.search_kw_typing_auto_close_brackets)
    put(R.string.typing_wrap_selection_title, R.string.search_kw_typing_wrap_selection)
    put(R.string.update_row_check_title, R.string.search_kw_update_row_check)
    put(R.string.tooldetail_sticker_suggest_title, R.string.search_kw_sticker_suggest)
    put(R.string.tooldetail_translate_engine_title, R.string.search_kw_translate_engine)
    put(R.string.tooldetail_translate_models_group, R.string.search_kw_translate_models)
    put(R.string.tooldetail_translate_downloaded_first_title, R.string.search_kw_translate_downloaded_first)
    put(R.string.tooldetail_translate_only_downloaded_title, R.string.search_kw_translate_only_downloaded)
    put(R.string.tooldetail_deepl_key_label, R.string.search_kw_deepl)
    put(R.string.voice_engine_title, R.string.search_kw_voice_engine)
}

/** The keywords registered for [title], or 0 when it has none. */
@StringRes
internal fun searchKeywordsFor(@StringRes title: Int): Int = SettingsSearchKeywords[title] ?: 0

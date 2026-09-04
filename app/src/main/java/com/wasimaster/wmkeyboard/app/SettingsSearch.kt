package com.wasimaster.wmkeyboard.app

import android.content.res.Resources
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool

/**
 * How much an entry is worth next to the others, as a percentage applied to
 * its raw match score.
 *
 * Two entries can match a word equally well and still deserve very different
 * places in the list. "Themes" names both the themes screen and the toolbar
 * shortcut that opens the theme picker, and someone searching *settings*
 * wants the screen. The backup screen, meanwhile, has a toggle named after
 * nearly every feature ("Sticker packs", "Include API keys") and none of
 * them is the feature itself.
 */
internal enum class EntryWeight(val percent: Int) {
    /** A destination screen: the whole feature lives behind it. */
    SECTION(200),

    /** An ordinary setting row, or a tool's own page. */
    NORMAL(100),

    /**
     * A row that only points at a setting whose real home is another screen:
     * the backup screen's per-feature toggles, and the "All … settings"
     * shortcuts on the tool pages.
     */
    MIRROR(40),
}

/**
 * One searchable setting: what it is called, where it lives, and the route
 * that opens the screen holding it.
 *
 * [title], [subtitle] and [screen] are plain text so that the ranking below
 * stays ordinary Kotlin. They are resolved once, by [settingsSearchIndex],
 * from the very resources the settings screens draw, so the index reads in
 * the same language the user sees.
 *
 * [titleRes] is the id behind [title]. It travels to [SettingsHighlight], which
 * matches a row by its resource rather than by its words: an id survives
 * translation, and the drawn text does not.
 */
internal data class SettingsSearchEntry(
    val title: String,
    val subtitle: String,
    /** Breadcrumb shown under the result, e.g. "Tools › Camera". */
    val screen: String,
    val route: String,
    val weight: EntryWeight = EntryWeight.NORMAL,
    /** Set on the tool entries, so a result can draw the tool's own icon. */
    val tool: ToolbarTool? = null,
    @StringRes val titleRes: Int = 0,
    /**
     * Words the entry is found by and never draws: "dark mode" for the themes
     * screen, "new phone" for backup. Only the destination screens carry them,
     * because a word on every row of a screen tells the ranking nothing.
     */
    val keywords: String = "",
) {
    /**
     * The four fields in the form the matcher compares against, built on the
     * first search that reaches this entry and kept for the rest of the screen.
     */
    internal val searchText: List<SearchText> by lazy {
        listOf(
            SearchText(MatchField.TITLE, title),
            SearchText(MatchField.KEYWORDS, keywords),
            SearchText(MatchField.SCREEN, screen),
            SearchText(MatchField.SUBTITLE, subtitle),
        )
    }
}

/** What separates the parts of a breadcrumb. Punctuation, not words. */
private const val CRUMB_SEPARATOR = " › "

/** Joins the breadcrumb parts that are set, outermost first. */
private fun Resources.crumb(vararg parts: Int): String =
    parts.filter { it != 0 }.joinToString(CRUMB_SEPARATOR) { getString(it) }

/**
 * Builds one entry from the resources the owning screen draws.
 *
 * [screen] names the screen the row sits on, [screenParent] the screen above
 * it and [screenRoot] the one above that. Leave the two outer ones at 0 for a
 * row on a top-level screen.
 */
private fun Resources.entry(
    @StringRes title: Int,
    @StringRes subtitle: Int = 0,
    @StringRes screen: Int = 0,
    route: String = "",
    @StringRes screenParent: Int = 0,
    @StringRes screenRoot: Int = 0,
    weight: EntryWeight = EntryWeight.NORMAL,
    @StringRes keywords: Int = 0,
): SettingsSearchEntry = SettingsSearchEntry(
    title = getString(title),
    subtitle = if (subtitle == 0) "" else getString(subtitle),
    screen = crumb(screenRoot, screenParent, screen),
    route = route,
    weight = weight,
    titleRes = title,
    keywords = if (keywords == 0) "" else getString(keywords),
)

/**
 * Builds an entry on a tool's own page. The breadcrumb and the route both come
 * from the tool, so a renamed tool never desynchronises from its entries, and
 * the tool's name is read from [toolTitle] rather than copied here.
 */
private fun Resources.toolEntry(
    tool: ToolbarTool,
    @StringRes title: Int,
    @StringRes subtitle: Int = 0,
    weight: EntryWeight = EntryWeight.NORMAL,
): SettingsSearchEntry = SettingsSearchEntry(
    title = getString(title),
    subtitle = if (subtitle == 0) "" else getString(subtitle),
    screen = crumb(R.string.home_tools_title, toolTitle(tool)),
    route = "tool/${tool.name}",
    weight = weight,
    tool = tool,
    titleRes = title,
)

/** Rows on the Typing screen, in screen order. */
private fun Resources.typingRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_typing_title, "typing")
    return listOfNotNull(
        row(R.string.typing_autocorrect_title, R.string.typing_autocorrect_subtitle),
        row(R.string.typing_autocorrect_confidence_title, R.string.typing_autocorrect_confidence_subtitle),
        row(R.string.typing_autocorrect_adaptive_title, R.string.typing_autocorrect_adaptive_subtitle),
        row(R.string.typing_timing_signal_title, R.string.typing_timing_signal_subtitle),
        row(R.string.typing_register_priors_title, R.string.typing_register_priors_subtitle),
        row(R.string.typing_undo_autocorrect_title, R.string.typing_undo_autocorrect_subtitle),
        row(R.string.typing_skip_all_caps_title, R.string.typing_skip_all_caps_subtitle),
        row(R.string.typing_block_offensive_title, R.string.typing_block_offensive_subtitle),
        row(R.string.typing_context_rerank_title, R.string.typing_context_rerank_subtitle),
        row(R.string.typing_autocorrect_splits_title, R.string.typing_autocorrect_splits_subtitle),
        row(
            R.string.typing_language_detection_title,
            R.string.typing_language_detection_subtitle,
        ),
        row(R.string.typing_language_detection_strength_title),
        row(
            R.string.typing_number_row_corrections_title,
            R.string.typing_number_row_corrections_subtitle,
        ),
        row(R.string.typing_auto_apostrophe_title, R.string.typing_auto_apostrophe_subtitle),
        row(R.string.typing_auto_capitalize_title, R.string.typing_auto_capitalize_subtitle),
        row(R.string.typing_double_space_period_title, R.string.typing_double_space_period_subtitle),
        row(R.string.typing_double_space_window_title, R.string.typing_double_space_window_subtitle),
        row(R.string.typing_double_space_tab_title, R.string.typing_double_space_tab_subtitle),
        row(R.string.typing_auto_space_punctuation_title, R.string.typing_auto_space_punctuation_subtitle),
        row(R.string.typing_space_after_suggestion_title, R.string.typing_space_after_suggestion_subtitle),
        row(R.string.typing_wrap_selection_title, R.string.typing_wrap_selection_subtitle),
        row(R.string.typing_shift_recase_title, R.string.typing_shift_recase_subtitle),
        row(R.string.typing_suggestions_title, R.string.typing_suggestions_subtitle),
        row(R.string.typing_suggestion_slots_title, R.string.typing_suggestion_slots_subtitle),
        row(R.string.typing_suggestion_scroll_title, R.string.typing_suggestion_scroll_subtitle),
        row(R.string.typing_punctuation_suggestions_title, R.string.typing_punctuation_suggestions_subtitle),
        row(R.string.typing_suggestions_all_fields_title, R.string.typing_suggestions_all_fields_subtitle),
        row(R.string.typing_learn_threshold_title, R.string.typing_learn_threshold_subtitle),
        row(R.string.typing_new_word_sightings_title, R.string.typing_new_word_sightings_subtitle),
        row(R.string.typing_ask_before_learning_title, R.string.typing_ask_before_learning_subtitle),
        row(R.string.typing_offer_near_miss_title, R.string.typing_offer_near_miss_subtitle),
        row(R.string.typing_suggestions_first_title, R.string.typing_suggestions_first_subtitle),
        row(R.string.typing_primary_center_title, R.string.typing_primary_center_subtitle),
        row(R.string.typing_contact_names_title, R.string.typing_contact_names_subtitle),
        row(R.string.typing_contact_emails_title, R.string.typing_contact_emails_subtitle),
        row(R.string.typing_contact_emails_in_email_fields_title, R.string.typing_contact_emails_in_email_fields_subtitle),
        row(R.string.typing_app_names_title, R.string.typing_app_names_subtitle),
        row(R.string.typing_inline_emoji_search_title, R.string.typing_inline_emoji_search_subtitle),
        row(R.string.typing_inline_autofill_title, R.string.typing_inline_autofill_subtitle),
        row(R.string.typing_smart_replies_title, R.string.typing_smart_replies_subtitle),
        row(R.string.typing_smart_hit_detection_title, R.string.typing_smart_hit_detection_subtitle),
        // Personal dictionary, Custom dictionaries and Suggestion blacklist are
        // rows on this screen too, but each only opens a screen of its own. They
        // are indexed once, in sectionRows, pointing straight at that screen: a
        // second entry that lands on Typing and flashes the row would be a
        // near-identical result one line below the useful one.
        row(R.string.typing_smart_chips_title, R.string.typing_smart_chips_subtitle),
        row(R.string.typing_smart_calc_title, R.string.typing_smart_calc_subtitle),
        row(R.string.typing_smart_currency_title),
        row(R.string.typing_smart_units_title, R.string.typing_smart_units_subtitle),
        row(R.string.typing_smart_tool_keywords_title, R.string.typing_smart_tool_keywords_subtitle),
        row(R.string.typing_smart_dates_title, R.string.typing_smart_dates_subtitle),
        row(R.string.typing_smart_weather_title, R.string.typing_smart_weather_subtitle),
        row(R.string.typing_smart_lookups_title, R.string.typing_smart_lookups_subtitle),
        row(R.string.typing_smart_intents_title, R.string.typing_smart_intents_subtitle),
        row(R.string.typing_smart_gifs_title, R.string.typing_smart_gifs_subtitle),
        row(R.string.typing_otp_chip_title, R.string.typing_otp_chip_subtitle),
        row(R.string.typing_otp_access_title, R.string.typing_otp_access_subtitle),
        row(R.string.typing_otp_number_fields_title, R.string.typing_otp_number_fields_subtitle),
        row(R.string.typing_otp_expiry_title, R.string.typing_otp_expiry_subtitle),
        row(R.string.typing_otp_dismiss_title, R.string.typing_otp_dismiss_subtitle),
        row(R.string.typing_otp_per_digit_title, R.string.typing_otp_per_digit_subtitle),
        row(R.string.typing_glide_typing_title, R.string.typing_glide_typing_subtitle),
        row(R.string.typing_glide_picker_title, R.string.typing_glide_picker_subtitle),
        // Full builds only: the handwriting recognizer is an ML Kit feature.
        if (BuildConfig.ENABLE_ML_KIT_HANDWRITING) {
            row(R.string.typing_letter_swipe_action_title, R.string.typing_letter_swipe_action_subtitle)
        } else {
            null
        },
        row(R.string.typing_space_glide_multiword_title, R.string.typing_space_glide_multiword_subtitle),
        row(R.string.typing_space_after_glide_title, R.string.typing_space_after_glide_subtitle),
        row(R.string.typing_glide_apostrophe_title, R.string.typing_glide_apostrophe_subtitle),
        row(R.string.typing_glide_apostrophe_s_title, R.string.typing_glide_apostrophe_s_subtitle),
        row(R.string.typing_swipe_start_distance_title, R.string.typing_swipe_start_distance_subtitle),
        row(R.string.typing_gesture_cooldown_title, R.string.typing_gesture_cooldown_subtitle),
        // "Extra time for a dot or a cross" is left out on purpose: it is drawn
        // only while the letter swipe writes by hand, which is neither the
        // default nor a state a search result can put the screen into.
        row(R.string.typing_trail_width_title, R.string.typing_trail_width_subtitle),
        row(R.string.typing_trail_length_title, R.string.typing_trail_length_subtitle),
        row(R.string.typing_trail_opacity_title),
        row(R.string.typing_space_short_swipe_title, R.string.typing_space_short_swipe_subtitle),
        row(R.string.typing_space_long_swipe_title, R.string.typing_space_long_swipe_subtitle),
        row(R.string.typing_space_cursor_step_title, R.string.typing_space_cursor_step_subtitle),
        row(R.string.typing_space_cursor_2d_title, R.string.typing_space_cursor_2d_subtitle),
        row(R.string.typing_space_swipe_down_hide_title, R.string.typing_space_swipe_down_hide_subtitle),
        row(R.string.typing_spacebar_language_arrows_title, R.string.typing_spacebar_language_arrows_subtitle),
        row(R.string.typing_spacebar_display_title, R.string.typing_spacebar_display_subtitle),
        row(R.string.typing_spacebar_text_label),
        row(R.string.typing_backspace_swipe_title, R.string.typing_backspace_swipe_subtitle),
        row(R.string.typing_backspace_step_title, R.string.typing_backspace_step_subtitle),
        row(R.string.typing_shift_enter_title, R.string.typing_shift_enter_subtitle),
        row(R.string.typing_volume_cursor_title, R.string.typing_volume_cursor_subtitle),
        row(R.string.typing_volume_cursor_media_title, R.string.typing_volume_cursor_media_subtitle),
        row(R.string.typing_hardware_input_title, R.string.typing_hardware_input_subtitle),
        row(R.string.typing_hw_shortcuts_title, R.string.typing_hw_shortcuts_subtitle),
        row(R.string.typing_hw_panel_nav_title, R.string.typing_hw_panel_nav_subtitle),
        row(R.string.typing_hw_esc_title, R.string.typing_hw_esc_subtitle),
        row(R.string.typing_hw_digit_chord_title, R.string.typing_hw_digit_chord_subtitle),
        row(R.string.typing_hw_lang_chord_title, R.string.typing_hw_lang_chord_subtitle),
        row(R.string.typing_hw_modifier_words_title, R.string.typing_hw_modifier_words_subtitle),
        row(R.string.typing_hw_picker_timeout_title, R.string.typing_hw_picker_timeout_subtitle),
        row(R.string.typing_hw_suggestion_hotkeys_title, R.string.typing_hw_suggestion_hotkeys_subtitle),
        row(R.string.typing_hw_suggestion_hints_title, R.string.typing_hw_suggestion_hints_subtitle),
        row(R.string.typing_hw_mac_title, R.string.typing_hw_mac_subtitle),
        row(R.string.typing_hw_auto_show_title, R.string.typing_hw_auto_show_subtitle),
    )
}

/** Rows on the Key press screen. */
private fun Resources.keyPressRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_keypress_title, "keypress")
    return listOf(
        row(R.string.keypress_haptics_title, R.string.keypress_haptics_subtitle),
        row(R.string.keypress_haptic_strength_title, R.string.keypress_haptic_strength_subtitle),
        row(R.string.keypress_haptic_intensity_title, R.string.keypress_haptic_intensity_subtitle),
        row(R.string.keypress_long_press_haptics_title, R.string.keypress_long_press_haptics_subtitle),
        row(R.string.keypress_long_press_release_title, R.string.keypress_long_press_release_subtitle),
        row(R.string.keypress_vibrate_space_title, R.string.keypress_vibrate_space_subtitle),
        row(R.string.keypress_vibrate_delete_swipe_title, R.string.keypress_vibrate_delete_swipe_subtitle),
        row(R.string.keypress_vibrate_repeat_title, R.string.keypress_vibrate_repeat_subtitle),
        row(R.string.keypress_dnd_mute_title, R.string.keypress_dnd_mute_subtitle),
        row(R.string.hardware_sound_key_title, R.string.hardware_sound_key_subtitle),
        row(R.string.hardware_sound_volume_title, R.string.hardware_sound_volume_subtitle),
        row(R.string.keypress_popup_title, R.string.keypress_popup_subtitle),
        row(R.string.keypress_popup_numeric_title, R.string.keypress_popup_numeric_subtitle),
        row(R.string.keypress_popup_min_duration_title, R.string.keypress_popup_min_duration_subtitle),
        row(R.string.keypress_popup_max_duration_title, R.string.keypress_popup_max_duration_subtitle),
        row(R.string.keypress_popup_on_key_title, R.string.keypress_popup_on_key_subtitle),
        row(R.string.keypress_popup_font_size_title, R.string.keypress_popup_font_size_subtitle),
        row(R.string.keypress_popup_height_title, R.string.keypress_popup_height_subtitle),
        row(R.string.keypress_popup_offset_y_title, R.string.keypress_popup_offset_y_subtitle),
        row(R.string.keypress_popup_offset_x_title, R.string.keypress_popup_offset_x_subtitle),
        row(R.string.keypress_popup_background_title, R.string.keypress_popup_background_subtitle),
        row(R.string.keypress_popup_text_color_title, R.string.keypress_popup_text_color_subtitle),
        row(R.string.keypress_popup_shape_title, R.string.keypress_popup_shape_subtitle),
        row(R.string.keypress_popup_radius_title, R.string.keypress_popup_radius_subtitle),
        row(R.string.keypress_long_press_delay_title, R.string.keypress_long_press_delay_subtitle),
        row(R.string.keypress_delete_repeat_title, R.string.keypress_delete_repeat_subtitle),
        row(R.string.keypress_space_repeat_title, R.string.keypress_space_repeat_subtitle),
        row(R.string.keypress_caps_lock_title, R.string.keypress_caps_lock_subtitle),
        row(R.string.keypress_long_press_hints_title, R.string.keypress_long_press_hints_subtitle),
        row(R.string.keypress_all_accents_title, R.string.keypress_all_accents_subtitle),
        row(R.string.keypress_symbols_numpad_title, R.string.keypress_symbols_numpad_subtitle),
        row(R.string.keypress_currency_keys_title),
        row(R.string.keypress_ctrl_raw_title, R.string.keypress_ctrl_raw_subtitle),
        row(R.string.keypress_hold_a_title, R.string.keypress_hold_a_subtitle),
        row(R.string.keypress_hold_c_title, R.string.keypress_hold_c_subtitle),
        row(R.string.keypress_hold_x_title, R.string.keypress_hold_x_subtitle),
        row(R.string.keypress_hold_v_title, R.string.keypress_hold_v_subtitle),
        row(R.string.keypress_hold_z_title, R.string.keypress_hold_z_subtitle),
        row(R.string.keypress_hold_y_title, R.string.keypress_hold_y_subtitle),
    )
}

/** Rows on Appearance and the two screens that hang off it. */
private fun Resources.photoRows(): List<SettingsSearchEntry> {
    fun services(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.photo_services_title, "photos",
        screenParent = R.string.home_screen_theme_edit_title,
        screenRoot = R.string.home_appearance_title,
    )
    fun rotation(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.photo_rotation_title, "photo_rotation",
        screenParent = R.string.home_screen_theme_edit_title,
        screenRoot = R.string.home_appearance_title,
    )
    fun library(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.photo_library_title, "photo_library",
        screenParent = R.string.photo_rotation_title,
        screenRoot = R.string.home_appearance_title,
    )
    return listOf(
        services(R.string.photo_unsplash_key_label),
        services(R.string.photo_pexels_key_label),
        rotation(R.string.photo_rotation_on_title, R.string.photo_rotation_on_subtitle),
        rotation(R.string.photo_rotation_interval_title),
        rotation(R.string.photo_rotation_shuffle_title, R.string.photo_rotation_shuffle_subtitle),
        rotation(R.string.photo_rotation_source_saved_title, R.string.photo_rotation_source_saved_subtitle),
        rotation(R.string.photo_rotation_source_online_title, R.string.photo_rotation_source_online_subtitle),
        rotation(R.string.photo_rotation_topics_title, R.string.photo_rotation_topics_subtitle),
        rotation(R.string.photo_rotation_terms_label),
        rotation(R.string.photo_rotation_safe_title, R.string.photo_rotation_safe_subtitle),
        rotation(R.string.photo_rotation_wide_title, R.string.photo_rotation_wide_subtitle),
        rotation(R.string.photo_rotation_metered_title, R.string.photo_rotation_metered_subtitle),
        rotation(R.string.photo_rotation_pool_title),
        rotation(R.string.photo_rotation_scope_title),
        rotation(R.string.photo_rotation_scope_pick_title),
        rotation(
            R.string.photo_rotation_delete_downloads_title,
            R.string.photo_rotation_delete_downloads_subtitle,
        ),
        library(R.string.photo_add_device_title, R.string.photo_add_device_subtitle),
        library(R.string.photo_storage_title),
    )
}

private fun Resources.appearanceRows(): List<SettingsSearchEntry> {
    fun theme(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.home_screen_themes_title, "themes",
        screenParent = R.string.home_appearance_title,
    )
    fun icon(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.home_screen_icons_title, "icons",
        screenParent = R.string.home_appearance_title,
    )
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_appearance_title, "appearance")
    return listOf(
        // Keyboard themes, Keyboard font and Icons are rows here, but each
        // opens its own screen. They are indexed once in sectionRows,
        // pointing at that screen.
        theme(R.string.theme_auto_title, R.string.theme_auto_subtitle),
        theme(R.string.theme_auto_light_title),
        theme(R.string.theme_auto_dark_title),
        theme(R.string.theme_auto_light_from_title),
        theme(R.string.theme_auto_dark_from_title),
        theme(R.string.theme_shuffle_interval_title, R.string.theme_shuffle_interval_subtitle),
        theme(R.string.theme_shuffle_now_title, R.string.theme_shuffle_now_subtitle),
        theme(R.string.theme_background_image_landscape_title),
        icon(R.string.plugins_icons_pack_title),
        icon(R.string.plugins_icons_import_title, R.string.plugins_icons_import_subtitle),
        icon(R.string.plugins_icons_reset_title, R.string.plugins_icons_reset_subtitle),
        icon(R.string.plugins_icons_picker_use_svg_action),
        row(R.string.appearance_key_corner_radius_title, R.string.appearance_key_corner_radius_subtitle),
        row(R.string.appearance_key_label_size_title, R.string.appearance_key_label_size_subtitle),
        row(R.string.appearance_key_hint_size_title, R.string.appearance_key_hint_size_subtitle),
        row(R.string.appearance_toolbar_show_title, R.string.appearance_toolbar_show_subtitle),
        row(R.string.appearance_toolbar_placement_title, R.string.appearance_toolbar_placement_subtitle),
        row(R.string.appearance_toolbar_swipe_down_title, R.string.appearance_toolbar_swipe_down_subtitle),
        row(R.string.appearance_toolbar_hardware_only_title, R.string.appearance_toolbar_hardware_only_subtitle),
        row(R.string.appearance_toolbar_rtl_title, R.string.appearance_toolbar_rtl_subtitle),
        row(R.string.appearance_toolbar_spread_title, R.string.appearance_toolbar_spread_subtitle),
        row(R.string.appearance_toolbar_height_title, R.string.appearance_toolbar_height_subtitle),
        row(R.string.appearance_toolbar_scroll_title, R.string.appearance_toolbar_scroll_subtitle),
        row(R.string.appearance_toolbar_lock_title, R.string.appearance_toolbar_lock_subtitle),
        row(R.string.appearance_toolbar_labels_title, R.string.appearance_toolbar_labels_subtitle),
        row(R.string.appearance_toolbar_label_size_title, R.string.appearance_toolbar_label_size_subtitle),
        row(R.string.appearance_suggestion_text_size_title, R.string.appearance_suggestion_text_size_subtitle),
        row(R.string.appearance_suggestion_spacing_title, R.string.appearance_suggestion_spacing_subtitle),
        row(R.string.home_reset_pinned_tools_title, R.string.home_reset_pinned_tools_subtitle),
        row(R.string.appearance_tool_circle_title, R.string.appearance_tool_circle_subtitle),
        row(R.string.appearance_tool_shape_title, R.string.appearance_tool_shape_subtitle),
        row(R.string.appearance_tool_width_title, R.string.appearance_tool_width_subtitle),
        row(R.string.appearance_toolbox_layout_title, R.string.appearance_toolbox_layout_subtitle),
        row(R.string.appearance_toolbox_columns_title, R.string.appearance_toolbox_columns_subtitle),
        row(R.string.appearance_toolbox_pill_columns_title, R.string.appearance_toolbox_pill_columns_subtitle),
        row(R.string.appearance_toolbox_pill_filled_title, R.string.appearance_toolbox_pill_filled_subtitle),
        row(R.string.appearance_toolbox_paginate_title, R.string.appearance_toolbox_paginate_subtitle),
        row(R.string.appearance_toolbox_page_size_title, R.string.appearance_toolbox_page_size_subtitle),
    )
}

/** Rows on Layout & size, plus the two rows that moved off it. */
private fun Resources.layoutRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_layout_title, "layout")
    return listOf(
        row(R.string.layout_number_row_title, R.string.layout_number_row_subtitle),
        row(R.string.layout_number_row_height_title, R.string.layout_number_row_height_subtitle),
        row(R.string.layout_number_row_shift_symbols_title, R.string.layout_number_row_shift_symbols_subtitle),
        row(R.string.layout_number_row_in_symbols_title, R.string.layout_number_row_in_symbols_subtitle),
        row(R.string.layout_symbols_return_title, R.string.layout_symbols_return_subtitle),
        row(R.string.layout_symbols_return_chars_title),
        row(R.string.layout_numeral_scope_title, R.string.layout_numeral_scope_subtitle),
        row(R.string.layout_key_height_title, R.string.layout_key_height_subtitle),
        row(R.string.layout_bottom_row_height_title, R.string.layout_bottom_row_height_subtitle),
        row(R.string.layout_side_padding_title, R.string.layout_side_padding_subtitle),
        row(R.string.layout_key_spacing_title, R.string.layout_key_spacing_subtitle),
        row(R.string.layout_keyboard_scale_title, R.string.layout_keyboard_scale_subtitle),
        row(R.string.layout_bottom_padding_title, R.string.layout_bottom_padding_subtitle),
        row(R.string.layout_keyboard_width_title, R.string.layout_keyboard_width_subtitle),
        row(R.string.layout_keyboard_position_title, R.string.layout_keyboard_position_info),
        row(R.string.layout_variant_follows_portrait_label),
        row(R.string.layout_font_size_title),
        row(R.string.layout_follow_portrait_title),
        row(R.string.layout_one_handed_title, R.string.layout_one_handed_subtitle),
        // The width, the height and the side of the one-handed keyboard name
        // the orientation they belong to ("Portrait width"), so their titles
        // are format strings. The index has no orientation to put in one, and a
        // result reading "%1$s width" is worse than no result: the row above
        // opens the same group.
        row(R.string.layout_split_title, R.string.layout_split_subtitle),
        row(R.string.layout_split_gap_title, R.string.layout_split_gap_subtitle),
        row(R.string.layout_floating_title, R.string.layout_floating_subtitle),
        row(R.string.layout_floating_width_title, R.string.layout_floating_width_subtitle),
        row(R.string.layout_comma_emoji_title, R.string.layout_comma_emoji_subtitle),
        row(R.string.layout_globe_emoji_title, R.string.layout_globe_emoji_subtitle),
        row(R.string.layout_swap_comma_globe_title, R.string.layout_swap_comma_globe_subtitle),
        entry(R.string.layout_editor_import_title, R.string.layout_editor_import_subtitle, R.string.home_keymaps_title, "keymaps"),
        entry(
            R.string.layout_editor_tablet_expand_title,
            R.string.layout_editor_tablet_expand_subtitle,
            R.string.home_keymaps_title,
            "keymaps",
        ),
    )
}

/**
 * Rows on the Languages screen, and the ones on a language's own page.
 *
 * A language page opens from the list with the language in the route, and the
 * index has no language to put there, so every row lands on the list. That is
 * still the screen the setting is behind, and the list is one press from it.
 */
private fun Resources.languageRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_languages_title, "languages")
    return listOf(
        row(R.string.langemoji_lang_add_title),
        row(R.string.langemoji_lang_keymaps_title, R.string.langemoji_lang_keymaps_subtitle),
        row(R.string.langemoji_lang_per_app_toggle_title, R.string.langemoji_lang_per_app_toggle_subtitle),
        row(R.string.langemoji_lang_os_switcher_title, R.string.langemoji_lang_os_switcher_subtitle),
        row(R.string.langemoji_lang_app_name_first_title, R.string.langemoji_lang_app_name_first_subtitle),
        row(R.string.langemoji_lang_subtype_enabler_title, R.string.langemoji_lang_subtype_enabler_subtitle),
        entry(
            R.string.languages_more_layouts_title,
            screen = R.string.home_languages_title,
            route = "languages",
            keywords = R.string.search_kw_more_layouts,
        ),
        row(R.string.languages_conjunct_backspace_title),
        row(R.string.languages_fancy_style_row_title, R.string.languages_fancy_style_row_subtitle),
        // The subtitle names the language it is about, so it is a format string
        // with nothing to fill it in here. The title carries the search anyway.
        row(R.string.languages_numeral_system_title),
        row(R.string.languages_custom_dictionaries_title, R.string.languages_custom_dictionaries_subtitle),
        row(R.string.languages_emoji_keywords_title, R.string.languages_emoji_keywords_subtitle),
        row(R.string.languages_cjk_traditional_title, R.string.languages_cjk_traditional_subtitle),
        row(R.string.languages_cjk_lazy_title, R.string.languages_cjk_lazy_subtitle),
        row(R.string.languages_cjk_fuzzy_title, R.string.languages_cjk_fuzzy_subtitle),
    )
}

/** Rows on the Emoji screen. */
private fun Resources.emojiRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_emoji_title, "emoji")
    return listOf(
        row(R.string.langemoji_emoji_toolbar_title, R.string.langemoji_emoji_toolbar_subtitle),
        row(R.string.langemoji_emoji_full_bleed_title, R.string.langemoji_emoji_full_bleed_subtitle),
        row(R.string.langemoji_emoji_prediction_title, R.string.langemoji_emoji_prediction_subtitle),
        row(R.string.langemoji_emoji_insert_mode_title, R.string.langemoji_emoji_insert_mode_subtitle),
        row(R.string.langemoji_emoji_grid_size_title, R.string.langemoji_emoji_grid_size_subtitle),
        row(R.string.langemoji_emoji_size_title, R.string.langemoji_emoji_size_subtitle),
        row(R.string.langemoji_emoji_tab_mode_title, R.string.langemoji_emoji_tab_mode_subtitle),
        row(R.string.langemoji_emoji_clear_recents_title, R.string.langemoji_emoji_clear_recents_subtitle),
        row(R.string.langemoji_emoji_kaomoji_title, R.string.langemoji_emoji_kaomoji_subtitle),
        row(R.string.langemoji_emoji_long_press_name_title, R.string.langemoji_emoji_long_press_name_subtitle),
        row(R.string.langemoji_emoji_animated_title, R.string.langemoji_emoji_animated_subtitle),
        row(R.string.langemoji_emoji_sticker_title, R.string.langemoji_emoji_sticker_subtitle),
        row(R.string.langemoji_emoji_row_title, R.string.langemoji_emoji_bar_mode_subtitle),
        row(R.string.langemoji_emoji_bar_content_title, R.string.langemoji_emoji_bar_content_subtitle),
        row(R.string.langemoji_emoji_bar_count_title, R.string.langemoji_emoji_bar_count_subtitle),
        row(R.string.langemoji_emoji_bar_scroll_title, R.string.langemoji_emoji_bar_scroll_subtitle),
        row(R.string.langemoji_emoji_font_title, R.string.langemoji_emoji_font_subtitle),
        row(R.string.langemoji_emoji_skin_tone_title, R.string.langemoji_emoji_skin_tone_subtitle),
        row(R.string.langemoji_emoji_tone_override_title, R.string.langemoji_emoji_tone_override_subtitle),
        row(R.string.langemoji_emoji_close_after_insert_title, R.string.langemoji_emoji_close_after_insert_subtitle),
        // One title in every configuration now: an emoji the chosen font lacks
        // is drawn in the phone's own font rather than hidden, so the toggle is
        // always about the phone.
        row(R.string.langemoji_emoji_hide_unrenderable_title, R.string.langemoji_emoji_hide_unrenderable_subtitle),
    )
}

/** Rows on the Voice typing screen. */
private fun Resources.voiceRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_voice_title, "voice")
    return listOf(
        row(R.string.voice_engine_title, R.string.voice_engine_subtitle),
        row(R.string.voice_ui_title, R.string.voice_ui_subtitle),
        row(R.string.voice_typing_title, R.string.voice_typing_subtitle),
        row(R.string.voice_hold_title, R.string.voice_hold_subtitle),
        row(R.string.voice_continuous_title, R.string.voice_continuous_subtitle),
        row(R.string.voice_punctuation_title, R.string.voice_punctuation_subtitle),
        row(R.string.voice_translate_title, R.string.voice_translate_subtitle),
    )
}

/** Rows on the Clipboard screen. */
private fun Resources.clipboardRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_clipboard_title, "clipboard")
    return listOf(
        row(R.string.clipboard_history_title, R.string.clipboard_history_subtitle),
        row(R.string.clipboard_suggest_recent_title, R.string.clipboard_suggest_recent_subtitle),
        row(R.string.clipboard_chip_life_title, R.string.clipboard_chip_life_subtitle),
        row(R.string.clipboard_suggest_codes_title, R.string.clipboard_suggest_codes_subtitle),
        row(R.string.clipboard_toast_title, R.string.clipboard_toast_subtitle),
        row(R.string.clipboard_expiry_title, R.string.clipboard_expiry_subtitle),
        row(R.string.clipboard_max_title, R.string.clipboard_max_subtitle),
        row(R.string.clipboard_bottom_row_title, R.string.clipboard_bottom_row_subtitle),
        row(R.string.clipboard_full_bleed_title, R.string.clipboard_full_bleed_subtitle),
        row(R.string.clipboard_pinned_last_title, R.string.clipboard_pinned_last_subtitle),
        row(R.string.clipboard_search_title, R.string.clipboard_search_subtitle),
        row(R.string.clipboard_entities_title, R.string.clipboard_entities_subtitle),
        row(R.string.clipboard_password_paste_title, R.string.clipboard_password_paste_subtitle),
        row(R.string.clipboard_link_previews_title, R.string.clipboard_link_previews_subtitle),
        row(R.string.clipboard_screenshots_title, R.string.clipboard_screenshots_subtitle),
        row(R.string.clipboard_track_source_title, R.string.clipboard_track_source_subtitle),
        row(R.string.clipboard_sensitive_title, R.string.clipboard_sensitive_subtitle),
        row(R.string.clipboard_detect_sensitive_title, R.string.clipboard_detect_sensitive_subtitle),
        row(R.string.clipboard_sensitive_expiry_title, R.string.clipboard_sensitive_expiry_subtitle),
    )
}

/**
 * Rows on the Text Expander screen.
 *
 * The snippets themselves are not here: they are the user's own text, and the
 * index is built once per screen from resources. The two reference cards are,
 * because template variables and patterns are what someone looking for the
 * feature actually types.
 */
private fun Resources.expanderRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_expander_title, "expander")
    return listOf(
        row(R.string.expander_variables_title),
        row(R.string.expander_pattern_title),
        row(R.string.expander_multi_expand_title, R.string.expander_multi_expand_subtitle),
        row(R.string.expander_add_action),
        row(R.string.expander_reorder_title),
        // Espanso is the name somebody arriving from that app will search for,
        // and it appears nowhere else in the index.
        row(R.string.expander_import_source_title, R.string.expander_source_espanso_body),
        row(R.string.expander_export_target_title, R.string.expander_target_espanso_body),
    )
}

/** Rows on the tool pages, from Emoji through One-handed mode. */
private fun Resources.toolPageRowsA(): List<SettingsSearchEntry> = listOf(
    toolEntry(ToolbarTool.EMOJI, R.string.tooldetail_emoji_toolbar_title, R.string.tooldetail_emoji_toolbar_subtitle),
    toolEntry(ToolbarTool.EMOJI, R.string.tooldetail_emoji_all_title, R.string.tooldetail_emoji_all_subtitle, weight = EntryWeight.MIRROR),
    toolEntry(
        ToolbarTool.CLIPBOARD,
        R.string.tooldetail_clipboard_all_title,
        R.string.tooldetail_clipboard_all_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.SNIPPETS,
        R.string.tooldetail_snippets_all_title,
        R.string.tooldetail_snippets_all_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.SPLIT, R.string.tooldetail_split_gap_title, R.string.tooldetail_split_gap_subtitle),
    toolEntry(
        ToolbarTool.SPLIT,
        R.string.tooldetail_layout_nav_title,
        R.string.tooldetail_layout_nav_split_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.FLOATING, R.string.tooldetail_floating_width_title, R.string.tooldetail_floating_width_subtitle),
    toolEntry(
        ToolbarTool.FLOATING,
        R.string.tooldetail_layout_nav_title,
        R.string.tooldetail_layout_nav_floating_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.FLASHLIGHT, R.string.tooldetail_flashlight_auto_off_title, R.string.tooldetail_flashlight_auto_off_subtitle),
    toolEntry(ToolbarTool.COMPASS, R.string.tooldetail_compass_degrees_title, R.string.tooldetail_compass_degrees_subtitle),
    toolEntry(ToolbarTool.COMPASS, R.string.tooldetail_compass_qibla_title, R.string.tooldetail_compass_qibla_subtitle),
    toolEntry(ToolbarTool.LEVEL, R.string.tooldetail_level_angles_title, R.string.tooldetail_level_angles_subtitle),
    toolEntry(ToolbarTool.UNDO, R.string.tooldetail_redo_ctrl_y_title, R.string.tooldetail_redo_ctrl_y_subtitle),
    toolEntry(ToolbarTool.REDO, R.string.tooldetail_redo_ctrl_y_title, R.string.tooldetail_redo_ctrl_y_subtitle),
    toolEntry(ToolbarTool.MOON_PHASE, R.string.tooldetail_moon_southern_title, R.string.tooldetail_moon_southern_subtitle),
    toolEntry(ToolbarTool.WEATHER, R.string.tooldetail_weather_fahrenheit_title, R.string.tooldetail_weather_fahrenheit_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_first_title, R.string.tooldetail_calendar_first_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_second_title, R.string.tooldetail_calendar_second_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.toolai_weekend_title, R.string.toolai_weekend_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_hijri_title, R.string.tooldetail_calendar_hijri_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_front_title, R.string.tooldetail_camera_front_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_mirror_title, R.string.tooldetail_camera_mirror_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_fullframe_title, R.string.tooldetail_camera_fullframe_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_gallery_title, R.string.tooldetail_camera_gallery_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_shutter_title, R.string.tooldetail_camera_shutter_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_haptics_title, R.string.tooldetail_camera_haptics_subtitle),
    toolEntry(ToolbarTool.DICTIONARY, R.string.tooldetail_dictionary_auto_title, R.string.tooldetail_dictionary_auto_subtitle),
    toolEntry(ToolbarTool.TEXT_EDIT, R.string.tooldetail_text_edit_repeat_title, R.string.tooldetail_text_edit_repeat_subtitle),
    // One switch drawn on all eight caret tools' pages, indexed once. Eight
    // results with the same title would read as a broken search, and the switch
    // is the same one wherever it is flipped — so the first of them answers for
    // the set.
    toolEntry(
        ToolbarTool.CURSOR_LEFT,
        R.string.tooldetail_cursor_repeat_title,
        R.string.tooldetail_cursor_repeat_subtitle,
    ),
    // The toolbox switch is per tool, so unlike the one above it genuinely has
    // eight different answers. Still indexed once: eight rows with the same
    // title would read as a broken search, and the first of them is the way in
    // to the rest.
    toolEntry(
        ToolbarTool.CURSOR_LEFT,
        R.string.tooldetail_cursor_repeat_toolbox_title,
        R.string.tooldetail_cursor_repeat_toolbox_subtitle,
    ),
    toolEntry(
        ToolbarTool.SELECT_MODE,
        R.string.tooldetail_select_mode_hold_title,
        R.string.tooldetail_select_mode_hold_subtitle,
    ),
    toolEntry(
        ToolbarTool.SELECT_MODE,
        R.string.tooldetail_select_mode_taps_title,
        R.string.tooldetail_select_mode_taps_subtitle,
    ),
    toolEntry(ToolbarTool.NUMPAD, R.string.tooldetail_numpad_calc_title, R.string.tooldetail_numpad_calc_subtitle),
    toolEntry(ToolbarTool.INCOGNITO, R.string.tooldetail_incognito_learning_title, R.string.tooldetail_incognito_learning_subtitle),
    toolEntry(ToolbarTool.INCOGNITO, R.string.tooldetail_incognito_clipboard_title, R.string.tooldetail_incognito_clipboard_subtitle),
    toolEntry(ToolbarTool.INCOGNITO, R.string.tooldetail_incognito_auto_title, R.string.tooldetail_incognito_auto_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_now_title, R.string.tooldetail_power_now_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_trigger_title, R.string.tooldetail_power_trigger_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_battery_title, R.string.tooldetail_power_battery_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_charging_title, R.string.tooldetail_power_charging_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_haptics_title, R.string.tooldetail_power_drop_haptics_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_sound_title, R.string.tooldetail_power_drop_sound_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_popup_title, R.string.tooldetail_power_drop_popup_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_glide_title, R.string.tooldetail_power_drop_glide_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_emoji_title, R.string.tooldetail_power_drop_emoji_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_anim_title, R.string.tooldetail_power_drop_anim_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_trail_title, R.string.tooldetail_power_drop_trail_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_chips_title, R.string.tooldetail_power_drop_chips_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_network_title, R.string.tooldetail_power_drop_network_subtitle),
    toolEntry(
        ToolbarTool.POWER_SAVING,
        R.string.tooldetail_power_drop_screenshot_title,
        R.string.tooldetail_power_drop_screenshot_subtitle,
    ),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_models_title, R.string.tooldetail_power_drop_models_subtitle),
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_sort_title, R.string.tooldetail_launcher_sort_subtitle),
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_labels_title, R.string.tooldetail_launcher_labels_subtitle),
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_recents_title, R.string.tooldetail_launcher_recents_subtitle),
    toolEntry(
        ToolbarTool.APP_LAUNCHER,
        R.string.tooldetail_launcher_drilldown_title,
        R.string.tooldetail_launcher_drilldown_subtitle,
    ),
    toolEntry(
        ToolbarTool.APP_LAUNCHER,
        R.string.tooldetail_launcher_non_exported_title,
        R.string.tooldetail_launcher_non_exported_subtitle,
    ),
    // The tool screen's own "Autocorrect" toggle is not listed: toolRows
    // already indexes the tool under that title and route. A row named after
    // the tool holding it is always a duplicate result, never a second one.
    toolEntry(
        ToolbarTool.AUTOCORRECT,
        R.string.tooldetail_typing_nav_title,
        R.string.tooldetail_typing_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.SOUND_HAPTICS,
        R.string.tooldetail_keypress_nav_title,
        R.string.tooldetail_keypress_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.FANCY, R.string.tooldetail_fancy_style_title, R.string.tooldetail_fancy_style_subtitle),
    toolEntry(ToolbarTool.FANCY, R.string.tooldetail_fancy_keep_title, R.string.tooldetail_fancy_keep_subtitle),
    toolEntry(ToolbarTool.FANCY, R.string.tooldetail_fancy_auto_off_title, R.string.tooldetail_fancy_auto_off_subtitle),
    toolEntry(
        ToolbarTool.FANCY,
        R.string.tooldetail_fancy_language_nav_title,
        R.string.tooldetail_fancy_language_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.HANDWRITING, R.string.tooldetail_handwriting_stylus_title, R.string.tooldetail_handwriting_stylus_subtitle),
    toolEntry(
        ToolbarTool.HANDWRITING,
        R.string.tooldetail_handwriting_auto_space_title,
        R.string.tooldetail_handwriting_auto_space_subtitle,
    ),
    toolEntry(ToolbarTool.HANDWRITING, R.string.tooldetail_handwriting_pause_title, R.string.tooldetail_handwriting_pause_subtitle),
    toolEntry(
        ToolbarTool.HANDWRITING,
        R.string.tooldetail_handwriting_languages_title,
        R.string.tooldetail_handwriting_languages_subtitle,
    ),
    toolEntry(
        ToolbarTool.THEMES,
        R.string.tooldetail_themes_nav_title,
        R.string.tooldetail_themes_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.ONE_HANDED,
        R.string.tooldetail_layout_nav_title,
        R.string.tooldetail_layout_nav_one_handed_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.TRANSLATE, R.string.tooldetail_translate_key_label, R.string.tooldetail_translate_key_hint),
)

/** Rows on the tool pages, from Translate through the AI tool. */
private fun Resources.toolPageRowsB(): List<SettingsSearchEntry> = listOf(
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_sticker_packs_title, R.string.tooldetail_sticker_packs_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_full_bleed_title, R.string.tooldetail_media_full_bleed_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_full_bleed_title, R.string.tooldetail_media_full_bleed_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_klipy_label, R.string.tooldetail_media_klipy_hint),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_klipy_label, R.string.tooldetail_media_klipy_hint),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_giphy_label, R.string.tooldetail_media_giphy_hint),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_giphy_label, R.string.tooldetail_media_giphy_hint),
    // Each send mode is indexed under the one tool whose panel it governs; the
    // other page no longer draws it.
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_sticker_send_title, R.string.tooldetail_media_sticker_send_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_gif_send_title, R.string.tooldetail_media_gif_send_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_limit_title, R.string.tooldetail_media_limit_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_limit_title, R.string.tooldetail_media_limit_subtitle),
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.tooldetail_search_key_label, R.string.tooldetail_search_key_hint),
    toolEntry(ToolbarTool.IMAGE_SEARCH, R.string.tooldetail_search_key_label, R.string.tooldetail_search_key_hint),
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.tooldetail_search_safe_title, R.string.tooldetail_search_safe_subtitle),
    toolEntry(ToolbarTool.IMAGE_SEARCH, R.string.tooldetail_search_safe_title, R.string.tooldetail_search_safe_subtitle),
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.tooldetail_search_count_title, R.string.tooldetail_search_count_subtitle),
    toolEntry(ToolbarTool.IMAGE_SEARCH, R.string.tooldetail_search_count_title, R.string.tooldetail_search_count_subtitle),
    toolEntry(ToolbarTool.OCR, R.string.tooldetail_ocr_select_all_title, R.string.tooldetail_ocr_select_all_subtitle),
    toolEntry(ToolbarTool.QR_SCAN, R.string.tooldetail_qr_scan_auto_title, R.string.tooldetail_qr_scan_auto_subtitle),
    toolEntry(ToolbarTool.QR_SCAN, R.string.tooldetail_qr_scan_haptics_title, R.string.tooldetail_qr_scan_haptics_subtitle),
    toolEntry(ToolbarTool.QR_SCAN, R.string.tooldetail_qr_scan_preview_title, R.string.tooldetail_qr_scan_preview_subtitle),
    toolEntry(ToolbarTool.DOC_SCAN, R.string.tooldetail_doc_scan_gallery_title, R.string.tooldetail_doc_scan_gallery_subtitle),
    toolEntry(
        ToolbarTool.VOICE,
        R.string.tooldetail_voice_all_title,
        R.string.tooldetail_voice_all_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.GRAMMAR, R.string.tooldetail_grammar_dialect_title, R.string.tooldetail_grammar_dialect_subtitle),
    toolEntry(ToolbarTool.GRAMMAR, R.string.tooldetail_grammar_debounce_title, R.string.tooldetail_grammar_debounce_subtitle),
    toolEntry(ToolbarTool.GRAMMAR, R.string.tooldetail_grammar_system_title, R.string.tooldetail_grammar_system_subtitle),
    toolEntry(
        ToolbarTool.GRAMMAR,
        R.string.tooldetail_grammar_no_suggestions_title,
        R.string.tooldetail_grammar_no_suggestions_subtitle,
    ),
    toolEntry(ToolbarTool.WIKIPEDIA, R.string.tooldetail_wiki_language_label, R.string.tooldetail_wiki_language_hint),
    toolEntry(ToolbarTool.WIKIPEDIA, R.string.tooldetail_wiki_markdown_title, R.string.tooldetail_wiki_markdown_subtitle),
    toolEntry(ToolbarTool.CALCULATOR, R.string.tooldetail_calc_smart_title, R.string.tooldetail_calc_smart_subtitle),
    toolEntry(ToolbarTool.CALCULATOR, R.string.tooldetail_calc_degrees_title, R.string.tooldetail_calc_degrees_subtitle),
    toolEntry(ToolbarTool.CALCULATOR, R.string.tooldetail_calc_precision_title, R.string.tooldetail_calc_precision_subtitle),
    toolEntry(ToolbarTool.UNIT_CONVERT, R.string.tooldetail_units_smart_title, R.string.tooldetail_units_smart_subtitle),
    toolEntry(
        ToolbarTool.UNIT_CONVERT,
        R.string.tooldetail_units_compound_title,
        R.string.tooldetail_units_compound_subtitle,
    ),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_smart_title),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_decimals_title, R.string.tooldetail_currency_decimals_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_refresh_title, R.string.tooldetail_currency_refresh_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_source_title, R.string.tooldetail_currency_source_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_enable_title, R.string.tooldetail_crypto_enable_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_decimals_title, R.string.tooldetail_crypto_decimals_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_refresh_title, R.string.tooldetail_crypto_refresh_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_source_title, R.string.tooldetail_crypto_source_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_coins_title),
    toolEntry(ToolbarTool.QR_GEN, R.string.tooldetail_qr_gen_size_title, R.string.tooldetail_qr_gen_size_subtitle),
    toolEntry(ToolbarTool.QR_GEN, R.string.tooldetail_qr_gen_send_title, R.string.tooldetail_qr_gen_send_subtitle),
    toolEntry(ToolbarTool.QR_GEN, R.string.tooldetail_qr_gen_gallery_title, R.string.tooldetail_qr_gen_gallery_subtitle),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_length_title),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_uppercase_title, R.string.tooldetail_password_uppercase_subtitle),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_digits_title, R.string.tooldetail_password_digits_subtitle),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_symbols_title),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_ambiguous_title, R.string.tooldetail_password_ambiguous_subtitle),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_passphrase_words_title),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_passphrase_separator_label, R.string.tooldetail_passphrase_separator_hint),
    toolEntry(
        ToolbarTool.PASSWORD_GEN,
        R.string.tooldetail_passphrase_capitalize_title,
        R.string.tooldetail_passphrase_capitalize_subtitle,
    ),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_passphrase_digit_title, R.string.tooldetail_passphrase_digit_subtitle),
    toolEntry(ToolbarTool.MODES, R.string.tooldetail_modes_edit_title, R.string.tooldetail_modes_edit_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_seconds_label),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_words_label),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_punctuation_title, R.string.toolai_typing_punctuation_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_numbers_title, R.string.toolai_typing_numbers_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_glide_title, R.string.toolai_typing_glide_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_suggestions_title, R.string.toolai_typing_suggestions_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_clear_records_title, R.string.toolai_typing_clear_records_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_anthropic_key_label, R.string.toolai_ai_anthropic_key_hint),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_model_label),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_openai_key_label, R.string.toolai_ai_openai_key_hint),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_gemini_key_label, R.string.toolai_ai_gemini_key_hint),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_xai_key_label, R.string.toolai_ai_xai_key_hint),
    toolEntry(
        ToolbarTool.AI,
        R.string.toolai_ai_deepseek_key_label,
        R.string.toolai_ai_deepseek_key_hint,
    ),
    toolEntry(
        ToolbarTool.AI,
        R.string.toolai_ai_compatible_url_label,
        R.string.toolai_ai_compatible_url_hint,
    ),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_compatible_key_label, R.string.toolai_ai_compatible_key_hint),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_server_address_label, R.string.toolai_ai_ollama_url_hint),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_max_tokens_title, R.string.toolai_ai_max_tokens_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_translate_to_label, R.string.toolai_ai_translate_to_hint),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_show_thinking_title, R.string.toolai_ai_show_thinking_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_model_picker_title, R.string.toolai_ai_model_picker_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_actions_title, R.string.toolai_ai_actions_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_diff_title, R.string.toolai_ai_diff_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_diff_first_title, R.string.toolai_ai_diff_first_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_chat_nav_title, R.string.toolai_ai_chat_nav_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_history_title, R.string.toolai_ai_history_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_history_nav_title, R.string.toolai_ai_history_nav_subtitle),
    entry(
        R.string.toolai_ai_history_max_title,
        R.string.toolai_ai_history_max_subtitle,
        R.string.home_screen_ai_history_title,
        "ai_history",
        screenParent = R.string.home_tools_title,
    ),
    toolEntry(ToolbarTool.TRANSLATE, R.string.toolai_translate_into_title, R.string.toolai_translate_into_subtitle),
)

/**
 * Rows on Backup, on the sticker and plugin screens, and on Privacy, Rows &
 * bars, Keyboard modes, Accessibility, About and Tools.
 */
private fun Resources.otherRows(): List<SettingsSearchEntry> {
    fun backup(@StringRes title: Int, @StringRes subtitle: Int) =
        entry(title, subtitle, R.string.home_backup_title, "backup", weight = EntryWeight.MIRROR)
    fun stickerPack(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.home_screen_sticker_packs_title, "sticker_packs",
        screenParent = toolTitle(ToolbarTool.STICKER),
        screenRoot = R.string.home_tools_title,
    )
    fun plugin(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.home_screen_plugins_title, "plugins",
        screenParent = R.string.home_tools_title,
    )
    fun privacy(@StringRes title: Int, @StringRes subtitle: Int) =
        entry(title, subtitle, R.string.home_privacy_title, "privacy")
    fun dataSaver(@StringRes title: Int, @StringRes subtitle: Int) =
        entry(title, subtitle, R.string.home_datasaver_title, "datasaver")
    fun permission(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.privacy_permissions_title, "permissions",
        screenParent = R.string.home_privacy_title,
    )
    fun appLock(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.privacy_lock_title, "applock",
        screenParent = R.string.home_privacy_title,
    )
    fun mode(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_screen_mode_edit_title, "modes")
    fun access(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_accessibility_title, "accessibility")
    fun about(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_about_title, "about")
    fun debug(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.home_screen_debug_log_title, "debug_log",
        screenParent = R.string.home_about_title,
    )
    return listOfNotNull(
        // Every switch that says what a backup holds. Each is named after the
        // feature it copies, never after the feature itself, which is why they
        // all weigh MIRROR: the search for "themes" wants the theme screen.
        backup(R.string.backup_auto_dest_title, R.string.backup_auto_dest_subtitle),
        backup(R.string.backup_auto_folder_title, R.string.backup_auto_folder_subtitle),
        backup(R.string.backup_auto_webdav_url_label, R.string.backup_auto_dest_subtitle),
        backup(R.string.backup_auto_drive_title, R.string.backup_auto_dest_subtitle),
        backup(R.string.backup_auto_s3_bucket_label, R.string.backup_auto_s3_endpoint_hint),
        backup(R.string.backup_auto_s3_path_style_title, R.string.backup_auto_s3_path_style_subtitle),
        backup(R.string.backup_auto_ftp_host_label, R.string.backup_auto_ftp_path_hint),
        backup(R.string.backup_auto_ftp_secure_title, R.string.backup_auto_ftp_secure_subtitle),
        backup(R.string.backup_auto_dest_dropbox, R.string.backup_auto_dropbox_info),
        backup(R.string.backup_auto_dest_onedrive, R.string.backup_auto_onedrive_info),
        backup(R.string.backup_auto_enabled_title, R.string.backup_auto_enabled_subtitle),
        backup(R.string.backup_auto_interval_title, R.string.backup_auto_enabled_subtitle),
        backup(R.string.backup_auto_keep_title, R.string.backup_auto_keep_subtitle),
        backup(R.string.backup_auto_encrypt_title, R.string.backup_auto_encrypt_subtitle),
        backup(R.string.backup_section_settings_label, R.string.backup_include_settings_subtitle),
        backup(R.string.backup_include_secrets_title, R.string.backup_include_secrets_subtitle),
        backup(R.string.backup_section_themes_label, R.string.backup_include_themes_subtitle),
        backup(R.string.backup_section_dictionary_label, R.string.backup_include_dictionary_subtitle),
        backup(R.string.backup_section_clipboard_label, R.string.backup_include_clipboard_subtitle),
        backup(R.string.backup_section_snippets_label, R.string.backup_include_snippets_subtitle),
        backup(R.string.backup_section_stickers_label, R.string.backup_include_stickers_subtitle),
        backup(R.string.backup_section_icons_label, R.string.backup_include_icons_subtitle),
        backup(R.string.backup_section_wordlists_label, R.string.backup_include_wordlists_subtitle),
        backup(R.string.backup_section_addons_label, R.string.backup_include_addons_subtitle),
        backup(R.string.backup_section_emoji_label, R.string.backup_include_emoji_subtitle),
        stickerPack(R.string.import_sticker_pack_new_title, R.string.import_sticker_pack_new_subtitle),
        stickerPack(R.string.import_sticker_pack_import_title, R.string.import_sticker_pack_import_subtitle),
        // Lands on the pack list: the editor itself cannot open without an
        // image to edit, so there is nothing to deep-link to.
        stickerPack(R.string.import_sticker_editor_title, R.string.import_sticker_editor_subtitle),
        plugin(R.string.plugins_allow_title, R.string.plugins_allow_subtitle),
        plugin(R.string.tooldetail_plugins_manage_title, R.string.tooldetail_plugins_manage_subtitle),
        plugin(R.string.plugins_install_file_title, R.string.plugins_install_file_subtitle),
        privacy(R.string.privacy_learn_typing_title, R.string.privacy_learn_typing_subtitle),
        privacy(R.string.privacy_system_dictionary_title, R.string.privacy_system_dictionary_subtitle),
        privacy(R.string.privacy_dict_shortcuts_title, R.string.privacy_dict_shortcuts_subtitle),
        privacy(R.string.privacy_incognito_title, R.string.privacy_incognito_subtitle),
        privacy(R.string.privacy_auto_incognito_title, R.string.privacy_auto_incognito_subtitle),
        privacy(R.string.privacy_backup_title, R.string.privacy_backup_subtitle),
        // The fingerprint lock's own three settings. The per-target checkboxes
        // below them are deliberately absent: each is named after a screen or
        // a row that already has its own entry here, and a second result for
        // "Personal dictionary" that lands on a checkbox is worse than none.
        appLock(R.string.privacy_lock_enabled_title, R.string.privacy_lock_enabled_subtitle),
        appLock(R.string.privacy_lock_relock_title, R.string.privacy_lock_relock_info),
        appLock(R.string.privacy_lock_credential_title, R.string.privacy_lock_credential_subtitle),
        // Data saver. Every row, because each one is a feature someone will
        // look for by name once it stops working on mobile data.
        dataSaver(R.string.datasaver_manual_title, R.string.datasaver_manual_subtitle),
        dataSaver(R.string.datasaver_trigger_title, R.string.datasaver_trigger_subtitle),
        dataSaver(R.string.datasaver_link_previews_title, R.string.datasaver_link_previews_subtitle),
        dataSaver(R.string.datasaver_dictionary_title, R.string.datasaver_dictionary_subtitle),
        dataSaver(R.string.datasaver_photos_title, R.string.datasaver_photos_subtitle),
        dataSaver(R.string.datasaver_weather_title, R.string.datasaver_weather_subtitle),
        dataSaver(R.string.datasaver_rates_title, R.string.datasaver_rates_subtitle),
        dataSaver(R.string.datasaver_addons_title, R.string.datasaver_addons_subtitle),
        dataSaver(R.string.datasaver_media_title, R.string.datasaver_media_subtitle),
        dataSaver(R.string.datasaver_search_title, R.string.datasaver_search_subtitle),
        dataSaver(
            R.string.datasaver_animated_emoji_title,
            R.string.datasaver_animated_emoji_subtitle,
        ),
        dataSaver(R.string.datasaver_downloads_title, R.string.datasaver_downloads_subtitle),
        dataSaver(R.string.datasaver_ai_title, R.string.datasaver_ai_subtitle),
        // The Permissions screen's rows. The version-gated Storage row is left
        // out: on most devices a result would land on a screen without it.
        permission(R.string.privacy_permissions_mic_title, R.string.privacy_permissions_mic_subtitle),
        permission(R.string.privacy_permissions_camera_title, R.string.privacy_permissions_camera_subtitle),
        permission(R.string.privacy_permissions_contacts_title, R.string.privacy_permissions_contacts_subtitle),
        permission(R.string.privacy_permissions_calendar_title, R.string.privacy_permissions_calendar_subtitle),
        permission(R.string.privacy_permissions_images_title, R.string.privacy_permissions_images_subtitle),
        permission(
            R.string.privacy_permissions_notifications_title,
            R.string.privacy_permissions_notifications_subtitle,
        ),
        permission(R.string.privacy_permissions_usage_title, R.string.privacy_permissions_usage_subtitle),
        permission(
            R.string.privacy_permissions_accessibility_title,
            R.string.privacy_permissions_accessibility_subtitle,
        ),
        permission(R.string.privacy_permissions_internet_title, R.string.privacy_permissions_internet_subtitle),
        permission(R.string.privacy_permissions_vibrate_title, R.string.privacy_permissions_vibrate_subtitle),
        permission(R.string.privacy_permissions_biometric_title, R.string.privacy_permissions_biometric_subtitle),
        entry(R.string.rows_symbol_row_title, R.string.rows_symbol_row_subtitle, R.string.home_rows_title, "rows"),
        entry(R.string.modes_drag_edits_title, R.string.modes_drag_edits_subtitle, R.string.home_modes_title, "modes"),
        mode(R.string.modes_name_label, R.string.modes_name_hint),
        mode(R.string.modes_emoji_row_title, R.string.modes_active_subtitle),
        mode(R.string.modes_symbol_row_title),
        mode(R.string.modes_pinned_tools_title, R.string.modes_pinned_tools_subtitle),
        mode(R.string.modes_pinned_behaviour_title, R.string.modes_pinned_behaviour_append_subtitle),
        mode(R.string.modes_toolbox_order_title, R.string.modes_toolbox_order_subtitle),
        mode(R.string.modes_symbol_sets_title, R.string.modes_symbol_sets_subtitle),
        access(R.string.accessibility_color_vision_title, R.string.accessibility_color_vision_subtitle),
        access(R.string.accessibility_high_contrast_title, R.string.accessibility_high_contrast_subtitle),
        access(R.string.accessibility_key_outlines_title, R.string.accessibility_key_outlines_subtitle),
        access(R.string.accessibility_bold_labels_title, R.string.accessibility_bold_labels_subtitle),
        access(R.string.accessibility_readable_font_title),
        access(R.string.accessibility_text_size_title, R.string.accessibility_text_size_subtitle),
        access(R.string.accessibility_keyboard_font_title, R.string.accessibility_keyboard_font_subtitle),
        access(R.string.accessibility_reduce_motion_title, R.string.accessibility_reduce_motion_subtitle),
        access(R.string.accessibility_talkback_title, R.string.accessibility_talkback_subtitle),
        access(R.string.accessibility_passthrough_service_title),
        access(R.string.accessibility_debounce_title, R.string.accessibility_debounce_subtitle_off),
        access(R.string.accessibility_long_press_title, R.string.accessibility_long_press_subtitle),
        access(R.string.accessibility_key_size_title, R.string.accessibility_key_size_subtitle),
        access(R.string.accessibility_haptics_title, R.string.accessibility_haptics_subtitle),
        about(R.string.about_version_title),
        about(R.string.about_licence_title),
        about(R.string.about_source_title),
        // Open-source licences has its own screen, indexed once in sectionRows.
        about(R.string.about_user_guide_title),
        about(R.string.about_privacy_policy_title, R.string.about_privacy_policy_subtitle),
        about(R.string.about_report_bug_title, R.string.about_report_bug_subtitle),
        about(R.string.about_email_developer_title),
        entry(
            R.string.about_diagnostics_title, R.string.about_diagnostics_subtitle,
            R.string.home_about_title, "debug_log", keywords = R.string.search_kw_diagnostics,
        ),
        entry(
            R.string.about_replay_onboarding_title, R.string.about_replay_onboarding_subtitle,
            R.string.home_about_title, "about", keywords = R.string.search_kw_replay_onboarding,
        ),
        debug(R.string.shell_debug_log_share_title, R.string.shell_debug_log_share_subtitle),
        debug(R.string.shell_debug_log_copy_title, R.string.shell_debug_log_copy_subtitle),
        debug(R.string.shell_debug_log_system_title, R.string.shell_debug_log_system_subtitle),
        debug(R.string.shell_debug_log_crash_test_title, R.string.shell_debug_log_crash_test_subtitle),
        about(R.string.about_dictionaries_title, R.string.about_dictionaries_subtitle),
        // Play builds only: nowhere else has an Updates group to land on. The
        // check row is indexed under its resting name — the resource is what a
        // search result matches on, and the row wears three other names while
        // a download is running.
        if (BuildConfig.ENABLE_PLAY_STORE) {
            about(R.string.update_row_check_title, R.string.update_row_check_subtitle)
        } else {
            null
        },
        if (BuildConfig.ENABLE_PLAY_STORE) {
            about(R.string.update_row_prompts_title, R.string.update_row_prompts_subtitle)
        } else {
            null
        },
        entry(R.string.tools_colored_icons_title, R.string.tools_colored_icons_subtitle, R.string.home_tools_title, "tools"),
        entry(R.string.tools_gradient_icons_title, R.string.tools_gradient_icons_subtitle, R.string.home_tools_title, "tools"),
    )
}

/** The destinations: the settings home's own list, plus the screens that hang off it. */
private fun Resources.sectionRows(): List<SettingsSearchEntry> {
    fun home(
        @StringRes title: Int,
        @StringRes subtitle: Int = 0,
        route: String,
        @StringRes keywords: Int = 0,
    ) = entry(
        title, subtitle, CommonR.string.common_settings, route,
        weight = EntryWeight.SECTION, keywords = keywords,
    )
    fun under(
        @StringRes title: Int,
        @StringRes subtitle: Int,
        @StringRes screen: Int,
        route: String,
        @StringRes keywords: Int = 0,
    ) = entry(title, subtitle, screen, route, weight = EntryWeight.SECTION, keywords = keywords)
    return listOf(
        home(R.string.home_typing_title, R.string.home_typing_subtitle, "typing", R.string.search_kw_typing),
        home(R.string.home_keypress_title, R.string.home_keypress_subtitle, "keypress", R.string.search_kw_keypress),
        home(R.string.home_languages_title, R.string.search_languages_subtitle, "languages", R.string.search_kw_languages),
        home(R.string.home_appearance_title, R.string.home_appearance_subtitle, "appearance", R.string.search_kw_appearance),
        home(R.string.home_layout_title, R.string.home_layout_subtitle, "layout", R.string.search_kw_layout),
        under(
            R.string.photo_rotation_title, R.string.photo_rotation_subtitle,
            R.string.home_screen_theme_edit_title, "photo_rotation",
        ),
        under(
            R.string.photo_find_title, R.string.photo_find_subtitle,
            R.string.home_screen_theme_edit_title, "photo_browse",
        ),
        under(
            R.string.photo_library_title, R.string.photo_library_subtitle,
            R.string.photo_rotation_title, "photo_library",
        ),
        under(
            R.string.photo_services_title, R.string.photo_services_subtitle,
            R.string.home_screen_theme_edit_title, "photos",
        ),
        home(R.string.home_keymaps_title, R.string.home_keymaps_subtitle, "keymaps", R.string.search_kw_keymaps),
        home(R.string.home_rows_title, R.string.home_rows_subtitle, "rows", R.string.search_kw_rows),
        home(R.string.home_modes_title, R.string.home_modes_subtitle, "modes", R.string.search_kw_modes),
        home(R.string.home_emoji_title, R.string.home_emoji_subtitle, "emoji", R.string.search_kw_emoji),
        home(
            R.string.home_clipboard_title, R.string.home_clipboard_subtitle,
            "clipboard", R.string.search_kw_clipboard,
        ),
        home(
            R.string.home_voice_title, R.string.home_voice_subtitle,
            "voice", R.string.search_kw_voice,
        ),
        home(
            R.string.home_expander_title, R.string.home_expander_subtitle,
            "expander", R.string.search_kw_expander,
        ),
        home(R.string.home_tools_title, R.string.home_tools_subtitle, "tools", R.string.search_kw_tools),
        home(R.string.home_addons_title, R.string.home_addons_subtitle, "addons", R.string.search_kw_addons),
        home(
            R.string.home_accessibility_title, R.string.home_accessibility_subtitle,
            "accessibility", R.string.search_kw_accessibility,
        ),
        home(R.string.home_privacy_title, R.string.home_privacy_subtitle, "privacy", R.string.search_kw_privacy),
        home(
            R.string.home_datasaver_title, R.string.home_datasaver_subtitle,
            "datasaver", R.string.search_kw_datasaver,
        ),
        home(R.string.home_backup_title, R.string.home_backup_subtitle, "backup", R.string.search_kw_backup),
        home(R.string.home_about_title, R.string.home_about_subtitle, "about", R.string.search_kw_about),
        under(
            R.string.appearance_themes_title, R.string.appearance_themes_subtitle,
            R.string.home_appearance_title, "themes", R.string.search_kw_themes,
        ),
        under(
            R.string.appearance_font_title, R.string.appearance_font_subtitle,
            R.string.home_appearance_title, "fonts", R.string.search_kw_fonts,
        ),
        under(
            R.string.appearance_icons_title, R.string.appearance_icons_subtitle,
            R.string.home_appearance_title, "icons", R.string.search_kw_icons,
        ),
        under(
            R.string.typing_personal_dictionary_title,
            R.string.typing_personal_dictionary_subtitle,
            R.string.home_typing_title,
            "dictionary",
            R.string.search_kw_dictionary,
        ),
        under(
            R.string.typing_custom_dictionaries_title,
            R.string.typing_custom_dictionaries_subtitle,
            R.string.home_typing_title,
            "customdictionaries",
            R.string.search_kw_customdictionaries,
        ),
        under(
            R.string.langemoji_emoji_keywords_title,
            R.string.langemoji_emoji_keywords_subtitle,
            R.string.home_emoji_title,
            "emojikeywords",
            R.string.search_kw_emojikeywords,
        ),
        under(
            R.string.typing_blacklist_title, R.string.typing_blacklist_subtitle,
            R.string.home_typing_title, "blacklist", R.string.search_kw_blacklist,
        ),
        // The Clipboard screen has a row for this, but the row only opens this
        // screen, so it is indexed once and points straight at it.
        under(
            R.string.home_screen_phoneformats_title,
            R.string.clipboard_phone_formats_subtitle,
            R.string.home_clipboard_title,
            "phoneformats",
            R.string.search_kw_phoneformats,
        ),
        under(
            R.string.typing_hw_shortcuts_list_title,
            R.string.typing_hw_shortcuts_list_subtitle,
            R.string.home_typing_title,
            "hwshortcuts",
            R.string.search_kw_hwshortcuts,
        ),
        under(
            R.string.about_licences_title, R.string.about_licences_subtitle,
            R.string.home_about_title, "licenses", R.string.search_kw_licenses,
        ),
        under(
            R.string.about_storage_title, R.string.about_storage_subtitle,
            R.string.home_about_title, "storage", R.string.search_kw_storage,
        ),
        under(
            R.string.statistics_title, R.string.statistics_subtitle,
            R.string.home_about_title, "statistics", R.string.search_kw_statistics,
        ),
        under(
            R.string.privacy_permissions_title, R.string.privacy_permissions_subtitle,
            R.string.home_privacy_title, "permissions", R.string.search_kw_permissions,
        ),
        under(
            R.string.privacy_lock_title, R.string.privacy_lock_subtitle,
            R.string.home_privacy_title, "applock", R.string.search_kw_applock,
        ),
    )
}

/**
 * The Storage screen's categories.
 *
 * All of them point at the screen itself rather than at each category's own
 * page: someone searching for stickers wants the sticker packs, and a second
 * result that lands on a size readout for them would only be in the way. What
 * this buys is the other direction — a search for cached files, or for models,
 * finds the one screen that can delete them.
 */
private fun Resources.storageRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int) =
        entry(title, subtitle, R.string.about_storage_title, "storage", screenParent = R.string.home_about_title)
    return listOf(
        row(R.string.storage_wordlists_title, R.string.storage_wordlists_subtitle),
        row(R.string.storage_voice_models_title, R.string.storage_voice_models_subtitle),
        row(R.string.storage_ai_models_title, R.string.storage_ai_models_subtitle),
        row(R.string.storage_themes_title, R.string.storage_themes_subtitle),
        row(R.string.storage_learned_title, R.string.storage_learned_subtitle),
        row(R.string.storage_cache_images_title, R.string.storage_cache_images_subtitle),
        row(R.string.storage_settings_data_title, R.string.storage_settings_data_subtitle),
    )
}

/** Rows that live on one of those screens rather than naming it. */
private fun Resources.sectionChildRows(): List<SettingsSearchEntry> = listOf(
    entry(R.string.fonts_installed_header, 0, R.string.home_screen_fonts_title, "fonts", screenParent = R.string.home_appearance_title),
    entry(
        R.string.customdict_emoji_auto_download_title,
        R.string.customdict_emoji_auto_download_subtitle,
        R.string.home_screen_emoji_keywords_title,
        "emojikeywords",
        screenParent = R.string.home_emoji_title,
    ),
)

/**
 * The tools themselves, derived from the enum rather than listed, so a new
 * tool is searchable the moment it has a title. No index edit needed.
 */
private fun Resources.toolRows(): List<SettingsSearchEntry> =
    ToolbarTool.entries.filter(::isSupportedTool).map { tool ->
        toolEntry(tool, toolTitle(tool), toolDescription(tool))
    }

/**
 * The whole searchable surface, in the language [res] is configured for.
 *
 * Build it once per screen, keyed on the context, and hand the result to
 * [searchSettings]. Tool-detail rows for tools this build cannot provide (the
 * lite flavor drops the ML Kit ones) are filtered out, because their screens
 * are unreachable.
 */
internal fun settingsSearchIndex(res: Resources): List<SettingsSearchEntry> = with(res) {
    val unsupported = ToolbarTool.entries.filterNot(::isSupportedTool)
        .map { "tool/${it.name}" }.toSet()
    val all = sectionRows() + toolRows() + sectionChildRows() + typingRows() + keyPressRows() +
        appearanceRows() + photoRows() + layoutRows() + languageRows() + emojiRows() +
        voiceRows() + clipboardRows() + expanderRows() + toolPageRowsA() + toolPageRowsB() + storageRows() + otherRows()
    all.filterNot { it.route in unsupported }
}

// The ranking itself lives in SettingsSearchMatch.kt: what a query word is
// worth against an entry, and the word groups that let a search for a vibration
// find the haptics rows.

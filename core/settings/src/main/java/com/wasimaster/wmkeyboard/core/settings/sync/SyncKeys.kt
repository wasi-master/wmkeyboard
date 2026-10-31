package com.wasimaster.wmkeyboard.core.settings.sync

import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.SettingsBackup

/**
 * Which settings sync carries, and which stay on the device that set them.
 *
 * A backup restores one phone onto another and wants nearly everything. Sync
 * keeps two phones the same while both are in use, which is a different
 * question: a keyboard height tuned for a tablet is wrong on a phone, a paired
 * KDE Connect computer is paired with one device, and a counter the keyboard
 * bumps to say "reload the dictionary" means nothing anywhere else. Those stay
 * here.
 *
 * The rule is a list, not a flag on every setting, because settings are added
 * weekly and a new one should sync by default: the same reason
 * [SettingsBackup.encodeSettings] walks the whole preference map.
 */
object SyncKeys {

    /**
     * Whole names that stay on this device. Grouped by why.
     */
    private val LOCAL_KEYS = setOf(
        // Where the keyboard sits and how it floats: per screen and per hand.
        "floating_keyboard", "floating_keyboard_landscape", "floating_handle_position",
        "floating_x", "floating_y", "floating_width", "floating_height_scale",
        "split_keyboard", "split_gap_percent", "split_only_large_screens", "split_spacebar",
        "symbol_row_height", "toolbar_height", "toolbar_padding_top", "toolbar_padding_bottom",
        "key_popup_offset_x", "key_popup_offset_y", "key_popup_floating_height",
        "gesture_word_preview_offset_x", "gesture_word_preview_offset_y",
        "voice_bar_dock_bias", "voice_bar_edge_right", "voice_bar_y_bias", "voice_bar_snap",
        "voice_bar_vertical", "voice_bar_active",
        // A hardware keyboard is attached to one device.
        "hardware_keyboard_input", "toolbar_only_hw_keyboard",
        // What is installed or downloaded here.
        "emoji_font_installed_id", "whisper_model_id", "whisper_model_by_lang", "ai_local_model_id",
        "media_music_apps", "launcher_pinned", "launcher_hidden", "launcher_recents",
        "share_sheet_pinned", "share_sheet_recents",
        "launcher_folders",
        // Current state rather than a choice.
        "active_layout_id", "input_mode", "incognito", "data_saver_manual", "power_saving_manual",
        "recent_layout_ids", "globe_recent_order", "symbol_recents", "unit_convert_last",
        "photo_rotation_state", "auto_theme_shuffled_at", "auto_theme_shuffled_at_elapsed",
        "auto_theme_shuffle_light_id", "auto_theme_shuffle_dark_id", "advanced_open",
        "weather_lat", "weather_lon", "weather_place",
        // Signals the keyboard bumps to itself.
        "lexicon_version", "corrections_version", "custom_dict_version", "emoji_usage_version",
        "emoji_keyword_pack_version", "gesture_swipe_style_version", "mode_seed_version",
        "selection_macros_list_version", "stats_version",
        // One-time progress on this install.
        "onboarding_done", "onboarding_persona_depth", "onboarding_persona_languages",
        "onboarding_persona_privacy", "auto_pair_romanized_done", "toolbox_hint_dismissed",
        // Security that belongs to one device's owner and hardware.
        "app_lock_enabled", "app_lock_relock", "app_lock_targets", "app_lock_allow_credential",
        "app_lock_method", "app_lock_secret", "app_lock_biometric_shortcut",
    )

    /**
     * Name prefixes that stay here. The sizing keys come in one flavour per
     * screen shape (`key_height_landscape`, `…_unfolded`), and a prefix covers
     * the shapes a later build adds.
     */
    private val LOCAL_PREFIXES = listOf(
        "key_height", "number_row_height", "bottom_padding", "bottom_row_height",
        "keyboard_width_percent", "keyboard_alignment", "keyboard_scale", "key_gap_scale",
        "side_pad_", "font_scale_",
        "one_handed_", "hw_", "kde_", "sync_", "auto_backup_",
    )

    /**
     * The per-screen number-row switches. Exact names, not a prefix: the
     * number row has real preferences under the same stem
     * (`number_row_corrections`), and those sync.
     */
    private val VARIANT_NUMBER_ROW = ScreenVariant.entries.mapTo(HashSet()) { "number_row_${it.suffix}" }

    /**
     * Settings that sync by default but that a person may want different on
     * each device, kept here when they tick the group. A phone and a tablet
     * rarely want the same toolbar, and a work phone may not want the
     * languages of a personal one.
     */
    enum class LocalGroup(val id: String, private val names: Set<String>, private val prefixes: List<String>) {
        /** Which tools, in what order, and how the toolbar and toolbox look. */
        TOOLBAR("toolbar", emptySet(), listOf("toolbar_", "toolbox_", "tool_")),

        /** The layouts switched between, the extra languages, and the per-app choices. */
        LAYOUTS(
            "layouts",
            setOf(
                "enabled_layout_ids", "secondary_languages", "per_app_layout_map", "per_app_language_enabled",
                "per_app_layout_rules",
                "layout_display_names",
            ),
            emptyList(),
        ),

        /**
         * The theme in use, light and dark, and when it switches (#935). The
         * Themes section carries the user's own themes, the library; which
         * one is on is a setting, and a phone and a tablet often want
         * different ones.
         */
        THEME("theme", setOf("keyboard_theme_id", "theme_mode"), listOf("auto_theme_")),
        ;

        fun holds(key: String): Boolean = key in names || prefixes.any { key.startsWith(it) }

        companion object {
            fun of(ids: Set<String>): Set<LocalGroup> = entries.filterTo(LinkedHashSet()) { it.id in ids }
        }
    }

    /** Whether the setting named [key] travels between devices. */
    fun syncable(key: String, includeSecrets: Boolean, keepLocal: Set<LocalGroup> = emptySet()): Boolean {
        if (key in SettingsBackup.TRANSIENT_KEYS) return false
        if (keepLocal.any { it.holds(key) }) return false
        if (key in VARIANT_NUMBER_ROW) return false
        if (key in SettingsBackup.THEME_KEYS) return false
        if (!includeSecrets && key in SettingsBackup.SECRET_KEYS) return false
        if (key in LOCAL_KEYS) return false
        if (key in com.wasimaster.wmkeyboard.core.settings.TodoPrefs.LOCAL_KEYS) return false
        return LOCAL_PREFIXES.none { key.startsWith(it) }
    }
}

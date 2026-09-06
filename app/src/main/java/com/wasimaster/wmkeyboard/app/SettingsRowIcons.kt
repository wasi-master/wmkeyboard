package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.FormatTextdirectionRToL
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BorderStyle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.DataArray
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.KeyboardCapslock
import androidx.compose.material.icons.outlined.KeyboardCommandKey
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LineWeight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Mosque
import androidx.compose.material.icons.outlined.MotionPhotosOff
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Padding
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoSizeSelectActual
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.PhotoSizeSelectSmall
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Quickreply
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.RoundedCorner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.SwipeDown
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Shortcut
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The glyph beside each settings row, keyed by the string resource of the row's
 * own name.
 *
 * One table rather than an argument at every call site, for the same reason
 * [SettingsRouteIcons] is one: the icons only work if they agree with each
 * other, and a few hundred `icon =` arguments spread over four files is how two
 * rows called the same thing end up wearing different glyphs. The row helpers
 * that take a `@StringRes` title look their icon up here, so giving a row an
 * icon is an edit to this file alone.
 *
 * A row missing from the map draws no icon, which is deliberate wherever every
 * candidate glyph would be a guess — a language name, a per-orientation width,
 * "Follows portrait". A vague icon is worse than none: it is one more thing to
 * scan past that says nothing.
 */
internal object SettingsRowIcons {
    // Builders rather than built vectors. Materialising all ~450 glyphs used
    // to run at class init — path parsing for every icon in the app, paid in
    // one lump before the first settings row could draw. Each glyph is now
    // built (and cached by the icons library) when a row first draws it.
    private val map: Map<Int, () -> ImageVector> = buildMap {

        // ---- About ----
        put(R.string.about_version_title) { Icons.Outlined.Info }
        put(R.string.about_licence_title) { Icons.Outlined.Gavel }
        put(R.string.about_licences_title) { Icons.Outlined.Gavel }
        put(R.string.about_source_title) { Icons.Outlined.Code }
        put(R.string.about_user_guide_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.about_privacy_policy_title) { Icons.Outlined.PrivacyTip }
        put(R.string.about_storage_title) { Icons.Outlined.PieChart }
        put(R.string.statistics_title) { Icons.Outlined.QueryStats }
        put(R.string.about_diagnostics_title) { Icons.AutoMirrored.Outlined.Article }
        put(R.string.about_persona_title) { Icons.Outlined.Person }
        put(R.string.about_replay_onboarding_title) { Icons.Outlined.Replay }
        put(R.string.about_dictionaries_title) { Icons.AutoMirrored.Outlined.LibraryBooks }
        put(R.string.about_report_bug_title) { Icons.Outlined.BugReport }
        put(R.string.about_email_developer_title) { Icons.Outlined.Email }
        put(R.string.about_share_play_link) { Icons.Outlined.Share }
        put(R.string.about_share_github_link) { Icons.Outlined.Share }
        put(R.string.about_share_fdroid_link) { Icons.Outlined.Share }

        // ---- Updates (Play builds only; the rows are absent everywhere else) ----
        // The same glyph on every state of the one row, so it does not appear to
        // jump between rows as the download runs.
        put(R.string.update_row_check_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_available_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_downloading_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_install_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_installing_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_prompts_title) { Icons.Outlined.Notifications }

        // ---- Accessibility ----
        put(R.string.accessibility_color_vision_title) { Icons.Outlined.Palette }
        put(R.string.accessibility_high_contrast_title) { Icons.Outlined.Contrast }
        put(R.string.accessibility_key_outlines_title) { Icons.Outlined.BorderStyle }
        put(R.string.accessibility_bold_labels_title) { Icons.Outlined.FormatBold }
        put(R.string.accessibility_readable_font_title) { Icons.Outlined.FontDownload }
        put(R.string.accessibility_text_size_title) { Icons.Outlined.FormatSize }
        put(R.string.accessibility_keyboard_font_title) { Icons.Outlined.TextFields }
        put(R.string.accessibility_reduce_motion_title) { Icons.Outlined.MotionPhotosOff }
        put(R.string.accessibility_talkback_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.accessibility_passthrough_service_title) { Icons.Outlined.Accessibility }
        put(R.string.accessibility_debounce_title) { Icons.Outlined.FilterAlt }
        put(R.string.accessibility_long_press_title) { Icons.Outlined.TouchApp }
        put(R.string.accessibility_key_size_title) { Icons.Outlined.PhotoSizeSelectSmall }
        put(R.string.accessibility_haptics_title) { Icons.Outlined.Vibration }

        // ---- Add-ons ----
        put(R.string.addon_auto_refresh_title) { Icons.Outlined.Autorenew }
        put(R.string.addon_refresh_unmetered_title) { Icons.Outlined.Wifi }

        // ---- Appearance ----
        put(R.string.appearance_themes_title) { Icons.Outlined.Palette }
        put(R.string.appearance_font_title) { Icons.Outlined.TextFields }
        put(R.string.appearance_icons_title) { Icons.Outlined.Image }
        put(R.string.appearance_key_corner_radius_title) { Icons.Outlined.RoundedCorner }
        put(R.string.appearance_key_label_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_key_hint_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_toolbar_show_title) { Icons.Outlined.Visibility }
        put(R.string.appearance_toolbar_swipe_down_title) { Icons.Outlined.SwipeDown }
        put(R.string.appearance_toolbar_hardware_only_title) { Icons.Outlined.KeyboardAlt }
        put(R.string.appearance_toolbar_lock_title) { Icons.Outlined.Lock }
        put(R.string.appearance_toolbar_rtl_title) { Icons.AutoMirrored.Outlined.FormatTextdirectionRToL }
        put(R.string.appearance_toolbar_fit_title) { Icons.Outlined.SpaceBar }
        put(R.string.appearance_toolbar_height_title) { Icons.Outlined.Height }
        put(R.string.appearance_toolbar_labels_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.appearance_toolbar_label_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_tool_circle_title) { Icons.Outlined.Circle }
        put(R.string.appearance_tool_shape_title) { Icons.Outlined.Category }
        put(R.string.appearance_toolbox_layout_title) { Icons.Outlined.GridView }
        put(R.string.appearance_toolbox_columns_title) { Icons.Outlined.GridOn }
        put(R.string.appearance_toolbox_pill_columns_title) { Icons.Outlined.ViewAgenda }
        put(R.string.appearance_toolbox_pill_filled_title) { Icons.Outlined.FormatColorFill }
        put(R.string.appearance_toolbox_paginate_title) { Icons.Outlined.Swipe }
        put(R.string.appearance_toolbox_page_size_title) { Icons.Outlined.Numbers }
        put(R.string.appearance_toolbox_label_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_suggestion_text_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_suggestion_spacing_title) { Icons.Outlined.SpaceBar }
        put(R.string.appearance_tool_width_title) { Icons.Outlined.Straighten }
        put(R.string.appearance_reset_toolbox_order_title) { Icons.Outlined.Restore }
        put(R.string.appearance_reset_title) { Icons.Outlined.Restore }

        // ---- Backup ----
        put(R.string.backup_section_settings_label) { Icons.Outlined.Settings }
        put(R.string.backup_section_dictionary_label) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.backup_section_wordlists_label) { Icons.AutoMirrored.Outlined.Article }
        put(R.string.backup_section_clipboard_label) { Icons.Outlined.ContentPaste }
        put(R.string.backup_section_snippets_label) { Icons.Outlined.Description }
        put(R.string.backup_section_themes_label) { Icons.Outlined.Palette }
        put(R.string.backup_section_icons_label) { Icons.Outlined.Category }
        put(R.string.backup_section_stickers_label) { Icons.Outlined.EmojiEmotions }
        put(R.string.backup_section_addons_label) { Icons.Outlined.Extension }
        put(R.string.backup_section_emoji_label) { Icons.Outlined.History }
        put(R.string.backup_section_statistics_label) { Icons.Outlined.QueryStats }
        put(R.string.backup_blacklist_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.backup_include_secrets_title) { Icons.Outlined.Key }
        put(R.string.backup_auto_dest_title) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_folder_title) { Icons.Outlined.Folder }
        put(R.string.backup_auto_webdav_url_label) { Icons.Outlined.Link }
        put(R.string.backup_auto_drive_title) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_s3_bucket_label) { Icons.Outlined.Inventory2 }
        put(R.string.backup_auto_s3_path_style_title) { Icons.Outlined.Link }
        put(R.string.backup_auto_ftp_host_label) { Icons.Outlined.Link }
        put(R.string.backup_auto_ftp_secure_title) { Icons.Outlined.Lock }
        put(R.string.backup_auto_dest_dropbox) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_dest_onedrive) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_enabled_title) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_interval_title) { Icons.Outlined.Schedule }
        put(R.string.backup_auto_keep_title) { Icons.Outlined.Numbers }
        put(R.string.backup_auto_encrypt_title) { Icons.Outlined.Lock }
        put(R.string.backup_auto_charging_title) { Icons.Outlined.BatteryChargingFull }
        put(R.string.backup_auto_unmetered_title) { Icons.Outlined.Wifi }

        // ---- Key press ----
        put(R.string.keypress_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.keypress_haptic_strength_title) { Icons.Outlined.Vibration }
        put(R.string.keypress_haptic_intensity_title) { Icons.Outlined.GraphicEq }
        put(R.string.keypress_long_press_haptics_title) { Icons.Outlined.TouchApp }
        put(R.string.keypress_long_press_release_title) { Icons.Outlined.TouchApp }
        put(R.string.keypress_vibrate_space_title) { Icons.Outlined.SpaceBar }
        put(R.string.keypress_vibrate_delete_swipe_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.keypress_vibrate_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_dnd_mute_title) { Icons.Outlined.DoNotDisturbOn }
        put(R.string.hardware_sound_key_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.hardware_sound_volume_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.hardware_sound_pack_release_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.keypress_sound_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_system_touch_title) { Icons.Outlined.PhoneAndroid }
        put(R.string.keypress_popup_title) { Icons.Outlined.Notifications }
        put(R.string.keypress_popup_numeric_title) { Icons.Outlined.Dialpad }
        put(R.string.keypress_popup_on_key_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.keypress_popup_min_duration_title) { Icons.Outlined.Timer }
        put(R.string.keypress_popup_max_duration_title) { Icons.Outlined.Timer }
        put(R.string.keypress_popup_font_size_title) { Icons.Outlined.FormatSize }
        put(R.string.keypress_popup_height_title) { Icons.Outlined.Height }
        put(R.string.keypress_popup_offset_y_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.keypress_popup_offset_x_title) { Icons.Outlined.SwapHoriz }
        put(R.string.keypress_popup_background_title) { Icons.Outlined.Palette }
        put(R.string.keypress_popup_text_color_title) { Icons.Outlined.Colorize }
        put(R.string.keypress_alternates_size_title) { Icons.Outlined.FormatSize }
        put(R.string.keypress_alternates_padding_title) { Icons.Outlined.Padding }
        put(R.string.keypress_alternates_columns_title) { Icons.Outlined.ViewWeek }
        put(R.string.keypress_alternates_nearest_title) { Icons.Outlined.SwapVert }
        put(R.string.keypress_alternates_hold_title) { Icons.Outlined.Gesture }
        put(R.string.keypress_popup_shape_title) { Icons.Outlined.Category }
        put(R.string.keypress_popup_radius_title) { Icons.Outlined.RoundedCorner }
        put(R.string.keypress_long_press_delay_title) { Icons.Outlined.Timer }
        put(R.string.keypress_repeat_start_title) { Icons.Outlined.Timer }
        put(R.string.keypress_delete_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_space_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_caps_lock_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.keypress_long_press_hints_title) { Icons.Outlined.Lightbulb }
        put(R.string.keypress_all_accents_title) { Icons.Outlined.Translate }
        put(R.string.keypress_symbols_numpad_title) { Icons.Outlined.Dialpad }
        put(R.string.keypress_ctrl_raw_title) { Icons.Outlined.Terminal }
        put(R.string.keypress_hold_actions_title) { Icons.Outlined.SelectAll }

        // ---- Emoji ----
        put(R.string.langemoji_emoji_toolbar_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.langemoji_emoji_full_bleed_title) { Icons.Outlined.Fullscreen }
        put(R.string.langemoji_emoji_prediction_title) { Icons.Outlined.AutoAwesome }
        put(R.string.langemoji_emoji_insert_mode_title) { Icons.Outlined.TouchApp }
        put(R.string.langemoji_emoji_grid_size_title) { Icons.Outlined.GridOn }
        put(R.string.langemoji_emoji_size_title) { Icons.Outlined.FormatSize }
        put(R.string.langemoji_emoji_tab_mode_title) { Icons.Outlined.History }
        put(R.string.langemoji_emoji_recents_title) { Icons.Outlined.Numbers }
        put(R.string.langemoji_emoji_clear_recents_title) { Icons.Outlined.DeleteSweep }
        put(R.string.langemoji_emoji_clear_history_title) { Icons.Outlined.DeleteSweep }
        put(R.string.langemoji_emoji_kaomoji_title) { Icons.Outlined.SentimentSatisfied }
        put(R.string.langemoji_emoji_long_press_name_title) { Icons.Outlined.Abc }
        put(R.string.langemoji_emoji_animated_title) { Icons.Outlined.Animation }
        put(R.string.langemoji_emoji_sticker_title) { Icons.Outlined.PhotoSizeSelectActual }
        put(R.string.langemoji_emoji_row_title) { Icons.Outlined.ViewWeek }
        put(R.string.langemoji_emoji_bar_content_title) { Icons.Outlined.ViewWeek }
        put(R.string.langemoji_emoji_bar_count_title) { Icons.Outlined.Numbers }
        put(R.string.langemoji_emoji_bar_scroll_title) { Icons.Outlined.SwapHoriz }
        put(R.string.langemoji_emoji_font_title) { Icons.Outlined.FontDownload }
        put(R.string.langemoji_emoji_skin_tone_title) { Icons.Outlined.Colorize }
        put(R.string.langemoji_emoji_tone_override_title) { Icons.Outlined.Colorize }
        put(R.string.langemoji_emoji_close_after_insert_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.langemoji_emoji_hide_unrenderable_title) { Icons.Outlined.VisibilityOff }
        put(R.string.langemoji_emoji_keywords_title) { Icons.Outlined.EmojiEmotions }

        // ---- Languages ----
        put(R.string.langemoji_lang_add_title) { Icons.Outlined.Add }
        put(R.string.langemoji_lang_keymaps_title) { Icons.Outlined.GridOn }
        put(R.string.langemoji_lang_per_app_toggle_title) { Icons.Outlined.Apps }
        put(R.string.langemoji_lang_os_switcher_title) { Icons.Outlined.Language }
        put(R.string.langemoji_lang_subtype_enabler_title) { Icons.Outlined.Settings }
        put(R.string.langemoji_lang_app_name_first_title) { Icons.Outlined.Apps }
        put(R.string.langemoji_lang_auto_download_title) { Icons.Outlined.CloudDownload }
        put(R.string.langemoji_lang_metered_title) { Icons.Outlined.SignalCellularAlt }
        put(R.string.langemoji_lang_autopair_title) { Icons.Outlined.Link }
        put(R.string.langemoji_lang_forget_apps_title) { Icons.Outlined.DeleteSweep }
        put(R.string.languages_conjunct_backspace_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.languages_numeral_system_title) { Icons.Outlined.Numbers }
        put(R.string.languages_custom_dictionaries_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.languages_emoji_keywords_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.languages_cjk_traditional_title) { Icons.Outlined.Translate }
        put(R.string.languages_cjk_fuzzy_title) { Icons.Outlined.BlurOn }
        put(R.string.languages_cjk_lazy_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.languages_cjk_fuzzy_pairs_reset_title) { Icons.Outlined.Restore }
        put(R.string.languages_fancy_style_row_title) { Icons.Outlined.TextFormat }
        put(R.string.languages_spelling_map_row_title) { Icons.Outlined.Spellcheck }

        // ---- Layout & size ----
        put(R.string.layout_number_row_title) { Icons.Outlined.Numbers }
        put(R.string.layout_number_row_height_title) { Icons.Outlined.Height }
        put(R.string.layout_number_row_in_symbols_title) { Icons.Outlined.Numbers }
        put(R.string.layout_number_row_shift_symbols_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.layout_symbols_return_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.layout_numeral_scope_title) { Icons.Outlined.Numbers }
        put(R.string.layout_key_height_title) { Icons.Outlined.Height }
        put(R.string.layout_bottom_row_height_title) { Icons.Outlined.Height }
        put(R.string.layout_side_padding_left_title) { Icons.Outlined.Padding }
        put(R.string.layout_side_padding_right_title) { Icons.Outlined.Padding }
        put(R.string.layout_key_spacing_title) { Icons.Outlined.SpaceBar }
        put(R.string.layout_keyboard_scale_title) { Icons.Outlined.ZoomOutMap }
        put(R.string.layout_bottom_padding_title) { Icons.Outlined.Padding }
        put(R.string.layout_keyboard_width_title) { Icons.Outlined.Straighten }
        put(R.string.layout_keyboard_position_title) { Icons.Outlined.OpenWith }
        put(R.string.layout_font_size_title) { Icons.Outlined.FormatSize }
        put(R.string.layout_follow_portrait_title) { Icons.Outlined.ScreenRotation }
        put(R.string.layout_variant_follows_portrait_label) { Icons.Outlined.ScreenRotation }
        put(R.string.layout_one_handed_title) { Icons.Outlined.PanTool }
        put(R.string.layout_split_title) { Icons.Outlined.VerticalSplit }
        put(R.string.layout_split_gap_title) { Icons.Outlined.SpaceBar }
        put(R.string.layout_split_large_only_title) { Icons.Outlined.TabletAndroid }
        put(R.string.layout_floating_title) { Icons.Outlined.PictureInPicture }
        put(R.string.layout_floating_width_title) { Icons.Outlined.Straighten }
        put(R.string.layout_floating_height_title) { Icons.Outlined.Height }
        put(R.string.layout_floating_reset_title) { Icons.Outlined.Restore }
        put(R.string.layout_reset_sizing_title) { Icons.Outlined.Restore }
        put(R.string.layout_comma_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.layout_globe_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.layout_swap_comma_globe_title) { Icons.Outlined.SwapHoriz }
        put(R.string.layout_editor_action_row_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.layout_editor_hint_title) { Icons.Outlined.Subtitles }
        put(R.string.layout_editor_role_title) { Icons.Outlined.Tune }
        put(R.string.layout_editor_show_shift_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.layout_editor_tablet_expand_title) { Icons.Outlined.TabletAndroid }
        put(R.string.layout_editor_persist_title) { Icons.Outlined.PushPin }
        put(R.string.layout_editor_theme_title) { Icons.Outlined.Palette }
        put(R.string.layout_editor_json_title) { Icons.Outlined.DataObject }
        put(R.string.layout_editor_composer_title) { Icons.Outlined.Keyboard }
        put(R.string.layout_editor_actual_size_title) { Icons.Outlined.Height }

        // ---- Keyboard modes / rows ----
        put(R.string.modes_enabled_title) { Icons.Outlined.Tune }
        put(R.string.modes_drag_edits_title) { Icons.Outlined.DragIndicator }
        put(R.string.modes_emoji_row_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.modes_symbol_row_title) { Icons.Outlined.Tag }
        put(R.string.modes_pinned_tools_title) { Icons.Outlined.PushPin }
        put(R.string.modes_pinned_behaviour_title) { Icons.Outlined.PushPin }
        put(R.string.modes_toolbox_order_title) { Icons.Outlined.Reorder }
        put(R.string.modes_symbol_sets_title) { Icons.Outlined.Tag }
        put(R.string.modes_manual_duration_title) { Icons.Outlined.Timer }
        put(R.string.modes_autocorrect_title) { Icons.Outlined.Spellcheck }
        put(R.string.modes_autocapitalize_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.modes_suggestions_title) { Icons.Outlined.Lightbulb }
        put(R.string.modes_layout_title) { Icons.Outlined.Keyboard }
        put(R.string.rows_symbol_row_title) { Icons.Outlined.Tag }
        put(R.string.rows_fancy_title) { Icons.Outlined.TextFormat }
        put(R.string.rows_dictionary_bar_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.languages_dictionary_use_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.languages_emoji_keywords_use_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.rows_symbol_row_height_title) { Icons.Outlined.Height }
        put(R.string.rows_reset_order_title) { Icons.Outlined.Restore }

        // ---- Photos ----
        put(R.string.photo_services_title) { Icons.Outlined.Wallpaper }
        put(R.string.photo_library_title) { Icons.Outlined.Collections }
        put(R.string.photo_rotation_on_title) { Icons.Outlined.Autorenew }
        put(R.string.photo_rotation_interval_title) { Icons.Outlined.Schedule }
        put(R.string.photo_rotation_topics_title) { Icons.Outlined.Category }
        put(R.string.photo_rotation_safe_title) { Icons.Outlined.Shield }
        put(R.string.photo_rotation_wide_title) { Icons.Outlined.AspectRatio }
        put(R.string.photo_rotation_metered_title) { Icons.Outlined.SignalCellularAlt }
        put(R.string.photo_rotation_pool_title) { Icons.Outlined.Inventory2 }
        put(R.string.photo_rotation_scope_title) { Icons.Outlined.Palette }
        put(R.string.photo_rotation_scope_pick_title) { Icons.Outlined.Checklist }
        put(R.string.photo_rotation_seed_palette_title) { Icons.Outlined.Colorize }
        put(R.string.photo_rotation_readability_title) { Icons.Outlined.Contrast }
        put(R.string.photo_rotation_key_opacity_title) { Icons.Outlined.Opacity }
        put(R.string.photo_rotation_budget_title) { Icons.Outlined.Storage }

        // ---- Plugins ----
        put(R.string.plugins_auto_disable_title) { Icons.Outlined.Block }

        // ---- Privacy ----
        put(R.string.privacy_learn_typing_title) { Icons.Outlined.School }
        put(R.string.privacy_system_dictionary_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.privacy_dict_shortcuts_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.privacy_incognito_title) { Icons.Outlined.VisibilityOff }
        put(R.string.privacy_auto_incognito_title) { Icons.Outlined.Public }
        put(R.string.privacy_backup_title) { Icons.Outlined.CloudUpload }
        put(R.string.privacy_delete_learned_words_title) { Icons.Outlined.DeleteSweep }

        // ---- Privacy: the fingerprint lock ----
        // The per-target rows on the configurator are absent on purpose: each
        // borrows the glyph of the screen or row it guards, so the list reads
        // as the places it names rather than as a column of padlocks.
        put(R.string.privacy_lock_title) { Icons.Outlined.Fingerprint }
        put(R.string.privacy_lock_enabled_title) { Icons.Outlined.Lock }
        put(R.string.privacy_lock_relock_title) { Icons.Outlined.Timer }
        put(R.string.privacy_lock_credential_title) { Icons.Outlined.Password }
        put(R.string.privacy_lock_enroll_title) { Icons.Outlined.Fingerprint }
        put(R.string.privacy_lock_storage_delete_title) { Icons.Outlined.DeleteSweep }
        put(R.string.privacy_lock_factory_reset_title) { Icons.Outlined.RestartAlt }
        put(R.string.privacy_lock_export_title) { Icons.Outlined.Save }

        // ---- Privacy: permissions ----
        put(R.string.privacy_permissions_title) { Icons.Outlined.Key }
        put(R.string.privacy_permissions_biometric_title) { Icons.Outlined.Fingerprint }
        put(R.string.privacy_permissions_mic_title) { Icons.Outlined.MicNone }
        put(R.string.privacy_permissions_camera_title) { Icons.Outlined.PhotoCamera }
        put(R.string.privacy_permissions_contacts_title) { Icons.Outlined.Contacts }
        put(R.string.privacy_permissions_calendar_title) { Icons.Outlined.CalendarMonth }
        put(R.string.privacy_permissions_images_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.privacy_permissions_storage_title) { Icons.Outlined.Folder }
        put(R.string.privacy_permissions_notifications_title) { Icons.Outlined.Notifications }
        put(R.string.privacy_permissions_usage_title) { Icons.Outlined.DataUsage }
        put(R.string.privacy_permissions_accessibility_title) { Icons.Outlined.Accessibility }
        put(R.string.privacy_permissions_internet_title) { Icons.Outlined.Public }
        put(R.string.privacy_permissions_network_state_title) { Icons.Outlined.SignalCellularAlt }
        put(R.string.privacy_permissions_vibrate_title) { Icons.Outlined.Vibration }

        // ---- Diagnostics ----
        put(R.string.shell_debug_log_system_title) { Icons.Outlined.Terminal }
        put(R.string.shell_debug_log_copy_title) { Icons.Outlined.ContentCopy }
        put(R.string.shell_debug_log_share_title) { Icons.Outlined.Share }
        put(R.string.shell_debug_log_crash_test_title) { Icons.Outlined.BugReport }

        // ---- Text expander ----
        put(R.string.expander_multi_expand_title) { Icons.Outlined.AltRoute }

        // ---- Typing ----
        put(R.string.typing_autocorrect_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_autocorrect_confidence_title) { Icons.Outlined.Tune }
        put(R.string.typing_autocorrect_adaptive_title) { Icons.Outlined.Tune }
        put(R.string.typing_timing_signal_title) { Icons.Outlined.Timer }
        put(R.string.typing_number_row_corrections_title) { Icons.Outlined.Pin }
        put(R.string.typing_autocorrect_splits_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_language_detection_title) { Icons.Outlined.Translate }
        put(R.string.typing_language_detection_strength_title) { Icons.Outlined.Tune }
        put(R.string.typing_register_priors_title) { Icons.Outlined.QuestionAnswer }
        put(R.string.typing_context_rerank_title) { Icons.Outlined.Psychology }
        put(R.string.typing_learn_threshold_title) { Icons.Outlined.School }
        put(R.string.typing_new_word_sightings_title) { Icons.Outlined.LibraryAdd }
        put(R.string.typing_ask_before_learning_title) { Icons.Outlined.HelpOutline }
        put(R.string.typing_offer_near_miss_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_undo_autocorrect_title) { Icons.AutoMirrored.Outlined.Undo }
        put(R.string.typing_undo_memory_title) { Icons.Outlined.History }
        put(R.string.typing_skip_all_caps_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_block_offensive_title) { Icons.Outlined.Block }
        put(R.string.typing_auto_apostrophe_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_auto_capitalize_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_double_space_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_double_space_window_title) { Icons.Outlined.Timer }
        put(R.string.typing_auto_space_punctuation_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_space_after_suggestion_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_wrap_selection_title) { Icons.Outlined.DataArray }
        put(R.string.typing_shift_recase_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_suggestions_title) { Icons.Outlined.Lightbulb }
        put(R.string.typing_suggestions_all_fields_title) { Icons.Outlined.Lightbulb }
        put(R.string.typing_punctuation_suggestions_title) { Icons.Outlined.MoreHoriz }
        put(R.string.typing_suggestions_first_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.typing_suggestion_slots_title) { Icons.Outlined.Numbers }
        put(R.string.typing_suggestion_scroll_title) { Icons.Outlined.SwapHoriz }
        put(R.string.typing_primary_center_title) { Icons.Outlined.CenterFocusStrong }
        put(R.string.typing_contact_names_title) { Icons.Outlined.Contacts }
        put(R.string.typing_contact_emails_title) { Icons.Outlined.AlternateEmail }
        put(R.string.typing_contact_emails_in_email_fields_title) { Icons.Outlined.AlternateEmail }
        put(R.string.typing_app_names_title) { Icons.Outlined.Apps }
        put(R.string.typing_inline_emoji_search_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.typing_inline_autofill_title) { Icons.Outlined.Password }
        put(R.string.typing_smart_replies_title) { Icons.Outlined.Quickreply }
        put(R.string.typing_personal_dictionary_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.typing_custom_dictionaries_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.typing_blacklist_title) { Icons.Outlined.VisibilityOff }
        put(R.string.typing_smart_chips_title) { Icons.Outlined.AutoAwesome }
        put(R.string.typing_smart_calc_title) { Icons.Outlined.Calculate }
        put(R.string.typing_smart_currency_title) { Icons.Outlined.CurrencyExchange }
        put(R.string.typing_smart_units_title) { Icons.Outlined.Straighten }
        put(R.string.typing_smart_tool_keywords_title) { Icons.Outlined.Bolt }
        put(R.string.typing_smart_hit_detection_title) { Icons.Outlined.AdsClick }
        put(R.string.typing_group_autopilot_title) { Icons.Outlined.AdsClick }
        put(R.string.typing_autopilot_strength_title) { Icons.Outlined.Tune }
        put(R.string.typing_autopilot_show_title) { Icons.Outlined.Visibility }
        put(R.string.typing_autopilot_size_title) { Icons.Outlined.ZoomOutMap }
        put(R.string.typing_autopilot_outline_title) { Icons.Outlined.CropFree }
        put(R.string.typing_smart_dates_title) { Icons.Outlined.CalendarMonth }
        put(R.string.typing_smart_weather_title) { Icons.Outlined.WbSunny }
        put(R.string.typing_smart_lookups_title) { Icons.Outlined.Search }
        put(R.string.typing_smart_intents_title) { Icons.Outlined.Translate }
        put(R.string.typing_smart_gifs_title) { Icons.Outlined.Gif }
        put(R.string.typing_smart_numbers_title) { Icons.Outlined.Numbers }
        put(R.string.typing_otp_chip_title) { Icons.Outlined.Password }
        put(R.string.typing_otp_access_title) { Icons.Outlined.Notifications }
        put(R.string.typing_otp_number_fields_title) { Icons.Outlined.Dialpad }
        put(R.string.typing_otp_expiry_title) { Icons.Outlined.Timer }
        put(R.string.typing_otp_dismiss_title) { Icons.Outlined.NotificationsOff }
        put(R.string.typing_otp_per_digit_title) { Icons.Outlined.Pin }
        put(R.string.typing_glide_typing_title) { Icons.Outlined.Gesture }
        put(R.string.typing_glide_picker_title) { Icons.Outlined.Gesture }
        put(R.string.typing_letter_swipe_action_title) { Icons.Outlined.Draw }
        put(R.string.typing_handwrite_dot_title) { Icons.Outlined.Timer }
        put(R.string.typing_gesture_cooldown_title) { Icons.Outlined.Timer }
        put(R.string.typing_space_glide_multiword_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_space_after_glide_title) { Icons.Outlined.SpaceBar }
        put(R.string.appearance_toolbar_placement_title) { Icons.Outlined.ViewAgenda }
        put(R.string.tooldetail_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_icon_colour_title) { Icons.Outlined.Colorize }
        put(R.string.tooldetail_icon_colour_start_title) { Icons.Outlined.Colorize }
        put(R.string.tooldetail_icon_colour_end_title) { Icons.Outlined.Gradient }
        put(R.string.typing_glide_apostrophe_title) { Icons.Outlined.FormatQuote }
        put(R.string.typing_glide_apostrophe_s_title) { Icons.Outlined.FormatQuote }
        put(R.string.typing_swipe_start_distance_title) { Icons.Outlined.Straighten }
        put(R.string.typing_trail_width_title) { Icons.Outlined.LineWeight }
        put(R.string.typing_trail_length_title) { Icons.Outlined.Timeline }
        put(R.string.typing_trail_opacity_title) { Icons.Outlined.Opacity }
        put(R.string.typing_glide_preview_title) { Icons.Outlined.Notifications }
        put(R.string.typing_glide_preview_height_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.typing_glide_preview_shift_title) { Icons.Outlined.SwapHoriz }
        put(R.string.typing_glide_preview_size_title) { Icons.Outlined.FormatSize }
        put(R.string.typing_glide_preview_color_title) { Icons.Outlined.Palette }
        put(R.string.typing_glide_preview_text_color_title) { Icons.Outlined.Colorize }
        put(R.string.typing_spacebar_language_arrows_title) { Icons.Outlined.SwapHoriz }
        put(R.string.typing_spacebar_display_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_space_cursor_2d_title) { Icons.Outlined.Mouse }
        put(R.string.typing_space_cursor_step_title) { Icons.Outlined.Speed }
        put(R.string.typing_space_swipe_down_hide_title) { Icons.Outlined.SwipeDown }
        put(R.string.typing_space_hold_keys_label) { Icons.Outlined.TouchApp }
        put(R.string.typing_backspace_swipe_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.typing_backspace_unit_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.typing_backspace_preview_title) { Icons.Outlined.Visibility }
        put(R.string.typing_backspace_step_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.typing_backspace_char_step_title) { Icons.Outlined.Speed }
        put(R.string.typing_shift_enter_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.typing_volume_cursor_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.typing_volume_cursor_media_title) { Icons.Outlined.MusicNote }
        put(R.string.typing_hardware_input_title) { Icons.Outlined.KeyboardAlt }
        put(R.string.typing_hw_shortcuts_title) { Icons.Outlined.Bolt }
        put(R.string.typing_hw_shortcuts_list_title) { Icons.Outlined.Keyboard }
        put(R.string.typing_hw_panel_nav_title) { Icons.Outlined.Gamepad }
        put(R.string.typing_hw_esc_title) { Icons.Outlined.Close }
        put(R.string.typing_hw_digit_chord_title) { Icons.Outlined.Pin }
        put(R.string.typing_hw_modifier_words_title) { Icons.Outlined.Abc }
        put(R.string.typing_hw_picker_timeout_title) { Icons.Outlined.Timer }
        put(R.string.typing_hw_suggestion_hotkeys_title) { Icons.Outlined.Numbers }
        put(R.string.typing_hw_suggestion_hints_title) { Icons.Outlined.Tag }
        put(R.string.typing_hw_lang_chord_title) { Icons.Outlined.Language }
        put(R.string.typing_hw_mac_title) { Icons.Outlined.KeyboardCommandKey }
        put(R.string.typing_hw_auto_show_title) { Icons.Outlined.Visibility }
        put(R.string.hardware_shortcuts_leader_subtitle) { Icons.Outlined.KeyboardCommandKey }

        // ---- Tools screen ----
        put(R.string.tools_colored_icons_title) { Icons.Outlined.Palette }
        put(R.string.tools_gradient_icons_title) { Icons.Outlined.Gradient }

        // ---- Tool pages: shared ----
        put(CommonR.string.common_enable) { Icons.Outlined.PowerSettingsNew }
        put(R.string.toolai_keyword_case_title) { Icons.Outlined.TextFormat }

        // ---- Tool pages ----
        put(R.string.tooldetail_emoji_all_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.clipboard_history_title) { Icons.Outlined.History }
        put(R.string.clipboard_suggest_recent_title) { Icons.Outlined.ContentPaste }
        put(R.string.clipboard_suggest_codes_title) { Icons.Outlined.Password }
        put(R.string.clipboard_toast_title) { Icons.Outlined.Notifications }
        put(R.string.clipboard_expiry_title) { Icons.Outlined.Timer }
        put(R.string.clipboard_max_title) { Icons.Outlined.Numbers }
        put(R.string.clipboard_sensitive_title) { Icons.Outlined.Shield }
        put(R.string.clipboard_detect_sensitive_title) { Icons.Outlined.Search }
        put(R.string.clipboard_sensitive_expiry_title) { Icons.Outlined.Timer }
        put(R.string.panel_layout_row_title) { Icons.Outlined.ViewAgenda }
        put(R.string.clipboard_full_bleed_title) { Icons.Outlined.Fullscreen }
        put(R.string.clipboard_pinned_last_title) { Icons.Outlined.PushPin }
        put(R.string.clipboard_search_title) { Icons.Outlined.Search }
        put(R.string.clipboard_password_paste_title) { Icons.Outlined.Password }
        put(R.string.clipboard_link_previews_title) { Icons.Outlined.Link }
        put(R.string.clipboard_entities_title) { Icons.Outlined.Tag }
        put(R.string.clipboard_chip_life_title) { Icons.Outlined.Timer }
        put(R.string.clipboard_phone_formats_title) { Icons.Outlined.Phone }
        put(R.string.clipboard_screenshots_title) { Icons.Outlined.Screenshot }
        put(R.string.clipboard_track_source_title) { Icons.Outlined.Apps }
        put(R.string.clipboard_storage_permission_title) { Icons.Outlined.Folder }
        put(R.string.clipboard_usage_permission_title) { Icons.Outlined.Lock }
        put(R.string.tooldetail_layout_nav_title) { Icons.Outlined.AspectRatio }
        put(R.string.tooldetail_flashlight_auto_off_title) { Icons.Outlined.FlashlightOff }
        put(R.string.tooldetail_compass_degrees_title) { Icons.Outlined.Explore }
        put(R.string.tooldetail_compass_qibla_title) { Icons.Outlined.Mosque }
        put(R.string.tooldetail_level_angles_title) { Icons.Outlined.Architecture }
        put(R.string.tooldetail_redo_ctrl_y_title) { Icons.AutoMirrored.Outlined.Redo }
        put(R.string.tooldetail_moon_southern_title) { Icons.Outlined.Public }
        put(R.string.tooldetail_weather_fahrenheit_title) { Icons.Outlined.Thermostat }
        put(R.string.tooldetail_weather_refresh_title) { Icons.Outlined.Schedule }
        put(R.string.tooldetail_calendar_hijri_title) { Icons.Outlined.CalendarMonth }
        put(R.string.toolai_weekend_title) { Icons.Outlined.Weekend }
        put(R.string.tooldetail_camera_front_title) { Icons.Outlined.Cameraswitch }
        put(R.string.tooldetail_camera_mirror_title) { Icons.Outlined.Flip }
        put(R.string.tooldetail_camera_fullframe_title) { Icons.Outlined.AspectRatio }
        put(R.string.tooldetail_camera_gallery_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.tooldetail_camera_shutter_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.tooldetail_camera_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.tooldetail_camera_timer_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_camera_resolution_title) { Icons.Outlined.PhotoSizeSelectLarge }
        put(R.string.tooldetail_launcher_sort_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.tooldetail_launcher_labels_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.tooldetail_launcher_recents_title) { Icons.Outlined.History }
        put(R.string.tooldetail_launcher_recents_count_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_launcher_drilldown_title) { Icons.Outlined.AppShortcut }
        put(R.string.tooldetail_launcher_non_exported_title) { Icons.Outlined.Lock }
        put(R.string.tooldetail_dictionary_auto_title) { Icons.Outlined.Search }
        // ---- Vocabulary ----
        put(R.string.tooldetail_vocab_packs_title) { Icons.Outlined.Inventory2 }
        put(R.string.tooldetail_vocab_lists_title) { Icons.Outlined.PlaylistAdd }
        put(R.string.tooldetail_vocab_review_title) { Icons.Outlined.Style }
        put(R.string.tooldetail_vocab_browse_title) { Icons.Outlined.AutoStories }
        put(R.string.tooldetail_vocab_nudges_title) { Icons.Outlined.Lightbulb }
        put(R.string.tooldetail_vocab_nudge_self_title) { Icons.Outlined.Abc }
        put(R.string.tooldetail_vocab_tap_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_vocab_scope_title) { Icons.Outlined.FilterList }
        put(R.string.tooldetail_vocab_level_title) { Icons.Outlined.Tune }
        put(R.string.tooldetail_vocab_cooldown_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_vocab_related_title) { Icons.Outlined.Link }
        put(R.string.tooldetail_vocab_translations_title) { Icons.Outlined.Translate }
        put(R.string.tooldetail_vocab_audio_source_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.tooldetail_vocab_accent_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.tooldetail_vocab_tts_rate_title) { Icons.Outlined.Speed }
        put(R.string.tooldetail_vocab_tts_pitch_title) { Icons.Outlined.GraphicEq }
        put(R.string.tooldetail_vocab_audio_test_title) { Icons.Outlined.PlayArrow }
        put(R.string.tooldetail_vocab_scheduler_title) { Icons.Outlined.Repeat }
        put(R.string.tooldetail_vocab_goal_title) { Icons.Outlined.Flag }
        put(R.string.tooldetail_vocab_wotd_card_title) { Icons.Outlined.Today }
        put(R.string.tooldetail_vocab_wotd_chip_title) { Icons.Outlined.Keyboard }
        put(R.string.tooldetail_text_edit_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.tooldetail_cursor_repeat_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_cursor_repeat_toolbox_title) { Icons.Outlined.GridView }
        put(R.string.tooldetail_select_mode_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_select_mode_taps_title) { Icons.Outlined.SelectAll }
        put(R.string.tooldetail_trackpad_step_x_title) { Icons.Outlined.SwapHoriz }
        put(R.string.tooldetail_trackpad_step_y_title) { Icons.Outlined.SwapVert }
        put(R.string.tooldetail_trackpad_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_trackpad_taps_title) { Icons.Outlined.SelectAll }
        put(R.string.tooldetail_trackpad_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.tooldetail_trackpad_trail_title) { Icons.Outlined.Gesture }
        put(R.string.tooldetail_numpad_calc_title) { Icons.Outlined.Calculate }
        put(R.string.tooldetail_incognito_learning_title) { Icons.Outlined.School }
        put(R.string.tooldetail_incognito_clipboard_title) { Icons.Outlined.ContentPaste }
        put(R.string.tooldetail_incognito_auto_nav_title) { Icons.Outlined.Public }
        put(R.string.tooldetail_power_now_title) { Icons.Outlined.BatterySaver }
        put(R.string.tooldetail_power_trigger_title) { Icons.Outlined.AutoMode }
        put(R.string.tooldetail_power_battery_title) { Icons.Outlined.BatteryAlert }
        put(R.string.tooldetail_power_charging_title) { Icons.Outlined.BatteryChargingFull }
        put(R.string.tooldetail_power_drop_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.tooldetail_power_drop_sound_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.tooldetail_power_drop_anim_title) { Icons.Outlined.Animation }
        put(R.string.tooldetail_power_drop_trail_title) { Icons.Outlined.Gesture }
        put(R.string.tooldetail_power_drop_chips_title) { Icons.Outlined.AutoAwesome }
        put(R.string.tooldetail_power_drop_network_title) { Icons.Outlined.CloudOff }
        put(R.string.tooldetail_power_drop_screenshot_title) { Icons.Outlined.Screenshot }
        put(R.string.tooldetail_power_drop_models_title) { Icons.Outlined.Memory }
        put(R.string.tooldetail_power_drop_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.tooldetail_power_drop_glide_title) { Icons.Outlined.Gesture }
        put(R.string.tooldetail_power_drop_stats_title) { Icons.Outlined.QueryStats }
        put(R.string.tooldetail_power_drop_media_pin_title) { Icons.Outlined.PushPin }

        // ---- Media control tool ----
        put(R.string.tooldetail_mediactl_pin_title) { Icons.Outlined.PushPin }
        put(R.string.tooldetail_mediactl_apps_title) { Icons.Outlined.MusicNote }
        put(R.string.tooldetail_mediactl_access_title) { Icons.Outlined.Notifications }
        put(R.string.musicapps_reset_title) { Icons.Outlined.Restore }

        // ---- Data saver ----
        put(R.string.datasaver_manual_title) { Icons.Outlined.DataSaverOn }
        put(R.string.datasaver_trigger_title) { Icons.Outlined.AutoMode }
        put(R.string.datasaver_link_previews_title) { Icons.Outlined.Link }
        put(R.string.datasaver_dictionary_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.datasaver_photos_title) { Icons.Outlined.Wallpaper }
        put(R.string.datasaver_weather_title) { Icons.Outlined.WbSunny }
        put(R.string.datasaver_rates_title) { Icons.Outlined.CurrencyExchange }
        put(R.string.datasaver_addons_title) { Icons.Outlined.Extension }
        put(R.string.datasaver_media_title) { Icons.Outlined.Gif }
        put(R.string.datasaver_search_title) { Icons.Outlined.Search }
        put(R.string.datasaver_animated_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.datasaver_downloads_title) { Icons.Outlined.CloudDownload }
        put(R.string.datasaver_ai_title) { Icons.Outlined.AutoAwesome }
        // The signpost left behind on the languages screen, where the metered
        // download confirmation used to live.
        put(R.string.langemoji_lang_metered_title) { Icons.Outlined.DataSaverOn }
        put(R.string.statistics_toggle_title) { Icons.Outlined.QueryStats }
        put(R.string.tooldetail_power_drop_popup_title) { Icons.Outlined.Notifications }
        put(R.string.tooldetail_autocorrect_title) { Icons.Outlined.Spellcheck }
        put(R.string.tooldetail_fancy_style_title) { Icons.Outlined.TextFormat }
        put(R.string.tooldetail_fancy_keep_title) { Icons.Outlined.PushPin }
        put(R.string.tooldetail_fancy_auto_off_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_fancy_language_nav_title) { Icons.Outlined.Language }
        put(R.string.tooldetail_custom_layout_layout_title) { Icons.Outlined.GridView }
        put(R.string.tooldetail_custom_layout_keymaps_nav_title) { Icons.Outlined.Keyboard }
        put(R.string.tooldetail_typing_nav_title) { Icons.Outlined.Keyboard }
        put(R.string.tooldetail_keypress_nav_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_themes_nav_title) { Icons.Outlined.Palette }
        put(R.string.tooldetail_handwriting_stylus_title) { Icons.Outlined.Draw }
        put(R.string.tooldetail_handwriting_auto_space_title) { Icons.Outlined.SpaceBar }
        put(R.string.tooldetail_handwriting_pause_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_handwriting_languages_title) { Icons.Outlined.Language }
        put(R.string.tooldetail_sticker_packs_title) { Icons.AutoMirrored.Outlined.StickyNote2 }
        put(R.string.tooldetail_media_full_bleed_title) { Icons.Outlined.Fullscreen }
        put(R.string.tooldetail_media_sticker_send_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.tooldetail_media_gif_send_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.tooldetail_media_limit_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_image_columns_title) { Icons.Outlined.GridOn }
        put(R.string.tooldetail_search_safe_title) { Icons.Outlined.Shield }
        put(R.string.tooldetail_search_count_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_ocr_select_all_title) { Icons.Outlined.SelectAll }
        put(R.string.tooldetail_qr_scan_auto_title) { Icons.Outlined.Bolt }
        put(R.string.tooldetail_qr_scan_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.tooldetail_qr_scan_preview_title) { Icons.Outlined.Link }
        put(R.string.tooldetail_doc_scan_gallery_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.voice_ui_title) { Icons.Outlined.ViewAgenda }
        put(R.string.voice_typing_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.voice_continuous_title) { Icons.Outlined.MicNone }
        put(R.string.voice_punctuation_title) { Icons.Outlined.MoreHoriz }
        put(R.string.voice_engine_title) { Icons.Outlined.GraphicEq }
        put(R.string.voice_translate_title) { Icons.Outlined.Translate }
        put(R.string.voice_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.models_whisper_fallback_title) { Icons.Outlined.Memory }
        put(R.string.tooldetail_grammar_dialect_title) { Icons.Outlined.Language }
        put(R.string.tooldetail_grammar_debounce_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_grammar_system_title) { Icons.Outlined.Public }
        put(R.string.tooldetail_grammar_no_suggestions_title) { Icons.Outlined.FormatUnderlined }
        put(R.string.tooldetail_wiki_markdown_title) { Icons.Outlined.Link }
        put(R.string.tooldetail_wiki_link_limit_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_chips_nav_title) { Icons.Outlined.Calculate }
        put(R.string.tooldetail_calc_degrees_title) { Icons.Outlined.Architecture }
        put(R.string.tooldetail_calc_precision_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_units_compound_title) { Icons.Outlined.Height }
        put(R.string.tooldetail_currency_decimals_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_currency_refresh_title) { Icons.Outlined.Refresh }
        put(R.string.tooldetail_currency_source_title) { Icons.Outlined.Cloud }
        put(R.string.tooldetail_crypto_enable_title) { Icons.Outlined.CurrencyBitcoin }
        put(R.string.tooldetail_crypto_decimals_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_crypto_refresh_title) { Icons.Outlined.Refresh }
        put(R.string.tooldetail_crypto_source_title) { Icons.Outlined.Cloud }
        put(R.string.tooldetail_crypto_coins_title) { Icons.Outlined.Toll }
        put(R.string.tooldetail_rate_fallback_title) { Icons.Outlined.CloudSync }
        put(R.string.tooldetail_qr_gen_size_title) { Icons.Outlined.PhotoSizeSelectLarge }
        put(R.string.tooldetail_qr_gen_send_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.tooldetail_qr_gen_gallery_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.tooldetail_qr_max_chars_title) { Icons.Outlined.Straighten }
        put(R.string.tooldetail_password_length_title) { Icons.Outlined.Straighten }
        put(R.string.tooldetail_password_uppercase_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.tooldetail_password_digits_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_password_symbols_title) { Icons.Outlined.Tag }
        put(R.string.tooldetail_password_ambiguous_title) { Icons.Outlined.Visibility }
        put(R.string.tooldetail_passphrase_words_title) { Icons.Outlined.Abc }
        put(R.string.tooldetail_passphrase_capitalize_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.tooldetail_passphrase_digit_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_modes_edit_title) { Icons.Outlined.Tune }
        put(R.string.toolai_typing_seconds_label) { Icons.Outlined.Timer }
        put(R.string.toolai_typing_words_label) { Icons.Outlined.Abc }
        put(R.string.toolai_typing_punctuation_title) { Icons.Outlined.MoreHoriz }
        put(R.string.toolai_typing_numbers_title) { Icons.Outlined.Numbers }
        put(R.string.toolai_typing_glide_title) { Icons.Outlined.Gesture }
        put(R.string.toolai_typing_suggestions_title) { Icons.Outlined.Lightbulb }
        put(R.string.toolai_typing_clear_records_title) { Icons.Outlined.DeleteSweep }
        put(R.string.toolai_ai_max_tokens_title) { Icons.Outlined.Numbers }
        put(R.string.toolai_ai_show_thinking_title) { Icons.Outlined.Psychology }
        put(R.string.toolai_ai_model_picker_title) { Icons.Outlined.Tune }
        put(R.string.toolai_ai_actions_title) { Icons.Outlined.AutoAwesome }
        put(R.string.toolai_ai_diff_title) { Icons.Outlined.Difference }
        put(R.string.toolai_ai_diff_first_title) { Icons.Outlined.Difference }
        put(R.string.toolai_ai_chat_nav_title) { Icons.AutoMirrored.Outlined.Chat }
        put(R.string.toolai_ai_history_title) { Icons.Outlined.History }
        put(R.string.toolai_ai_history_nav_title) { Icons.Outlined.History }
        put(R.string.toolai_ai_history_max_title) { Icons.Outlined.Numbers }
        put(R.string.toolai_continue_context_title) { Icons.AutoMirrored.Outlined.TextSnippet }
        put(R.string.toolai_keep_chats_title) { Icons.AutoMirrored.Outlined.Chat }
        put(R.string.toolai_delete_chats_title) { Icons.Outlined.DeleteSweep }
        put(R.string.toolai_ai_action_raw_title) { Icons.Outlined.Code }
        put(R.string.toolai_ai_action_ask_title) { Icons.Outlined.QuestionAnswer }
        put(R.string.toolai_ai_action_prefill_title) { Icons.Outlined.EditNote }
        put(R.string.toolai_ai_action_before_cursor_title) { Icons.AutoMirrored.Outlined.TextSnippet }
        put(R.string.toolai_ai_action_empty_field_title) { Icons.Outlined.CheckBoxOutlineBlank }
        put(R.string.toolai_ai_action_append_title) { Icons.AutoMirrored.Outlined.PlaylistAdd }
        put(R.string.toolai_ai_action_output_only_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.toolai_translate_into_title) { Icons.Outlined.Translate }
        put(R.string.customdict_emoji_auto_download_title) { Icons.Outlined.CloudDownload }

        // Rows the #43 reorganisation added: hub rows for the split pages, the
        // per-page resets, and the choice rows that replaced radio lists.
        put(R.string.appearance_toolbar_reset_title) { Icons.Outlined.Restore }
        put(R.string.appearance_toolbar_section_title) { Icons.Outlined.ViewDay }
        put(R.string.appearance_toolbox_reset_title) { Icons.Outlined.Restore }
        put(R.string.appearance_toolbox_section_title) { Icons.Outlined.Apps }
        put(R.string.backup_auto_group_title) { Icons.Outlined.Schedule }
        put(R.string.backup_include_group_title) { Icons.Outlined.Checklist }
        put(R.string.customdict_add_language_title) { Icons.Outlined.Add }
        put(R.string.fonts_english_header) { Icons.Outlined.TextFields }
        put(R.string.home_addons_title) { Icons.Outlined.Extension }
        put(R.string.home_datasaver_title) { Icons.Outlined.DataSaverOn }
        put(R.string.home_modes_title) { Icons.Outlined.ViewCarousel }
        put(R.string.home_rows_title) { Icons.Outlined.ViewAgenda }
        put(R.string.keypress_haptics_page_title) { Icons.Outlined.Vibration }
        put(R.string.keypress_popup_group_title) { Icons.Outlined.Preview }
        put(R.string.keypress_shortcuts_group_title) { Icons.Outlined.Shortcut }
        put(R.string.languages_translit_hints_row_title) { Icons.Outlined.Translate }
        put(R.string.langemoji_emoji_panel_title) { Icons.Outlined.GridView }
        put(R.string.languages_cjk_double_pinyin_title) { Icons.Outlined.Keyboard }
        put(R.string.languages_cjk_region_title) { Icons.Outlined.Public }
        put(R.string.layout_one_handed_group_title) { Icons.Outlined.PanTool }
        put(R.string.layout_size_position_title) { Icons.Outlined.FormatSize }
        put(R.string.typing_group_corrections_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_group_gestures_title) { Icons.Outlined.Gesture }
        put(R.string.typing_group_hardware_title) { Icons.Outlined.Keyboard }
        put(R.string.typing_group_otp_title) { Icons.Outlined.Password }
        put(R.string.typing_group_smart_chips_title) { Icons.Outlined.AutoAwesome }
        put(R.string.typing_group_suggestions_title) { Icons.Outlined.Lightbulb }
    }

    operator fun get(@StringRes id: Int): ImageVector? = map[id]?.invoke()
}

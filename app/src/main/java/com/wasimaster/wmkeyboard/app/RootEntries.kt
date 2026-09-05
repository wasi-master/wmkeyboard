package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.R

/** The headings the settings root is divided under, in order. */
internal enum class RootGroup(@StringRes val title: Int) {
    TYPING(R.string.home_group_typing_title),
    KEYBOARD(R.string.home_group_keyboard_title),
    FEATURES(R.string.home_group_features_title),
    ACCESSIBILITY(R.string.home_group_accessibility_title),
    DATA(R.string.home_group_data_title),
    ABOUT(R.string.home_group_about_title),
}

/** One row of the settings root: where it goes, and the words search finds it by. */
internal class RootEntry(
    val route: String,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @StringRes val keywords: Int,
    val group: RootGroup,
)

/**
 * The settings root, in display order. Languages first: it is the first thing
 * anyone looks for on a new keyboard. Key layouts is not here — it is a row
 * inside Languages, and one door is enough. What a first-week user never
 * needs (modes, add-ons, data saver, the physical keyboard) sits behind
 * Advanced, still one press away.
 */
internal val RootEntries: List<RootEntry> = listOf(
    RootEntry(
        "languages", Icons.Outlined.Language,
        R.string.home_languages_title, R.string.search_languages_subtitle,
        R.string.search_kw_languages, RootGroup.TYPING,
    ),
    RootEntry(
        "typing", Icons.Outlined.Keyboard,
        R.string.home_typing_title, R.string.home_typing_subtitle,
        R.string.search_kw_typing, RootGroup.TYPING,
    ),
    RootEntry(
        "keypress", Icons.Outlined.TouchApp,
        R.string.home_keypress_title, R.string.home_keypress_subtitle,
        R.string.search_kw_keypress, RootGroup.TYPING,
    ),
    RootEntry(
        "appearance", Icons.Outlined.Palette,
        R.string.home_appearance_title, R.string.home_appearance_subtitle,
        R.string.search_kw_appearance, RootGroup.KEYBOARD,
    ),
    RootEntry(
        "layout", Icons.Outlined.AspectRatio,
        R.string.home_layout_title, R.string.home_layout_subtitle,
        R.string.search_kw_layout, RootGroup.KEYBOARD,
    ),
    RootEntry(
        "rows", Icons.Outlined.ViewAgenda,
        R.string.home_rows_title, R.string.home_rows_subtitle,
        R.string.search_kw_rows, RootGroup.KEYBOARD,
    ),
    RootEntry(
        "emoji", Icons.Outlined.EmojiEmotions,
        R.string.home_emoji_title, R.string.home_emoji_subtitle,
        R.string.search_kw_emoji, RootGroup.FEATURES,
    ),
    RootEntry(
        "voice", Icons.Outlined.Mic,
        R.string.home_voice_title, R.string.home_voice_subtitle,
        R.string.search_kw_voice, RootGroup.FEATURES,
    ),
    RootEntry(
        "clipboard", Icons.Outlined.ContentPaste,
        R.string.home_clipboard_title, R.string.home_clipboard_subtitle,
        R.string.search_kw_clipboard, RootGroup.FEATURES,
    ),
    RootEntry(
        "expander", Icons.AutoMirrored.Outlined.TextSnippet,
        R.string.home_expander_title, R.string.home_expander_subtitle,
        R.string.search_kw_expander, RootGroup.FEATURES,
    ),
    RootEntry(
        "tools", Icons.Outlined.Widgets,
        R.string.home_tools_title, R.string.home_tools_subtitle,
        R.string.search_kw_tools, RootGroup.FEATURES,
    ),
    RootEntry(
        "accessibility", Icons.Outlined.Accessibility,
        R.string.home_accessibility_title, R.string.home_accessibility_subtitle,
        R.string.search_kw_accessibility, RootGroup.ACCESSIBILITY,
    ),
    RootEntry(
        "privacy", Icons.Outlined.Security,
        R.string.home_privacy_title, R.string.home_privacy_subtitle,
        R.string.search_kw_privacy, RootGroup.DATA,
    ),
    RootEntry(
        "backup", Icons.Outlined.Save,
        R.string.home_backup_title, R.string.home_backup_subtitle,
        R.string.search_kw_backup, RootGroup.DATA,
    ),
    RootEntry(
        "advanced", Icons.Outlined.Tune,
        R.string.home_advanced_title, R.string.home_advanced_subtitle,
        R.string.search_kw_advanced, RootGroup.DATA,
    ),
    RootEntry(
        "about", Icons.Outlined.Info,
        R.string.home_about_title, R.string.home_about_subtitle,
        R.string.search_kw_about, RootGroup.ABOUT,
    ),
)

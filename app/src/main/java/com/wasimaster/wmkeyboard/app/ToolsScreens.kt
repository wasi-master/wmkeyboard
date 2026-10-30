package com.wasimaster.wmkeyboard.app

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.core.ui.DisabledReason
import com.wasimaster.wmkeyboard.core.ui.ToolPaint
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.ime.R as ImeR
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.ime.ui.IconDefaults
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import com.wasimaster.wmkeyboard.core.settings.CursorTools
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.ActionOnlyTools
import com.wasimaster.wmkeyboard.core.settings.ToolBlocker
import com.wasimaster.wmkeyboard.core.settings.toolBlocker
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.extensionsResolved
import kotlinx.coroutines.launch

// ---- tools ----

/**
 * Whether a tool's settings page offers anything beyond the enable switch —
 * drives the "has more settings" marker on the tools list. Kept as the
 * caption-only exceptions so a new tool with options is marked by default.
 */
/**
 * Whether the tool's detail page offers anything beyond the enable switch —
 * gates the "has more settings" affordance in the tools list. Keep in sync
 * with [ToolDetailSettings]'s `when`: a tool whose page is just the toggle
 * (or a caption) doesn't earn the icon.
 */
private fun toolHasOptions(tool: ToolbarTool): Boolean = tool !in ToolsWithoutOptions
/** Asked once per row on a screen of sixty, so it is not built per call. */
private val ToolsWithoutOptions: Set<ToolbarTool> =
    setOf(
        ToolbarTool.SETTINGS,
        // Quick toggles/panels, not tools with settings of their own —
        // their options all live elsewhere (Appearance, Typing).
        ToolbarTool.THEMES, ToolbarTool.SOUND_HAPTICS, ToolbarTool.ONE_HANDED,
        // Just an enable toggle — the transport lives on the keyboard panel,
        // there is nothing to configure here.
        ToolbarTool.MEDIA_CONTROL,
        // Everything it changes is a Layout slider; the tool is only the
        // in-place way to drag them.
        ToolbarTool.RESIZE,
        // A shortcut, like Settings: its page only leads to the Statistics screen.
        ToolbarTool.STATISTICS,
    )
/**
 * The name of a tool on its settings screen, as a string resource the caller
 * resolves while it draws.
 *
 * The keyboard toolbar names the same tools in `toolLabelRes`, and half of them
 * word it identically: those reuse the keyboard's own resource rather than
 * carry a second copy for translators. The rest are the settings-screen wording,
 * which has room for the longer name the toolbar cannot fit ("Bubble level"
 * against "Level"), and those live in this module.
 */
@StringRes
internal fun toolTitle(tool: ToolbarTool): Int = when (tool) {
    ToolbarTool.EMOJI -> ImeR.string.ime_tool_emoji
    ToolbarTool.CLIPBOARD -> ImeR.string.ime_tool_clipboard
    ToolbarTool.CLIPBOARD_SEARCH -> ImeR.string.ime_tool_clipboard_search
    ToolbarTool.RECENT_COPIES -> ImeR.string.ime_tool_recent_copies
    ToolbarTool.LEAVE_FIELD -> ImeR.string.ime_tool_leave_field
    ToolbarTool.QUICK_NOTE -> ImeR.string.ime_tool_quick_note
    ToolbarTool.EMOJI_MIX -> ImeR.string.ime_tool_emoji_mix
    ToolbarTool.READ_BACK -> ImeR.string.ime_tool_read_back
    ToolbarTool.SNIPPETS -> ImeR.string.ime_tool_snippets
    ToolbarTool.TEXT_EDIT -> ImeR.string.ime_tool_text_edit
    ToolbarTool.TRACKPAD -> ImeR.string.ime_tool_trackpad
    ToolbarTool.ONE_HANDED -> R.string.fonts_tool_one_handed_title
    ToolbarTool.SPLIT -> R.string.fonts_tool_split_title
    ToolbarTool.FLOATING -> R.string.fonts_tool_floating_title
    ToolbarTool.PERSISTENT -> ImeR.string.ime_tool_persistent
    ToolbarTool.RESIZE -> ImeR.string.ime_tool_resize
    ToolbarTool.SETTINGS -> R.string.fonts_tool_settings_title
    ToolbarTool.FLASHLIGHT -> ImeR.string.ime_tool_flashlight
    ToolbarTool.COMPASS -> ImeR.string.ime_tool_compass
    ToolbarTool.LEVEL -> R.string.fonts_tool_level_title
    ToolbarTool.UNDO -> ImeR.string.ime_tool_undo
    ToolbarTool.REDO -> ImeR.string.ime_tool_redo
    ToolbarTool.MOON_PHASE -> R.string.fonts_tool_moon_phase_title
    ToolbarTool.WEATHER -> ImeR.string.ime_tool_weather
    ToolbarTool.LOCATION -> ImeR.string.ime_tool_location
    ToolbarTool.CLOCK -> ImeR.string.ime_tool_clock
    ToolbarTool.MUSIC_ID -> ImeR.string.ime_tool_music_id
    ToolbarTool.HOME_ASSISTANT -> ImeR.string.ime_tool_home_assistant
    ToolbarTool.SEND_FILES -> ImeR.string.ime_tool_send_files
    ToolbarTool.GALLERY -> ImeR.string.ime_tool_gallery
    ToolbarTool.CALENDAR -> ImeR.string.ime_tool_calendar
    ToolbarTool.INCOGNITO -> ImeR.string.ime_tool_incognito
    ToolbarTool.POWER_SAVING -> ImeR.string.ime_tool_power_saving
    ToolbarTool.THEMES -> ImeR.string.ime_tool_themes
    ToolbarTool.AUTOCORRECT -> ImeR.string.ime_tool_autocorrect
    ToolbarTool.SELECTION_ACTIONS -> ImeR.string.ime_tool_selection_actions
    ToolbarTool.PHONETIC_ENGLISH -> ImeR.string.ime_tool_phonetic_english
    ToolbarTool.SOUND_HAPTICS -> ImeR.string.ime_tool_sound_haptics
    ToolbarTool.NUMPAD -> ImeR.string.ime_tool_numpad
    ToolbarTool.HANDWRITING -> ImeR.string.ime_tool_handwriting
    ToolbarTool.CAMERA -> ImeR.string.ime_tool_camera
    ToolbarTool.DICTIONARY -> ImeR.string.ime_tool_dictionary
    ToolbarTool.VOCABULARY -> ImeR.string.ime_tool_vocabulary
    ToolbarTool.LEARN_FROM_TEXT -> ImeR.string.ime_tool_learn_from_text
    ToolbarTool.TRANSLATE -> ImeR.string.ime_tool_translate
    ToolbarTool.GIF -> ImeR.string.ime_tool_gif
    ToolbarTool.STICKER -> ImeR.string.ime_tool_sticker
    ToolbarTool.WEB_SEARCH -> R.string.fonts_tool_web_search_title
    ToolbarTool.IMAGE_SEARCH -> R.string.fonts_tool_image_search_title
    ToolbarTool.OCR -> R.string.fonts_tool_ocr_title
    ToolbarTool.QR_SCAN -> R.string.fonts_tool_qr_scan_title
    ToolbarTool.DOC_SCAN -> R.string.fonts_tool_doc_scan_title
    ToolbarTool.VOICE -> R.string.fonts_tool_voice_title
    ToolbarTool.GRAMMAR -> R.string.fonts_tool_grammar_title
    ToolbarTool.WIKIPEDIA -> ImeR.string.ime_tool_wikipedia
    ToolbarTool.SYMBOLS -> R.string.fonts_tool_symbols_title
    ToolbarTool.CALCULATOR -> ImeR.string.ime_tool_calculator
    ToolbarTool.UNIT_CONVERT -> R.string.fonts_tool_unit_convert_title
    ToolbarTool.CURRENCY -> R.string.fonts_tool_currency_title
    ToolbarTool.QR_GEN -> R.string.fonts_tool_qr_gen_title
    ToolbarTool.PASSWORD_GEN -> R.string.fonts_tool_password_gen_title
    ToolbarTool.TEXT_REPEAT -> R.string.fonts_tool_text_repeat_title
    ToolbarTool.TYPING_TEST -> R.string.fonts_tool_typing_test_title
    ToolbarTool.SPEED_TEST -> R.string.fonts_tool_speed_test_title
    ToolbarTool.MEDIA_CONTROL -> R.string.fonts_tool_media_control_title
    ToolbarTool.KDE_CONNECT -> ImeR.string.ime_tool_kde_connect
    ToolbarTool.STATISTICS -> ImeR.string.ime_tool_statistics
    ToolbarTool.FIND_REPLACE -> ImeR.string.ime_tool_find_replace
    ToolbarTool.NOTES -> ImeR.string.ime_tool_notes
    ToolbarTool.TODO -> ImeR.string.ime_tool_todo
    ToolbarTool.PLUGINS -> ImeR.string.ime_tool_plugins
    ToolbarTool.APP_LAUNCHER -> R.string.fonts_tool_app_launcher_title
    ToolbarTool.AI -> R.string.fonts_tool_ai_title
    ToolbarTool.AI_REPLY -> ImeR.string.ime_tool_ai_reply
    ToolbarTool.MODES -> R.string.fonts_tool_modes_title
    ToolbarTool.FANCY -> ImeR.string.ime_tool_fancy
    ToolbarTool.CUSTOM_LAYOUT -> ImeR.string.ime_tool_custom_layout
    ToolbarTool.CURSOR_LEFT -> R.string.fonts_tool_cursor_left_title
    ToolbarTool.CURSOR_RIGHT -> R.string.fonts_tool_cursor_right_title
    ToolbarTool.CURSOR_WORD_LEFT -> ImeR.string.ime_tool_cursor_word_left
    ToolbarTool.CURSOR_WORD_RIGHT -> ImeR.string.ime_tool_cursor_word_right
    ToolbarTool.CURSOR_UP -> R.string.fonts_tool_cursor_up_title
    ToolbarTool.CURSOR_DOWN -> R.string.fonts_tool_cursor_down_title
    ToolbarTool.CURSOR_HOME -> ImeR.string.ime_tool_cursor_home
    ToolbarTool.CURSOR_END -> ImeR.string.ime_tool_cursor_end
    ToolbarTool.PAGE_UP -> ImeR.string.ime_tool_page_up
    ToolbarTool.PAGE_DOWN -> ImeR.string.ime_tool_page_down
    ToolbarTool.SELECT_WORD -> ImeR.string.ime_tool_select_word
    ToolbarTool.SELECT_LINE -> ImeR.string.ime_tool_select_line
    ToolbarTool.SELECT_ALL -> CommonR.string.common_select_all
    ToolbarTool.SELECT_MODE -> ImeR.string.ime_tool_select_mode
    ToolbarTool.COPY -> ImeR.string.ime_tool_copy
    ToolbarTool.CUT -> ImeR.string.ime_tool_cut
    ToolbarTool.PASTE -> ImeR.string.ime_tool_paste
    ToolbarTool.HIDE_KEYBOARD -> R.string.fonts_tool_hide_keyboard_title
    ToolbarTool.LANGUAGE -> R.string.fonts_tool_language_title
}
/** The one-line description under a tool's name, as a string resource. */
@StringRes
internal fun toolDescription(tool: ToolbarTool): Int = when (tool) {
    ToolbarTool.EMOJI -> R.string.fonts_tool_emoji_desc
    ToolbarTool.CLIPBOARD -> R.string.fonts_tool_clipboard_desc
    ToolbarTool.CLIPBOARD_SEARCH -> R.string.fonts_tool_clipboard_search_desc
    ToolbarTool.RECENT_COPIES -> R.string.fonts_tool_recent_copies_desc
    ToolbarTool.LEAVE_FIELD -> R.string.fonts_tool_leave_field_desc
    ToolbarTool.QUICK_NOTE -> R.string.fonts_tool_quick_note_desc
    ToolbarTool.EMOJI_MIX -> R.string.fonts_tool_emoji_mix_desc
    ToolbarTool.READ_BACK -> R.string.fonts_tool_read_back_desc
    ToolbarTool.SNIPPETS -> R.string.fonts_tool_snippets_desc
    ToolbarTool.TEXT_EDIT -> R.string.fonts_tool_text_edit_desc
    ToolbarTool.TRACKPAD -> R.string.fonts_tool_trackpad_desc
    ToolbarTool.ONE_HANDED -> R.string.fonts_tool_one_handed_desc
    ToolbarTool.SPLIT -> R.string.fonts_tool_split_desc
    ToolbarTool.FLOATING -> R.string.fonts_tool_floating_desc
    ToolbarTool.PERSISTENT -> R.string.fonts_tool_persistent_desc
    ToolbarTool.RESIZE -> R.string.fonts_tool_resize_desc
    ToolbarTool.SETTINGS -> R.string.fonts_tool_settings_desc
    ToolbarTool.FLASHLIGHT -> R.string.fonts_tool_flashlight_desc
    ToolbarTool.COMPASS -> R.string.fonts_tool_compass_desc
    ToolbarTool.LEVEL -> R.string.fonts_tool_level_desc
    ToolbarTool.UNDO -> R.string.fonts_tool_undo_desc
    ToolbarTool.REDO -> R.string.fonts_tool_redo_desc
    ToolbarTool.MOON_PHASE -> R.string.fonts_tool_moon_phase_desc
    ToolbarTool.WEATHER -> R.string.fonts_tool_weather_desc
    ToolbarTool.LOCATION -> R.string.fonts_tool_location_desc
    ToolbarTool.CLOCK -> R.string.fonts_tool_clock_desc
    ToolbarTool.MUSIC_ID -> R.string.fonts_tool_music_id_desc
    ToolbarTool.HOME_ASSISTANT -> R.string.fonts_tool_home_assistant_desc
    ToolbarTool.SEND_FILES -> R.string.fonts_tool_send_files_desc
    ToolbarTool.GALLERY -> R.string.fonts_tool_gallery_desc
    ToolbarTool.CALENDAR -> R.string.fonts_tool_calendar_desc
    ToolbarTool.INCOGNITO -> R.string.fonts_tool_incognito_desc
    ToolbarTool.POWER_SAVING -> R.string.fonts_tool_power_saving_desc
    ToolbarTool.THEMES -> R.string.fonts_tool_themes_desc
    ToolbarTool.AUTOCORRECT -> R.string.fonts_tool_autocorrect_desc
    ToolbarTool.SELECTION_ACTIONS -> R.string.fonts_tool_selection_actions_desc
    ToolbarTool.PHONETIC_ENGLISH -> R.string.fonts_tool_phonetic_english_desc
    ToolbarTool.SOUND_HAPTICS -> R.string.fonts_tool_sound_haptics_desc
    ToolbarTool.NUMPAD -> R.string.fonts_tool_numpad_desc
    ToolbarTool.HANDWRITING -> R.string.fonts_tool_handwriting_desc
    ToolbarTool.CAMERA -> R.string.fonts_tool_camera_desc
    ToolbarTool.DICTIONARY -> R.string.fonts_tool_dictionary_desc
    ToolbarTool.VOCABULARY -> R.string.fonts_tool_vocabulary_desc
    ToolbarTool.LEARN_FROM_TEXT -> R.string.fonts_tool_learn_from_text_desc
    ToolbarTool.TRANSLATE -> R.string.fonts_tool_translate_desc
    ToolbarTool.GIF -> R.string.fonts_tool_gif_desc
    ToolbarTool.STICKER -> R.string.fonts_tool_sticker_desc
    ToolbarTool.WEB_SEARCH -> R.string.fonts_tool_web_search_desc
    ToolbarTool.IMAGE_SEARCH -> R.string.fonts_tool_image_search_desc
    ToolbarTool.OCR -> R.string.fonts_tool_ocr_desc
    ToolbarTool.QR_SCAN -> R.string.fonts_tool_qr_scan_desc
    ToolbarTool.DOC_SCAN -> R.string.fonts_tool_doc_scan_desc
    ToolbarTool.VOICE -> R.string.fonts_tool_voice_desc
    ToolbarTool.GRAMMAR -> R.string.fonts_tool_grammar_desc
    ToolbarTool.WIKIPEDIA -> R.string.fonts_tool_wikipedia_desc
    ToolbarTool.SYMBOLS -> R.string.fonts_tool_symbols_desc
    ToolbarTool.CALCULATOR -> R.string.fonts_tool_calculator_desc
    ToolbarTool.UNIT_CONVERT -> R.string.fonts_tool_unit_convert_desc
    ToolbarTool.CURRENCY -> R.string.fonts_tool_currency_desc
    ToolbarTool.QR_GEN -> R.string.fonts_tool_qr_gen_desc
    ToolbarTool.PASSWORD_GEN -> R.string.fonts_tool_password_gen_desc
    ToolbarTool.TEXT_REPEAT -> R.string.fonts_tool_text_repeat_desc
    ToolbarTool.TYPING_TEST -> R.string.fonts_tool_typing_test_desc
    ToolbarTool.SPEED_TEST -> R.string.fonts_tool_speed_test_desc
    ToolbarTool.MEDIA_CONTROL -> R.string.fonts_tool_media_control_desc
    ToolbarTool.KDE_CONNECT -> R.string.fonts_tool_kde_connect_desc
    ToolbarTool.STATISTICS -> R.string.fonts_tool_statistics_desc
    ToolbarTool.FIND_REPLACE -> R.string.fonts_tool_find_replace_desc
    ToolbarTool.NOTES -> R.string.fonts_tool_notes_desc
    ToolbarTool.TODO -> R.string.fonts_tool_todo_desc
    ToolbarTool.PLUGINS -> R.string.fonts_tool_plugins_desc
    ToolbarTool.APP_LAUNCHER -> R.string.fonts_tool_app_launcher_desc
    ToolbarTool.AI -> R.string.fonts_tool_ai_desc
    ToolbarTool.AI_REPLY -> R.string.fonts_tool_ai_reply_desc
    ToolbarTool.MODES -> R.string.fonts_tool_modes_desc
    ToolbarTool.FANCY -> R.string.fonts_tool_fancy_desc
    ToolbarTool.CUSTOM_LAYOUT -> R.string.fonts_tool_custom_layout_desc
    ToolbarTool.CURSOR_LEFT -> R.string.fonts_tool_cursor_left_desc
    ToolbarTool.CURSOR_RIGHT -> R.string.fonts_tool_cursor_right_desc
    ToolbarTool.CURSOR_WORD_LEFT -> R.string.fonts_tool_cursor_word_left_desc
    ToolbarTool.CURSOR_WORD_RIGHT -> R.string.fonts_tool_cursor_word_right_desc
    ToolbarTool.CURSOR_UP -> R.string.fonts_tool_cursor_up_desc
    ToolbarTool.CURSOR_DOWN -> R.string.fonts_tool_cursor_down_desc
    ToolbarTool.CURSOR_HOME -> R.string.fonts_tool_cursor_home_desc
    ToolbarTool.CURSOR_END -> R.string.fonts_tool_cursor_end_desc
    ToolbarTool.PAGE_UP -> R.string.fonts_tool_page_up_desc
    ToolbarTool.PAGE_DOWN -> R.string.fonts_tool_page_down_desc
    ToolbarTool.SELECT_WORD -> R.string.fonts_tool_select_word_desc
    ToolbarTool.SELECT_LINE -> R.string.fonts_tool_select_line_desc
    ToolbarTool.SELECT_ALL -> R.string.fonts_tool_select_all_desc
    ToolbarTool.SELECT_MODE -> R.string.fonts_tool_select_mode_desc
    ToolbarTool.COPY -> R.string.fonts_tool_copy_desc
    ToolbarTool.CUT -> R.string.fonts_tool_cut_desc
    ToolbarTool.PASTE -> R.string.fonts_tool_paste_desc
    ToolbarTool.HIDE_KEYBOARD -> R.string.fonts_tool_hide_keyboard_desc
    ToolbarTool.LANGUAGE -> R.string.fonts_tool_language_desc
}
internal fun toolIconFor(tool: ToolbarTool): androidx.compose.ui.graphics.vector.ImageVector =
    IconDefaults.forTool(tool)
/**
 * What a tool can be found by on the Tools screen: its name and its
 * description, run through the same folding the settings search uses, so a
 * query needs neither the accents nor the capitals of the language the app is
 * drawn in.
 *
 * Built from the resources the screen itself would draw, so it is the *shown*
 * names that are searched: a Bengali install is searched in Bengali.
 */
@Composable
private fun rememberToolSearchIndex(extensions: Set<Extension>): Map<ToolbarTool, String> {
    val resources = LocalContext.current.resources
    // The configuration is the composition's own handle on the locale, so a
    // language change rebuilds the index rather than leaving last language's
    // words in it.
    val configuration = LocalConfiguration.current
    return remember(resources, configuration, extensions) {
        ToolGroups.withExtensions(extensions).flatMapTo(LinkedHashSet()) { it.second }.associateWith { tool ->
            normalizeForSearch(
                resources.getString(toolTitle(tool)) + ' ' +
                    resources.getString(toolDescription(tool)),
            )
        }
    }
}

/**
 * The field that searches the tool menu, pinned under the bar so it stays in
 * reach however far the list below it is scrolled (#679): with sixty rows,
 * scrolling back up to search for the one not found was most of the work.
 */
@Composable
internal fun ToolsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.tools_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(CommonR.string.common_clear),
                    )
                }
            }
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The tool menu, grouped by what the tools do, under a field that searches it
 * ([ToolsSearchField], pinned by the screen).
 * Everything else — the enable switch and the tool's own options — lives one
 * level down.
 *
 * The list is about sixty rows, which is more than anyone reads to find the one
 * tool they came for, so the field is the way in and the groups are the way to
 * browse. While a query is being typed the groups and the colour switches are
 * put away: a result list under a heading called "Panels" would be saying
 * something untrue about what is in it.
 */
@Composable
internal fun ToolsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    query: String,
    onOpenTool: (ToolbarTool) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // A switched-off bundle's tools (Extensions) are not on this list, in
    // its search, or in any picker built from the same groups.
    val extensions = settings.watch { it.extensionsResolved() }
    val searchIndex = rememberToolSearchIndex(extensions)
    // Every word of the query has to be somewhere in the tool's name or its
    // description. Substring rather than whole word, because a name is found
    // halfway through typing it.
    val matches = remember(query, searchIndex) {
        val tokens = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) emptyList()
        else searchIndex.keys.filter { tool ->
            val haystack = searchIndex.getValue(tool)
            tokens.all { haystack.contains(it) }
        }
    }
    val searching = query.isNotBlank()

    // Resolved once per screen, not once per row: this list is ~60 rows long,
    // and every one of them recomposes whenever any tool is switched on or off.
    val paints = settings.watch { s -> ToolbarTool.entries.associateWith { toolAccentPaint(it, s) } }
    val optionsDesc = stringResource(R.string.tools_has_options_desc)

    if (searching) {
        if (matches.isEmpty()) {
            CaptionText(stringResource(R.string.tools_search_empty, query))
            return
        }
        SettingsGroup(stringResource(R.string.tools_search_results_title)) {
            for (tool in matches) {
                item {
                    ToolRow(
                        tool = tool,
                        paint = paints[tool],
                        blocker = settings.watch { toolBlocker(tool, it) },
                        enabled = settings.watch { tool in it.enabledTools },
                        optionsDesc = optionsDesc,
                        onToggle = { on -> scope.launch { repository.setToolEnabled(tool, on) } },
                        onOpen = { onOpenTool(tool) },
                    )
                }
            }
        }
        return
    }

    val colored = settings.watch { it.coloredToolIcons }
    ToggleSetting(
        title = R.string.tools_colored_icons_title,
        subtitle = stringResource(R.string.tools_colored_icons_subtitle),
        checked = colored,
        onChange = { scope.launch { repository.setColoredToolIcons(it) } },
        default = SettingsDefaults.coloredToolIcons,
    )
    // Nested under the switch above rather than shown greyed out: with the
    // colours off there is nothing for a gradient to be made of, so the row
    // would be asking about something that cannot happen.
    if (colored) {
        ToggleSetting(
            title = R.string.tools_gradient_icons_title,
            subtitle = stringResource(R.string.tools_gradient_icons_subtitle),
            checked = settings.watch { it.toolIconGradients },
            info = stringResource(R.string.tools_gradient_icons_info),
            onChange = { scope.launch { repository.setToolIconGradients(it) } },
            default = SettingsDefaults.toolIconGradients,
        )
    }
    val hasColourOverrides = settings.watch {
        it.toolColorOverrides.isNotEmpty() || it.toolColorEndOverrides.isNotEmpty()
    }
    if (colored && hasColourOverrides) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { scope.launch { repository.clearToolColors() } }) {
                Text(stringResource(R.string.tools_reset_colors_action))
            }
        }
    }
    // Not in a Play build, which cannot hold the grant behind it.
    if (ChannelFeatures.FILE_MANAGER) {
        ToggleSetting(
            title = R.string.tools_file_manager_title,
            subtitle = stringResource(R.string.tools_file_manager_subtitle),
            checked = settings.watch { it.builtInFileManager },
            onChange = { scope.launch { repository.setBuiltInFileManager(it) } },
            default = SettingsDefaults.builtInFileManager,
        )
    }
    // Panels as one group or two (#759). Above the groups it reshapes, so
    // the switch and what it changes are on screen together.
    val splitPanels = settings.watch { it.appUi.splitToolPanels }
    ToggleSetting(
        title = R.string.tools_split_panels_title,
        subtitle = stringResource(R.string.tools_split_panels_subtitle),
        checked = splitPanels,
        onChange = { scope.launch { repository.setSplitToolPanels(it) } },
        default = SettingsDefaults.appUi.splitToolPanels,
    )
    val groups = (if (splitPanels) SplitToolGroups else ToolGroups).withExtensions(extensions)
    // Only the group titles need a composition; each grouping itself is fixed.
    val allGroups = groups.map { (title, tools) -> stringResource(title) to tools }
    // Resolved once per grouping, not per recomposition: the keys go into
    // saved settings, so they have to be the same string every time the
    // screen is built.
    val resources = LocalContext.current.resources
    val foldKeys = remember(resources, groups) {
        groups.map { (titleRes, _) -> toolGroupFoldKey(resources, titleRes) }
    }
    // Composing the sixty rows is deferred and staggered by SettingsGroup
    // itself now — see [rememberGroupRevealed] — so the screen no longer
    // needs its own gate on the opening animation.
    val intro = stringResource(R.string.tools_intro_info)
    allGroups.forEachIndexed { index, (groupTitle, tools) ->
        // The one explanation of what a tool is rides on the first heading.
        SettingsGroup(
            groupTitle,
            foldKey = foldKeys[index],
            info = intro.takeIf { index == 0 },
            // A closed group names the tools it holds. Eight headings and
            // eight counts would be a menu that says how big it is and not
            // what is in it, and the whole point of closing the groups is to
            // get the sixty rows off the screen without hiding them.
            foldSummary = { tools.map { stringResource(toolTitle(it)) }.joinToString(", ") },
        ) {
            for (tool in tools) {
                item {
                    ToolRow(
                        tool = tool,
                        paint = paints[tool],
                        // A blocked tool cannot be switched on at all: the
                        // keyboard would draw a button whose panel only
                        // apologises. The row still opens, because what
                        // unblocks it (a key field, a link to Key layouts) is
                        // inside.
                        blocker = settings.watch { toolBlocker(tool, it) },
                        enabled = settings.watch { tool in it.enabledTools },
                        optionsDesc = optionsDesc,
                        onToggle = { on -> scope.launch { repository.setToolEnabled(tool, on) } },
                        onOpen = { onOpenTool(tool) },
                    )
                }
            }
        }
    }
}

/**
 * A tool group's fold key, taken from the name of its title resource.
 *
 * Not the numeric id, which a build can renumber, and not the group's index,
 * which the F-Droid build can shift by dropping a whole group. The key is
 * joined to the screen route and saved in `AppUiSettings.advancedOpen`, so a
 * key that moves would reopen the wrong group.
 */
private fun toolGroupFoldKey(resources: Resources, @StringRes titleRes: Int): String =
    resources.getResourceEntryName(titleRes)
        .removePrefix("tools_group_")
        .removeSuffix("_title")

/**
 * One tool on the Tools list: its glyph in its own colour, its name and
 * description, the marker for a tool that has more settings, and the switch.
 *
 * Its own composable, and taking only what it draws, so that switching one tool
 * on recomposes that one row rather than all sixty — the whole screen reads
 * `settings`, so a row that took the settings object could not be skipped.
 */
@Composable
internal fun ToolRow(
    tool: ToolbarTool,
    paint: ToolPaint?,
    /** Why the tool cannot run, or null; the row says so in place of its description. */
    blocker: ToolBlocker?,
    enabled: Boolean,
    optionsDesc: String,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val route = toolRoute(tool)
    val usable = blocker == null
    val title = stringResource(toolTitle(tool))
    val subtitle = when (blocker) {
        null -> stringResource(toolDescription(tool))
        ToolBlocker.NEEDS_SEARCH_KEY -> stringResource(R.string.tools_needs_key_subtitle)
        ToolBlocker.NEEDS_SEARCH_INSTANCE -> stringResource(R.string.tools_needs_instance_subtitle)
        ToolBlocker.NEEDS_SECONDARY_LAYOUT -> stringResource(R.string.tools_needs_layout_subtitle)
        ToolBlocker.VOICE_OFF -> stringResource(R.string.tools_voice_off_subtitle)
        ToolBlocker.NEEDS_MIX_SOURCE -> stringResource(R.string.tools_needs_mix_source_subtitle)
        ToolBlocker.NEEDS_MUSIC_PROVIDER -> stringResource(R.string.tools_needs_music_provider_subtitle)
    }
    val row: @Composable () -> Unit = {
        WmRow(
            title = title,
            subtitle = subtitle,
            leading = {
                SlotIcon(
                    IconSlots.forTool(tool),
                    contentDescription = null,
                    modifier = Modifier.wmSharedElement(takeOffKey("icon", route)),
                    tint = paint?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    brush = paint?.brush,
                )
            },
            flightTo = route,
            subtitleFlies = true,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (toolHasOptions(tool)) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = optionsDesc,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = usable && enabled,
                        onCheckedChange = onToggle,
                        enabled = usable,
                        modifier = Modifier.wmSharedElement(takeOffKey("switch", route)),
                    )
                }
            },
            onClick = onOpen,
        )
    }
    // The row still opens, only its switch is greyed out: the hold says why,
    // and sends the user to the tool's page, which holds every fix.
    if (usable) {
        row()
    } else {
        HighlightableRow(title, disabledReason = DisabledReason(subtitle, route = route), content = row)
    }
}
/** The Panels group, in the order the Tools screen lists it. */
private val PanelTools = listOf(
    ToolbarTool.EMOJI, ToolbarTool.CLIPBOARD, ToolbarTool.SNIPPETS, ToolbarTool.NOTES, ToolbarTool.TODO,
    ToolbarTool.TEXT_EDIT, ToolbarTool.TRACKPAD, ToolbarTool.NUMPAD, ToolbarTool.HANDWRITING,
    ToolbarTool.VOICE, ToolbarTool.CAMERA, ToolbarTool.GALLERY, ToolbarTool.DICTIONARY, ToolbarTool.VOCABULARY,
    ToolbarTool.LEARN_FROM_TEXT, ToolbarTool.FIND_REPLACE, ToolbarTool.GRAMMAR, ToolbarTool.APP_LAUNCHER, ToolbarTool.MEDIA_CONTROL,
    ToolbarTool.MUSIC_ID, ToolbarTool.KDE_CONNECT, ToolbarTool.SEND_FILES,
)

/**
 * The panels that go to Connect and media when Panels is split: media, other
 * apps and other devices. The rest of [PanelTools] is Writing.
 */
private val ConnectPanels = setOf(
    ToolbarTool.CAMERA, ToolbarTool.GALLERY, ToolbarTool.APP_LAUNCHER, ToolbarTool.MEDIA_CONTROL, ToolbarTool.MUSIC_ID,
    ToolbarTool.KDE_CONNECT, ToolbarTool.SEND_FILES,
)

/**
 * The Tools screen's sections, as string resource and tools.
 *
 * A top-level value rather than a list built in composition: the grouping
 * never changes, and rebuilding it (and the sets behind the safety net below)
 * on every recomposition of a screen this size is work for nothing.
 *
 * The last group is the safety net: a tool added to the enum but forgotten
 * here still gets a settings entry, because this menu is the only path to a
 * tool's own options. Tools this build cannot provide (F-Droid's has fewer) are
 * filtered out, and a group left empty by that is dropped.
 *
 * This is the grouping every tool picker uses. The Tools screen alone can
 * show [SplitToolGroups] instead, when the user asks for it.
 *
 * Below the two panel lists on purpose: top-level values are set in the order
 * the file declares them, and these two read those lists as they are built.
 */
internal val ToolGroups: List<Pair<Int, List<ToolbarTool>>> = buildToolGroups(splitPanels = false)

/**
 * [ToolGroups] with Panels shown as two groups, Writing and Connect and
 * media (#759). Only the Tools screen offers it, behind
 * `AppUiSettings.splitToolPanels`: a picker is opened to find one tool, and a
 * second shape of the same list there would only be one more to learn.
 */
internal val SplitToolGroups: List<Pair<Int, List<ToolbarTool>>> = buildToolGroups(splitPanels = true)

private fun buildToolGroups(splitPanels: Boolean): List<Pair<Int, List<ToolbarTool>>> = buildList {
    if (splitPanels) {
        add(R.string.tools_group_writing_title to PanelTools.filterNot { it in ConnectPanels })
        add(R.string.tools_group_connect_title to PanelTools.filter { it in ConnectPanels })
    } else {
        add(R.string.tools_group_panels_title to PanelTools)
    }
    add(
        R.string.tools_group_scanners_title to listOf(
            ToolbarTool.OCR, ToolbarTool.QR_SCAN, ToolbarTool.DOC_SCAN,
        ),
    )
    add(
        R.string.tools_group_online_title to listOf(
            ToolbarTool.TRANSLATE, ToolbarTool.GIF, ToolbarTool.STICKER,
            ToolbarTool.WEB_SEARCH, ToolbarTool.IMAGE_SEARCH,
            ToolbarTool.WIKIPEDIA, ToolbarTool.CURRENCY, ToolbarTool.SPEED_TEST, ToolbarTool.AI, ToolbarTool.AI_REPLY,
        ),
    )
    add(
        R.string.tools_group_create_title to listOf(
            ToolbarTool.SYMBOLS, ToolbarTool.CALCULATOR, ToolbarTool.UNIT_CONVERT,
            ToolbarTool.QR_GEN, ToolbarTool.PASSWORD_GEN, ToolbarTool.TEXT_REPEAT, ToolbarTool.READ_BACK, ToolbarTool.TYPING_TEST,
        ),
    )
    add(
        R.string.tools_group_modes_title to listOf(
            ToolbarTool.MODES, ToolbarTool.ONE_HANDED, ToolbarTool.SPLIT, ToolbarTool.FLOATING,
            ToolbarTool.PERSISTENT, ToolbarTool.RESIZE,
        ),
    )
    add(R.string.tools_group_cursor_title to (CursorTools + ToolbarTool.HIDE_KEYBOARD))
    add(
        R.string.tools_group_quick_actions_title to listOf(
            ToolbarTool.UNDO, ToolbarTool.REDO,
            ToolbarTool.COPY, ToolbarTool.CUT, ToolbarTool.PASTE,
            ToolbarTool.AUTOCORRECT, ToolbarTool.SELECTION_ACTIONS, ToolbarTool.PHONETIC_ENGLISH,
            ToolbarTool.LANGUAGE, ToolbarTool.FANCY, ToolbarTool.CUSTOM_LAYOUT, ToolbarTool.INCOGNITO, ToolbarTool.SOUND_HAPTICS,
            ToolbarTool.THEMES, ToolbarTool.POWER_SAVING, ToolbarTool.SETTINGS, ToolbarTool.STATISTICS,
        ),
    )
    add(
        R.string.tools_group_utilities_title to listOf(
            ToolbarTool.FLASHLIGHT, ToolbarTool.COMPASS, ToolbarTool.LEVEL,
            ToolbarTool.CALENDAR, ToolbarTool.CLOCK, ToolbarTool.WEATHER, ToolbarTool.LOCATION, ToolbarTool.HOME_ASSISTANT,
            ToolbarTool.MOON_PHASE, ToolbarTool.PLUGINS,
        ),
    )
    val grouped = flatMapTo(HashSet()) { it.second }
    // Action-only tools have no tile: the binding pickers list them under Other.
    val ungrouped = ToolbarTool.entries.filterNot { it in grouped || it in ActionOnlyTools }
    if (ungrouped.isNotEmpty()) add(R.string.tools_group_other_title to ungrouped)
}.map { (title, tools) -> title to tools.filter(::isSupportedTool) }
    .filter { it.second.isNotEmpty() }
/**
 * A tool's own settings page, as flights name it. Not the navigation route
 * (`tool/{toolName}`), which is the same string for every tool and would put
 * one key on all of them.
 */
internal fun toolRoute(tool: ToolbarTool): String = "tool/${tool.name}"
/** A tool's glyph at heading size — the icon pack's, if the user installed one. */
@Composable
internal fun ToolGlyph(tool: ToolbarTool, brush: Brush? = null) {
    SlotIcon(
        IconSlots.forTool(tool),
        contentDescription = null,
        modifier = Modifier.size(HeaderGlyphSize),
        brush = brush,
    )
}

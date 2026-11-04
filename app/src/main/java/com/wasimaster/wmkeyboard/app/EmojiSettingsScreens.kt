package com.wasimaster.wmkeyboard.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.settings.EmojiBarContent
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.EmojiBarCountRange
import com.wasimaster.wmkeyboard.core.settings.EmojiGridCellSizeRange
import com.wasimaster.wmkeyboard.core.settings.EmojiTabWidthRange
import com.wasimaster.wmkeyboard.core.settings.EmojiGridColumnsRange
import com.wasimaster.wmkeyboard.core.settings.EmojiGridEmojiSizeRange
import com.wasimaster.wmkeyboard.core.settings.EmojiGridRowHeightRange
import com.wasimaster.wmkeyboard.core.settings.EmojiRecentsRange
import com.wasimaster.wmkeyboard.core.settings.MediaPanelExtraHeightRange
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.BarRow
import com.wasimaster.wmkeyboard.core.settings.modesOverridingRow
import com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice
import com.wasimaster.wmkeyboard.core.settings.EmojiSkinTone
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.core.settings.EmojiInsertMode
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import com.wasimaster.wmkeyboard.core.settings.MediaSwitcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.emoji.EmojiSearchExamples
import com.wasimaster.wmkeyboard.core.fonts.FontFile
import com.wasimaster.wmkeyboard.core.fonts.FontImportResult
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.content.R as ContentR
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch

// ---- emoji ----

@Composable
internal fun EmojiSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The slider readout is a plain lambda, so its format string is resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    // What decides which rows the groups hold; each row reads its own value.
    val predictionOn = settings.watch { it.emojiPrediction }
    val barMode = settings.watch { it.emojiBarMode }
    val emojiRowOverridden = settings.watch { it.modesOverridingRow(BarRow.EMOJI).isNotEmpty() }
    val emojiButtonOn = settings.watch { it.emojiToolbar }
    val stripOwnSize = settings.watch { it.toolbarBehavior.stripOwnSize }
    val dpFormat = stringResource(R.string.typing_value_dp)
    val extensions = LocalExtensions.current
    SettingsGroup(stringResource(R.string.langemoji_emoji_access_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_emoji_toolbar_title,
                stringResource(R.string.langemoji_emoji_toolbar_subtitle),
                settings.watch { it.emojiToolbar },
                info = stringResource(R.string.langemoji_emoji_toolbar_info),
                default = SettingsDefaults.emojiToolbar,
            ) { scope.launch { repository.setEmojiToolbar(it) } }
        }
        // Which tools stay beside the suggestions, in order (#462). Here, beside
        // the switch that shows them, because this is the row people come to
        // when they want the emoji button to be something else, or more.
        item(visible = emojiButtonOn, level = SettingLevel.STANDARD) {
            val shortcuts = settings.watch { it.toolbarBehavior.stripShortcuts }
            var picking by remember { mutableStateOf(false) }
            NavRow(
                title = R.string.langemoji_strip_shortcut_title,
                subtitle = stringResource(R.string.langemoji_strip_shortcut_subtitle),
                value = stringResource(R.string.langemoji_strip_tools_add_value),
            ) { picking = true }
            if (picking) {
                ToolPickerDialog(
                    title = stringResource(R.string.langemoji_strip_shortcut_title),
                    current = null,
                    options = ToolbarTool.entries.filter { isSupportedTool(it) && it !in shortcuts },
                    onDismiss = { picking = false },
                    onPick = { picked ->
                        picking = false
                        if (picked != null) scope.launch { repository.setStripShortcuts(shortcuts + picked) }
                    },
                )
            }
        }
        // The chosen buttons themselves: drag to change the order they sit in
        // beside the suggestions, the bin to take one off.
        item(visible = emojiButtonOn) {
            val shortcuts = settings.watch { it.toolbarBehavior.stripShortcuts }
            // Every tool's name, not only the chosen ones': the list adopts a
            // change a frame after it lands, so a tool just taken off is still
            // drawn once more by name.
            val names = ToolbarTool.entries.associateWith { stringResource(toolTitle(it)) }
            ReorderableColumn(
                items = shortcuts,
                label = { names[it] ?: it.name },
                onReorder = { order -> scope.launch { repository.setStripShortcuts(order) } },
                onDelete = { tool -> scope.launch { repository.setStripShortcuts(shortcuts - tool) } },
                keepLast = false,
            )
        }
        // The toolbox launcher among them (#970).
        item(visible = emojiButtonOn, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.langemoji_strip_toolbox_title,
                stringResource(R.string.langemoji_strip_toolbox_subtitle),
                settings.watch { it.toolbarBehavior.stripToolbox },
                default = SettingsDefaults.toolbarBehavior.stripToolbox,
            ) { scope.launch { repository.setStripToolbox(it) } }
        }
        // Which end of the strip they sit at.
        item(visible = emojiButtonOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_strip_at_end_title,
                stringResource(R.string.langemoji_strip_at_end_subtitle),
                settings.watch { it.toolbarBehavior.stripShortcutsAtEnd },
                default = SettingsDefaults.toolbarBehavior.stripShortcutsAtEnd,
            ) { scope.launch { repository.setStripShortcutsAtEnd(it) } }
        }
        // Their size, apart from the toolbar's tools (which the Toolbar page's
        // sliders size). Off, a tool on both looks the same in either spot.
        item(visible = emojiButtonOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_strip_own_size_title,
                stringResource(R.string.langemoji_strip_own_size_subtitle),
                settings.watch { it.toolbarBehavior.stripOwnSize },
                default = SettingsDefaults.toolbarBehavior.stripOwnSize,
            ) { scope.launch { repository.setStripOwnSize(it) } }
        }
        item(visible = emojiButtonOn && stripOwnSize, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.langemoji_strip_tool_width_title,
                subtitle = stringResource(R.string.langemoji_strip_tool_width_subtitle),
                value = settings.watch { it.toolbarBehavior.stripToolWidthDp }.toFloat(),
                // Down to 20 dp, a button about as wide as its icon (#970).
                range = 20f..64f,
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbarBehavior.stripToolWidthDp.toFloat(),
            ) { scope.launch { repository.setStripToolWidthDp(it.roundToInt()) } }
        }
        item(visible = emojiButtonOn && stripOwnSize, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.langemoji_strip_icon_size_title,
                subtitle = stringResource(R.string.langemoji_strip_icon_size_subtitle),
                value = settings.watch { it.toolbarBehavior.stripIconSizeDp }.toFloat(),
                range = 14f..30f,
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbarBehavior.stripIconSizeDp.toFloat(),
            ) { scope.launch { repository.setStripIconSizeDp(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_emoji_full_bleed_title,
                stringResource(R.string.langemoji_emoji_full_bleed_subtitle),
                settings.watch { it.emojiFullBleed },
                info = stringResource(R.string.langemoji_emoji_full_bleed_info),
                default = SettingsDefaults.emojiFullBleed,
            ) { scope.launch { repository.setEmojiFullBleed(it) } }
        }
        // The panel's grid — tabs, search, the emoji grid and the bottom row —
        // is a panel layout (issue #63).
        item(level = SettingLevel.STANDARD) {
            NavRow(
                title = R.string.panel_layout_row_title,
                subtitle = stringResource(R.string.panel_layout_row_subtitle),
            ) { onNavigate(panelEditRoute(PanelKind.EMOJI)) }
        }
        // Beside the panel's layout rather than in a group of its own further
        // down (#136): both rows are about the panel, and one door next to
        // the other is where the eye looks for it.
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.langemoji_emoji_panel_title,
                stringResource(R.string.langemoji_emoji_panel_subtitle),
                route = "emoji/panel",
            ) {
                onNavigate("emoji/panel")
            }
        }
        // Mixes are emoji you send as a picture, so they sit with the panel
        // they are picked from.
        // Owned by the Stickers, GIFs and emoji mixes extension: gone with it.
        item(visible = Extension.STICKERS in extensions, level = SettingLevel.STANDARD) {
            NavRow(
                R.string.emojimix_row_title,
                stringResource(R.string.emojimix_row_subtitle),
                route = "emoji/mixes",
            ) {
                onNavigate("emoji/mixes")
            }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_suggestions_title)) {
        item {
            // Examples in the user's own languages: "type জন্মদিন" only reads as proof
            // the feature works to someone who reads Bengali.
            val birthdayWord = settings.watch { s ->
                EmojiSearchExamples.one(EmojiSearchExamples.birthday, s.enabledLanguages.map { it.id })
            }
            ToggleSetting(
                R.string.langemoji_emoji_prediction_title,
                stringResource(R.string.langemoji_emoji_prediction_subtitle),
                predictionOn,
                info = stringResource(R.string.langemoji_emoji_prediction_info, birthdayWord),
                default = SettingsDefaults.emojiPrediction,
            ) { scope.launch { repository.setEmojiPrediction(it) } }
        }
        item(visible = predictionOn, level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.langemoji_emoji_insert_mode_title,
                subtitle = stringResource(R.string.langemoji_emoji_insert_mode_subtitle),
                info = stringResource(R.string.langemoji_emoji_insert_mode_info),
                options = listOf(
                    EmojiInsertMode.REPLACE to
                        stringResource(R.string.langemoji_emoji_insert_replace_label),
                    EmojiInsertMode.APPEND to
                        stringResource(R.string.langemoji_emoji_insert_append_label),
                ),
                selected = settings.watch { it.emojiInsertMode },
                default = SettingsDefaults.emojiInsertMode,
                detail = { mode -> ChoiceDetail(stringResource(emojiInsertDescRes(mode))) },
            ) { scope.launch { repository.setEmojiInsertMode(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_row_title)) {
        item {
            ChoiceSetting(
                title = R.string.langemoji_emoji_row_title,
                subtitle = stringResource(R.string.langemoji_emoji_bar_mode_subtitle),
                info = stringResource(R.string.langemoji_emoji_bar_mode_info),
                options = listOf(
                    EmojiBarMode.OFF to stringResource(CommonR.string.common_off),
                    EmojiBarMode.BUTTON to
                        stringResource(R.string.langemoji_emoji_bar_button_label),
                    EmojiBarMode.ALWAYS to
                        stringResource(R.string.langemoji_emoji_bar_always_label),
                ),
                selected = barMode,
                default = SettingsDefaults.emojiBarMode,
                detail = { mode -> ChoiceDetail(stringResource(emojiBarModeDescRes(mode))) },
            ) { scope.launch { repository.setEmojiBarMode(it) } }
        }
        item(visible = emojiRowOverridden) {
            ModeRowOverrides(BarRow.EMOJI, settings, onNavigate)
        }
        item(visible = barMode != EmojiBarMode.OFF, level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.langemoji_emoji_bar_content_title,
                subtitle = stringResource(R.string.langemoji_emoji_bar_content_subtitle),
                options = listOf(
                    EmojiBarContent.MOST_USED to
                        stringResource(R.string.langemoji_emoji_most_used_label),
                    EmojiBarContent.RECENTS to
                        stringResource(R.string.langemoji_emoji_recent_label),
                    EmojiBarContent.FAVOURITES to
                        stringResource(R.string.langemoji_emoji_favourites_label),
                ),
                selected = settings.watch { it.emojiBarContent },
                default = SettingsDefaults.emojiBarContent,
                detail = { content -> ChoiceDetail(stringResource(emojiBarContentDescRes(content))) },
            ) { scope.launch { repository.setEmojiBarContent(it) } }
        }
        item(visible = barMode != EmojiBarMode.OFF, level = SettingLevel.ADVANCED) {
            SliderSetting(
                title = R.string.langemoji_emoji_bar_count_title,
                subtitle = stringResource(R.string.langemoji_emoji_bar_count_subtitle),
                value = settings.watch { it.emoji.barCount }.toFloat(),
                range = EmojiBarCountRange.first.toFloat()..EmojiBarCountRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_bar_count_info),
                default = SettingsDefaults.emoji.barCount.toFloat(),
            ) { scope.launch { repository.setEmojiBarCount(it.roundToInt()) } }
        }
        item(visible = barMode != EmojiBarMode.OFF, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_emoji_bar_scroll_title,
                stringResource(R.string.langemoji_emoji_bar_scroll_subtitle),
                settings.watch { it.emoji.barScrollable },
                info = stringResource(R.string.langemoji_emoji_bar_scroll_info),
                default = SettingsDefaults.emoji.barScrollable,
            ) { scope.launch { repository.setEmojiBarScrollable(it) } }
        }
    }
    // Where the row sits among the bars is decided on Rows & bars; a row
    // that opens it beats a sentence saying so.
    if (barMode == EmojiBarMode.ALWAYS) {
        SettingsGroup {
            item(level = SettingLevel.ADVANCED) {
                NavRow(
                    R.string.home_rows_title,
                    stringResource(R.string.home_rows_subtitle),
                    route = "rows",
                ) { onNavigate("rows") }
            }
        }
    }
    // After the row and before the style (#136): the order the screen reads
    // in is access, suggestions, the row, the tone, the font.
    SettingsGroup(stringResource(R.string.langemoji_emoji_skin_tone_group_title)) {
        item {
            ChoiceSetting(
                title = R.string.langemoji_emoji_skin_tone_title,
                subtitle = stringResource(R.string.langemoji_emoji_skin_tone_subtitle),
                info = stringResource(R.string.langemoji_emoji_skin_tone_info),
                options = listOf(
                    EmojiSkinTone.NONE to "✋",
                    EmojiSkinTone.LIGHT to "✋🏻",
                    EmojiSkinTone.MEDIUM_LIGHT to "✋🏼",
                    EmojiSkinTone.MEDIUM to "✋🏽",
                    EmojiSkinTone.MEDIUM_DARK to "✋🏾",
                    EmojiSkinTone.DARK to "✋🏿",
                ),
                selected = settings.watch { it.emoji.defaultSkinTone },
                default = SettingsDefaults.emoji.defaultSkinTone,
            ) { scope.launch { repository.setEmojiDefaultSkinTone(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_emoji_tone_override_title,
                stringResource(R.string.langemoji_emoji_tone_override_subtitle),
                settings.watch { it.emoji.toneOverrideByLastUsed },
                info = stringResource(R.string.langemoji_emoji_tone_override_info),
                default = SettingsDefaults.emoji.toneOverrideByLastUsed,
            ) { scope.launch { repository.setEmojiToneOverrideByLastUsed(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_style_title)) {
        item(level = SettingLevel.STANDARD) {
            val context = LocalContext.current
            val emojiFont = settings.watch { it.emojiFont }
            val installedId = settings.watch { it.emojiFontInstalled.installedId }
            // Bumped after an import so the preview re-resolves the (same-named) file.
            var fontRefresh by remember { mutableIntStateOf(0) }
            // Where the Emoji font addon's Use button lands: the title resource
            // is the highlight key every resource-titled row now carries.
            ChoiceSetting(
                title = R.string.langemoji_emoji_font_title,
                subtitle = stringResource(R.string.langemoji_emoji_font_subtitle),
                info = stringResource(R.string.langemoji_emoji_font_info),
                options = buildList {
                    add(
                        EmojiFontChoice.SYSTEM to
                            stringResource(R.string.langemoji_emoji_font_system_label),
                    )
                    // Noto is not a file of the app's own: it comes from the
                    // same Play services font provider as the Google fonts, and
                    // without it the choice draws the system set. Kept when it
                    // is the standing choice, so a phone that lost Play
                    // services still shows what it is set to and can move off.
                    if (PlayServices.hasFontProvider(context) ||
                        emojiFont == EmojiFontChoice.NOTO
                    ) {
                        add(
                            EmojiFontChoice.NOTO to
                                stringResource(R.string.langemoji_emoji_font_noto_label),
                        )
                    }
                    add(
                        EmojiFontChoice.INSTALLED to
                            stringResource(R.string.langemoji_emoji_font_installed_label),
                    )
                    add(EmojiFontChoice.CUSTOM to stringResource(CommonR.string.common_custom))
                },
                selected = emojiFont,
                default = SettingsDefaults.emojiFont,
                detail = { choice -> ChoiceDetail(stringResource(emojiFontDescRes(choice))) },
            ) { scope.launch { repository.setEmojiFont(it) } }
            EmojiFontPreviewRow(
                choice = emojiFont,
                installedId = installedId,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                refresh = fontRefresh,
            )
            // The direct fetch of a current Noto, next to the choice it fixes:
            // "Google" above can only ask the system font provider, which
            // serves the build it has rather than the newest one.
            EmojiFontDownloadRow(
                installedId = installedId,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { fontId ->
                scope.launch { repository.setInstalledEmojiFont(fontId) }
                fontRefresh++
            }
            // A file of one's own, beside the download (#970): the library's
            // own button only shows once Installed is picked, so from here the
            // screen read as download-only.
            if (emojiFont != EmojiFontChoice.INSTALLED) {
                val importFont = rememberEmojiFontImport(repository)
                OutlinedButton(
                    onClick = importFont,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.langemoji_emoji_fonts_add_action)) }
            }
            if (emojiFont == EmojiFontChoice.INSTALLED) {
                InstalledEmojiFontList(repository, settings)
            }
            if (emojiFont == EmojiFontChoice.CUSTOM) {
                val importEmojiFont = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            importFontFile(context, uri, KeyboardFonts.customEmojiFontFile(context))
                        }
                        fontRefresh++
                    }
                }
                val imported = KeyboardFonts.customEmojiFontFile(context).exists()
                if (!imported) {
                    Text(
                        stringResource(R.string.langemoji_emoji_font_missing_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val importLabel = if (imported) {
                    stringResource(R.string.langemoji_emoji_font_replace_action)
                } else {
                    stringResource(R.string.langemoji_emoji_font_import_action)
                }
                OutlinedButton(
                    onClick = { importEmojiFont.launch(FONT_MIME_TYPES) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(importLabel) }
                Spacer(Modifier.height(8.dp))
            }
        }
        // Straight after the picker, because "Installed" is the option that
        // needs a font to have arrived from somewhere first.
        item(level = SettingLevel.STANDARD) { AddonStoreRow(AddonType.EmojiFont, onNavigate) }
        item(level = SettingLevel.STANDARD) {
            // The phone is always the one to blame here: an emoji the chosen
            // font is missing is drawn in the phone's own emoji font instead,
            // so the only emoji that stay blank are the ones neither has.
            val ownFont = settings.watch { it.emojiFont != EmojiFontChoice.SYSTEM }
            val hideInfo = stringResource(R.string.langemoji_emoji_hide_unrenderable_info)
            val ownFontInfo =
                stringResource(R.string.langemoji_emoji_hide_unrenderable_own_font_info)
            ToggleSetting(
                R.string.langemoji_emoji_hide_unrenderable_title,
                stringResource(R.string.langemoji_emoji_hide_unrenderable_subtitle),
                settings.watch { it.emoji.hideUnrenderable },
                info = if (ownFont) "$hideInfo\n\n$ownFontInfo" else hideInfo,
                default = SettingsDefaults.emoji.hideUnrenderable,
            ) { scope.launch { repository.setHideUnrenderableEmoji(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            // Issue #385: any character by the words of its Unicode name.
            ToggleSetting(
                R.string.langemoji_emoji_unicode_search_title,
                stringResource(R.string.langemoji_emoji_unicode_search_subtitle),
                settings.watch { it.emoji.unicodeSearch },
                info = stringResource(R.string.langemoji_emoji_unicode_search_info),
                default = SettingsDefaults.emoji.unicodeSearch,
            ) { scope.launch { repository.setEmojiUnicodeSearch(it) } }
        }
    }
}

@Composable
internal fun EmojiPanelSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val numberFormat = stringResource(R.string.values_number)
    // Decides which rows the group holds; the rows read everything else.
    val tabMode = settings.watch { it.emojiTabMode }
    SettingsGroup(
        stringResource(R.string.langemoji_emoji_panel_title),
        info = stringResource(R.string.langemoji_emoji_tip_body),
    ) {
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                title = R.string.langemoji_emoji_grid_size_title,
                subtitle = stringResource(R.string.langemoji_emoji_grid_size_subtitle),
                value = settings.watch { it.emoji.gridCellSize }.toFloat(),
                range = EmojiGridCellSizeRange.first.toFloat()..
                    EmojiGridCellSizeRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_grid_size_info),
                default = SettingsDefaults.emoji.gridCellSize.toFloat(),
            ) { scope.launch { repository.setEmojiGridCellSize(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                title = R.string.langemoji_emoji_tab_width_title,
                subtitle = stringResource(R.string.langemoji_emoji_tab_width_subtitle),
                value = settings.watch { it.emoji.tabMinWidth }.toFloat(),
                range = EmojiTabWidthRange.first.toFloat()..EmojiTabWidthRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                default = SettingsDefaults.emoji.tabMinWidth.toFloat(),
            ) { scope.launch { repository.setEmojiTabMinWidth(it.roundToInt()) } }
        }
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                title = R.string.langemoji_emoji_size_title,
                subtitle = stringResource(R.string.langemoji_emoji_size_subtitle),
                value = settings.watch { it.emoji.gridEmojiSize }.toFloat(),
                range = EmojiGridEmojiSizeRange.first.toFloat()..
                    EmojiGridEmojiSizeRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_size_info),
                default = SettingsDefaults.emoji.gridEmojiSize.toFloat(),
            ) { scope.launch { repository.setEmojiGridEmojiSize(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            EmojiGridAutoSlider(
                title = R.string.langemoji_emoji_grid_columns_title,
                subtitle = stringResource(R.string.langemoji_emoji_grid_columns_subtitle),
                info = stringResource(R.string.langemoji_emoji_grid_columns_info),
                value = settings.watch { it.emoji.gridColumns },
                range = EmojiGridColumnsRange,
                default = SettingsDefaults.emoji.gridColumns,
            ) { scope.launch { repository.setEmojiGridColumns(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            EmojiGridAutoSlider(
                title = R.string.langemoji_emoji_grid_row_height_title,
                subtitle = stringResource(R.string.langemoji_emoji_grid_row_height_subtitle),
                info = stringResource(R.string.langemoji_emoji_grid_row_height_info),
                value = settings.watch { it.emoji.gridRowHeight },
                range = EmojiGridRowHeightRange,
                default = SettingsDefaults.emoji.gridRowHeight,
            ) { scope.launch { repository.setEmojiGridRowHeight(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_emoji_continuous_title,
                stringResource(R.string.langemoji_emoji_continuous_subtitle),
                settings.watch { it.emoji.continuousScroll },
                info = stringResource(R.string.langemoji_emoji_continuous_info),
                default = SettingsDefaults.emoji.continuousScroll,
            ) { scope.launch { repository.setEmojiContinuousScroll(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            val none = stringResource(R.string.langemoji_panel_height_none)
            val taller = stringResource(R.string.langemoji_panel_height_value)
            SliderSetting(
                R.string.langemoji_panel_height_title,
                subtitle = stringResource(R.string.langemoji_panel_height_subtitle),
                value = settings.watch { it.emoji.panelExtraHeightDp }.toFloat(),
                range = MediaPanelExtraHeightRange.first.toFloat()..MediaPanelExtraHeightRange.last.toFloat(),
                display = {
                    val dp = it.roundToInt()
                    if (dp == 0) none else taller.format(dp)
                },
                info = stringResource(R.string.langemoji_panel_height_info),
                default = SettingsDefaults.emoji.panelExtraHeightDp.toFloat(),
            ) { scope.launch { repository.setMediaPanelExtraHeightDp(it.roundToInt()) } }
        }
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                title = R.string.langemoji_emoji_recents_title,
                subtitle = stringResource(R.string.langemoji_emoji_recents_subtitle),
                value = settings.watch { it.emoji.recentsLimit }.toFloat(),
                range = EmojiRecentsRange.first.toFloat()..EmojiRecentsRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_recents_info),
                default = SettingsDefaults.emoji.recentsLimit.toFloat(),
            ) { scope.launch { repository.setEmojiRecentsLimit(it.roundToInt()) } }
        }
        item(level = SettingLevel.STANDARD) {
            ActionRow(
                title = R.string.langemoji_emoji_clear_history_title,
                subtitle = stringResource(R.string.langemoji_emoji_clear_history_subtitle),
                action = stringResource(CommonR.string.common_clear),
                confirm = stringResource(R.string.langemoji_emoji_clear_history_confirm),
                lock = AppLockTargets["action_clear_emoji_history"],
            ) { scope.launch { repository.clearEmojiHistory() } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.langemoji_emoji_close_after_insert_title,
                stringResource(R.string.langemoji_emoji_close_after_insert_subtitle),
                settings.watch { it.emoji.closeAfterInsert },
                info = stringResource(R.string.langemoji_emoji_close_after_insert_info),
                default = SettingsDefaults.emoji.closeAfterInsert,
            ) { scope.launch { repository.setEmojiCloseAfterInsert(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.langemoji_media_switcher_title,
                subtitle = stringResource(R.string.langemoji_media_switcher_subtitle),
                info = stringResource(R.string.langemoji_media_switcher_info),
                options = listOf(
                    MediaSwitcher.OFF to stringResource(CommonR.string.common_off),
                    MediaSwitcher.TOP to stringResource(R.string.langemoji_media_switcher_top),
                    MediaSwitcher.BOTTOM to stringResource(R.string.langemoji_media_switcher_bottom),
                ),
                selected = settings.watch { it.emoji.mediaSwitcher },
                default = SettingsDefaults.emoji.mediaSwitcher,
            ) { scope.launch { repository.setEmojiMediaSwitcher(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_media_remember_title,
                stringResource(R.string.langemoji_media_remember_subtitle),
                settings.watch { it.emoji.rememberMediaTab },
                info = stringResource(R.string.langemoji_media_remember_info),
                default = SettingsDefaults.emoji.rememberMediaTab,
            ) { scope.launch { repository.setEmojiRememberMediaTab(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.langemoji_emoji_tab_mode_title,
                subtitle = stringResource(R.string.langemoji_emoji_tab_mode_subtitle),
                info = stringResource(R.string.langemoji_emoji_tab_mode_info),
                options = listOf(
                    EmojiTabMode.RECENTS to stringResource(R.string.langemoji_emoji_recent_label),
                    EmojiTabMode.MOST_USED to
                        stringResource(R.string.langemoji_emoji_most_used_label),
                    EmojiTabMode.BOTH to stringResource(R.string.langemoji_emoji_both_label),
                    EmojiTabMode.OFF to stringResource(R.string.langemoji_emoji_tab_off_label),
                ),
                selected = tabMode,
                default = SettingsDefaults.emojiTabMode,
                detail = { mode -> ChoiceDetail(stringResource(emojiTabModeDescRes(mode))) },
            ) { scope.launch { repository.setEmojiTabMode(it) } }
        }
        // The button is drawn on the recents tab only: the most-used tab is
        // counted rather than remembered, so there is nothing there to clear.
        if (tabMode == EmojiTabMode.RECENTS || tabMode == EmojiTabMode.BOTH) item {
            ToggleSetting(
                R.string.langemoji_emoji_clear_recents_title,
                stringResource(R.string.langemoji_emoji_clear_recents_subtitle),
                settings.watch { it.emojiClearRecentsButton },
                info = stringResource(R.string.langemoji_emoji_clear_recents_info),
                default = SettingsDefaults.emojiClearRecentsButton,
            ) { scope.launch { repository.setEmojiClearRecentsButton(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.langemoji_emoji_kaomoji_title,
                stringResource(R.string.langemoji_emoji_kaomoji_subtitle),
                settings.watch { it.emoji.kaomojiTabs },
                info = stringResource(R.string.langemoji_emoji_kaomoji_info),
                default = SettingsDefaults.emoji.kaomojiTabs,
            ) { scope.launch { repository.setEmojiKaomojiTabs(it) } }
        }
        // The tab's tags, its edit rail, the library and the Emotic import.
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.langemoji_kaomoji_title,
                stringResource(R.string.langemoji_kaomoji_subtitle),
                route = "kaomoji",
            ) { onNavigate("kaomoji") }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_emoji_long_press_name_title,
                stringResource(R.string.langemoji_emoji_long_press_name_subtitle),
                settings.watch { it.emojiLongPressName },
                info = stringResource(R.string.langemoji_emoji_long_press_name_info),
                default = SettingsDefaults.emojiLongPressName,
            ) { scope.launch { repository.setEmojiLongPressName(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.langemoji_emoji_animated_title,
                stringResource(R.string.langemoji_emoji_animated_subtitle),
                settings.watch { it.emoji.animated },
                info = stringResource(R.string.langemoji_emoji_animated_info),
                default = SettingsDefaults.emoji.animated,
            ) { scope.launch { repository.setAnimatedEmoji(it) } }
        }
        serverItems(repository, settings, endpoints = listOf(ServiceEndpoint.ANIMATED_EMOJI))
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.langemoji_emoji_sticker_title,
                stringResource(R.string.langemoji_emoji_sticker_subtitle),
                settings.watch { it.emoji.sendAsSticker },
                info = stringResource(R.string.langemoji_emoji_sticker_info),
                default = SettingsDefaults.emoji.sendAsSticker,
            ) { scope.launch { repository.setSendEmojiAsSticker(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.langemoji_emoji_categories_title,
                stringResource(R.string.langemoji_emoji_categories_subtitle),
                route = "emojicategories",
            ) { onNavigate("emojicategories") }
        }
        item(level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.langemoji_emoji_added_title,
                stringResource(R.string.langemoji_emoji_added_subtitle),
                value = settings.watch { it.emoji.added.size }.takeIf { it > 0 }?.toString(),
                route = "emojiadded",
            ) { onNavigate("emojiadded") }
        }
        item(level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.langemoji_emoji_keywords_title,
                stringResource(R.string.langemoji_emoji_keywords_subtitle),
                route = "emojikeywords",
            ) { onNavigate("emojikeywords") }
        }
    }
}
/**
 * The emoji faces in the font library, with the one in use ticked, and the
 * button that adds a file of the user's own to it.
 *
 * Emoji fonts live in the same [FontStore] as the text faces — same files, same
 * lifecycle — but are listed apart, since drawing key labels in a colour emoji
 * font is not a choice anyone makes on purpose.
 *
 * The import is here, and not only under "Custom", because Custom is a single
 * slot that each import overwrites (#657): someone without the download route,
 * such as an offline build, had to pick the file again every time they went
 * back to a face they had used before.
 */
@Composable
private fun InstalledEmojiFontList(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { FontStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val fonts = remember(revision) { store.emojiFonts() }
    val importFont = rememberEmojiFontImport(repository)

    if (fonts.isEmpty()) {
        CaptionText(stringResource(R.string.langemoji_emoji_fonts_empty))
    }
    val installedId = settings.watch { it.emojiFontInstalled.installedId }
    for (font in fonts) key(font.id) {
        val selected = installedId == font.id
        WmRow(
            title = font.name,
            supporting = font.author.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            swipeActions = listOf(
                deleteSwipeAction(stringResource(R.string.langemoji_emoji_font_delete_desc, font.name)) {
                    scope.launch {
                        repository.forgetInstalledEmojiFont(font.id)
                        withContext(Dispatchers.IO) { store.delete(font.id) }
                    }
                },
            ),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription =
                                stringResource(R.string.langemoji_emoji_font_selected_desc),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            repository.forgetInstalledEmojiFont(font.id)
                            withContext(Dispatchers.IO) { store.delete(font.id) }
                        }
                    }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(
                                R.string.langemoji_emoji_font_delete_desc,
                                font.name,
                            ),
                        )
                    }
                }
            },
            onClick = { scope.launch { repository.setInstalledEmojiFont(font.id) } },
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = importFont,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { Text(stringResource(R.string.langemoji_emoji_fonts_add_action)) }
    Spacer(Modifier.height(8.dp))
}

/**
 * Picks a font file off the phone and adds it to the emoji font library,
 * selected (#657, #970), with a dialog for a file that is refused. Returned
 * as the call that opens the picker, so the font library and the card beside
 * the Noto download can both offer it.
 */
@Composable
private fun rememberEmojiFontImport(repository: SettingsRepository): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { FontStore.get(context) }
    // A refused import, already in words: it is resolved on the main thread
    // as the result lands, against the language the screen is showing.
    var importError by remember { mutableStateOf<String?>(null) }
    val importFont = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // One after another, so several picked at once land in the order they
        // were picked, and the last of them is the one left selected.
        scope.launch {
            for (uri in uris) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.requireInputStream(uri).use {
                            FontFile.import(it, store, name = fontFileLabel(context, uri), emoji = true)
                        }
                    }.getOrElse {
                        FontImportResult.Failed(ContentR.string.core_content_font_error_read)
                    }
                }
                when (result) {
                    // Selected straight away: importing a face and then having to
                    // find it in the list to use it is one step too many.
                    is FontImportResult.Imported -> repository.setInstalledEmojiFont(result.font.id)
                    is FontImportResult.NotAFont -> importError = context.getString(result.messageRes)
                    FontImportResult.TooManyFonts -> importError = context.resources.getQuantityString(
                        R.plurals.fonts_import_limit_message,
                        FontStore.MAX_FONTS,
                        FontStore.MAX_FONTS,
                    )
                    is FontImportResult.Failed -> importError =
                        context.getString(result.messageRes, *result.messageArgs.toTypedArray())
                }
            }
        }
    }
    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
    return { importFont.launch(FONT_MIME_TYPES) }
}

/** What each emoji suggestion answer does to the typed word, for the sheet. */
private fun emojiInsertDescRes(mode: EmojiInsertMode): Int = when (mode) {
    EmojiInsertMode.REPLACE -> R.string.langemoji_emoji_insert_replace_desc
    EmojiInsertMode.APPEND -> R.string.langemoji_emoji_insert_append_desc
}

/** Where the emoji row lives under each answer, for the sheet. */
private fun emojiBarModeDescRes(mode: EmojiBarMode): Int = when (mode) {
    EmojiBarMode.OFF -> R.string.langemoji_emoji_bar_off_desc
    EmojiBarMode.BUTTON -> R.string.langemoji_emoji_bar_button_desc
    EmojiBarMode.ALWAYS -> R.string.langemoji_emoji_bar_always_desc
}

/** What the emoji row holds under each answer, for the sheet. */
private fun emojiBarContentDescRes(content: EmojiBarContent): Int = when (content) {
    EmojiBarContent.MOST_USED -> R.string.langemoji_emoji_bar_content_most_used_desc
    EmojiBarContent.RECENTS -> R.string.langemoji_emoji_bar_content_recents_desc
    EmojiBarContent.FAVOURITES -> R.string.langemoji_emoji_bar_content_favourites_desc
}

/** Which emoji set each answer draws, for the sheet. */
private fun emojiFontDescRes(choice: EmojiFontChoice): Int = when (choice) {
    EmojiFontChoice.SYSTEM -> R.string.langemoji_emoji_font_system_desc
    EmojiFontChoice.NOTO -> R.string.langemoji_emoji_font_noto_desc
    EmojiFontChoice.INSTALLED -> R.string.langemoji_emoji_font_installed_desc
    EmojiFontChoice.CUSTOM -> R.string.langemoji_emoji_font_custom_desc
}

/** How the first emoji tab is ordered under each answer, for the sheet. */
private fun emojiTabModeDescRes(mode: EmojiTabMode): Int = when (mode) {
    EmojiTabMode.RECENTS -> R.string.langemoji_emoji_recent_desc
    EmojiTabMode.MOST_USED -> R.string.langemoji_emoji_most_used_desc
    EmojiTabMode.BOTH -> R.string.langemoji_emoji_both_desc
    EmojiTabMode.OFF -> R.string.langemoji_emoji_tab_off_desc
}

/**
 * A slider over [range] with one more stop below it, "Auto", stored as 0: the
 * shape of `EmojiSettings.gridColumns` and `EmojiSettings.gridRowHeight`, where
 * a fixed value is the exception and fitting is the default.
 */
@Composable
private fun EmojiGridAutoSlider(
    @StringRes title: Int,
    subtitle: String,
    info: String,
    value: Int,
    range: IntRange,
    default: Int,
    onChange: (Int) -> Unit,
) {
    val auto = stringResource(R.string.langemoji_emoji_grid_auto)
    val numberFormat = stringResource(R.string.values_number)
    val autoStop = range.first - 1
    SliderSetting(
        title = title,
        subtitle = subtitle,
        value = (if (value <= 0) autoStop else value).toFloat(),
        range = autoStop.toFloat()..range.last.toFloat(),
        display = { v -> if (v.roundToInt() <= autoStop) auto else numberFormat.format(v.roundToInt()) },
        info = info,
        default = (if (default <= 0) autoStop else default).toFloat(),
    ) { v -> onChange(if (v.roundToInt() <= autoStop) 0 else v.roundToInt()) }
}

package com.wasimaster.wmkeyboard.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.wasimaster.wmkeyboard.core.settings.EmojiBarCountRange
import com.wasimaster.wmkeyboard.core.settings.EmojiGridCellSizeRange
import com.wasimaster.wmkeyboard.core.settings.EmojiGridEmojiSizeRange
import com.wasimaster.wmkeyboard.core.settings.EmojiRecentsRange
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice
import com.wasimaster.wmkeyboard.core.settings.EmojiSkinTone
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.core.settings.EmojiInsertMode
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.emoji.EmojiSearchExamples
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import kotlinx.coroutines.launch

// ---- emoji ----

@Composable
internal fun EmojiSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The slider readout is a plain lambda, so its format string is resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    // Examples in the user's own languages: "type জন্মদিন" only reads as proof
    // the feature works to someone who reads Bengali.
    val languageIds = settings.enabledLanguages.map { it.id }
    val birthdayWord = EmojiSearchExamples.one(EmojiSearchExamples.birthday, languageIds)
    SettingsGroup(stringResource(R.string.langemoji_emoji_access_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_emoji_toolbar_title,
                stringResource(R.string.langemoji_emoji_toolbar_subtitle),
                settings.emojiToolbar,
                info = stringResource(R.string.langemoji_emoji_toolbar_info),
                default = SettingsDefaults.emojiToolbar,
            ) { scope.launch { repository.setEmojiToolbar(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_full_bleed_title,
                stringResource(R.string.langemoji_emoji_full_bleed_subtitle),
                settings.emojiFullBleed,
                info = stringResource(R.string.langemoji_emoji_full_bleed_info),
                default = SettingsDefaults.emojiFullBleed,
            ) { scope.launch { repository.setEmojiFullBleed(it) } }
        }
        // The panel's grid — tabs, search, the emoji grid and the bottom row —
        // is a panel layout (issue #63).
        item {
            NavRow(
                title = R.string.panel_layout_row_title,
                subtitle = stringResource(R.string.panel_layout_row_subtitle),
            ) { onNavigate("panel_edit/${PanelKind.EMOJI.name}") }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_suggestions_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_emoji_prediction_title,
                stringResource(R.string.langemoji_emoji_prediction_subtitle),
                settings.emojiPrediction,
                info = stringResource(R.string.langemoji_emoji_prediction_info, birthdayWord),
                default = SettingsDefaults.emojiPrediction,
            ) { scope.launch { repository.setEmojiPrediction(it) } }
        }
        if (settings.emojiPrediction) {
            item {
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
                    selected = settings.emojiInsertMode,
                    default = SettingsDefaults.emojiInsertMode,
                ) { scope.launch { repository.setEmojiInsertMode(it) } }
            }
        }
    }
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
                selected = settings.emoji.defaultSkinTone,
                default = SettingsDefaults.emoji.defaultSkinTone,
            ) { scope.launch { repository.setEmojiDefaultSkinTone(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_tone_override_title,
                stringResource(R.string.langemoji_emoji_tone_override_subtitle),
                settings.emoji.toneOverrideByLastUsed,
                info = stringResource(R.string.langemoji_emoji_tone_override_info),
                default = SettingsDefaults.emoji.toneOverrideByLastUsed,
            ) { scope.launch { repository.setEmojiToneOverrideByLastUsed(it) } }
        }
    }
    SettingsGroup(
        stringResource(R.string.langemoji_emoji_panel_title),
        info = stringResource(R.string.langemoji_emoji_tip_body),
    ) {
        item {
            SliderSetting(
                title = R.string.langemoji_emoji_grid_size_title,
                subtitle = stringResource(R.string.langemoji_emoji_grid_size_subtitle),
                value = settings.emoji.gridCellSize.toFloat(),
                range = EmojiGridCellSizeRange.first.toFloat()..
                    EmojiGridCellSizeRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_grid_size_info),
                default = SettingsDefaults.emoji.gridCellSize.toFloat(),
            ) { scope.launch { repository.setEmojiGridCellSize(it.roundToInt()) } }
        }
        item {
            SliderSetting(
                title = R.string.langemoji_emoji_size_title,
                subtitle = stringResource(R.string.langemoji_emoji_size_subtitle),
                value = settings.emoji.gridEmojiSize.toFloat(),
                range = EmojiGridEmojiSizeRange.first.toFloat()..
                    EmojiGridEmojiSizeRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_size_info),
                default = SettingsDefaults.emoji.gridEmojiSize.toFloat(),
            ) { scope.launch { repository.setEmojiGridEmojiSize(it.roundToInt()) } }
        }
        item {
            SliderSetting(
                title = R.string.langemoji_emoji_recents_title,
                subtitle = stringResource(R.string.langemoji_emoji_recents_subtitle),
                value = settings.emoji.recentsLimit.toFloat(),
                range = EmojiRecentsRange.first.toFloat()..EmojiRecentsRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_recents_info),
                default = SettingsDefaults.emoji.recentsLimit.toFloat(),
            ) { scope.launch { repository.setEmojiRecentsLimit(it.roundToInt()) } }
        }
        item {
            ActionRow(
                title = R.string.langemoji_emoji_clear_history_title,
                subtitle = stringResource(R.string.langemoji_emoji_clear_history_subtitle),
                action = stringResource(CommonR.string.common_clear),
                confirm = stringResource(R.string.langemoji_emoji_clear_history_confirm),
                lock = AppLockTargets["action_clear_emoji_history"],
            ) { scope.launch { repository.clearEmojiHistory() } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_close_after_insert_title,
                stringResource(R.string.langemoji_emoji_close_after_insert_subtitle),
                settings.emoji.closeAfterInsert,
                info = stringResource(R.string.langemoji_emoji_close_after_insert_info),
                default = SettingsDefaults.emoji.closeAfterInsert,
            ) { scope.launch { repository.setEmojiCloseAfterInsert(it) } }
        }
        item {
            ChoiceSetting(
                title = R.string.langemoji_emoji_tab_mode_title,
                subtitle = stringResource(R.string.langemoji_emoji_tab_mode_subtitle),
                info = stringResource(R.string.langemoji_emoji_tab_mode_info),
                options = listOf(
                    EmojiTabMode.RECENTS to stringResource(R.string.langemoji_emoji_recent_label),
                    EmojiTabMode.MOST_USED to
                        stringResource(R.string.langemoji_emoji_most_used_label),
                ),
                selected = settings.emojiTabMode,
                default = SettingsDefaults.emojiTabMode,
            ) { scope.launch { repository.setEmojiTabMode(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_clear_recents_title,
                stringResource(R.string.langemoji_emoji_clear_recents_subtitle),
                settings.emojiClearRecentsButton,
                info = stringResource(R.string.langemoji_emoji_clear_recents_info),
                default = SettingsDefaults.emojiClearRecentsButton,
            ) { scope.launch { repository.setEmojiClearRecentsButton(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_kaomoji_title,
                stringResource(R.string.langemoji_emoji_kaomoji_subtitle),
                settings.emoji.kaomojiTabs,
                info = stringResource(R.string.langemoji_emoji_kaomoji_info),
                default = SettingsDefaults.emoji.kaomojiTabs,
            ) { scope.launch { repository.setEmojiKaomojiTabs(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_long_press_name_title,
                stringResource(R.string.langemoji_emoji_long_press_name_subtitle),
                settings.emojiLongPressName,
                info = stringResource(R.string.langemoji_emoji_long_press_name_info),
                default = SettingsDefaults.emojiLongPressName,
            ) { scope.launch { repository.setEmojiLongPressName(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_animated_title,
                stringResource(R.string.langemoji_emoji_animated_subtitle),
                settings.emoji.animated,
                info = stringResource(R.string.langemoji_emoji_animated_info),
                default = SettingsDefaults.emoji.animated,
            ) { scope.launch { repository.setAnimatedEmoji(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_sticker_title,
                stringResource(R.string.langemoji_emoji_sticker_subtitle),
                settings.emoji.sendAsSticker,
                info = stringResource(R.string.langemoji_emoji_sticker_info),
                default = SettingsDefaults.emoji.sendAsSticker,
            ) { scope.launch { repository.setSendEmojiAsSticker(it) } }
        }
        item {
            NavRow(
                R.string.langemoji_emoji_keywords_title,
                stringResource(R.string.langemoji_emoji_keywords_subtitle),
                route = "emojikeywords",
            ) { onNavigate("emojikeywords") }
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
                selected = settings.emojiBarMode,
                default = SettingsDefaults.emojiBarMode,
            ) { scope.launch { repository.setEmojiBarMode(it) } }
        }
        if (settings.emojiBarMode != EmojiBarMode.OFF) {
            item {
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
                    selected = settings.emojiBarContent,
                    default = SettingsDefaults.emojiBarContent,
                ) { scope.launch { repository.setEmojiBarContent(it) } }
            }
            item {
                SliderSetting(
                    title = R.string.langemoji_emoji_bar_count_title,
                    subtitle = stringResource(R.string.langemoji_emoji_bar_count_subtitle),
                    value = settings.emoji.barCount.toFloat(),
                    range = EmojiBarCountRange.first.toFloat()..EmojiBarCountRange.last.toFloat(),
                    display = { numberFormat.format(it.roundToInt()) },
                    info = stringResource(R.string.langemoji_emoji_bar_count_info),
                    default = SettingsDefaults.emoji.barCount.toFloat(),
                ) { scope.launch { repository.setEmojiBarCount(it.roundToInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.langemoji_emoji_bar_scroll_title,
                    stringResource(R.string.langemoji_emoji_bar_scroll_subtitle),
                    settings.emoji.barScrollable,
                    info = stringResource(R.string.langemoji_emoji_bar_scroll_info),
                    default = SettingsDefaults.emoji.barScrollable,
                ) { scope.launch { repository.setEmojiBarScrollable(it) } }
            }
        }
    }
    // Where the row sits among the bars is decided on Rows & bars; a row
    // that opens it beats a sentence saying so.
    if (settings.emojiBarMode == EmojiBarMode.ALWAYS) {
        SettingsGroup {
            item {
                NavRow(
                    R.string.home_rows_title,
                    stringResource(R.string.home_rows_subtitle),
                    route = "rows",
                ) { onNavigate("rows") }
            }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_style_title)) {
        item {
            val context = LocalContext.current
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
                        settings.emojiFont == EmojiFontChoice.NOTO
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
                selected = settings.emojiFont,
                default = SettingsDefaults.emojiFont,
            ) { scope.launch { repository.setEmojiFont(it) } }
            EmojiFontPreviewRow(
                choice = settings.emojiFont,
                installedId = settings.emojiFontInstalled.installedId,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                refresh = fontRefresh,
            )
            // The direct fetch of a current Noto, next to the choice it fixes:
            // "Google" above can only ask the system font provider, which
            // serves the build it has rather than the newest one.
            EmojiFontDownloadRow(
                installedId = settings.emojiFontInstalled.installedId,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { fontId ->
                scope.launch { repository.setInstalledEmojiFont(fontId) }
                fontRefresh++
            }
            if (settings.emojiFont == EmojiFontChoice.INSTALLED) {
                InstalledEmojiFontList(repository, settings)
            }
            if (settings.emojiFont == EmojiFontChoice.CUSTOM) {
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
        item { AddonStoreRow(AddonType.EmojiFont, onNavigate) }
        item {
            // The phone is always the one to blame here: an emoji the chosen
            // font is missing is drawn in the phone's own emoji font instead,
            // so the only emoji that stay blank are the ones neither has.
            val ownFont = settings.emojiFont != EmojiFontChoice.SYSTEM
            val hideInfo = stringResource(R.string.langemoji_emoji_hide_unrenderable_info)
            val ownFontInfo =
                stringResource(R.string.langemoji_emoji_hide_unrenderable_own_font_info)
            ToggleSetting(
                R.string.langemoji_emoji_hide_unrenderable_title,
                stringResource(R.string.langemoji_emoji_hide_unrenderable_subtitle),
                settings.emoji.hideUnrenderable,
                info = if (ownFont) "$hideInfo\n\n$ownFontInfo" else hideInfo,
                default = SettingsDefaults.emoji.hideUnrenderable,
            ) { scope.launch { repository.setHideUnrenderableEmoji(it) } }
        }
    }
}
/**
 * The emoji faces in the font library, with the one in use ticked.
 *
 * Emoji fonts live in the same [FontStore] as the text faces — same files, same
 * lifecycle — but are listed apart, since drawing key labels in a colour emoji
 * font is not a choice anyone makes on purpose.
 */
@Composable
private fun InstalledEmojiFontList(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { FontStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val fonts = remember(revision) { store.emojiFonts() }

    if (fonts.isEmpty()) {
        CaptionText(stringResource(R.string.langemoji_emoji_fonts_empty))
        return
    }
    for (font in fonts) {
        val selected = settings.emojiFontInstalled.installedId == font.id
        WmRow(
            title = font.name,
            supporting = font.author.takeIf { it.isNotBlank() }?.let { { Text(it) } },
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
}

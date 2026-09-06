package com.wasimaster.wmkeyboard.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SignalCellularAlt1Bar
import androidx.compose.material.icons.outlined.SignalCellularAlt2Bar
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import com.wasimaster.wmkeyboard.core.vocab.VocabChipTap
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeScope
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeLevel
import com.wasimaster.wmkeyboard.core.vocab.VocabCooldown
import com.wasimaster.wmkeyboard.core.vocab.VocabScheduler
import com.wasimaster.wmkeyboard.core.vocab.VocabAudioSource
import com.wasimaster.wmkeyboard.core.vocab.VocabAccent
import com.wasimaster.wmkeyboard.core.vocab.VocabRelatedTap
import com.wasimaster.wmkeyboard.core.vocab.FieldVisibility
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AlignHorizontalCenter
import androidx.compose.material.icons.outlined.AlignHorizontalLeft
import androidx.compose.material.icons.outlined.AlignHorizontalRight
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.KeyboardCapslock
import androidx.compose.material.icons.outlined.KeyboardCommandKey
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.StayCurrentLandscape
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewCompact
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.script.NumeralCommitScope
import com.wasimaster.wmkeyboard.core.settings.AppSortOrder
import com.wasimaster.wmkeyboard.core.settings.AutoThemeTrigger
import com.wasimaster.wmkeyboard.core.settings.BackspaceSwipeUnit
import com.wasimaster.wmkeyboard.core.settings.CopiedCodeChip
import com.wasimaster.wmkeyboard.core.settings.DataSaverTrigger
import com.wasimaster.wmkeyboard.core.settings.EmojiBarContent
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice
import com.wasimaster.wmkeyboard.core.settings.EmojiInsertMode
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import com.wasimaster.wmkeyboard.core.settings.GifContentFilter
import com.wasimaster.wmkeyboard.core.settings.GifSourceMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.ManualModeDuration
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedSide
import com.wasimaster.wmkeyboard.core.settings.PowerSavingTrigger
import com.wasimaster.wmkeyboard.core.settings.RotationInterval
import com.wasimaster.wmkeyboard.core.settings.ScreenReaderMode
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.core.settings.SpaceSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SpacebarDisplay
import com.wasimaster.wmkeyboard.core.settings.SuggestionHotkeyMode
import com.wasimaster.wmkeyboard.core.settings.ThemeGalleryStyle
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.settings.ToolboxLayout
import com.wasimaster.wmkeyboard.core.settings.TransliterationHintMode
import com.wasimaster.wmkeyboard.core.snippets.MultiExpand
import com.wasimaster.wmkeyboard.core.snippets.MultiExpandMode
import com.wasimaster.wmkeyboard.core.snippets.UppercaseStyle
import com.wasimaster.wmkeyboard.core.theme.GradientType
import com.wasimaster.wmkeyboard.core.theme.KeyEffectColorMode
import com.wasimaster.wmkeyboard.core.theme.KeyEffectKind
import com.wasimaster.wmkeyboard.core.theme.KeyTextureScale
import com.wasimaster.wmkeyboard.core.theme.ThemeAnimation
import com.wasimaster.wmkeyboard.core.tools.StatsPeriod
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.BorderStyle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Doorbell
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.ViewSidebar
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinScheme
import com.wasimaster.wmkeyboard.core.script.NumeralSystem
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AppLockRelock
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.ColorVisionFilter
import com.wasimaster.wmkeyboard.core.settings.GlideApostropheKey
import com.wasimaster.wmkeyboard.core.settings.GlideVocabulary
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.LanguageDetectionStrength
import com.wasimaster.wmkeyboard.core.settings.MeteredPolicy
import com.wasimaster.wmkeyboard.core.settings.QrEccLevel
import com.wasimaster.wmkeyboard.core.prediction.UndoMemory
import androidx.compose.material.icons.outlined.AutoMode
import com.wasimaster.wmkeyboard.core.input.composer.HanVariant
import com.wasimaster.wmkeyboard.core.script.ComposerType

/**
 * The glyph one option of a one-of-N setting wears, keyed by the option value
 * itself. [SettingsRowIcons] is the same idea one level up, for rows.
 *
 * Keyed by the value rather than by its label, because a picker is handed
 * `List<Pair<T, String>>` — the labels are already resolved strings by then,
 * and the value is the only thing left that identifies the option. That is
 * what makes this table free at the call site: `ChoiceSheet` looks every
 * option up here on its own, so giving a setting icons is an edit to this
 * file alone, and every screen that offers the same enum agrees.
 *
 * **Enum constants only.** `true`, `"large"` and `3` are options in dozens of
 * unrelated settings, and one table keyed by them would put the same glyph on
 * all of them. A non-enum option that wants an icon names it on its own
 * [ChoiceDetail] instead, at the call site that knows what it means.
 *
 * A value missing from the map draws no icon, and that is the right answer
 * more often than it looks. Left out on purpose:
 *
 * - Ordinal scales with no picture behind them — the QR error correction
 *   levels, the glide vocabulary sizes, the autocorrect memory levels, the
 *   language detection strengths. "L, M, Q, H" is a dial, and four shades of
 *   the same glyph is a dial that has learned to lie.
 * - Sets where every candidate would be the same glyph: the grammar dialects
 *   (four globes), the colour vision filters (three eyes), the Han regions,
 *   the double pinyin schemes, the composers.
 * - Options that are already a picture: the skin tones are drawn as the hand
 *   itself, the numeral systems as their own digits, and the border colours
 *   carry a swatch through [ChoiceDetail.leading].
 * - Everything the user supplies: languages, layouts, themes, fonts, models,
 *   folders. There is nothing to key on and nothing true to draw.
 *
 * The rule is [SettingsRowIcons]': a vague glyph is worse than none, because
 * it is one more thing to scan past that says nothing.
 */
internal object ChoiceOptionIcons {
    // Builders rather than built vectors, for the reason SettingsRowIcons
    // gives: materialising every glyph at class init is path parsing for the
    // whole table before the first sheet can draw one row of it.
    private val map: Map<Any, () -> ImageVector> = buildMap {

        // ---- Themes ----
        put(ThemeMode.SYSTEM) { Icons.Outlined.PhoneAndroid }
        put(ThemeMode.LIGHT) { Icons.Outlined.LightMode }
        put(ThemeMode.DARK) { Icons.Outlined.DarkMode }
        // Not a third shade of dark: AMOLED is about the screen, and contrast
        // is the thing it changes that the eye actually sees.
        put(ThemeMode.AMOLED) { Icons.Outlined.Contrast }

        put(AutoThemeTrigger.SYSTEM) { Icons.Outlined.PhoneAndroid }
        put(AutoThemeTrigger.SCHEDULE) { Icons.Outlined.Schedule }
        put(AutoThemeTrigger.SUN) { Icons.Outlined.LightMode }

        put(ThemeGalleryStyle.AUTO) { Icons.Outlined.AutoAwesome }
        put(ThemeGalleryStyle.GROUPED) { Icons.Outlined.Collections }
        put(ThemeGalleryStyle.FLAT) { Icons.Outlined.GridView }

        put(RotationInterval.EVERY_OPEN) { Icons.Outlined.Autorenew }
        put(RotationInterval.HOURLY) { Icons.Outlined.Schedule }
        put(RotationInterval.SIX_HOURLY) { Icons.Outlined.Update }
        put(RotationInterval.DAILY) { Icons.Outlined.Today }
        put(RotationInterval.WEEKLY) { Icons.Outlined.DateRange }
        put(RotationInterval.MANUAL) { Icons.Outlined.TouchApp }

        // ---- Vocabulary ----
        put(VocabChipTap.OPEN) { Icons.Outlined.OpenInNew }
        put(VocabChipTap.REPLACE) { Icons.Outlined.FindReplace }
        put(VocabNudgeScope.UNLEARNT) { Icons.Outlined.School }
        put(VocabNudgeScope.ALL) { Icons.Outlined.AllInclusive }
        put(VocabNudgeScope.LEARNT_ONLY) { Icons.Outlined.CheckCircle }
        put(VocabNudgeLevel.LOW) { Icons.Outlined.SignalCellularAlt1Bar }
        put(VocabNudgeLevel.MEDIUM) { Icons.Outlined.SignalCellularAlt2Bar }
        put(VocabNudgeLevel.HIGH) { Icons.Outlined.SignalCellularAlt }
        put(VocabCooldown.EVERY_TIME) { Icons.Outlined.Autorenew }
        put(VocabCooldown.ONCE_PER_FIELD) { Icons.Outlined.TextFields }
        put(VocabCooldown.ONCE_PER_DAY) { Icons.Outlined.Today }
        put(VocabScheduler.LEITNER) { Icons.Outlined.Layers }
        put(VocabScheduler.SM2) { Icons.AutoMirrored.Outlined.TrendingUp }
        put(VocabAudioSource.AUTO) { Icons.Outlined.AutoAwesome }
        put(VocabAudioSource.WIKTIONARY) { Icons.Outlined.Public }
        put(VocabAudioSource.TTS) { Icons.Outlined.RecordVoiceOver }
        put(VocabAccent.US) { Icons.Outlined.Flag }
        put(VocabAccent.UK) { Icons.Outlined.OutlinedFlag }
        put(VocabRelatedTap.OPEN_CARD_ELSE_INSERT) { Icons.Outlined.Style }
        put(VocabRelatedTap.INSERT) { Icons.Outlined.Keyboard }
        put(VocabRelatedTap.DICTIONARY_LOOKUP) { Icons.Outlined.Search }
        put(FieldVisibility.OFF) { Icons.Outlined.VisibilityOff }
        put(FieldVisibility.SETTINGS) { Icons.Outlined.Settings }
        put(FieldVisibility.KEYBOARD) { Icons.Outlined.Keyboard }

        // ---- Theme editor ----
        put(KeyTextureScale.CROP) { Icons.Outlined.Crop }
        put(KeyTextureScale.STRETCH) { Icons.Outlined.SwapHoriz }
        put(KeyTextureScale.TILE) { Icons.Outlined.GridOn }

        put(ThemeAnimation.NONE) { Icons.Outlined.Block }
        put(ThemeAnimation.FLOW) { Icons.Outlined.Waves }
        put(ThemeAnimation.HUE_CYCLE) { Icons.Outlined.Colorize }

        put(KeyEffectKind.STARS) { Icons.Outlined.StarOutline }
        put(KeyEffectKind.HEARTS) { Icons.Outlined.FavoriteBorder }
        put(KeyEffectKind.SPARKLE) { Icons.Outlined.AutoAwesome }
        put(KeyEffectKind.CONFETTI) { Icons.Outlined.Celebration }
        put(KeyEffectKind.EMOJI) { Icons.Outlined.EmojiEmotions }
        put(KeyEffectKind.CUSTOM_IMAGE) { Icons.Outlined.Image }

        put(KeyEffectColorMode.NATURAL) { Icons.Outlined.Palette }
        put(KeyEffectColorMode.KEY_TEXT) { Icons.Outlined.TextFields }
        put(KeyEffectColorMode.ACCENT) { Icons.Outlined.ColorLens }
        put(KeyEffectColorMode.GESTURE_TRAIL) { Icons.Outlined.Gesture }
        put(KeyEffectColorMode.CUSTOM) { Icons.Outlined.Colorize }
        put(KeyEffectColorMode.RANDOM) { Icons.Outlined.Shuffle }

        // The shape each one draws, as close as a glyph gets: a straight run,
        // a ring, and something turning.
        put(GradientType.LINEAR) { Icons.Outlined.LinearScale }
        put(GradientType.RADIAL) { Icons.Outlined.RadioButtonUnchecked }
        put(GradientType.SWEEP) { Icons.Outlined.RotateRight }

        // ---- Photo backgrounds ----
        put(BackgroundSlot.PORTRAIT) { Icons.Outlined.StayCurrentPortrait }
        put(BackgroundSlot.LANDSCAPE) { Icons.Outlined.StayCurrentLandscape }
        put(BackgroundSlot.BOTH) { Icons.Outlined.ScreenRotation }

        // ---- Layout and size ----
        put(KeyboardAlignment.LEFT) { Icons.Outlined.AlignHorizontalLeft }
        put(KeyboardAlignment.CENTER) { Icons.Outlined.AlignHorizontalCenter }
        put(KeyboardAlignment.RIGHT) { Icons.Outlined.AlignHorizontalRight }

        put(OneHandedMode.OFF) { Icons.Outlined.Block }
        // Not the auto-mirrored arrows: this is a side of the physical screen,
        // and it does not swap when the language reads right to left.
        put(OneHandedMode.LEFT) { Icons.Outlined.AlignHorizontalLeft }
        put(OneHandedMode.RIGHT) { Icons.Outlined.AlignHorizontalRight }
        put(OneHandedSide.LEFT) { Icons.Outlined.AlignHorizontalLeft }
        put(OneHandedSide.RIGHT) { Icons.Outlined.AlignHorizontalRight }

        put(ToolbarPlacement.STRIP) { Icons.Outlined.ViewCompact }
        put(ToolbarPlacement.ON_DEMAND_ROW) { Icons.Outlined.SmartButton }
        put(ToolbarPlacement.ALWAYS_ROW) { Icons.Outlined.TableRows }

        put(ToolboxLayout.ICONS) { Icons.Outlined.Apps }
        put(ToolboxLayout.PILLS) { Icons.Outlined.ViewAgenda }

        // ---- Typing ----
        put(SpaceSwipeAction.NONE) { Icons.Outlined.Block }
        put(SpaceSwipeAction.LANGUAGE) { Icons.Outlined.Language }
        put(SpaceSwipeAction.CURSOR) { Icons.Outlined.SwapHoriz }
        put(SpaceSwipeAction.NUMPAD) { Icons.Outlined.Dialpad }

        put(SpacebarDisplay.LANGUAGE) { Icons.Outlined.Language }
        put(SpacebarDisplay.LAYOUT) { Icons.Outlined.Keyboard }
        put(SpacebarDisplay.BOTH) { Icons.Outlined.AllInclusive }

        put(BackspaceSwipeUnit.WORD) { Icons.AutoMirrored.Outlined.ShortText }
        put(BackspaceSwipeUnit.CHARACTER) { Icons.Outlined.Abc }

        put(LetterSwipeAction.TYPE_WORDS) { Icons.Outlined.Gesture }
        put(LetterSwipeAction.HANDWRITE) { Icons.Outlined.Draw }

        put(SuggestionHotkeyMode.OFF) { Icons.Outlined.Block }
        put(SuggestionHotkeyMode.LEADER_DIGIT) { Icons.Outlined.KeyboardCommandKey }
        put(SuggestionHotkeyMode.ALT_DIGIT) { Icons.Outlined.KeyboardAlt }

        // ---- Languages ----
        put(NumeralCommitScope.TEXT_ONLY) { Icons.Outlined.TextFields }
        put(NumeralCommitScope.EVERYWHERE) { Icons.Outlined.Public }
        put(NumeralCommitScope.DISPLAY_ONLY) { Icons.Outlined.Visibility }

        put(TransliterationHintMode.OFF) { Icons.Outlined.Block }
        put(TransliterationHintMode.ADDED) { Icons.Outlined.Label }
        put(TransliterationHintMode.CLUSTER) { Icons.Outlined.Workspaces }

        // ---- Emoji ----
        put(EmojiBarMode.OFF) { Icons.Outlined.Block }
        put(EmojiBarMode.BUTTON) { Icons.Outlined.SmartButton }
        put(EmojiBarMode.ALWAYS) { Icons.Outlined.ViewStream }

        put(EmojiBarContent.MOST_USED) { Icons.Outlined.TrendingUp }
        put(EmojiBarContent.RECENTS) { Icons.Outlined.History }
        put(EmojiBarContent.FAVOURITES) { Icons.Outlined.FavoriteBorder }

        put(EmojiTabMode.RECENTS) { Icons.Outlined.History }
        put(EmojiTabMode.MOST_USED) { Icons.Outlined.TrendingUp }

        put(EmojiInsertMode.REPLACE) { Icons.Outlined.FindReplace }
        put(EmojiInsertMode.APPEND) { Icons.AutoMirrored.Outlined.PlaylistAdd }

        put(EmojiFontChoice.SYSTEM) { Icons.Outlined.PhoneAndroid }
        put(EmojiFontChoice.NOTO) { Icons.Outlined.FontDownload }
        put(EmojiFontChoice.CUSTOM) { Icons.Outlined.Tune }
        put(EmojiFontChoice.INSTALLED) { Icons.Outlined.FolderOpen }

        // ---- Tools ----
        put(GifSourceMode.TABS) { Icons.Outlined.ViewCompact }
        put(GifSourceMode.MIX) { Icons.Outlined.Shuffle }

        // A ladder of protection rather than of size: the filter is about what
        // comes back, and "off" is the one that lets everything through.
        put(GifContentFilter.OFF) { Icons.Outlined.FilterAltOff }
        put(GifContentFilter.LOW) { Icons.Outlined.Shield }
        put(GifContentFilter.MEDIUM) { Icons.Outlined.Security }
        put(GifContentFilter.HIGH) { Icons.Outlined.VerifiedUser }

        put(MediaSendMode.IMAGE) { Icons.Outlined.Image }
        put(MediaSendMode.STICKER) { Icons.Outlined.Style }

        put(AppSortOrder.ALPHABETICAL) { Icons.Outlined.SortByAlpha }
        put(AppSortOrder.RECENT_FIRST) { Icons.Outlined.History }

        put(PowerSavingTrigger.OFF) { Icons.Outlined.Block }
        put(PowerSavingTrigger.SYSTEM_SAVER) { Icons.Outlined.BatterySaver }
        put(PowerSavingTrigger.LOW_BATTERY) { Icons.Outlined.BatteryAlert }
        put(PowerSavingTrigger.EITHER) { Icons.Outlined.AllInclusive }

        put(DataSaverTrigger.OFF) { Icons.Outlined.Block }
        put(DataSaverTrigger.METERED) { Icons.Outlined.NetworkCheck }
        put(DataSaverTrigger.ROAMING) { Icons.Outlined.Public }
        put(DataSaverTrigger.SYSTEM_SAVER) { Icons.Outlined.DataSaverOn }
        put(DataSaverTrigger.EITHER) { Icons.Outlined.AllInclusive }

        put(StatsPeriod.DAY) { Icons.Outlined.Today }
        put(StatsPeriod.WEEK) { Icons.Outlined.DateRange }
        put(StatsPeriod.MONTH) { Icons.Outlined.CalendarMonth }

        // ---- Modes ----
        put(ManualModeDuration.UNTIL_APP_CHANGES) { Icons.Outlined.Apps }
        put(ManualModeDuration.UNTIL_CHANGED) { Icons.Outlined.PushPin }

        // ---- Privacy and clipboard ----
        put(CopiedCodeChip.OFF) { Icons.Outlined.Block }
        put(CopiedCodeChip.CODE_FIELDS) { Icons.Outlined.Pin }
        put(CopiedCodeChip.ANY_FIELD) { Icons.Outlined.Public }

        put(SensitiveClipHandling.KEEP) { Icons.Outlined.ContentPaste }
        put(SensitiveClipHandling.SHORT_LIVED) { Icons.Outlined.Timer }
        put(SensitiveClipHandling.NEVER_SAVE) { Icons.Outlined.DeleteForever }

        // ---- Accessibility ----
        put(ScreenReaderMode.OFF) { Icons.Outlined.Block }
        put(ScreenReaderMode.LABELS) { Icons.Outlined.Label }
        put(ScreenReaderMode.EXPLORE) { Icons.Outlined.TouchApp }
        put(ScreenReaderMode.PASSTHROUGH) { Icons.Outlined.Accessibility }

        // ---- Snippets ----
        put(MultiExpand.DEFAULT) { Icons.Outlined.Tune }
        put(MultiExpand.CHIPS_ONLY) { Icons.Outlined.Label }
        put(MultiExpand.INSERT_FIRST) { Icons.AutoMirrored.Outlined.PlaylistAdd }
        put(MultiExpandMode.CHIPS_ONLY) { Icons.Outlined.Label }
        put(MultiExpandMode.INSERT_FIRST) { Icons.AutoMirrored.Outlined.PlaylistAdd }

        put(UppercaseStyle.CAPITALIZE) { Icons.Outlined.TextFields }
        put(UppercaseStyle.CAPITALIZE_WORDS) { Icons.Outlined.Title }
        put(UppercaseStyle.UPPERCASE) { Icons.Outlined.KeyboardCapslock }

        // ---- Backup ----
        // The screen this table was asked for: six destinations, and the
        // words alone do not say which of them is a server and which is an
        // account somewhere.
        put(BackupDestination.FOLDER) { Icons.Outlined.Folder }
        put(BackupDestination.WEBDAV) { Icons.Outlined.Dns }
        put(BackupDestination.DRIVE) { Icons.Outlined.Cloud }
        put(BackupDestination.S3) { Icons.Outlined.Storage }
        put(BackupDestination.DROPBOX) { Icons.Outlined.Cloud }
        put(BackupDestination.ONEDRIVE) { Icons.Outlined.Cloud }
        put(BackupDestination.FTP) { Icons.Outlined.Computer }

        put(MeteredPolicy.ALLOW) { Icons.Outlined.AllInclusive }
        put(MeteredPolicy.ASK) { Icons.Outlined.HelpOutline }
        put(MeteredPolicy.BLOCK) { Icons.Outlined.Block }

        // ---- App lock ----
        put(AppLockRelock.IMMEDIATE) { Icons.Outlined.Lock }
        put(AppLockRelock.AFTER_1_MIN) { Icons.Outlined.Timer }
        put(AppLockRelock.AFTER_5_MIN) { Icons.Outlined.Timer }
        put(AppLockRelock.ON_LEAVE) { Icons.Outlined.Doorbell }
        put(AppLockRelock.UNTIL_APP_CLOSES) { Icons.Outlined.History }

        // ---- AI providers ----
        // One glyph for the seven that are somebody's API and two for the
        // ones that are not: where the request goes is the only thing that
        // separates them, and it is the thing worth marking.
        put(AiProvider.ANTHROPIC) { Icons.Outlined.Cloud }
        put(AiProvider.OPENAI) { Icons.Outlined.Cloud }
        put(AiProvider.GEMINI) { Icons.Outlined.Cloud }
        put(AiProvider.XAI) { Icons.Outlined.Cloud }
        put(AiProvider.DEEPSEEK) { Icons.Outlined.Cloud }
        put(AiProvider.OPENAI_COMPATIBLE) { Icons.Outlined.Dns }
        put(AiProvider.OLLAMA) { Icons.Outlined.Computer }
        put(AiProvider.LM_STUDIO) { Icons.Outlined.Computer }
        put(AiProvider.ON_DEVICE) { Icons.Outlined.PhoneAndroid }

        // ---- Sets whose options differ only in degree or in name ----
        // One glyph for the whole set, on purpose. It says "these are all
        // wordlist sizes" or "these are all dialects", which is true, and it
        // stops a sheet looking half finished beside every other sheet. What
        // it must not do is give them *different* glyphs, which would promise
        // a difference the icons cannot actually show.
        for (level in QrEccLevel.entries) put(level) { Icons.Outlined.Healing }
        for (dialect in GrammarDialect.entries) put(dialect) { Icons.Outlined.Language }
        for (size in GlideVocabulary.entries) put(size) {
            Icons.AutoMirrored.Outlined.LibraryBooks
        }
        for (strength in LanguageDetectionStrength.entries) put(strength) {
            Icons.Outlined.Translate
        }
        for (system in NumeralSystem.entries) put(system) { Icons.Outlined.Numbers }
        for (scheme in DoublePinyinScheme.entries) put(scheme) { Icons.Outlined.Keyboard }

        // The "off" end of a set like that is a different thing from the
        // rest, so it keeps its own mark.
        put(DoublePinyinScheme.OFF) { Icons.Outlined.Block }
        put(GlideApostropheKey.OFF) { Icons.Outlined.Block }
        for (key in GlideApostropheKey.entries.filter { it != GlideApostropheKey.OFF }) {
            put(key) { Icons.Outlined.Keyboard }
        }
        put(UndoMemory.OFF) { Icons.Outlined.Block }
        for (level in UndoMemory.entries.filter { it != UndoMemory.OFF }) {
            put(level) { Icons.Outlined.History }
        }

        // ---- Accessibility: colour vision ----
        // The three deficiencies share a glyph because they are the same kind
        // of correction; only "none" and the greyscale filter do something
        // else, and only they get something else to look at.
        put(ColorVisionFilter.NONE) { Icons.Outlined.Block }
        put(ColorVisionFilter.DEUTERANOPIA) { Icons.Outlined.InvertColors }
        put(ColorVisionFilter.PROTANOPIA) { Icons.Outlined.InvertColors }
        put(ColorVisionFilter.TRITANOPIA) { Icons.Outlined.InvertColors }
        put(ColorVisionFilter.GRAYSCALE) { Icons.Outlined.FilterBAndW }

        // ---- Scripts ----
        // The composers are named after the schemes they implement (Telex,
        // Cangjie, Jyutping), and no glyph tells those apart. What a glyph
        // can say is how the keys are used, which is the part a reader
        // choosing between them can act on.
        put(ComposerType.NONE) { Icons.Outlined.Block }
        put(ComposerType.STROKE) { Icons.Outlined.Draw }
        put(ComposerType.VNI) { Icons.Outlined.Dialpad }
        put(ComposerType.T9_PINYIN) { Icons.Outlined.Dialpad }
        for (type in ComposerType.entries) putIfAbsent(type) { Icons.Outlined.Keyboard }

        put(HanVariant.HanRegion.GENERIC) { Icons.Outlined.Public }
        put(HanVariant.HanRegion.TAIWAN) { Icons.Outlined.Place }
        put(HanVariant.HanRegion.HONG_KONG) { Icons.Outlined.LocationCity }
    }

    /**
     * The glyph for [option], or null for anything this table has nothing
     * true to say about — which includes every option that is not an enum
     * constant at all. See the note on the object.
     */
    operator fun get(option: Any?): ImageVector? {
        val key = option as? Enum<*> ?: return null
        return map[key]?.invoke()
    }
}

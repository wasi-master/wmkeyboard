package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import com.wasimaster.wmkeyboard.core.input.composer.CjkLearning
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.settings.HoldToTalkRange
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.CopiedCodeChip
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.ViewCompact
import androidx.compose.material.icons.outlined.ViewStream

/** The permission that lets the clipboard read the user's screenshots. */
private val ImagesPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
private fun hasImagesPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, ImagesPermission) ==
        PackageManager.PERMISSION_GRANTED
@Composable
internal fun PrivacySettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // An unnamed group has no SectionHeader to hold it off the top bar, so the
    // breathing room a named group gets for free is spelled out here.
    Spacer(Modifier.height(12.dp))
    SettingsGroup {
        item {
            NavRow(
                R.string.privacy_permissions_title,
                stringResource(R.string.privacy_permissions_subtitle),
                route = "permissions",
            ) { onNavigate("permissions") }
        }
        item {
            val lock = LocalAppLock.current
            val lockStatus by lock.status.collectAsStateWithLifecycle()
            val lockConfig by lock.config.collectAsStateWithLifecycle()
            NavRow(
                R.string.privacy_lock_title,
                stringResource(R.string.privacy_lock_subtitle),
                value = stringResource(
                    when {
                        // What the phone can do beats what the flag says; see
                        // [AppLockSettings]. A row reading "On" next to gates
                        // that are standing aside would be a lie.
                        !lockStatus.canEnable -> R.string.privacy_lock_state_unavailable
                        lockConfig?.enabled == true -> R.string.privacy_lock_state_on
                        else -> R.string.privacy_lock_state_off
                    },
                ),
                route = AppLockTargets.ROUTE,
            ) { onNavigate(AppLockTargets.ROUTE) }
        }
    }
    SettingsGroup(
        stringResource(R.string.privacy_learning_group_title),
        info = stringResource(R.string.privacy_on_device_info),
    ) {
        item {
            ToggleSetting(
                R.string.privacy_learn_typing_title,
                stringResource(R.string.privacy_learn_typing_subtitle),
                settings.learnFromTyping,
                info = stringResource(R.string.privacy_learn_typing_info),
                default = SettingsDefaults.learnFromTyping,
            ) { scope.launch { repository.setLearnFromTyping(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_system_dictionary_title,
                stringResource(R.string.privacy_system_dictionary_subtitle),
                settings.addWordsToSystemDictionary,
                info = stringResource(R.string.privacy_system_dictionary_info),
                default = SettingsDefaults.addWordsToSystemDictionary,
            ) { scope.launch { repository.setAddWordsToSystemDictionary(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_use_system_dictionary_title,
                stringResource(R.string.privacy_use_system_dictionary_subtitle),
                settings.suggestionStrip.useSystemDictionary,
                info = stringResource(R.string.privacy_use_system_dictionary_info),
                default = SettingsDefaults.suggestionStrip.useSystemDictionary,
            ) { scope.launch { repository.setUseSystemDictionary(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_dict_shortcuts_title,
                stringResource(R.string.privacy_dict_shortcuts_subtitle),
                settings.suggestionStrip.expandUserDictShortcuts,
                info = stringResource(R.string.privacy_dict_shortcuts_info),
                default = SettingsDefaults.suggestionStrip.expandUserDictShortcuts,
            ) { scope.launch { repository.setExpandUserDictShortcuts(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_incognito_title,
                stringResource(R.string.privacy_incognito_subtitle),
                settings.incognito,
                info = stringResource(R.string.privacy_incognito_info),
                default = SettingsDefaults.incognito,
            ) { scope.launch { repository.setIncognito(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_auto_incognito_title,
                stringResource(R.string.privacy_auto_incognito_subtitle),
                settings.autoIncognito,
                info = stringResource(AUTO_INCOGNITO_INFO),
                default = SettingsDefaults.autoIncognito,
            ) { scope.launch { repository.setAutoIncognito(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.privacy_backup_group_title)) {
        item {
            ToggleSetting(
                R.string.privacy_backup_title,
                stringResource(R.string.privacy_backup_subtitle),
                settings.cloudBackup,
                info = stringResource(R.string.privacy_backup_info),
                default = SettingsDefaults.cloudBackup,
            ) { scope.launch { repository.setCloudBackup(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.privacy_data_group_title)) {
        item {
            ActionRow(
                title = R.string.privacy_delete_learned_words_title,
                subtitle = stringResource(R.string.privacy_delete_learned_words_subtitle),
                action = stringResource(R.string.privacy_delete_learned_words_action),
                confirm = stringResource(R.string.privacy_delete_learned_words_confirm),
                lock = AppLockTargets["action_delete_learned_words"],
            ) {
                scope.launch {
                    repository.clearLearnedData()
                    // The Chinese, Japanese and Cantonese picks are one of the
                    // files the repository deletes, but its live store is a
                    // `:core:input` object that `:core:settings` cannot reach,
                    // so the in-memory copy in this process is dropped here.
                    CjkLearning.store?.clear()
                }
            }
        }
    }
}
// ---- voice typing ----

/**
 * The Voice typing screen: the engine, the microphone view, and dictation.
 *
 * A Features row rather than the Voice typing tool page, which now holds one
 * row that opens this — the same split the Emoji tool has. Dictation is a way
 * of typing, and the microphone can be reached from a key or a hardware
 * shortcut with the tool nowhere on the toolbar, so these are not the tool's
 * settings.
 */
@Composable
internal fun VoiceSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val whisperEnabled = com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled()
    val usingWhisper = whisperEnabled && settings.whisper.engine == "whisper"
    if (whisperEnabled) {
        val systemEngine = stringResource(R.string.voice_engine_system)
        val whisperEngine = stringResource(R.string.voice_engine_whisper)
        SettingsGroup(stringResource(R.string.voice_engine_group)) {
            item {
                ChoiceSetting(
                    R.string.voice_engine_title,
                    subtitle = stringResource(R.string.voice_engine_subtitle),
                    // What the system engine is only matters while it is the one in use.
                    info = listOfNotNull(
                        stringResource(R.string.voice_engine_info),
                        stringResource(R.string.voice_system_info).takeIf { settings.whisper.engine == "system" },
                    ).joinToString("\n\n"),
                    options = listOf(
                        "system" to systemEngine,
                        "whisper" to whisperEngine,
                    ),
                    selected = settings.whisper.engine,
                    default = SettingsDefaults.whisper.engine,
                    detail = { engine ->
                        if (engine == "whisper") {
                            ChoiceDetail(
                                stringResource(R.string.voice_engine_whisper_desc),
                                Icons.Outlined.Memory,
                            )
                        } else {
                            ChoiceDetail(
                                stringResource(R.string.voice_engine_system_desc),
                                Icons.Outlined.PhoneAndroid,
                            )
                        }
                    },
                ) { scope.launch { repository.setVoiceEngine(it) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.voice_dictation_group)) {
        item {
            ChoiceSetting(
                R.string.voice_ui_title,
                subtitle = stringResource(R.string.voice_ui_subtitle),
                info = stringResource(R.string.voice_ui_info),
                options = listOf(
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_PANEL to
                        stringResource(R.string.voice_ui_panel),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP to
                        stringResource(R.string.voice_ui_strip),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_BAR to
                        stringResource(R.string.voice_ui_bar),
                ),
                selected = settings.voiceBar.mode,
                default = SettingsDefaults.voiceBar.mode,
                detail = { mode ->
                    ChoiceDetail(
                        stringResource(voiceUiDescRes(mode)),
                        when (mode) {
                            com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP -> Icons.Outlined.ViewStream
                            com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_BAR -> Icons.Outlined.ViewCompact
                            else -> Icons.Outlined.Dashboard
                        },
                    )
                },
            ) { scope.launch { repository.setVoiceUiMode(it) } }
        }
        item {
            ChoiceSetting(
                R.string.voice_typing_title,
                subtitle = stringResource(R.string.voice_typing_subtitle),
                info = stringResource(R.string.voice_typing_info),
                options = listOf(
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_BLOCK to
                        stringResource(R.string.voice_typing_block),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_INTERACTIVE to
                        stringResource(R.string.voice_typing_interactive),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN to
                        stringResource(R.string.voice_typing_plain),
                ),
                selected = settings.voiceBar.typingMode,
                default = SettingsDefaults.voiceBar.typingMode,
                detail = { mode ->
                    ChoiceDetail(
                        stringResource(voiceTypingDescRes(mode)),
                        when (mode) {
                            com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_INTERACTIVE -> Icons.Outlined.TouchApp
                            com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN -> Icons.Outlined.TextFields
                            else -> Icons.Outlined.Block
                        },
                    )
                },
            ) { scope.launch { repository.setVoiceTypingMode(it) } }
        }
        item {
            val holdMsFormat = stringResource(R.string.typing_value_milliseconds)
            SliderSetting(
                R.string.voice_hold_title,
                subtitle = stringResource(R.string.voice_hold_subtitle),
                value = settings.voiceBar.holdToTalkMs.toFloat(),
                range = HoldToTalkRange.first.toFloat()..HoldToTalkRange.last.toFloat(),
                display = { holdMsFormat.format((it / 50f).roundToInt() * 50) },
                info = stringResource(R.string.voice_hold_info),
                default = SettingsDefaults.voiceBar.holdToTalkMs.toFloat(),
            ) { picked ->
                scope.launch {
                    repository.setHoldToTalkMs((picked / 50f).roundToInt() * 50)
                }
            }
        }
        item {
            ToggleSetting(
                R.string.voice_continuous_title,
                stringResource(R.string.voice_continuous_subtitle),
                settings.voiceContinuous,
                default = SettingsDefaults.voiceContinuous,
            ) { scope.launch { repository.setVoiceContinuous(it) } }
        }
        item {
            ToggleSetting(
                R.string.voice_punctuation_title,
                stringResource(R.string.voice_punctuation_subtitle),
                settings.voiceSpokenPunctuation,
                default = SettingsDefaults.voiceSpokenPunctuation,
            ) { scope.launch { repository.setVoiceSpokenPunctuation(it) } }
        }
    }
    if (usingWhisper) {
        SettingsGroup(stringResource(R.string.voice_offline_group)) {
            item {
                ToggleSetting(
                    R.string.voice_translate_title,
                    stringResource(R.string.voice_translate_subtitle),
                    settings.whisper.translate,
                    default = SettingsDefaults.whisper.translate,
                ) { scope.launch { repository.setWhisperTranslate(it) } }
            }
        }
        WhisperModelManager(repository, settings)
    }
}
// ---- clipboard ----

/**
 * The Clipboard screen: the history, the panel, and the sensitive-clip rules.
 *
 * A Features row rather than the Clipboard tool page, which now holds one row
 * that opens this — the same split the Emoji tool has. The history is filled
 * by every copy you make, whether or not the panel's button is on the toolbar,
 * so these are not the tool's settings.
 */
@Composable
internal fun ClipboardSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The slider readouts are plain lambdas, so their format strings are
    // resolved here and captured. The format also puts the number through the
    // locale, which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    val minutesFormat = stringResource(R.string.values_minutes)
    val hoursFormat = stringResource(R.string.values_hours)
    // Both grants happen on a system screen, so they are read through
    // rememberGrantState: the rows below disappear as soon as we come back
    // with the permission in hand, instead of on the next unrelated redraw.
    val screenshotsGranted = rememberGrantState(::hasImagesPermission)
    val usageAccessGranted = rememberGrantState(::hasUsageAccess)
    SettingsGroup(stringResource(R.string.clipboard_history_group)) {
        item {
            ToggleSetting(
                R.string.clipboard_history_title,
                stringResource(R.string.clipboard_history_subtitle),
                settings.clipboard.history,
                default = SettingsDefaults.clipboard.history,
            ) { scope.launch { repository.setClipboardHistory(it) } }
        }
        item {
            SliderSetting(
                R.string.clipboard_max_title,
                subtitle = stringResource(R.string.clipboard_max_subtitle),
                value = settings.clipboard.maxItems.toFloat(),
                range = 5f..500f,
                display = { numberFormat.format(it.toInt()) },
                info = stringResource(R.string.clipboard_max_info),
                default = SettingsDefaults.clipboard.maxItems.toFloat(),
            ) { scope.launch { repository.setClipboardMaxItems(it.toInt()) } }
        }
        item {
            // The readout lambda is not composable, so the "never" word
            // is resolved here and captured, like the hours format.
            val never = stringResource(R.string.clipboard_expiry_never)
            SliderSetting(
                R.string.clipboard_expiry_title,
                subtitle = stringResource(R.string.clipboard_expiry_subtitle),
                value = settings.clipboard.expiryHours.toFloat(),
                range = 0f..168f,
                display = { if (it.toInt() == 0) never else hoursFormat.format(it.toInt()) },
                default = SettingsDefaults.clipboard.expiryHours.toFloat(),
            ) { scope.launch { repository.setClipboardExpiryHours(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_pinned_last_title,
                stringResource(R.string.clipboard_pinned_last_subtitle),
                settings.clipboard.pinnedLast,
                default = SettingsDefaults.clipboard.pinnedLast,
            ) { scope.launch { repository.setClipboardPinnedLast(it) } }
        }
        item {
            val context = LocalContext.current
            ToggleSetting(
                R.string.clipboard_screenshots_title,
                stringResource(R.string.clipboard_screenshots_subtitle),
                settings.clipboard.userScreenshots,
                default = SettingsDefaults.clipboard.userScreenshots,
            ) { on ->
                scope.launch { repository.setClipboardUserScreenshots(on) }
                if (on && !hasImagesPermission(context)) {
                    runCatching {
                        context.startActivity(Intent(context, ImagesPermissionActivity::class.java))
                    }
                }
            }
        }
        // The guard sits outside item {} on purpose: an item whose body
        // draws nothing still gets its own card, which showed up as a
        // sliver of empty surface once the permission was granted.
        if (settings.clipboard.userScreenshots && !screenshotsGranted) {
            item {
                val context = LocalContext.current
                NavRow(
                    R.string.clipboard_storage_permission_title,
                    stringResource(R.string.clipboard_storage_permission_subtitle),
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        item {
            val context = LocalContext.current
            val usageAccess = rememberDisclosedSpecialAccess(SpecialAccess.USAGE)
            ToggleSetting(
                R.string.clipboard_track_source_title,
                stringResource(R.string.clipboard_track_source_subtitle),
                settings.clipboard.trackSource,
                info = stringResource(R.string.clipboard_track_source_info),
                default = SettingsDefaults.clipboard.trackSource,
            ) { on ->
                scope.launch { repository.setClipboardTrackSource(on) }
                // Disclosure then the grant screen, the first time they
                // switch it on — but not when it is already granted, which
                // is the common case for a toggle flipped off and on again.
                if (on && !hasUsageAccess(context)) usageAccess()
            }
        }
        if (settings.clipboard.trackSource && !usageAccessGranted) {
            item {
                val usageAccessRow = rememberDisclosedSpecialAccess(SpecialAccess.USAGE)
                NavRow(
                    R.string.clipboard_usage_permission_title,
                    stringResource(R.string.clipboard_usage_permission_subtitle),
                ) { usageAccessRow() }
            }
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_suggest_group)) {
        item {
            ToggleSetting(
                R.string.clipboard_suggest_recent_title,
                stringResource(R.string.clipboard_suggest_recent_subtitle),
                settings.clipboard.suggestRecent,
                default = SettingsDefaults.clipboard.suggestRecent,
            ) { scope.launch { repository.setClipboardSuggestRecent(it) } }
        }
        if (settings.clipboard.suggestRecent) {
            item {
                val untilDismissed =
                    stringResource(R.string.clipboard_chip_until_dismissed)
                val chipMinutesFormat = stringResource(R.string.values_minutes)
                val secondsFormat = stringResource(R.string.values_seconds)
                SliderSetting(
                    R.string.clipboard_chip_life_title,
                    subtitle = stringResource(R.string.clipboard_chip_life_subtitle),
                    value = settings.clipboard.pasteChipSeconds.toFloat(),
                    // Steps of 30 s to 30 min, with 0 at the top of the
                    // range reading as a word rather than a duration.
                    range = 0f..1800f,
                    display = { value ->
                        val secs = (value / 30f).roundToInt() * 30
                        when {
                            secs <= 0 -> untilDismissed
                            secs < 60 -> secondsFormat.format(secs)
                            else -> chipMinutesFormat.format(secs / 60)
                        }
                    },
                    info = stringResource(R.string.clipboard_chip_life_info),
                    default = SettingsDefaults.clipboard.pasteChipSeconds.toFloat(),
                ) { value ->
                    val secs = (value / 30f).roundToInt() * 30
                    scope.launch { repository.setPasteChipSeconds(secs) }
                }
            }
        }
        if (settings.clipboard.suggestRecent) {
            item {
                ChoiceSetting(
                    title = R.string.clipboard_suggest_codes_title,
                    subtitle = stringResource(R.string.clipboard_suggest_codes_subtitle),
                    info = stringResource(R.string.clipboard_suggest_codes_info),
                    options = CopiedCodeChip.entries.map { it to stringResource(it.labelRes) },
                    selected = settings.clipboard.copiedCodeChip,
                    default = SettingsDefaults.clipboard.copiedCodeChip,
                    detail = { chip -> ChoiceDetail(stringResource(copiedCodeChipDescRes(chip))) },
                ) { scope.launch { repository.setClipboardCopiedCodeChip(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.clipboard_entities_title,
                stringResource(R.string.clipboard_entities_subtitle),
                settings.clipboard.detectEntities,
                info = stringResource(R.string.clipboard_entities_info),
                default = SettingsDefaults.clipboard.detectEntities,
            ) { scope.launch { repository.setClipboardDetectEntities(it) } }
        }
        // The number chips are the ones that go wrong, because a phone
        // number is the one fragment with no shape of its own. This row
        // is where the user gives it one.
        if (settings.clipboard.detectEntities) {
            item {
                val count = settings.clipboard.phoneFormats.size
                NavRow(
                    R.string.clipboard_phone_formats_title,
                    subtitle = if (count == 0) {
                        stringResource(R.string.clipboard_phone_formats_subtitle)
                    } else {
                        pluralStringResource(
                            R.plurals.clipboard_phone_formats_count_subtitle,
                            count,
                            count,
                        )
                    },
                    route = "phoneformats",
                    onClick = { onNavigate("phoneformats") },
                )
            }
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_panel_group)) {
        // The panel's grid — and the abc / space / backspace row the old toggle
        // here switched on — is a panel layout now (issue #63).
        item {
            NavRow(
                title = R.string.panel_layout_row_title,
                subtitle = stringResource(R.string.panel_layout_row_subtitle),
            ) { onNavigate("panel_edit/${PanelKind.CLIPBOARD.name}") }
        }
        item {
            ToggleSetting(
                R.string.clipboard_full_bleed_title,
                stringResource(R.string.clipboard_full_bleed_subtitle),
                settings.clipboard.fullBleed,
                info = stringResource(R.string.clipboard_full_bleed_info),
                default = SettingsDefaults.clipboard.fullBleed,
            ) { scope.launch { repository.setClipboardFullBleed(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_search_title,
                stringResource(R.string.clipboard_search_subtitle),
                settings.clipboard.search,
                default = SettingsDefaults.clipboard.search,
            ) { scope.launch { repository.setClipboardSearch(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_link_previews_title,
                stringResource(R.string.clipboard_link_previews_subtitle),
                settings.clipboard.linkPreviews,
                default = SettingsDefaults.clipboard.linkPreviews,
            ) { scope.launch { repository.setClipboardLinkPreviews(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_toast_title,
                stringResource(R.string.clipboard_toast_subtitle),
                settings.feedback.toastOnCopy,
                info = stringResource(R.string.clipboard_toast_info),
                default = SettingsDefaults.feedback.toastOnCopy,
            ) { scope.launch { repository.setToastOnCopy(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_sensitive_group)) {
        item {
            ToggleSetting(
                R.string.clipboard_password_paste_title,
                stringResource(R.string.clipboard_password_paste_subtitle),
                settings.clipboard.clearAfterPasswordPaste,
                info = stringResource(R.string.clipboard_password_paste_info),
                default = SettingsDefaults.clipboard.clearAfterPasswordPaste,
            ) { scope.launch { repository.setClipboardClearAfterPasswordPaste(it) } }
        }
        item {
            ChoiceSetting(
                title = R.string.clipboard_sensitive_title,
                subtitle = stringResource(R.string.clipboard_sensitive_subtitle),
                info = stringResource(R.string.clipboard_sensitive_info),
                options = SensitiveClipHandling.entries.map { it to stringResource(it.labelRes) },
                selected = settings.clipboard.sensitiveHandling,
                default = SettingsDefaults.clipboard.sensitiveHandling,
                // The enum has carried the line under each answer since it was
                // written; the picker only now has somewhere to draw it.
                detail = { handling -> ChoiceDetail(stringResource(handling.detailRes)) },
            ) { scope.launch { repository.setClipboardSensitiveHandling(it) } }
        }
        if (settings.clipboard.sensitiveHandling != SensitiveClipHandling.KEEP) {
            item {
                ToggleSetting(
                    R.string.clipboard_detect_sensitive_title,
                    stringResource(R.string.clipboard_detect_sensitive_subtitle),
                    settings.clipboard.detectSensitive,
                    info = stringResource(R.string.clipboard_detect_sensitive_info),
                    default = SettingsDefaults.clipboard.detectSensitive,
                ) { scope.launch { repository.setClipboardDetectSensitive(it) } }
            }
        }
        if (settings.clipboard.sensitiveHandling == SensitiveClipHandling.SHORT_LIVED) {
            item {
                SliderSetting(
                    R.string.clipboard_sensitive_expiry_title,
                    subtitle = stringResource(
                        R.string.clipboard_sensitive_expiry_subtitle,
                    ),
                    value = settings.clipboard.sensitiveExpiryMinutes.toFloat(),
                    range = 1f..120f,
                    display = { minutesFormat.format(it.toInt()) },
                    default = SettingsDefaults.clipboard.sensitiveExpiryMinutes.toFloat(),
                ) {
                    scope.launch { repository.setClipboardSensitiveExpiryMinutes(it.toInt()) }
                }
            }
        }
    }
}

/** What the microphone button opens under each answer, for the picker sheet. */
private fun voiceUiDescRes(mode: String): Int = when (mode) {
    VoiceBarSettings.MODE_STRIP -> R.string.voice_ui_strip_desc
    VoiceBarSettings.MODE_BAR -> R.string.voice_ui_bar_desc
    else -> R.string.voice_ui_panel_desc
}

/** How voice typing and the keys share the field, for the picker sheet. */
private fun voiceTypingDescRes(mode: String): Int = when (mode) {
    VoiceBarSettings.TYPING_INTERACTIVE -> R.string.voice_typing_interactive_desc
    VoiceBarSettings.TYPING_PLAIN -> R.string.voice_typing_plain_desc
    else -> R.string.voice_typing_block_desc
}

/** Where a copied code may be offered, for the picker sheet. */
private fun copiedCodeChipDescRes(chip: CopiedCodeChip): Int = when (chip) {
    CopiedCodeChip.OFF -> R.string.clipboard_suggest_codes_off_desc
    CopiedCodeChip.CODE_FIELDS -> R.string.clipboard_suggest_codes_fields_desc
    CopiedCodeChip.ANY_FIELD -> R.string.clipboard_suggest_codes_any_desc
}

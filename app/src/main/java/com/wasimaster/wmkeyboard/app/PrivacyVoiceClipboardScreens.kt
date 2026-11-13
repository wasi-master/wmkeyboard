package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.ui.DisabledReason
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.VoiceChoiceRef
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Reorder
import com.wasimaster.wmkeyboard.ime.R as ImeR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.settings.ClipRecentChipsMax
import com.wasimaster.wmkeyboard.core.settings.ClipLineSpacingRange
import com.wasimaster.wmkeyboard.core.settings.ClipSwipePin
import com.wasimaster.wmkeyboard.core.settings.ClipCardShortcut
import com.wasimaster.wmkeyboard.core.settings.ClipHoldAction
import com.wasimaster.wmkeyboard.core.settings.HoldToTalkRange
import com.wasimaster.wmkeyboard.core.settings.VoiceSilenceStopRange
import com.wasimaster.wmkeyboard.core.settings.ClipboardView
import com.wasimaster.wmkeyboard.core.settings.ClipGridColumnsRange
import com.wasimaster.wmkeyboard.core.settings.ClipMaxItemsSteps
import com.wasimaster.wmkeyboard.core.settings.ClipMaxTextCharsSteps
import com.wasimaster.wmkeyboard.core.settings.ClipPanelExtraHeightRange
import com.wasimaster.wmkeyboard.core.settings.PanelOverKeysExtraHeightRange
import com.wasimaster.wmkeyboard.core.settings.ClipPreviewLinesRange
import com.wasimaster.wmkeyboard.core.settings.ClipTimeLabel
import com.wasimaster.wmkeyboard.core.settings.CopiedCodeChip
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GraphicEq
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speed
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Memory
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhoneAndroid
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Block
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dns
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dashboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TouchApp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewCompact
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewStream

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
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Decides which rows the group holds; the rows read everything else.
    val learnOn = settings.watch { it.learnFromTyping }
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
        item(level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.netlog_title,
                stringResource(R.string.netlog_subtitle),
                route = "network_activity",
            ) { onNavigate("network_activity") }
        }
        item(level = SettingLevel.STANDARD) {
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
                learnOn,
                info = stringResource(R.string.privacy_learn_typing_info),
                default = SettingsDefaults.learnFromTyping,
            ) { scope.launch { repository.setLearnFromTyping(it) } }
        }
        // Words only reach the system dictionary through the same learn path
        // the switch above owns, and that path returns before the mirror.
        if (learnOn) item {
            ToggleSetting(
                R.string.privacy_system_dictionary_title,
                stringResource(R.string.privacy_system_dictionary_subtitle),
                settings.watch { it.addWordsToSystemDictionary },
                info = stringResource(R.string.privacy_system_dictionary_info),
                default = SettingsDefaults.addWordsToSystemDictionary,
            ) { scope.launch { repository.setAddWordsToSystemDictionary(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.privacy_use_system_dictionary_title,
                stringResource(R.string.privacy_use_system_dictionary_subtitle),
                settings.watch { it.suggestionStrip.useSystemDictionary },
                info = stringResource(R.string.privacy_use_system_dictionary_info),
                default = SettingsDefaults.suggestionStrip.useSystemDictionary,
            ) { scope.launch { repository.setUseSystemDictionary(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.privacy_dict_shortcuts_title,
                stringResource(R.string.privacy_dict_shortcuts_subtitle),
                settings.watch { it.suggestionStrip.expandUserDictShortcuts },
                info = stringResource(R.string.privacy_dict_shortcuts_info),
                default = SettingsDefaults.suggestionStrip.expandUserDictShortcuts,
            ) { scope.launch { repository.setExpandUserDictShortcuts(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_incognito_title,
                stringResource(R.string.privacy_incognito_subtitle),
                settings.watch { it.incognito },
                info = stringResource(R.string.privacy_incognito_info),
                default = SettingsDefaults.incognito,
            ) { scope.launch { repository.setIncognito(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.privacy_auto_incognito_title,
                stringResource(R.string.privacy_auto_incognito_subtitle),
                settings.watch { it.autoIncognito },
                info = stringResource(AUTO_INCOGNITO_INFO),
                default = SettingsDefaults.autoIncognito,
            ) { scope.launch { repository.setAutoIncognito(it) } }
        }
    }
    IncognitoAppsGroup(repository, settings)
    SettingsGroup(stringResource(R.string.privacy_backup_group_title)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.privacy_backup_title,
                stringResource(R.string.privacy_backup_subtitle),
                settings.watch { it.cloudBackup },
                info = stringResource(R.string.privacy_backup_info),
                default = SettingsDefaults.cloudBackup,
            ) { scope.launch { repository.setCloudBackup(it) } }
        }
    }
    OtherAppsGroup(repository, onNavigate)
    // F-Droid's opt-in and the offline build's import are what a feature's
    // message points to, so they show at every depth; the checksum switch,
    // which is for one rare situation, stays with Everything.
    val libraryChoice = NativeDelivery.active && (NativeConsent.downloadsNeedOptIn || !InternetPermission.granted)
    SettingsGroup(
        stringResource(R.string.privacy_downloads_group_title),
        level = if (libraryChoice) SettingLevel.BASIC else SettingLevel.ADVANCED,
    ) {
        item(level = SettingLevel.ADVANCED) { VerifyChecksumsRow(repository, settings) }
        item(visible = FeatureLibraryList.offered) { FeatureLibrariesNavRow() }
        item { FeatureLibrariesRows() }
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

/**
 * The switch behind [IntegrityChecks]. Turning it on is instant; turning it
 * off asks first, because it is the one switch on this screen that makes the
 * keyboard trust a file it would otherwise refuse.
 */
@Composable
private fun VerifyChecksumsRow(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val verify = settings.watch { it.verifyChecksums }
    var confirmOff by remember { mutableStateOf(false) }
    ToggleSetting(
        R.string.privacy_verify_checksums_title,
        stringResource(
            if (verify) R.string.privacy_verify_checksums_subtitle else R.string.privacy_verify_checksums_off_subtitle,
        ),
        verify,
        info = stringResource(R.string.privacy_verify_checksums_info),
        default = SettingsDefaults.verifyChecksums,
    ) { on ->
        if (on) scope.launch { repository.setVerifyChecksums(true) } else confirmOff = true
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.privacy_verify_checksums_confirm_title)) },
            text = { Text(stringResource(R.string.privacy_verify_checksums_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    scope.launch { repository.setVerifyChecksums(false) }
                }) { Text(stringResource(CommonR.string.common_disable)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
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
internal fun VoiceSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val whisperEnabled = com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled()
    // No library off arm64 or where F-Droid stripped it, so nothing to offer there (#548).
    val whistleEnabled = com.wasimaster.wmkeyboard.core.voice.whistle.WhistleEngine.shipped
    // The engines whose models download file by file: the ExecuTorch ones
    // behind Asr4All (#289), Parakeet and Moonshine, and Sherpa and
    // Omnilingual on ONNX Runtime, each wherever its own runtime is.
    // What decides which groups and rows the screen holds; each row reads its
    // own value.
    // The default engine: the first on the voice list that can run.
    val engine = settings.watch { it.whisper.engine }
    val voiceUiMode = settings.watch { it.voiceBar.mode }
    val typingMode = settings.watch { it.voiceBar.typingMode }
    val usingWhisper = whisperEnabled && engine == "whisper"
    val etEngine = com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine.byId(engine)?.takeIf { it.runtimeShipped }
    // Every engine something can send dictation to: a row of the list, on or
    // off, or a language's own choice. Their models and setup are on each
    // engine's own screen; here they only decide whether denoise applies.
    val engines = settings.watch { s ->
        s.resolveProviders(ProviderTool.VOICE, all = true).mapNotNull { ProviderTypes.VOICE_ENGINES[it.typeId] }.toSet() +
            s.whisper.voiceByLang.values.map { VoiceChoiceRef.parse(it).engine }
    }
    val whisperListed = whisperEnabled && "whisper" in engines
    val whistleListed = whistleEnabled && "whistle" in engines
    val etListed = engines.mapNotNull { com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine.byId(it) }
        .filter { it.runtimeShipped }
    // The master switch (#578). Off, the rest of the screen is about something
    // the keyboard no longer does, so it goes; the values stay stored.
    val voiceOn = settings.watch { it.voiceBar.enabled }
    val micSwitchOn = settings.watch { it.voiceBar.micSwitch }
    val systemInputOn = settings.watch { it.voiceBar.systemInput }
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.voice_enabled_title,
                stringResource(R.string.voice_enabled_subtitle),
                voiceOn,
                info = stringResource(R.string.voice_enabled_info),
                default = SettingsDefaults.voiceBar.enabled,
            ) { scope.launch { repository.setVoiceEnabled(it) } }
        }
    }
    if (!voiceOn) return
    // The engines are a provider list: the first that is on and can run is
    // the default, and a transcription server is a row with its address,
    // key and model, as many as the user runs.
    ProvidersGroup(
        repository,
        settings,
        ProviderTool.VOICE,
        titleRes = R.string.voice_engine_title,
        info = stringResource(R.string.voice_engine_info) + "\n\n" + stringResource(R.string.voice_engines_list_info),
    )
    // The system recognizer's own switch, the engines' models and the
    // servers' options are on each engine's screen, opened from its row.
    SettingsGroup(stringResource(R.string.voice_languages_group)) {
        item { VoiceLanguageRow(repository, settings) }
        item { VoiceEngineLanguagesRow(repository, settings) }
    }
    SettingsGroup(stringResource(R.string.voice_dictation_group)) {
        item(level = SettingLevel.STANDARD) {
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
                selected = voiceUiMode,
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
        if (voiceUiMode == com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP) {
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.voice_strip_language_title,
                    stringResource(R.string.voice_strip_language_subtitle),
                    settings.watch { it.voiceBar.stripLanguageChip },
                    info = stringResource(R.string.voice_strip_language_info),
                    default = SettingsDefaults.voiceBar.stripLanguageChip,
                ) { scope.launch { repository.setVoiceStripLanguageChip(it) } }
            }
        }
        item(level = SettingLevel.STANDARD) {
            // With only one block at a time on offer, the row says what brings
            // the other two (#852): a list of one, with nothing on it saying
            // why, read as a setting that would not change.
            val keysShownForModes = voiceUiMode == com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP
            ChoiceSetting(
                R.string.voice_typing_title,
                subtitle = if (keysShownForModes) {
                    stringResource(R.string.voice_typing_subtitle)
                } else {
                    stringResource(R.string.voice_typing_subtitle_needs_strip, stringResource(R.string.voice_ui_strip))
                },
                info = stringResource(R.string.voice_typing_info),
                // Typing while you speak needs the keys on screen, which only
                // the compact bar leaves there; plain text needs an engine that
                // can be asked for no punctuation, the system recognizer. The
                // mode in force stays listed so it can be changed back.
                options = listOf(
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_BLOCK to
                        stringResource(R.string.voice_typing_block),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_INTERACTIVE to
                        stringResource(R.string.voice_typing_interactive),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN to
                        stringResource(R.string.voice_typing_plain),
                ).filter { (mode, _) ->
                    val keysShown = voiceUiMode == com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP
                    mode == typingMode ||
                        mode == com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_BLOCK ||
                        (keysShown && mode == com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_INTERACTIVE) ||
                        (keysShown && engine == "system")
                },
                selected = typingMode,
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
        item(level = SettingLevel.STANDARD) {
            // Interactive and plain typing chain sessions whatever this says,
            // so it is drawn off rather than hidden: a missing row would read
            // as chaining being gone, when it is in fact forced on.
            val chainingForced =
                typingMode != com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_BLOCK
            ToggleSetting(
                R.string.voice_continuous_title,
                stringResource(R.string.voice_continuous_subtitle),
                settings.watch { it.voiceContinuous } || chainingForced,
                enabled = !chainingForced,
                disabledReason = rememberDisabledReason(
                    stringResource(R.string.voice_continuous_forced_reason),
                    R.string.voice_typing_title,
                ),
                default = SettingsDefaults.voiceContinuous,
            ) { scope.launch { repository.setVoiceContinuous(it) } }
        }
        item {
            ToggleSetting(
                R.string.voice_keep_screen_on_title,
                stringResource(R.string.voice_keep_screen_on_subtitle),
                settings.watch { it.voiceBar.keepScreenOn },
                default = SettingsDefaults.voiceBar.keepScreenOn,
            ) { scope.launch { repository.setVoiceKeepScreenOn(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            val offLabel = stringResource(R.string.voice_silence_stop_off)
            val secondsFormat = stringResource(R.string.voice_silence_stop_seconds)
            SliderSetting(
                R.string.voice_silence_stop_title,
                subtitle = stringResource(R.string.voice_silence_stop_subtitle),
                value = settings.watch { it.voiceBar.silenceStopMs }.toFloat(),
                range = 0f..VoiceSilenceStopRange.last.toFloat(),
                display = { picked ->
                    val ms = (picked / 250f).roundToInt() * 250
                    if (ms == 0) offLabel else secondsFormat.format(ms / 1000f)
                },
                info = stringResource(R.string.voice_silence_stop_info),
                default = SettingsDefaults.voiceBar.silenceStopMs.toFloat(),
            ) { picked ->
                scope.launch { repository.setVoiceSilenceStopMs((picked / 250f).roundToInt() * 250) }
            }
        }
        // Only the engines that decode as they hear end a phrase by a detector
        // of their own (#976); the others keep the recorder's loudness stop.
        val liveListed = etListed.any { it.live }
        if (liveListed) item(level = SettingLevel.ADVANCED) {
            VoicePhraseEndSetting(repository, settings, scope)
        }
        if (typingMode != com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN) item {
            ToggleSetting(
                R.string.voice_punctuation_title,
                stringResource(R.string.voice_punctuation_subtitle),
                settings.watch { it.voiceSpokenPunctuation },
                default = SettingsDefaults.voiceSpokenPunctuation,
            ) { scope.launch { repository.setVoiceSpokenPunctuation(it) } }
        }
        // Only Sherpa, Omnilingual and Vosk write bare words; plain voice
        // typing takes the words exactly as said.
        if (typingMode != com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN &&
            etListed.any { it.onnx || it == com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine.VOSK }
        ) {
            item { VoicePunctuationSetting(repository, settings, scope) }
        }
        // Plain voice typing is the words exactly as said, so it is never
        // tidied (#499) and the row goes with the punctuation one above.
        if (typingMode != com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN) item {
            ToggleSetting(
                R.string.voice_ai_tidy_title,
                stringResource(R.string.voice_ai_tidy_subtitle),
                settings.watch { it.voiceBar.aiTidy },
                info = stringResource(R.string.voice_ai_tidy_info),
                default = SettingsDefaults.voiceBar.aiTidy,
            ) { scope.launch { repository.setVoiceAiTidy(it) } }
        }
        item {
            ToggleSetting(
                R.string.voice_mask_swearing_title,
                stringResource(R.string.voice_mask_swearing_subtitle),
                settings.watch { it.voiceBar.maskSwearing },
                info = stringResource(R.string.voice_mask_swearing_info),
                default = SettingsDefaults.voiceBar.maskSwearing,
            ) { scope.launch { repository.setVoiceMaskSwearing(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.voice_suppress_symbols_title,
                stringResource(R.string.voice_suppress_symbols_subtitle),
                settings.watch { it.voiceBar.suppressSymbols },
                info = stringResource(R.string.voice_suppress_symbols_info),
                default = SettingsDefaults.voiceBar.suppressSymbols,
            ) { scope.launch { repository.setVoiceSuppressSymbols(it) } }
        }
    }
    // The keyboard's engines for every other app's microphone (#1142).
    SettingsGroup(stringResource(R.string.voice_other_apps_group)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.voice_system_input_title,
                stringResource(R.string.voice_system_input_subtitle),
                systemInputOn,
                info = stringResource(R.string.voice_system_input_info),
                default = SettingsDefaults.voiceBar.systemInput,
            ) { scope.launch { repository.setVoiceSystemInput(it) } }
        }
        if (systemInputOn) item(level = SettingLevel.STANDARD) {
            val context = LocalContext.current
            NavRow(
                R.string.voice_system_input_pick_title,
                stringResource(R.string.voice_system_input_pick_subtitle),
            ) { openVoiceInputSettings(context, R.string.voice_system_input_pick_hint) }
        }
    }
    // What most people never change: kept in reach, out of the way.
    SettingsGroup(
        stringResource(R.string.tooldetail_advanced_group),
        foldKey = "voice_advanced",
        foldSummary = {
            listOf(
                R.string.voice_hold_picks_title,
                R.string.voice_resume_title,
                R.string.voice_pause_media_title,
                R.string.voice_start_sound_title,
                R.string.voice_mic_switch_title,
                R.string.voice_engine_picker_title,
            ).map { stringResource(it) }.joinToString(", ")
        },
    ) {
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.voice_hold_picks_title,
                stringResource(R.string.voice_hold_picks_subtitle),
                settings.watch { it.voiceBar.holdPicksTypingMode },
                info = stringResource(R.string.voice_hold_picks_info),
                default = SettingsDefaults.voiceBar.holdPicksTypingMode,
            ) { scope.launch { repository.setVoiceHoldPicksTypingMode(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.voice_resume_title,
                stringResource(R.string.voice_resume_subtitle),
                settings.watch { it.voiceBar.resumeOnShow },
                info = stringResource(R.string.voice_resume_info),
                default = SettingsDefaults.voiceBar.resumeOnShow,
            ) { scope.launch { repository.setVoiceResumeOnShow(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.voice_pause_media_title,
                stringResource(R.string.voice_pause_media_subtitle),
                settings.watch { it.voiceBar.pauseMedia },
                info = stringResource(R.string.voice_pause_media_info),
                default = SettingsDefaults.voiceBar.pauseMedia,
            ) { scope.launch { repository.setVoicePauseMedia(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            VoiceStartSoundRow(settings.watch { it.voiceBar.startSound }) { picked ->
                scope.launch { repository.setVoiceStartSound(picked) }
            }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.voice_mic_switch_title,
                stringResource(R.string.voice_mic_switch_subtitle),
                settings.watch { it.voiceBar.micSwitch },
                info = stringResource(R.string.voice_mic_switch_info),
                default = SettingsDefaults.voiceBar.micSwitch,
            ) { scope.launch { repository.setVoiceMicSwitch(it) } }
        }
        // The pick behind the switch; read where the rest of the screen's
        // shape is decided, since this builder is not composable.
        if (micSwitchOn) item {
            ChoiceSetting(
                R.string.voice_mic_title,
                subtitle = stringResource(R.string.voice_mic_subtitle),
                info = stringResource(R.string.voice_mic_info),
                options = listOf(
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MIC_AUTO to
                        stringResource(R.string.voice_mic_auto),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MIC_BLUETOOTH to
                        stringResource(R.string.voice_mic_bluetooth),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MIC_WIRED to
                        stringResource(R.string.voice_mic_wired),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MIC_USB to
                        stringResource(R.string.voice_mic_usb),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MIC_PHONE to
                        stringResource(R.string.voice_mic_phone),
                ),
                selected = settings.watch { it.voiceBar.mic },
                default = SettingsDefaults.voiceBar.mic,
            ) { scope.launch { repository.setVoiceMic(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.voice_engine_picker_title,
                stringResource(R.string.voice_engine_picker_subtitle),
                settings.watch { it.voiceBar.enginePicker },
                info = stringResource(R.string.voice_engine_picker_info),
                default = SettingsDefaults.voiceBar.enginePicker,
            ) { scope.launch { repository.setVoiceEnginePicker(it) } }
        }
        // Only the panel's mic reads a hold: the strip and collapsed-bar mics
        // are plain taps, and the panel is never opened in those modes.
        if (voiceUiMode == com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_PANEL) item {
            val holdMsFormat = stringResource(R.string.typing_value_milliseconds)
            SliderSetting(
                R.string.voice_hold_title,
                subtitle = stringResource(R.string.voice_hold_subtitle),
                value = settings.watch { it.voiceBar.holdToTalkMs }.toFloat(),
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
        // Only the offline engines record the audio themselves, so only they
        // can have it cleaned first.
        if (whisperListed || whistleListed || etListed.isNotEmpty()) item {
            VoiceDenoiseSetting(repository, settings, scope)
        }
    }
    // The group shows for the engines that read it (#305): Whistle takes it as
    // keywords (#548), offline Whisper's whisper.cpp models as their prompt
    // (#440), and Sherpa's transducers as hotwords. Where only LiteRT graphs
    // run, Whisper has no way to take a hint. asr4all, Parakeet, Moonshine and
    // Omnilingual have no way to take one either.
    val etTakesWords = etEngine == null || etEngine == com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine.SHERPA
    if ((!usingWhisper || WhisperCatalog.cppAvailable) && etTakesWords) {
        SettingsGroup(stringResource(R.string.voice_bias_group), foldKey = "voice_bias") {
            item {
                ToggleSetting(
                    R.string.voice_bias_personal_title,
                    stringResource(R.string.voice_bias_personal_subtitle),
                    settings.watch { it.whisper.biasPersonalWords },
                    info = stringResource(R.string.voice_bias_personal_info),
                    default = SettingsDefaults.whisper.biasPersonalWords,
                ) { scope.launch { repository.setVoiceBiasPersonalWords(it) } }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.voice_bias_words_label),
                    value = settings.watch { it.whisper.biasWords },
                    hint = stringResource(R.string.voice_bias_words_hint),
                    default = SettingsDefaults.whisper.biasWords,
                ) { repository.setVoiceBiasWords(it) }
            }
        }
    }
}

/**
 * Android's own page for the phone's voice input (#1142), where WM Keyboard
 * is picked once **Voice input for other apps** is on. Phones file it in
 * different places, so the nearest one that opens is used: voice input,
 * then default apps, where the digital assistant row holds it, then
 * Settings itself. [hint] says which row on that page to pick.
 */
internal fun openVoiceInputSettings(context: Context, @StringRes hint: Int? = null) {
    val tries = listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        Settings.ACTION_SETTINGS,
    )
    for (action in tries) {
        val opened = runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) {
            // Recent Android has no page of voice input's own: the action
            // opens the digital assistant page, with Voice input as a row near
            // its foot (#1276). Without a word it reads as the wrong page.
            if (hint != null) Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
            return
        }
    }
}

/**
 * What every transcription server on the voice list is sent with (#286):
 * whether to name the language, and the prompt. Where each server is, its
 * key, model and route, and the Test button, are the server row's own setup
 * on the list above; the docs page carries the recipes.
 */
@Composable
internal fun VoiceServerSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.voice_server_group)) {
        item {
            ToggleSetting(
                R.string.voice_server_language_title,
                stringResource(R.string.voice_server_language_subtitle),
                settings.watch { it.whisper.serverSendLanguage },
                default = SettingsDefaults.whisper.serverSendLanguage,
            ) { scope.launch { repository.setVoiceServerSendLanguage(it) } }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.voice_server_prompt_label),
                value = settings.watch { it.whisper.serverPrompt },
                hint = stringResource(R.string.voice_server_prompt_hint),
                default = SettingsDefaults.whisper.serverPrompt,
            ) { repository.setVoiceServerPrompt(it) }
        }
    }
    VoiceServerLanguages(repository, settings)
}

// ---- clipboard ----

/**
 * The Clipboard hub: the history switch, which is also the door to the
 * history page, the doors to the paste chip and the rules pages, and the Panel
 * fold (SETTINGS_REDESIGN.md §4). Fifty-one rows on one screen were the
 * complaint; the three pages hold them by what they are about.
 *
 * A Features row rather than the Clipboard tool page, which holds one row that
 * opens this — the same split the Emoji tool has. The history is filled by
 * every copy you make, whether or not the panel's button is on the toolbar, so
 * these are not the tool's settings.
 */
@Composable
internal fun ClipboardSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the groups hold; each row reads its own value.
    val historyOn = settings.watch { it.clipboard.history }
    val swipeToDelete = settings.watch { it.clipboard.swipeToDelete }
    val clipSearch = settings.watch { it.clipboard.search }
    val clipView = settings.watch { it.clipboard.view }
    // The slider readouts are plain lambdas, so their format strings are
    // resolved here and captured. The format also puts the number through the
    // locale, which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    SettingsGroup {
        item {
            ToggleNavRow(
                R.string.clipboard_history_title,
                stringResource(R.string.clipboard_history_subtitle),
                checked = historyOn,
                route = "clipboard/history",
                default = SettingsDefaults.clipboard.history,
                onChange = { scope.launch { repository.setClipboardHistory(it) } },
                onOpen = { onNavigate("clipboard/history") },
            )
        }
        item {
            NavRow(
                R.string.clipboard_page_chips_title,
                stringResource(R.string.clipboard_page_chips_subtitle),
                route = "clipboard/chips",
            ) { onNavigate("clipboard/chips") }
        }
        item {
            NavRow(
                R.string.clipboard_page_rules_title,
                stringResource(R.string.clipboard_page_rules_subtitle),
                route = "clipboard/rules",
            ) { onNavigate("clipboard/rules") }
        }
    }
    // The shared board (#1201): a folder on one of the WebDAV places under
    // Backup & restore, where every device that picks it leaves clips for
    // the others. Off until a place is picked.
    val boardPlaces = settings.watch { s ->
        s.autoBackup.locations.filter {
            it.type == com.wasimaster.wmkeyboard.core.settings.BackupDestination.WEBDAV && it.configured
        }
    }
    val boardPicked = settings.watch { it.clipboard.boardLocation }
    SettingsGroup(stringResource(R.string.clipboard_board_group)) {
        item {
            ChoiceSetting(
                R.string.clipboard_board_title,
                subtitle = stringResource(
                    if (boardPlaces.isEmpty()) R.string.clipboard_board_none_subtitle else R.string.clipboard_board_subtitle,
                ),
                info = stringResource(R.string.clipboard_board_info),
                options = listOf("" to stringResource(R.string.clipboard_board_off)) +
                    boardPlaces.map { place ->
                        place.id to place.name.ifEmpty { Uri.parse(place.webDavUrl).host ?: place.webDavUrl }
                    },
                selected = boardPicked.takeIf { id -> id.isEmpty() || boardPlaces.any { it.id == id } } ?: "",
                default = SettingsDefaults.clipboard.boardLocation,
            ) { scope.launch { repository.setClipboardBoardLocation(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_panel_group), level = SettingLevel.ADVANCED) {
        // The panel's grid — and the abc / space / backspace row the old toggle
        // here switched on — is a panel layout now (issue #63).
        item {
            NavRow(
                title = R.string.panel_layout_row_title,
                subtitle = stringResource(R.string.panel_layout_row_subtitle),
            ) { onNavigate(panelEditRoute(PanelKind.CLIPBOARD)) }
        }
        item {
            ToggleSetting(
                R.string.clipboard_full_bleed_title,
                stringResource(R.string.clipboard_full_bleed_subtitle),
                settings.watch { it.clipboard.fullBleed },
                info = stringResource(R.string.clipboard_full_bleed_info),
                default = SettingsDefaults.clipboard.fullBleed,
            ) { scope.launch { repository.setClipboardFullBleed(it) } }
        }
        item {
            // Also set by dragging the bar on top of the panel (#414).
            val none = stringResource(R.string.clipboard_panel_height_none)
            val taller = stringResource(R.string.clipboard_panel_height_value)
            SliderSetting(
                R.string.clipboard_panel_height_title,
                subtitle = stringResource(R.string.clipboard_panel_height_subtitle),
                value = settings.watch { it.clipboard.panelExtraHeightDp }.toFloat(),
                range = ClipPanelExtraHeightRange.first.toFloat()..ClipPanelExtraHeightRange.last.toFloat(),
                display = {
                    val dp = it.roundToInt()
                    if (dp == 0) none else taller.format(dp)
                },
                info = stringResource(R.string.clipboard_panel_height_info),
                default = SettingsDefaults.clipboard.panelExtraHeightDp.toFloat(),
            ) { scope.launch { repository.setClipboardPanelExtraHeightDp(it.roundToInt()) } }
        }
        item {
            // Also set by dragging the bar on a panel over the keys (#615), and
            // shared by every such panel, not the clipboard's alone.
            val none = stringResource(R.string.clipboard_over_keys_height_none)
            val taller = stringResource(R.string.clipboard_panel_height_value)
            SliderSetting(
                R.string.clipboard_over_keys_height_title,
                subtitle = stringResource(R.string.clipboard_over_keys_height_subtitle),
                value = settings.watch { it.layoutBehavior.panelOverKeysExtraHeightDp }.toFloat(),
                range = PanelOverKeysExtraHeightRange.first.toFloat()..PanelOverKeysExtraHeightRange.last.toFloat(),
                display = {
                    val dp = it.roundToInt()
                    if (dp == 0) none else taller.format(dp)
                },
                info = stringResource(R.string.clipboard_over_keys_height_info),
                default = SettingsDefaults.layoutBehavior.panelOverKeysExtraHeightDp.toFloat(),
            ) { scope.launch { repository.setPanelOverKeysExtraHeightDp(it.roundToInt()) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_key_row_title,
                stringResource(R.string.clipboard_key_row_subtitle),
                settings.watch { it.clipboard.keyRow },
                info = stringResource(R.string.clipboard_key_row_info),
                default = SettingsDefaults.clipboard.keyRow,
            ) { scope.launch { repository.setClipboardKeyRow(it) } }
        }
        item {
            ChoiceSetting(
                title = R.string.clipboard_view_title,
                subtitle = stringResource(R.string.clipboard_view_subtitle),
                info = stringResource(R.string.clipboard_view_info),
                options = ClipboardView.entries.map { it to stringResource(it.labelRes) },
                selected = clipView,
                default = SettingsDefaults.clipboard.view,
            ) { scope.launch { repository.setClipboardView(it) } }
        }
        item(visible = clipView == ClipboardView.GRID) {
            SliderSetting(
                R.string.clipboard_columns_title,
                subtitle = stringResource(R.string.clipboard_columns_subtitle),
                value = settings.watch { it.clipboard.gridColumns }.toFloat(),
                range = ClipGridColumnsRange.first.toFloat()..ClipGridColumnsRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.clipboard_columns_info),
                default = SettingsDefaults.clipboard.gridColumns.toFloat(),
            ) { scope.launch { repository.setClipboardGridColumns(it.roundToInt()) } }
        }
        item {
            val auto = stringResource(R.string.clipboard_lines_auto)
            SliderSetting(
                R.string.clipboard_lines_title,
                subtitle = stringResource(R.string.clipboard_lines_subtitle),
                value = settings.watch { it.clipboard.previewLines }.toFloat(),
                range = ClipPreviewLinesRange.first.toFloat()..ClipPreviewLinesRange.last.toFloat(),
                display = { if (it.roundToInt() == 0) auto else numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.clipboard_lines_info),
                default = SettingsDefaults.clipboard.previewLines.toFloat(),
            ) { scope.launch { repository.setClipboardPreviewLines(it.roundToInt()) } }
        }
        item {
            val percent = stringResource(R.string.clipboard_line_spacing_value)
            SliderSetting(
                R.string.clipboard_line_spacing_title,
                subtitle = stringResource(R.string.clipboard_line_spacing_subtitle),
                value = settings.watch { it.clipboard.lineSpacing }.toFloat(),
                range = ClipLineSpacingRange.first.toFloat()..ClipLineSpacingRange.last.toFloat(),
                display = { percent.format((it / 5f).roundToInt() * 5) },
                info = stringResource(R.string.clipboard_line_spacing_info),
                default = SettingsDefaults.clipboard.lineSpacing.toFloat(),
            ) { scope.launch { repository.setClipboardLineSpacing((it / 5f).roundToInt() * 5) } }
        }
        item {
            ChoiceSetting(
                title = R.string.clipboard_time_title,
                subtitle = stringResource(R.string.clipboard_time_subtitle),
                info = stringResource(R.string.clipboard_time_info),
                options = ClipTimeLabel.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.clipboard.timeLabel },
                default = SettingsDefaults.clipboard.timeLabel,
            ) { scope.launch { repository.setClipboardTimeLabel(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_numbers_title,
                stringResource(R.string.clipboard_numbers_subtitle),
                settings.watch { it.clipboard.showNumbers },
                info = stringResource(R.string.clipboard_numbers_info),
                default = SettingsDefaults.clipboard.showNumbers,
            ) { scope.launch { repository.setClipboardShowNumbers(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_pinned_tabs_title,
                stringResource(R.string.clipboard_pinned_tabs_subtitle),
                settings.watch { it.clipboard.pinnedTabs },
                info = stringResource(R.string.clipboard_pinned_tabs_info),
                default = SettingsDefaults.clipboard.pinnedTabs,
            ) { scope.launch { repository.setClipboardPinnedTabs(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_outline_pinned_title,
                stringResource(R.string.clipboard_outline_pinned_subtitle),
                settings.watch { it.clipboard.outlinePinned },
                default = SettingsDefaults.clipboard.outlinePinned,
            ) { scope.launch { repository.setClipboardOutlinePinned(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_card_buttons_title,
                stringResource(R.string.clipboard_card_buttons_subtitle),
                settings.watch { it.clipboard.cardButtons },
                info = stringResource(R.string.clipboard_card_buttons_info),
                default = SettingsDefaults.clipboard.cardButtons,
            ) { scope.launch { repository.setClipboardCardButtons(it) } }
        }
        item {
            ChoiceSetting(
                title = R.string.clipboard_card_shortcut_title,
                subtitle = stringResource(R.string.clipboard_card_shortcut_subtitle),
                info = stringResource(R.string.clipboard_card_shortcut_info),
                options = ClipCardShortcut.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.clipboard.cardShortcut },
                default = SettingsDefaults.clipboard.cardShortcut,
            ) { scope.launch { repository.setClipboardCardShortcut(it) } }
        }
        // Issue #1094: the icons under a held clip, in the order a thumb wants.
        item(level = SettingLevel.ADVANCED) {
            val labels = clipHoldActionLabels()
            ReorderSetting(
                title = stringResource(R.string.clipboard_hold_order_title),
                dialogTitle = stringResource(R.string.clipboard_hold_order_title),
                items = settings.watch { it.clipboard.holdActions },
                label = { labels.getValue(it) },
                icon = Icons.Outlined.Reorder,
            ) { scope.launch { repository.setClipboardHoldActions(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.clipboard_hold_paste_title,
                stringResource(R.string.clipboard_hold_paste_subtitle),
                settings.watch { it.clipboard.holdPaste },
                info = stringResource(R.string.clipboard_hold_paste_info),
                default = SettingsDefaults.clipboard.holdPaste,
            ) { scope.launch { repository.setClipboardHoldPaste(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_type_out_title,
                stringResource(R.string.clipboard_type_out_subtitle),
                settings.watch { it.clipboard.typeOutPastes },
                info = stringResource(R.string.clipboard_type_out_info),
                default = SettingsDefaults.clipboard.typeOutPastes,
            ) { scope.launch { repository.setClipboardTypeOutPastes(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_bump_on_paste_title,
                stringResource(R.string.clipboard_bump_on_paste_subtitle),
                settings.watch { it.clipboard.bumpOnPaste },
                default = SettingsDefaults.clipboard.bumpOnPaste,
            ) { scope.launch { repository.setClipboardBumpOnPaste(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_backspace_undoes_paste_title,
                stringResource(R.string.clipboard_backspace_undoes_paste_subtitle),
                settings.watch { it.clipboard.backspaceUndoesPaste },
                info = stringResource(R.string.clipboard_backspace_undoes_paste_info),
                default = SettingsDefaults.clipboard.backspaceUndoesPaste,
            ) { scope.launch { repository.setClipboardBackspaceUndoesPaste(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_type_tags_title,
                stringResource(R.string.clipboard_type_tags_subtitle),
                settings.watch { it.clipboard.typeTags },
                info = stringResource(R.string.clipboard_type_tags_info),
                default = SettingsDefaults.clipboard.typeTags,
            ) { scope.launch { repository.setClipboardTypeTags(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_keep_rich_text_title,
                stringResource(R.string.clipboard_keep_rich_text_subtitle),
                settings.watch { it.clipboard.keepRichText },
                info = stringResource(R.string.clipboard_keep_rich_text_info),
                default = SettingsDefaults.clipboard.keepRichText,
            ) { scope.launch { repository.setClipboardKeepRichText(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_swipe_delete_title,
                stringResource(R.string.clipboard_swipe_delete_subtitle),
                settings.watch { it.clipboard.swipeToDelete },
                info = stringResource(R.string.clipboard_swipe_delete_info),
                default = SettingsDefaults.clipboard.swipeToDelete,
            ) { scope.launch { repository.setClipboardSwipeToDelete(it) } }
        }
        item(visible = swipeToDelete) {
            ChoiceSetting(
                title = R.string.clipboard_swipe_pin_title,
                subtitle = stringResource(R.string.clipboard_swipe_pin_subtitle),
                info = stringResource(R.string.clipboard_swipe_pin_info),
                options = ClipSwipePin.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.clipboard.swipePin },
                default = SettingsDefaults.clipboard.swipePin,
            ) { scope.launch { repository.setClipboardSwipePin(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_undo_delete_title,
                stringResource(R.string.clipboard_undo_delete_subtitle),
                settings.watch { it.clipboard.undoDelete },
                info = stringResource(R.string.clipboard_undo_delete_info),
                default = SettingsDefaults.clipboard.undoDelete,
            ) { scope.launch { repository.setClipboardUndoDelete(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_confirm_pinned_delete_title,
                stringResource(R.string.clipboard_confirm_pinned_delete_subtitle),
                settings.watch { it.clipboard.confirmPinnedDelete },
                info = stringResource(R.string.clipboard_confirm_pinned_delete_info),
                default = SettingsDefaults.clipboard.confirmPinnedDelete,
            ) { scope.launch { repository.setClipboardConfirmPinnedDelete(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_search_title,
                stringResource(R.string.clipboard_search_subtitle),
                settings.watch { it.clipboard.search },
                default = SettingsDefaults.clipboard.search,
            ) { scope.launch { repository.setClipboardSearch(it) } }
        }
        item(visible = clipSearch) {
            ToggleSetting(
                R.string.clipboard_search_regex_title,
                stringResource(R.string.clipboard_search_regex_subtitle),
                settings.watch { it.clipboard.searchRegex },
                info = stringResource(R.string.clipboard_search_regex_info),
                default = SettingsDefaults.clipboard.searchRegex,
            ) { scope.launch { repository.setClipboardSearchRegex(it) } }
        }
        item(visible = clipSearch) {
            ToggleSetting(
                R.string.clipboard_search_notes_title,
                stringResource(R.string.clipboard_search_notes_subtitle),
                settings.watch { it.clipboard.searchNotes },
                info = stringResource(R.string.clipboard_search_notes_info),
                default = SettingsDefaults.clipboard.searchNotes,
            ) { scope.launch { repository.setClipboardSearchNotes(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_clear_button_title,
                stringResource(R.string.clipboard_clear_button_subtitle),
                settings.watch { it.clipboard.clearButton },
                info = stringResource(R.string.clipboard_clear_button_info),
                default = SettingsDefaults.clipboard.clearButton,
            ) { scope.launch { repository.setClipboardClearButton(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_link_previews_title,
                stringResource(R.string.clipboard_link_previews_subtitle),
                settings.watch { it.clipboard.linkPreviews },
                default = SettingsDefaults.clipboard.linkPreviews,
            ) { scope.launch { repository.setClipboardLinkPreviews(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_toast_title,
                stringResource(R.string.clipboard_toast_subtitle),
                settings.watch { it.feedback.toastOnCopy },
                info = stringResource(R.string.clipboard_toast_info),
                default = SettingsDefaults.feedback.toastOnCopy,
            ) { scope.launch { repository.setToastOnCopy(it) } }
        }
    }
}

/**
 * Clipboard history: how much of it is kept, where pinned clips sit, and the
 * two extra things a clip can carry (a screenshot, the app it came from).
 */
@Composable
internal fun ClipboardHistorySettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the groups hold; each row reads its own value.
    val historyOn = settings.watch { it.clipboard.history }
    val userScreenshots = settings.watch { it.clipboard.userScreenshots }
    val textOnly = settings.watch { it.clipboard.textOnly }
    val trackSource = settings.watch { it.clipboard.trackSource }
    val numberFormat = stringResource(R.string.values_number)
    val hoursFormat = stringResource(R.string.values_hours)
    // Both grants happen on a system screen, so they are read through
    // rememberGrantState: the rows below disappear as soon as we come back
    // with the permission in hand, instead of on the next unrelated redraw.
    val screenshotsGranted = rememberGrantState(::hasImagesPermission)
    val usageAccessGranted = rememberGrantState(::hasUsageAccess)
    SettingsGroup(stringResource(R.string.clipboard_limits_group)) {
        item(level = SettingLevel.STANDARD) {
            // Stops, with no cap at all as the last one (#414). A value from
            // before the stops shows at the nearest one until it is moved.
            val unlimited = stringResource(R.string.clipboard_max_unlimited)
            val steps = ClipMaxItemsSteps
            val stored = settings.watch { it.clipboard.maxItems }
            val index = if (stored <= 0) {
                steps.lastIndex
            } else {
                steps.indices.filter { steps[it] > 0 }.minByOrNull { kotlin.math.abs(steps[it] - stored) } ?: 0
            }
            SliderSetting(
                R.string.clipboard_max_title,
                subtitle = stringResource(R.string.clipboard_max_subtitle),
                value = index.toFloat(),
                range = 0f..steps.lastIndex.toFloat(),
                display = {
                    val items = steps[it.roundToInt().coerceIn(steps.indices)]
                    if (items == 0) unlimited else numberFormat.format(items)
                },
                info = stringResource(R.string.clipboard_max_info),
                default = steps.indexOf(SettingsDefaults.clipboard.maxItems).coerceAtLeast(0).toFloat(),
            ) { scope.launch { repository.setClipboardMaxItems(steps[it.roundToInt().coerceIn(steps.indices)]) } }
        }
        item(level = SettingLevel.STANDARD) {
            // The readout lambda is not composable, so the "never" word
            // is resolved here and captured, like the hours format.
            val never = stringResource(R.string.clipboard_expiry_never)
            SliderSetting(
                R.string.clipboard_expiry_title,
                subtitle = stringResource(R.string.clipboard_expiry_subtitle),
                value = settings.watch { it.clipboard.expiryHours }.toFloat(),
                range = 0f..168f,
                display = { if (it.toInt() == 0) never else hoursFormat.format(it.toInt()) },
                default = SettingsDefaults.clipboard.expiryHours.toFloat(),
            ) { scope.launch { repository.setClipboardExpiryHours(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            // A slider over a handful of stops rather than every number: the
            // choice is "about how much", and a round figure is one the user
            // can set again.
            val none = stringResource(R.string.clipboard_max_chars_none)
            val steps = ClipMaxTextCharsSteps
            val stored = settings.watch { it.clipboard.maxTextChars }
            val index = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - stored) } ?: 0
            SliderSetting(
                R.string.clipboard_max_chars_title,
                subtitle = stringResource(R.string.clipboard_max_chars_subtitle),
                value = index.toFloat(),
                range = 0f..steps.lastIndex.toFloat(),
                display = {
                    val chars = steps[it.roundToInt().coerceIn(steps.indices)]
                    if (chars == 0) none else numberFormat.format(chars)
                },
                info = stringResource(R.string.clipboard_max_chars_info),
                default = steps.indexOf(SettingsDefaults.clipboard.maxTextChars).coerceAtLeast(0).toFloat(),
            ) { scope.launch { repository.setClipboardMaxTextChars(steps[it.roundToInt().coerceIn(steps.indices)]) } }
        }
    }
    // The tabs put pinned clips on a tab of their own, and in place keeps them
    // where they were copied: either way there is no end of the list left for
    // them to go to, so the row goes while one of those is on.
    val pinnedEndFree = !settings.watch { it.clipboard.pinnedTabs } && !settings.watch { it.clipboard.pinnedInPlace }
    SettingsGroup(stringResource(R.string.clipboard_pinned_group)) {
        item(visible = pinnedEndFree, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.clipboard_pinned_last_title,
                stringResource(R.string.clipboard_pinned_last_subtitle),
                settings.watch { it.clipboard.pinnedLast },
                default = SettingsDefaults.clipboard.pinnedLast,
            ) { scope.launch { repository.setClipboardPinnedLast(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.clipboard_pinned_in_place_title,
                stringResource(R.string.clipboard_pinned_in_place_subtitle),
                settings.watch { it.clipboard.pinnedInPlace },
                info = stringResource(R.string.clipboard_pinned_in_place_info),
                default = SettingsDefaults.clipboard.pinnedInPlace,
            ) { scope.launch { repository.setClipboardPinnedInPlace(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.clipboard_pinned_folders_title,
                stringResource(R.string.clipboard_pinned_folders_subtitle),
                settings.watch { it.clipboard.pinnedFolders },
                info = stringResource(R.string.clipboard_pinned_folders_info),
                default = SettingsDefaults.clipboard.pinnedFolders,
            ) { scope.launch { repository.setClipboardPinnedFolders(it) } }
        }
    }
    // Nothing is stored with history off, so there is nothing for these to
    // ride along with; the group goes rather than standing empty.
    if (historyOn) SettingsGroup(stringResource(R.string.clipboard_sources_group)) {
        // Text only (gap list item 25): pictures, videos and files stay on the
        // system clipboard and out of history, screenshots included.
        if (historyOn) item {
            ToggleSetting(
                R.string.clipboard_text_only_title,
                stringResource(R.string.clipboard_text_only_subtitle),
                textOnly,
                default = SettingsDefaults.clipboard.textOnly,
            ) { on -> scope.launch { repository.setClipboardTextOnly(on) } }
        }
        // Screenshots, the source app and the paste chip are all read as a
        // clip is being stored, and nothing is stored with history off. The
        // Play build has no photos permission to ask for, so no screenshots.
        if (historyOn && ChannelFeatures.SCREENSHOT_CLIPS) item {
            val context = LocalContext.current
            ToggleSetting(
                R.string.clipboard_screenshots_title,
                stringResource(R.string.clipboard_screenshots_subtitle),
                userScreenshots && !textOnly,
                enabled = !textOnly,
                disabledReason = if (textOnly) DisabledReason(stringResource(R.string.clipboard_screenshots_text_only)) else null,
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
        if (historyOn && userScreenshots && !textOnly && ChannelFeatures.SCREENSHOT_CLIPS &&
            !screenshotsGranted
        ) {
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
        if (historyOn) item {
            val context = LocalContext.current
            val usageAccess = rememberDisclosedSpecialAccess(SpecialAccess.USAGE)
            ToggleSetting(
                R.string.clipboard_track_source_title,
                stringResource(R.string.clipboard_track_source_subtitle),
                trackSource,
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
        if (historyOn && trackSource &&
            !usageAccessGranted
        ) {
            item {
                val usageAccessRow = rememberDisclosedSpecialAccess(SpecialAccess.USAGE)
                NavRow(
                    R.string.clipboard_usage_permission_title,
                    stringResource(R.string.clipboard_usage_permission_subtitle),
                ) { usageAccessRow() }
            }
        }
    }
}

/**
 * The paste chip and the codes, numbers and links found in a clip: everything
 * the history puts on the suggestion strip.
 */
@Composable
internal fun ClipboardChipsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the groups hold; each row reads its own value.
    val historyOn = settings.watch { it.clipboard.history }
    val suggestRecent = settings.watch { it.clipboard.suggestRecent }
    val detectEntities = settings.watch { it.clipboard.detectEntities }
    // Every row here reads a clip as it is stored, and nothing is stored with
    // history off. The banner says so and offers the switch, where the old
    // group simply drew nothing.
    if (!historyOn) {
        StateBanner(
            stringResource(R.string.clipboard_history_off_banner),
            action = stringResource(R.string.clipboard_history_off_action),
        ) { scope.launch { repository.setClipboardHistory(true) } }
        return
    }
    SettingsGroup(stringResource(R.string.clipboard_chip_group)) {
        item {
            ToggleSetting(
                R.string.clipboard_suggest_recent_title,
                stringResource(R.string.clipboard_suggest_recent_subtitle),
                suggestRecent,
                default = SettingsDefaults.clipboard.suggestRecent,
            ) { scope.launch { repository.setClipboardSuggestRecent(it) } }
        }
        item(visible = suggestRecent, level = SettingLevel.ADVANCED) {
            val untilDismissed =
                stringResource(R.string.clipboard_chip_until_dismissed)
            val chipMinutesFormat = stringResource(R.string.values_minutes)
            val secondsFormat = stringResource(R.string.values_seconds)
            SliderSetting(
                R.string.clipboard_chip_life_title,
                subtitle = stringResource(R.string.clipboard_chip_life_subtitle),
                value = settings.watch { it.clipboard.pasteChipSeconds }.toFloat(),
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
                // Seconds, then minutes: a bare number cannot say which.
                typed = false,
            ) { value ->
                val secs = (value / 30f).roundToInt() * 30
                scope.launch { repository.setPasteChipSeconds(secs) }
            }
        }
        item(visible = suggestRecent, level = SettingLevel.ADVANCED) {
            // Issue #414: the last few copies as a row, FUTO-style.
            SliderSetting(
                R.string.clipboard_recent_chips_title,
                subtitle = stringResource(R.string.clipboard_recent_chips_subtitle),
                value = settings.watch { it.clipboard.recentChips }.toFloat(),
                range = 1f..ClipRecentChipsMax.toFloat(),
                display = { it.toInt().toString() },
                info = stringResource(R.string.clipboard_recent_chips_info),
                default = SettingsDefaults.clipboard.recentChips.toFloat(),
            ) { scope.launch { repository.setClipboardRecentChips(it.toInt()) } }
        }
        item(visible = suggestRecent, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clipboard_compact_paste_chip_title,
                stringResource(R.string.clipboard_compact_paste_chip_subtitle),
                settings.watch { it.clipboard.compactPasteChip },
                info = stringResource(R.string.clipboard_compact_paste_chip_info),
                default = SettingsDefaults.clipboard.compactPasteChip,
            ) { scope.launch { repository.setClipboardCompactPasteChip(it) } }
        }
        item(visible = suggestRecent, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.clipboard_suggest_codes_title,
                subtitle = stringResource(R.string.clipboard_suggest_codes_subtitle),
                info = stringResource(R.string.clipboard_suggest_codes_info),
                options = CopiedCodeChip.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.clipboard.copiedCodeChip },
                default = SettingsDefaults.clipboard.copiedCodeChip,
                detail = { chip -> ChoiceDetail(stringResource(copiedCodeChipDescRes(chip))) },
            ) { scope.launch { repository.setClipboardCopiedCodeChip(it) } }
        }
        item(visible = suggestRecent, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clipboard_secret_chip_title,
                stringResource(R.string.clipboard_secret_chip_subtitle),
                settings.watch { it.clipboard.secretChipInPasswordFields },
                info = stringResource(R.string.clipboard_secret_chip_info),
                default = SettingsDefaults.clipboard.secretChipInPasswordFields,
            ) { scope.launch { repository.setClipboardSecretChipInPasswordFields(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_entities_group)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clipboard_entities_title,
                stringResource(R.string.clipboard_entities_subtitle),
                detectEntities,
                info = stringResource(R.string.clipboard_entities_info),
                default = SettingsDefaults.clipboard.detectEntities,
            ) { scope.launch { repository.setClipboardDetectEntities(it) } }
        }
        item(visible = detectEntities, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.clipboard_entity_icons_title,
                stringResource(R.string.clipboard_entity_icons_subtitle),
                settings.watch { it.clipboard.entityIcons },
                info = stringResource(R.string.clipboard_entity_icons_info),
                default = SettingsDefaults.clipboard.entityIcons,
            ) { scope.launch { repository.setClipboardEntityIcons(it) } }
        }
        item(visible = detectEntities, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.clipboard_entity_to_clipboard_title,
                stringResource(R.string.clipboard_entity_to_clipboard_subtitle),
                settings.watch { it.clipboard.entityToClipboard },
                default = SettingsDefaults.clipboard.entityToClipboard,
            ) { scope.launch { repository.setClipboardEntityToClipboard(it) } }
        }
        item(visible = detectEntities, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.clipboard_fragments_on_strip_title,
                stringResource(R.string.clipboard_fragments_on_strip_subtitle),
                settings.watch { it.clipboard.fragmentsOnStrip },
                info = stringResource(R.string.clipboard_fragments_on_strip_info),
                default = SettingsDefaults.clipboard.fragmentsOnStrip,
            ) { scope.launch { repository.setClipboardFragmentsOnStrip(it) } }
        }
    }
}

/**
 * Rules and privacy: the rules a copy goes through before the history keeps
 * it, the phone-number masks, and how a password or a code is handled.
 */
@Composable
internal fun ClipboardRulesSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the groups hold; each row reads its own value.
    val historyOn = settings.watch { it.clipboard.history }
    val detectEntities = settings.watch { it.clipboard.detectEntities }
    val sensitiveHandling = settings.watch { it.clipboard.sensitiveHandling }
    val minutesFormat = stringResource(R.string.values_minutes)
    SettingsGroup(stringResource(R.string.clipboard_rules_group)) {
        // The number chips are the ones that go wrong, because a phone
        // number is the one fragment with no shape of its own. This row
        // is where the user gives it one.
        // Before the history keeps a copy, the user's own patterns tidy it (#612).
        item(level = SettingLevel.STANDARD) {
            val count = settings.watch { it.clipboard.copyRules.size }
            NavRow(
                R.string.clipboard_copy_rules_title,
                subtitle = if (count == 0) {
                    stringResource(R.string.clipboard_copy_rules_subtitle)
                } else {
                    pluralStringResource(R.plurals.clipboard_copy_rules_count_subtitle, count, count)
                },
                route = "cliprules",
                onClick = { onNavigate("cliprules") },
            )
        }
        item(visible = detectEntities, level = SettingLevel.ADVANCED) {
            val count = settings.watch { it.clipboard.phoneFormats.size }
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
    SettingsGroup(stringResource(R.string.clipboard_sensitive_group)) {
        item {
            ToggleSetting(
                R.string.clipboard_private_copies_title,
                stringResource(R.string.clipboard_private_copies_subtitle),
                settings.watch { it.clipboard.privateCopies },
                info = stringResource(R.string.clipboard_private_copies_info),
                default = SettingsDefaults.clipboard.privateCopies,
            ) { scope.launch { repository.setClipboardPrivateCopies(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clipboard_password_paste_title,
                stringResource(R.string.clipboard_password_paste_subtitle),
                settings.watch { it.clipboard.clearAfterPasswordPaste },
                info = stringResource(R.string.clipboard_password_paste_info),
                default = SettingsDefaults.clipboard.clearAfterPasswordPaste,
            ) { scope.launch { repository.setClipboardClearAfterPasswordPaste(it) } }
        }
        // Pictures and videos lose their location, camera and dates (#1179).
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clipboard_strip_media_title,
                stringResource(R.string.clipboard_strip_media_subtitle),
                settings.watch { it.clipboard.stripMediaMetadata },
                info = stringResource(R.string.clipboard_strip_media_info),
                default = SettingsDefaults.clipboard.stripMediaMetadata,
            ) { scope.launch { repository.setClipboardStripMediaMetadata(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.clipboard_sensitive_title,
                subtitle = stringResource(R.string.clipboard_sensitive_subtitle),
                info = stringResource(R.string.clipboard_sensitive_info),
                options = SensitiveClipHandling.entries.map { it to stringResource(it.labelRes) },
                selected = sensitiveHandling,
                default = SettingsDefaults.clipboard.sensitiveHandling,
                // The enum has carried the line under each answer since it was
                // written; the picker only now has somewhere to draw it.
                detail = { handling -> ChoiceDetail(stringResource(handling.detailRes)) },
            ) { scope.launch { repository.setClipboardSensitiveHandling(it) } }
        }
        // Detection runs in the same listener, which returns with history off.
        if (historyOn &&
            sensitiveHandling != SensitiveClipHandling.KEEP
        ) {
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.clipboard_detect_sensitive_title,
                    stringResource(R.string.clipboard_detect_sensitive_subtitle),
                    settings.watch { it.clipboard.detectSensitive },
                    info = stringResource(R.string.clipboard_detect_sensitive_info),
                    default = SettingsDefaults.clipboard.detectSensitive,
                ) { scope.launch { repository.setClipboardDetectSensitive(it) } }
            }
        }
        item(visible = sensitiveHandling == SensitiveClipHandling.SHORT_LIVED, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.clipboard_sensitive_expiry_title,
                subtitle = stringResource(
                    R.string.clipboard_sensitive_expiry_subtitle,
                ),
                value = settings.watch { it.clipboard.sensitiveExpiryMinutes }.toFloat(),
                range = 1f..120f,
                display = { minutesFormat.format(it.toInt()) },
                default = SettingsDefaults.clipboard.sensitiveExpiryMinutes.toFloat(),
            ) {
                scope.launch { repository.setClipboardSensitiveExpiryMinutes(it.toInt()) }
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

/** What each icon under a held clip is called, in the keyboard's own words for it (#1094). */
@Composable
private fun clipHoldActionLabels(): Map<ClipHoldAction, String> = ClipHoldAction.entries.associateWith {
    stringResource(
        when (it) {
            ClipHoldAction.DETAILS -> ImeR.string.ime_clip_details_desc
            ClipHoldAction.PASTE_NEXT -> ImeR.string.ime_clip_paste_next
            ClipHoldAction.SELECT -> ImeR.string.ime_clip_select
            ClipHoldAction.REORDER -> ImeR.string.ime_clip_reorder
            ClipHoldAction.SHOW_IN_HISTORY -> ImeR.string.ime_clip_show_in_history
            ClipHoldAction.KEEP_ONLY -> ImeR.string.ime_clip_keep_only
            ClipHoldAction.EDIT -> ImeR.string.ime_clip_edit
            ClipHoldAction.TAGS -> ImeR.string.ime_clip_tags
            ClipHoldAction.QR -> ImeR.string.ime_clip_qr
            ClipHoldAction.WEB_SEARCH -> ImeR.string.ime_clip_search_web
            ClipHoldAction.WIKIPEDIA -> ImeR.string.ime_clip_search_wikipedia
            ClipHoldAction.OPEN_LINK -> ImeR.string.ime_clip_open_link
            ClipHoldAction.COMPOSE_EMAIL -> ImeR.string.ime_clip_compose_email
            ClipHoldAction.EXTRACT -> ImeR.string.ime_clip_extract_parts
            ClipHoldAction.PIN -> ImeR.string.ime_clip_pin
            ClipHoldAction.DELETE -> CommonR.string.common_delete
        },
    )
}

/**
 * The sound the microphone opens with (#1260), picked in Android's own sound
 * picker, which lists the phone's notification sounds and lets the user add
 * one of their own. "None" in the picker is no sound, the default.
 */
@Composable
private fun VoiceStartSoundRow(uri: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        @Suppress("DEPRECATION")
        val picked: Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        onPick(picked?.toString().orEmpty())
    }
    val name = if (uri.isEmpty()) {
        stringResource(R.string.voice_start_sound_none)
    } else {
        remember(uri) {
            runCatching { android.media.RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }.getOrNull()
        } ?: stringResource(R.string.voice_start_sound_custom)
    }
    NavRow(
        R.string.voice_start_sound_title,
        stringResource(R.string.voice_start_sound_subtitle),
        value = name,
    ) {
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uri.takeIf { it.isNotEmpty() }?.let(Uri::parse))
        runCatching { launcher.launch(intent) }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.DeviceNetworkState
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.VocabularySettings
import com.wasimaster.wmkeyboard.core.tools.TypingStatsMath
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.core.vocab.FieldVisibility
import com.wasimaster.wmkeyboard.core.vocab.VocabAccent
import com.wasimaster.wmkeyboard.core.vocab.VocabAudioSource
import com.wasimaster.wmkeyboard.core.vocab.VocabAutofill
import com.wasimaster.wmkeyboard.core.vocab.VocabCardField
import com.wasimaster.wmkeyboard.core.vocab.VocabCardFields
import com.wasimaster.wmkeyboard.core.vocab.VocabCatalog
import com.wasimaster.wmkeyboard.core.vocab.VocabCatalogEntry
import com.wasimaster.wmkeyboard.core.vocab.VocabChipTap
import com.wasimaster.wmkeyboard.core.vocab.VocabCooldown
import com.wasimaster.wmkeyboard.core.vocab.VocabDownloadManager
import com.wasimaster.wmkeyboard.core.vocab.VocabIndex
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeLevel
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeScope
import com.wasimaster.wmkeyboard.core.vocab.VocabPack
import com.wasimaster.wmkeyboard.core.vocab.VocabPackFile
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import com.wasimaster.wmkeyboard.core.vocab.VocabProgress
import com.wasimaster.wmkeyboard.core.vocab.VocabRelatedTap
import com.wasimaster.wmkeyboard.core.vocab.VocabScheduler
import com.wasimaster.wmkeyboard.core.vocab.VocabSense
import com.wasimaster.wmkeyboard.core.vocab.VocabSpeaker
import com.wasimaster.wmkeyboard.core.vocab.VocabTranslation
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import com.wasimaster.wmkeyboard.common.R as CommonR
import java.io.File
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- routes ----

internal const val VOCAB_PACKS_ROUTE = "vocab/packs"
internal const val VOCAB_LISTS_ROUTE = "vocab/lists"
internal const val VOCAB_REVIEW_ROUTE = "vocab/review"
internal const val VOCAB_BROWSE_ROUTE = "vocab/browse"

internal fun vocabListRoute(packId: String): String = "vocab/list/$packId"

internal fun vocabWordRoute(packId: String, word: String): String = "vocab/word/$packId/${Uri.encode(word)}"

// ---- shared state ----

/** The merged index of every enabled pack, built off the main thread; null while loading. */
@Composable
internal fun rememberVocabIndex(revision: Int): VocabIndex? {
    val context = LocalContext.current
    val state = produceState<VocabIndex?>(initialValue = null, key1 = revision) {
        value = withContext(Dispatchers.IO) {
            val packs = VocabPacks.languages(context.filesDir).flatMap { VocabPacks.load(context.filesDir, it) }
            VocabIndex.build(packs, VocabPacks.stateToken(context.filesDir))
        }
    }
    return state.value
}

/** The learning record, shared with the keyboard through its file. */
@Composable
internal fun rememberVocabProgress(): VocabProgress {
    val context = LocalContext.current
    return remember { VocabProgress(File(context.filesDir, VocabProgress.FILE_PATH)) }
}

@Composable
internal fun rememberVocabSpeaker(): VocabSpeaker {
    val context = LocalContext.current
    val speaker = remember { VocabSpeaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}

internal fun vocabToday(): Int = TypingStatsMath.localEpochDay(System.currentTimeMillis(), TimeZone.getDefault())

private fun isMeteredNetwork(context: Context): Boolean =
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == true

/** What data saving says about one vocabulary fetch right now. */
internal fun vocabDecisionNow(context: Context, settings: KeyboardSettings, feature: MeteredFeature): MeteredDecision {
    val network = DeviceNetworkState(metered = isMeteredNetwork(context))
    val status = DataSaverStatus(active = settings.dataSaver.appliesTo(network), settings = settings.dataSaver)
    return status.decide(feature)
}

/** Plays the word the way the settings say: recording when allowed, else the synthesiser. */
internal fun speakVocabWord(context: Context, settings: KeyboardSettings, speaker: VocabSpeaker, word: VocabWord) {
    val vocab = settings.vocabulary
    val url = word.audioFor(vocab.accent)?.takeIf {
        vocab.audioSource != VocabAudioSource.TTS &&
            vocabDecisionNow(context, settings, MeteredFeature.VOCAB_AUDIO) == MeteredDecision.ALLOWED
    }
    speaker.speak(word.word, url, vocab.ttsRate, vocab.ttsPitch, vocab.accent.locale)
}

internal fun languageNameFor(code: String): String =
    LanguageRegistry.all.firstOrNull { it.id == code }?.englishName ?: code

private fun VocabCardField.titleRes(): Int = when (this) {
    VocabCardField.IPA -> R.string.tooldetail_vocab_field_ipa_title
    VocabCardField.RESPELLING -> R.string.tooldetail_vocab_field_respelling_title
    VocabCardField.EXAMPLES -> R.string.tooldetail_vocab_field_examples_title
    VocabCardField.QUOTATIONS -> R.string.tooldetail_vocab_field_quotations_title
    VocabCardField.SYNONYMS -> R.string.tooldetail_vocab_field_synonyms_title
    VocabCardField.ANTONYMS -> R.string.tooldetail_vocab_field_antonyms_title
    VocabCardField.FAMILY -> R.string.tooldetail_vocab_field_family_title
    VocabCardField.HYPERNYMS -> R.string.tooldetail_vocab_field_hypernyms_title
    VocabCardField.TAGS -> R.string.tooldetail_vocab_field_tags_title
    VocabCardField.TOPICS -> R.string.tooldetail_vocab_field_topics_title
    VocabCardField.ETYMOLOGY -> R.string.tooldetail_vocab_field_etymology_title
    VocabCardField.ORIGIN -> R.string.tooldetail_vocab_field_origin_title
    VocabCardField.ROOT -> R.string.tooldetail_vocab_field_root_title
    VocabCardField.ATTESTED -> R.string.tooldetail_vocab_field_attested_title
    VocabCardField.MNEMONIC -> R.string.tooldetail_vocab_field_mnemonic_title
    VocabCardField.TRANSLATIONS -> R.string.tooldetail_vocab_field_translations_title
    VocabCardField.SOURCES -> R.string.tooldetail_vocab_field_sources_title
    VocabCardField.HYPHENATION -> R.string.tooldetail_vocab_field_hyphenation_title
    VocabCardField.RHYMES -> R.string.tooldetail_vocab_field_rhymes_title
    VocabCardField.FORMS -> R.string.tooldetail_vocab_field_forms_title
    VocabCardField.WIKIPEDIA -> R.string.tooldetail_vocab_field_wikipedia_title
}

// ---- the tool's own page ----

/** The Vocabulary tool's settings page body, called from [ToolDetailSettings]. */
@Composable
internal fun VocabularyToolSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val v = settings.vocabulary
    val d = SettingsDefaults.vocabulary
    val numberFormat = stringResource(R.string.values_number)
    val speedFormat = stringResource(R.string.tooldetail_vocab_speed_value)
    val summary by produceState<Pair<Int, Int>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val packs = VocabPacks.languages(context.filesDir).sumOf { VocabPacks.files(context.filesDir, it, enabledOnly = true).size }
            val progress = VocabProgress(File(context.filesDir, VocabProgress.FILE_PATH))
            packs to progress.stats(vocabToday()).due
        }
    }

    SettingsGroup(stringResource(R.string.tooldetail_vocab_manage_group)) {
        item {
            val packs = summary?.first
            NavRow(
                R.string.tooldetail_vocab_packs_title,
                subtitle = if (packs == null || packs == 0) {
                    stringResource(R.string.tooldetail_vocab_packs_none_subtitle)
                } else {
                    pluralStringResource(R.plurals.tooldetail_vocab_packs_subtitle, packs, packs)
                },
                route = VOCAB_PACKS_ROUTE,
            ) { onNavigate(VOCAB_PACKS_ROUTE) }
        }
        item {
            NavRow(R.string.tooldetail_vocab_lists_title, stringResource(R.string.tooldetail_vocab_lists_subtitle), route = VOCAB_LISTS_ROUTE) {
                onNavigate(VOCAB_LISTS_ROUTE)
            }
        }
        item {
            val due = summary?.second
            NavRow(
                R.string.tooldetail_vocab_review_title,
                stringResource(R.string.tooldetail_vocab_review_subtitle),
                value = due?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.tooldetail_vocab_due_value, it, it) },
                route = VOCAB_REVIEW_ROUTE,
            ) { onNavigate(VOCAB_REVIEW_ROUTE) }
        }
        item {
            NavRow(R.string.tooldetail_vocab_browse_title, stringResource(R.string.tooldetail_vocab_browse_subtitle), route = VOCAB_BROWSE_ROUTE) {
                onNavigate(VOCAB_BROWSE_ROUTE)
            }
        }
    }

    SettingsGroup(stringResource(R.string.tooldetail_vocab_nudges_group), info = stringResource(R.string.tooldetail_vocab_nudges_info)) {
        item {
            ToggleSetting(R.string.tooldetail_vocab_nudges_title, stringResource(R.string.tooldetail_vocab_nudges_subtitle), v.nudges, default = d.nudges) {
                scope.launch { repository.setVocabNudges(it) }
            }
        }
        if (v.nudges) {
            item {
                ToggleSetting(
                    R.string.tooldetail_vocab_nudge_self_title,
                    stringResource(R.string.tooldetail_vocab_nudge_self_subtitle),
                    v.nudgeOnVocabWord,
                    default = d.nudgeOnVocabWord,
                ) { scope.launch { repository.setVocabNudgeOnVocabWord(it) } }
            }
            item {
                ChoiceSetting(
                    R.string.tooldetail_vocab_tap_title,
                    subtitle = stringResource(R.string.tooldetail_vocab_tap_subtitle),
                    options = listOf(
                        VocabChipTap.OPEN to stringResource(R.string.tooldetail_vocab_tap_open_label),
                        VocabChipTap.REPLACE to stringResource(R.string.tooldetail_vocab_tap_replace_label),
                    ),
                    selected = v.chipTapAction,
                    default = d.chipTapAction,
                ) { scope.launch { repository.setVocabChipTapAction(it) } }
            }
            item {
                ChoiceSetting(
                    R.string.tooldetail_vocab_scope_title,
                    options = listOf(
                        VocabNudgeScope.UNLEARNT to stringResource(R.string.tooldetail_vocab_scope_unlearnt_label),
                        VocabNudgeScope.ALL to stringResource(R.string.tooldetail_vocab_scope_all_label),
                        VocabNudgeScope.LEARNT_ONLY to stringResource(R.string.tooldetail_vocab_scope_learnt_label),
                    ),
                    selected = v.nudgeScope,
                    default = d.nudgeScope,
                ) { scope.launch { repository.setVocabNudgeScope(it) } }
            }
            item {
                ChoiceSetting(
                    R.string.tooldetail_vocab_level_title,
                    subtitle = stringResource(R.string.tooldetail_vocab_level_subtitle),
                    options = listOf(
                        VocabNudgeLevel.LOW to stringResource(R.string.tooldetail_vocab_level_low_label),
                        VocabNudgeLevel.MEDIUM to stringResource(R.string.tooldetail_vocab_level_medium_label),
                        VocabNudgeLevel.HIGH to stringResource(R.string.tooldetail_vocab_level_high_label),
                    ),
                    selected = v.nudgeLevel,
                    default = d.nudgeLevel,
                ) { scope.launch { repository.setVocabNudgeLevel(it) } }
            }
            item {
                ChoiceSetting(
                    R.string.tooldetail_vocab_cooldown_title,
                    options = listOf(
                        VocabCooldown.EVERY_TIME to stringResource(R.string.tooldetail_vocab_cooldown_every_label),
                        VocabCooldown.ONCE_PER_FIELD to stringResource(R.string.tooldetail_vocab_cooldown_field_label),
                        VocabCooldown.ONCE_PER_DAY to stringResource(R.string.tooldetail_vocab_cooldown_day_label),
                    ),
                    selected = v.cooldown,
                    default = d.cooldown,
                ) { scope.launch { repository.setVocabCooldown(it) } }
            }
        }
    }

    val resolved = remember(v.cardFields) { VocabCardFields.resolve(v.cardFields) }
    SettingsGroup(
        stringResource(R.string.tooldetail_vocab_card_group),
        foldKey = "vocab_card",
        info = stringResource(R.string.tooldetail_vocab_card_info),
        foldSummary = {
            // joinToString's lambda is not composable, so resolve the names first.
            val names = VocabCardField.entries
                .filter { resolved[it] == FieldVisibility.KEYBOARD }
                .map { stringResource(it.titleRes()) }
            names.joinToString(", ")
        },
        action = {
            if (v.cardFields.isNotEmpty()) {
                TextButton(onClick = { scope.launch { repository.setVocabCardFields("") } }) {
                    Text(stringResource(CommonR.string.common_reset))
                }
            }
        },
    ) {
        for (field in VocabCardField.entries) {
            item {
                ChoiceSetting(
                    field.titleRes(),
                    options = listOf(
                        FieldVisibility.OFF to stringResource(R.string.tooldetail_vocab_visibility_off_label),
                        FieldVisibility.SETTINGS to stringResource(R.string.tooldetail_vocab_visibility_settings_label),
                        FieldVisibility.KEYBOARD to stringResource(R.string.tooldetail_vocab_visibility_keyboard_label),
                    ),
                    selected = resolved[field] ?: field.defaultVisibility,
                    default = field.defaultVisibility,
                ) { scope.launch { repository.setVocabCardFields(VocabCardFields.with(v.cardFields, field, it)) } }
            }
        }
    }

    SettingsGroup(stringResource(R.string.tooldetail_vocab_related_group)) {
        item {
            ChoiceSetting(
                R.string.tooldetail_vocab_related_title,
                subtitle = stringResource(R.string.tooldetail_vocab_related_subtitle),
                options = listOf(
                    VocabRelatedTap.OPEN_CARD_ELSE_INSERT to stringResource(R.string.tooldetail_vocab_related_open_label),
                    VocabRelatedTap.INSERT to stringResource(R.string.tooldetail_vocab_related_insert_label),
                    VocabRelatedTap.DICTIONARY_LOOKUP to stringResource(R.string.tooldetail_vocab_related_lookup_label),
                ),
                selected = v.relatedTap,
                default = d.relatedTap,
            ) { scope.launch { repository.setVocabRelatedTap(it) } }
        }
        item {
            var picking by remember { mutableStateOf(false) }
            val chosen = v.translationLangList
            NavRow(
                R.string.tooldetail_vocab_translations_title,
                subtitle = if (chosen.isEmpty()) {
                    stringResource(R.string.tooldetail_vocab_translations_auto_subtitle)
                } else {
                    chosen.joinToString(", ") { languageNameFor(it) }
                },
            ) { picking = true }
            if (picking) {
                TranslationLanguagesDialog(
                    settings = settings,
                    onDismiss = { picking = false },
                    onChange = { codes -> scope.launch { repository.setVocabTranslationLangs(codes.joinToString(",")) } },
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.tooldetail_vocab_audio_group), info = stringResource(R.string.tooldetail_vocab_audio_info)) {
        item {
            ChoiceSetting(
                R.string.tooldetail_vocab_audio_source_title,
                options = listOf(
                    VocabAudioSource.AUTO to stringResource(R.string.tooldetail_vocab_audio_auto_label),
                    VocabAudioSource.WIKTIONARY to stringResource(R.string.tooldetail_vocab_audio_wiktionary_label),
                    VocabAudioSource.TTS to stringResource(R.string.tooldetail_vocab_audio_tts_label),
                ),
                selected = v.audioSource,
                default = d.audioSource,
            ) { scope.launch { repository.setVocabAudioSource(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_vocab_accent_title,
                subtitle = stringResource(R.string.tooldetail_vocab_accent_subtitle),
                options = listOf(
                    VocabAccent.US to stringResource(R.string.tooldetail_vocab_accent_us_label),
                    VocabAccent.UK to stringResource(R.string.tooldetail_vocab_accent_uk_label),
                ),
                selected = v.accent,
                default = d.accent,
            ) { scope.launch { repository.setVocabAccent(it) } }
        }
        if (v.audioSource != VocabAudioSource.WIKTIONARY) {
            item {
                SliderSetting(
                    R.string.tooldetail_vocab_tts_rate_title,
                    value = v.ttsRate,
                    range = VocabularySettings.MIN_TTS..VocabularySettings.MAX_TTS,
                    display = { speedFormat.format(it) },
                    default = d.ttsRate,
                ) { scope.launch { repository.setVocabTtsRate(it) } }
            }
            item {
                SliderSetting(
                    R.string.tooldetail_vocab_tts_pitch_title,
                    value = v.ttsPitch,
                    range = VocabularySettings.MIN_TTS..VocabularySettings.MAX_TTS,
                    display = { speedFormat.format(it) },
                    default = d.ttsPitch,
                ) { scope.launch { repository.setVocabTtsPitch(it) } }
            }
        }
        item {
            val speaker = rememberVocabSpeaker()
            WmRow(
                title = stringResource(R.string.tooldetail_vocab_audio_test_title),
                icon = SettingsRowIcons[R.string.tooldetail_vocab_audio_test_title],
                onClick = { speakVocabWord(context, settings, speaker, VocabWord("serendipity")) },
            )
        }
    }

    SettingsGroup(stringResource(R.string.tooldetail_vocab_flashcards_group), info = stringResource(R.string.tooldetail_vocab_flashcards_info)) {
        item {
            ChoiceSetting(
                R.string.tooldetail_vocab_scheduler_title,
                options = listOf(
                    VocabScheduler.LEITNER to stringResource(R.string.tooldetail_vocab_scheduler_leitner_label),
                    VocabScheduler.SM2 to stringResource(R.string.tooldetail_vocab_scheduler_sm2_label),
                ),
                selected = v.scheduler,
                default = d.scheduler,
                detail = { option ->
                    ChoiceDetail(
                        description = stringResource(
                            if (option == VocabScheduler.LEITNER) {
                                R.string.tooldetail_vocab_scheduler_leitner_desc
                            } else {
                                R.string.tooldetail_vocab_scheduler_sm2_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setVocabScheduler(it) } }
        }
        item {
            SliderSetting(
                R.string.tooldetail_vocab_goal_title,
                subtitle = stringResource(R.string.tooldetail_vocab_goal_subtitle),
                value = v.dailyGoal.toFloat(),
                range = VocabularySettings.MIN_DAILY_GOAL.toFloat()..VocabularySettings.MAX_DAILY_GOAL.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                default = d.dailyGoal.toFloat(),
            ) { scope.launch { repository.setVocabDailyGoal(it.roundToInt()) } }
        }
    }

    SettingsGroup(stringResource(R.string.tooldetail_vocab_wotd_group)) {
        item {
            ToggleSetting(R.string.tooldetail_vocab_wotd_card_title, stringResource(R.string.tooldetail_vocab_wotd_card_subtitle), v.wordOfTheDayCard, default = d.wordOfTheDayCard) {
                scope.launch { repository.setVocabWordOfTheDayCard(it) }
            }
        }
        item {
            ToggleSetting(R.string.tooldetail_vocab_wotd_chip_title, stringResource(R.string.tooldetail_vocab_wotd_chip_subtitle), v.wordOfTheDayChip, default = d.wordOfTheDayChip) {
                scope.launch { repository.setVocabWordOfTheDayChip(it) }
            }
        }
    }
}

@Composable
private fun TranslationLanguagesDialog(
    settings: KeyboardSettings,
    onDismiss: () -> Unit,
    onChange: (List<String>) -> Unit,
) {
    val offered = remember {
        (VocabCatalog.entries.flatMap { it.translationCodes } + settings.enabledLanguages.map { it.id })
            .filter { it != "en" }
            .distinct()
            .sortedBy { languageNameFor(it) }
    }
    var chosen by remember { mutableStateOf(settings.vocabulary.translationLangList) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tooldetail_vocab_translations_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CaptionText(stringResource(R.string.tooldetail_vocab_translations_dialog_body))
                for (code in offered) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = code in chosen,
                            onCheckedChange = { on -> chosen = if (on) chosen + code else chosen - code },
                        )
                        Text("${languageNameFor(code)} · $code")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChange(chosen); onDismiss() }) { Text(stringResource(CommonR.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

// ---- packs ----

private data class InstalledVocabPack(val file: File, val pack: VocabPack, val learnt: Int, val seen: Int)

/** The packs screen: the catalogue's downloads, what is installed, imports and translation sidecars. */
@Composable
internal fun VocabPacksScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<List<InstalledVocabPack>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<InstalledVocabPack?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var confirmMetered by remember { mutableStateOf<(() -> Unit)?>(null) }
    val states by VocabDownloadManager.states.collectAsState()
    val translationStates by VocabDownloadManager.translationStates.collectAsState()
    val downloadDecision = rememberDownloadDecision(settings)

    LaunchedEffect(revision) {
        VocabDownloadManager.refresh(context.filesDir)
        installed = withContext(Dispatchers.IO) {
            val progress = VocabProgress(File(context.filesDir, VocabProgress.FILE_PATH))
            VocabPacks.languages(context.filesDir).flatMap { langId ->
                VocabPacks.files(context.filesDir, langId).mapNotNull { file ->
                    val pack = VocabPacks.loadFile(file) ?: return@mapNotNull null
                    if (pack.meta.userCreated) return@mapNotNull null
                    val lemmas = pack.words.map { it.word }
                    val stats = progress.stats(vocabToday(), lemmas)
                    InstalledVocabPack(file, pack, stats.learnt, stats.seen)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        VocabDownloadManager.completions.collect { revision++ }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "vocabulary"
                    context.contentResolver.openInputStream(uri)!!.use { VocabPacks.import(context.filesDir, "en", name, it) }
                }.getOrNull()
            }
            message = when (result) {
                is VocabPacks.ImportResult.Imported -> context.resources.getQuantityString(R.plurals.vocab_import_done, result.wordCount, result.wordCount)
                VocabPacks.ImportResult.TooLarge -> context.getString(R.string.vocab_import_too_large_error)
                VocabPacks.ImportResult.Empty -> context.getString(R.string.vocab_import_empty_error)
                VocabPacks.ImportResult.NotAPack, null -> context.getString(R.string.vocab_import_error)
            }
            if (result is VocabPacks.ImportResult.Imported) revision++
        }
    }

    fun gated(action: () -> Unit) {
        when (downloadDecision()) {
            MeteredDecision.ALLOWED -> action()
            MeteredDecision.ASK -> confirmMetered = action
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    AddonStoreGroup(AddonType.Vocabulary, onNavigate)

    val installedIds = installed.map { VocabPacks.packIdOf(it.file) }.toSet()
    val available = VocabCatalog.entries.filter { it.id !in installedIds }
    if (available.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.vocab_packs_available_title), info = stringResource(R.string.vocab_packs_available_info)) {
            for (entry in available) {
                item {
                    VocabCatalogRow(
                        entry = entry,
                        status = states[entry.id],
                        onDownload = { gated { VocabDownloadManager.start(context.filesDir, entry) } },
                        onCancel = { VocabDownloadManager.cancel(entry.id) },
                    )
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.vocab_packs_installed_title), highlightKey = R.string.vocab_packs_installed_title) {
        if (installed.isEmpty()) {
            item { CaptionText(stringResource(R.string.vocab_packs_installed_empty)) }
        }
        for (item in installed) {
            item {
                val packId = VocabPacks.packIdOf(item.file)
                val catalog = VocabCatalog.byId(packId)
                HighlightableItem(packId) {
                    Column {
                        WmRow(
                            title = item.pack.name,
                            subtitle = stringResource(R.string.vocab_pack_progress_subtitle, item.pack.words.size, item.seen, item.learnt),
                            icon = Icons.Outlined.School,
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { confirmDelete = item }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(CommonR.string.common_delete))
                                    }
                                    Switch(
                                        checked = VocabPacks.isEnabled(item.file),
                                        onCheckedChange = { on ->
                                            scope.launch {
                                                withContext(Dispatchers.IO) { VocabPacks.setEnabled(item.file, on) }
                                                revision++
                                            }
                                        },
                                    )
                                }
                            },
                            onClick = { onNavigate("$VOCAB_BROWSE_ROUTE?pack=$packId") },
                        )
                        if (catalog != null && catalog.translationCodes.isNotEmpty()) {
                            TranslationChips(
                                entry = catalog,
                                states = translationStates,
                                onFetch = { code -> gated { VocabDownloadManager.startTranslations(context.filesDir, catalog, listOf(code)) } },
                                onRemove = { code -> VocabDownloadManager.deleteTranslation(context.filesDir, catalog, code) },
                            )
                        }
                    }
                }
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.vocab_packs_import_title),
                subtitle = stringResource(R.string.vocab_packs_import_subtitle),
                icon = Icons.Outlined.FileUpload,
                highlightKey = R.string.vocab_packs_import_title,
                onClick = { importLauncher.launch(arrayOf("*/*")) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.vocab_pack_delete_title, item.pack.name)) },
            text = { Text(stringResource(R.string.vocab_pack_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { VocabPacks.remove(item.file) }
                        VocabDownloadManager.refresh(context.filesDir)
                        revision++
                    }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
    confirmMetered?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmMetered = null },
            title = { Text(stringResource(R.string.languages_metered_confirm_title)) },
            text = { Text(stringResource(R.string.vocab_metered_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmMetered = null; action() }) { Text(stringResource(CommonR.string.common_download)) }
            },
            dismissButton = { TextButton(onClick = { confirmMetered = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
    if (blocked) MeteredBlockedDialog { blocked = false }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) } },
        )
    }
    @Suppress("UNUSED_VARIABLE")
    val unused = repository
}

@Composable
private fun VocabCatalogRow(
    entry: VocabCatalogEntry,
    status: VocabDownloadManager.DownloadStatus?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val sizeText = formatVocabBytes(entry.approxGzBytes)
    val subtitle = when (status) {
        is VocabDownloadManager.DownloadStatus.Downloading -> stringResource(R.string.vocab_pack_downloading_subtitle, formatVocabBytes(status.bytes), sizeText)
        VocabDownloadManager.DownloadStatus.Queued -> stringResource(R.string.vocab_pack_queued_subtitle)
        is VocabDownloadManager.DownloadStatus.Failed ->
            if (status.messageArg.isEmpty()) stringResource(status.messageRes) else stringResource(status.messageRes, status.messageArg)
        else -> pluralStringResource(R.plurals.vocab_pack_size_subtitle, entry.wordCount, entry.wordCount, sizeText)
    }
    val busy = status is VocabDownloadManager.DownloadStatus.Downloading || status == VocabDownloadManager.DownloadStatus.Queued
    WmRow(
        title = entry.name,
        subtitle = subtitle,
        icon = Icons.Outlined.School,
        trailing = {
            IconButton(onClick = { if (busy) onCancel() else onDownload() }) {
                Icon(
                    when {
                        busy -> Icons.Outlined.Close
                        status is VocabDownloadManager.DownloadStatus.Failed -> Icons.Outlined.Refresh
                        else -> Icons.Outlined.Download
                    },
                    contentDescription = stringResource(if (busy) CommonR.string.common_cancel else R.string.vocab_pack_download_action),
                )
            }
        },
        onClick = { if (!busy) onDownload() },
    )
    val downloading = status as? VocabDownloadManager.DownloadStatus.Downloading
    if (downloading != null) {
        LinearProgressIndicator(
            progress = { if (downloading.totalBytes > 0) (downloading.bytes.toFloat() / downloading.totalBytes).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TranslationChips(
    entry: VocabCatalogEntry,
    states: Map<String, VocabDownloadManager.DownloadStatus>,
    onFetch: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        CaptionText(stringResource(R.string.vocab_pack_translations_caption))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (code in entry.translationCodes) {
                val status = states[VocabDownloadManager.translationKey(entry.id, code)]
                val have = status is VocabDownloadManager.DownloadStatus.Downloaded
                val busy = status is VocabDownloadManager.DownloadStatus.Downloading || status == VocabDownloadManager.DownloadStatus.Queued
                FilterChip(
                    selected = have,
                    enabled = !busy,
                    onClick = { if (have) onRemove(code) else onFetch(code) },
                    label = { Text(languageNameFor(code)) },
                    leadingIcon = if (have) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

internal fun formatVocabBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

// ---- user lists ----

private data class UserVocabList(val file: File, val pack: VocabPack, val learnt: Int)

/** The user's own lists: make one, switch one off, open one to edit. */
@Composable
internal fun VocabListsScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var lists by remember { mutableStateOf<List<UserVocabList>>(emptyList()) }
    var newName by remember { mutableStateOf<String?>(null) }
    val langId = remember(settings) { settings.enabledLanguages.firstOrNull { it.id == "en" }?.id ?: "en" }

    LaunchedEffect(revision) {
        lists = withContext(Dispatchers.IO) {
            val progress = VocabProgress(File(context.filesDir, VocabProgress.FILE_PATH))
            VocabPacks.languages(context.filesDir).flatMap { lang ->
                VocabPacks.files(context.filesDir, lang).mapNotNull { file ->
                    val pack = VocabPacks.loadFile(file) ?: return@mapNotNull null
                    if (!pack.meta.userCreated) return@mapNotNull null
                    UserVocabList(file, pack, pack.words.count { progress.isLearnt(it.word) })
                }
            }
        }
    }

    RegisterAddFab(stringResource(R.string.vocab_lists_add_action)) { newName = "" }

    SettingsGroup(stringResource(R.string.vocab_lists_title), info = stringResource(R.string.vocab_lists_info)) {
        if (lists.isEmpty()) {
            item { CaptionText(stringResource(R.string.vocab_lists_empty_body)) }
        }
        for (list in lists) {
            item {
                WmRow(
                    title = list.pack.name,
                    subtitle = stringResource(R.string.vocab_list_subtitle, list.pack.words.size, list.learnt),
                    icon = Icons.Outlined.PlaylistAdd,
                    trailing = {
                        Switch(
                            checked = VocabPacks.isEnabled(list.file),
                            onCheckedChange = { on ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { VocabPacks.setEnabled(list.file, on) }
                                    revision++
                                }
                            },
                        )
                    },
                    onClick = { onNavigate(vocabListRoute(VocabPacks.packIdOf(list.file))) },
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    newName?.let { draft ->
        AlertDialog(
            onDismissRequest = { newName = null },
            title = { Text(stringResource(R.string.vocab_lists_add_action)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.vocab_list_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val meta = VocabPacks.newUserPack(draft, langId)
                        newName = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VocabPacks.write(context.filesDir, VocabPack(meta, emptyList()), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
                            }
                            onNavigate(vocabListRoute(meta.id))
                        }
                    },
                ) { Text(stringResource(CommonR.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { newName = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
    @Suppress("UNUSED_VARIABLE")
    val unused = repository
}

// ---- list editor ----

/** One user list: rename, export, delete; its words; the add-word sheet and bulk add. */
@Composable
internal fun VocabListEditorScreen(
    packId: String,
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var pack by remember { mutableStateOf<VocabPack?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<VocabWord?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf<String?>(null) }
    val index = rememberVocabIndex(revision)
    val progress = rememberVocabProgress()

    LaunchedEffect(revision) {
        pack = withContext(Dispatchers.IO) {
            VocabPacks.languages(context.filesDir).firstNotNullOfOrNull { lang ->
                VocabPacks.files(context.filesDir, lang).firstOrNull { VocabPacks.packIdOf(it) == packId }?.let { VocabPacks.loadFile(it) }
            }
        }
    }

    fun save(updated: VocabPack) {
        scope.launch {
            withContext(Dispatchers.IO) { VocabPacks.write(context.filesDir, updated, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME) }
            revision++
        }
    }

    var pendingExport by remember { mutableStateOf<VocabPack?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VocabPackFile.MIME_TYPE)) { uri ->
        val exporting = pendingExport
        pendingExport = null
        if (uri == null || exporting == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        out.write(VocabPackFile.encode(exporting, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME).toByteArray())
                    }
                }.isSuccess
            }
            message = context.getString(if (ok) R.string.vocab_list_exported else R.string.vocab_list_export_error)
        }
    }

    val current = pack
    if (current == null) {
        CaptionText(stringResource(R.string.vocab_list_missing_body), Modifier.padding(16.dp))
        return
    }

    RegisterAddFab(stringResource(R.string.vocab_list_add_word_action)) { adding = VocabWord(""); showSheet = true }

    SettingsGroup(current.name) {
        item {
            WmRow(title = stringResource(R.string.vocab_list_rename_title), icon = Icons.Outlined.Edit, onClick = { renaming = current.name })
        }
        item {
            WmRow(
                title = stringResource(R.string.vocab_list_export_title),
                subtitle = stringResource(R.string.vocab_list_export_subtitle),
                icon = Icons.Outlined.Share,
                onClick = {
                    pendingExport = current
                    exportLauncher.launch(VocabPackFile.fileName(current.meta))
                },
            )
        }
        item {
            WmRow(title = stringResource(R.string.vocab_list_bulk_title), subtitle = stringResource(R.string.vocab_list_bulk_subtitle), icon = Icons.Outlined.PlaylistAdd, onClick = { bulk = "" })
        }
        item {
            WmRow(title = stringResource(R.string.vocab_list_delete_title), icon = Icons.Outlined.Delete, onClick = { confirmDelete = true })
        }
    }

    SettingsGroup(pluralStringResource(R.plurals.vocab_list_words_title, current.words.size, current.words.size)) {
        if (current.words.isEmpty()) {
            item { CaptionText(stringResource(R.string.vocab_list_words_empty)) }
        }
        for (word in current.words) {
            item {
                val learnt = progress.isLearnt(word.word)
                WmRow(
                    title = word.word,
                    subtitle = word.definition.ifBlank { stringResource(R.string.vocab_word_needs_details_label) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (learnt) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            IconButton(onClick = { adding = word; showSheet = true }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(CommonR.string.common_edit)) }
                            IconButton(onClick = { save(current.copy(words = current.words.filter { it.word != word.word })) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(CommonR.string.common_delete))
                            }
                        }
                    },
                    onClick = { onNavigate(vocabWordRoute(packId, word.word)) },
                )
            }
        }
    }
    Spacer(Modifier.height(80.dp))

    if (showSheet && adding != null) {
        AddVocabWordSheet(
            initial = adding!!,
            index = index,
            settings = settings,
            onDismiss = { showSheet = false },
            onSave = { word ->
                showSheet = false
                val others = current.words.filter { it.word != word.word }
                save(current.copy(words = others + word))
            },
        )
    }

    renaming?.let { draft ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.vocab_list_rename_title)) },
            text = { OutlinedTextField(value = draft, onValueChange = { renaming = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    renaming = null
                    save(current.copy(meta = current.meta.copy(name = draft.trim())))
                }) { Text(stringResource(CommonR.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.vocab_list_delete_title)) },
            text = { Text(stringResource(R.string.vocab_list_delete_body, current.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { current.file?.let { VocabPacks.remove(it) } }
                        onNavigate(VOCAB_LISTS_ROUTE)
                    }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
    bulk?.let { text ->
        BulkAddDialog(
            text = text,
            onText = { bulk = it },
            index = index,
            settings = settings,
            onDismiss = { bulk = null },
            onAdd = { words ->
                bulk = null
                val existing = current.words.map { it.word }.toSet()
                val fresh = words.filter { it.word !in existing }
                message = context.getString(R.string.vocab_bulk_done, fresh.size, fresh.count { it.senses.isNotEmpty() })
                if (fresh.isNotEmpty()) save(current.copy(words = current.words + fresh))
            },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) } },
        )
    }
    @Suppress("UNUSED_VARIABLE")
    val unused = repository
}

/**
 * The add/edit sheet: type a word, take what an installed pack or the online
 * dictionary knows about it, or type the details yourself.
 */
@Composable
private fun AddVocabWordSheet(
    initial: VocabWord,
    index: VocabIndex?,
    settings: KeyboardSettings,
    onDismiss: () -> Unit,
    onSave: (VocabWord) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var word by remember { mutableStateOf(initial.word) }
    var pos by remember { mutableStateOf(initial.pos.firstOrNull().orEmpty()) }
    var definition by remember { mutableStateOf(initial.definition) }
    var example by remember { mutableStateOf(initial.senses.firstOrNull()?.example.orEmpty()) }
    var synonyms by remember { mutableStateOf(initial.synonyms.joinToString(", ")) }
    var antonyms by remember { mutableStateOf(initial.antonyms.joinToString(", ")) }
    var mnemonic by remember { mutableStateOf(initial.mnemonic.orEmpty()) }
    var triggers by remember { mutableStateOf(initial.triggers.joinToString(", ") { it.w }) }
    var translation by remember { mutableStateOf(initial.translations.values.firstOrNull()?.w?.joinToString(", ").orEmpty()) }
    var found by remember { mutableStateOf<VocabWord?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var lookingUp by remember { mutableStateOf(false) }
    val translationCode = settings.vocabulary.translationLangList.firstOrNull()
        ?: settings.enabledLanguages.map { it.id }.firstOrNull { it != "en" } ?: "bn"

    fun fill(record: VocabWord) {
        pos = record.pos.firstOrNull().orEmpty()
        definition = record.definition
        example = record.senses.firstOrNull()?.example.orEmpty()
        synonyms = record.synonyms.joinToString(", ")
        antonyms = record.antonyms.joinToString(", ")
        mnemonic = record.mnemonic.orEmpty()
        triggers = record.triggers.joinToString(", ") { it.w }
        translation = record.translations[translationCode]?.w?.joinToString(", ").orEmpty()
        found = record
    }

    LaunchedEffect(word, index) {
        found = null
        val lemma = VocabPackFile.normalizeLemma(word) ?: return@LaunchedEffect
        if (lemma == initial.word && initial.senses.isNotEmpty()) return@LaunchedEffect
        delay(300)
        val hit = index?.lookupAnyForm(lemma) ?: return@LaunchedEffect
        found = hit
    }

    fun lookupOnline() {
        val lemma = VocabPackFile.normalizeLemma(word) ?: return
        val decision = vocabDecisionNow(context, settings, MeteredFeature.DICTIONARY_LOOKUP)
        if (decision == MeteredDecision.BLOCKED) {
            status = context.getString(R.string.vocab_add_lookup_blocked_body)
            return
        }
        lookingUp = true
        scope.launch {
            val result = VocabAutofill.resolve(VocabIndex.EMPTY, lemma, allowOnline = true)
            lookingUp = false
            when (result) {
                is VocabAutofill.Result.Found -> {
                    fill(result.word)
                    status = context.getString(R.string.vocab_add_found_online)
                }
                VocabAutofill.Result.NotFound -> status = context.getString(R.string.vocab_add_not_found_online)
                else -> status = context.getString(R.string.vocab_add_lookup_error)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (initial.word.isEmpty()) R.string.vocab_list_add_word_action else CommonR.string.common_edit),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = word,
                onValueChange = { word = it },
                label = { Text(stringResource(R.string.vocab_add_word_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val hit = found
                if (hit != null && hit.senses.isNotEmpty() && hit.definition != definition) {
                    AssistChip(
                        onClick = { fill(hit) },
                        label = { Text(stringResource(R.string.vocab_add_found_in_pack, index?.packOf(hit.word)?.name ?: "")) },
                        leadingIcon = { Icon(Icons.Outlined.School, contentDescription = null) },
                    )
                }
                AssistChip(
                    onClick = { lookupOnline() },
                    enabled = !lookingUp && VocabPackFile.normalizeLemma(word) != null,
                    label = { Text(stringResource(R.string.vocab_add_lookup_online_action)) },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                )
            }
            status?.let { CaptionText(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in listOf("noun", "verb", "adjective", "adverb")) {
                    FilterChip(selected = pos == option, onClick = { pos = if (pos == option) "" else option }, label = { Text(option) })
                }
            }
            OutlinedTextField(value = definition, onValueChange = { definition = it }, label = { Text(stringResource(R.string.vocab_add_definition_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = example, onValueChange = { example = it }, label = { Text(stringResource(R.string.vocab_add_example_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = synonyms, onValueChange = { synonyms = it }, label = { Text(stringResource(R.string.vocab_add_synonyms_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = antonyms, onValueChange = { antonyms = it }, label = { Text(stringResource(R.string.vocab_add_antonyms_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = triggers, onValueChange = { triggers = it }, label = { Text(stringResource(R.string.vocab_add_triggers_label)) }, supportingText = { Text(stringResource(R.string.vocab_add_triggers_hint)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = mnemonic, onValueChange = { mnemonic = it }, label = { Text(stringResource(R.string.vocab_add_mnemonic_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = translation,
                onValueChange = { translation = it },
                label = { Text(stringResource(R.string.vocab_add_translation_label, languageNameFor(translationCode))) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
                TextButton(
                    enabled = VocabPackFile.normalizeLemma(word) != null,
                    onClick = {
                        val lemma = VocabPackFile.normalizeLemma(word) ?: return@TextButton
                        fun list(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val base = found?.takeIf { it.word == lemma } ?: initial.takeIf { it.word == lemma } ?: VocabWord(lemma)
                        val senses = if (definition.isBlank()) {
                            base.senses
                        } else {
                            listOf(VocabSense(pos = pos, definition = definition.trim(), example = example.trim().takeIf { it.isNotEmpty() })) +
                                base.senses.drop(1)
                        }
                        onSave(
                            base.copy(
                                word = lemma,
                                pos = if (pos.isNotEmpty()) listOf(pos) else base.pos,
                                senses = senses,
                                synonyms = list(synonyms),
                                antonyms = list(antonyms),
                                mnemonic = mnemonic.trim().takeIf { it.isNotEmpty() },
                                triggers = list(triggers).map { com.wasimaster.wmkeyboard.core.vocab.VocabTrigger(it.lowercase()) },
                                translations = if (translation.isBlank()) {
                                    base.translations - translationCode
                                } else {
                                    base.translations + (translationCode to VocabTranslation(list(translation)))
                                },
                            ),
                        )
                    },
                ) { Text(stringResource(CommonR.string.common_save)) }
            }
        }
    }
}

/** Paste many words; each takes its details from an installed pack, or (optionally) from the online dictionary. */
@Composable
private fun BulkAddDialog(
    text: String,
    onText: (String) -> Unit,
    index: VocabIndex?,
    settings: KeyboardSettings,
    onDismiss: () -> Unit,
    onAdd: (List<VocabWord>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var online by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val words = remember(text) { VocabAutofill.parseWordList(text) }
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text(stringResource(R.string.vocab_list_bulk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    label = { Text(pluralStringResource(R.plurals.vocab_bulk_count_label, words.size, words.size)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = online, onCheckedChange = { online = it })
                    Text(stringResource(R.string.vocab_bulk_online_label), style = MaterialTheme.typography.bodyMedium)
                }
                progress?.let { (done, total) ->
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = words.isNotEmpty() && progress == null,
                onClick = {
                    val allowOnline = online &&
                        vocabDecisionNow(context, settings, MeteredFeature.DICTIONARY_LOOKUP) == MeteredDecision.ALLOWED
                    progress = 0 to words.size
                    scope.launch {
                        val out = ArrayList<VocabWord>()
                        var onlineLeft = MAX_BULK_ONLINE
                        for ((i, lemma) in words.withIndex()) {
                            val fromIndex = index?.lookupAnyForm(lemma)
                            val record = when {
                                fromIndex != null -> fromIndex
                                allowOnline && onlineLeft > 0 -> {
                                    onlineLeft--
                                    val result = VocabAutofill.resolve(VocabIndex.EMPTY, lemma, allowOnline = true)
                                    delay(300)
                                    (result as? VocabAutofill.Result.Found)?.word ?: VocabWord(lemma)
                                }
                                else -> VocabWord(lemma)
                            }
                            out += record
                            progress = (i + 1) to words.size
                        }
                        progress = null
                        onAdd(out)
                    }
                },
            ) { Text(stringResource(R.string.vocab_bulk_add_action)) }
        },
        dismissButton = { TextButton(enabled = progress == null, onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

private const val MAX_BULK_ONLINE = 100

// ---- browse ----

/** Every word of the enabled packs, searchable and filterable, paged for the scrolling screen. */
@Composable
internal fun VocabBrowseScreen(
    settings: KeyboardSettings,
    packArg: String?,
    onNavigate: (String) -> Unit,
) {
    val index = rememberVocabIndex(0)
    val progress = rememberVocabProgress()
    var query by remember { mutableStateOf("") }
    var packId by remember { mutableStateOf(packArg) }
    var filter by remember { mutableStateOf(0) } // 0 all, 1 new, 2 learning, 3 learnt
    var shown by remember { mutableIntStateOf(PAGE) }
    val rows = remember(index, query, packId, filter) {
        val pool = if (index == null) emptyList() else if (packId != null) index.byPack(packId!!) else index.allWords.sortedBy { it.word }
        val q = query.trim().lowercase()
        pool.filter { word ->
            (q.isEmpty() || word.word.contains(q) || word.definition.contains(q, ignoreCase = true)) &&
                when (filter) {
                    1 -> !progress.isSeen(word.word)
                    2 -> progress.isSeen(word.word) && !progress.isLearnt(word.word)
                    3 -> progress.isLearnt(word.word)
                    else -> true
                }
        }
    }
    RegisterPinned {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; shown = PAGE },
                placeholder = { Text(stringResource(R.string.vocab_browse_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = packId == null, onClick = { packId = null }, label = { Text(stringResource(R.string.vocab_browse_all_packs)) })
                for (pack in index?.packs.orEmpty()) {
                    FilterChip(selected = packId == pack.id, onClick = { packId = pack.id }, label = { Text(pack.name) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val labels = listOf(
                    R.string.vocab_browse_filter_all, R.string.vocab_browse_filter_new,
                    R.string.vocab_browse_filter_learning, R.string.vocab_browse_filter_learnt,
                )
                labels.forEachIndexed { i, res ->
                    FilterChip(selected = filter == i, onClick = { filter = i; shown = PAGE }, label = { Text(stringResource(res)) })
                }
            }
        }
    }
    if (index == null) {
        CaptionText(stringResource(CommonR.string.common_loading), Modifier.padding(16.dp))
        return
    }
    if (index.isEmpty) {
        SettingsGroup {
            item { CaptionText(stringResource(R.string.vocab_browse_no_packs_body)) }
            item { NavRow(R.string.tooldetail_vocab_packs_title, route = VOCAB_PACKS_ROUTE) { onNavigate(VOCAB_PACKS_ROUTE) } }
        }
        return
    }
    SettingsGroup(pluralStringResource(R.plurals.vocab_browse_count_title, rows.size, rows.size)) {
        if (rows.isEmpty()) item { CaptionText(stringResource(R.string.vocab_browse_no_results)) }
        for (word in rows.take(shown)) {
            item {
                val learnt = progress.isLearnt(word.word)
                WmRow(
                    title = word.word,
                    subtitle = listOfNotNull(word.pos.firstOrNull(), word.definition.takeIf { it.isNotBlank() }).joinToString(" · "),
                    trailing = if (learnt) {
                        { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    onClick = { onNavigate(vocabWordRoute(index.packOf(word.word)?.id ?: "all", word.word)) },
                )
            }
        }
        if (rows.size > shown) {
            item {
                WmRow(
                    title = pluralStringResource(R.plurals.vocab_browse_show_more, rows.size - shown, rows.size - shown),
                    onClick = { shown += PAGE },
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    @Suppress("UNUSED_VARIABLE")
    val unused = settings
}

private const val PAGE = 60

// ---- one word ----

/** A word's full card, its progress, and the actions on it. */
@Composable
internal fun VocabWordScreen(
    packId: String,
    lemma: String,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val index = rememberVocabIndex(revision)
    val progress = rememberVocabProgress()
    val speaker = rememberVocabSpeaker()
    var tick by remember { mutableIntStateOf(0) }
    var lists by remember { mutableStateOf<List<VocabPack>?>(null) }
    val word = remember(index, lemma) { index?.lookupAnyForm(lemma) }
    if (index == null) {
        CaptionText(stringResource(CommonR.string.common_loading), Modifier.padding(16.dp))
        return
    }
    if (word == null) {
        CaptionText(stringResource(R.string.vocab_word_missing_body, lemma), Modifier.padding(16.dp))
        return
    }
    val state = remember(tick, word.word) { progress.stateOf(word.word) }
    VocabWordCard(
        word = word,
        settings = settings,
        index = index,
        onRelated = { related ->
            val target = index.lookupAnyForm(related)
            if (target != null) onNavigate(vocabWordRoute(index.packOf(target.word)?.id ?: packId, target.word))
        },
        onSpeak = { speakVocabWord(context, settings, speaker, word) },
    )
    SettingsGroup(stringResource(R.string.vocab_word_progress_group)) {
        item {
            CaptionText(
                when {
                    state.learnt -> stringResource(R.string.vocab_word_progress_learnt)
                    !state.seen -> stringResource(R.string.vocab_word_progress_new)
                    else -> stringResource(R.string.vocab_word_progress_learning, state.box + 1, state.reps, (state.dueDay - vocabToday()).coerceAtLeast(0))
                },
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            WmRow(
                title = stringResource(if (state.learnt) R.string.vocab_word_unlearn_action else R.string.vocab_word_learn_action),
                icon = if (state.learnt) Icons.Outlined.Close else Icons.Outlined.CheckCircle,
                onClick = {
                    progress.markLearnt(word.word, !state.learnt, vocabToday())
                    progress.save()
                    tick++
                },
            )
        }
        if (state.seen) {
            item {
                WmRow(title = stringResource(R.string.vocab_word_reset_action), icon = Icons.Outlined.Refresh, onClick = {
                    progress.reset(word.word)
                    progress.save()
                    tick++
                })
            }
        }
        item {
            WmRow(title = stringResource(R.string.vocab_word_add_list_action), icon = Icons.Outlined.PlaylistAdd, onClick = {
                scope.launch {
                    lists = withContext(Dispatchers.IO) {
                        VocabPacks.languages(context.filesDir).flatMap { lang ->
                            VocabPacks.files(context.filesDir, lang).mapNotNull { VocabPacks.loadFile(it) }.filter { it.meta.userCreated }
                        }
                    }
                }
            })
        }
    }
    Spacer(Modifier.height(16.dp))

    lists?.let { options ->
        AlertDialog(
            onDismissRequest = { lists = null },
            title = { Text(stringResource(R.string.vocab_word_add_list_action)) },
            text = {
                Column {
                    val myWordsName = stringResource(com.wasimaster.wmkeyboard.tools.R.string.core_tools_vocab_my_words)
                    TextButton(onClick = {
                        lists = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VocabPacks.addToMyWords(context.filesDir, word.let { index.packOf(it.word)?.langId ?: "en" }, word, myWordsName, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
                            }
                            revision++
                        }
                    }) { Text(myWordsName) }
                    for (pack in options.filter { it.id != VocabPacks.MY_WORDS_ID }) {
                        TextButton(onClick = {
                            lists = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (pack.words.none { it.word == word.word }) {
                                        VocabPacks.write(context.filesDir, pack.copy(words = pack.words + word), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
                                    }
                                }
                                revision++
                            }
                        }) { Text(pack.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { lists = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
}

/** The Material card of one word: every section whose visibility is not Off. */
@Composable
internal fun VocabWordCard(
    word: VocabWord,
    settings: KeyboardSettings,
    index: VocabIndex?,
    compact: Boolean = false,
    onRelated: (String) -> Unit,
    onSpeak: () -> Unit,
) {
    val v = settings.vocabulary
    val fields = remember(v.cardFields) { VocabCardFields.resolve(v.cardFields) }
    fun shown(field: VocabCardField) = VocabCardFields.inApp(fields, field)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(word.word, style = MaterialTheme.typography.headlineSmall)
                    val line = listOfNotNull(
                        word.pos.joinToString(", ").takeIf { it.isNotEmpty() },
                        word.ipaFor(v.accent).takeIf { shown(VocabCardField.IPA) },
                        word.respelling.takeIf { shown(VocabCardField.RESPELLING) },
                    ).joinToString("  ·  ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(R.string.vocab_word_speak_desc)) }
            }
            if (shown(VocabCardField.SOURCES) && word.sources.isNotEmpty()) {
                Text(
                    word.sources.joinToString(" · ") { id -> index?.sources?.get(id)?.name ?: id },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val senses = if (compact) word.senses.take(2) else word.senses
            senses.forEachIndexed { i, sense ->
                Column {
                    Row {
                        Text("${i + 1}. ", style = MaterialTheme.typography.bodyMedium)
                        Column {
                            val head = listOfNotNull(
                                sense.pos.takeIf { it.isNotEmpty() },
                                sense.tags.joinToString(", ").takeIf { shown(VocabCardField.TAGS) && it.isNotEmpty() },
                            ).joinToString(" · ")
                            if (head.isNotEmpty()) Text(head, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontStyle = FontStyle.Italic)
                            Text(sense.definition, style = MaterialTheme.typography.bodyMedium)
                            if (shown(VocabCardField.EXAMPLES) && sense.example != null) {
                                Text("“${sense.example}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!compact && shown(VocabCardField.QUOTATIONS)) {
                                for (quote in sense.quotations) {
                                    Text("“${quote.text}” — ${quote.ref}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (!compact && shown(VocabCardField.TOPICS) && sense.topics.isNotEmpty()) {
                                Text(sense.topics.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (compact) return@Column
            val chipRows = buildList {
                if (shown(VocabCardField.SYNONYMS) && word.synonyms.isNotEmpty()) add(R.string.vocab_word_synonyms_label to word.synonyms)
                if (shown(VocabCardField.ANTONYMS) && word.antonyms.isNotEmpty()) add(R.string.vocab_word_antonyms_label to word.antonyms)
                if (shown(VocabCardField.FAMILY) && word.familyWords.isNotEmpty()) add(R.string.vocab_word_family_label to word.familyWords)
                if (shown(VocabCardField.HYPERNYMS) && word.hypernyms.isNotEmpty()) add(R.string.vocab_word_hypernyms_label to word.hypernyms)
                if (shown(VocabCardField.HYPERNYMS) && word.hyponyms.isNotEmpty()) add(R.string.vocab_word_hyponyms_label to word.hyponyms)
            }
            for ((labelRes, words) in chipRows) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (related in words) {
                        val known = index?.lookupAnyForm(related) != null
                        AssistChip(onClick = { onRelated(related) }, enabled = known, label = { Text(related) })
                    }
                }
            }
            val lines = buildList {
                if (shown(VocabCardField.ORIGIN) && word.origin.isNotEmpty()) add(R.string.vocab_word_origin_label to word.origin.joinToString(" ← ") { "${it.lang} ${it.word}" })
                if (shown(VocabCardField.ETYMOLOGY) && !word.etymology.isNullOrBlank()) add(R.string.vocab_word_etymology_label to word.etymology!!)
                if (shown(VocabCardField.ROOT) && !word.root.isNullOrBlank()) add(R.string.vocab_word_root_label to word.root!!)
                if (shown(VocabCardField.ATTESTED) && !word.attested.isNullOrBlank()) add(R.string.vocab_word_attested_label to word.attested!!)
                if (shown(VocabCardField.MNEMONIC) && !word.mnemonic.isNullOrBlank()) add(R.string.vocab_word_mnemonic_label to word.mnemonic!!)
                if (shown(VocabCardField.HYPHENATION) && word.hyphenation.size > 1) add(R.string.vocab_word_hyphenation_label to word.hyphenation.joinToString("·"))
                if (shown(VocabCardField.RHYMES) && !word.rhymes.isNullOrBlank()) add(R.string.vocab_word_rhymes_label to word.rhymes!!)
                if (shown(VocabCardField.FORMS) && word.forms.isNotEmpty()) add(R.string.vocab_word_forms_label to word.forms.joinToString(", "))
                if (shown(VocabCardField.WIKIPEDIA) && !word.wikipedia.isNullOrBlank()) add(R.string.vocab_word_wikipedia_label to word.wikipedia!!)
            }
            for ((labelRes, body) in lines) {
                Column {
                    Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(body, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (shown(VocabCardField.TRANSLATIONS) && word.translations.isNotEmpty()) {
                val wanted = v.translationLangList.ifEmpty { word.translations.keys.sorted() }
                for (code in wanted) {
                    val tr = word.translations[code] ?: continue
                    Row {
                        Text(languageNameFor(code) + ": ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            tr.w.joinToString(", ") + (tr.r.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString(", ")})" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (word.translations.isEmpty() && v.translationLangList.isNotEmpty()) {
                Spacer(Modifier.width(0.dp))
            }
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val unusedTool = ToolbarTool.VOCABULARY
}

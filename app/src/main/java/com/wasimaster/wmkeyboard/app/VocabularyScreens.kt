@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInBrowser
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lightbulb
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlaylistAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.School
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
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
import com.wasimaster.wmkeyboard.core.vocab.VocabIndexCache
import com.wasimaster.wmkeyboard.core.vocab.VocabLanguages
import com.wasimaster.wmkeyboard.core.vocab.VocabWordInterval
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeLevel
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeScope
import com.wasimaster.wmkeyboard.core.vocab.VocabPack
import com.wasimaster.wmkeyboard.core.vocab.VocabPackFile
import com.wasimaster.wmkeyboard.core.vocab.VocabQuotation
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

/**
 * The merged index of every enabled pack: the process's one copy
 * ([VocabIndexCache]), so a screen that opens after another has built it
 * paints at once; null only on the very first opening while it is built.
 * [revision] bumps after this screen wrote a pack, so the cache re-checks
 * the disk.
 */
@Composable
internal fun rememberVocabIndex(revision: Int, settings: LiveSettings): VocabIndex? {
    val context = LocalContext.current
    val codes = settings.watch { vocabTranslationCodes(it) }
    val state = produceState(initialValue = VocabIndexCache.peek(), key1 = revision, key2 = codes) {
        value = VocabIndexCache.get(context.filesDir, codes)
    }
    return state.value
}

/**
 * The sidecar codes the user reads translations in: the ones they chose, or
 * else the keyboard's languages (`bn_rom` reads `bn`, `zh` reads `cmn`).
 */
internal fun vocabTranslationCodes(settings: KeyboardSettings): List<String> =
    VocabLanguages.wantedCodes(settings.vocabulary.translationLangList, settings.enabledLanguages.map { it.id })

/** Catalogue packs on disk, by id, whether enabled or switched off. */
internal fun installedCatalogIds(filesDir: File): Set<String> =
    VocabPacks.languages(filesDir)
        .flatMap { VocabPacks.files(filesDir, it) }
        .map { VocabPacks.packIdOf(it) }
        .filter { VocabCatalog.byId(it) != null }
        .toSet()

/**
 * Fetches the translation sidecars the user's languages call for and nobody
 * asked for by hand: for every installed catalogue pack, the wanted codes it
 * offers that are neither on disk nor in flight. Only when data saving allows
 * it outright — a connection that wants asking waits for a tap on the chip —
 * and never a retry of one that failed, so a dead network does not loop.
 */
internal suspend fun autoFetchVocabTranslations(context: Context, settings: KeyboardSettings) {
    if (downloadDecisionNow(context, settings) != MeteredDecision.ALLOWED) return
    val wanted = vocabTranslationCodes(settings)
    if (wanted.isEmpty()) return
    VocabDownloadManager.refresh(context.filesDir)
    val installed = withContext(Dispatchers.IO) { installedCatalogIds(context.filesDir) }
    val states = VocabDownloadManager.translationStates.value
    for (entry in VocabCatalog.entries) {
        if (entry.id !in installed) continue
        val codes = VocabLanguages.codesToFetch(wanted, entry.translationCodes).filter { code ->
            val status = states[VocabDownloadManager.translationKey(entry.id, code)]
            status == null || status == VocabDownloadManager.DownloadStatus.NotDownloaded
        }
        if (codes.isNotEmpty()) VocabDownloadManager.startTranslations(context.filesDir, entry, codes)
    }
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

private fun isMeteredNetwork(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == true
}.getOrDefault(false)

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
    VocabLanguages.displayName(code) { id -> LanguageRegistry.all.firstOrNull { it.id == id }?.englishName }

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

/** Where a word not in the packs is looked up, in the order of the tool's provider list. */
private fun vocabSources(settings: KeyboardSettings): List<VocabAutofill.Source> =
    settings.resolveProviders(ProviderTool.VOCAB_AUTOFILL).mapNotNull { VocabAutofill.sourceOf(it.typeId) }

/** The Vocabulary tool's settings page body, called from [ToolDetailSettings]. */
@Composable
internal fun VocabularyToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val d = SettingsDefaults.vocabulary
    // What decides which rows the groups hold; each row reads its own value.
    val nudges = settings.watch { it.vocabulary.nudges }
    val ttsTunable = settings.watch { it.vocabulary.audioSource != VocabAudioSource.WIKTIONARY }
    val showsWord = settings.watch { it.vocabulary.wordOfTheDayCard || it.vocabulary.wordOfTheDayChip }
    val wordChip = settings.watch { it.vocabulary.wordOfTheDayChip }
    val numberFormat = stringResource(R.string.values_number)
    val speedFormat = stringResource(R.string.tooldetail_vocab_speed_value)
    val summary by produceState<Pair<Int, Int>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val packs = VocabPacks.languages(context.filesDir).sumOf { VocabPacks.files(context.filesDir, it, enabledOnly = true).size }
            val progress = VocabProgress(File(context.filesDir, VocabProgress.FILE_PATH))
            packs to progress.stats(vocabToday()).due
        }
    }

    // A language added to the keyboard, or picked below, gets its translations
    // fetched here rather than on the next visit to the packs screen.
    LaunchedEffect(settings.watch { it.vocabulary.translationLangs }, settings.watch { it.enabledLanguages }) {
        autoFetchVocabTranslations(context, settings.value)
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
            NavRow(
                R.string.tooldetail_vocab_lists_title,
                stringResource(R.string.tooldetail_vocab_lists_subtitle),
                route = VOCAB_LISTS_ROUTE,
            ) {
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
            NavRow(
                R.string.tooldetail_vocab_browse_title,
                stringResource(R.string.tooldetail_vocab_browse_subtitle),
                route = VOCAB_BROWSE_ROUTE,
            ) {
                onNavigate(VOCAB_BROWSE_ROUTE)
            }
        }
    }

    SettingsGroup(stringResource(R.string.tooldetail_vocab_nudges_group), info = stringResource(R.string.tooldetail_vocab_nudges_info)) {
        item {
            ToggleSetting(
                R.string.tooldetail_vocab_nudges_title,
                stringResource(R.string.tooldetail_vocab_nudges_subtitle),
                nudges,
                default = d.nudges,
            ) {
                scope.launch { repository.setVocabNudges(it) }
            }
        }
        item(visible = nudges, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.tooldetail_vocab_nudge_self_title,
                stringResource(R.string.tooldetail_vocab_nudge_self_subtitle),
                settings.watch { it.vocabulary.nudgeOnVocabWord },
                default = d.nudgeOnVocabWord,
            ) { scope.launch { repository.setVocabNudgeOnVocabWord(it) } }
        }
        item(visible = nudges, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.tooldetail_vocab_tap_title,
                subtitle = stringResource(R.string.tooldetail_vocab_tap_subtitle),
                options = listOf(
                    VocabChipTap.OPEN to stringResource(R.string.tooldetail_vocab_tap_open_label),
                    VocabChipTap.REPLACE to stringResource(R.string.tooldetail_vocab_tap_replace_label),
                ),
                selected = settings.watch { it.vocabulary.chipTapAction },
                default = d.chipTapAction,
            ) { scope.launch { repository.setVocabChipTapAction(it) } }
        }
        item(visible = nudges, level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                R.string.tooldetail_vocab_scope_title,
                options = listOf(
                    VocabNudgeScope.UNLEARNT to stringResource(R.string.tooldetail_vocab_scope_unlearnt_label),
                    VocabNudgeScope.ALL to stringResource(R.string.tooldetail_vocab_scope_all_label),
                    VocabNudgeScope.LEARNT_ONLY to stringResource(R.string.tooldetail_vocab_scope_learnt_label),
                ),
                selected = settings.watch { it.vocabulary.nudgeScope },
                default = d.nudgeScope,
            ) { scope.launch { repository.setVocabNudgeScope(it) } }
        }
        item(visible = nudges, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.tooldetail_vocab_level_title,
                subtitle = stringResource(R.string.tooldetail_vocab_level_subtitle),
                options = listOf(
                    VocabNudgeLevel.LOW to stringResource(R.string.tooldetail_vocab_level_low_label),
                    VocabNudgeLevel.MEDIUM to stringResource(R.string.tooldetail_vocab_level_medium_label),
                    VocabNudgeLevel.HIGH to stringResource(R.string.tooldetail_vocab_level_high_label),
                ),
                selected = settings.watch { it.vocabulary.nudgeLevel },
                default = d.nudgeLevel,
            ) { scope.launch { repository.setVocabNudgeLevel(it) } }
        }
        item(visible = nudges, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.tooldetail_vocab_cooldown_title,
                options = listOf(
                    VocabCooldown.EVERY_TIME to stringResource(R.string.tooldetail_vocab_cooldown_every_label),
                    VocabCooldown.ONCE_PER_FIELD to stringResource(R.string.tooldetail_vocab_cooldown_field_label),
                    VocabCooldown.ONCE_PER_DAY to stringResource(R.string.tooldetail_vocab_cooldown_day_label),
                ),
                selected = settings.watch { it.vocabulary.cooldown },
                default = d.cooldown,
            ) { scope.launch { repository.setVocabCooldown(it) } }
        }
    }

    SettingsGroup(
        stringResource(R.string.tooldetail_vocab_card_group),
        foldKey = "vocab_card",
        info = stringResource(R.string.tooldetail_vocab_card_info),
        foldSummary = {
            val resolved = settings.watch { VocabCardFields.resolve(it.vocabulary.cardFields) }
            // joinToString's lambda is not composable, so resolve the names first.
            val names = VocabCardField.entries
                .filter { resolved[it] == FieldVisibility.KEYBOARD }
                .map { stringResource(it.titleRes()) }
            names.joinToString(", ")
        },
        action = {
            if (settings.watch { it.vocabulary.cardFields.isNotEmpty() }) {
                TextButton(onClick = { scope.launch { repository.setVocabCardFields("") } }) {
                    Text(stringResource(CommonR.string.common_reset))
                }
            }
        },
        level = SettingLevel.ADVANCED,
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
                    selected = settings.watch { VocabCardFields.resolve(it.vocabulary.cardFields)[field] }
                        ?: field.defaultVisibility,
                    default = field.defaultVisibility,
                ) {
                    val fields = settings.value.vocabulary.cardFields
                    scope.launch { repository.setVocabCardFields(VocabCardFields.with(fields, field, it)) }
                }
            }
        }
    }

    ServerFieldsGroup(
        repository,
        settings,
        endpoints = listOf(ServiceEndpoint.KAIKKI, ServiceEndpoint.WIKTIONARY, ServiceEndpoint.DICTIONARY_API),
        level = SettingLevel.ADVANCED,
    )
    SettingsGroup(stringResource(R.string.tooldetail_vocab_related_group), level = SettingLevel.STANDARD) {
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                R.string.tooldetail_vocab_related_title,
                subtitle = stringResource(R.string.tooldetail_vocab_related_subtitle),
                options = listOf(
                    VocabRelatedTap.OPEN_CARD_ELSE_INSERT to stringResource(R.string.tooldetail_vocab_related_open_label),
                    VocabRelatedTap.INSERT to stringResource(R.string.tooldetail_vocab_related_insert_label),
                    VocabRelatedTap.DICTIONARY_LOOKUP to stringResource(R.string.tooldetail_vocab_related_lookup_label),
                ),
                selected = settings.watch { it.vocabulary.relatedTap },
                default = d.relatedTap,
            ) { scope.launch { repository.setVocabRelatedTap(it) } }
        }
        item {
            var picking by remember { mutableStateOf(false) }
            val chosen = settings.watch { it.vocabulary.translationLangList }
            val automatic = settings.watch { vocabTranslationCodes(it) }
            NavRow(
                R.string.tooldetail_vocab_translations_title,
                subtitle = when {
                    chosen.isNotEmpty() -> chosen.joinToString(", ") { languageNameFor(it) }
                    automatic.isNotEmpty() -> stringResource(
                        R.string.tooldetail_vocab_translations_auto_named_subtitle,
                        automatic.joinToString(", ") { languageNameFor(it) },
                    )
                    else -> stringResource(R.string.tooldetail_vocab_translations_auto_subtitle)
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

    // Where a word that no pack knows is looked up when you add it.
    ProvidersGroup(
        repository,
        settings,
        ProviderTool.VOCAB_AUTOFILL,
        info = stringResource(R.string.tooldetail_vocab_providers_info),
        level = SettingLevel.STANDARD,
    )
    SettingsGroup(
        stringResource(R.string.tooldetail_vocab_audio_group),
        info = stringResource(R.string.tooldetail_vocab_audio_info),
        level = SettingLevel.STANDARD,
    ) {
        item {
            ChoiceSetting(
                R.string.tooldetail_vocab_audio_source_title,
                options = listOf(
                    VocabAudioSource.AUTO to stringResource(R.string.tooldetail_vocab_audio_auto_label),
                    VocabAudioSource.WIKTIONARY to stringResource(R.string.tooldetail_vocab_audio_wiktionary_label),
                    VocabAudioSource.TTS to stringResource(R.string.tooldetail_vocab_audio_tts_label),
                ),
                selected = settings.watch { it.vocabulary.audioSource },
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
                selected = settings.watch { it.vocabulary.accent },
                default = d.accent,
            ) { scope.launch { repository.setVocabAccent(it) } }
        }
        item(visible = ttsTunable, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.tooldetail_vocab_tts_rate_title,
                value = settings.watch { it.vocabulary.ttsRate },
                range = VocabularySettings.MIN_TTS..VocabularySettings.MAX_TTS,
                display = { speedFormat.format(it) },
                default = d.ttsRate,
            ) { scope.launch { repository.setVocabTtsRate(it) } }
        }
        item(visible = ttsTunable, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.tooldetail_vocab_tts_pitch_title,
                value = settings.watch { it.vocabulary.ttsPitch },
                range = VocabularySettings.MIN_TTS..VocabularySettings.MAX_TTS,
                display = { speedFormat.format(it) },
                default = d.ttsPitch,
            ) { scope.launch { repository.setVocabTtsPitch(it) } }
        }
        item {
            val speaker = rememberVocabSpeaker()
            WmRow(
                title = stringResource(R.string.tooldetail_vocab_audio_test_title),
                icon = SettingsRowIcons[R.string.tooldetail_vocab_audio_test_title],
                onClick = { speakVocabWord(context, settings.value, speaker, VocabWord("serendipity")) },
            )
        }
    }

    SettingsGroup(
        stringResource(R.string.tooldetail_vocab_flashcards_group),
        info = stringResource(R.string.tooldetail_vocab_flashcards_info),
        level = SettingLevel.STANDARD,
    ) {
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                R.string.tooldetail_vocab_scheduler_title,
                options = listOf(
                    VocabScheduler.LEITNER to stringResource(R.string.tooldetail_vocab_scheduler_leitner_label),
                    VocabScheduler.SM2 to stringResource(R.string.tooldetail_vocab_scheduler_sm2_label),
                ),
                selected = settings.watch { it.vocabulary.scheduler },
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
                value = settings.watch { it.vocabulary.dailyGoal }.toFloat(),
                range = VocabularySettings.MIN_DAILY_GOAL.toFloat()..VocabularySettings.MAX_DAILY_GOAL.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                default = d.dailyGoal.toFloat(),
            ) { scope.launch { repository.setVocabDailyGoal(it.roundToInt()) } }
        }
    }

    SettingsGroup(stringResource(R.string.tooldetail_vocab_wotd_group), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.tooldetail_vocab_wotd_card_title,
                stringResource(R.string.tooldetail_vocab_wotd_card_subtitle),
                settings.watch { it.vocabulary.wordOfTheDayCard },
                default = d.wordOfTheDayCard,
            ) {
                scope.launch { repository.setVocabWordOfTheDayCard(it) }
            }
        }
        item {
            ToggleSetting(
                R.string.tooldetail_vocab_wotd_chip_title,
                stringResource(R.string.tooldetail_vocab_wotd_chip_subtitle),
                wordChip,
                default = d.wordOfTheDayChip,
            ) {
                scope.launch { repository.setVocabWordOfTheDayChip(it) }
            }
        }
        // How often the word turns over only matters where a word is shown:
        // the card on the settings home, the chip on the keyboard, or both.
        if (showsWord) item {
            ChoiceSetting(
                R.string.tooldetail_vocab_wotd_interval_title,
                subtitle = stringResource(R.string.tooldetail_vocab_wotd_interval_subtitle),
                options = listOf(
                    VocabWordInterval.DAILY to stringResource(R.string.tooldetail_vocab_wotd_interval_day_label),
                    VocabWordInterval.EVERY_12_HOURS to stringResource(R.string.tooldetail_vocab_wotd_interval_12h_label),
                    VocabWordInterval.EVERY_6_HOURS to stringResource(R.string.tooldetail_vocab_wotd_interval_6h_label),
                    VocabWordInterval.EVERY_3_HOURS to stringResource(R.string.tooldetail_vocab_wotd_interval_3h_label),
                    VocabWordInterval.HOURLY to stringResource(R.string.tooldetail_vocab_wotd_interval_hour_label),
                ),
                selected = settings.watch { it.vocabulary.wordInterval },
                default = d.wordInterval,
            ) { scope.launch { repository.setVocabWordInterval(it) } }
        }
        item(visible = wordChip, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.tooldetail_vocab_wotd_chip_times_title,
                subtitle = stringResource(R.string.tooldetail_vocab_wotd_chip_times_subtitle),
                value = settings.watch { it.vocabulary.chipTimesPerWord }.toFloat(),
                range = VocabularySettings.MIN_CHIP_TIMES.toFloat()..VocabularySettings.MAX_CHIP_TIMES.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                default = d.chipTimesPerWord.toFloat(),
            ) { scope.launch { repository.setVocabChipTimesPerWord(it.roundToInt()) } }
        }
    }
}

/**
 * Checkboxes over Wiktionary's language codes: the packs' sidecars, the
 * keyboard's languages and [extraCodes]. [chosenNow] is the stored pick
 * list. The Dictionary tool's translations (#764) use it too, with more
 * languages offered, since Wiktionary's pages carry far more than the packs.
 */
@Composable
internal fun TranslationLanguagesDialog(
    settings: LiveSettings,
    onDismiss: () -> Unit,
    onChange: (List<String>) -> Unit,
    chosenNow: (KeyboardSettings) -> List<String> = { it.vocabulary.translationLangList },
    title: String = stringResource(R.string.tooldetail_vocab_translations_title),
    body: String = stringResource(R.string.tooldetail_vocab_translations_dialog_body),
    extraCodes: List<String> = emptyList(),
) {
    // Sidecar codes only: a keyboard language maps to its code (bn_rom reads
    // bn) so Bangla is one entry, not two, and a pick always matches a file.
    val keyboardCodes = settings.watch { s -> s.enabledLanguages.flatMap { VocabLanguages.codesFor(it.id) } }
    val offered = remember {
        (VocabCatalog.entries.flatMap { it.translationCodes } + keyboardCodes + extraCodes)
            .filter { it != "en" }
            .distinct()
            .sortedBy { languageNameFor(it) }
    }
    val initialChosen = settings.watch { s -> chosenNow(s).flatMap { VocabLanguages.codesFor(it) }.distinct() }
    var chosen by remember { mutableStateOf(initialChosen) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CaptionText(body)
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

/**
 * What the packs screen showed last time, so coming back paints it on the
 * first frame instead of listing every pack as "not downloaded" until the
 * disk has been read. Process-held and keyed on the pack files and the
 * learning record, like the daily card's draw.
 */
private object InstalledPacksCache {
    var key: Long = Long.MIN_VALUE
    var packs: List<InstalledVocabPack>? = null
}

private suspend fun readInstalledPacks(context: Context): List<InstalledVocabPack> = withContext(Dispatchers.IO) {
    val filesDir = context.filesDir
    val progressFile = File(filesDir, VocabProgress.FILE_PATH)
    val key = VocabPacks.stateToken(filesDir).toLong() * 31 + progressFile.lastModified() + progressFile.length()
    InstalledPacksCache.packs?.takeIf { InstalledPacksCache.key == key }?.let { return@withContext it }
    val progress = VocabProgress(progressFile)
    val today = vocabToday()
    val packs = VocabPacks.languages(filesDir).flatMap { langId ->
        VocabPacks.files(filesDir, langId).mapNotNull { file ->
            val pack = VocabPacks.loadFile(file) ?: return@mapNotNull null
            if (pack.meta.userCreated) return@mapNotNull null
            val stats = progress.stats(today, pack.words.map { it.word })
            InstalledVocabPack(file, pack, stats.learnt, stats.seen)
        }
    }
    InstalledPacksCache.key = key
    InstalledPacksCache.packs = packs
    packs
}

/** The packs screen: the catalogue's downloads, what is installed, imports and translation sidecars. */
@Composable
internal fun VocabPacksScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf(InstalledPacksCache.packs) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<InstalledVocabPack?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var confirmMetered by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showLinks by remember { mutableStateOf(false) }
    val states by VocabDownloadManager.states.collectAsState()
    val translationStates by VocabDownloadManager.translationStates.collectAsState()
    val downloadDecision = rememberDownloadDecision(settings)

    LaunchedEffect(revision) {
        VocabDownloadManager.refresh(context.filesDir)
        installed = readInstalledPacks(context)
        autoFetchVocabTranslations(context, settings.value)
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
                is VocabPacks.ImportResult.Imported -> context.resources.getQuantityString(
                    R.plurals.vocab_import_done,
                    result.wordCount,
                    result.wordCount,
                )
                VocabPacks.ImportResult.TooLarge -> context.getString(R.string.vocab_import_too_large_error)
                VocabPacks.ImportResult.Empty -> context.getString(R.string.vocab_import_empty_error)
                VocabPacks.ImportResult.NotAPack, null -> context.getString(R.string.vocab_import_error)
            }
            if (result is VocabPacks.ImportResult.Imported) revision++
        }
    }

    val notifyDownload = rememberDownloadNotifier()

    fun gated(action: () -> Unit) {
        when (downloadDecision()) {
            MeteredDecision.ALLOWED -> action()
            MeteredDecision.ASK -> confirmMetered = action
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    AddonStoreGroup(AddonType.Vocabulary, onNavigate)

    // Nothing read yet (first opening of the process): say so rather than
    // list every pack as missing and then flip once the disk answers.
    val known = installed
    if (known == null) {
        CaptionText(stringResource(R.string.vocab_packs_loading), Modifier.padding(16.dp))
        return
    }

    val installedIds = known.map { VocabPacks.packIdOf(it.file) }.toSet()
    val available = VocabCatalog.entries.filter { it.id !in installedIds }
    // What you have first: the packs you use are the ones you come back for;
    // the catalogue below is for the odd new one.
    SettingsGroup(stringResource(R.string.vocab_packs_installed_title), highlightKey = R.string.vocab_packs_installed_title) {
        if (known.isEmpty()) {
            item { CaptionText(stringResource(R.string.vocab_packs_installed_empty)) }
        }
        for (item in known) {
            item(key = item.file.absolutePath) {
                val packId = VocabPacks.packIdOf(item.file)
                val catalog = VocabCatalog.byId(packId)
                HighlightableItem(packId) {
                    Column {
                        WmRow(
                            title = item.pack.name,
                            subtitle = stringResource(R.string.vocab_pack_progress_subtitle, item.pack.words.size, item.seen, item.learnt),
                            icon = Icons.Outlined.School,
                            swipeActions = listOf(
                                deleteSwipeAction(stringResource(CommonR.string.common_delete)) {
                                    confirmDelete = item
                                },
                            ),
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
                                wanted = settings.watch { vocabTranslationCodes(it) },
                                onFetch = { code -> gated { VocabDownloadManager.startTranslations(
                                    context.filesDir,
                                    catalog,
                                    listOf(code),
                                ) } },
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
        // Where each pack is published, to fetch in a browser when the
        // download here cannot run: the no-internet build, a blocked network
        // (#665). The file then comes in through Import above.
        item {
            WmRow(
                title = stringResource(R.string.vocab_packs_links_title),
                subtitle = stringResource(R.string.vocab_packs_links_subtitle),
                icon = Icons.Outlined.OpenInBrowser,
                onClick = { showLinks = true },
            )
        }
    }
    if (showLinks) {
        OfflineLinksDialog(VocabCatalog.entries.map { OfflineLink(it.name, it.url) }) { showLinks = false }
    }
    if (available.isNotEmpty() && !offlineBuild) {
        SettingsGroup(stringResource(R.string.vocab_packs_available_title), info = stringResource(R.string.vocab_packs_available_info)) {
            for (entry in available) {
                item {
                    VocabCatalogRow(
                        entry = entry,
                        status = states[entry.id],
                        onDownload = {
                            gated {
                                VocabDownloadManager.start(context.filesDir, entry, vocabTranslationCodes(settings.value))
                                notifyDownload(
                                    entry.id,
                                    entry.name,
                                    DownloadProgressFlows.vocab(context, entry.id),
                                )
                            }
                        },
                        onCancel = { VocabDownloadManager.cancel(entry.id) },
                    )
                }
            }
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
        MeteredDownloadDialog(
            detail = null,
            onConfirm = { confirmMetered = null; action() },
            onDismiss = { confirmMetered = null },
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
        is VocabDownloadManager.DownloadStatus.Downloading -> stringResource(
            R.string.vocab_pack_downloading_subtitle,
            formatVocabBytes(status.bytes),
            sizeText,
        )
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

/**
 * One line under an installed pack — "Translations · 2 of 56 languages" —
 * that opens into the chips. Folded by default: fifty-odd chips under every
 * pack made the screen a wall, and the languages the user reads are fetched
 * on their own; the chips are for the odd extra one.
 */
@Composable
private fun TranslationChips(
    entry: VocabCatalogEntry,
    states: Map<String, VocabDownloadManager.DownloadStatus>,
    wanted: List<String>,
    onFetch: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val have = entry.translationCodes.count { states[VocabDownloadManager.translationKey(
        entry.id,
        it,
    )] is VocabDownloadManager.DownloadStatus.Downloaded }
    // The user's own languages first, then the rest by name.
    val ordered = remember(entry.id, wanted) {
        val mine = wanted.filter { it in entry.translationCodes }
        mine + (entry.translationCodes - mine.toSet()).sortedBy { languageNameFor(it) }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.vocab_pack_translations_summary, have, entry.translationCodes.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.vocab_pack_translations_collapse_desc else R.string.vocab_pack_translations_expand_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                for (code in ordered) {
                    val status = states[VocabDownloadManager.translationKey(entry.id, code)]
                    val downloaded = status is VocabDownloadManager.DownloadStatus.Downloaded
                    val busy = status is VocabDownloadManager.DownloadStatus.Downloading || status == VocabDownloadManager.DownloadStatus.Queued
                    FilterChip(
                        selected = downloaded,
                        enabled = !busy,
                        onClick = { if (downloaded) onRemove(code) else onFetch(code) },
                        label = { Text(languageNameFor(code)) },
                        leadingIcon = if (downloaded) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
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
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var lists by remember { mutableStateOf<List<UserVocabList>>(emptyList()) }
    var newName by remember { mutableStateOf<String?>(null) }

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
                        val langId = settings.value.enabledLanguages.firstOrNull { it.id == "en" }?.id ?: "en"
                        val meta = VocabPacks.newUserPack(draft, langId)
                        newName = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VocabPacks.write(
                                    context.filesDir,
                                    VocabPack(meta, emptyList()),
                                    BuildConfig.VERSION_CODE,
                                    BuildConfig.VERSION_NAME,
                                )
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
    settings: LiveSettings,
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
    var bulk by remember { mutableStateOf(false) }
    val index = rememberVocabIndex(revision, settings)
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
            WmRow(
                title = stringResource(R.string.vocab_list_rename_title),
                icon = Icons.Outlined.Edit,
                onClick = { renaming = current.name },
            )
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
            WmRow(
                title = stringResource(R.string.vocab_list_bulk_title),
                subtitle = stringResource(R.string.vocab_list_bulk_subtitle),
                icon = Icons.Outlined.PlaylistAdd,
                onClick = { bulk = true },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.vocab_list_delete_title),
                icon = Icons.Outlined.Delete,
                onClick = { confirmDelete = true },
            )
        }
    }

    SettingsGroup(pluralStringResource(R.plurals.vocab_list_words_title, current.words.size, current.words.size)) {
        if (current.words.isEmpty()) {
            item { CaptionText(stringResource(R.string.vocab_list_words_empty)) }
        }
        for (word in current.words) {
            item(key = word.word) {
                val learnt = progress.isLearnt(word.word)
                WmRow(
                    title = word.word,
                    subtitle = word.definition.ifBlank { stringResource(R.string.vocab_word_needs_details_label) },
                    swipeActions = listOf(
                        SwipeAction(Icons.Outlined.Edit, stringResource(CommonR.string.common_edit)) {
                            adding = word
                            showSheet = true
                        },
                        deleteSwipeAction(stringResource(CommonR.string.common_delete)) {
                            save(current.copy(words = current.words.filter { it.word != word.word }))
                        },
                    ),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (learnt) Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(onClick = { adding = word; showSheet = true }) { Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(CommonR.string.common_edit),
                            ) }
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
    if (bulk) {
        BulkAddDialog(
            index = index,
            settings = settings,
            onDismiss = { bulk = false },
            onAdd = { words ->
                bulk = false
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
 * The add/edit sheet: type a word, take what an installed pack or the
 * network knows about it, or type the details yourself. Opens full-height
 * and scrolls, with Save in the header where it can always be reached; the
 * fields sit under small headings so a glance says what each one is for.
 */
@Composable
private fun AddVocabWordSheet(
    initial: VocabWord,
    index: VocabIndex?,
    settings: LiveSettings,
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
    val translationCodes = settings.watch { vocabTranslationCodes(it) }
    val translationCode = translationCodes.firstOrNull() ?: "bn"
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    // One tap, a few kilobytes: not a thing data saving has a say in. The
    // policy it used to consult governs the Dictionary tool's automatic
    // look-up on every selection, which is a different amount of traffic.
    fun lookupOnline() {
        val lemma = VocabPackFile.normalizeLemma(word) ?: return
        lookingUp = true
        status = context.getString(R.string.vocab_add_looking_up)
        scope.launch {
            val result = VocabAutofill.resolve(
                VocabIndex.EMPTY,
                lemma,
                allowOnline = true,
                translationCodes = translationCodes,
                sources = vocabSources(settings.value),
            )
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

    fun save() {
        val lemma = VocabPackFile.normalizeLemma(word) ?: return
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
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (initial.word.isEmpty()) R.string.vocab_list_add_word_action else CommonR.string.common_edit),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
                TextButton(enabled = VocabPackFile.normalizeLemma(word) != null, onClick = ::save) {
                    Text(stringResource(CommonR.string.common_save))
                }
            }
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

            SheetHeading(stringResource(R.string.vocab_add_section_meaning))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in listOf("noun", "verb", "adjective", "adverb")) {
                    FilterChip(selected = pos == option, onClick = { pos = if (pos == option) "" else option }, label = { Text(option) })
                }
            }
            OutlinedTextField(
                value = definition,
                onValueChange = { definition = it },
                label = { Text(stringResource(R.string.vocab_add_definition_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = example,
                onValueChange = { example = it },
                label = { Text(stringResource(R.string.vocab_add_example_label)) },
                modifier = Modifier.fillMaxWidth(),
            )

            SheetHeading(stringResource(R.string.vocab_add_section_related))
            OutlinedTextField(
                value = synonyms,
                onValueChange = { synonyms = it },
                label = { Text(stringResource(R.string.vocab_add_synonyms_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = antonyms,
                onValueChange = { antonyms = it },
                label = { Text(stringResource(R.string.vocab_add_antonyms_label)) },
                modifier = Modifier.fillMaxWidth(),
            )

            SheetHeading(stringResource(R.string.vocab_add_section_nudge))
            OutlinedTextField(
                value = triggers,
                onValueChange = { triggers = it },
                label = { Text(stringResource(R.string.vocab_add_triggers_label)) },
                supportingText = { Text(stringResource(R.string.vocab_add_triggers_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )

            SheetHeading(stringResource(R.string.vocab_add_section_remember))
            OutlinedTextField(
                value = mnemonic,
                onValueChange = { mnemonic = it },
                label = { Text(stringResource(R.string.vocab_add_mnemonic_label)) },
                modifier = Modifier.fillMaxWidth(),
            )

            SheetHeading(stringResource(R.string.vocab_add_section_translation))
            OutlinedTextField(
                value = translation,
                onValueChange = { translation = it },
                label = { Text(stringResource(R.string.vocab_add_translation_label, languageNameFor(translationCode))) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
                TextButton(enabled = VocabPackFile.normalizeLemma(word) != null, onClick = ::save) {
                    Text(stringResource(CommonR.string.common_save))
                }
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Paste many words; each takes its details from an installed pack, or
 * (optionally) from Wiktionary. The text lives in the dialog as a
 * `TextFieldValue` rather than being hoisted as a `String`: a multi-line
 * field whose value round-trips through the parent's state a frame late
 * had the keyboard re-committing its composing text, which doubled the
 * last word pasted.
 */
@Composable
private fun BulkAddDialog(
    index: VocabIndex?,
    settings: LiveSettings,
    onDismiss: () -> Unit,
    onAdd: (List<VocabWord>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var online by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val words = remember(field.text) { VocabAutofill.parseWordList(field.text) }
    val translationCodes = settings.watch { vocabTranslationCodes(it) }
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text(stringResource(R.string.vocab_list_bulk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
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
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = words.isNotEmpty() && progress == null,
                onClick = {
                    progress = 0 to words.size
                    scope.launch {
                        val out = ArrayList<VocabWord>()
                        var onlineLeft = MAX_BULK_ONLINE
                        for ((i, lemma) in words.withIndex()) {
                            val fromIndex = index?.lookupAnyForm(lemma)
                            val record = when {
                                fromIndex != null -> fromIndex
                                online && onlineLeft > 0 -> {
                                    onlineLeft--
                                    val result = VocabAutofill.resolve(
                                        VocabIndex.EMPTY,
                                        lemma,
                                        allowOnline = true,
                                        translationCodes = translationCodes,
                                        sources = vocabSources(settings.value),
                                    )
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
        dismissButton = { TextButton(
            enabled = progress == null,
            onClick = onDismiss,
        ) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

private const val MAX_BULK_ONLINE = 100

// ---- browse ----

/** Every word of the enabled packs, searchable and filterable, paged for the scrolling screen. */
@Composable
internal fun VocabBrowseScreen(
    settings: LiveSettings,
    packArg: String?,
    onNavigate: (String) -> Unit,
) {
    val index = rememberVocabIndex(0, settings)
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
            // One line each, scrolling sideways: eleven pack chips wrapped into
            // four rows pushed the list itself off the screen.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = packId == null,
                    onClick = { packId = null },
                    label = { Text(stringResource(R.string.vocab_browse_all_packs)) },
                )
                for (pack in index?.packs.orEmpty()) {
                    FilterChip(selected = packId == pack.id, onClick = { packId = pack.id }, label = { Text(pack.name) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    flightTo = vocabWordRoute(index.packOf(word.word)?.id ?: "all", word.word),
                    onClick = { onNavigate(vocabWordRoute(index.packOf(word.word)?.id ?: "all", word.word)) },
                )
            }
        }
        item(visible = rows.size > shown) {
            WmRow(
                title = pluralStringResource(R.plurals.vocab_browse_show_more, rows.size - shown, rows.size - shown),
                onClick = { shown += PAGE },
            )
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
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val index = rememberVocabIndex(revision, settings)
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
    var lookup by remember { mutableStateOf<String?>(null) }
    // A related word in a pack has a page of its own; any other is looked
    // up online and shown in a sheet, where it can be filed into My words.
    val openRelated: (String) -> Unit = { related ->
        val target = index.lookupAnyForm(related)
        if (target != null) {
            lookup = null
            onNavigate(vocabWordRoute(index.packOf(target.word)?.id ?: packId, target.word))
        } else {
            lookup = related
        }
    }
    VocabWordCard(
        word = word,
        settings = settings,
        index = index,
        learnt = state.learnt,
        box = state.box.takeIf { state.seen },
        onRelated = openRelated,
        onSpeak = { speakVocabWord(context, settings.value, speaker, word) },
        onGetTranslations = { onNavigate(VOCAB_PACKS_ROUTE) },
    )
    lookup?.let { looked ->
        VocabLookupSheet(
            word = looked,
            settings = settings,
            index = index,
            onDismiss = { lookup = null },
            onRelated = openRelated,
            onSaved = { revision++ },
        )
    }
    SettingsGroup(stringResource(R.string.vocab_word_progress_group)) {
        item {
            CaptionText(
                when {
                    state.learnt -> stringResource(R.string.vocab_word_progress_learnt)
                    !state.seen -> stringResource(R.string.vocab_word_progress_new)
                    else -> stringResource(
                        R.string.vocab_word_progress_learning,
                        state.box + 1,
                        state.reps,
                        (state.dueDay - vocabToday()).coerceAtLeast(0),
                    )
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
        item(visible = state.seen) {
            WmRow(title = stringResource(R.string.vocab_word_reset_action), icon = Icons.Outlined.Refresh, onClick = {
                progress.reset(word.word)
                progress.save()
                tick++
            })
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
                                VocabPacks.addToMyWords(
                                    context.filesDir,
                                    word.let { index.packOf(it.word)?.langId ?: "en" },
                                    word,
                                    myWordsName,
                                    BuildConfig.VERSION_CODE,
                                    BuildConfig.VERSION_NAME,
                                )
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
                                        VocabPacks.write(
                                            context.filesDir,
                                            pack.copy(words = pack.words + word),
                                            BuildConfig.VERSION_CODE,
                                            BuildConfig.VERSION_NAME,
                                        )
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

/**
 * A word that is in no pack, fetched from the network and shown as a card:
 * kaikki.org first, Wiktionary's endpoint as the backup, the free
 * dictionary API last. Its related words open here too when they are not
 * in a pack, and the word can be filed into My words from the sheet.
 */
@Composable
private fun VocabLookupSheet(
    word: String,
    settings: LiveSettings,
    index: VocabIndex?,
    onDismiss: () -> Unit,
    onRelated: (String) -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = rememberVocabSpeaker()
    val codes = settings.watch { vocabTranslationCodes(it) }
    val result by produceState<VocabAutofill.Result?>(initialValue = null, key1 = word) {
        value = VocabAutofill.resolve(
            VocabIndex.EMPTY,
            word,
            allowOnline = true,
            translationCodes = codes,
            sources = vocabSources(settings.value),
        )
    }
    var saved by remember(word) { mutableStateOf(false) }
    val myWordsName = stringResource(com.wasimaster.wmkeyboard.tools.R.string.core_tools_vocab_my_words)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            when (val current = result) {
                null -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.vocab_lookup_loading, word), style = MaterialTheme.typography.bodyMedium)
                }
                is VocabAutofill.Result.Found -> {
                    VocabWordCard(
                        word = current.word,
                        settings = settings,
                        index = index,
                        onRelated = onRelated,
                        onSpeak = { speakVocabWord(context, settings.value, speaker, current.word) },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            enabled = !saved,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        VocabPacks.addToMyWords(
                                            context.filesDir,
                                            "en",
                                            current.word,
                                            myWordsName,
                                            BuildConfig.VERSION_CODE,
                                            BuildConfig.VERSION_NAME,
                                        )
                                    }
                                    saved = true
                                    onSaved()
                                }
                            },
                        ) { Text(stringResource(if (saved) R.string.vocab_lookup_added_label else R.string.vocab_lookup_add_action)) }
                    }
                }
                VocabAutofill.Result.NotFound -> CaptionText(stringResource(R.string.vocab_lookup_not_found, word), Modifier.padding(24.dp))
                else -> CaptionText(stringResource(R.string.vocab_lookup_error), Modifier.padding(24.dp))
            }
        }
    }
}

/**
 * One word's card in the app. A hero with the headword, then one section
 * per kind of thing the record knows — meaning, related words, the
 * mnemonic, translations, where the word comes from, and the rest — each
 * in its own surface so the eye finds a section before it reads a line.
 * Origin and the odds and ends fold away; the meaning never does.
 */
@Composable
internal fun VocabWordCard(
    word: VocabWord,
    settings: LiveSettings,
    index: VocabIndex?,
    compact: Boolean = false,
    learnt: Boolean = false,
    /** The Leitner box, when the word has been reviewed. */
    box: Int? = null,
    onRelated: (String) -> Unit,
    onSpeak: () -> Unit,
    /** Where to go when the pack has no translation for the user's languages yet. */
    onGetTranslations: (() -> Unit)? = null,
) {
    val fields = settings.watch { VocabCardFields.resolve(it.vocabulary.cardFields) }
    val shown: (VocabCardField) -> Boolean = { VocabCardFields.inApp(fields, it) }
    val enabledIds = settings.watch { s -> s.enabledLanguages.map { it.id } }
    val wantedCodes = settings.watch { vocabTranslationCodes(it) }
    val accent = settings.watch { it.vocabulary.accent }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VocabHero(word, accent, index, learnt, box, shown, onSpeak)
        VocabMeaningSection(if (compact) word.senses.take(2) else word.senses, compact, shown)
        if (compact) return@Column
        VocabRelatedSection(word, index, shown, onRelated)
        if (shown(VocabCardField.MNEMONIC)) word.mnemonic?.takeIf { it.isNotBlank() }?.let { VocabMnemonicCard(it) }
        if (shown(VocabCardField.TRANSLATIONS)) VocabTranslationsSection(word, wantedCodes, enabledIds, onGetTranslations)
        VocabOriginSection(word, shown)
        VocabMoreSection(word, shown)
    }
    @Suppress("UNUSED_VARIABLE")
    val unusedTool = ToolbarTool.VOCABULARY
}

/** The headword, how to say it, the speaker, and the tags: parts of speech, progress, the lists it is in. */
@Composable
private fun VocabHero(
    word: VocabWord,
    accent: VocabAccent,
    index: VocabIndex?,
    learnt: Boolean,
    box: Int?,
    shown: (VocabCardField) -> Boolean,
    onSpeak: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(word.word, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    val line = listOfNotNull(
                        word.ipaFor(accent).takeIf { shown(VocabCardField.IPA) },
                        word.respelling.takeIf { shown(VocabCardField.RESPELLING) },
                    ).joinToString("  ·  ")
                    if (line.isNotEmpty()) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
                IconButton(onClick = onSpeak) {
                    Icon(
                        Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = stringResource(R.string.vocab_word_speak_desc),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp, end = 10.dp),
            ) {
                for (pos in word.pos) VocabTag(pos, strong = true)
                if (learnt) {
                    VocabTag(stringResource(R.string.vocab_word_learnt_badge), strong = true)
                } else if (box != null && box > 0) {
                    VocabTag(stringResource(R.string.vocab_word_box_badge, box + 1), strong = true)
                }
                if (shown(VocabCardField.SOURCES)) {
                    for (id in word.sources) VocabTag(index?.sources?.get(id)?.name?.takeIf { it.isNotEmpty() } ?: id, strong = false)
                }
            }
        }
    }
}

/** The senses, grouped under their parts of speech when there is more than one. */
@Composable
private fun VocabMeaningSection(senses: List<VocabSense>, compact: Boolean, shown: (VocabCardField) -> Boolean) {
    if (senses.isEmpty()) return
    VocabSection(title = stringResource(R.string.vocab_word_section_meaning)) {
        val grouped = senses.groupBy { it.pos }
        var number = 0
        for ((pos, group) in grouped) {
            if (pos.isNotEmpty() && grouped.size > 1) {
                Text(
                    pos,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            for (sense in group) {
                number++
                VocabSenseBlock(number, sense, compact, shown)
            }
        }
    }
}

/** Synonyms, antonyms, the family, broader and narrower words, as chips; every one tappable. */
@Composable
private fun VocabRelatedSection(word: VocabWord, index: VocabIndex?, shown: (VocabCardField) -> Boolean, onRelated: (String) -> Unit) {
    val chipRows = buildList {
        if (shown(VocabCardField.SYNONYMS) && word.synonyms.isNotEmpty()) add(R.string.vocab_word_synonyms_label to word.synonyms)
        if (shown(VocabCardField.ANTONYMS) && word.antonyms.isNotEmpty()) add(R.string.vocab_word_antonyms_label to word.antonyms)
        if (shown(VocabCardField.FAMILY) && word.familyWords.isNotEmpty()) add(R.string.vocab_word_family_label to word.familyWords)
        if (shown(VocabCardField.HYPERNYMS) && word.hypernyms.isNotEmpty()) add(R.string.vocab_word_hypernyms_label to word.hypernyms)
        if (shown(VocabCardField.HYPERNYMS) && word.hyponyms.isNotEmpty()) add(R.string.vocab_word_hyponyms_label to word.hyponyms)
    }
    if (chipRows.isEmpty()) return
    VocabSection(title = stringResource(R.string.vocab_word_section_related)) {
        for ((labelRes, words) in chipRows) {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (related in words) VocabRelatedChip(
                    related,
                    known = index?.lookupAnyForm(related) != null,
                    onClick = { onRelated(related) },
                )
            }
        }
    }
}

/** A related word; the ones with no page of their own carry a look-up glyph. */
@Composable
private fun VocabRelatedChip(word: String, known: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(word) },
        trailingIcon = if (known) {
            null
        } else {
            {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.vocab_word_lookup_chip_desc),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** The mnemonic, set apart: it is the one line meant to stick. */
@Composable
private fun VocabMnemonicCard(mnemonic: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.vocab_word_section_remember),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(mnemonic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

/** The user's languages only, romanisation beside each gloss; a way to the packs screen when none is downloaded. */
@Composable
private fun VocabTranslationsSection(
    word: VocabWord,
    wantedCodes: List<String>,
    enabledIds: List<String>,
    onGetTranslations: (() -> Unit)?,
) {
    // The user's languages when the record has them, else whatever it has:
    // a sidecar fetched by hand is worth showing whichever language it is.
    val codes = VocabLanguages.codesToShow(
        wantedCodes,
        word.translations.keys,
    ) { id -> LanguageRegistry.all.firstOrNull { it.id == id }?.englishName }
    val rows = codes.mapNotNull { code -> word.translations[code]?.let { code to it } }
    if (rows.isNotEmpty()) {
        VocabSection(title = stringResource(R.string.vocab_word_section_translations)) {
            for ((code, tr) in rows) {
                VocabTranslationRow(code, tr, romanizedFirst = VocabLanguages.prefersRomanized(code, enabledIds))
            }
        }
    } else if (wantedCodes.isNotEmpty() && onGetTranslations != null && word.sources.isNotEmpty()) {
        VocabSection(title = stringResource(R.string.vocab_word_section_translations)) {
            Text(
                stringResource(R.string.vocab_word_no_translations_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onGetTranslations, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.vocab_word_get_translations_action))
            }
        }
    }
}

/** Where the word comes from: the chain up front, the etymology, root and first use folded behind it. */
@Composable
private fun VocabOriginSection(word: VocabWord, shown: (VocabCardField) -> Boolean) {
    val lines = buildList {
        if (shown(VocabCardField.ETYMOLOGY)) word.etymology?.takeIf { it.isNotBlank() }?.let { add(R.string.vocab_word_etymology_label to it) }
        if (shown(VocabCardField.ROOT)) word.root?.takeIf { it.isNotBlank() }?.let { add(R.string.vocab_word_root_label to it) }
        if (shown(VocabCardField.ATTESTED)) word.attested?.takeIf { it.isNotBlank() }?.let { add(R.string.vocab_word_attested_label to it) }
    }
    val chain = word.origin.takeIf { shown(VocabCardField.ORIGIN) }.orEmpty()
    if (chain.isEmpty() && lines.isEmpty()) return
    VocabSection(
        title = stringResource(R.string.vocab_word_section_origin),
        collapsible = lines.isNotEmpty(),
        summary = if (chain.isEmpty()) {
            null
        } else {
            {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    chain.forEachIndexed { i, step ->
                        if (i > 0) Text("←", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        VocabTag("${step.lang} ${step.word}", strong = false)
                    }
                }
            }
        },
    ) {
        for ((labelRes, body) in lines) VocabLabeledText(stringResource(labelRes), body)
    }
}

/** Forms, hyphenation, rhymes and the Wikipedia title, folded away. */
@Composable
private fun VocabMoreSection(word: VocabWord, shown: (VocabCardField) -> Boolean) {
    val lines = buildList {
        if (shown(VocabCardField.FORMS) && word.forms.isNotEmpty()) add(R.string.vocab_word_forms_label to word.forms.joinToString(", "))
        if (shown(VocabCardField.HYPHENATION) && word.hyphenation.size > 1) add(R.string.vocab_word_hyphenation_label to word.hyphenation.joinToString("·"))
        if (shown(VocabCardField.RHYMES)) word.rhymes?.takeIf { it.isNotBlank() }?.let { add(R.string.vocab_word_rhymes_label to it) }
        if (shown(VocabCardField.WIKIPEDIA)) word.wikipedia?.takeIf { it.isNotBlank() }?.let { add(R.string.vocab_word_wikipedia_label to it) }
    }
    if (lines.isEmpty()) return
    VocabSection(title = stringResource(R.string.vocab_word_section_more), collapsible = true) {
        for ((labelRes, body) in lines) VocabLabeledText(stringResource(labelRes), body)
    }
}

/** One surface of the card. Collapsible ones show [summary] always and the body on a tap. */
@Composable
private fun VocabSection(
    title: String,
    collapsible: Boolean = false,
    summary: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(!collapsible) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (collapsible) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(if (expanded) R.string.vocab_word_section_collapse_desc else R.string.vocab_word_section_expand_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            summary?.invoke()
            if (expanded) {
                Spacer(Modifier.height(2.dp))
                content()
            }
        }
    }
}

/** A numbered sense: the definition, its example set in from the margin, quotations behind a toggle. */
@Composable
private fun VocabSenseBlock(number: Int, sense: VocabSense, compact: Boolean, shown: (VocabCardField) -> Boolean) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(
            "$number",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(22.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            if (shown(VocabCardField.TAGS) && sense.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    for (tag in sense.tags) VocabTag(tag, strong = false)
                }
            }
            Text(sense.definition, style = MaterialTheme.typography.bodyLarge)
            if (shown(VocabCardField.EXAMPLES) && sense.example != null) {
                Text(
                    "“${sense.example}”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 8.dp),
                )
            }
            if (!compact && shown(VocabCardField.QUOTATIONS) && sense.quotations.isNotEmpty()) {
                var open by remember(sense.definition) { mutableStateOf(false) }
                TextButton(onClick = { open = !open }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        pluralStringResource(R.plurals.vocab_word_quotations_toggle, sense.quotations.size, sense.quotations.size),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (open) {
                    for (quote in sense.quotations) VocabQuotationBlock(quote)
                }
            }
            if (!compact && shown(VocabCardField.TOPICS) && sense.topics.isNotEmpty()) {
                Text(
                    sense.topics.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Language name on the left; on the right one line per gloss with its own
 * romanisation set smaller beside it, so "ঘৃণা করা · ghrina kora" reads as
 * one thing. On a romanised layout the romanisation leads.
 */
@Composable
private fun VocabTranslationRow(code: String, tr: VocabTranslation, romanizedFirst: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            languageNameFor(code),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            modifier = Modifier.width(96.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            for (gloss in tr.glosses()) {
                val lead = if (romanizedFirst && gloss.roman != null) gloss.roman else gloss.word
                val trail = if (romanizedFirst && gloss.roman != null) gloss.word else gloss.roman
                Text(
                    buildAnnotatedString {
                        append(lead)
                        if (trail != null) {
                            withStyle(SpanStyle(color = muted, fontSize = 12.sp)) { append("  ·  $trail") }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The word's meaning in the user's language, one line, for the surfaces
 * that otherwise speak only English (#651): the word-of-the-day card and the
 * Review screen's flashcard back. Null, and nothing drawn, when the record
 * has no translation or Card sections has translations off.
 */
@Composable
internal fun rememberVocabMeaning(word: VocabWord, settings: LiveSettings): com.wasimaster.wmkeyboard.core.vocab.VocabMeaning? {
    val off = settings.watch { VocabCardFields.resolve(it.vocabulary.cardFields)[VocabCardField.TRANSLATIONS] == FieldVisibility.OFF }
    val wanted = settings.watch { vocabTranslationCodes(it) }
    val enabledIds = settings.watch { s -> s.enabledLanguages.map { it.id } }
    return remember(word, off, wanted, enabledIds) {
        if (off) null else VocabLanguages.primaryMeaning(word, wanted, enabledIds)
    }
}

/** [meaning] as one line: the gloss, its romanisation smaller after it, and the language when it is not obvious. */
@Composable
internal fun VocabMeaningLine(
    meaning: com.wasimaster.wmkeyboard.core.vocab.VocabMeaning,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    maxLines: Int = 2,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val language = languageNameFor(meaning.code)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted, fontSize = 12.sp)) { append("$language  ") }
            append(meaning.text)
            meaning.trail?.let { trail -> withStyle(SpanStyle(color = muted, fontSize = 12.sp)) { append("  ·  $trail") } }
        },
        style = style,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * One quotation from print: the year as a badge, the quote against a thin
 * rule, and the reference stripped to who, what and where.
 */
@Composable
private fun VocabQuotationBlock(quote: VocabQuotation) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(top = 6.dp, start = 4.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
            Text(
                quote.year ?: "·",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.layout.Box(
            Modifier.width(2.dp).fillMaxHeight().padding(vertical = 2.dp).background(
                muted.copy(alpha = 0.35f),
                MaterialTheme.shapes.extraSmall,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("“${quote.text}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            val citation = quote.citation
            if (citation.isNotEmpty()) {
                Text("— $citation", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun VocabLabeledText(label: String, body: String) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

/** A small rounded label: a part of speech, a list badge, a register tag, a step of the origin chain. */
@Composable
private fun VocabTag(text: String, strong: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (strong) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

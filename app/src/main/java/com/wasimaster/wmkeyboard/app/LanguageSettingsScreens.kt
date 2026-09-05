package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.ConnectivityManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryEntry
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryStore
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackDownloadManager
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackEntry
import com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictEntry
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictStore
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinScheme
import com.wasimaster.wmkeyboard.core.input.composer.PinyinFuzzy
import com.wasimaster.wmkeyboard.core.input.composer.HanVariant
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.KeymanBinding
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.prediction.BengaliSpellingMap
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.DeviceLocales
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.LanguageSuggestions
import com.wasimaster.wmkeyboard.core.script.NumeralSystem
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.script.SuggestedLanguage
import com.wasimaster.wmkeyboard.core.script.SuggestionReason
import com.wasimaster.wmkeyboard.core.script.resolveNumeralDigits
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * The screens that make the [LanguageRegistry] reachable: a searchable list to
 * add a language, and a per-language detail with its layouts, secondary
 * suggestion sources, dictionary status and a remove action. They live here
 * rather than in `MainActivity` both to keep that file's churn down and because
 * they are a self-contained feature; the reusable rows (`SettingsGroup`,
 * `NavRow`, `ToggleSetting`, `CaptionText`) are `internal` in the same package.
 *
 * Everything reads the registry, not the old static `LanguageCatalog`: the
 * "Your languages" list is [KeyboardSettings.enabledLanguages] (already deduped
 * in switch order by the repository), and a language's layouts come from
 * [LanguageDef.layoutIds] resolved for their display names.
 */

/**
 * A human label for a language's script, e.g. LATIN → "Latin". Script names are
 * proper nouns, like the language names beside them, so they are not translated.
 */
internal fun scriptLabel(lang: LanguageDef): String =
    lang.script.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }

/**
 * The one-line subtitle a language gets wherever it is offered for adding — here
 * and on the onboarding languages page, which browses the same registry.
 *
 * Composable so the text follows the app's language: it is read while the row is
 * drawn, not cached in a value that outlives a locale change.
 */
@Composable
internal fun languageRowSubtitle(lang: LanguageDef): String =
    if (lang.bundledDictionary) {
        stringResource(R.string.languages_row_subtitle_bundled, scriptLabel(lang))
    } else {
        scriptLabel(lang)
    }

/**
 * One language's search text, lowercased once: endonym, English name, id and
 * locale — so "german", "deutsch", "de" and "de-DE" all find it — plus its
 * layouts' names and ids, so "avro" or "bepo" finds the language that ships
 * that layout for someone who knows the input system but not what the app
 * files it under. An empty term matches everything.
 *
 * There are 843 languages in the registry, and filtering it runs per keystroke
 * in the search box; lowercasing every field on every call built and threw
 * away fourteen hundred strings per letter typed, which is why the strings are
 * built once here. The comparisons are kept field by field rather than
 * concatenated so a query cannot match across a boundary between two names.
 */
internal class LanguageSearchKey(language: LanguageDef) {
    private val displayName = language.displayName.lowercase()
    private val englishName = language.englishName.lowercase()
    private val id = language.id.lowercase()
    private val localeTag = language.localeTag.lowercase()

    /**
     * The layout names ("Avro phonetic", "প্রভাত (Probhat)") and the layout ids
     * minus their `builtin_`/`asset_` prefix ("avro", "fr_bepo"). The prefix is
     * dropped because "builtin" or "asset" would match every language at once.
     */
    private val layouts = language.layoutIds.flatMap { layoutId ->
        listOfNotNull(
            (BuiltInLayouts.byId(layoutId) ?: AssetLayouts.byId(layoutId))?.name?.lowercase(),
            layoutId.substringAfter('_').lowercase(),
        )
    }

    /** [query] must already be trimmed and lowercased. */
    fun matches(query: String): Boolean =
        query.isEmpty() ||
            displayName.contains(query) ||
            englishName.contains(query) ||
            id.contains(query) ||
            localeTag.contains(query) ||
            layouts.any { it.contains(query) }
}

/**
 * Every registry language paired with its search key. The registry is a
 * constant, but the asset layouts' names arrive only once their JSON finishes
 * parsing off the main thread — so the index is memoised on
 * [AssetLayouts.generation] rather than built once, the same way
 * `resolveLayouts` is: an index built before the load would file every asset
 * layout under no name at all, forever.
 */
private object LanguageSearchIndex {
    class Entry(val generation: Int, val index: List<Pair<LanguageDef, LanguageSearchKey>>)

    @Volatile
    var cache: Entry? = null

    fun get(): List<Pair<LanguageDef, LanguageSearchKey>> {
        val generation = AssetLayouts.generation
        cache?.let { if (it.generation == generation) return it.index }
        val built = LanguageRegistry.all.map { it to LanguageSearchKey(it) }
        cache = Entry(generation, built)
        return built
    }
}

/**
 * The registry languages matching an already-trimmed, lowercased [query], in
 * registry order — see [LanguageSearchKey] for what the query is compared
 * against. Shared by the settings and onboarding add-language searches.
 */
internal fun searchLanguages(query: String): List<LanguageDef> =
    if (query.isEmpty()) {
        LanguageRegistry.all
    } else {
        LanguageSearchIndex.get().mapNotNull { (language, key) ->
            language.takeIf { key.matches(query) }
        }
    }

/**
 * The enabled languages, for the one-line summary under the Languages row.
 *
 * Endonyms, in switch order, trimmed to the first few — the row is one line and
 * someone typing in eight languages does not need all eight recited at them.
 */
@Composable
internal fun enabledLanguagesSummary(settings: KeyboardSettings): String {
    val names = settings.enabledLanguages.map { it.displayName.substringBefore(" · ") }
    val shown = names.take(LANGUAGE_SUMMARY_LIMIT).joinToString()
    val rest = names.size - LANGUAGE_SUMMARY_LIMIT
    return when {
        names.isEmpty() -> stringResource(R.string.languages_summary_empty)
        rest > 0 -> pluralStringResource(R.plurals.languages_summary_more, rest, shown, rest)
        else -> shown
    }
}

private const val LANGUAGE_SUMMARY_LIMIT = 3

/**
 * How many suggestions the Languages screen offers before the user has to go
 * through "Add language". Short enough that it reads as a shortcut rather than
 * as a second list.
 */
internal const val LANGUAGE_SCREEN_SUGGESTIONS = 4

/**
 * The languages this device suggests, minus whatever is already enabled.
 *
 * Read once and cached for as long as the screen lives: the phone's language
 * list and SIM don't change mid-screen, and re-reading them on every
 * recomposition would put a `TelephonyManager` call in the middle of a list
 * scroll. The already-enabled set is *not* part of the cache key — a language
 * disappearing from the list the instant it is tapped is the point.
 */
@Composable
internal fun rememberSuggestedLanguages(
    settings: KeyboardSettings,
    limit: Int = LanguageSuggestions.DEFAULT_LIMIT,
): List<SuggestedLanguage> {
    val context = LocalContext.current
    val signals = remember(context) { DeviceLocales.read(context) }
    val enabled = settings.enabledLanguages.mapTo(HashSet()) { it.id }
    return remember(signals, enabled, limit) {
        LanguageSuggestions.suggest(signals, exclude = enabled, limit = limit)
    }
}

/**
 * Why a suggestion is being offered, in one line under its name. Region names
 * come from the platform, so they arrive in the user's own language.
 */
@Composable
internal fun suggestionReasonLabel(suggestion: SuggestedLanguage): String = when (suggestion.reason) {
    SuggestionReason.SYSTEM_LANGUAGE ->
        stringResource(R.string.languages_suggestion_reason_system)
    SuggestionReason.REGION -> {
        val region = suggestion.regionCode
            ?.let { Locale.Builder().setRegion(it).build().displayCountry }
            ?.takeIf { it.isNotBlank() }
        if (region != null) {
            stringResource(R.string.languages_suggestion_reason_region, region)
        } else {
            stringResource(R.string.languages_suggestion_reason_nearby)
        }
    }
    SuggestionReason.FALLBACK -> languageRowSubtitle(suggestion.language)
}

/**
 * The searchable add-language list, over every [LanguageRegistry] entry — see
 * [LanguageSearchKey] for what a search term is compared against. Tapping a
 * not-yet-added language enables its default layout, then opens its detail so
 * the user can pick others or a secondary.
 */
@Composable
internal fun AddLanguageScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onOpenLanguage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val enabledLangIds = remember(settings.enabledLanguages) {
        settings.enabledLanguages.mapTo(HashSet()) { it.id }
    }
    val q = query.trim().lowercase()
    // Both remembered on the query: this whole screen recomposes on every
    // letter typed into the search box, and re-running the filter over 843
    // languages — and rebuilding the enabled-id set — for a query that has not
    // changed is work with no result to show for it.
    val matches = remember(q) { searchLanguages(q) }
    val suggested = rememberSuggestedLanguages(settings)
    // The language is enabled straight away either way; the prompt only decides
    // whether its data comes down now, so answering it is never load-bearing.
    val prompt = rememberLanguageDataPrompt()
    val context = LocalContext.current
    val add: (LanguageDef) -> Unit = { lang ->
        // Nothing is enabled until the dialog is answered, so its Cancel really
        // is a cancel and has nothing to undo.
        prompt.ask(lang) {
            addLanguage(scope, repository, settings, lang, onPaired = { pairs ->
                // One toast even if several links landed: the first pair is
                // the one the user just caused, and the detail screen lists
                // the full truth.
                val (a, b) = pairs.first()
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.languages_auto_pair_toast,
                        LanguageRegistry.byId(a).englishName,
                        LanguageRegistry.byId(b).englishName,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            })
            onOpenLanguage(lang.id)
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.languages_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
    // Only while browsing: once someone is searching, they know what they want
    // and a suggestion block above the results is in the way.
    if (q.isEmpty() && suggested.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.languages_suggested_title),
            info = stringResource(R.string.languages_suggested_info),
        ) {
            for (suggestion in suggested) {
                item {
                    NavRow(
                        suggestion.language.displayName,
                        subtitle = suggestionReasonLabel(suggestion),
                    ) { add(suggestion.language) }
                }
            }
        }
    }
    val allTitle = stringResource(R.string.languages_all_title)
    val addedLabel = stringResource(R.string.languages_added_label)
    SettingsGroup(if (q.isEmpty() && suggested.isNotEmpty()) allTitle else null) {
        for (lang in matches.take(ADD_LANGUAGE_LIMIT)) {
            item {
                val added = lang.id in enabledLangIds
                NavRow(
                    lang.displayName,
                    subtitle = languageRowSubtitle(lang),
                    value = if (added) addedLabel else null,
                ) {
                    if (added) onOpenLanguage(lang.id) else add(lang)
                }
            }
        }
        if (matches.isEmpty()) {
            item { CaptionText(stringResource(R.string.languages_search_empty, query)) }
        } else if (matches.size > ADD_LANGUAGE_LIMIT) {
            val extra = matches.size - ADD_LANGUAGE_LIMIT
            item {
                CaptionText(pluralStringResource(R.plurals.languages_search_more_count, extra, extra))
            }
        }
    }
}

/**
 * Most languages listed at once before the rest are left to the search box.
 *
 * [SettingsGroup] composes every row it is given into a `Column` inside a
 * scrolling parent, so an uncapped list is a full compose-and-measure of the
 * whole registry on the way in. That was tolerable at 352 entries and is not at
 * 843, which is where the Keyman conversion left it.
 *
 * Higher than the onboarding page's 30 because this screen is also where
 * someone browses, and browsing wants more than a screenful. Anyone looking for
 * a specific language types, and typing is what the caption points at.
 */
private const val ADD_LANGUAGE_LIMIT = 80

/**
 * A cluster the reader will recognise, for the conjunct-backspace row. A script
 * with no sample here still gets the setting; the row just describes it in words
 * rather than showing one, which beats showing a Bengali cluster to someone
 * setting up Khmer. Null means "no sample": the caller puts
 * `languages_conjunct_sample_fallback` in its place.
 */
private fun conjunctSample(script: ScriptId): String? = when (script) {
    ScriptId.BENGALI -> "ক্ষ"
    ScriptId.DEVANAGARI -> "क्ष"
    ScriptId.GURMUKHI -> "ਕ੍ਸ਼"
    ScriptId.GUJARATI -> "ક્ષ"
    ScriptId.ORIYA -> "କ୍ଷ"
    ScriptId.TAMIL -> "க்ஷ"
    ScriptId.TELUGU -> "క్ష"
    ScriptId.KANNADA -> "ಕ್ಷ"
    ScriptId.MALAYALAM -> "ക്ഷ"
    ScriptId.SINHALA -> "ක්ෂ"
    ScriptId.KHMER -> "ក្ស"
    ScriptId.MYANMAR -> "က္ခ"
    ScriptId.TIBETAN -> "ཀྵ"
    // Every script with nothing to show, listed rather than left to an `else`
    // so a newly added ScriptId has to come here and pick a side. Most have no
    // conjuncts at all; the few Brahmic ones here have no cluster recognisable
    // enough to teach the setting, which is what the fallback wording is for.
    ScriptId.LATIN, ScriptId.CYRILLIC, ScriptId.GREEK, ScriptId.ARMENIAN, ScriptId.GEORGIAN, ScriptId.ARABIC, ScriptId.HEBREW,
    ScriptId.SYRIAC, ScriptId.THAI, ScriptId.LAO, ScriptId.HANGUL, ScriptId.ETHIOPIC, ScriptId.THAANA, ScriptId.JAPANESE, ScriptId.HAN,
    ScriptId.IPA, ScriptId.TIFINAGH, ScriptId.CHEROKEE, ScriptId.NKO, ScriptId.CANADIAN_ABORIGINAL_SYLLABICS, ScriptId.OL_CHIKI,
    ScriptId.MEETEI_MAYEK, ScriptId.TAI_LE, ScriptId.VAI, ScriptId.OSAGE, ScriptId.ADLAM, ScriptId.AHOM, ScriptId.AVESTAN,
    ScriptId.BALINESE, ScriptId.BAMUM, ScriptId.BASSA_VAH, ScriptId.BATAK, ScriptId.BHAIKSUKI, ScriptId.BOPOMOFO, ScriptId.BRAHMI,
    ScriptId.BUGINESE, ScriptId.BUHID, ScriptId.CAUCASIAN_ALBANIAN, ScriptId.CHAKMA, ScriptId.CHAM, ScriptId.COPTIC, ScriptId.CYPRIOT,
    ScriptId.CYPRO_MINOAN, ScriptId.DESERET, ScriptId.DIVES_AKURU, ScriptId.DOGRA, ScriptId.ELBASAN, ScriptId.GLAGOLITIC,
    ScriptId.GOTHIC, ScriptId.GRANTHA, ScriptId.GUNJALA_GONDI, ScriptId.HANIFI_ROHINGYA, ScriptId.HANUNOO, ScriptId.HATRAN,
    ScriptId.INSCRIPTIONAL_PAHLAVI, ScriptId.INSCRIPTIONAL_PARTHIAN, ScriptId.JAVANESE, ScriptId.KAITHI, ScriptId.KAWI,
    ScriptId.KAYAH_LI, ScriptId.KHAROSHTHI, ScriptId.KHOJKI, ScriptId.KHUDAWADI, ScriptId.KIRAT_RAI, ScriptId.LEPCHA, ScriptId.LIMBU,
    ScriptId.LINEAR_B, ScriptId.LISU, ScriptId.LYCIAN, ScriptId.LYDIAN, ScriptId.MAHAJANI, ScriptId.MAKASAR, ScriptId.MANDAIC,
    ScriptId.MANICHAEAN, ScriptId.MARCHEN, ScriptId.MASARAM_GONDI, ScriptId.MEDEFAIDRIN, ScriptId.MENDE_KIKAKUI,
    ScriptId.MEROITIC_CURSIVE, ScriptId.MEROITIC_HIEROGLYPHS, ScriptId.MIAO, ScriptId.MODI, ScriptId.MONGOLIAN, ScriptId.MRO,
    ScriptId.MULTANI, ScriptId.NABATAEAN, ScriptId.NAG_MUNDARI, ScriptId.NANDINAGARI, ScriptId.NEWA, ScriptId.NEW_TAI_LUE,
    ScriptId.NYIAKENG_PUACHUE_HMONG, ScriptId.OGHAM, ScriptId.OLD_HUNGARIAN, ScriptId.OLD_ITALIC, ScriptId.OLD_PERMIC,
    ScriptId.OLD_PERSIAN, ScriptId.OLD_SOGDIAN, ScriptId.OLD_SOUTH_ARABIAN, ScriptId.OLD_UYGHUR, ScriptId.OSMANYA,
    ScriptId.PAHAWH_HMONG, ScriptId.PALMYRENE, ScriptId.PAU_CIN_HAU, ScriptId.PHAGS_PA, ScriptId.PHOENICIAN, ScriptId.PSALTER_PAHLAVI,
    ScriptId.REJANG, ScriptId.RUNIC, ScriptId.SAMARITAN, ScriptId.SAURASHTRA, ScriptId.SHARADA, ScriptId.SHAVIAN, ScriptId.SIDDHAM,
    ScriptId.SOGDIAN, ScriptId.SORA_SOMPENG, ScriptId.SOYOMBO, ScriptId.SUNDANESE, ScriptId.SYLOTI_NAGRI, ScriptId.TAGALOG,
    ScriptId.TAGBANWA, ScriptId.TAI_THAM, ScriptId.TAI_VIET, ScriptId.TAKRI, ScriptId.TIRHUTA, ScriptId.TODHRI, ScriptId.TOTO,
    ScriptId.UGARITIC, ScriptId.VITHKUQI, ScriptId.YEZIDI, ScriptId.YI, ScriptId.ZANABAZAR_SQUARE, ScriptId.MUSIC, ScriptId.BRAILLE,
    -> null
}

/**
 * What a language can download: its word list, its emoji keywords and its
 * n-gram pack, with a rough size for all three together. Any of them may be
 * missing — most languages have a word list, only 125 have keywords, and two
 * so far have an n-gram pack.
 */
internal data class LanguageData(
    val wordlist: DictionaryEntry?,
    val emojiDict: EmojiDictEntry?,
    val ngram: NgramPackEntry?,
    val bytes: Long,
) {
    val isEmpty: Boolean get() = wordlist == null && emojiDict == null && ngram == null
}

/** Everything downloadable for [langId], sized for a prompt. */
internal fun languageData(langId: String): LanguageData {
    val lists = DictionaryCatalog.forLanguage(langId)
    // Where a language has several lists (Portuguese), the one whose id is the
    // language itself is its default; the other is the regional variant.
    val wordlist = lists.firstOrNull { it.id == langId } ?: lists.firstOrNull()
    val emojiDict = EmojiDictCatalog.forLanguage(langId)
    val ngram = NgramPackCatalog.forLanguage(langId)
    // The catalogue sizes the whole file, and a capped download stops partway
    // through it, so scale by the share of the list actually read.
    val wordlistBytes = wordlist?.let {
        val cap = DictionaryCatalog.wordCap(it, AUTO_DOWNLOAD_SIZE)
        it.approxGzBytes * cap / it.totalWordCount.coerceAtLeast(1)
    } ?: 0L
    return LanguageData(
        wordlist = wordlist,
        emojiDict = emojiDict,
        ngram = ngram,
        bytes = wordlistBytes + (emojiDict?.approxGzBytes ?: 0L) + (ngram?.approxGzBytes ?: 0L),
    )
}


/**
 * Whether the connection in use right now is one Android counts as metered.
 *
 * Assumes not metered when the service is missing, which is the same direction
 * the platform errs: a wrong "metered" would block a download on a connection
 * that costs nothing, and the user has no way to see why.
 */
internal fun isMeteredNow(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

/**
 * Fetches everything [data] offers, as the prompt's Download does.
 *
 * All three managers queue internally and skip what is already on disk, so
 * this is safe to call for a language that is half downloaded already.
 */
internal fun startLanguageDataDownload(filesDir: File, data: LanguageData) {
    data.wordlist?.let { WordlistDownloadManager.start(filesDir, it, AUTO_DOWNLOAD_SIZE) }
    data.emojiDict?.let { EmojiDictDownloadManager.start(filesDir, it) }
    data.ngram?.let { NgramPackDownloadManager.start(filesDir, it.languageId) }
}

/**
 * How much of a word list the add-a-language prompt fetches. The largest fixed
 * tier rather than [DictionaryCatalog.DictionarySize.ALL]: this download is one
 * tap on a dialog, so it should not be the one that pulls four million words.
 */
private val AUTO_DOWNLOAD_SIZE = DictionaryCatalog.DictionarySize.LARGE

/** Bytes a language's downloaded files are taking up right now. */
private fun downloadedLanguageBytes(filesDir: File, langId: String): Long =
    DictionaryStore.downloadedFile(filesDir, langId).length() +
        EmojiDictStore.packFile(filesDir, langId).length()

/**
 * The download prompt, hoisted so every screen that can add a language asks the
 * same question in the same words — the add-language list, the shortlist on the
 * Languages screen and the setup wizard all go through this.
 *
 * Returns the holder; the dialog itself is rendered here, when there is one to
 * render. See [LanguageDataPrompt.ask] for how a row uses it.
 */
@Composable
internal fun rememberLanguageDataPrompt(): LanguageDataPrompt {
    val filesDir = LocalContext.current.filesDir
    val prompt = remember { LanguageDataPrompt() }
    prompt.pending?.let { (language, proceed) ->
        val data = remember(language.id) { languageData(language.id) }
        LanguageDataDownloadDialog(
            language = language,
            data = data,
            onCancel = { prompt.dismiss() },
            onSkip = {
                prompt.dismiss()
                proceed()
            },
            onDownload = {
                startLanguageDataDownload(filesDir, data)
                prompt.dismiss()
                proceed()
            },
        )
    }
    return prompt
}

/**
 * State for [rememberLanguageDataPrompt]: which language is being asked about,
 * and what to do once the question is answered.
 *
 * The "what to do" is per call rather than fixed on the holder because the
 * screens differ on it — one opens the language it just added, one clears its
 * search box, and the wizard's layout picker has already enabled a specific
 * layout by the time the prompt is asked.
 */
@Stable
internal class LanguageDataPrompt {

    /** The open dialog's language and its continuation, or null for none. */
    var pending by mutableStateOf<Pair<LanguageDef, () -> Unit>?>(null)
        private set

    /**
     * Asks whether to download [language]'s data, then runs [proceed] — which
     * is where the caller actually adds the language, on either answer.
     *
     * A language with nothing to download never shows the dialog: an empty
     * question is worse than no question.
     */
    fun ask(language: LanguageDef, proceed: () -> Unit) {
        if (languageData(language.id).isEmpty) proceed() else pending = language to proceed
    }

    fun dismiss() {
        pending = null
    }
}

/**
 * Asks once, as a language is added, whether to fetch the data that makes it
 * work properly. The alternative to asking is either a silent download on a
 * metered connection or a language that quietly predicts nothing.
 *
 * Three answers, because the dialog is the first thing between tapping a name
 * in a long list and having a new language: [onDownload] and [onSkip] both add
 * it, [onCancel] backs out of the whole thing for the tap that was a mistake.
 * All three are laid out in one flow row so a long label wraps instead of
 * pushing "Download" off the edge.
 */
@Composable
private fun LanguageDataDownloadDialog(
    language: LanguageDef,
    data: LanguageData,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.languages_data_download_title)) },
        text = {
            Text(
                stringResource(
                    R.string.languages_data_download_body,
                    language.displayName,
                    formatBytes(data.bytes),
                ),
            )
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.languages_data_download_dismiss_action))
                }
                TextButton(onClick = onDownload) {
                    Text(stringResource(CommonR.string.common_download))
                }
            }
        },
    )
}

/** The mirror of [LanguageDataDownloadDialog], for a language on its way out. */
@Composable
private fun LanguageDataDeleteDialog(
    language: LanguageDef,
    bytes: Long,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.languages_data_delete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.languages_data_delete_body,
                    language.displayName,
                    formatBytes(bytes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(CommonR.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text(stringResource(R.string.languages_data_delete_dismiss_action))
            }
        },
    )
}

/**
 * Adds a language by enabling its first layout. The rest of its layouts, and any
 * secondary suggestion sources, are then a tap away on its detail screen — which
 * is where every caller sends the user next.
 *
 * Adding is also the moment romanized languages get cross-wired with their
 * same-script company (see [RomanizedPairing]): [onPaired] fires with the new
 * links so the Languages screen can toast them, while onboarding leaves it at
 * its silent default.
 */
internal fun addLanguage(
    scope: CoroutineScope,
    repository: SettingsRepository,
    settings: KeyboardSettings,
    language: LanguageDef,
    onPaired: (List<Pair<String, String>>) -> Unit = {},
) {
    val first = language.layoutIds.firstOrNull() ?: return
    scope.launch {
        repository.setEnabledLayoutIds((settings.enabledLayoutIds + first).distinct())
        val paired = repository.autoPairRomanizedSecondaries()
        if (paired.isNotEmpty()) onPaired(paired)
    }
}

/**
 * The route of a language's [MoreLayoutsScreen]. One function so the row that
 * opens it and the graph that declares it cannot drift apart.
 */
internal fun moreLayoutsRoute(langId: String): String = "language/$langId/more"

/**
 * The layouts of [lang] that the detail screen sends to [MoreLayoutsScreen]:
 * converted Keyman grids the user has not switched on.
 *
 * The Keyman conversion added 862 grids to languages that mostly had one or
 * three, and a language like Amharic or Arabic then opened on a wall of names
 * with its own layouts buried at the top of it. An enabled one stays on the
 * detail screen whatever its origin — a screen that lists what this language
 * types must not hide half of the answer behind a tap.
 */
private fun overflowLayoutIds(lang: LanguageDef, settings: KeyboardSettings): List<String> =
    lang.layoutIds.filter { id ->
        id !in settings.enabledLayoutIds &&
            resolveLayout(settings.customLayouts, id).keyman != null
    }

/**
 * A toggle per layout, and under each converted Keyman layout the row that
 * fetches its typing rules.
 *
 * An extension on the group scope rather than a composable of its own, so the
 * detail screen and [MoreLayoutsScreen] draw the same rows into their own cards
 * without one of them owning the other's. The `keyman` test stays outside
 * `item` because an item that composes nothing still takes a slot in the card.
 */
private fun SettingsGroupScope.layoutRows(
    layoutIds: List<String>,
    settings: KeyboardSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    enableGate: (String, () -> Unit) -> Unit,
    promptForRules: (KeymanBinding, String) -> Unit,
    rulesRefresh: Int,
) {
    for (layoutId in layoutIds) {
        val spec = resolveLayout(settings.customLayouts, layoutId)
        item {
            ToggleSetting(
                spec.name,
                null,
                layoutId in settings.enabledLayoutIds,
                default = layoutId in SettingsDefaults.enabledLayoutIds,
            ) { enable ->
                fun write() {
                    scope.launch {
                        val next =
                            if (enable) settings.enabledLayoutIds + layoutId
                            else settings.enabledLayoutIds - layoutId
                        // At least one layout must stay enabled somewhere.
                        if (next.isNotEmpty()) repository.setEnabledLayoutIds(next.distinct())
                    }
                    // Asked at the moment of switching on, because that is
                    // when the user is deciding to type in this language.
                    // Finding out later that the keys produce Latin letters
                    // is the outcome this exists to prevent.
                    if (enable) spec.keyman?.let { promptForRules(it, spec.name) }
                }
                if (enable) enableGate(layoutId) { write() } else write()
            }
        }
        // A converted Keyman layout can only type what its author wrote once
        // its rules are on the device, so the row that fetches them sits
        // directly under the layout it belongs to, and names it.
        spec.keyman?.let { binding ->
            item { KeymanRulesRow(binding, spec.name, rulesRefresh) }
        }
    }
}

/**
 * The Layouts group for one language: the layouts we wrote for it plus whichever
 * converted ones are switched on, and a row down to the rest.
 *
 * Split out of [LanguageDetailScreen] rather than left inline. That function was
 * already past detekt's complexity ceiling before the Keyman rows went in, and
 * adding a prompt and a refresh counter to it made a bad number worse; this is
 * the piece that grew, so this is the piece that moves.
 */
@Composable
private fun LayoutsGroup(
    lang: LanguageDef,
    settings: KeyboardSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    onNavigate: (String) -> Unit,
) {
    val enableGate = rememberLayoutEnableGate(settings)
    // Bumped when the enable prompt installs rules, so the row underneath stops
    // offering to download what it just got.
    var rulesRefresh by remember { mutableStateOf(0) }
    val promptForRules = rememberKeymanRulesPrompt { rulesRefresh++ }

    val overflow = overflowLayoutIds(lang, settings)
    val listed = lang.layoutIds - overflow.toSet()

    SettingsGroup(stringResource(R.string.languages_layouts_title)) {
        layoutRows(listed, settings, repository, scope, enableGate, promptForRules, rulesRefresh)
        if (overflow.isNotEmpty()) {
            item {
                NavRow(
                    R.string.languages_more_layouts_title,
                    subtitle = pluralStringResource(
                        R.plurals.languages_more_layouts_subtitle,
                        overflow.size,
                        overflow.size,
                    ),
                    route = moreLayoutsRoute(lang.id),
                ) { onNavigate(moreLayoutsRoute(lang.id)) }
            }
        }
    }
}

/**
 * One language's converted Keyman layouts, a tap off its detail screen.
 *
 * Lists every Keyman grid the language has, not only the ones the detail screen
 * left behind: this page is the catalogue, and a layout vanishing from it the
 * moment it is switched on would read as the toggle having deleted something.
 * The overlap is two rows for one setting, which both write the same value.
 *
 * Named "More layouts" rather than "Keyman layouts" because the name has to mean
 * something to a user who has never heard of Keyman, and the description says
 * where they come from for the user who has.
 */
@Composable
internal fun MoreLayoutsScreen(
    langId: String,
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val lang = LanguageRegistry.byId(langId)
    val enableGate = rememberLayoutEnableGate(settings)
    var rulesRefresh by remember { mutableStateOf(0) }
    val promptForRules = rememberKeymanRulesPrompt { rulesRefresh++ }

    val layoutIds = lang.layoutIds.filter {
        resolveLayout(settings.customLayouts, it).keyman != null
    }

    // Guarded, or a language reached with no converted layouts at all — a stale
    // deep link, a build that dropped them — draws a description of nothing.
    if (layoutIds.isEmpty()) return
    CaptionText(stringResource(R.string.languages_more_layouts_body))
    SettingsGroup(stringResource(R.string.languages_layouts_title)) {
        layoutRows(layoutIds, settings, repository, scope, enableGate, promptForRules, rulesRefresh)
    }
}

/**
 * One language: toggle its layouts on/off (at least one layout overall must stay
 * enabled), pick other enabled languages as secondary suggestion sources, see
 * its dictionary status, and remove it. [onRemoved] pops back to the list once
 * the language is gone.
 */
@Composable
internal fun LanguageDetailScreen(
    langId: String,
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
    onRemoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val filesDir = LocalContext.current.filesDir
    val lang = LanguageRegistry.byId(langId)
    var pendingDelete by remember { mutableStateOf(false) }

    val removeLanguage: () -> Unit = {
        scope.launch {
            val next = settings.enabledLayoutIds.filterNot {
                resolveLayout(settings.customLayouts, it).language().id == langId
            }
            if (next.isNotEmpty()) {
                repository.setEnabledLayoutIds(next.distinct())
                onRemoved()
            }
        }
    }

    if (pendingDelete) {
        LanguageDataDeleteDialog(
            language = lang,
            bytes = downloadedLanguageBytes(filesDir, langId),
            // Either answer removes the language: the dialog is about the
            // files on disk, not about the removal the user already asked for.
            onKeep = {
                pendingDelete = false
                removeLanguage()
            },
            onDelete = {
                pendingDelete = false
                DictionaryStore.delete(filesDir, langId)
                WordlistDownloadManager.refresh(filesDir)
                // Leaves a "declined" mark behind, which is what stops the
                // automatic pass fetching the keywords straight back.
                EmojiDictDownloadManager.delete(filesDir, langId)
                removeLanguage()
            },
        )
    }

    // A shipped layout always validates, but an *edited* one is stored under the
    // same id and resolves in its place, so this list can hold a broken layout
    // too. Same gate as the custom list; switching off is never gated.
    LayoutsGroup(lang, settings, repository, scope, onNavigate)

    // Fancy Text: the style the one fancy layout draws and types. The strip
    // over the keys switches it too; this row makes it discoverable from
    // Settings and readable as a plain list.
    if (langId == "fancy") {
        SettingsGroup(stringResource(R.string.languages_fancy_style_title)) {
            item {
                ChoiceSetting(
                    R.string.languages_fancy_style_row_title,
                    subtitle = stringResource(R.string.languages_fancy_style_row_subtitle),
                    options = FancyStyles.all.map { it to it.sample },
                    selected = FancyStyles.byId(settings.layoutBehavior.fancyStyleId)
                        ?: FancyStyles.all.first(),
                    default = FancyStyles.byId(SettingsDefaults.layoutBehavior.fancyStyleId)
                        ?: FancyStyles.all.first(),
                ) { scope.launch { repository.setFancyStyle(it.id) } }
            }
        }
    }

    // Cluster deletion, for the languages whose script has clusters to delete.
    // Per language for the same reason numerals are: someone typing Bengali and
    // Hindi together may well want whole conjuncts gone in one and code points
    // in the other, and a single global switch made that impossible.
    if (ScriptRegistry[lang.script].composer == ComposerType.INDIC_CLUSTER) {
        SettingsGroup(stringResource(R.string.languages_clusters_title)) {
            item {
                val sample = conjunctSample(lang.script)
                    ?: stringResource(R.string.languages_conjunct_sample_fallback)
                ToggleSetting(
                    R.string.languages_conjunct_backspace_title,
                    stringResource(R.string.languages_conjunct_backspace_subtitle, sample),
                    langId in settings.conjunctBackspaceLanguages,
                    info = stringResource(
                        R.string.languages_conjunct_backspace_info,
                        lang.englishName,
                    ),
                    default = langId in SettingsDefaults.conjunctBackspaceLanguages,
                ) { scope.launch { repository.setConjunctBackspace(langId, it) } }
            }
        }
    }

    // Fixed spellings, for the languages that ship the lists. On by default:
    // it is what makes "table" commit টেবিল rather than তাবলে, and "tmr" তোমার
    // rather than ত্ম্র. Off is for the person who wants the letter-for-letter
    // reading and cannot otherwise get it, since the map outranks every other
    // suggestion source.
    if (langId in BengaliSpellingMap.LANGUAGES) {
        SettingsGroup(stringResource(R.string.languages_spelling_map_title)) {
            item {
                ToggleSetting(
                    R.string.languages_spelling_map_row_title,
                    stringResource(R.string.languages_spelling_map_row_subtitle),
                    settings.suggestionStrip.spellingMapEnabledFor(langId),
                    info = stringResource(
                        R.string.languages_spelling_map_info,
                        lang.englishName,
                    ),
                    default = SettingsDefaults.suggestionStrip.spellingMapEnabledFor(langId),
                ) { scope.launch { repository.setSpellingMapEnabled(langId, it) } }
            }
        }
    }

    // Numerals are per language: Arabic can type ٠-٩ while English beside it
    // stays 0-9. Two options only — this language's own digits (stored as
    // [NumeralSystem.AUTO], which follows the language) or 0-9. The full list of
    // systems was there before and made no sense to offer: nobody writing
    // Bengali wants Persian digits, and every extra button was one more way to
    // get the keyboard into a state the user did not mean. A language whose own
    // digits are 0-9 has nothing to choose, so it gets no row at all.
    val nativeNumerals = lang.numeralSystem
    if (nativeNumerals != NumeralSystem.LATIN) {
        SettingsGroup(stringResource(R.string.languages_numerals_title)) {
            item {
                val current = settings.layoutBehavior.numeralSystemFor(langId)
                ChoiceSetting(
                    R.string.languages_numeral_system_title,
                    subtitle = stringResource(
                        R.string.languages_numeral_system_subtitle,
                        lang.englishName,
                    ),
                    info = stringResource(
                        R.string.languages_numeral_system_info,
                        lang.englishName,
                        stringResource(nativeNumerals.labelRes),
                    ),
                    options = listOf(
                        NumeralSystem.AUTO to stringResource(nativeNumerals.labelRes),
                        NumeralSystem.LATIN to stringResource(NumeralSystem.LATIN.labelRes),
                    ),
                    // A value an older build stored (say Persian digits under
                    // Bengali) is not one of the two buttons. It reads as
                    // whichever of them it behaves like, so the row still shows
                    // the truth and one press writes a value from this list.
                    selected = if (resolveNumeralDigits(current, lang) == null) {
                        NumeralSystem.LATIN
                    } else {
                        NumeralSystem.AUTO
                    },
                    default = SettingsDefaults.layoutBehavior.numeralSystemFor(langId),
                ) { scope.launch { repository.setNumeralSystemForLanguage(langId, it) } }
            }
        }
    }

    val others = settings.enabledLanguages.filter { it.id != langId }
    if (others.isNotEmpty()) {
        val secondaries = settings.secondaryLanguages[langId].orEmpty()
        SettingsGroup(
            stringResource(R.string.languages_secondary_title),
            info = stringResource(R.string.languages_secondary_info, lang.englishName),
        ) {
            for (other in others) {
                item {
                    ToggleSetting(other.displayName, null, other.id in secondaries) { on ->
                        scope.launch {
                            val cur = settings.secondaryLanguages[langId].orEmpty()
                            val nextList = if (on) cur + other.id else cur - other.id
                            repository.setSecondaryLanguages(
                                settings.secondaryLanguages + (langId to nextList.distinct()),
                            )
                        }
                    }
                }
            }
        }
    }

    // One tap for the whole set: the word list, the emoji keywords and the
    // n-gram pack together. Here for the language that was added before this
    // screen existed, and for the one added with automatic downloads off — the
    // rows below still fetch them one at a time. The size is on the button
    // because pressing it is the consent.
    val downloadable = remember(langId) { languageData(langId) }
    val downloadDecision = rememberDownloadDecision(settings)
    var confirmMetered by remember { mutableStateOf(false) }
    var blockedMetered by remember { mutableStateOf(false) }
    if (!downloadable.isEmpty) {
        SettingsGroup(
            stringResource(R.string.languages_data_title),
            info = stringResource(R.string.languages_data_download_all_info),
        ) {
            item {
                OutlinedButton(
                    onClick = {
                        when (downloadDecision()) {
                            MeteredDecision.ASK -> confirmMetered = true
                            MeteredDecision.BLOCKED -> blockedMetered = true
                            MeteredDecision.ALLOWED ->
                                startLanguageDataDownload(filesDir, downloadable)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.languages_data_download_all_action,
                            formatBytes(downloadable.bytes),
                        ),
                    )
                }
            }
        }
    }
    if (confirmMetered) {
        AlertDialog(
            onDismissRequest = { confirmMetered = false },
            title = { Text(stringResource(R.string.languages_metered_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.languages_metered_confirm_body,
                        formatBytes(downloadable.bytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMetered = false
                    startLanguageDataDownload(filesDir, downloadable)
                }) { Text(stringResource(CommonR.string.common_download)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmMetered = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
    if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }

    val wordlistEntries = DictionaryCatalog.forLanguage(langId)
    SettingsGroup(stringResource(R.string.languages_dictionary_title)) {
        item {
            CaptionText(
                stringResource(
                    when {
                        lang.bundledDictionary && wordlistEntries.isNotEmpty() ->
                            R.string.languages_dictionary_bundled_more
                        lang.bundledDictionary -> R.string.languages_dictionary_bundled
                        wordlistEntries.isNotEmpty() ->
                            R.string.languages_dictionary_none_download
                        else -> R.string.languages_dictionary_none_learn
                    },
                ),
            )
        }
        for (entry in wordlistEntries) {
            item { WordlistRow(entry, settings.appUi.defaultWordlistSize) { chosen ->
                scope.launch { repository.setDefaultWordlistSize(chosen) }
            } }
        }
        item {
            NavRow(
                R.string.languages_custom_dictionaries_title,
                stringResource(R.string.languages_custom_dictionaries_subtitle),
                route = "customdictionaries",
            ) {
                onNavigate("customdictionaries")
            }
        }
    }

    val emojiDict = EmojiDictCatalog.forLanguage(langId)
    SettingsGroup(stringResource(R.string.languages_emoji_title)) {
        item {
            CaptionText(
                stringResource(
                    when {
                        emojiDict == null -> R.string.languages_emoji_keywords_unavailable
                        settings.autoDownloadLanguageData && settings.emoji.autoDownloadKeywords ->
                            R.string.languages_emoji_keywords_auto
                        else -> R.string.languages_emoji_keywords_manual
                    },
                    lang.englishName,
                ),
            )
        }
        if (emojiDict != null) {
            item { EmojiDictRow(emojiDict) }
        }
        item {
            NavRow(
                R.string.languages_emoji_keywords_title,
                stringResource(R.string.languages_emoji_keywords_subtitle),
                route = "emojikeywords",
            ) {
                onNavigate("emojikeywords")
            }
        }
    }

    // Chinese/Japanese get a downloadable large conversion dictionary; Chinese
    // also gets fuzzy + Double Pinyin, all in one "… options" group.
    if (CjkDictCatalog.forLang(langId).isNotEmpty()) {
        CjkDictPackManager(langId, repository, settings)
    }

    // Removing the only language would leave nothing to type in, so it is only
    // offered when another language is enabled.
    if (settings.enabledLanguages.size > 1) {
        SettingsGroup {
            item {
                OutlinedButton(
                    onClick = {
                        val downloaded = DictionaryStore.isDownloaded(filesDir, langId) ||
                            EmojiDictStore.isDownloaded(filesDir, langId)
                        if (downloaded) pendingDelete = true else removeLanguage()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.languages_remove_action, lang.englishName)) }
            }
        }
    }
}

/**
 * Download/delete row for one language's emoji dictionary.
 *
 * Simpler than [WordlistRow] because the payload is: no size tier (the whole
 * file is ~100 KB), and a determinate progress bar, since nothing stops the
 * transfer early. Shared with the Emoji keywords screen, so a language shows
 * the same state wherever it is looked at.
 */
@Composable
internal fun EmojiDictRow(entry: EmojiDictEntry) {
    val filesDir = LocalContext.current.filesDir
    val states by EmojiDictDownloadManager.states.collectAsState()
    LaunchedEffect(entry.languageId) { EmojiDictDownloadManager.refresh(filesDir) }
    val status = states[entry.languageId]
        ?: EmojiDictDownloadManager.DownloadStatus.NotDownloaded

    WmRow(
        title = stringResource(R.string.languages_emoji_keywords_title),
        supporting = {
            Text(
                when (status) {
                    is EmojiDictDownloadManager.DownloadStatus.Downloaded -> pluralStringResource(
                        R.plurals.languages_emoji_dict_downloaded,
                        status.emojiCount,
                        status.emojiCount,
                        formatBytes(status.sizeBytes),
                    )
                    EmojiDictDownloadManager.DownloadStatus.Queued ->
                        stringResource(R.string.languages_download_queued)
                    is EmojiDictDownloadManager.DownloadStatus.Downloading ->
                        stringResource(CommonR.string.common_downloading)
                    is EmojiDictDownloadManager.DownloadStatus.Failed ->
                        if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                        else stringResource(status.messageRes, status.messageArg)
                    EmojiDictDownloadManager.DownloadStatus.NotDownloaded -> pluralStringResource(
                        R.plurals.languages_emoji_dict_download_size,
                        entry.emojiCount,
                        entry.emojiCount,
                        formatBytes(entry.approxGzBytes),
                    )
                },
                color = if (status is EmojiDictDownloadManager.DownloadStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    androidx.compose.ui.graphics.Color.Unspecified
                },
            )
        },
        trailing = {
            when (status) {
                is EmojiDictDownloadManager.DownloadStatus.Downloaded ->
                    IconButton(
                        onClick = { EmojiDictDownloadManager.delete(filesDir, entry.languageId) },
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(
                                R.string.languages_emoji_dict_delete_desc,
                            ),
                        )
                    }
                EmojiDictDownloadManager.DownloadStatus.Queued,
                is EmojiDictDownloadManager.DownloadStatus.Downloading,
                ->
                    IconButton(
                        onClick = { EmojiDictDownloadManager.cancel(entry.languageId) },
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(
                                R.string.languages_cancel_download_desc,
                            ),
                        )
                    }
                EmojiDictDownloadManager.DownloadStatus.NotDownloaded,
                is EmojiDictDownloadManager.DownloadStatus.Failed,
                -> TextButton(onClick = { EmojiDictDownloadManager.start(filesDir, entry) }) {
                    Text(
                        if (status is EmojiDictDownloadManager.DownloadStatus.Failed) {
                            stringResource(CommonR.string.common_retry)
                        } else {
                            stringResource(CommonR.string.common_download)
                        },
                    )
                }
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    val downloading = status as? EmojiDictDownloadManager.DownloadStatus.Downloading
    if (downloading != null) {
        LinearProgressIndicator(
            progress = {
                if (downloading.totalBytes > 0) {
                    (downloading.bytes.toFloat() / downloading.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * Download/delete row for one [DictionaryCatalog] wordlist, driven by the
 * process-level [WordlistDownloadManager] so progress survives navigation
 * (same pattern as the Whisper model rows). Before downloading, the trailing
 * dropdown picks how many of the most frequent words to keep — the choice is
 * a download parameter, not a setting; it is recorded in the file itself.
 */
@Composable
private fun WordlistRow(
    entry: DictionaryEntry,
    defaultSize: DictionaryCatalog.DictionarySize,
    onDefaultSizeChange: (DictionaryCatalog.DictionarySize) -> Unit,
) {
    val filesDir = LocalContext.current.filesDir
    val states by WordlistDownloadManager.states.collectAsState()
    LaunchedEffect(entry.id) { WordlistDownloadManager.refresh(filesDir) }
    val status = states[entry.id] ?: WordlistDownloadManager.DownloadStatus.NotDownloaded
    // Seeded from the stored default and written back on every pick, so the
    // choice carries to the next language and survives leaving the screen. It
    // used to be plain composition state that reset to LARGE every time.
    var size by remember(defaultSize) { mutableStateOf(defaultSize) }
    var sizeMenu by remember { mutableStateOf(false) }
    val effectiveWords = DictionaryCatalog.wordCap(entry, size)
    // Tiers past the end of a short list all keep the same words, so only the
    // first one that reaches the whole list is worth offering.
    val sizeOptions = remember(entry.totalWordCount) {
        DictionaryCatalog.DictionarySize.entries.distinctBy { DictionaryCatalog.wordCap(entry, it) }
    }

    WmRow(
        title = entry.variantRes?.let {
            stringResource(R.string.languages_wordlist_title_variant, stringResource(it))
        } ?: stringResource(R.string.languages_wordlist_title),
        supporting = {
            Text(
                when (status) {
                    is WordlistDownloadManager.DownloadStatus.Downloaded -> pluralStringResource(
                        R.plurals.languages_wordlist_downloaded,
                        status.wordCount,
                        status.wordCount,
                        formatBytes(status.sizeBytes),
                    )
                    WordlistDownloadManager.DownloadStatus.Processing ->
                        stringResource(R.string.languages_wordlist_preparing)
                    is WordlistDownloadManager.DownloadStatus.Downloading ->
                        stringResource(CommonR.string.common_downloading)
                    is WordlistDownloadManager.DownloadStatus.Failed ->
                        if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                        else stringResource(status.messageRes, status.messageArg)
                    WordlistDownloadManager.DownloadStatus.NotDownloaded -> pluralStringResource(
                        R.plurals.languages_wordlist_word_count,
                        effectiveWords,
                        effectiveWords,
                    )
                },
                color = if (status is WordlistDownloadManager.DownloadStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    androidx.compose.ui.graphics.Color.Unspecified
                },
            )
        },
        trailing = {
            when (status) {
                is WordlistDownloadManager.DownloadStatus.Downloaded ->
                    IconButton(onClick = { WordlistDownloadManager.delete(filesDir, entry) }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(
                                R.string.languages_wordlist_delete_desc,
                            ),
                        )
                    }
                is WordlistDownloadManager.DownloadStatus.Downloading,
                WordlistDownloadManager.DownloadStatus.Processing,
                ->
                    IconButton(onClick = { WordlistDownloadManager.cancel() }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(
                                R.string.languages_cancel_download_desc,
                            ),
                        )
                    }
                WordlistDownloadManager.DownloadStatus.NotDownloaded,
                is WordlistDownloadManager.DownloadStatus.Failed,
                -> Row {
                    // Hide the size picker when the whole list fits the
                    // smallest tier anyway.
                    if (entry.totalWordCount > DictionaryCatalog.DictionarySize.SMALL.wordCap) {
                        TextButton(
                            onClick = { sizeMenu = true },
                            enabled = !WordlistDownloadManager.isBusy,
                        ) {
                            Text(stringResource(size.labelRes))
                            Icon(
                                Icons.Outlined.ArrowDropDown,
                                contentDescription = stringResource(
                                    R.string.languages_wordlist_size_desc,
                                ),
                            )
                        }
                        DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                            for (option in sizeOptions) {
                                DropdownMenuItem(
                                    text = {
                                        val words = DictionaryCatalog.wordCap(entry, option)
                                        Text(
                                            pluralStringResource(
                                                R.plurals.languages_wordlist_size_option,
                                                words,
                                                stringResource(option.labelRes),
                                                words,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        size = option
                                        onDefaultSizeChange(option)
                                        sizeMenu = false
                                    },
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { WordlistDownloadManager.start(filesDir, entry, size) },
                        enabled = !WordlistDownloadManager.isBusy,
                    ) {
                        Text(
                            if (status is WordlistDownloadManager.DownloadStatus.Failed) {
                                stringResource(CommonR.string.common_retry)
                            } else {
                                stringResource(CommonR.string.common_download)
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    when (status) {
        is WordlistDownloadManager.DownloadStatus.Downloading -> Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            // Indeterminate on purpose: a capped download stops early, so
            // bytes-of-total would count to a total it never reaches.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(
                    R.string.languages_wordlist_downloaded_bytes,
                    formatBytes(status.bytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        WordlistDownloadManager.DownloadStatus.Processing -> Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // The three resting states. Nothing goes under the row for any of them:
        // the row itself already carries the word count, the size and the retry
        // button, so a second line here would only repeat it. Listed rather than
        // left to an `else` so a new status has to decide whether it needs one.
        WordlistDownloadManager.DownloadStatus.NotDownloaded,
        is WordlistDownloadManager.DownloadStatus.Downloaded,
        is WordlistDownloadManager.DownloadStatus.Failed,
        -> Unit
    }
}

/**
 * Download/delete rows for a language's [CjkDictCatalog] packs, driven by the
 * process-level [CjkDictDownloadManager] so progress survives navigation. The
 * pack replaces the small bundled dictionary once fetched (the service reloads
 * on the next field focus). A pack with no hosting URL yet shows "Not available
 * yet" with its download disabled.
 */
@Composable
private fun CjkDictPackManager(
    langId: String,
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val scope = rememberCoroutineScope()
    val states by CjkDictDownloadManager.states.collectAsState()
    LaunchedEffect(langId) { CjkDictDownloadManager.refresh(filesDir) }

    // Named from the registry rather than an if-chain, so a new CJK language
    // does not silently inherit another language's heading.
    val groupTitle = stringResource(
        R.string.languages_cjk_options_title,
        LanguageRegistry.byId(langId).englishName,
    )
    SettingsGroup(
        groupTitle,
        info = listOf(
            stringResource(R.string.languages_cjk_download_info),
            stringResource(R.string.languages_cjk_double_pinyin_info),
        ).joinToString("\n\n"),
    ) {
        for (pack in CjkDictCatalog.forLang(langId)) {
            item {
                val status = states[pack.id] ?: CjkDictDownloadManager.DownloadStatus.NotDownloaded
                WmRow(
                    title = stringResource(pack.displayNameRes),
                    subtitle = packStatusLabel(pack, status),
                    trailing = {
                        when (status) {
                            is CjkDictDownloadManager.DownloadStatus.Downloading ->
                                IconButton(onClick = { CjkDictDownloadManager.cancel() }) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            CjkDictDownloadManager.DownloadStatus.Downloaded ->
                                IconButton(onClick = { CjkDictDownloadManager.delete(filesDir, pack) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(
                                            R.string.languages_cjk_delete_desc,
                                            stringResource(pack.displayNameRes),
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            // The three states with nothing on disk to act on, so
                            // all three offer the same button; the label below is
                            // what separates resuming from starting. Listed rather
                            // than left to an `else` so a new status has to say
                            // which button it belongs under.
                            CjkDictDownloadManager.DownloadStatus.NotDownloaded,
                            is CjkDictDownloadManager.DownloadStatus.Paused,
                            is CjkDictDownloadManager.DownloadStatus.Failed,
                            -> TextButton(
                                enabled = pack.available && !CjkDictDownloadManager.isBusy,
                                onClick = { CjkDictDownloadManager.start(filesDir, pack) },
                            ) {
                                Text(
                                    if (status is CjkDictDownloadManager.DownloadStatus.Paused) {
                                        stringResource(R.string.languages_resume_action)
                                    } else {
                                        stringResource(CommonR.string.common_download)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }

        // Traditional output suits both Chinese (Taiwan) and Cantonese (Hong Kong),
        // so unlike the pinyin options below it is not gated to zh.
        item {
            ToggleSetting(
                R.string.languages_cjk_traditional_title,
                stringResource(R.string.languages_cjk_traditional_subtitle),
                settings.cjk.traditionalOutput,
                info = stringResource(R.string.languages_cjk_region_info),
                default = SettingsDefaults.cjk.traditionalOutput,
            ) { on -> scope.launch { repository.setCjkTraditionalOutput(on) } }
        }

        // Traditional characters are only half of writing Traditional: Taipei
        // says 計程車 where the mainland says 出租車, and no character map
        // reaches that. Only worth showing once the toggle above is on.
        if (settings.cjk.traditionalOutput) {
            for (region in HanVariant.HanRegion.entries) {
                item {
                    val titleRes = when (region) {
                        HanVariant.HanRegion.GENERIC -> R.string.languages_cjk_region_generic_title
                        HanVariant.HanRegion.TAIWAN -> R.string.languages_cjk_region_taiwan_title
                        HanVariant.HanRegion.HONG_KONG ->
                            R.string.languages_cjk_region_hong_kong_title
                    }
                    val subtitleRes = when (region) {
                        HanVariant.HanRegion.GENERIC ->
                            R.string.languages_cjk_region_generic_subtitle
                        HanVariant.HanRegion.TAIWAN ->
                            R.string.languages_cjk_region_taiwan_subtitle
                        HanVariant.HanRegion.HONG_KONG ->
                            R.string.languages_cjk_region_hong_kong_subtitle
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { scope.launch { repository.setCjkHanRegion(region) } }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = settings.cjk.hanRegion == region,
                            onClick = { scope.launch { repository.setCjkHanRegion(region) } },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(stringResource(titleRes))
                            CaptionText(stringResource(subtitleRes))
                        }
                    }
                }
            }
        }

        // Cantonese-only: the sound mergers most Hong Kong speakers have, and
        // therefore spell — without this, someone who says 你 as lei5 types `lei`
        // and the dictionary (which files it under nei5) offers nothing at all.
        if (langId == "yue") {
            item {
                ToggleSetting(
                    R.string.languages_cjk_lazy_title,
                    stringResource(R.string.languages_cjk_lazy_subtitle),
                    settings.cjk.jyutpingLazy,
                    default = SettingsDefaults.cjk.jyutpingLazy,
                ) { on -> scope.launch { repository.setJyutpingLazy(on) } }
            }
        }

        // Chinese-only: fuzzy pinyin + Double Pinyin scheme.
        if (langId == "zh") {
            item {
                ToggleSetting(
                    R.string.languages_cjk_fuzzy_title,
                    stringResource(R.string.languages_cjk_fuzzy_subtitle),
                    settings.cjk.pinyinFuzzy,
                    info = stringResource(R.string.languages_cjk_fuzzy_pairs_info),
                    default = SettingsDefaults.cjk.pinyinFuzzy,
                ) { on -> scope.launch { repository.setPinyinFuzzy(on) } }
            }
            // The eleven groups, individually. They were all-or-nothing, and
            // they are not one preference: the nasal endings are a regional
            // accent, while n↔l costs precision on every syllable starting with
            // either. Only drawn while fuzzy is on — off, they decide nothing.
            if (settings.cjk.pinyinFuzzy) {
                for (pair in PinyinFuzzy.PAIRS) {
                    item {
                        val on = pair.id in settings.cjk.pinyinFuzzyPairs
                        val label = pair.members.joinToString(" ↔ ")
                        WmRow(
                            title = label,
                            subtitle = stringResource(
                                if (pair.initial) {
                                    R.string.languages_cjk_fuzzy_pair_initial
                                } else {
                                    R.string.languages_cjk_fuzzy_pair_final
                                },
                            ),
                            trailing = {
                                Switch(
                                    checked = on,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            repository.setPinyinFuzzyPair(pair.id, checked)
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
                if (settings.cjk.pinyinFuzzyPairs != PinyinFuzzy.ALL_PAIRS) {
                    item {
                        ActionRow(
                            title = R.string.languages_cjk_fuzzy_pairs_reset_title,
                            subtitle = null,
                            action = stringResource(CommonR.string.common_reset),
                        ) { scope.launch { repository.resetPinyinFuzzyPairs() } }
                    }
                }
            }
            for (scheme in DoublePinyinScheme.entries) {
                item {
                    val select: () -> Unit = { scope.launch { repository.setPinyinDoublePinyin(scheme) } }
                    WmRow(
                        title = stringResource(scheme.displayNameRes),
                        trailing = {
                            RadioButton(
                                selected = settings.cjk.pinyinDoublePinyin == scheme,
                                onClick = select,
                            )
                        },
                        onClick = select,
                    )
                }
            }
        }
    }
}

/**
 * Supporting-line text for a pack's current download state. Composable so the
 * text follows the app's language: it is read only while the row is drawn.
 */
@Composable
private fun packStatusLabel(
    pack: CjkDictPack,
    status: CjkDictDownloadManager.DownloadStatus,
): String = when (status) {
    CjkDictDownloadManager.DownloadStatus.NotDownloaded ->
        if (pack.available) {
            stringResource(pack.descriptionRes)
        } else {
            stringResource(R.string.languages_cjk_pack_unavailable)
        }
    is CjkDictDownloadManager.DownloadStatus.Downloading ->
        if (status.total > 0) {
            stringResource(
                R.string.languages_cjk_downloading_percent,
                (status.bytes * PERCENT / status.total).toInt(),
            )
        } else {
            stringResource(CommonR.string.common_downloading)
        }
    is CjkDictDownloadManager.DownloadStatus.Paused ->
        stringResource(R.string.languages_cjk_paused)
    CjkDictDownloadManager.DownloadStatus.Downloaded ->
        stringResource(R.string.languages_cjk_downloaded)
    is CjkDictDownloadManager.DownloadStatus.Failed ->
        status.messageArg?.let { stringResource(status.messageRes, it) }
            ?: stringResource(status.messageRes)
}

/** Turns a fraction into the whole-number percentage the pack row shows. */
private const val PERCENT = 100L

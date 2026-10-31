package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.share.ShareSheetRoute
import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.updates.UpdateChannel
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.barcode.BarcodeEngine
import com.wasimaster.wmkeyboard.core.settings.AutomationPermission
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.ActionOnlyTools
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingModels
import com.wasimaster.wmkeyboard.core.translate.BergamotTranslator
import com.wasimaster.wmkeyboard.core.translate.NllbTranslator
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.ime.AppLaunchModes

/**
 * How much an entry is worth next to the others, as a percentage applied to
 * its raw match score.
 *
 * Two entries can match a word equally well and still deserve very different
 * places in the list. "Themes" names both the themes screen and the toolbar
 * shortcut that opens the theme picker, and someone searching *settings*
 * wants the screen. The backup screen, meanwhile, has a toggle named after
 * nearly every feature ("Sticker packs", "Include API keys") and none of
 * them is the feature itself.
 */
internal enum class EntryWeight(val percent: Int, val titleBonus: Int) {
    /**
     * A destination screen: the whole feature lives behind it. The bonus is
     * enough that a screen whose name only shares a stem with the query
     * ("Keyboard themes" for `themes`) beats the tool page called exactly that.
     */
    SECTION(100, 35),

    /**
     * The switch that *is* the feature, the row most people come looking for:
     * Autocorrect, Key press haptics, Key popup, Number row. The label is the
     * statement that this row is what the word means, so it lifts the row
     * above every ordinary row that carries the word, exact name included, and
     * above rows that start with your word when the primary row is only reached
     * through a synonym ("Key press haptics" for `vibrate`, over "Vibrate on
     * space"). Not enough to beat a screen named by the word, and no help to a
     * spelling slip or a substring against a proper hit elsewhere.
     */
    PRIMARY(100, 32),

    /** An ordinary setting row. */
    NORMAL(100, 0),

    /**
     * A tool's own page. It is named after the tool, so it lands an exact hit
     * on the tool's name; the settings screen of the same name ("Clipboard",
     * "Themes", "Incognito mode") is what a search for settings means, and this
     * keeps the page just under it.
     */
    TOOL(90, 0),

    /**
     * A row on a tool's page, or a permission: named after a feature it only
     * touches. The camera page has a "Haptics" switch, the scanner a "Vibrate
     * on detection", the permissions screen a "Vibration". None of them is what
     * a search for vibration means, and their names carry the word whole, so
     * they need to sit below the rows that are.
     */
    DETAIL(80, 0),

    /**
     * A row that only points at a setting whose real home is another screen:
     * the backup screen's per-feature toggles, and the "All … settings"
     * shortcuts on the tool pages.
     */
    MIRROR(40, 0),
}

/**
 * One searchable setting: what it is called, where it lives, and the route
 * that opens the screen holding it.
 *
 * [title], [subtitle] and [screen] are plain text so that the ranking below
 * stays ordinary Kotlin. They are resolved once, by [settingsSearchIndex],
 * from the very resources the settings screens draw, so the index reads in
 * the same language the user sees.
 *
 * [titleRes] is the id behind [title]. It travels to [SettingsHighlight], which
 * matches a row by its resource rather than by its words: an id survives
 * translation, and the drawn text does not.
 */
internal data class SettingsSearchEntry(
    val title: String,
    val subtitle: String,
    /**
     * The path above the destination, outermost first: `["Tools", "Camera"]`.
     *
     * Kept as its parts rather than as the one string the result draws,
     * because the parts are also what the path strip is seeded with when a
     * result is opened — a jump has no back stack to derive a path from
     * (#111).
     */
    val screenPath: List<String>,
    val route: String,
    val weight: EntryWeight = EntryWeight.NORMAL,
    /** Set on the tool entries, so a result can draw the tool's own icon. */
    val tool: ToolbarTool? = null,
    @StringRes val titleRes: Int = 0,
    /**
     * Words the entry is found by and never draws: "dark mode" for the themes
     * screen, "new phone" for backup. Only the destination screens carry them,
     * because a word on every row of a screen tells the ranking nothing.
     */
    val keywords: String = "",
    /**
     * Names this entry across builds and languages: the route plus the *name*
     * of the title resource, never its id, which the compiler reassigns. The
     * picks store keys on it, so what you chose last month is still the same
     * row after an update.
     */
    val key: String = "$route#$title",
    /**
     * The route pattern of the screen the row is really drawn on, when that is
     * not [route]: a screen that takes an argument the index cannot fill in.
     * The mode editor's rows are the case. Search opens the mode list, since
     * it cannot know which mode was meant, but a link can name one, and
     * `wmkeyboard://settings/mode_edit/mode_browser?setting=…` finds its row
     * through this. Null for every row drawn where [route] opens.
     */
    val screenPattern: String? = null,
) {
    /** The path as the result draws it under the row, e.g. "Tools › Camera". */
    val screen: String get() = screenPath.joinToString(CRUMB_SEPARATOR)

    /**
     * The four fields in the form the matcher compares against, built on the
     * first search that reaches this entry and kept for the rest of the screen.
     */
    internal val searchText: List<SearchText> by lazy {
        listOf(
            SearchText(MatchField.TITLE, title),
            SearchText(MatchField.KEYWORDS, keywords),
            SearchText(MatchField.SCREEN, screen),
            SearchText(MatchField.SUBTITLE, subtitle),
        )
    }
}

/**
 * Where the index reads its words from. [android.content.res.Resources] in the
 * app; a parse of the `strings*.xml` files in the JVM tests, which is what lets
 * the ranking be checked against the real index rather than a fixture.
 */
internal interface SearchStrings {
    fun getString(@StringRes id: Int): String
    fun getStringArray(@ArrayRes id: Int): Array<String>

    /** The `name="…"` of a string resource, for [SettingsSearchEntry.key]. */
    fun resourceName(@StringRes id: Int): String
}

/** The app's [SearchStrings]: the resources of the language the screens draw in. */
internal class ResourceSearchStrings(private val res: Resources) : SearchStrings {
    override fun getString(@StringRes id: Int): String = res.getString(id)
    override fun getStringArray(@ArrayRes id: Int): Array<String> = res.getStringArray(id)
    override fun resourceName(@StringRes id: Int): String = res.getResourceEntryName(id)
}

/** What separates the parts of a breadcrumb. Punctuation, not words. */
private const val CRUMB_SEPARATOR = " › "

/**
 * The mode editor's pattern in [SettingsRoutes.all], which its rows name as
 * their [SettingsSearchEntry.screenPattern].
 */
internal const val MODE_EDIT_PATTERN = "mode_edit/{modeId}"

/** The breadcrumb parts that are set, outermost first. */
private fun SearchStrings.crumb(vararg parts: Int): List<String> =
    parts.filter { it != 0 }.map { getString(it) }

/**
 * Builds one entry from the resources the owning screen draws.
 *
 * [screen] names the screen the row sits on, [screenParent] the screen above
 * it and [screenRoot] the one above that. Leave the two outer ones at 0 for a
 * row on a top-level screen.
 */
private fun SearchStrings.entry(
    @StringRes title: Int,
    @StringRes subtitle: Int = 0,
    @StringRes screen: Int = 0,
    route: String = "",
    @StringRes screenParent: Int = 0,
    @StringRes screenRoot: Int = 0,
    weight: EntryWeight = EntryWeight.NORMAL,
    @StringRes keywords: Int = 0,
    screenPattern: String? = null,
): SettingsSearchEntry = SettingsSearchEntry(
    title = getString(title),
    subtitle = if (subtitle == 0) "" else getString(subtitle),
    screenPath = crumb(screenRoot, screenParent, screen),
    route = route,
    weight = weight,
    titleRes = title,
    // An explicit argument wins; otherwise the row's own entry in the keyword
    // table, so a feature known by another name is found by it without the
    // screen that owns the row having to know.
    keywords = (if (keywords != 0) keywords else searchKeywordsFor(title))
        .let { if (it == 0) "" else getString(it) },
    key = "$route#${resourceName(title)}",
    screenPattern = screenPattern,
)

/**
 * Builds an entry on a tool's own page. The breadcrumb and the route both come
 * from the tool, so a renamed tool never desynchronises from its entries, and
 * the tool's name is read from [toolTitle] rather than copied here.
 */
private fun SearchStrings.toolEntry(
    tool: ToolbarTool,
    @StringRes title: Int,
    @StringRes subtitle: Int = 0,
    weight: EntryWeight = EntryWeight.DETAIL,
    // For a title several tool pages share (the provider list's heading): the
    // keyword table is keyed by title, so words that belong to one tool's copy
    // of the row are named here instead.
    @StringRes keywords: Int = 0,
): SettingsSearchEntry = SettingsSearchEntry(
    title = getString(title),
    subtitle = if (subtitle == 0) "" else getString(subtitle),
    screenPath = crumb(R.string.home_tools_title, toolTitle(tool)),
    route = "tool/${tool.name}",
    weight = weight,
    tool = tool,
    titleRes = title,
    keywords = (if (keywords != 0) keywords else searchKeywordsFor(title))
        .let { if (it == 0) "" else getString(it) },
    key = "tool/${tool.name}#${resourceName(title)}",
)

/**
 * Builds an entry on a provider's own screen ([PROVIDER_PAGE_ROUTE]): the
 * path is the screen the tool's list is on, then the provider. The kind's
 * name is read from the kind, as the screen's heading reads it.
 */
private fun SearchStrings.providerEntry(
    tool: ProviderTool,
    type: ProviderType,
    @StringRes title: Int,
    @StringRes subtitle: Int = 0,
    // A row on a tool's page weighs as one ([toolEntry]); elsewhere as an ordinary row.
    weight: EntryWeight? = null,
): SettingsSearchEntry {
    val host = providerHostTool(tool)
    val route = providerRoute(tool, type)
    return SettingsSearchEntry(
        title = getString(title),
        subtitle = if (subtitle == 0) "" else getString(subtitle),
        screenPath = providerListPath(tool) + getString(type.labelRes),
        route = route,
        weight = weight ?: if (host != null) EntryWeight.DETAIL else EntryWeight.NORMAL,
        tool = host,
        titleRes = title,
        keywords = searchKeywordsFor(title).let { if (it == 0) "" else getString(it) },
        key = "$route#${resourceName(title)}",
    )
}

/** The path to the screen [tool]'s list is drawn on. */
private fun SearchStrings.providerListPath(tool: ProviderTool): List<String> {
    val host = providerHostTool(tool)
    return when {
        host != null -> crumb(R.string.home_tools_title, toolTitle(host))
        tool == ProviderTool.VOICE -> crumb(R.string.home_voice_title)
        tool == ProviderTool.PHOTOS -> crumb(R.string.photo_services_title)
        tool == ProviderTool.IMAGE_GEN -> crumb(
            R.string.home_tools_title, toolTitle(ToolbarTool.STICKER), R.string.home_screen_sticker_packs_title, R.string.stickergen_title,
        )
        else -> crumb(R.string.home_typing_title, R.string.typing_group_suggestions_title)
    }
}

/**
 * Every kind of provider, once for each list it can join, so a service is
 * found by its own name ("DeepL", "Whisper", "Ollama") and opens its own
 * screen. That screen shows the kind's first row on the list, or offers to
 * add it. Weighted as a tool's page: a provider is a destination too, and
 * its name lands an exact hit the same way, while a tool or a screen called
 * exactly what was typed still comes first.
 *
 * The on-device translators are left out where this build has no engine
 * for them, as their model rows are. What else a phone cannot run (a voice
 * engine whose library is missing, anything online in the no-internet
 * build) is only known on the phone, so the search screen drops those
 * itself ([searchableProviderEntries]).
 */
private fun SearchStrings.providerKindRows(): List<SettingsSearchEntry> =
    ProviderTool.entries.flatMap { tool ->
        ProviderTypes.serving(tool).filter(::providerKindIndexed).map { type ->
            providerEntry(tool, type, type.labelRes, type.descriptionRes(tool), weight = EntryWeight.TOOL)
        }
    }

/** Whether this build has [type] at all; see [providerKindRows]. */
private fun providerKindIndexed(type: ProviderType): Boolean = when (type.id) {
    ProviderTypes.ML_KIT.id -> OnDeviceTranslator.offered
    ProviderTypes.BERGAMOT.id -> BergamotTranslator.available
    ProviderTypes.NLLB.id -> NllbTranslator.available
    else -> true
}

/**
 * The rows the user has that the kind entries do not name: a second key or
 * server, and any row given a nickname. Each opens that row's own screen,
 * and reads under its name what the row is set up with ([subtitle], the
 * line its list row shows). Built on the phone from the settings, beside
 * the index rather than in it: the index is read once per language and
 * kept, and these change whenever a list does.
 */
internal fun SearchStrings.providerInstanceRows(
    rows: (ProviderTool) -> List<ResolvedProvider>,
    subtitle: (ProviderTool, ResolvedProvider) -> String,
): List<SettingsSearchEntry> = ProviderTool.entries.flatMap { tool ->
    rows(tool)
        .filter { it.type != null && (it.key != it.typeId || it.customName.isNotEmpty()) }
        .map { row ->
            val title = row.customName.ifEmpty { getString(checkNotNull(row.type).labelRes) }
            val route = providerRoute(tool, row.key)
            SettingsSearchEntry(
                title = title,
                subtitle = subtitle(tool, row),
                screenPath = providerListPath(tool) + title,
                route = route,
                weight = EntryWeight.TOOL,
                tool = providerHostTool(tool),
                // Names the row, not a resource: a nickname has none.
                key = "$route#$PROVIDER_INSTANCE_KEY",
            )
        }
}

/**
 * The name half of an instance entry's [SettingsSearchEntry.key]. Not shaped
 * like a resource name, so no link and no route check can take it for one.
 */
private const val PROVIDER_INSTANCE_KEY = "provider-instance"

/**
 * [index] without what this phone cannot open, plus [instances]: the entries
 * of a provider kind ([providerKindRows], and the rows on its screen) are
 * dropped when the kind cannot run here and no list has it. A kind already
 * on a list stays findable, since its screen is where it is removed.
 */
internal fun searchableProviderEntries(
    index: List<SettingsSearchEntry>,
    instances: List<SettingsSearchEntry>,
    runnable: (ProviderTool, ProviderType) -> Boolean,
    listed: (ProviderTool, String) -> Boolean,
): List<SettingsSearchEntry> {
    val kept = index.filter { entry ->
        val (tool, key) = providerOfRoute(entry.route) ?: return@filter true
        val type = ProviderTypes.of(providerKindOf(key)) ?: return@filter true
        runnable(tool, type) || listed(tool, type.id)
    }
    return (kept + instances).distinctBy { it.key }
}

/** Rows on the Typing screen, in screen order. */
private fun SearchStrings.typingRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_typing_title, "typing", weight = weight)
    return listOfNotNull(
        // Personal dictionary, Custom dictionaries and Suggestion blacklist are
        // rows on this screen too, but each only opens a screen of its own. They
        // are indexed once, in sectionRows, pointing straight at that screen: a
        // second entry that lands on Typing and flashes the row would be a
        // near-identical result one line below the useful one.
        // The Backspace group moved to Key press (#136); its rows are indexed
        // there, and the gesture rows on the three pages under Gestures.
        row(R.string.typing_shift_enter_title, R.string.typing_shift_enter_subtitle),
        row(R.string.typing_volume_cursor_title, R.string.typing_volume_cursor_subtitle),
        row(R.string.typing_volume_cursor_media_title, R.string.typing_volume_cursor_media_subtitle),
    )
}

/** Rows on the typing/corrections page, in screen order. */
private fun SearchStrings.typingCorrectionsRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.typing_group_corrections_title, "typing/corrections", screenParent = R.string.home_typing_title,
        weight = weight,
    )
    return listOf(
        row(R.string.typing_autocorrect_title, R.string.typing_autocorrect_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_autocorrect_confidence_title, R.string.typing_autocorrect_confidence_subtitle),
        row(R.string.typing_autocorrect_adaptive_title, R.string.typing_autocorrect_adaptive_subtitle),
        row(R.string.typing_timing_signal_title, R.string.typing_timing_signal_subtitle),
        row(R.string.typing_register_priors_title, R.string.typing_register_priors_subtitle),
        row(R.string.typing_undo_autocorrect_title, R.string.typing_undo_autocorrect_subtitle),
        row(R.string.typing_undo_chip_title, R.string.typing_undo_chip_subtitle),
        row(
            R.string.typing_undo_chip_obviousness_title,
            R.string.typing_undo_chip_obviousness_subtitle,
        ),
        row(R.string.typing_undo_memory_title, R.string.typing_undo_memory_subtitle),
        row(R.string.typing_stop_after_declines_title, R.string.typing_stop_after_declines_subtitle),
        row(R.string.typing_learned_gate_title, R.string.typing_learned_gate_subtitle),
        row(R.string.typing_learn_corrections_title, R.string.typing_learn_corrections_subtitle),
        row(R.string.typing_adapt_taps_title, R.string.typing_adapt_taps_subtitle),
        row(R.string.typing_mistype_tolerance_title, R.string.typing_mistype_tolerance_subtitle),
        row(R.string.typing_fix_several_slips_title, R.string.typing_fix_several_slips_subtitle),
        row(R.string.typing_skip_all_caps_title, R.string.typing_skip_all_caps_subtitle),
        row(R.string.typing_autocorrect_on_enter_title, R.string.typing_autocorrect_on_enter_subtitle),
        row(R.string.typing_autocorrect_on_punctuation_title, R.string.typing_autocorrect_on_punctuation_subtitle),
        row(R.string.typing_dictionary_capitals_title, R.string.typing_dictionary_capitals_subtitle),
        row(R.string.typing_block_offensive_title, R.string.typing_block_offensive_subtitle),
        row(R.string.typing_context_rerank_title, R.string.typing_context_rerank_subtitle),
        row(R.string.typing_neural_context_title, R.string.typing_neural_context_subtitle),
        row(R.string.typing_context_revision_title, R.string.typing_context_revision_subtitle),
        row(R.string.typing_phrase_completion_title, R.string.typing_phrase_completion_subtitle),
        row(R.string.typing_autocorrect_splits_title, R.string.typing_autocorrect_splits_subtitle),
        row(
            R.string.typing_language_detection_by_app_title,
            R.string.typing_language_detection_by_app_subtitle,
        ),
        row(
            R.string.typing_language_detection_title,
            R.string.typing_language_detection_subtitle,
        ),
        row(R.string.typing_language_detection_strength_title),
        row(
            R.string.typing_number_row_corrections_title,
            R.string.typing_number_row_corrections_subtitle,
        ),
        row(R.string.typing_auto_apostrophe_title, R.string.typing_auto_apostrophe_subtitle),
    )
}

/** Rows on the typing/writing page (capitals, punctuation and spacing), in screen order. */
private fun SearchStrings.typingWritingRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.typing_group_writing_title, "typing/writing", screenParent = R.string.home_typing_title,
        weight = weight,
    )
    return listOf(
        row(R.string.typing_auto_capitalize_title, R.string.typing_auto_capitalize_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_shift_recase_title, R.string.typing_shift_recase_subtitle),
        row(R.string.typing_shift_recase_caret_title, R.string.typing_shift_recase_caret_subtitle),
        row(R.string.typing_shift_recase_inside_title, R.string.typing_shift_recase_inside_subtitle),
        row(R.string.typing_double_space_title, R.string.typing_double_space_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_double_space_window_title, R.string.typing_double_space_window_subtitle),
        row(R.string.typing_auto_space_punctuation_title, R.string.typing_auto_space_punctuation_subtitle),
        row(R.string.typing_hug_punctuation_title, R.string.typing_hug_punctuation_subtitle),
        row(R.string.typing_hug_punctuation_marks_title, R.string.typing_hug_punctuation_marks_hint),
        row(
            R.string.typing_language_punctuation_spacing_title,
            R.string.typing_language_punctuation_spacing_subtitle,
        ),
        row(R.string.typing_space_after_suggestion_title, R.string.typing_space_after_suggestion_subtitle),
        row(R.string.typing_wrap_selection_title, R.string.typing_wrap_selection_subtitle),
        row(R.string.typing_auto_close_brackets_title, R.string.typing_auto_close_brackets_subtitle),
        row(R.string.typing_continue_lists_title, R.string.typing_continue_lists_subtitle),
    )
}

/** Rows on the typing/suggestions page, in screen order. */
private fun SearchStrings.typingSuggestionsRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.typing_group_suggestions_title, "typing/suggestions", screenParent = R.string.home_typing_title,
        weight = weight,
    )
    return listOf(
        row(R.string.typing_suggestions_title, R.string.typing_suggestions_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_suggestion_slots_title, R.string.typing_suggestion_slots_subtitle),
        row(R.string.typing_fit_more_words_title, R.string.typing_fit_more_words_subtitle),
        row(R.string.typing_suggestion_pages_title, R.string.typing_suggestion_pages_subtitle),
        row(R.string.typing_suggestion_pages_emoji_title, R.string.typing_suggestion_pages_emoji_subtitle),
        row(R.string.typing_suggestion_pages_fancy_title, R.string.typing_suggestion_pages_fancy_subtitle),
        row(R.string.typing_suggestion_swipe_up_title, R.string.typing_suggestion_swipe_up_subtitle),
        row(R.string.typing_suggestion_expanded_grid_title, R.string.typing_suggestion_expanded_grid_subtitle),
        row(R.string.typing_suggestion_emoji_slot_title, R.string.typing_suggestion_emoji_slot_subtitle),
        row(R.string.typing_suggestion_emoji_count_title, R.string.typing_suggestion_emoji_count_subtitle),
        row(R.string.typing_suggestion_fixed_slots_title, R.string.typing_suggestion_fixed_slots_subtitle),
        row(R.string.typing_suggestion_tinted_title, R.string.typing_suggestion_tinted_subtitle),
        row(R.string.typing_suggestion_scroll_title, R.string.typing_suggestion_scroll_subtitle),
        row(R.string.typing_suggestion_more_arrow_title, R.string.typing_suggestion_more_arrow_subtitle),
        row(R.string.typing_punctuation_suggestions_title, R.string.typing_punctuation_suggestions_subtitle),
        row(R.string.typing_suggestions_all_fields_title, R.string.typing_suggestions_all_fields_subtitle),
        row(R.string.typing_learn_threshold_title, R.string.typing_learn_threshold_subtitle),
        row(R.string.typing_new_word_sightings_title, R.string.typing_new_word_sightings_subtitle),
        row(R.string.typing_ask_before_learning_title, R.string.typing_ask_before_learning_subtitle),
        row(R.string.typing_offer_near_miss_title, R.string.typing_offer_near_miss_subtitle),
        row(R.string.typing_suggestions_first_title, R.string.typing_suggestions_first_subtitle),
        row(R.string.typing_primary_center_title, R.string.typing_primary_center_subtitle),
        row(R.string.typing_suggestion_overflow_title, R.string.typing_suggestion_overflow_subtitle),
        row(R.string.typing_contact_names_title, R.string.typing_contact_names_subtitle),
        row(R.string.typing_contact_emails_title, R.string.typing_contact_emails_subtitle),
        row(R.string.typing_contact_emails_in_email_fields_title, R.string.typing_contact_emails_in_email_fields_subtitle),
        row(R.string.typing_typed_emails_title, R.string.typing_typed_emails_subtitle),
        row(R.string.typing_typed_numbers_title, R.string.typing_typed_numbers_subtitle),
        row(R.string.typing_app_names_title, R.string.typing_app_names_subtitle),
        row(R.string.typing_inline_emoji_search_title, R.string.typing_inline_emoji_search_subtitle),
        row(R.string.typing_inline_autofill_title, R.string.typing_inline_autofill_subtitle),
        row(R.string.typing_smart_replies_title, R.string.typing_smart_replies_subtitle),
        row(R.string.typing_group_autopilot_title, R.string.typing_group_autopilot_subtitle),
        row(R.string.typing_skip_typed_word_title, R.string.typing_skip_typed_word_subtitle),
        row(R.string.typing_typed_word_first_title, R.string.typing_typed_word_first_subtitle),
        row(R.string.typing_recent_word_bar_title, R.string.typing_recent_word_bar_subtitle),
        row(R.string.typing_number_prediction_title, R.string.typing_number_prediction_subtitle),
        row(R.string.typing_group_octopus_title, R.string.typing_group_octopus_subtitle),
        row(R.string.typing_word_menu_title, R.string.typing_word_menu_subtitle),
        row(R.string.typing_synonym_sources_title),
        row(R.string.typing_rank_control_title, R.string.typing_rank_control_subtitle),
        row(R.string.typing_delete_edits_lists_title, R.string.typing_delete_edits_lists_subtitle),
    )
}

/** Rows on the typing/autopilot page, in screen order. */
private fun SearchStrings.typingAutopilotRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.typing_group_autopilot_title, "typing/autopilot", screenParent = R.string.home_typing_title,
        weight = weight,
    )
    return listOf(
        row(R.string.typing_smart_hit_detection_title, R.string.typing_smart_hit_detection_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_autopilot_strength_title, R.string.typing_autopilot_strength_subtitle),
        row(R.string.typing_autopilot_show_title, R.string.typing_autopilot_show_subtitle),
        row(R.string.typing_autopilot_size_title, R.string.typing_autopilot_size_subtitle),
        row(R.string.typing_autopilot_outline_title, R.string.typing_autopilot_outline_subtitle),
        row(R.string.typing_autopilot_animate_title, R.string.typing_autopilot_animate_subtitle),
    )
}

/**
 * Rows on the clipboard's Copy rules page (#898): "Remove trackers" was only
 * reachable by knowing it sat behind a row called Copy rules.
 */
private fun SearchStrings.clipRulesRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, keywords: Int = 0) = entry(
        title, subtitle, R.string.clipboard_copy_rules_title, "cliprules",
        screenParent = R.string.clipboard_page_rules_title, screenRoot = R.string.home_clipboard_title,
        keywords = keywords,
    )
    return listOf(
        row(R.string.clipboard_copy_rules_system_title, R.string.clipboard_copy_rules_system_subtitle),
        row(R.string.clipboard_plain_letters_title, R.string.clipboard_plain_letters_subtitle),
        row(R.string.clipboard_clean_links_title, R.string.clipboard_clean_links_subtitle, R.string.search_kw_clean_links),
        row(R.string.clipboard_extra_trackers_title, keywords = R.string.search_kw_clean_links),
    )
}

/** Rows on the typing/octopus page, in screen order. */
private fun SearchStrings.typingOctopusRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.typing_group_octopus_title, "typing/octopus", screenParent = R.string.home_typing_title,
        weight = weight,
    )
    return listOf(
        row(R.string.typing_octopus_enabled_title, R.string.typing_octopus_enabled_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_octopus_placement_title, R.string.typing_octopus_placement_subtitle),
        row(R.string.typing_octopus_density_title, R.string.typing_octopus_density_subtitle),
        row(R.string.typing_octopus_kinds_title, R.string.typing_octopus_kinds_subtitle),
        row(R.string.typing_octopus_glide_title, R.string.typing_octopus_glide_subtitle),
        row(R.string.typing_octopus_flick_title, R.string.typing_octopus_flick_subtitle),
        row(R.string.typing_octopus_sensitivity_title, R.string.typing_octopus_sensitivity_subtitle),
        row(R.string.typing_octopus_tap_title, R.string.typing_octopus_tap_subtitle),
        row(R.string.typing_octopus_size_title, R.string.typing_octopus_size_subtitle),
        row(R.string.typing_octopus_hints_title, R.string.typing_octopus_hints_subtitle),
        row(R.string.typing_octopus_long_press_title, R.string.typing_octopus_long_press_subtitle),
        row(R.string.typing_octopus_stack_title, R.string.typing_octopus_stack_subtitle),
    )
}

/** Rows on the Suggestion blacklist screen, in screen order. */
private fun SearchStrings.blacklistRows(): List<SettingsSearchEntry> = listOf(
    entry(
        R.string.backup_blacklist_scope_title, R.string.backup_blacklist_scope_subtitle,
        R.string.typing_blacklist_title, "blacklist",
        screenParent = R.string.typing_group_suggestions_title, screenRoot = R.string.home_typing_title,
    ),
)

/** Rows on the typing/chips page, in screen order. */
private fun SearchStrings.typingChipsRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.typing_group_smart_chips_title, "typing/chips", screenParent = R.string.home_typing_title,
        weight = weight,
    )
    return listOf(
        row(R.string.typing_smart_chips_title, R.string.typing_smart_chips_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_smart_calc_title, R.string.typing_smart_calc_subtitle),
        row(R.string.typing_smart_currency_title),
        row(R.string.typing_smart_units_title, R.string.typing_smart_units_subtitle),
        row(R.string.typing_smart_tool_keywords_title, R.string.typing_smart_tool_keywords_subtitle),
        row(R.string.typing_smart_dates_title, R.string.typing_smart_dates_subtitle),
        row(R.string.typing_smart_weather_title, R.string.typing_smart_weather_subtitle),
        row(R.string.typing_smart_lookups_title, R.string.typing_smart_lookups_subtitle),
        row(R.string.typing_smart_intents_title, R.string.typing_smart_intents_subtitle),
        row(R.string.typing_smart_gifs_title, R.string.typing_smart_gifs_subtitle),
        row(R.string.typing_smart_numbers_title, R.string.typing_smart_numbers_subtitle),
        row(R.string.typing_smart_contacts_title, R.string.typing_smart_contacts_subtitle),
        row(R.string.typing_smart_repeats_title, R.string.typing_smart_repeats_subtitle),
        row(R.string.typing_smart_repeat_after_title, R.string.typing_smart_repeat_after_subtitle),
        row(R.string.typing_smart_keep_text_title, R.string.typing_smart_keep_text_subtitle),
        row(
            R.string.typing_smart_number_grouping_title,
            R.string.typing_smart_number_grouping_subtitle,
        ),
    )
}

/** Rows on the typing/codes page, in screen order. */
private fun SearchStrings.typingCodesRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.typing_group_otp_title, "typing/codes", screenParent = R.string.home_typing_title,
        weight = weight,
    )
    return listOf(
        row(R.string.typing_otp_chip_title, R.string.typing_otp_chip_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_otp_access_title, R.string.typing_otp_access_subtitle),
        row(R.string.typing_otp_code_fields_title, R.string.typing_otp_code_fields_subtitle),
        row(R.string.typing_otp_expiry_title, R.string.typing_otp_expiry_subtitle),
        row(R.string.typing_otp_dismiss_title, R.string.typing_otp_dismiss_subtitle),
        row(R.string.typing_otp_per_digit_title, R.string.typing_otp_per_digit_subtitle),
    )
}

/**
 * Rows on the three pages under Gestures (SETTINGS_REDESIGN.md §4), each in
 * its page's screen order. The path is three steps, Typing › Gestures › the
 * page, which is what the strip is seeded with when a result is opened.
 */
private fun SearchStrings.gesturePageRow(
    @StringRes screen: Int,
    route: String,
    @StringRes title: Int,
    @StringRes subtitle: Int = 0,
    weight: EntryWeight = EntryWeight.NORMAL,
) = entry(
    title, subtitle, screen, route,
    screenParent = R.string.typing_group_gestures_title, screenRoot = R.string.home_typing_title, weight = weight,
)

/** Rows on the typing/gestures/glide page, in screen order. */
private fun SearchStrings.typingGlideRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        gesturePageRow(R.string.typing_group_glide_title, "typing/gestures/glide", title, subtitle, weight)
    return listOf(
        row(R.string.typing_glide_typing_title, R.string.typing_glide_typing_subtitle, weight = EntryWeight.PRIMARY),
        // Every build: slide-to-select (#658) is there even without the ML
        // Kit handwriting recognizer.
        row(R.string.typing_letter_swipe_action_title, R.string.typing_letter_swipe_action_subtitle),
        row(R.string.typing_space_glide_multiword_title, R.string.typing_space_glide_multiword_subtitle),
        row(R.string.typing_two_thumb_title, R.string.typing_two_thumb_subtitle),
        row(R.string.typing_two_thumb_word_end_title, R.string.typing_two_thumb_word_end_subtitle),
        row(R.string.typing_two_thumb_pause_title, R.string.typing_two_thumb_pause_subtitle),
        row(R.string.typing_two_thumb_tap_pause_title, R.string.typing_two_thumb_tap_pause_subtitle),
        row(R.string.typing_two_thumb_one_letter_pause_title, R.string.typing_two_thumb_one_letter_pause_subtitle),
        row(R.string.typing_two_thumb_retract_title, R.string.typing_two_thumb_retract_subtitle),
        row(R.string.typing_two_thumb_pause_band_title, R.string.typing_two_thumb_pause_band_subtitle),
        row(R.string.typing_swipe_continues_typed_title, R.string.typing_swipe_continues_typed_subtitle),
        row(R.string.typing_shift_glide_capitals_title, R.string.typing_shift_glide_capitals_subtitle),
        row(R.string.typing_shift_glide_mode_title, R.string.typing_shift_glide_mode_subtitle),
        row(R.string.typing_glide_picker_title, R.string.typing_glide_picker_subtitle),
        row(R.string.typing_glide_picker_sensitivity_title, R.string.typing_glide_picker_sensitivity_subtitle),
        row(R.string.typing_glide_picker_dwell_title, R.string.typing_glide_picker_dwell_subtitle),
        row(R.string.typing_glide_picker_hold_title, R.string.typing_glide_picker_hold_subtitle),
        row(R.string.typing_glide_picker_choices_title, R.string.typing_glide_picker_choices_subtitle),
        row(R.string.typing_glide_swipe_style_title, R.string.typing_glide_swipe_style_subtitle),
        row(R.string.typing_glide_swipe_style_forget),
        row(R.string.typing_glide_checkup_title, R.string.typing_glide_checkup_subtitle),
        row(R.string.typing_space_after_glide_title, R.string.typing_space_after_glide_subtitle),
        row(R.string.typing_glide_backspace_undo_title, R.string.typing_glide_backspace_undo_subtitle),
        row(R.string.typing_glide_apostrophe_title, R.string.typing_glide_apostrophe_subtitle),
        row(R.string.typing_swipe_start_distance_title, R.string.typing_swipe_start_distance_subtitle),
        row(R.string.typing_gesture_cooldown_title, R.string.typing_gesture_cooldown_subtitle),
        // "Extra time for a dot or a cross" is left out on purpose: it is drawn
        // only while the letter swipe writes by hand, which is neither the
        // default nor a state a search result can put the screen into.
        // The "Advanced tuning" fold, in the order it draws its rows.
        row(R.string.typing_glide_start_radius_title, R.string.typing_glide_start_radius_subtitle),
        row(R.string.typing_glide_end_radius_title, R.string.typing_glide_end_radius_subtitle),
        row(R.string.typing_glide_near_radius_title, R.string.typing_glide_near_radius_subtitle),
        row(R.string.typing_glide_dwell_title, R.string.typing_glide_dwell_subtitle),
        row(R.string.typing_glide_loop_title, R.string.typing_glide_loop_subtitle),
        row(R.string.typing_glide_loop_arc_title, R.string.typing_glide_loop_arc_subtitle),
        row(R.string.typing_glide_loop_extent_title, R.string.typing_glide_loop_extent_subtitle),
        row(R.string.typing_glide_loop_radius_title, R.string.typing_glide_loop_radius_subtitle),
        row(R.string.typing_glide_wiggle_title, R.string.typing_glide_wiggle_subtitle),
        row(
            R.string.typing_glide_wiggle_strength_title,
            R.string.typing_glide_wiggle_strength_subtitle,
        ),
        row(R.string.typing_glide_wiggle_extent_title, R.string.typing_glide_wiggle_extent_subtitle),
        row(R.string.typing_glide_shapes_per_word_title, R.string.typing_glide_shapes_per_word_subtitle),
        // The trail and the preview.
        row(R.string.typing_trail_style_title, R.string.typing_trail_style_subtitle),
        row(R.string.typing_trail_width_title, R.string.typing_trail_width_subtitle),
        row(R.string.typing_trail_length_title, R.string.typing_trail_length_subtitle),
        row(R.string.typing_trail_opacity_title),
        row(R.string.typing_glide_preview_title, R.string.typing_glide_preview_subtitle),
        row(
            R.string.typing_glide_preview_height_title,
            R.string.typing_glide_preview_height_subtitle,
        ),
        row(
            R.string.typing_glide_preview_shift_title,
            R.string.typing_glide_preview_shift_subtitle,
        ),
        row(R.string.typing_glide_preview_size_title, R.string.typing_glide_preview_size_subtitle),
        row(
            R.string.typing_glide_preview_color_title,
            R.string.typing_glide_preview_color_subtitle,
        ),
        row(
            R.string.typing_glide_preview_text_color_title,
            R.string.typing_glide_preview_text_color_subtitle,
        ),
        row(
            R.string.typing_glide_strip_preview_title,
            R.string.typing_glide_strip_preview_subtitle,
        ),
    )
}

/** Rows on the typing/gestures/glide/checkup page (#966 §5.5), in screen order. */
private fun SearchStrings.typingGlideCheckupRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.typing_glide_checkup_title, "typing/gestures/glide/checkup",
        screenParent = R.string.typing_group_glide_title, screenRoot = R.string.home_typing_title,
    )
    return listOf(
        row(R.string.typing_glide_checkup_measure_title, R.string.typing_glide_checkup_measure_subtitle),
        row(R.string.typing_glide_checkup_frequency_title),
        row(R.string.typing_glide_checkup_reset),
    )
}

/** Rows on the typing/gestures/spacebar page, in screen order. */
private fun SearchStrings.typingSpacebarRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        gesturePageRow(R.string.typing_page_spacebar_title, "typing/gestures/spacebar", title, subtitle, weight)
    return listOf(
        row(R.string.typing_space_short_swipe_title, R.string.typing_space_short_swipe_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_space_long_swipe_title, R.string.typing_space_long_swipe_subtitle),
        row(R.string.typing_space_swipe_up_touchpad_title, R.string.typing_space_swipe_up_touchpad_subtitle),
        row(R.string.typing_space_swipe_up_numpad_title, R.string.typing_space_swipe_up_numpad_subtitle),
        row(R.string.typing_space_cursor_2d_title, R.string.typing_space_cursor_2d_subtitle),
        row(R.string.typing_space_cursor_step_title, R.string.typing_space_cursor_step_subtitle),
        row(R.string.typing_space_cursor_accelerate_title, R.string.typing_space_cursor_accelerate_subtitle),
        row(R.string.typing_space_cursor_direct_title, R.string.typing_space_cursor_direct_subtitle),
        row(R.string.typing_space_cursor_edge_repeat_title, R.string.typing_space_cursor_edge_repeat_subtitle),
        row(R.string.typing_space_cursor_whole_keyboard_title, R.string.typing_space_cursor_whole_keyboard_subtitle),
        row(R.string.typing_space_cursor_top_speed_title, R.string.typing_space_cursor_top_speed_subtitle),
        row(R.string.typing_space_cursor_magnifier_title, R.string.typing_space_cursor_magnifier_subtitle),
        row(R.string.typing_magnifier_follows_title, R.string.typing_magnifier_follows_subtitle),
        row(R.string.typing_space_swipe_down_hide_title, R.string.typing_space_swipe_down_hide_subtitle),
        row(R.string.typing_spacebar_language_arrows_title, R.string.typing_spacebar_language_arrows_subtitle),
        row(R.string.typing_language_echo_title, R.string.typing_language_echo_subtitle),
        row(R.string.typing_space_hold_keys_label),
        row(R.string.typing_language_picker_style_title, R.string.typing_language_picker_style_subtitle),
        row(R.string.typing_space_hold_picker_long_ring_title, R.string.typing_space_hold_picker_long_ring_subtitle),
        row(R.string.typing_spacebar_display_title, R.string.typing_spacebar_display_subtitle),
        row(R.string.typing_spacebar_text_label),
        row(R.string.typing_spacebar_live_wpm_title, R.string.typing_spacebar_live_wpm_subtitle),
    )
}

/** Rows on the typing/gestures/flicks page, in screen order. */
private fun SearchStrings.typingFlicksRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        gesturePageRow(R.string.typing_page_flicks_title, "typing/gestures/flicks", title, subtitle, weight)
    return listOf(
        row(R.string.typing_hint_flick_title, R.string.typing_hint_flick_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_capital_flick_title, R.string.typing_capital_flick_subtitle),
        row(R.string.typing_flick_hints_title, R.string.typing_flick_hints_subtitle),
        row(R.string.typing_flick_popup_title, R.string.typing_flick_popup_subtitle),
        row(R.string.typing_swipe_only_tap_title, R.string.typing_swipe_only_tap_subtitle),
        row(R.string.typing_flick_distance_title, R.string.typing_flick_distance_subtitle),
        row(R.string.typing_flick_reach_title, R.string.typing_flick_reach_subtitle),
        row(R.string.typing_flick_onto_letter_title, R.string.typing_flick_onto_letter_subtitle),
        row(R.string.typing_possessive_swipe_title, R.string.typing_possessive_swipe_subtitle),
        row(R.string.typing_edge_swipe_back_title, R.string.typing_edge_swipe_back_subtitle),
        row(R.string.typing_finger_swipes_title, R.string.typing_finger_swipes_subtitle),
        row(R.string.typing_finger_swipes_reset_title, R.string.typing_finger_swipes_reset_subtitle),
        row(R.string.typing_symbol_chords_title, R.string.typing_symbol_chords_subtitle),
        row(R.string.typing_symbol_chord_add_title, R.string.typing_symbol_chord_add_subtitle),
        row(R.string.typing_symbol_chords_reset_title, R.string.typing_symbol_chords_reset_subtitle),
        row(R.string.typing_key_swipes_title, R.string.typing_key_swipes_subtitle),
        row(R.string.typing_key_swipe_add_title, R.string.typing_key_swipe_add_subtitle),
        row(R.string.typing_key_swipe_preset_title, R.string.typing_key_swipe_preset_subtitle),
        row(R.string.typing_key_swipes_reset_title, R.string.typing_key_swipes_reset_subtitle),
    )
}

/** Rows on the typing/hardware page, in screen order. */
private fun SearchStrings.typingHardwareRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.typing_group_hardware_title, "typing/hardware", screenParent = R.string.home_typing_title,
        weight = weight,
    )
    return listOf(
        row(R.string.typing_hardware_input_title, R.string.typing_hardware_input_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_hw_shortcuts_title, R.string.typing_hw_shortcuts_subtitle),
        row(R.string.typing_hw_panel_nav_title, R.string.typing_hw_panel_nav_subtitle),
        row(R.string.typing_hw_dpad_keys_title, R.string.typing_hw_dpad_keys_subtitle),
        row(R.string.typing_hw_esc_title, R.string.typing_hw_esc_subtitle),
        row(R.string.typing_hw_digit_chord_title, R.string.typing_hw_digit_chord_subtitle),
        row(R.string.typing_hw_lang_chord_title, R.string.typing_hw_lang_chord_subtitle),
        row(R.string.typing_hw_modifier_words_title, R.string.typing_hw_modifier_words_subtitle),
        row(R.string.typing_hw_picker_timeout_title, R.string.typing_hw_picker_timeout_subtitle),
        row(R.string.typing_hw_suggestion_hotkeys_title, R.string.typing_hw_suggestion_hotkeys_subtitle),
        row(R.string.typing_hw_suggestion_hints_title, R.string.typing_hw_suggestion_hints_subtitle),
        row(R.string.typing_hw_pick_mode_key_title, R.string.typing_hw_pick_mode_key_subtitle),
        row(R.string.typing_hw_function_keys_title, R.string.typing_hw_function_keys_subtitle),
        row(R.string.typing_hw_mac_title, R.string.typing_hw_mac_subtitle),
        row(R.string.typing_hw_auto_show_title, R.string.typing_hw_auto_show_subtitle),
    )
}

/** Rows on the Key press screen. */
private fun SearchStrings.keyPressRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_keypress_title, "keypress", weight = weight)
    return listOf(
        // The Backspace group, moved here from Typing (#136): a key's
        // gestures are a key-press matter, not a typing-engine one.
        row(R.string.typing_backspace_swipe_title, R.string.typing_backspace_swipe_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.typing_backspace_unit_title, R.string.typing_backspace_unit_subtitle),
        row(R.string.typing_backspace_preview_title, R.string.typing_backspace_preview_subtitle),
        row(R.string.typing_backspace_step_title, R.string.typing_backspace_step_subtitle),
        row(R.string.typing_backspace_char_step_title, R.string.typing_backspace_char_step_subtitle),
        row(R.string.typing_backspace_up_clears_title, R.string.typing_backspace_up_clears_subtitle),
        row(R.string.typing_forward_delete_swipe_title, R.string.typing_forward_delete_swipe_subtitle),
        row(R.string.typing_web_backspace_key_title, R.string.typing_web_backspace_key_subtitle),
        row(R.string.keypress_alternates_size_title, R.string.keypress_alternates_size_subtitle),
        row(
            R.string.keypress_alternates_padding_title,
            R.string.keypress_alternates_padding_subtitle,
        ),
        row(
            R.string.keypress_alternates_columns_title,
            R.string.keypress_alternates_columns_subtitle,
        ),
        row(
            R.string.keypress_alternates_nearest_title,
            R.string.keypress_alternates_nearest_subtitle,
        ),
        row(
            R.string.keypress_alternates_centred_title,
            R.string.keypress_alternates_centred_subtitle,
        ),
        row(R.string.keypress_alternates_order_title, R.string.keypress_alternates_order_subtitle),
        row(R.string.keypress_long_press_delay_title, R.string.keypress_long_press_delay_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.keypress_delete_repeat_title, R.string.keypress_delete_repeat_subtitle),
        row(R.string.keypress_hold_words_title, R.string.keypress_hold_words_subtitle),
        row(R.string.keypress_word_stops_punctuation_title, R.string.keypress_word_stops_punctuation_subtitle),
        row(R.string.keypress_word_stops_capitals_title, R.string.keypress_word_stops_capitals_subtitle),
        row(R.string.keypress_word_stops_digits_title, R.string.keypress_word_stops_digits_subtitle),
        row(R.string.keypress_word_delete_repeat_title, R.string.keypress_word_delete_repeat_subtitle),
        row(R.string.keypress_space_repeat_title, R.string.keypress_space_repeat_subtitle),
        row(R.string.keypress_custom_repeat_title, R.string.keypress_custom_repeat_subtitle),
        row(R.string.keypress_flicks_repeat_title, R.string.keypress_flicks_repeat_subtitle),
        row(R.string.keypress_caps_lock_title, R.string.keypress_caps_lock_subtitle, weight = EntryWeight.PRIMARY),
    )
}

/** Rows on the keypress/haptics page, in screen order. */
private fun SearchStrings.keypressHapticsRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.keypress_haptics_page_title, "keypress/haptics", screenParent = R.string.home_keypress_title,
        weight = weight,
    )
    return listOf(
        row(R.string.hardware_sound_key_title, R.string.hardware_sound_key_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.hardware_sound_volume_title, R.string.hardware_sound_volume_subtitle),
        row(R.string.keypress_haptics_title, R.string.keypress_haptics_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.keypress_haptic_strength_title, R.string.keypress_haptic_strength_subtitle),
        row(R.string.keypress_haptic_intensity_title, R.string.keypress_haptic_intensity_subtitle),
        row(R.string.keypress_long_press_haptics_title, R.string.keypress_long_press_haptics_subtitle),
        row(R.string.keypress_long_press_release_title, R.string.keypress_long_press_release_subtitle),
        row(R.string.keypress_cursor_haptics_title, R.string.keypress_cursor_haptics_subtitle),
        row(R.string.keypress_vibrate_space_title, R.string.keypress_vibrate_space_subtitle),
        row(R.string.keypress_vibrate_delete_swipe_title, R.string.keypress_vibrate_delete_swipe_subtitle),
        row(R.string.keypress_vibrate_repeat_title, R.string.keypress_vibrate_repeat_subtitle),
        row(R.string.keypress_haptics_hardware_title, R.string.keypress_haptics_hardware_subtitle),
        row(R.string.keypress_dnd_mute_title, R.string.keypress_dnd_mute_subtitle),
    )
}

/** Rows on the keypress/popup page, in screen order. */
private fun SearchStrings.keypressPopupRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.keypress_popup_group_title, "keypress/popup", screenParent = R.string.home_keypress_title,
        weight = weight,
    )
    return listOf(
        row(R.string.keypress_popup_title, R.string.keypress_popup_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.keypress_popup_numeric_title, R.string.keypress_popup_numeric_subtitle),
        row(R.string.keypress_popup_window_band_title, R.string.keypress_popup_window_band_subtitle),
        row(R.string.keypress_popup_min_duration_title, R.string.keypress_popup_min_duration_subtitle),
        row(R.string.keypress_popup_on_key_title, R.string.keypress_popup_on_key_subtitle),
        row(R.string.keypress_popup_font_size_title, R.string.keypress_popup_font_size_subtitle),
        row(R.string.keypress_popup_height_title, R.string.keypress_popup_height_subtitle),
        row(R.string.keypress_popup_offset_y_title, R.string.keypress_popup_offset_y_subtitle),
        row(R.string.keypress_popup_offset_x_title, R.string.keypress_popup_offset_x_subtitle),
        row(R.string.keypress_popup_background_title, R.string.keypress_popup_background_subtitle),
        row(R.string.keypress_popup_text_color_title, R.string.keypress_popup_text_color_subtitle),
        row(R.string.keypress_popup_shape_title, R.string.keypress_popup_shape_subtitle),
        row(R.string.keypress_popup_radius_title, R.string.keypress_popup_radius_subtitle),
    )
}

/** Rows on the keypress/shortcuts page, in screen order. */
private fun SearchStrings.keypressShortcutsRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.keypress_shortcuts_group_title, "keypress/shortcuts", screenParent = R.string.home_keypress_title,
        weight = weight,
    )
    return listOf(
        row(R.string.keypress_long_press_hints_title, R.string.keypress_long_press_hints_subtitle),
        row(R.string.keypress_all_accents_title, R.string.keypress_all_accents_subtitle),
        row(R.string.keypress_no_accents_title, R.string.keypress_no_accents_subtitle),
        row(R.string.keypress_top_row_holds_title, R.string.keypress_top_row_holds_subtitle),
        row(R.string.keypress_hold_symbols_title, R.string.keypress_hold_symbols_subtitle),
        row(R.string.keypress_native_letters_title, R.string.keypress_native_letters_subtitle),
        row(R.string.keypress_shifted_popup_title, R.string.keypress_shifted_popup_subtitle),
        row(R.string.keypress_symbols_numpad_title, R.string.keypress_symbols_numpad_subtitle),
        row(R.string.keypress_enter_emoji_title, R.string.keypress_enter_emoji_subtitle),
        row(R.string.keypress_currency_keys_title),
        row(R.string.keypress_ctrl_raw_title, R.string.keypress_ctrl_raw_subtitle),
        row(R.string.keypress_hold_actions_title, R.string.keypress_hold_actions_subtitle),
        row(R.string.keypress_hold_action_position_title, R.string.keypress_hold_action_position_subtitle),
        row(R.string.keypress_globe_drag_title, R.string.keypress_globe_drag_subtitle),
    )
}

/** Rows on Appearance and the two screens that hang off it. */
private fun SearchStrings.photoRows(): List<SettingsSearchEntry> {
    fun services(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.photo_services_title, "photos",
        screenParent = R.string.home_screen_theme_edit_title,
        screenRoot = R.string.home_appearance_title,
    )
    fun rotation(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.photo_rotation_title, "photo_rotation",
        screenParent = R.string.home_screen_theme_edit_title,
        screenRoot = R.string.home_appearance_title,
    )
    fun library(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.photo_library_title, "photo_library",
        screenParent = R.string.photo_rotation_title,
        screenRoot = R.string.home_appearance_title,
    )
    return listOf(
        // Unsplash, Pexels and Commons are rows of the provider list now.
        services(R.string.photo_providers_section_title, R.string.providers_add_subtitle),
        rotation(R.string.photo_rotation_on_title, R.string.photo_rotation_on_subtitle),
        rotation(R.string.photo_rotation_interval_title),
        rotation(R.string.photo_rotation_shuffle_title, R.string.photo_rotation_shuffle_subtitle),
        rotation(R.string.photo_rotation_source_saved_title, R.string.photo_rotation_source_saved_subtitle),
        rotation(R.string.photo_rotation_source_online_title, R.string.photo_rotation_source_online_subtitle),
        rotation(R.string.photo_rotation_topics_title, R.string.photo_rotation_topics_subtitle),
        rotation(R.string.photo_rotation_terms_label),
        rotation(R.string.photo_rotation_safe_title, R.string.photo_rotation_safe_subtitle),
        rotation(R.string.photo_rotation_wide_title, R.string.photo_rotation_wide_subtitle),
        rotation(R.string.photo_rotation_metered_title, R.string.photo_rotation_metered_subtitle),
        rotation(R.string.photo_rotation_pool_title),
        rotation(R.string.photo_rotation_scope_title),
        rotation(R.string.photo_rotation_scope_pick_title),
        rotation(
            R.string.photo_rotation_delete_downloads_title,
            R.string.photo_rotation_delete_downloads_subtitle,
        ),
        library(R.string.photo_add_device_title, R.string.photo_add_device_subtitle),
        library(R.string.photo_storage_title),
    )
}

private fun SearchStrings.appearanceRows(): List<SettingsSearchEntry> {
    fun theme(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.home_screen_themes_title, "themes",
        screenParent = R.string.home_appearance_title,
        weight = weight,
    )
    fun icon(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.home_screen_icons_title, "icons",
        screenParent = R.string.home_appearance_title,
        weight = weight,
    )
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_appearance_title, "appearance", weight = weight)
    return listOf(
        // Keyboard themes, Keyboard font and Icons are rows here, but each
        // opens its own screen. They are indexed once in sectionRows,
        // pointing at that screen.
        theme(R.string.theme_auto_title, R.string.theme_auto_subtitle),
        theme(R.string.theme_auto_light_title),
        theme(R.string.theme_auto_dark_title),
        theme(R.string.theme_auto_light_from_title),
        theme(R.string.theme_auto_dark_from_title),
        theme(R.string.theme_shuffle_interval_title, R.string.theme_shuffle_interval_subtitle),
        theme(R.string.theme_shuffle_now_title, R.string.theme_shuffle_now_subtitle),
        theme(R.string.theme_app_colors_title, R.string.theme_app_colors_subtitle),
        theme(R.string.theme_app_colors_scope_title),
        theme(R.string.theme_app_colors_source_title),
        theme(R.string.theme_app_colors_style_title, R.string.theme_app_colors_style_subtitle),
        theme(R.string.theme_app_colors_reset_title),
        theme(R.string.theme_background_image_landscape_title),
        icon(R.string.plugins_icons_pack_title),
        icon(R.string.plugins_icons_import_title, R.string.plugins_icons_import_subtitle),
        icon(R.string.plugins_icons_reset_title, R.string.plugins_icons_reset_subtitle),
        icon(R.string.plugins_icons_picker_use_svg_action),
        row(R.string.appearance_key_corner_radius_title, R.string.appearance_key_corner_radius_subtitle),
        row(R.string.appearance_key_label_size_title, R.string.appearance_key_label_size_subtitle),
        row(R.string.appearance_key_hint_size_title, R.string.appearance_key_hint_size_subtitle),
        row(R.string.appearance_key_hint_offset_title, R.string.appearance_key_hint_offset_subtitle),
        row(R.string.appearance_key_hint_offset_x_title, R.string.appearance_key_hint_offset_x_subtitle),
        row(R.string.appearance_key_label_offset_x_title, R.string.appearance_key_label_offset_x_subtitle),
        row(R.string.appearance_key_label_offset_y_title, R.string.appearance_key_label_offset_y_subtitle),
        row(R.string.home_reset_pinned_tools_title, R.string.home_reset_pinned_tools_subtitle),
    )
}

/** Rows on the appearance/toolbar page, in screen order. */
private fun SearchStrings.appearanceToolbarRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.appearance_toolbar_section_title, "appearance/toolbar", screenParent = R.string.home_appearance_title,
        weight = weight,
    )
    return listOf(
        row(R.string.appearance_toolbar_show_title, R.string.appearance_toolbar_show_subtitle),
        row(R.string.appearance_toolbar_placement_title, R.string.appearance_toolbar_placement_subtitle),
        row(R.string.appearance_toolbar_show_strip_title, R.string.appearance_toolbar_show_strip_subtitle),
        row(R.string.appearance_toolbar_landscape_column_title, R.string.appearance_toolbar_landscape_column_subtitle),
        row(R.string.appearance_toolbar_swipe_down_title, R.string.appearance_toolbar_swipe_down_subtitle),
        row(R.string.appearance_toolbar_drag_title, R.string.appearance_toolbar_drag_subtitle),
        row(R.string.appearance_toolbar_hold_delay_title, R.string.appearance_toolbar_hold_delay_subtitle),
        row(R.string.appearance_toolbar_hardware_only_title, R.string.appearance_toolbar_hardware_only_subtitle),
        row(R.string.appearance_toolbar_hardware_auto_hide_title, R.string.appearance_toolbar_hardware_auto_hide_subtitle),
        row(R.string.appearance_toolbar_rtl_title, R.string.appearance_toolbar_rtl_subtitle),
        row(R.string.appearance_toolbar_fit_title, R.string.appearance_toolbar_fit_subtitle),
        row(R.string.appearance_toolbar_padding_top_title, R.string.appearance_toolbar_padding_top_subtitle),
        row(R.string.appearance_toolbar_padding_bottom_title, R.string.appearance_toolbar_padding_bottom_subtitle),
        row(R.string.appearance_toolbar_lock_title, R.string.appearance_toolbar_lock_subtitle),
        row(R.string.appearance_toolbar_labels_title, R.string.appearance_toolbar_labels_subtitle),
        row(R.string.appearance_toolbar_label_size_title, R.string.appearance_toolbar_label_size_subtitle),
        row(R.string.appearance_toolbar_labels_fixed_title, R.string.appearance_toolbar_labels_fixed_subtitle),
        row(R.string.appearance_toolbar_pinned_colors_title, R.string.appearance_toolbar_pinned_colors_subtitle),
        row(R.string.appearance_suggestion_text_size_title, R.string.appearance_suggestion_text_size_subtitle),
        row(R.string.appearance_suggestion_spacing_title, R.string.appearance_suggestion_spacing_subtitle),
        row(R.string.appearance_suggestion_primary_color_title, R.string.appearance_suggestion_primary_color_subtitle),
        row(R.string.appearance_tool_circle_title, R.string.appearance_tool_circle_subtitle),
        row(R.string.appearance_tool_shape_title, R.string.appearance_tool_shape_subtitle),
        row(R.string.appearance_tool_width_title, R.string.appearance_tool_width_subtitle),
        row(R.string.appearance_tool_icon_size_title, R.string.appearance_tool_icon_size_subtitle),
        row(R.string.appearance_toolbar_reset_title, R.string.appearance_toolbar_reset_subtitle),
    )
}

/** Rows on the appearance/toolbox page, in screen order. */
private fun SearchStrings.appearanceToolboxRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.appearance_toolbox_section_title, "appearance/toolbox", screenParent = R.string.home_appearance_title,
        weight = weight,
    )
    return listOf(
        row(R.string.appearance_toolbox_layout_title, R.string.appearance_toolbox_layout_subtitle),
        row(R.string.appearance_toolbox_columns_title, R.string.appearance_toolbox_columns_subtitle),
        row(R.string.appearance_toolbox_pill_columns_title, R.string.appearance_toolbox_pill_columns_subtitle),
        row(R.string.appearance_toolbox_pill_filled_title, R.string.appearance_toolbox_pill_filled_subtitle),
        row(R.string.appearance_toolbox_paginate_title, R.string.appearance_toolbox_paginate_subtitle),
        row(R.string.appearance_toolbox_page_size_title, R.string.appearance_toolbox_page_size_subtitle),
        row(R.string.appearance_reset_toolbox_order_title, R.string.appearance_reset_toolbox_order_subtitle),
        row(R.string.appearance_toolbox_sort_title, R.string.appearance_toolbox_sort_subtitle),
        row(R.string.appearance_toolbox_label_size_title, R.string.appearance_toolbox_label_size_subtitle),
        row(R.string.appearance_toolbox_cell_spacing_title, R.string.appearance_toolbox_cell_spacing_subtitle),
        row(R.string.appearance_toolbox_search_button_title, R.string.appearance_toolbox_search_button_subtitle),
        row(R.string.appearance_toolbox_search_at_end_title, R.string.appearance_toolbox_search_at_end_subtitle),
        row(R.string.appearance_toolbox_search_sort_title, R.string.appearance_toolbox_search_sort_subtitle),
        row(R.string.appearance_toolbox_reset_title, R.string.appearance_toolbox_reset_subtitle),
    )
}

/** Rows on Layout & size, plus the two rows that moved off it. */
private fun SearchStrings.layoutRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_layout_title, "layout", weight = weight)
    return listOf(
        row(R.string.layout_number_row_shift_symbols_title, R.string.layout_number_row_shift_symbols_subtitle),
        row(R.string.layout_number_row_in_symbols_title, R.string.layout_number_row_in_symbols_subtitle),
        row(R.string.layout_number_row_on_keypad_title, R.string.layout_number_row_on_keypad_subtitle),
        row(R.string.layout_arrow_row_title, R.string.layout_arrow_row_subtitle),
        row(R.string.layout_arrow_row_order_title, R.string.layout_arrow_row_order_subtitle),
        row(R.string.layout_symbols_return_title, R.string.layout_symbols_return_subtitle),
        row(R.string.layout_symbols_return_chars_title),
        row(R.string.layout_symbols_return_space_title, R.string.layout_symbols_return_space_subtitle),
        row(R.string.layout_numeral_scope_title, R.string.layout_numeral_scope_subtitle),
        row(R.string.langemoji_lang_keymaps_title, R.string.langemoji_lang_keymaps_subtitle),
        // The width, the height and the side of the one-handed keyboard name
        // the orientation they belong to ("Portrait width"), so their titles
        // are format strings. The index has no orientation to put in one, and a
        // result reading "%1$s width" is worse than no result: the row above
        // opens the same group.
        row(R.string.layout_comma_emoji_title, R.string.layout_comma_emoji_subtitle),
        row(R.string.keypress_enter_emoji_title, R.string.keypress_enter_emoji_subtitle),
        row(R.string.layout_symbols_numpad_key_title, R.string.layout_symbols_numpad_key_subtitle),
        row(R.string.layout_show_globe_title, R.string.layout_show_globe_subtitle),
        row(R.string.layout_globe_recent_title, R.string.layout_globe_recent_subtitle),
        row(R.string.layout_globe_guard_title, R.string.layout_globe_guard_subtitle),
        row(R.string.layout_globe_emoji_title, R.string.layout_globe_emoji_subtitle),
        row(R.string.layout_swap_comma_globe_title, R.string.layout_swap_comma_globe_subtitle),
        row(R.string.layout_globe_in_one_place_title, R.string.layout_globe_in_one_place_subtitle),
        entry(R.string.layout_editor_import_title, R.string.layout_editor_import_subtitle, R.string.home_keymaps_title, "keymaps"),
        entry(R.string.panel_layouts_title, R.string.panel_layouts_subtitle, R.string.home_keymaps_title, "keymaps"),
        entry(
            R.string.layout_editor_tablet_expand_title,
            R.string.layout_editor_tablet_expand_subtitle,
            R.string.home_keymaps_title,
            "keymaps",
        ),
    )
}

/** Rows on the layout/size page, in screen order. */
private fun SearchStrings.layoutSizeRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.layout_size_position_title, "layout/size", screenParent = R.string.home_layout_title,
        weight = weight,
    )
    return listOf(
        row(R.string.layout_number_row_title, R.string.layout_number_row_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.layout_number_row_height_title, R.string.layout_number_row_height_subtitle),
        row(R.string.layout_resize_drag_title, R.string.layout_resize_drag_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.layout_key_height_title, R.string.layout_key_height_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.layout_bottom_row_height_title, R.string.layout_bottom_row_height_subtitle),
        row(R.string.layout_side_padding_left_title, R.string.layout_side_padding_left_subtitle),
        row(R.string.layout_side_padding_right_title, R.string.layout_side_padding_right_subtitle),
        row(R.string.layout_key_spacing_title, R.string.layout_key_spacing_subtitle),
        row(R.string.layout_extend_edge_keys_title, R.string.layout_extend_edge_keys_subtitle),
        row(R.string.layout_keyboard_scale_title, R.string.layout_keyboard_scale_subtitle),
        row(R.string.layout_bottom_padding_title, R.string.layout_bottom_padding_subtitle),
        row(R.string.layout_hide_system_keyboard_bar_title, R.string.layout_hide_system_keyboard_bar_subtitle),
        row(R.string.layout_board_corner_top_title, R.string.layout_board_corner_top_subtitle),
        row(R.string.layout_board_corner_bottom_title, R.string.layout_board_corner_bottom_subtitle),
        row(R.string.layout_keyboard_width_title, R.string.layout_keyboard_width_subtitle),
        row(R.string.layout_keyboard_position_title, R.string.layout_keyboard_position_info),
        row(R.string.layout_variant_follows_portrait_label),
        row(R.string.layout_font_size_title),
        row(R.string.layout_variant_allow_one_handed_title, R.string.layout_variant_allow_one_handed_subtitle),
        row(R.string.layout_variant_allow_split_title, R.string.layout_variant_allow_split_subtitle),
        row(R.string.layout_follow_portrait_title),
    )
}

/** Rows on the layout/onehanded page, in screen order. */
private fun SearchStrings.layoutOnehandedRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.layout_one_handed_group_title, "layout/onehanded", screenParent = R.string.home_layout_title,
        weight = weight,
    )
    return listOf(
        row(R.string.layout_one_handed_title, R.string.layout_one_handed_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.layout_one_handed_portrait_only_title, R.string.layout_one_handed_portrait_only_subtitle),
        row(R.string.layout_split_title, R.string.layout_split_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.layout_split_gap_title, R.string.layout_split_gap_subtitle),
        row(R.string.layout_split_spacebar_title, R.string.layout_split_spacebar_subtitle),
        row(R.string.layout_floating_title, R.string.layout_floating_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.layout_floating_landscape_title, R.string.layout_floating_landscape_subtitle),
        row(R.string.layout_landscape_fullscreen_title, R.string.layout_landscape_fullscreen_subtitle),
        row(R.string.layout_floating_handle_title, R.string.layout_floating_handle_subtitle),
        row(R.string.layout_floating_width_title, R.string.layout_floating_width_subtitle),
        row(R.string.layout_persistent_title, R.string.layout_persistent_subtitle, weight = EntryWeight.PRIMARY),
    )
}

/** Rows on Advanced › Share sheet (#970), in screen order. */
private fun SearchStrings.shareSheetRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.share_sheet_title, ShareSheetRoute,
        screenParent = R.string.home_advanced_title,
    )
    return listOf(
        row(R.string.share_sheet_enabled_title, R.string.share_sheet_enabled_subtitle),
        row(R.string.share_sheet_recents_title, R.string.share_sheet_recents_subtitle),
        row(R.string.share_sheet_pins_title),
        row(R.string.share_sheet_recents_clear_title),
    )
}

/** Rows on Advanced › Floating button (#755), in screen order. */
private fun SearchStrings.floatingButtonRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.floating_button_title, FloatingButtonRoute,
        screenParent = R.string.home_advanced_title,
    )
    return listOf(
        row(R.string.floating_button_enable_title, R.string.floating_button_enable_subtitle),
        row(R.string.floating_button_screen_title, R.string.floating_button_screen_subtitle),
        row(R.string.floating_button_tap_title),
        row(R.string.floating_button_double_tap_title),
        row(R.string.floating_button_hold_title),
        row(R.string.floating_button_tap_typing_title),
        row(R.string.floating_button_double_tap_typing_title),
        row(R.string.floating_button_menu_add_title),
        row(R.string.floating_button_menu_labels_title, R.string.floating_button_menu_labels_subtitle),
        row(R.string.floating_button_menu_gap_title, R.string.floating_button_menu_gap_subtitle),
        row(R.string.floating_button_menu_animation_title),
        row(R.string.floating_button_menu_reset_title, R.string.floating_button_menu_reset_subtitle),
        row(R.string.floating_button_style_title),
        row(R.string.floating_button_shape_title, R.string.floating_button_shape_subtitle),
        row(R.string.floating_button_size_title, R.string.floating_button_size_subtitle),
        row(R.string.floating_button_opacity_title, R.string.floating_button_opacity_subtitle),
        row(R.string.floating_button_idle_title, R.string.floating_button_idle_subtitle),
        row(R.string.floating_button_idle_opacity_title, R.string.floating_button_idle_opacity_subtitle),
        row(R.string.floating_button_tuck_title, R.string.floating_button_tuck_subtitle),
        row(R.string.floating_button_while_typing_title, R.string.floating_button_while_typing_subtitle),
        row(R.string.floating_button_over_keyboard_title, R.string.floating_button_over_keyboard_subtitle),
        row(R.string.floating_button_landscape_title, R.string.floating_button_landscape_subtitle),
        row(R.string.floating_button_haptics_title, R.string.floating_button_haptics_subtitle),
        row(R.string.floating_button_reset_position_title, R.string.floating_button_reset_position_subtitle),
    )
}

/**
 * Rows on the Languages screen, and the ones on a language's own page.
 *
 * A language page opens from the list with the language in the route, and the
 * index has no language to put there, so every row lands on the list. That is
 * still the screen the setting is behind, and the list is one press from it.
 */
private fun SearchStrings.languageRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_languages_title, "languages", weight = weight)
    return listOf(
        row(R.string.langemoji_lang_add_title),
        row(R.string.langemoji_lang_per_app_toggle_title, R.string.langemoji_lang_per_app_toggle_subtitle),
        row(R.string.langemoji_lang_app_rule_add),
        row(R.string.langemoji_lang_os_switcher_title, R.string.langemoji_lang_os_switcher_subtitle),
        row(R.string.langemoji_lang_carry_word_title, R.string.langemoji_lang_carry_word_subtitle),
        row(R.string.langemoji_lang_switcher_extra_title, R.string.langemoji_lang_switcher_extra_subtitle),
        row(R.string.langemoji_lang_app_name_first_title, R.string.langemoji_lang_app_name_first_subtitle),
        row(R.string.langemoji_lang_subtype_enabler_title, R.string.langemoji_lang_subtype_enabler_subtitle),
        entry(
            R.string.languages_more_layouts_title,
            screen = R.string.home_languages_title,
            route = "languages",
            keywords = R.string.search_kw_more_layouts,
        ),
        row(R.string.languages_conjunct_backspace_title),
        row(R.string.languages_bengali_vowel_twice_title, R.string.languages_bengali_vowel_twice_subtitle),
        // The subtitle names the language, so it is a format string with
        // nothing to fill it in here; the title carries the search.
        row(R.string.languages_translit_hints_row_title),
        row(R.string.languages_phonetic_strip_fixed_title),
        row(R.string.languages_raw_keys_chip_title, R.string.languages_raw_keys_chip_subtitle),
        row(R.string.languages_phonetic_strip_source_title, R.string.languages_phonetic_strip_source_subtitle),
        row(R.string.languages_phonetic_candidates_title, R.string.languages_phonetic_candidates_subtitle),
        // The subtitle is a format string (the download size); the title carries the search.
        row(R.string.languages_neural_phonetic_title),
        row(R.string.languages_neural_leads_title, R.string.languages_neural_leads_subtitle),
        row(R.string.languages_romanized_dictation_model_title),
        row(R.string.languages_phonetic_guide_title),
        row(R.string.languages_ansi_allowed_title, R.string.languages_ansi_allowed_subtitle),
        row(R.string.languages_ansi_version_title, R.string.languages_ansi_version_subtitle),
        row(R.string.languages_zawgyi_allowed_title, R.string.languages_zawgyi_allowed_subtitle),
        row(R.string.languages_zawgyi_key_font_title, R.string.languages_zawgyi_key_font_subtitle),
        row(R.string.languages_fancy_style_row_title, R.string.languages_fancy_style_row_subtitle),
        row(R.string.languages_fancy_fonts_add_title, R.string.languages_fancy_fonts_add_subtitle),
        row(R.string.languages_vietnamese_strict_tones_title, R.string.languages_vietnamese_strict_tones_subtitle),
        row(R.string.languages_vietnamese_restore_marks_title, R.string.languages_vietnamese_restore_marks_subtitle),
        // The subtitle names the language it is about, so it is a format string
        // with nothing to fill it in here. The title carries the search anyway.
        row(R.string.languages_numeral_system_title),
        row(R.string.languages_custom_dictionaries_title, R.string.languages_custom_dictionaries_subtitle),
        row(R.string.languages_dictionaries_title),
        row(R.string.languages_words_title),
        row(R.string.languages_emoji_keywords_title),
        row(R.string.languages_word_pairs_title),
        row(R.string.languages_cjk_traditional_title, R.string.languages_cjk_traditional_subtitle),
        row(R.string.languages_cjk_reading_in_field_title, R.string.languages_cjk_reading_in_field_subtitle),
        row(R.string.languages_cjk_reading_on_switch_title),
        row(R.string.languages_rime_engine_title, R.string.languages_rime_engine_subtitle),
        row(R.string.languages_rime_import_title, R.string.languages_rime_import_subtitle),
        row(R.string.languages_mozc_engine_title, R.string.languages_mozc_engine_subtitle),
        row(R.string.languages_mozc_clear_title, R.string.languages_mozc_clear_subtitle),
        row(R.string.languages_cjk_flex_import_title, R.string.languages_cjk_flex_import_subtitle),
        row(R.string.languages_cjk_lazy_title, R.string.languages_cjk_lazy_subtitle),
        row(R.string.languages_cjk_loose_marks_title, R.string.languages_cjk_loose_marks_subtitle),
        row(R.string.languages_cjk_full_width_space_title, R.string.languages_cjk_full_width_space_subtitle),
        row(R.string.languages_cjk_space_steps_title, R.string.languages_cjk_space_steps_subtitle),
        row(R.string.languages_cjk_jianpin_title, R.string.languages_cjk_jianpin_subtitle),
        row(R.string.languages_cjk_prediction_title, R.string.languages_cjk_prediction_subtitle),
        row(R.string.languages_cjk_shortcuts_title, R.string.languages_cjk_shortcuts_subtitle),
        row(R.string.languages_cjk_fuzzy_title, R.string.languages_cjk_fuzzy_subtitle),
        row(R.string.languages_cjk_double_pinyin_custom_title, R.string.languages_cjk_double_pinyin_custom_subtitle),
    )
}

/** Rows on the Emoji screen. */
private fun SearchStrings.emojiRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_emoji_title, "emoji", weight = weight)
    return listOf(
        row(R.string.langemoji_emoji_toolbar_title, R.string.langemoji_emoji_toolbar_subtitle),
        row(R.string.langemoji_strip_shortcut_title, R.string.langemoji_strip_shortcut_subtitle),
        row(R.string.langemoji_strip_toolbox_title, R.string.langemoji_strip_toolbox_subtitle),
        row(R.string.langemoji_strip_at_end_title, R.string.langemoji_strip_at_end_subtitle),
        row(R.string.langemoji_strip_own_size_title, R.string.langemoji_strip_own_size_subtitle),
        row(R.string.langemoji_strip_tool_width_title, R.string.langemoji_strip_tool_width_subtitle),
        row(R.string.langemoji_strip_icon_size_title, R.string.langemoji_strip_icon_size_subtitle),
        row(R.string.langemoji_emoji_full_bleed_title, R.string.langemoji_emoji_full_bleed_subtitle),
        row(R.string.panel_layout_row_title, R.string.panel_layout_row_subtitle),
        row(R.string.langemoji_emoji_prediction_title, R.string.langemoji_emoji_prediction_subtitle),
        row(R.string.langemoji_emoji_insert_mode_title, R.string.langemoji_emoji_insert_mode_subtitle),
        row(R.string.langemoji_emoji_row_title, R.string.langemoji_emoji_bar_mode_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.langemoji_emoji_bar_content_title, R.string.langemoji_emoji_bar_content_subtitle),
        row(R.string.langemoji_emoji_bar_count_title, R.string.langemoji_emoji_bar_count_subtitle),
        row(R.string.langemoji_emoji_bar_scroll_title, R.string.langemoji_emoji_bar_scroll_subtitle),
        row(R.string.langemoji_emoji_font_title, R.string.langemoji_emoji_font_subtitle),
        row(R.string.langemoji_emoji_skin_tone_title, R.string.langemoji_emoji_skin_tone_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.langemoji_emoji_tone_override_title, R.string.langemoji_emoji_tone_override_subtitle),
        // One title in every configuration now: an emoji the chosen font lacks
        // is drawn in the phone's own font rather than hidden, so the toggle is
        // always about the phone.
        row(R.string.langemoji_emoji_hide_unrenderable_title, R.string.langemoji_emoji_hide_unrenderable_subtitle),
        row(R.string.langemoji_emoji_unicode_search_title, R.string.langemoji_emoji_unicode_search_subtitle),
    )
}

/** Rows on the emoji/mixes page, in screen order. */
private fun SearchStrings.emojiMixRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.emojimix_row_title, "emoji/mixes", screenParent = R.string.home_emoji_title,
    )
    return listOf(
        row(R.string.emojimix_add_title, R.string.emojimix_add_subtitle),
        row(R.string.emojimix_panel_strip_title, R.string.emojimix_panel_strip_subtitle),
        row(R.string.emojimix_typing_title, R.string.emojimix_typing_subtitle),
        row(R.string.emojimix_style_title),
        row(R.string.emojimix_replace_title, R.string.emojimix_replace_subtitle),
        row(R.string.emojimix_panel_title, R.string.emojimix_panel_subtitle),
        row(R.string.emojimix_order_title),
        row(R.string.emojimix_send_title, R.string.emojimix_send_subtitle),
        row(R.string.emojimix_cell_size_title),
        row(R.string.emojimix_auto_refresh_title, R.string.emojimix_auto_refresh_subtitle),
        row(R.string.emojimix_clear_cache_title),
    )
}

/** Rows on the emoji/panel page, in screen order. */
private fun SearchStrings.emojiPanelRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.langemoji_emoji_panel_title, "emoji/panel", screenParent = R.string.home_emoji_title,
        weight = weight,
    )
    return listOf(
        row(R.string.langemoji_emoji_grid_size_title, R.string.langemoji_emoji_grid_size_subtitle),
        row(R.string.langemoji_emoji_tab_width_title, R.string.langemoji_emoji_tab_width_subtitle),
        row(R.string.langemoji_emoji_size_title, R.string.langemoji_emoji_size_subtitle),
        row(R.string.langemoji_emoji_grid_columns_title, R.string.langemoji_emoji_grid_columns_subtitle),
        row(R.string.langemoji_emoji_grid_row_height_title, R.string.langemoji_emoji_grid_row_height_subtitle),
        row(R.string.langemoji_emoji_tab_mode_title, R.string.langemoji_emoji_tab_mode_subtitle),
        row(R.string.langemoji_emoji_clear_recents_title, R.string.langemoji_emoji_clear_recents_subtitle),
        row(R.string.langemoji_emoji_kaomoji_title, R.string.langemoji_emoji_kaomoji_subtitle),
        row(R.string.langemoji_emoji_long_press_name_title, R.string.langemoji_emoji_long_press_name_subtitle),
        row(R.string.langemoji_emoji_animated_title, R.string.langemoji_emoji_animated_subtitle),
        row(R.string.langemoji_emoji_sticker_title, R.string.langemoji_emoji_sticker_subtitle),
        row(R.string.langemoji_emoji_close_after_insert_title, R.string.langemoji_emoji_close_after_insert_subtitle),
        row(R.string.langemoji_media_switcher_title, R.string.langemoji_media_switcher_subtitle),
        row(R.string.langemoji_media_remember_title, R.string.langemoji_media_remember_subtitle),
        row(R.string.langemoji_emoji_continuous_title, R.string.langemoji_emoji_continuous_subtitle),
        row(R.string.langemoji_panel_height_title, R.string.langemoji_panel_height_subtitle),
    )
}

/** Rows on the kaomoji page, in screen order. */
private fun SearchStrings.kaomojiRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0) = entry(
        title, subtitle, R.string.langemoji_kaomoji_title, "kaomoji", screenParent = R.string.langemoji_emoji_panel_title,
    )
    return listOf(
        row(R.string.langemoji_kaomoji_split_title, R.string.langemoji_kaomoji_split_subtitle),
        row(R.string.langemoji_kaomoji_tag_tabs_title, R.string.langemoji_kaomoji_tag_tabs_subtitle),
        row(R.string.langemoji_kaomoji_start_title, R.string.langemoji_kaomoji_start_subtitle),
        row(R.string.langemoji_kaomoji_rail_title, R.string.langemoji_kaomoji_rail_subtitle),
        row(R.string.langemoji_kaomoji_suggest_title, R.string.langemoji_kaomoji_suggest_subtitle),
        row(R.string.langemoji_kaomoji_in_search_title, R.string.langemoji_kaomoji_in_search_subtitle),
        row(R.string.langemoji_kaomoji_import_title, R.string.langemoji_kaomoji_import_subtitle),
        row(R.string.langemoji_kaomoji_export_title, R.string.langemoji_kaomoji_export_subtitle),
        row(R.string.langemoji_kaomoji_reset_title, R.string.langemoji_kaomoji_reset_subtitle),
    )
}

/** Rows on the Voice typing screen. */
private fun SearchStrings.voiceRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_voice_title, "voice", weight = weight)
    return listOf(
        row(R.string.voice_enabled_title, R.string.voice_enabled_subtitle),
        // The engines are a provider list now; its heading is the title.
        row(R.string.voice_engine_title, R.string.providers_add_subtitle),
        row(R.string.voice_languages_title, R.string.voice_languages_subtitle),
        row(R.string.voice_engine_languages_title, R.string.voice_engine_languages_subtitle),
        row(R.string.voice_ui_title, R.string.voice_ui_subtitle),
        row(R.string.voice_strip_language_title, R.string.voice_strip_language_subtitle),
        row(R.string.voice_typing_title, R.string.voice_typing_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.voice_hold_picks_title, R.string.voice_hold_picks_subtitle),
        row(R.string.voice_resume_title, R.string.voice_resume_subtitle),
        row(R.string.voice_pause_media_title, R.string.voice_pause_media_subtitle),
        row(R.string.voice_mic_switch_title, R.string.voice_mic_switch_subtitle),
        row(R.string.voice_mic_title, R.string.voice_mic_subtitle),
        row(R.string.voice_engine_picker_title, R.string.voice_engine_picker_subtitle),
        row(R.string.voice_hold_title, R.string.voice_hold_subtitle),
        row(R.string.voice_continuous_title, R.string.voice_continuous_subtitle),
        row(R.string.voice_silence_stop_title, R.string.voice_silence_stop_subtitle),
        row(R.string.voice_vad_title, R.string.voice_vad_subtitle),
        row(R.string.voice_vad_threshold_title, R.string.voice_vad_threshold_subtitle),
        row(R.string.voice_smart_turn_title, R.string.voice_smart_turn_subtitle),
        row(R.string.voice_denoise_title, R.string.voice_denoise_subtitle),
        row(R.string.voice_punctuation_title, R.string.voice_punctuation_subtitle),
        row(R.string.voice_punctuation_choice_title, R.string.voice_punctuation_choice_subtitle),
        row(R.string.voice_punctuation_custom_add_title, R.string.voice_punctuation_custom_add_subtitle),
        row(R.string.voice_auto_punctuation_title, R.string.voice_auto_punctuation_subtitle),
        row(R.string.voice_ai_tidy_title, R.string.voice_ai_tidy_subtitle),
        // On the engines' own screens, opened from their rows on the list.
        providerEntry(
            ProviderTool.VOICE, ProviderTypes.SYSTEM_RECOGNIZER,
            R.string.voice_system_on_device_title, R.string.voice_system_on_device_subtitle,
        ),
        providerEntry(ProviderTool.VOICE, ProviderTypes.WHISPER, R.string.voice_translate_title, R.string.voice_translate_subtitle),
        providerEntry(
            ProviderTool.VOICE, ProviderTypes.WHISPER,
            R.string.models_whisper_compute_title, R.string.models_whisper_compute_subtitle,
        ),
        providerEntry(
            ProviderTool.VOICE, ProviderTypes.TRANSCRIPTION_SERVER,
            R.string.voice_server_language_title, R.string.voice_server_language_subtitle,
        ),
        row(R.string.voice_bias_personal_title, R.string.voice_bias_personal_subtitle),
    )
}

/**
 * Rows on the Clipboard hub: the history switch, which is also the door to
 * the history page, and the Panel fold (SETTINGS_REDESIGN.md §4). The rest of
 * the old screen is on the three pages below.
 */
private fun SearchStrings.clipboardRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_clipboard_title, "clipboard", weight = weight)
    return listOf(
        row(R.string.clipboard_history_title, R.string.clipboard_history_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.panel_layout_row_title, R.string.panel_layout_row_subtitle),
        row(R.string.clipboard_full_bleed_title, R.string.clipboard_full_bleed_subtitle),
        row(R.string.clipboard_panel_height_title, R.string.clipboard_panel_height_subtitle),
        row(R.string.clipboard_over_keys_height_title, R.string.clipboard_over_keys_height_subtitle),
        row(R.string.clipboard_key_row_title, R.string.clipboard_key_row_subtitle),
        row(R.string.clipboard_view_title, R.string.clipboard_view_subtitle),
        row(R.string.clipboard_columns_title, R.string.clipboard_columns_subtitle),
        row(R.string.clipboard_lines_title, R.string.clipboard_lines_subtitle),
        row(R.string.clipboard_line_spacing_title, R.string.clipboard_line_spacing_subtitle),
        row(R.string.clipboard_time_title, R.string.clipboard_time_subtitle),
        row(R.string.clipboard_numbers_title, R.string.clipboard_numbers_subtitle),
        row(R.string.clipboard_pinned_tabs_title, R.string.clipboard_pinned_tabs_subtitle),
        row(R.string.clipboard_outline_pinned_title, R.string.clipboard_outline_pinned_subtitle),
        row(R.string.clipboard_card_buttons_title, R.string.clipboard_card_buttons_subtitle),
        row(R.string.clipboard_card_shortcut_title, R.string.clipboard_card_shortcut_subtitle),
        row(R.string.clipboard_type_out_title, R.string.clipboard_type_out_subtitle),
        row(R.string.clipboard_bump_on_paste_title, R.string.clipboard_bump_on_paste_subtitle),
        row(R.string.clipboard_type_tags_title, R.string.clipboard_type_tags_subtitle),
        row(R.string.clipboard_keep_rich_text_title, R.string.clipboard_keep_rich_text_subtitle),
        row(R.string.clipboard_swipe_delete_title, R.string.clipboard_swipe_delete_subtitle),
        row(R.string.clipboard_swipe_pin_title, R.string.clipboard_swipe_pin_subtitle),
        row(R.string.clipboard_undo_delete_title, R.string.clipboard_undo_delete_subtitle),
        row(R.string.clipboard_confirm_pinned_delete_title, R.string.clipboard_confirm_pinned_delete_subtitle),
        row(R.string.clipboard_search_title, R.string.clipboard_search_subtitle),
        row(R.string.clipboard_search_regex_title, R.string.clipboard_search_regex_subtitle),
        row(R.string.clipboard_clear_button_title, R.string.clipboard_clear_button_subtitle),
        row(R.string.clipboard_link_previews_title, R.string.clipboard_link_previews_subtitle),
        row(R.string.clipboard_toast_title, R.string.clipboard_toast_subtitle),
    )
}

/** Rows on the clipboard/history page, in screen order. */
private fun SearchStrings.clipboardHistoryRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.clipboard_history_title, "clipboard/history", screenParent = R.string.home_clipboard_title,
        weight = weight,
    )
    return listOfNotNull(
        row(R.string.clipboard_max_title, R.string.clipboard_max_subtitle),
        row(R.string.clipboard_expiry_title, R.string.clipboard_expiry_subtitle),
        row(R.string.clipboard_max_chars_title, R.string.clipboard_max_chars_subtitle),
        row(R.string.clipboard_pinned_last_title, R.string.clipboard_pinned_last_subtitle),
        row(R.string.clipboard_pinned_in_place_title, R.string.clipboard_pinned_in_place_subtitle),
        row(R.string.clipboard_pinned_folders_title, R.string.clipboard_pinned_folders_subtitle),
        row(R.string.clipboard_screenshots_title, R.string.clipboard_screenshots_subtitle)
            .takeIf { ChannelFeatures.SCREENSHOT_CLIPS },
        row(R.string.clipboard_track_source_title, R.string.clipboard_track_source_subtitle),
    )
}

/** Rows on the clipboard/chips page, in screen order. */
private fun SearchStrings.clipboardChipsRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.clipboard_page_chips_title, "clipboard/chips", screenParent = R.string.home_clipboard_title,
        weight = weight,
    )
    return listOf(
        row(R.string.clipboard_suggest_recent_title, R.string.clipboard_suggest_recent_subtitle, weight = EntryWeight.PRIMARY),
        row(R.string.clipboard_chip_life_title, R.string.clipboard_chip_life_subtitle),
        row(R.string.clipboard_recent_chips_title, R.string.clipboard_recent_chips_subtitle),
        row(R.string.clipboard_compact_paste_chip_title, R.string.clipboard_compact_paste_chip_subtitle),
        row(R.string.clipboard_suggest_codes_title, R.string.clipboard_suggest_codes_subtitle),
        row(R.string.clipboard_entities_title, R.string.clipboard_entities_subtitle),
        row(R.string.clipboard_entity_icons_title, R.string.clipboard_entity_icons_subtitle),
        row(R.string.clipboard_entity_to_clipboard_title, R.string.clipboard_entity_to_clipboard_subtitle),
        row(R.string.clipboard_fragments_on_strip_title, R.string.clipboard_fragments_on_strip_subtitle),
    )
}

/**
 * Rows on the clipboard/rules page, in screen order. Copy rules and Phone
 * number formats are screens of their own and are indexed in sectionRows.
 */
private fun SearchStrings.clipboardRulesRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.clipboard_page_rules_title, "clipboard/rules", screenParent = R.string.home_clipboard_title,
        weight = weight,
    )
    return listOf(
        row(R.string.clipboard_private_copies_title, R.string.clipboard_private_copies_subtitle),
        row(R.string.clipboard_password_paste_title, R.string.clipboard_password_paste_subtitle),
        row(R.string.clipboard_sensitive_title, R.string.clipboard_sensitive_subtitle),
        row(R.string.clipboard_detect_sensitive_title, R.string.clipboard_detect_sensitive_subtitle),
        row(R.string.clipboard_sensitive_expiry_title, R.string.clipboard_sensitive_expiry_subtitle),
    )
}

/**
 * Rows on the Text Expander screen.
 *
 * The snippets themselves are not here: they are the user's own text, and the
 * index is built once per screen from resources. The two reference cards are,
 * because template variables and patterns are what someone looking for the
 * feature actually types.
 */
private fun SearchStrings.expanderRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_expander_title, "expander", weight = weight)
    return listOf(
        row(R.string.expander_variables_title),
        row(R.string.expander_pattern_title),
        row(R.string.expander_multi_expand_title, R.string.expander_multi_expand_subtitle),
        row(R.string.expander_grid_columns_title, R.string.expander_grid_columns_subtitle),
        row(R.string.expander_titles_only_title, R.string.expander_titles_only_subtitle),
        row(R.string.expander_panel_stays_title, R.string.expander_panel_stays_subtitle),
        row(R.string.expander_secure_fields_title, R.string.expander_secure_fields_subtitle),
        row(R.string.expander_escape_title, R.string.expander_escape_subtitle),
        row(R.string.expander_add_action),
        row(R.string.expander_reorder_title),
        // Espanso is the name somebody arriving from that app will search for,
        // and it appears nowhere else in the index.
        row(R.string.expander_import_source_title, R.string.expander_source_espanso_body),
        row(R.string.expander_export_target_title, R.string.expander_target_espanso_body),
    )
}

/** Rows on the tool pages, from Emoji through One-handed mode. */
private fun SearchStrings.toolPageRowsA(): List<SettingsSearchEntry> = listOfNotNull(
    toolEntry(ToolbarTool.EMOJI, R.string.tooldetail_emoji_all_title, R.string.tooldetail_emoji_all_subtitle, weight = EntryWeight.MIRROR),
    toolEntry(
        ToolbarTool.CLIPBOARD,
        R.string.tooldetail_clipboard_all_title,
        R.string.tooldetail_clipboard_all_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.SNIPPETS,
        R.string.tooldetail_snippets_all_title,
        R.string.tooldetail_snippets_all_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.SPLIT,
        R.string.tooldetail_layout_nav_title,
        R.string.tooldetail_layout_nav_split_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.FLOATING,
        R.string.tooldetail_layout_nav_title,
        R.string.tooldetail_layout_nav_floating_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.PERSISTENT,
        R.string.tooldetail_layout_nav_title,
        R.string.tooldetail_layout_nav_persistent_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.FLASHLIGHT, R.string.tooldetail_flashlight_auto_off_title, R.string.tooldetail_flashlight_auto_off_subtitle),
    toolEntry(ToolbarTool.COMPASS, R.string.tooldetail_compass_degrees_title, R.string.tooldetail_compass_degrees_subtitle),
    toolEntry(ToolbarTool.COMPASS, R.string.tooldetail_compass_qibla_title, R.string.tooldetail_compass_qibla_subtitle),
    toolEntry(ToolbarTool.LEVEL, R.string.tooldetail_level_angles_title, R.string.tooldetail_level_angles_subtitle),
    toolEntry(ToolbarTool.UNDO, R.string.tooldetail_redo_ctrl_y_title, R.string.tooldetail_redo_ctrl_y_subtitle),
    toolEntry(ToolbarTool.REDO, R.string.tooldetail_redo_ctrl_y_title, R.string.tooldetail_redo_ctrl_y_subtitle),
    toolEntry(ToolbarTool.MOON_PHASE, R.string.tooldetail_moon_southern_title, R.string.tooldetail_moon_southern_subtitle),
    toolEntry(ToolbarTool.MOON_PHASE, R.string.tooldetail_moon_arabic_title, R.string.tooldetail_moon_arabic_subtitle),
    toolEntry(ToolbarTool.WEATHER, R.string.tooldetail_weather_fahrenheit_title, R.string.tooldetail_weather_fahrenheit_subtitle),
    toolEntry(ToolbarTool.WEATHER, R.string.tooldetail_weather_auto_fetch_title, R.string.tooldetail_weather_auto_fetch_subtitle),
    // The Clock tool.
    toolEntry(ToolbarTool.CLOCK, R.string.clock_open_title, R.string.clock_open_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_hour_format_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_show_seconds_title, R.string.clock_show_seconds_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_sort_zones_title, R.string.clock_sort_zones_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_zones_grid_title, R.string.clock_zones_grid_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_insert_format_title, R.string.clock_insert_format_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_alarm_sound_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_timer_sound_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_vibrate_title, R.string.clock_vibrate_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_snooze_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_fade_title, R.string.clock_fade_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_auto_silence_title, R.string.clock_auto_silence_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_auto_snooze_title, R.string.clock_auto_snooze_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_upcoming_title, R.string.clock_upcoming_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_snoozed_notice_title, R.string.clock_snoozed_notice_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_missed_notice_title, R.string.clock_missed_notice_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_volume_keys_title, R.string.clock_volume_keys_subtitle),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_focus_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_short_break_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_long_break_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_long_every_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_work_start_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_work_end_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_history_keep_title),
    toolEntry(ToolbarTool.CLOCK, R.string.clock_grant_exact_title, R.string.clock_grant_exact_subtitle),
    // The Location tool (#350).
    toolEntry(ToolbarTool.LOCATION, R.string.location_format_title, R.string.location_format_subtitle),
    toolEntry(ToolbarTool.LOCATION, R.string.location_map_link_title, R.string.location_map_link_subtitle),
    toolEntry(ToolbarTool.LOCATION, R.string.location_precision_title, R.string.location_precision_subtitle),
    toolEntry(ToolbarTool.LOCATION, R.string.location_lookup_address_title, R.string.location_lookup_address_subtitle),
    toolEntry(ToolbarTool.LOCATION, R.string.location_locate_on_open_title, R.string.location_locate_on_open_subtitle),
    toolEntry(ToolbarTool.LOCATION, R.string.location_template_label),
    toolEntry(ToolbarTool.LOCATION, R.string.location_tiles_label, R.string.location_tiles_group),
    toolEntry(ToolbarTool.LOCATION, R.string.location_places_group, R.string.location_places_info),
    toolEntry(ToolbarTool.LOCATION, R.string.location_permission_title, R.string.location_permission_subtitle),
    // The Music ID tool.
    toolEntry(ToolbarTool.MUSIC_ID, R.string.music_providers_title, R.string.music_providers_info),
    toolEntry(ToolbarTool.MUSIC_ID, R.string.music_text_format_title, R.string.music_text_format_subtitle),
    toolEntry(ToolbarTool.MUSIC_ID, R.string.music_card_style_title, R.string.music_card_style_subtitle),
    toolEntry(ToolbarTool.MUSIC_ID, R.string.music_open_link_title, R.string.music_open_link_subtitle),
    toolEntry(ToolbarTool.MUSIC_ID, R.string.music_clip_seconds_title, R.string.music_clip_seconds_subtitle),
    toolEntry(ToolbarTool.MUSIC_ID, R.string.music_now_playing_first_title, R.string.music_now_playing_first_subtitle),
    toolEntry(ToolbarTool.MUSIC_ID, R.string.music_keep_history_title, R.string.music_keep_history_subtitle),
    toolEntry(ToolbarTool.MUSIC_ID, R.string.music_history_open_title, R.string.music_history_open_subtitle),
    // The smart home tool.
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_enabled_title, R.string.ha_enabled_subtitle),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.smarthome_homes_title, R.string.smarthome_homes_info),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_favourites_title, R.string.ha_favourites_subtitle_none),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_open_on_title),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_areas_tabs_title, R.string.ha_areas_tabs_subtitle),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_tile_size_title),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_confirm_risky_title, R.string.ha_confirm_risky_subtitle),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_insert_format_title),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_assist_enabled_title, R.string.ha_assist_enabled_subtitle),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_assist_agent_title, R.string.ha_assist_agent_subtitle),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_assist_language_title, R.string.ha_assist_language_subtitle),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_lifetime_title, R.string.ha_lifetime_info),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_cache_title, R.string.ha_cache_subtitle),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_ai_tools_title, R.string.ha_ai_tools_subtitle),
    toolEntry(ToolbarTool.HOME_ASSISTANT, R.string.ha_smart_chip_title, R.string.ha_smart_chip_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_first_title, R.string.tooldetail_calendar_first_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_second_title, R.string.tooldetail_calendar_second_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.toolai_weekend_title, R.string.toolai_weekend_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_hijri_title, R.string.tooldetail_calendar_hijri_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_save_mode_title, R.string.tooldetail_calendar_save_mode_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_default_calendar_title, R.string.tooldetail_calendar_default_calendar_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_default_length_title, R.string.tooldetail_calendar_default_length_subtitle),
    toolEntry(ToolbarTool.CALENDAR, R.string.tooldetail_calendar_default_reminder_title, R.string.tooldetail_calendar_default_reminder_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_front_title, R.string.tooldetail_camera_front_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_mirror_title, R.string.tooldetail_camera_mirror_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_fullframe_title, R.string.tooldetail_camera_fullframe_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_gallery_title, R.string.tooldetail_camera_gallery_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_search_button_title, R.string.tooldetail_camera_search_button_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.location_camera_stamp_title, R.string.location_camera_stamp_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.location_camera_caption_title, R.string.location_camera_caption_subtitle),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_search_with_title),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_search_engine_title),
    // #727: the photo-translate provider list.
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_translate_group, keywords = R.string.search_kw_photo_translate),
    toolEntry(ToolbarTool.CAMERA, R.string.tooldetail_camera_shutter_title, R.string.tooldetail_camera_shutter_subtitle),
    toolEntry(ToolbarTool.DICTIONARY, R.string.tooldetail_dictionary_auto_title, R.string.tooldetail_dictionary_auto_subtitle),
    toolEntry(ToolbarTool.DICTIONARY, R.string.tooldetail_dictionary_sources_title),
    // #764: the word in other languages.
    toolEntry(ToolbarTool.DICTIONARY, R.string.tooldetail_dictionary_translations_title, R.string.tooldetail_dictionary_translations_subtitle),
    toolEntry(ToolbarTool.DICTIONARY, R.string.tooldetail_dictionary_translation_langs_title),
    toolEntry(
        ToolbarTool.VOCABULARY,
        R.string.tooldetail_vocab_packs_title,
        R.string.tooldetail_vocab_packs_none_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.VOCABULARY,
        R.string.tooldetail_vocab_lists_title,
        R.string.tooldetail_vocab_lists_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.VOCABULARY,
        R.string.tooldetail_vocab_review_title,
        R.string.tooldetail_vocab_review_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.VOCABULARY,
        R.string.tooldetail_vocab_browse_title,
        R.string.tooldetail_vocab_browse_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_nudges_title, R.string.tooldetail_vocab_nudges_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_nudge_self_title, R.string.tooldetail_vocab_nudge_self_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_tap_title, R.string.tooldetail_vocab_tap_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_scope_title, 0),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_level_title, R.string.tooldetail_vocab_level_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_cooldown_title, 0),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_related_title, R.string.tooldetail_vocab_related_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_translations_title, R.string.tooldetail_vocab_translations_auto_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.providers_group_title, R.string.providers_add_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_audio_source_title, 0),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_accent_title, R.string.tooldetail_vocab_accent_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_tts_rate_title, 0),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_tts_pitch_title, 0),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_audio_test_title, 0),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_scheduler_title, 0),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_goal_title, R.string.tooldetail_vocab_goal_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_wotd_card_title, R.string.tooldetail_vocab_wotd_card_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_wotd_chip_title, R.string.tooldetail_vocab_wotd_chip_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_wotd_interval_title, R.string.tooldetail_vocab_wotd_interval_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_wotd_chip_times_title, R.string.tooldetail_vocab_wotd_chip_times_subtitle),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_ipa_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_respelling_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_examples_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_quotations_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_synonyms_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_antonyms_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_family_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_hypernyms_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_tags_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_topics_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_etymology_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_origin_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_root_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_attested_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_mnemonic_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_translations_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_sources_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_hyphenation_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_rhymes_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_forms_title),
    toolEntry(ToolbarTool.VOCABULARY, R.string.tooldetail_vocab_field_wikipedia_title),
    toolEntry(ToolbarTool.TEXT_EDIT, R.string.tooldetail_text_edit_repeat_title, R.string.tooldetail_text_edit_repeat_subtitle),
    toolEntry(ToolbarTool.TEXT_EDIT, R.string.panel_layout_row_title, R.string.panel_layout_row_subtitle),
    toolEntry(ToolbarTool.TRACKPAD, R.string.tooldetail_trackpad_step_x_title, R.string.tooldetail_trackpad_step_x_subtitle),
    toolEntry(ToolbarTool.TRACKPAD, R.string.tooldetail_trackpad_step_y_title, R.string.tooldetail_trackpad_step_y_subtitle),
    toolEntry(ToolbarTool.TRACKPAD, R.string.tooldetail_trackpad_hold_title, R.string.tooldetail_trackpad_hold_subtitle),
    toolEntry(ToolbarTool.TRACKPAD, R.string.tooldetail_trackpad_taps_title, R.string.tooldetail_trackpad_taps_subtitle),
    toolEntry(ToolbarTool.TRACKPAD, R.string.tooldetail_trackpad_haptics_title, R.string.tooldetail_trackpad_haptics_subtitle),
    toolEntry(ToolbarTool.TRACKPAD, R.string.tooldetail_trackpad_trail_title, R.string.tooldetail_trackpad_trail_subtitle),
    toolEntry(ToolbarTool.TRACKPAD, R.string.tooldetail_trackpad_magnifier_title, R.string.tooldetail_trackpad_magnifier_subtitle),
    // One switch drawn on all eight caret tools' pages, indexed once. Eight
    // results with the same title would read as a broken search, and the switch
    // is the same one wherever it is flipped — so the first of them answers for
    // the set.
    toolEntry(
        ToolbarTool.CURSOR_LEFT,
        R.string.tooldetail_cursor_repeat_title,
        R.string.tooldetail_cursor_repeat_subtitle,
    ),
    // The toolbox switch is per tool, so unlike the one above it genuinely has
    // eight different answers. Still indexed once: eight rows with the same
    // title would read as a broken search, and the first of them is the way in
    // to the rest.
    toolEntry(
        ToolbarTool.CURSOR_LEFT,
        R.string.tooldetail_cursor_repeat_toolbox_title,
        R.string.tooldetail_cursor_repeat_toolbox_subtitle,
    ),
    // Drawn on the Word left, Word right and Select word pages, one setting
    // for the three, so indexed once like the caret switches above.
    toolEntry(
        ToolbarTool.CURSOR_WORD_LEFT,
        R.string.tooldetail_word_steps_title,
        R.string.tooldetail_word_steps_subtitle,
    ),
    toolEntry(
        ToolbarTool.CURSOR_WORD_LEFT,
        R.string.tooldetail_word_steps_capitals_title,
        R.string.tooldetail_word_steps_capitals_subtitle,
    ),
    toolEntry(
        ToolbarTool.CURSOR_WORD_LEFT,
        R.string.tooldetail_word_steps_digits_title,
        R.string.tooldetail_word_steps_digits_subtitle,
    ),
    toolEntry(
        ToolbarTool.SELECT_MODE,
        R.string.tooldetail_select_mode_hold_title,
        R.string.tooldetail_select_mode_hold_subtitle,
    ),
    toolEntry(
        ToolbarTool.SELECT_MODE,
        R.string.tooldetail_select_mode_taps_title,
        R.string.tooldetail_select_mode_taps_subtitle,
    ),
    toolEntry(ToolbarTool.NUMPAD, R.string.tooldetail_numpad_calc_title, R.string.tooldetail_numpad_calc_subtitle),
    toolEntry(ToolbarTool.FIND_REPLACE, R.string.tooldetail_find_history_title, R.string.tooldetail_find_history_subtitle),
    toolEntry(ToolbarTool.NOTES, R.string.tooldetail_notes_open_title),
    toolEntry(ToolbarTool.NOTES, R.string.tooldetail_notes_sort_title),
    toolEntry(ToolbarTool.NOTES, R.string.tooldetail_notes_markdown_title, R.string.tooldetail_notes_markdown_subtitle),
    toolEntry(ToolbarTool.NOTES, R.string.tooldetail_notes_panel_start_title, R.string.tooldetail_notes_panel_start_subtitle),
    toolEntry(ToolbarTool.NOTES, R.string.tooldetail_notes_panel_layout_title, R.string.tooldetail_notes_panel_layout_subtitle),
    toolEntry(ToolbarTool.NOTES, R.string.tooldetail_notes_trash_days_title),
    toolEntry(ToolbarTool.NOTES, R.string.tooldetail_notes_save_selection_title, R.string.tooldetail_notes_save_selection_subtitle),
    toolEntry(ToolbarTool.NOTES, R.string.tooldetail_notes_clear_title, R.string.tooldetail_notes_clear_subtitle),
    toolEntry(ToolbarTool.TODO, R.string.tooldetail_todo_open_title),
    toolEntry(ToolbarTool.TODO, R.string.tooldetail_todo_hide_done_title, R.string.tooldetail_todo_hide_done_subtitle),
    toolEntry(ToolbarTool.TODO, R.string.tooldetail_todo_due_chip_title, R.string.tooldetail_todo_due_chip_subtitle),
    toolEntry(ToolbarTool.TODO, R.string.tooldetail_todo_sync_title, R.string.tooldetail_todo_sync_subtitle),
    toolEntry(ToolbarTool.TODO, R.string.tooldetail_todo_import_title, R.string.tooldetail_todo_import_subtitle),
    toolEntry(ToolbarTool.TODO, R.string.tooldetail_todo_clear_title, R.string.tooldetail_todo_clear_subtitle),
    toolEntry(ToolbarTool.INCOGNITO, R.string.tooldetail_incognito_learning_title, R.string.tooldetail_incognito_learning_subtitle),
    toolEntry(ToolbarTool.INCOGNITO, R.string.tooldetail_incognito_clipboard_title, R.string.tooldetail_incognito_clipboard_subtitle),
    toolEntry(
        ToolbarTool.INCOGNITO,
        R.string.tooldetail_incognito_private_clipboard_title,
        R.string.tooldetail_incognito_private_clipboard_subtitle,
    ),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_now_title, R.string.tooldetail_power_now_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_preset_title, R.string.tooldetail_power_preset_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_trigger_title, R.string.tooldetail_power_trigger_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_battery_title, R.string.tooldetail_power_battery_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_charging_title, R.string.tooldetail_power_charging_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_haptics_title, R.string.tooldetail_power_drop_haptics_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_sound_title, R.string.tooldetail_power_drop_sound_subtitle),
    toolEntry(
        ToolbarTool.POWER_SAVING,
        R.string.tooldetail_power_drop_glass_blur_title,
        R.string.tooldetail_power_drop_glass_blur_subtitle,
    ),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_glide_title, R.string.tooldetail_power_drop_glide_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_emoji_title, R.string.tooldetail_power_drop_emoji_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_anim_title, R.string.tooldetail_power_drop_anim_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_trail_title, R.string.tooldetail_power_drop_trail_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_chips_title, R.string.tooldetail_power_drop_chips_subtitle),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_network_title, R.string.tooldetail_power_drop_network_subtitle),
    toolEntry(
        ToolbarTool.POWER_SAVING,
        R.string.tooldetail_power_drop_screenshot_title,
        R.string.tooldetail_power_drop_screenshot_subtitle,
    ),
    toolEntry(ToolbarTool.POWER_SAVING, R.string.tooldetail_power_drop_models_title, R.string.tooldetail_power_drop_models_subtitle),
    toolEntry(
        ToolbarTool.POWER_SAVING,
        R.string.tooldetail_power_drop_media_pin_title,
        R.string.tooldetail_power_drop_media_pin_subtitle,
    ),
    toolEntry(
        ToolbarTool.MEDIA_CONTROL,
        R.string.tooldetail_mediactl_pin_title,
        R.string.tooldetail_mediactl_pin_subtitle,
    ),
    // Subtitle left off: the drawn one counts the chosen apps, and a number
    // that changes as the user ticks is not something to search for.
    toolEntry(ToolbarTool.MEDIA_CONTROL, R.string.tooldetail_mediactl_apps_title),
    // Same reason: the drawn subtitle says whether the grant is in place.
    toolEntry(ToolbarTool.MEDIA_CONTROL, R.string.tooldetail_mediactl_access_title),
    // KDE Connect (#285): every row on the tool's own page.
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_enabled_title, R.string.kdeconnect_enabled_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_lifetime_title, R.string.kdeconnect_lifetime_keyboard_detail),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_auto_connect_title, R.string.kdeconnect_auto_connect_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_clipboard_receive_title, R.string.kdeconnect_clipboard_receive_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_clear_secrets_title, R.string.kdeconnect_clear_secrets_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_clipboard_send_title, R.string.kdeconnect_clipboard_send_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_remote_typing_title, R.string.kdeconnect_remote_typing_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_pipeline_title, R.string.kdeconnect_pipeline_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_pad_speed_title),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_pad_accel_title, R.string.kdeconnect_pad_accel_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_scroll_speed_title),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_natural_scroll_title, R.string.kdeconnect_natural_scroll_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_tap_click_title, R.string.kdeconnect_tap_click_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_pad_haptics_title),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_receive_files_title, R.string.kdeconnect_receive_files_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_share_sheet_title, R.string.kdeconnect_share_sheet_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_battery_title, R.string.kdeconnect_battery_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_codes_title, R.string.kdeconnect_codes_subtitle),
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_media_title, R.string.kdeconnect_media_subtitle),
    // Subtitle left off: the drawn one says whether the grant is in place.
    toolEntry(ToolbarTool.KDE_CONNECT, R.string.kdeconnect_media_access_title),
    toolEntry(
        ToolbarTool.APP_LAUNCHER,
        R.string.tooldetail_launcher_open_mode_title,
        R.string.tooldetail_launcher_open_mode_subtitle,
    ),
    // Subtitle left off: it counts the pairs.
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_combos_title),
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_sort_title, R.string.tooldetail_launcher_sort_subtitle),
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_labels_title, R.string.tooldetail_launcher_labels_subtitle),
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_columns_title, R.string.tooldetail_launcher_columns_subtitle),
    toolEntry(
        ToolbarTool.APP_LAUNCHER,
        R.string.tooldetail_launcher_icon_size_title,
        R.string.tooldetail_launcher_icon_size_subtitle,
    ),
    toolEntry(
        ToolbarTool.APP_LAUNCHER,
        R.string.tooldetail_launcher_icon_shape_title,
        R.string.tooldetail_launcher_icon_shape_subtitle,
    ),
    // Subtitle left off: it counts the hidden apps.
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_hidden_title),
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_work_title, R.string.tooldetail_launcher_work_subtitle),
    // Subtitle left off: it counts the folders.
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_folders_title),
    toolEntry(ToolbarTool.APP_LAUNCHER, R.string.tooldetail_launcher_recents_title, R.string.tooldetail_launcher_recents_subtitle),
    toolEntry(
        ToolbarTool.APP_LAUNCHER,
        R.string.tooldetail_launcher_drilldown_title,
        R.string.tooldetail_launcher_drilldown_subtitle,
    ),
    toolEntry(
        ToolbarTool.APP_LAUNCHER,
        R.string.tooldetail_launcher_non_exported_title,
        R.string.tooldetail_launcher_non_exported_subtitle,
    ),
    // The tool screen's own "Autocorrect" toggle is not listed: toolRows
    // already indexes the tool under that title and route. A row named after
    // the tool holding it is always a duplicate result, never a second one.
    toolEntry(
        ToolbarTool.AUTOCORRECT,
        R.string.tooldetail_typing_nav_title,
        R.string.tooldetail_typing_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    // Same for the Selection actions tool's own switch.
    toolEntry(
        ToolbarTool.SELECTION_ACTIONS,
        R.string.tooldetail_selection_actions_nav_title,
        R.string.tooldetail_selection_actions_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.SOUND_HAPTICS,
        R.string.tooldetail_keypress_nav_title,
        R.string.tooldetail_keypress_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.FANCY, R.string.tooldetail_fancy_style_title, R.string.tooldetail_fancy_style_subtitle),
    toolEntry(ToolbarTool.FANCY, R.string.tooldetail_fancy_keep_title, R.string.tooldetail_fancy_keep_subtitle),
    toolEntry(ToolbarTool.FANCY, R.string.tooldetail_fancy_auto_off_title, R.string.tooldetail_fancy_auto_off_subtitle),
    toolEntry(
        ToolbarTool.FANCY,
        R.string.tooldetail_fancy_language_nav_title,
        R.string.tooldetail_fancy_language_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.CUSTOM_LAYOUT, R.string.tooldetail_custom_layout_layout_title, R.string.tooldetail_custom_layout_layout_subtitle),
    toolEntry(
        ToolbarTool.CUSTOM_LAYOUT,
        R.string.tooldetail_custom_layout_keymaps_nav_title,
        R.string.tooldetail_custom_layout_keymaps_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.HANDWRITING, R.string.tooldetail_handwriting_stylus_title, R.string.tooldetail_handwriting_stylus_subtitle),
    toolEntry(
        ToolbarTool.HANDWRITING,
        R.string.tooldetail_handwriting_auto_space_title,
        R.string.tooldetail_handwriting_auto_space_subtitle,
    ),
    toolEntry(
        ToolbarTool.HANDWRITING,
        R.string.tooldetail_handwriting_zinnia_title,
        R.string.tooldetail_handwriting_zinnia_subtitle,
    ).takeIf { HandwritingModels.mlKitInBuild },
    toolEntry(ToolbarTool.HANDWRITING, R.string.tooldetail_handwriting_pause_title, R.string.tooldetail_handwriting_pause_subtitle),
    toolEntry(ToolbarTool.HANDWRITING, R.string.panel_layout_row_title, R.string.panel_layout_row_subtitle),
    toolEntry(
        ToolbarTool.HANDWRITING,
        R.string.tooldetail_handwriting_full_screen_title,
        R.string.tooldetail_handwriting_full_screen_subtitle,
    ),
    toolEntry(
        ToolbarTool.HANDWRITING,
        R.string.tooldetail_handwriting_languages_title,
        R.string.tooldetail_handwriting_languages_subtitle,
    ),
    toolEntry(
        ToolbarTool.THEMES,
        R.string.tooldetail_themes_nav_title,
        R.string.tooldetail_themes_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.THEMES,
        R.string.tooldetail_appearance_icons_nav_title,
        R.string.tooldetail_appearance_icons_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.THEMES,
        R.string.tooldetail_appearance_emoji_nav_title,
        R.string.tooldetail_appearance_emoji_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.THEMES,
        R.string.tooldetail_appearance_fonts_nav_title,
        R.string.tooldetail_appearance_fonts_nav_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(
        ToolbarTool.ONE_HANDED,
        R.string.tooldetail_layout_nav_title,
        R.string.tooldetail_layout_nav_one_handed_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    // Google, DeepL, LibreTranslate and the user's own server are rows of the provider list now,
    // so the words that used to find the server's address row find the list.
    toolEntry(
        ToolbarTool.TRANSLATE,
        R.string.providers_group_title,
        R.string.providers_add_subtitle,
        keywords = R.string.search_kw_translate_server,
    ),
    providerEntry(ProviderTool.TRANSLATE, ProviderTypes.DEEPL, R.string.tooldetail_deepl_write_title, R.string.tooldetail_deepl_write_subtitle),
)

/** Rows on the tool pages, from Translate through the AI tool. */
/** The gallery tool's page (#708); the browsing rows only where the build has the grid. */
private fun SearchStrings.galleryRows(): List<SettingsSearchEntry> {
    val grid = ChannelFeatures.GALLERY_BROWSER
    fun row(@StringRes title: Int, @StringRes subtitle: Int = 0, gridOnly: Boolean = false) =
        toolEntry(ToolbarTool.GALLERY, title, subtitle).takeIf { grid || !gridOnly }
    return listOfNotNull(
        row(R.string.gallery_access_title, R.string.gallery_access_group, gridOnly = true),
        row(R.string.gallery_tap_title, R.string.gallery_tap_subtitle, gridOnly = true),
        row(R.string.gallery_columns_title, R.string.gallery_columns_subtitle, gridOnly = true),
        row(R.string.gallery_camera_tile_title, R.string.gallery_camera_tile_subtitle, gridOnly = true),
        row(R.string.gallery_show_videos_title, R.string.gallery_show_videos_subtitle, gridOnly = true),
        row(R.string.gallery_scrubber_title, R.string.gallery_scrubber_subtitle, gridOnly = true),
        row(R.string.gallery_start_expanded_title, R.string.gallery_start_expanded_subtitle, gridOnly = true),
        row(R.string.gallery_remember_album_title, R.string.gallery_remember_album_subtitle, gridOnly = true),
        row(R.string.gallery_haptics_title, R.string.gallery_haptics_subtitle, gridOnly = true),
        row(R.string.gallery_folders_title, R.string.gallery_folders_subtitle, gridOnly = true),
        row(R.string.gallery_quality_title, R.string.gallery_quality_subtitle),
        row(R.string.gallery_max_selection_title, R.string.gallery_max_selection_subtitle),
        row(R.string.gallery_caption_title, R.string.gallery_caption_subtitle, gridOnly = true),
        row(R.string.gallery_fallback_title, R.string.gallery_fallback_subtitle),
        row(R.string.gallery_close_after_send_title, R.string.gallery_close_after_send_subtitle, gridOnly = true),
        row(R.string.gallery_save_edited_title, R.string.gallery_save_edited_subtitle, gridOnly = true),
        row(R.string.gallery_strip_location_title, R.string.gallery_strip_location_subtitle),
        row(R.string.gallery_strip_all_title, R.string.gallery_strip_all_subtitle),
        row(R.string.gallery_random_names_title, R.string.gallery_random_names_subtitle),
        row(R.string.gallery_history_title, R.string.gallery_history_subtitle, gridOnly = true),
        row(R.string.gallery_history_clear_title, R.string.gallery_history_clear_subtitle, gridOnly = true),
    )
}

private fun SearchStrings.toolPageRowsB(): List<SettingsSearchEntry> = listOfNotNull(
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_sticker_packs_title, R.string.tooldetail_sticker_packs_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_sticker_suggest_title, R.string.tooldetail_sticker_suggest_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_sticker_suggest_style_title),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_sticker_suggest_trigger_title),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_sticker_suggest_magnify_title, R.string.tooldetail_sticker_suggest_magnify_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_full_bleed_title, R.string.tooldetail_media_full_bleed_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_full_bleed_title, R.string.tooldetail_media_full_bleed_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_gif_per_row_title, R.string.tooldetail_media_gif_per_row_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_sticker_per_row_title, R.string.tooldetail_media_sticker_per_row_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_spacing_title, R.string.tooldetail_media_spacing_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_spacing_title, R.string.tooldetail_media_spacing_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.langemoji_panel_height_title, R.string.langemoji_panel_height_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.langemoji_panel_height_title, R.string.langemoji_panel_height_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_hide_header_title, R.string.tooldetail_media_hide_header_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_hide_header_title, R.string.tooldetail_media_hide_header_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_remember_search_title, R.string.tooldetail_media_remember_search_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_remember_search_title, R.string.tooldetail_media_remember_search_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_gif_recent_title, R.string.tooldetail_gif_recent_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_gif_favourites_offline_title, R.string.tooldetail_gif_favourites_offline_subtitle),
    // KLIPY, GIPHY, Commons, GifCities and the packs are rows of the provider list now.
    toolEntry(ToolbarTool.GIF, R.string.providers_group_title, R.string.providers_add_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.providers_group_title, R.string.providers_add_subtitle),
    // Each send mode is indexed under the one tool whose panel it governs; the
    // other page no longer draws it.
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_sticker_send_title, R.string.tooldetail_media_sticker_send_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_gif_send_title, R.string.tooldetail_media_gif_send_subtitle),
    toolEntry(ToolbarTool.GIF, R.string.tooldetail_media_limit_title, R.string.tooldetail_media_limit_subtitle),
    toolEntry(ToolbarTool.STICKER, R.string.tooldetail_media_limit_title, R.string.tooldetail_media_limit_subtitle),
    // SearXNG, Tavily and Brave are rows of the provider list now.
    // Image search's settings are on web search's page (#879).
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.providers_group_title, R.string.providers_add_subtitle),
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.tooldetail_search_safe_title, R.string.tooldetail_search_safe_subtitle),
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.tooldetail_search_count_title, R.string.tooldetail_search_count_subtitle),
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.tooldetail_search_answer_title, R.string.tooldetail_search_answer_subtitle),
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.tooldetail_search_open_browser_title, R.string.tooldetail_search_open_browser_subtitle),
    toolEntry(ToolbarTool.WEB_SEARCH, R.string.tooldetail_image_columns_title, R.string.tooldetail_image_columns_subtitle),
    // On Tavily's own screen, opened from its row on the list.
    providerEntry(ProviderTool.SEARCH, ProviderTypes.TAVILY, R.string.tooldetail_tavily_advanced_title, R.string.tooldetail_tavily_advanced_subtitle),
    providerEntry(ProviderTool.SEARCH, ProviderTypes.SEARXNG, R.string.tooldetail_searx_categories_title, R.string.tooldetail_searx_categories_subtitle),
    toolEntry(ToolbarTool.OCR, R.string.tooldetail_ocr_select_all_title, R.string.tooldetail_ocr_select_all_subtitle),
    toolEntry(ToolbarTool.OCR, R.string.tooldetail_ocr_engine_title),
    toolEntry(ToolbarTool.OCR, R.string.tooldetail_ocr_packs_header),
    // A choice only where ML Kit's reader can run; elsewhere zxing-cpp is all there is.
    toolEntry(ToolbarTool.QR_SCAN, R.string.tooldetail_qr_scan_zxing_title, R.string.tooldetail_qr_scan_zxing_subtitle)
        .takeIf { BarcodeEngine.mlKitUsable },
    toolEntry(ToolbarTool.QR_SCAN, R.string.tooldetail_qr_scan_auto_title, R.string.tooldetail_qr_scan_auto_subtitle),
    toolEntry(ToolbarTool.QR_SCAN, R.string.tooldetail_qr_scan_haptics_title, R.string.tooldetail_qr_scan_haptics_subtitle),
    toolEntry(ToolbarTool.QR_SCAN, R.string.tooldetail_qr_scan_preview_title, R.string.tooldetail_qr_scan_preview_subtitle),
    // A choice only where Google's scanner can run; elsewhere the built-in one is all there is.
    toolEntry(ToolbarTool.DOC_SCAN, R.string.tooldetail_doc_scan_builtin_title, R.string.tooldetail_doc_scan_builtin_subtitle)
        .takeIf { googleDocScanner },
    toolEntry(ToolbarTool.DOC_SCAN, R.string.tooldetail_doc_scan_gallery_title, R.string.tooldetail_doc_scan_gallery_subtitle),
    toolEntry(
        ToolbarTool.VOICE,
        R.string.tooldetail_voice_all_title,
        R.string.tooldetail_voice_all_subtitle,
        weight = EntryWeight.MIRROR,
    ),
    toolEntry(ToolbarTool.GRAMMAR, R.string.tooldetail_grammar_providers_group, R.string.providers_add_subtitle),
    toolEntry(ToolbarTool.GRAMMAR, R.string.tooldetail_grammar_dialect_title, R.string.tooldetail_grammar_dialect_subtitle),
    toolEntry(ToolbarTool.GRAMMAR, R.string.tooldetail_grammar_debounce_title, R.string.tooldetail_grammar_debounce_subtitle),
    toolEntry(ToolbarTool.GRAMMAR, R.string.tooldetail_grammar_system_title, R.string.tooldetail_grammar_system_subtitle),
    // The four issue-type folds. Each is indexed by its own master switch, so a
    // search for a category lands on its fold rather than on the tool page.
    toolEntry(
        ToolbarTool.GRAMMAR,
        R.string.tooldetail_grammar_correctness_title,
        R.string.tooldetail_grammar_category_search_subtitle,
    ),
    toolEntry(
        ToolbarTool.GRAMMAR,
        R.string.tooldetail_grammar_clarity_title,
        R.string.tooldetail_grammar_category_search_subtitle,
    ),
    toolEntry(
        ToolbarTool.GRAMMAR,
        R.string.tooldetail_grammar_engagement_title,
        R.string.tooldetail_grammar_category_search_subtitle,
    ),
    toolEntry(
        ToolbarTool.GRAMMAR,
        R.string.tooldetail_grammar_delivery_title,
        R.string.tooldetail_grammar_category_search_subtitle,
    ),
    toolEntry(
        ToolbarTool.GRAMMAR,
        R.string.tooldetail_grammar_no_suggestions_title,
        R.string.tooldetail_grammar_no_suggestions_subtitle,
    ),
    toolEntry(ToolbarTool.WIKIPEDIA, R.string.tooldetail_wiki_language_label, R.string.tooldetail_wiki_language_hint),
    toolEntry(ToolbarTool.WIKIPEDIA, R.string.tooldetail_wiki_markdown_title, R.string.tooldetail_wiki_markdown_subtitle),
    toolEntry(ToolbarTool.CALCULATOR, R.string.tooldetail_calc_degrees_title, R.string.tooldetail_calc_degrees_subtitle),
    toolEntry(ToolbarTool.CALCULATOR, R.string.tooldetail_calc_phone_layout_title, R.string.tooldetail_calc_phone_layout_subtitle),
    toolEntry(ToolbarTool.CALCULATOR, R.string.tooldetail_calc_precision_title, R.string.tooldetail_calc_precision_subtitle),
    toolEntry(
        ToolbarTool.UNIT_CONVERT,
        R.string.tooldetail_units_compound_title,
        R.string.tooldetail_units_compound_subtitle,
    ),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_auto_fetch_title, R.string.tooldetail_currency_auto_fetch_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_decimals_title, R.string.tooldetail_currency_decimals_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_label_title, R.string.tooldetail_currency_label_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_refresh_title, R.string.tooldetail_currency_refresh_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_currency_source_title, R.string.tooldetail_currency_source_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_enable_title, R.string.tooldetail_crypto_enable_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_decimals_title, R.string.tooldetail_crypto_decimals_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_refresh_title, R.string.tooldetail_crypto_refresh_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_source_title, R.string.tooldetail_crypto_source_subtitle),
    toolEntry(ToolbarTool.CURRENCY, R.string.tooldetail_crypto_coins_title),
    toolEntry(ToolbarTool.QR_GEN, R.string.tooldetail_qr_gen_size_title, R.string.tooldetail_qr_gen_size_subtitle),
    toolEntry(ToolbarTool.QR_GEN, R.string.tooldetail_qr_gen_send_title, R.string.tooldetail_qr_gen_send_subtitle),
    toolEntry(ToolbarTool.QR_GEN, R.string.tooldetail_qr_gen_gallery_title, R.string.tooldetail_qr_gen_gallery_subtitle),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_length_title),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_uppercase_title, R.string.tooldetail_password_uppercase_subtitle),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_digits_title, R.string.tooldetail_password_digits_subtitle),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_symbols_title),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_password_ambiguous_title, R.string.tooldetail_password_ambiguous_subtitle),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_passphrase_words_title),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_passphrase_separator_label, R.string.tooldetail_passphrase_separator_hint),
    toolEntry(
        ToolbarTool.PASSWORD_GEN,
        R.string.tooldetail_passphrase_capitalize_title,
        R.string.tooldetail_passphrase_capitalize_subtitle,
    ),
    toolEntry(ToolbarTool.PASSWORD_GEN, R.string.tooldetail_passphrase_digit_title, R.string.tooldetail_passphrase_digit_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_count_title, R.string.tooldetail_repeat_count_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_mode_title, R.string.tooldetail_repeat_mode_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_separator_title, R.string.tooldetail_repeat_separator_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_custom_label, R.string.tooldetail_repeat_custom_hint),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_numbered_title, R.string.tooldetail_repeat_numbered_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_alternate_title, R.string.tooldetail_repeat_alternate_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_max_chars_title, R.string.tooldetail_repeat_max_chars_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_chip_insert_title, R.string.tooldetail_repeat_chip_insert_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_chip_after_title, R.string.tooldetail_repeat_chip_after_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_recents_title, R.string.tooldetail_repeat_recents_subtitle),
    toolEntry(ToolbarTool.TEXT_REPEAT, R.string.tooldetail_repeat_recents_clear_title),
    toolEntry(ToolbarTool.MODES, R.string.tooldetail_modes_edit_title, R.string.tooldetail_modes_edit_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_seconds_label),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_words_label),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_punctuation_title, R.string.toolai_typing_punctuation_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_numbers_title, R.string.toolai_typing_numbers_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_glide_title, R.string.toolai_typing_glide_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_suggestions_title, R.string.toolai_typing_suggestions_subtitle),
    toolEntry(ToolbarTool.TYPING_TEST, R.string.toolai_typing_clear_records_title, R.string.toolai_typing_clear_records_subtitle),
    // The internet speed test.
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_duration_title, R.string.tooldetail_speed_duration_subtitle),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_connections_title, R.string.tooldetail_speed_connections_subtitle),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_upload_title, R.string.tooldetail_speed_upload_subtitle),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_unit_title),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_card_style_title),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_card_size_title),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_card_layout_title),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_send_mode_title, R.string.tooldetail_speed_send_mode_subtitle),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_save_gallery_title, R.string.tooldetail_speed_save_gallery_subtitle),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_show_ip_title, R.string.tooldetail_speed_show_ip_subtitle),
    toolEntry(ToolbarTool.SPEED_TEST, R.string.tooldetail_speed_servers_group),
    // The services, their keys, addresses and models are rows of the AI provider list now.
    toolEntry(
        ToolbarTool.AI,
        R.string.toolai_ai_providers_title,
        R.string.providers_add_subtitle,
        keywords = R.string.search_kw_ai_provider,
    ),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_in_use_title, R.string.toolai_ai_in_use_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_max_tokens_title, R.string.toolai_ai_max_tokens_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_translate_to_label, R.string.toolai_ai_translate_to_hint),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_show_thinking_title, R.string.toolai_ai_show_thinking_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_model_picker_title, R.string.toolai_ai_model_picker_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_actions_title, R.string.toolai_ai_actions_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_diff_title, R.string.toolai_ai_diff_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_diff_first_title, R.string.toolai_ai_diff_first_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_strip_suggest_title, R.string.toolai_strip_suggest_info),
    toolEntry(ToolbarTool.AI, R.string.toolai_strip_suggest_word_only_title, R.string.toolai_strip_suggest_word_only_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_strip_suggest_chars_title, R.string.toolai_strip_suggest_chars_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_tool_search_title, R.string.toolai_ai_tool_search_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_tool_fetch_title, R.string.toolai_ai_tool_fetch_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_builtin_title, R.string.toolai_ai_builtin_subtitle),
    // The keyboard's own tools (#698), on their own screen, one row each.
    // Named after the tool they let the AI use ("Translate", "Calculator"),
    // so they sit under that tool's own page.
    *builtInToolText.values.map { text ->
        entry(
            text.title,
            text.subtitle,
            R.string.toolai_ai_builtin_screen_title,
            AI_BUILT_IN_TOOLS_ROUTE,
            screenParent = R.string.home_tools_title,
            weight = EntryWeight.DETAIL,
        )
    }.toTypedArray(),
    entry(
        R.string.toolai_ai_builtin_calendar_mode_title,
        R.string.toolai_ai_builtin_calendar_mode_subtitle,
        R.string.toolai_ai_builtin_screen_title,
        AI_BUILT_IN_TOOLS_ROUTE,
        screenParent = R.string.home_tools_title,
    ),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_mcp_title, R.string.toolai_ai_mcp_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_tool_rounds_title, R.string.toolai_ai_tool_rounds_subtitle),
    // The MCP server list's own row. Indexed on that screen: the tool page
    // only draws the NavRow that opens it (above).
    entry(
        R.string.toolai_ai_mcp_import_title,
        R.string.toolai_ai_mcp_import_subtitle,
        R.string.toolai_ai_mcp_screen_title,
        "ai_mcp",
        screenParent = R.string.home_tools_title,
    ),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_chat_nav_title, R.string.toolai_ai_chat_nav_subtitle),
    toolEntry(ToolbarTool.AI, R.string.settings_assistant_title, R.string.settings_assistant_nav_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_chat_enter_sends_title, R.string.toolai_chat_enter_sends_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_chat_auto_rename_title, R.string.toolai_chat_auto_rename_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_chat_follow_ups_title, R.string.toolai_chat_follow_ups_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_research_depth_title, R.string.toolai_research_depth_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_research_review_plan_title, R.string.toolai_research_review_plan_subtitle),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_selection_menu_title, R.string.toolai_ai_selection_menu_subtitle),
    // The AI history screen's own heading. Indexed on that screen, not on the
    // tool page: the tool page only draws the NavRow that opens it (next).
    entry(
        R.string.toolai_ai_history_title,
        R.string.toolai_ai_history_subtitle,
        R.string.home_screen_ai_history_title,
        "ai_history",
        screenParent = R.string.home_tools_title,
        weight = EntryWeight.SECTION,
    ),
    toolEntry(ToolbarTool.AI, R.string.toolai_ai_history_nav_title, R.string.toolai_ai_history_nav_subtitle),
    // AI reply (#234). Its own tool, its own page: the provider rows stay above.
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_count_title),
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_length_title, R.string.toolai_reply_length_subtitle),
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_emoji_title, R.string.toolai_reply_emoji_subtitle),
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_persona_label, R.string.toolai_reply_persona_hint),
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_language_label, R.string.toolai_reply_language_hint),
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_context_title),
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_image_mode_title, R.string.toolai_reply_image_mode_subtitle),
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_tidy_title, R.string.toolai_reply_tidy_subtitle),
    toolEntry(ToolbarTool.AI_REPLY, R.string.toolai_reply_history_title, R.string.toolai_reply_history_subtitle),
    entry(
        R.string.toolai_ai_history_max_title,
        R.string.toolai_ai_history_max_subtitle,
        R.string.home_screen_ai_history_title,
        "ai_history",
        screenParent = R.string.home_tools_title,
    ),
    toolEntry(ToolbarTool.TRANSLATE, R.string.toolai_translate_into_title, R.string.toolai_translate_into_subtitle),
) + translateEngineRows()

/**
 * The translate page's engine row and model list. Only where the on-device
 * engine exists: on any other build the page does not draw them, and a result
 * that lands on a row that is not there flashes nothing.
 */
private fun SearchStrings.translateEngineRows(): List<SettingsSearchEntry> =
    if (!OfflineTranslation.available) {
        emptyList()
    } else {
        listOf(
            toolEntry(
                ToolbarTool.TRANSLATE,
                R.string.tooldetail_translate_engine_title,
                R.string.tooldetail_translate_engine_subtitle,
            ),
            toolEntry(
                ToolbarTool.TRANSLATE,
                R.string.tooldetail_translate_downloaded_first_title,
                R.string.tooldetail_translate_downloaded_first_subtitle,
            ),
            toolEntry(
                ToolbarTool.TRANSLATE,
                R.string.tooldetail_translate_only_downloaded_title,
                R.string.tooldetail_translate_only_downloaded_subtitle,
            ),
        ) + listOfNotNull(
            // Each engine's model list is drawn only where that engine runs.
            // On each engine's own screen, opened from its row on the list.
            providerEntry(ProviderTool.TRANSLATE, ProviderTypes.ML_KIT, R.string.tooldetail_translate_models_group)
                .takeIf { OnDeviceTranslator.offered },
            providerEntry(ProviderTool.TRANSLATE, ProviderTypes.BERGAMOT, R.string.tooldetail_translate_bergamot_group)
                .takeIf { BergamotTranslator.available },
            providerEntry(ProviderTool.TRANSLATE, ProviderTypes.NLLB, R.string.tooldetail_translate_nllb_group)
                .takeIf { NllbTranslator.available },
        )
    }

/**
 * Rows on Backup, on the sticker and plugin screens, and on Privacy, Rows &
 * bars, Keyboard modes, Accessibility, About and Tools.
 */
private fun SearchStrings.otherRows(): List<SettingsSearchEntry> {
    fun backup(@StringRes title: Int, @StringRes subtitle: Int) =
        entry(title, subtitle, R.string.home_backup_title, "backup", weight = EntryWeight.MIRROR)
    fun backupAuto(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.backup_auto_group_title, "backup/auto", screenParent = R.string.home_backup_title,
    )
    fun backupHub(@StringRes title: Int, @StringRes subtitle: Int) =
        entry(title, subtitle, R.string.home_backup_title, "backup")
    fun backupSync(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.backup_sync_title, "backup/sync", screenParent = R.string.home_backup_title,
    )
    fun backupContents(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.backup_files_contents_title, "backup/contents",
        screenParent = R.string.home_backup_title, weight = EntryWeight.MIRROR,
    )
    fun stickerPack(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.home_screen_sticker_packs_title, "sticker_packs",
        screenParent = toolTitle(ToolbarTool.STICKER),
        screenRoot = R.string.home_tools_title,
    )
    fun plugin(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.home_screen_plugins_title, "plugins",
        screenParent = R.string.home_tools_title,
    )
    fun privacy(@StringRes title: Int, @StringRes subtitle: Int, weight: EntryWeight = EntryWeight.NORMAL) =
        entry(title, subtitle, R.string.home_privacy_title, "privacy", weight = weight)
    fun automation(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.automation_actions_title, "automation",
        screenParent = R.string.home_privacy_title, weight = EntryWeight.DETAIL,
    )
    fun dataSaver(@StringRes title: Int, @StringRes subtitle: Int) =
        entry(title, subtitle, R.string.home_datasaver_title, "datasaver")
    fun notifications(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.home_notifications_title, "notifications",
        screenParent = R.string.home_advanced_title,
    )
    fun selectionMacro(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.selection_macros_title, "selection_macros",
        screenParent = R.string.home_advanced_title,
    )
    fun permission(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.privacy_permissions_title, "permissions", weight = EntryWeight.DETAIL,
        screenParent = R.string.home_privacy_title,
    )
    fun appLock(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.privacy_lock_title, "applock",
        screenParent = R.string.home_privacy_title,
    )
    // Drawn on one mode's editor, found on the mode list: search cannot say
    // which mode, and a link that does reaches the row by its screenPattern.
    // The path stays the one crumb, since the trail a result seeds ends on
    // the list it opens (see settingsCrumbSeed).
    fun mode(@StringRes title: Int, @StringRes subtitle: Int = 0, weight: EntryWeight = EntryWeight.NORMAL) = entry(
        title, subtitle, R.string.home_screen_mode_edit_title, "modes",
        weight = weight, screenPattern = MODE_EDIT_PATTERN,
    )
    fun access(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_accessibility_title, "accessibility")
    fun about(@StringRes title: Int, @StringRes subtitle: Int = 0) =
        entry(title, subtitle, R.string.home_about_title, "about")
    fun debug(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.home_screen_debug_log_title, "debug_log",
        screenParent = R.string.home_about_title,
    )
    return listOfNotNull(
        // Every switch that says what a backup holds. Each is named after the
        // feature it copies, never after the feature itself, which is why they
        // all weigh MIRROR: the search for "themes" wants the theme screen.
        // Where backups go: the Locations card on the main Backup screen. Each
        // kind of place is findable by name, and all of them land on the card.
        backupHub(R.string.backup_locations_title, R.string.backup_location_add_subtitle),
        backupHub(R.string.backup_auto_folder_title, R.string.backup_auto_dest_folder_desc),
        backupHub(R.string.backup_auto_dest_webdav, R.string.backup_auto_dest_webdav_desc),
        backupHub(R.string.backup_auto_drive_title, R.string.backup_auto_dest_drive_desc),
        backupHub(R.string.backup_auto_dest_s3, R.string.backup_auto_dest_s3_desc),
        backupHub(R.string.backup_auto_dest_ftp, R.string.backup_auto_dest_ftp_desc),
        backupHub(R.string.backup_auto_dest_dropbox, R.string.backup_auto_dest_dropbox_desc),
        backupHub(R.string.backup_auto_dest_onedrive, R.string.backup_auto_dest_onedrive_desc),
        backupHub(R.string.backup_auto_dest_box, R.string.backup_auto_dest_box_desc),
        backupHub(R.string.backup_auto_dest_pcloud, R.string.backup_auto_dest_pcloud_desc),
        backupHub(R.string.backup_auto_dest_synology, R.string.backup_auto_dest_synology_desc),
        backupHub(R.string.backup_auto_dest_qnap, R.string.backup_auto_dest_qnap_desc),
        backupHub(R.string.backup_auto_dest_nfs, R.string.backup_auto_dest_nfs_desc),
        backupHub(R.string.backup_auto_dest_mega, R.string.backup_auto_dest_mega_desc),
        backupHub(R.string.backup_auto_dest_icloud, R.string.backup_auto_dest_icloud_desc),
        backupHub(R.string.backup_auto_dest_proton, R.string.backup_auto_dest_proton_desc),
        backupHub(R.string.backup_files_export_title, R.string.backup_files_export_subtitle),
        backupHub(R.string.backup_files_import_title, R.string.backup_files_import_subtitle),
        backupHub(R.string.backup_foreign_import_title, R.string.backup_foreign_import_subtitle),
        backupAuto(R.string.backup_auto_enabled_title, R.string.backup_auto_enabled_subtitle),
        backupAuto(R.string.backup_auto_interval_title, R.string.backup_auto_enabled_subtitle),
        backupAuto(R.string.backup_auto_keep_title, R.string.backup_auto_keep_subtitle),
        backupAuto(R.string.backup_auto_staggered_title, R.string.backup_auto_staggered_subtitle),
        backupAuto(R.string.backup_frequent_enabled_title, R.string.backup_frequent_enabled_subtitle),
        backupAuto(R.string.backup_auto_encrypt_title, R.string.backup_auto_encrypt_subtitle),
        backupAuto(R.string.backup_auto_contents_title, R.string.backup_include_settings_subtitle),
        backupSync(R.string.backup_sync_enabled_title, R.string.backup_sync_enabled_subtitle),
        backupSync(R.string.backup_sync_mode_title, R.string.backup_sync_mode_soon_desc),
        backupSync(R.string.backup_sync_interval_title, R.string.backup_sync_mode_schedule_desc),
        backupSync(R.string.backup_sync_targets_title, R.string.backup_sync_targets_info),
        backupAuto(R.string.backup_auto_targets_title, R.string.backup_auto_targets_info),
        backupSync(R.string.backup_sync_secrets_title, R.string.backup_sync_secrets_subtitle),
        backupSync(R.string.backup_sync_contents_title, R.string.backup_sync_local_note),
        backupSync(R.string.backup_sync_keep_local_title, R.string.backup_sync_keep_local_info),
        backupSync(R.string.backup_sync_keep_toolbar_title, R.string.backup_sync_keep_toolbar_subtitle),
        backupSync(R.string.backup_sync_keep_layouts_title, R.string.backup_sync_keep_layouts_subtitle),
        backupContents(R.string.backup_section_settings_label, R.string.backup_include_settings_subtitle),
        backupContents(R.string.backup_include_secrets_title, R.string.backup_include_secrets_subtitle),
        backupContents(R.string.backup_section_themes_label, R.string.backup_include_themes_subtitle),
        backupContents(R.string.backup_section_dictionary_label, R.string.backup_include_dictionary_subtitle),
        backupContents(R.string.backup_section_clipboard_label, R.string.backup_include_clipboard_subtitle),
        backupContents(R.string.backup_section_snippets_label, R.string.backup_include_snippets_subtitle),
        backupContents(R.string.backup_section_stickers_label, R.string.backup_include_stickers_subtitle),
        backupContents(R.string.backup_section_icons_label, R.string.backup_include_icons_subtitle),
        backupContents(R.string.backup_section_wordlists_label, R.string.backup_include_wordlists_subtitle),
        backupContents(R.string.backup_section_addons_label, R.string.backup_include_addons_subtitle),
        backupContents(R.string.backup_section_emoji_label, R.string.backup_include_emoji_subtitle),
        stickerPack(R.string.import_sticker_pack_new_title, 0),
        stickerPack(R.string.import_sticker_pack_import_title, R.string.import_sticker_pack_import_subtitle),
        stickerPack(R.string.stickergen_title, R.string.stickergen_entry_subtitle),
        stickerPack(R.string.import_signal_row_title, R.string.import_signal_row_subtitle),
        stickerPack(R.string.import_whatsapp_row_title, R.string.import_whatsapp_row_subtitle),
        stickerPack(R.string.import_telegram_row_title, R.string.import_telegram_row_subtitle),
        // Lands on the pack list: the editor itself cannot open without an
        // image to edit, so there is nothing to deep-link to.
        stickerPack(R.string.import_sticker_editor_title, R.string.import_sticker_editor_subtitle),
        plugin(R.string.plugins_allow_title, R.string.plugins_allow_subtitle),
        plugin(R.string.tooldetail_plugins_manage_title, R.string.tooldetail_plugins_manage_subtitle),
        plugin(R.string.plugins_install_file_title, R.string.plugins_install_file_subtitle),
        plugin(R.string.plugin_ide_entry_title, R.string.plugin_ide_entry_subtitle),
        privacy(R.string.privacy_learn_typing_title, R.string.privacy_learn_typing_subtitle),
        privacy(R.string.privacy_system_dictionary_title, R.string.privacy_system_dictionary_subtitle),
        privacy(R.string.privacy_dict_shortcuts_title, R.string.privacy_dict_shortcuts_subtitle),
        privacy(R.string.privacy_incognito_title, R.string.privacy_incognito_subtitle, weight = EntryWeight.PRIMARY),
        privacy(R.string.privacy_auto_incognito_title, R.string.privacy_auto_incognito_subtitle),
        privacy(R.string.privacy_incognito_apps_title, R.string.privacy_incognito_apps_add),
        privacy(R.string.privacy_backup_title, R.string.privacy_backup_subtitle),
        privacy(R.string.privacy_verify_checksums_title, R.string.privacy_verify_checksums_subtitle),
        privacy(R.string.automation_enabled_title, R.string.automation_enabled_subtitle),
        // Every row on Allowed actions, under the Privacy screen it hangs off.
        automation(R.string.automation_layout_title, R.string.automation_layout_subtitle),
        automation(R.string.automation_mode_title, R.string.automation_mode_subtitle),
        automation(R.string.automation_theme_title, R.string.automation_theme_subtitle),
        automation(R.string.automation_show_pin_title, R.string.automation_show_pin_subtitle),
        automation(R.string.automation_feedback_title, R.string.automation_feedback_subtitle),
        automation(R.string.automation_savers_title, R.string.automation_savers_subtitle),
        automation(R.string.automation_position_title, R.string.automation_position_subtitle),
        automation(R.string.automation_open_tool_title, R.string.automation_open_tool_subtitle),
        automation(R.string.automation_incognito_on_title, R.string.automation_incognito_on_subtitle),
        automation(R.string.automation_incognito_off_title, R.string.automation_incognito_off_subtitle),
        automation(R.string.automation_words_title, R.string.automation_words_subtitle),
        automation(R.string.automation_backup_title, R.string.automation_backup_subtitle),
        automation(R.string.automation_layout_events_title, R.string.automation_layout_events_subtitle),
        // Not offered on Play, so not indexed there either.
        automation(R.string.automation_type_text_title, R.string.automation_type_text_subtitle)
            .takeIf { AutomationPermission.TYPE_TEXT.offered },
        // The fingerprint lock's own three settings. The per-target checkboxes
        // below them are deliberately absent: each is named after a screen or
        // a row that already has its own entry here, and a second result for
        // "Personal dictionary" that lands on a checkbox is worse than none.
        appLock(R.string.privacy_lock_enabled_title, R.string.privacy_lock_enabled_subtitle),
        appLock(R.string.privacy_lock_relock_title, R.string.privacy_lock_relock_info),
        appLock(R.string.privacy_lock_credential_title, R.string.privacy_lock_credential_subtitle),
        appLock(R.string.privacy_lock_method_title, R.string.privacy_lock_method_info),
        appLock(R.string.privacy_lock_shortcut_title, R.string.privacy_lock_shortcut_subtitle),
        // Data saver. Every row, because each one is a feature someone will
        // look for by name once it stops working on mobile data.
        dataSaver(R.string.datasaver_manual_title, R.string.datasaver_manual_subtitle),
        dataSaver(R.string.datasaver_trigger_title, R.string.datasaver_trigger_subtitle),
        dataSaver(R.string.datasaver_link_previews_title, R.string.datasaver_link_previews_subtitle),
        dataSaver(R.string.datasaver_dictionary_title, R.string.datasaver_dictionary_subtitle),
        dataSaver(R.string.datasaver_photos_title, R.string.datasaver_photos_subtitle),
        dataSaver(R.string.datasaver_weather_title, R.string.datasaver_weather_subtitle),
        dataSaver(R.string.datasaver_rates_title, R.string.datasaver_rates_subtitle),
        dataSaver(R.string.datasaver_addons_title, R.string.datasaver_addons_subtitle),
        dataSaver(R.string.datasaver_media_title, R.string.datasaver_media_subtitle),
        dataSaver(R.string.datasaver_search_title, R.string.datasaver_search_subtitle),
        dataSaver(
            R.string.datasaver_animated_emoji_title,
            R.string.datasaver_animated_emoji_subtitle,
        ),
        dataSaver(R.string.datasaver_downloads_title, R.string.datasaver_downloads_subtitle),
        dataSaver(R.string.datasaver_ai_title, R.string.datasaver_ai_subtitle),
        dataSaver(R.string.datasaver_voice_title, R.string.datasaver_voice_subtitle),
        dataSaver(R.string.datasaver_grammar_title, R.string.datasaver_grammar_subtitle),
        dataSaver(R.string.datasaver_offline_fallback_title, R.string.datasaver_offline_fallback_subtitle),
        // The four kinds of notification, each one a thing someone will come
        // looking for by name the first time it arrives or stops arriving.
        notifications(R.string.notify_downloads_title, R.string.notify_downloads_subtitle),
        notifications(R.string.notify_updates_title, R.string.notify_updates_subtitle),
        notifications(R.string.notify_backup_title, R.string.notify_backup_subtitle),
        notifications(R.string.notify_controls_title, R.string.notify_controls_subtitle),
        // Selection actions. The screen's own row is indexed as a section
        // above; these are the switches on it.
        selectionMacro(R.string.selection_macros_placement_title, R.string.selection_macros_placement_subtitle),
        selectionMacro(R.string.selection_macros_every_title, R.string.selection_macros_every_subtitle),
        selectionMacro(R.string.selection_macros_caret_title, R.string.selection_macros_caret_subtitle),
        selectionMacro(R.string.selection_macros_detect_title, R.string.selection_macros_detect_subtitle),
        selectionMacro(R.string.text_actions_settings_actions_title, R.string.text_actions_settings_actions_subtitle),
        selectionMacro(R.string.text_actions_settings_translate_title, R.string.text_actions_settings_translate_subtitle),
        // The Permissions screen's rows. The version-gated Storage row is left
        // out: on most devices a result would land on a screen without it.
        permission(R.string.privacy_permissions_mic_title, R.string.privacy_permissions_mic_subtitle),
        permission(R.string.privacy_permissions_camera_title, R.string.privacy_permissions_camera_subtitle),
        permission(R.string.privacy_permissions_contacts_title, R.string.privacy_permissions_contacts_subtitle),
        permission(R.string.privacy_permissions_calendar_title, R.string.privacy_permissions_calendar_subtitle),
        permission(R.string.privacy_permissions_calendar_write_title, R.string.privacy_permissions_calendar_write_subtitle),
        permission(R.string.privacy_permissions_images_title, R.string.privacy_permissions_images_subtitle)
            .takeIf { ChannelFeatures.SCREENSHOT_CLIPS },
        permission(
            R.string.privacy_permissions_notifications_title,
            R.string.privacy_permissions_notifications_subtitle,
        ),
        permission(R.string.privacy_permissions_usage_title, R.string.privacy_permissions_usage_subtitle),
        permission(R.string.privacy_permissions_overlay_title, R.string.privacy_permissions_overlay_subtitle),
        permission(
            R.string.privacy_permissions_accessibility_title,
            R.string.privacy_permissions_accessibility_subtitle,
        ).takeIf { ChannelFeatures.GESTURE_PASSTHROUGH },
        permission(
            R.string.privacy_permissions_screen_content_title,
            R.string.privacy_permissions_screen_content_subtitle,
        ).takeIf { ChannelFeatures.SCREEN_CONTEXT },
        permission(
            R.string.privacy_permissions_split_screen_title,
            R.string.privacy_permissions_split_screen_subtitle,
        ).takeIf { ChannelFeatures.SPLIT_SCREEN_HELPER && AppLaunchModes.splitHelperApplies },
        // Only the channel that installs its own updates asks for this one.
        if (UpdateChannel.GITHUB) {
            permission(
                R.string.privacy_permissions_install_updates_title,
                R.string.privacy_permissions_install_updates_subtitle,
            )
        } else {
            null
        },
        permission(R.string.privacy_permissions_internet_title, R.string.privacy_permissions_internet_subtitle),
        permission(R.string.privacy_permissions_vibrate_title, R.string.privacy_permissions_vibrate_subtitle),
        permission(R.string.privacy_permissions_biometric_title, R.string.privacy_permissions_biometric_subtitle),
        entry(R.string.rows_symbol_row_title, R.string.rows_symbol_row_subtitle, R.string.home_rows_title, "rows"),
        // The symbol row's own page (#136): the toggle stays on Rows & bars,
        // everything under it lives here.
        entry(
            R.string.rows_symbol_row_height_title, R.string.rows_symbol_row_height_subtitle,
            R.string.rows_symbol_row_title, "rows/symbol", screenParent = R.string.home_rows_title,
        ),
        entry(
            R.string.rows_symbol_row_lines_title, R.string.rows_symbol_row_lines_subtitle,
            R.string.rows_symbol_row_title, "rows/symbol", screenParent = R.string.home_rows_title,
        ),
        entry(
            R.string.rows_symbol_row_scroll_title, R.string.rows_symbol_row_scroll_subtitle,
            R.string.rows_symbol_row_title, "rows/symbol", screenParent = R.string.home_rows_title,
        ),
        entry(R.string.rows_fancy_title, R.string.rows_fancy_subtitle, R.string.home_rows_title, "rows"),
        entry(R.string.rows_dictionary_bar_title, R.string.rows_dictionary_bar_subtitle, R.string.home_rows_title, "rows"),
        entry(R.string.rows_recent_words_placement_title, R.string.rows_recent_words_placement_info, R.string.home_rows_title, "rows"),
        entry(R.string.rows_recent_words_steady_title, R.string.rows_recent_words_steady_subtitle, R.string.home_rows_title, "rows"),
        entry(R.string.modes_enabled_title, R.string.modes_enabled_subtitle, R.string.home_modes_title, "modes"),
        entry(R.string.modes_drag_edits_title, R.string.modes_drag_edits_subtitle, R.string.home_modes_title, "modes"),
        mode(R.string.modes_use_title, R.string.modes_use_subtitle),
        mode(R.string.modes_name_label, R.string.modes_name_hint),
        mode(R.string.modes_icon_title, R.string.modes_icon_subtitle, EntryWeight.DETAIL),
        mode(R.string.modes_emoji_row_title, R.string.modes_active_subtitle),
        mode(R.string.modes_symbol_row_title),
        // Named after the global feature each one only overrides, so DETAIL:
        // a search for 'autocorrect' means the Typing switch.
        mode(R.string.modes_autocorrect_title, R.string.modes_active_subtitle, EntryWeight.DETAIL),
        mode(R.string.modes_autocapitalize_title, weight = EntryWeight.DETAIL),
        mode(R.string.modes_suggestions_title, weight = EntryWeight.DETAIL),
        mode(R.string.modes_layout_title, R.string.modes_layout_subtitle, EntryWeight.DETAIL),
        mode(R.string.modes_theme_title, R.string.modes_theme_inherit_subtitle, EntryWeight.DETAIL),
        mode(R.string.modes_pinned_tools_title, R.string.modes_pinned_tools_subtitle),
        mode(R.string.modes_pinned_behaviour_title, R.string.modes_pinned_behaviour_append_subtitle),
        mode(R.string.modes_toolbox_order_title, R.string.modes_toolbox_order_subtitle),
        mode(R.string.modes_symbol_sets_title, R.string.modes_symbol_sets_subtitle),
        mode(R.string.modes_autospace_title, R.string.modes_autospace_subtitle),
        mode(R.string.modes_glide_title, weight = EntryWeight.DETAIL),
        mode(R.string.modes_two_thumb_title, weight = EntryWeight.DETAIL),
        mode(R.string.modes_add_app_title, R.string.modes_add_app_subtitle_any),
        access(R.string.accessibility_color_vision_title, R.string.accessibility_color_vision_subtitle),
        access(R.string.accessibility_high_contrast_title, R.string.accessibility_high_contrast_subtitle),
        access(R.string.accessibility_key_outlines_title, R.string.accessibility_key_outlines_subtitle),
        access(R.string.accessibility_bold_labels_title, R.string.accessibility_bold_labels_subtitle),
        access(R.string.accessibility_readable_font_title),
        access(R.string.accessibility_text_size_title, R.string.accessibility_text_size_subtitle),
        access(R.string.accessibility_keyboard_font_title, R.string.accessibility_keyboard_font_subtitle),
        access(R.string.accessibility_reduce_motion_title, R.string.accessibility_reduce_motion_subtitle),
        access(R.string.accessibility_classic_popup_title, R.string.accessibility_classic_popup_subtitle),
        access(R.string.accessibility_panel_motion_title, R.string.accessibility_panel_motion_subtitle),
        access(R.string.accessibility_selection_motion_title, R.string.accessibility_selection_motion_subtitle),
        access(R.string.accessibility_language_switch_motion_title, R.string.accessibility_language_switch_motion_subtitle),
        access(R.string.accessibility_tool_result_motion_title, R.string.accessibility_tool_result_motion_subtitle),
        access(R.string.accessibility_row_icons_title, R.string.accessibility_row_icons_subtitle),
        access(R.string.accessibility_screen_transitions_title, R.string.accessibility_screen_transitions_subtitle),
        access(R.string.accessibility_keyboard_preview_title, R.string.accessibility_keyboard_preview_subtitle),
        access(R.string.favourites_button_title, R.string.favourites_button_subtitle),
        entry(
            R.string.toolai_hub_chat_button_everywhere_title,
            R.string.toolai_hub_chat_button_everywhere_subtitle,
            R.string.home_ai_title,
            "ai",
        ),
        entry(
            R.string.toolai_hub_settings_assistant_title,
            R.string.toolai_hub_settings_assistant_subtitle,
            R.string.home_ai_title,
            "ai",
        ),
        entry(R.string.toolai_memory_screen_title, R.string.toolai_memory_nav_subtitle, R.string.home_ai_title, "ai"),
        // The memory screen's own rows (Settings › AI › Memory).
        entry(
            R.string.toolai_memory_enabled_title,
            R.string.toolai_memory_enabled_subtitle,
            R.string.toolai_memory_screen_title,
            "ai_memory",
            screenParent = R.string.home_ai_title,
            weight = EntryWeight.SECTION,
        ),
        entry(
            R.string.toolai_memory_cloud_title,
            R.string.toolai_memory_cloud_subtitle,
            R.string.toolai_memory_screen_title,
            "ai_memory",
            screenParent = R.string.home_ai_title,
        ),
        entry(
            R.string.toolai_memory_delete_all_title,
            R.string.toolai_memory_delete_all_subtitle,
            R.string.toolai_memory_screen_title,
            "ai_memory",
            screenParent = R.string.home_ai_title,
        ),
        entry(R.string.semantic_mode_title, R.string.semantic_mode_subtitle, R.string.home_ai_title, "ai")
            .takeIf { com.wasimaster.wmkeyboard.core.semantic.SemanticModels.SUPPORTED },
        entry(R.string.semantic_tag_stickers_title, R.string.semantic_tag_stickers_subtitle, R.string.home_ai_title, "ai")
            .takeIf { com.wasimaster.wmkeyboard.core.semantic.SemanticModels.SUPPORTED },
        entry(R.string.semantic_delete_title, R.string.semantic_group_title, R.string.home_ai_title, "ai")
            .takeIf { com.wasimaster.wmkeyboard.core.semantic.SemanticModels.SUPPORTED },
        access(R.string.accessibility_talkback_title, R.string.accessibility_talkback_subtitle),
        access(R.string.accessibility_passthrough_service_title).takeIf { ChannelFeatures.GESTURE_PASSTHROUGH },
        access(R.string.accessibility_debounce_title, R.string.accessibility_debounce_subtitle_off),
        access(R.string.accessibility_long_press_title, R.string.accessibility_long_press_subtitle),
        access(R.string.accessibility_key_size_title, R.string.accessibility_key_size_subtitle),
        access(R.string.accessibility_haptics_title, R.string.accessibility_haptics_subtitle),
        about(R.string.about_version_title),
        about(R.string.about_licence_title),
        about(R.string.about_source_title),
        about(R.string.about_launcher_name_title, R.string.about_launcher_name_subtitle),
        about(R.string.about_launcher_shown_title, R.string.about_launcher_shown_subtitle),
        about(R.string.about_app_language_title, R.string.about_app_language_subtitle),
        // Open-source licences has its own screen, indexed once in sectionRows.
        about(R.string.about_user_guide_title),
        about(R.string.about_privacy_policy_title, R.string.about_privacy_policy_subtitle),
        about(R.string.about_setting_links_title, R.string.about_setting_links_subtitle),
        about(R.string.about_report_bug_title, R.string.about_report_bug_subtitle),
        about(R.string.about_email_developer_title),
        about(R.string.about_telegram_title, R.string.about_telegram_subtitle),
        about(R.string.about_follow_title, R.string.about_follow_subtitle),
        about(R.string.settings_depth_title, R.string.settings_depth_subtitle),
        about(R.string.settings_hidden_rows_title, R.string.settings_hidden_rows_subtitle),
        entry(
            R.string.about_diagnostics_title, R.string.about_diagnostics_subtitle,
            R.string.home_about_title, "debug_log", keywords = R.string.search_kw_diagnostics,
        ),
        entry(
            R.string.about_replay_onboarding_title, R.string.about_replay_onboarding_subtitle,
            R.string.home_about_title, "about", keywords = R.string.search_kw_replay_onboarding,
        ),
        debug(R.string.shell_debug_log_share_title, R.string.shell_debug_log_share_subtitle),
        debug(R.string.shell_debug_log_copy_title, R.string.shell_debug_log_copy_subtitle),
        debug(R.string.shell_debug_log_system_title, R.string.shell_debug_log_system_subtitle),
        debug(R.string.shell_debug_log_crash_test_title, R.string.shell_debug_log_crash_test_subtitle),
        about(R.string.about_dictionaries_title, R.string.about_dictionaries_subtitle),
        // Only a build with an update source behind it has an Updates group to
        // land on. The check row is indexed under its resting name — the
        // resource is what a search result matches on, and the row wears four
        // other names while a download is running.
        if (UpdateChannel.ANY) {
            about(R.string.update_row_check_title, R.string.update_row_check_subtitle)
        } else {
            null
        },
        if (UpdateChannel.ANY) {
            about(R.string.update_row_auto_check_title, R.string.update_row_auto_check_subtitle)
        } else {
            null
        },
        if (UpdateChannel.ANY) {
            about(R.string.update_row_prompts_title, R.string.update_row_prompts_subtitle)
        } else {
            null
        },
        // The pre-release switch exists only where there are pre-releases to
        // offer, which is the channel that reads GitHub's own release list.
        if (UpdateChannel.GITHUB) {
            about(R.string.update_row_prereleases_title, R.string.update_row_prereleases_subtitle)
        } else {
            null
        },
        entry(R.string.tools_colored_icons_title, R.string.tools_colored_icons_subtitle, R.string.home_tools_title, "tools"),
        entry(R.string.tools_gradient_icons_title, R.string.tools_gradient_icons_subtitle, R.string.home_tools_title, "tools"),
        entry(R.string.tools_split_panels_title, R.string.tools_split_panels_subtitle, R.string.home_tools_title, "tools"),
        if (ChannelFeatures.FILE_MANAGER) {
            entry(R.string.tools_file_manager_title, R.string.tools_file_manager_subtitle, R.string.home_tools_title, "tools")
        } else {
            null
        },
    )
}

/** The destinations: the settings home's own list, plus the screens that hang off it. */
private fun SearchStrings.sectionRows(): List<SettingsSearchEntry> {
    fun home(
        @StringRes title: Int,
        @StringRes subtitle: Int = 0,
        route: String,
        @StringRes keywords: Int = 0,
    ) = entry(
        title, subtitle, CommonR.string.common_settings, route,
        weight = EntryWeight.SECTION, keywords = keywords,
    )
    // [parent] is the screen above [screen], for the destinations that hang off
    // a sub-page rather than off a home row. The whole path matters: it is what
    // the result draws under its title and what the path strip is seeded with
    // when the result is opened (#111).
    fun under(
        @StringRes title: Int,
        @StringRes subtitle: Int,
        @StringRes screen: Int,
        route: String,
        @StringRes keywords: Int = 0,
        @StringRes parent: Int = 0,
    ) = entry(
        title, subtitle, screen, route,
        screenParent = parent, weight = EntryWeight.SECTION, keywords = keywords,
    )
    return RootEntries.map { home(it.title, it.subtitle, it.route, it.keywords) } + listOf(
        // Key layouts is reached from Layout & size, its one door.
        under(
            R.string.langemoji_lang_keymaps_title, R.string.langemoji_lang_keymaps_subtitle,
            R.string.home_layout_title, "keymaps", R.string.search_kw_keymaps,
        ),
        // The pages a hub screen is split into. Each is the row on the hub that
        // opens it, so `corrections` lands on the Corrections page and not on
        // the twenty rows that happen to sit there.
        under(
            R.string.typing_group_corrections_title, R.string.typing_group_corrections_subtitle,
            R.string.home_typing_title, "typing/corrections",
        ),
        under(
            R.string.typing_group_writing_title, R.string.typing_group_writing_subtitle,
            R.string.home_typing_title, "typing/writing",
        ),
        under(
            R.string.typing_group_suggestions_title, R.string.typing_group_suggestions_subtitle,
            R.string.home_typing_title, "typing/suggestions",
        ),
        under(
            R.string.typing_group_autopilot_title, R.string.typing_group_autopilot_subtitle,
            R.string.home_typing_title, "typing/autopilot",
        ),
        under(
            R.string.typing_group_octopus_title, R.string.typing_group_octopus_subtitle,
            R.string.home_typing_title, "typing/octopus",
        ),
        under(
            R.string.typing_group_smart_chips_title, R.string.typing_group_smart_chips_subtitle,
            R.string.home_typing_title, "typing/chips",
        ),
        under(
            R.string.typing_group_otp_title, R.string.typing_group_otp_subtitle,
            R.string.home_typing_title, "typing/codes", R.string.search_kw_codes,
        ),
        under(
            R.string.typing_group_gestures_title, R.string.typing_group_gestures_subtitle,
            R.string.home_typing_title, "typing/gestures",
        ),
        // The three pages under Gestures (SETTINGS_REDESIGN.md §4).
        under(
            R.string.typing_group_glide_title, R.string.typing_page_glide_subtitle,
            R.string.typing_group_gestures_title, "typing/gestures/glide",
            parent = R.string.home_typing_title,
        ),
        under(
            R.string.typing_glide_checkup_title, R.string.typing_glide_checkup_subtitle,
            R.string.typing_group_glide_title, "typing/gestures/glide/checkup",
            parent = R.string.typing_group_gestures_title,
        ),
        under(
            R.string.typing_page_spacebar_title, R.string.typing_page_spacebar_subtitle,
            R.string.typing_group_gestures_title, "typing/gestures/spacebar",
            parent = R.string.home_typing_title,
        ),
        under(
            R.string.typing_page_flicks_title, R.string.typing_page_flicks_subtitle,
            R.string.typing_group_gestures_title, "typing/gestures/flicks",
            parent = R.string.home_typing_title,
        ),
        // The three pages under Clipboard. The history page is named by the
        // switch on the hub that is also its door.
        under(
            R.string.clipboard_history_title, R.string.clipboard_page_history_subtitle,
            R.string.home_clipboard_title, "clipboard/history",
        ),
        under(
            R.string.clipboard_page_chips_title, R.string.clipboard_page_chips_subtitle,
            R.string.home_clipboard_title, "clipboard/chips",
        ),
        under(
            R.string.clipboard_page_rules_title, R.string.clipboard_page_rules_subtitle,
            R.string.home_clipboard_title, "clipboard/rules",
        ),
        under(
            R.string.typing_group_hardware_title, R.string.typing_group_hardware_subtitle,
            R.string.home_typing_title, "typing/hardware",
        ),
        under(
            R.string.keypress_haptics_page_title, R.string.keypress_haptics_group_subtitle,
            R.string.home_keypress_title, "keypress/haptics",
        ),
        under(
            R.string.rows_symbol_row_title, R.string.rows_symbol_row_subtitle,
            R.string.home_rows_title, "rows/symbol",
        ),
        under(
            R.string.keypress_popup_group_title, R.string.keypress_popup_group_subtitle,
            R.string.home_keypress_title, "keypress/popup",
        ),
        under(
            R.string.keypress_shortcuts_group_title, R.string.keypress_shortcuts_group_subtitle,
            R.string.home_keypress_title, "keypress/shortcuts",
        ),
        under(
            R.string.appearance_toolbar_section_title, R.string.appearance_toolbar_section_subtitle,
            R.string.home_appearance_title, "appearance/toolbar",
        ),
        under(
            R.string.appearance_toolbox_section_title, R.string.appearance_toolbox_section_subtitle,
            R.string.home_appearance_title, "appearance/toolbox",
        ),
        under(R.string.layout_size_position_title, R.string.layout_size_subtitle, R.string.home_layout_title, "layout/size"),
        under(
            R.string.layout_one_handed_group_title, R.string.layout_one_handed_page_subtitle,
            R.string.home_layout_title, "layout/onehanded",
        ),
        under(
            R.string.langemoji_emoji_panel_title, R.string.langemoji_emoji_panel_subtitle,
            R.string.home_emoji_title, "emoji/panel",
        ),
        under(R.string.emojimix_row_title, R.string.emojimix_row_subtitle, R.string.home_emoji_title, "emoji/mixes"),
        // The kaomoji library hangs off the emoji panel's page, beside the
        // switch that shows its tab.
        under(
            R.string.langemoji_kaomoji_title, R.string.langemoji_kaomoji_subtitle,
            R.string.langemoji_emoji_panel_title, "kaomoji", R.string.search_kw_kaomoji,
            parent = R.string.home_emoji_title,
        ),
        under(
            R.string.langemoji_kaomoji_faces_title, R.string.langemoji_kaomoji_faces_subtitle,
            R.string.langemoji_kaomoji_title, "kaomoji/faces", R.string.search_kw_kaomoji_faces,
            parent = R.string.langemoji_emoji_panel_title,
        ),
        under(
            R.string.langemoji_kaomoji_tags_title, R.string.langemoji_kaomoji_tags_subtitle,
            R.string.langemoji_kaomoji_title, "kaomoji/tags",
            parent = R.string.langemoji_emoji_panel_title,
        ),
        // The Extensions screen is a root row; each bundle's page hangs off it.
        *Extension.entries.map { extension ->
            under(extensionTitle(extension), extensionDescription(extension), R.string.extensions_title, extensionRoute(extension))
        }.toTypedArray(),
        // What sits behind Advanced.
        under(R.string.home_modes_title, R.string.home_modes_subtitle, R.string.home_advanced_title, "modes", R.string.search_kw_modes),
        under(R.string.home_addons_title, R.string.home_addons_subtitle, R.string.home_advanced_title, "addons", R.string.search_kw_addons),
        under(
            R.string.home_datasaver_title, R.string.home_datasaver_subtitle,
            R.string.home_advanced_title, "datasaver", R.string.search_kw_datasaver,
        ),
        // Only the F-Droid build reads the server settings, so only it indexes the screen.
        *if (BuildConfig.ENABLE_FDROID) {
            arrayOf(under(R.string.servers_title, R.string.servers_subtitle, R.string.home_advanced_title, "servers", R.string.search_kw_servers))
        } else {
            emptyArray()
        },
        under(
            R.string.home_notifications_title, R.string.home_notifications_subtitle,
            R.string.home_advanced_title, "notifications", R.string.search_kw_notifications,
        ),
        under(
            R.string.selection_macros_title, R.string.selection_macros_subtitle,
            R.string.home_advanced_title, "selection_macros", R.string.search_kw_selection_macros,
        ),
        under(
            R.string.floating_button_title, R.string.floating_button_subtitle,
            R.string.home_advanced_title, FloatingButtonRoute, R.string.search_kw_floating_button,
        ),
        under(
            R.string.share_sheet_title, R.string.share_sheet_subtitle,
            R.string.home_advanced_title, ShareSheetRoute, R.string.search_kw_share_sheet,
        ),
        // The actions list is indexed as a screen and never row by row: a row
        // called "Delete" or "Translate" would outrank the tools of those
        // names, which the ranking test pins.
        under(
            R.string.selection_macros_actions_title, R.string.selection_macros_actions_subtitle,
            R.string.selection_macros_title, "selection_macros/actions", R.string.search_kw_selection_macros_actions,
            parent = R.string.home_advanced_title,
        ),
        under(
            R.string.selection_macros_ai_title, R.string.selection_macros_ai_subtitle,
            R.string.selection_macros_title, "selection_macros/ai",
            parent = R.string.home_advanced_title,
        ),
        under(
            R.string.selection_macros_zones_title, R.string.selection_macros_zones_subtitle,
            R.string.selection_macros_title, "selection_macros/zones",
            parent = R.string.home_advanced_title,
        ),
        under(
            R.string.photo_rotation_title, R.string.photo_rotation_subtitle,
            R.string.home_screen_theme_edit_title, "photo_rotation",
        ),
        under(
            R.string.photo_find_title, R.string.photo_find_subtitle,
            R.string.home_screen_theme_edit_title, "photo_browse",
        ),
        under(
            R.string.photo_library_title, R.string.photo_library_subtitle,
            R.string.photo_rotation_title, "photo_library",
        ),
        under(
            R.string.photo_services_title, R.string.photo_services_subtitle,
            R.string.home_screen_theme_edit_title, "photos",
        ),
        under(
            R.string.appearance_themes_title, R.string.appearance_themes_subtitle,
            R.string.home_appearance_title, "themes", R.string.search_kw_themes,
        ),
        under(
            R.string.appearance_font_title, R.string.appearance_font_subtitle,
            R.string.home_appearance_title, "fonts", R.string.search_kw_fonts,
        ),
        under(
            R.string.appearance_icons_title, R.string.appearance_icons_subtitle,
            R.string.home_appearance_title, "icons", R.string.search_kw_icons,
        ),
        under(
            R.string.typing_personal_dictionary_title,
            R.string.typing_personal_dictionary_subtitle,
            R.string.typing_group_suggestions_title,
            "dictionary",
            R.string.search_kw_dictionary,
            parent = R.string.home_typing_title,
        ),
        under(
            R.string.typing_custom_dictionaries_title,
            R.string.typing_custom_dictionaries_subtitle,
            R.string.typing_group_suggestions_title,
            "customdictionaries",
            R.string.search_kw_customdictionaries,
            parent = R.string.home_typing_title,
        ),
        under(
            R.string.langemoji_emoji_keywords_title,
            R.string.langemoji_emoji_keywords_subtitle,
            R.string.home_emoji_title,
            "emojikeywords",
            R.string.search_kw_emojikeywords,
        ),
        under(
            R.string.langemoji_emoji_categories_title,
            R.string.langemoji_emoji_categories_subtitle,
            R.string.home_emoji_title,
            "emojicategories",
            R.string.search_kw_emojicategories,
        ),
        entry(
            R.string.langemoji_emoji_order_preset_title,
            R.string.langemoji_emoji_order_preset_subtitle,
            R.string.langemoji_emoji_categories_title,
            "emojicategories",
            screenParent = R.string.home_emoji_title,
        ),
        under(
            R.string.langemoji_emoji_added_title,
            R.string.langemoji_emoji_added_subtitle,
            R.string.home_emoji_title,
            "emojiadded",
            R.string.search_kw_emojiadded,
        ),
        under(
            R.string.typing_blacklist_title, R.string.typing_blacklist_subtitle,
            R.string.typing_group_suggestions_title, "blacklist", R.string.search_kw_blacklist,
            parent = R.string.home_typing_title,
        ),
        under(
            R.string.typing_learned_corrections_title, R.string.typing_learned_corrections_subtitle,
            R.string.typing_group_corrections_title, "learnedcorrections", R.string.search_kw_learnedcorrections,
            parent = R.string.home_typing_title,
        ),
        // A child of the media control tool's page rather than of a home
        // screen, so its breadcrumb is built by hand off the tool's own name.
        entry(
            title = R.string.musicapps_title,
            subtitle = R.string.musicapps_caption,
            screen = toolTitle(ToolbarTool.MEDIA_CONTROL),
            route = "musicapps",
            screenParent = R.string.home_tools_title,
            keywords = R.string.search_kw_musicapps,
        ),
        // The same shape for the app launcher's split-screen pairs.
        entry(
            title = R.string.launchercombos_title,
            subtitle = R.string.launchercombos_caption,
            screen = toolTitle(ToolbarTool.APP_LAUNCHER),
            route = "launchercombos",
            screenParent = R.string.home_tools_title,
            keywords = R.string.search_kw_launchercombos,
        ),
        // The same shape for KDE Connect's devices screen (#285).
        entry(
            title = R.string.kdeconnect_devices_title,
            subtitle = R.string.kdeconnect_devices_screen_subtitle,
            screen = toolTitle(ToolbarTool.KDE_CONNECT),
            route = "kdeconnect/devices",
            screenParent = R.string.home_tools_title,
            keywords = R.string.search_kw_kdeconnect_devices,
        ),
        // The Send files tool's two screens (#729), the same shape again.
        entry(
            title = R.string.send_instances_screen_title,
            subtitle = R.string.send_instances_screen_subtitle,
            screen = toolTitle(ToolbarTool.SEND_FILES),
            route = "send/instances",
            screenParent = R.string.home_tools_title,
            keywords = R.string.search_kw_send_instances,
        ),
        entry(
            title = R.string.send_history_screen_title,
            subtitle = R.string.send_history_screen_subtitle,
            screen = toolTitle(ToolbarTool.SEND_FILES),
            route = "send/history",
            screenParent = R.string.home_tools_title,
            keywords = R.string.search_kw_send_history,
        ),
        // The Clipboard screen has a row for this, but the row only opens this
        // screen, so it is indexed once and points straight at it.
        under(
            R.string.home_screen_phoneformats_title,
            R.string.clipboard_phone_formats_subtitle,
            R.string.clipboard_page_rules_title,
            "phoneformats",
            R.string.search_kw_phoneformats,
            parent = R.string.home_clipboard_title,
        ),
        // The same: one row on the Rules and privacy page that only opens this (#612).
        under(
            R.string.clipboard_copy_rules_title,
            R.string.clipboard_copy_rules_subtitle,
            R.string.clipboard_page_rules_title,
            "cliprules",
            R.string.search_kw_cliprules,
            parent = R.string.home_clipboard_title,
        ),
        under(
            R.string.typing_hw_shortcuts_list_title,
            R.string.typing_hw_shortcuts_list_subtitle,
            R.string.typing_group_hardware_title,
            "hwshortcuts",
            R.string.search_kw_hwshortcuts,
            parent = R.string.home_typing_title,
        ),
        under(
            R.string.about_licences_title, R.string.about_licences_subtitle,
            R.string.home_about_title, "licenses", R.string.search_kw_licenses,
        ),
        under(
            R.string.about_supporters_title, R.string.about_supporters_subtitle,
            R.string.home_about_title, "supporters", R.string.search_kw_supporters,
        ),
        under(
            R.string.about_storage_title, R.string.about_storage_subtitle,
            R.string.home_about_title, "storage", R.string.search_kw_storage,
        ),
        under(
            R.string.import_hub_title, R.string.import_hub_subtitle,
            R.string.home_about_title, "import_hub", R.string.search_kw_import_hub,
        ),
        under(
            R.string.statistics_title, R.string.statistics_subtitle,
            R.string.home_about_title, "statistics", R.string.search_kw_statistics,
        ),
        under(
            R.string.privacy_permissions_title, R.string.privacy_permissions_subtitle,
            R.string.home_privacy_title, "permissions", R.string.search_kw_permissions,
        ),
        under(
            R.string.netlog_title, R.string.netlog_subtitle,
            R.string.home_privacy_title, "network_activity", R.string.search_kw_netlog,
        ),
        under(
            R.string.privacy_lock_title, R.string.privacy_lock_subtitle,
            R.string.home_privacy_title, "applock", R.string.search_kw_applock,
        ),
        under(
            R.string.automation_actions_title, R.string.automation_actions_subtitle,
            R.string.home_privacy_title, "automation", R.string.search_kw_automation,
        ),
    )
}

/**
 * The Storage screen's categories.
 *
 * All of them point at the screen itself rather than at each category's own
 * page: someone searching for stickers wants the sticker packs, and a second
 * result that lands on a size readout for them would only be in the way. What
 * this buys is the other direction — a search for cached files, or for models,
 * finds the one screen that can delete them.
 */
/**
 * Rows on the vocabulary screens under the Vocabulary tool's page: packs,
 * lists, review and browse. Each names its own screen, so the crumb reads
 * Tools › Vocabulary › Vocabulary packs.
 */
/** The Send files tool's page (#729). */
private fun SearchStrings.sendRows(): List<SettingsSearchEntry> = listOf(
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_instance_title, R.string.send_instance_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_expire_title, R.string.send_expire_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_downloads_title, R.string.send_downloads_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_password_title, R.string.send_password_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_after_title, R.string.send_after_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_detect_title, R.string.send_detect_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_open_after_title, R.string.send_open_after_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_nearby_title, R.string.send_nearby_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_keep_direct_title, R.string.send_keep_direct_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_nearby_name_title, R.string.send_nearby_name_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_share_sheet_title, R.string.send_share_sheet_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_history_title, R.string.send_history_subtitle),
    toolEntry(ToolbarTool.SEND_FILES, R.string.send_history_open_title, R.string.send_history_open_subtitle),
)

private fun SearchStrings.vocabRows(): List<SettingsSearchEntry> {
    fun vocab(@StringRes title: Int, @StringRes subtitle: Int, @StringRes screen: Int, route: String) = entry(
        title, subtitle, screen, route,
        screenParent = toolTitle(ToolbarTool.VOCABULARY), screenRoot = R.string.home_tools_title,
    )
    return listOf(
        vocab(R.string.vocab_packs_import_title, R.string.vocab_packs_import_subtitle, R.string.vocab_packs_title, "vocab/packs"),
        vocab(R.string.vocab_packs_available_title, 0, R.string.vocab_packs_title, "vocab/packs"),
        vocab(R.string.vocab_packs_installed_title, 0, R.string.vocab_packs_title, "vocab/packs"),
        vocab(R.string.vocab_lists_add_action, R.string.vocab_lists_info, R.string.vocab_lists_title, "vocab/lists"),
        vocab(R.string.vocab_review_ahead_action, R.string.vocab_review_ahead_subtitle, R.string.vocab_review_title, "vocab/review"),
        vocab(R.string.vocab_browse_search_hint, 0, R.string.vocab_browse_title, "vocab/browse"),
    )
}

private fun SearchStrings.notesRows(): List<SettingsSearchEntry> {
    fun notes(@StringRes title: Int, @StringRes subtitle: Int, @StringRes screen: Int, route: String) = entry(
        title, subtitle, screen, route,
        screenParent = toolTitle(ToolbarTool.NOTES), screenRoot = R.string.home_tools_title,
    )
    return listOf(
        notes(R.string.notes_search_hint, 0, R.string.notes_title, "notes"),
        notes(R.string.notes_export_all_action, R.string.notes_export_all_subtitle, R.string.notes_title, "notes"),
        notes(R.string.notes_import_action, R.string.notes_import_subtitle, R.string.notes_title, "notes"),
        notes(R.string.notes_tag_add_action, 0, R.string.notes_tags_title, "notes/tags"),
        notes(R.string.notes_empty_trash_action, 0, R.string.notes_trash_title, "notes/trash"),
    )
}

private fun SearchStrings.todoRows(): List<SettingsSearchEntry> {
    fun todo(@StringRes title: Int, @StringRes subtitle: Int) = entry(
        title, subtitle, R.string.todo_title, TODO_ROUTE,
        screenParent = toolTitle(ToolbarTool.TODO), screenRoot = R.string.home_tools_title,
    )
    return listOf(
        todo(R.string.todo_add_hint, 0),
        todo(R.string.todo_clear_done_title, R.string.todo_clear_done_subtitle),
    )
}

private fun SearchStrings.storageRows(): List<SettingsSearchEntry> {
    fun row(@StringRes title: Int, @StringRes subtitle: Int) =
        entry(title, subtitle, R.string.about_storage_title, "storage", screenParent = R.string.home_about_title)
    return listOf(
        row(R.string.storage_wordlists_title, R.string.storage_wordlists_subtitle),
        row(R.string.storage_voice_models_title, R.string.storage_voice_models_subtitle),
        row(R.string.storage_ai_models_title, R.string.storage_ai_models_subtitle),
        row(R.string.storage_themes_title, R.string.storage_themes_subtitle),
        row(R.string.storage_learned_title, R.string.storage_learned_subtitle),
        row(R.string.storage_cache_images_title, R.string.storage_cache_images_subtitle),
        row(R.string.storage_settings_data_title, R.string.storage_settings_data_subtitle),
    )
}

/** Rows that live on one of those screens rather than naming it. */
private fun SearchStrings.sectionChildRows(): List<SettingsSearchEntry> = listOf(
    entry(R.string.fonts_installed_header, 0, R.string.home_screen_fonts_title, "fonts", screenParent = R.string.home_appearance_title),
    entry(
        R.string.customdict_emoji_auto_download_title,
        R.string.customdict_emoji_auto_download_subtitle,
        R.string.home_screen_emoji_keywords_title,
        "emojikeywords",
        screenParent = R.string.home_emoji_title,
    ),
)

/**
 * The tools themselves, derived from the enum rather than listed, so a new
 * tool is searchable the moment it has a title. No index edit needed.
 */
private fun SearchStrings.toolRows(): List<SettingsSearchEntry> =
    ToolbarTool.entries.filter { isSupportedTool(it) && it !in ActionOnlyTools }.map { tool ->
        toolEntry(tool, toolTitle(tool), toolDescription(tool), weight = EntryWeight.TOOL)
    }

/**
 * The whole searchable surface, in the language [strings] is read from.
 *
 * Build it once per screen, keyed on the context, and hand the result to
 * [searchSettings]. Tool-detail rows for tools this build cannot provide (the
 * F-Droid build drops the ML Kit ones) are filtered out, because their screens
 * are unreachable. A row listed twice by mistake is kept once: the results list
 * keys on the entry, and two rows with one key would crash it.
 */
internal fun settingsSearchIndex(strings: SearchStrings): List<SettingsSearchEntry> = with(strings) {
    val unsupported = ToolbarTool.entries.filterNot(::isSupportedTool)
        .map { "tool/${it.name}" }.toSet()
    val all = sectionRows() +
        toolRows() +
        // After the tools: a step of a result's path names the first screen
        // the index has by that name, and a tool's page is that screen.
        providerKindRows() +
        sectionChildRows() +
        typingRows() +
        typingCorrectionsRows() +
        typingWritingRows() +
        typingSuggestionsRows() +
        typingAutopilotRows() +
        clipRulesRows() +
        typingOctopusRows() +
        blacklistRows() +
        typingChipsRows() +
        typingCodesRows() +
        typingGlideRows() +
        typingGlideCheckupRows() +
        typingSpacebarRows() +
        typingFlicksRows() +
        typingHardwareRows() +
        keyPressRows() +
        keypressHapticsRows() +
        keypressPopupRows() +
        keypressShortcutsRows() +
        appearanceRows() +
        appearanceToolbarRows() +
        appearanceToolboxRows() + photoRows() + layoutRows() +
        layoutSizeRows() +
        layoutOnehandedRows() + floatingButtonRows() + shareSheetRows() + languageRows() + emojiRows() +
        emojiPanelRows() + emojiMixRows() + kaomojiRows() +
        voiceRows() + clipboardRows() + clipboardHistoryRows() + clipboardChipsRows() + clipboardRulesRows() + expanderRows() + toolPageRowsA() + toolPageRowsB() + galleryRows() + sendRows() + vocabRows() + notesRows() + todoRows() + storageRows() + otherRows()
    // A provider's screen goes with the tool page its list is on.
    all.filterNot { it.route in unsupported || it.tool?.let { tool -> "tool/${tool.name}" in unsupported } == true }
        .distinctBy { it.key }
}

/** The index in the language [res] is configured for. */
internal fun settingsSearchIndex(res: Resources): List<SettingsSearchEntry> =
    settingsSearchIndex(ResourceSearchStrings(res))

/**
 * The path strip's steps for the screen [entry] opens, for a jump that reaches
 * it without walking there.
 *
 * A search result is navigated to from the results list, so the back stack
 * behind it holds the search screen and nothing else. The strip is drawn from
 * that stack, which is why it used to read "Home › Personal dictionary" for a
 * screen that really lives at "Home › Typing › Suggestions › Personal
 * dictionary" (#111). The path the result already draws under its own title is
 * the same path, so it is what the trail is seeded with.
 *
 * Two parts are dropped. The settings home is already the first real step of
 * every trail, and a row entry's path ends with the very screen being opened,
 * which the destination puts on the trail for itself a frame later.
 *
 * A step's route is looked up by name against the index's own destination
 * entries, so a step is pressable exactly when the index knows a screen by
 * that name. Nothing else in the app has to agree with a second table.
 */
internal fun settingsCrumbSeed(
    entry: SettingsSearchEntry,
    index: List<SettingsSearchEntry>,
    homeTitle: String,
): List<SettingsCrumb> {
    // Reversed, so that a name indexed twice keeps the route of the entry that
    // comes first in the index — the destination, ahead of any later mention.
    // A tool's own page counts too, below a screen of the same name: a row on
    // a provider's screen has the tool above it, and that step should open.
    val routes = index.asReversed().filter { it.weight == EntryWeight.TOOL }.associate { it.title to it.route } +
        index.asReversed().filter { it.weight == EntryWeight.SECTION }.associate { it.title to it.route }
    val steps = entry.screenPath
        .filterNot { it == homeTitle }
        .map { SettingsCrumb(entryId = "seed:$it", title = it, route = routes[it], seeded = true) }
    // A destination's path stops above it; an ordinary row's ends with the very
    // screen it is drawn on, which is the screen being opened.
    return if (entry.weight == EntryWeight.SECTION) steps else steps.dropLast(1)
}

// The ranking itself lives in SettingsSearchMatch.kt: what a query word is
// worth against an entry, and the word groups that let a search for a vibration
// find the haptics rows.

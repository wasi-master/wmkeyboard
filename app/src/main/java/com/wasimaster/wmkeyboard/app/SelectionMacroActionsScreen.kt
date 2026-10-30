package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Abc
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.selection.MacroCategory
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.effectiveTimeZones
import com.wasimaster.wmkeyboard.core.tools.orderedAiActions
import com.wasimaster.wmkeyboard.ime.ui.selectionMacroIcon
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone

/** The Actions list, the AI buttons page and the Time zones page, under Selection actions. */
internal const val SelectionMacroActionsRoute = "selection_macros/actions"
internal const val SelectionMacroAiRoute = "selection_macros/ai"
internal const val SelectionMacroZonesRoute = "selection_macros/zones"

/** Selection actions › Icon only. */
internal const val SelectionMacroIconsRoute = "selection_macros/icons"

/** The configurable macros, grouped for browsing, each group in shipped order. */
private val MacroGroups: List<Pair<MacroCategory, List<SelectionMacro>>> =
    MacroCategory.entries.map { category ->
        category to (SelectionMacros.defaultOrder + SelectionMacros.ladderOnly).filter { it.category == category }
    }

/**
 * Settings › Advanced › Selection actions › Actions: every action the bar can
 * offer, with its switch, grouped by what it does, over a field that searches
 * the list.
 *
 * Forty-odd rows is more than anyone reads to find the one they came for, so
 * the field is the way in and the folded groups are the way to browse, the
 * shape the Tools screen settled on. The pencil on the first group turns the
 * groups into one draggable list of the actions that are on, which is the
 * order the bar draws them in.
 */
@Composable
internal fun SelectionMacroActionsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val index = rememberMacroSearchIndex()
    val matches = remember(query, index) {
        val tokens = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) emptyList() else index.keys.filter { macro -> tokens.all { index.getValue(macro).contains(it) } }
    }
    val optionsDesc = stringResource(R.string.selection_macros_options_desc)
    // The action whose name on the bar is being changed (#970).
    var renaming by rememberSaveable { mutableStateOf<SelectionMacro?>(null) }
    @Composable
    fun row(macro: SelectionMacro) = MacroRow(
        macro = macro,
        on = settings.watch { macro in it.selectionMacros.macros },
        label = settings.watch { it.selectionMacros.labels[macro] },
        optionsRoute = macroOptionsRoute(macro),
        optionsDesc = optionsDesc,
        onToggle = { on -> scope.launch { repository.setSelectionMacroEnabled(macro, on) } },
        onOpen = onNavigate,
        onRename = { renaming = macro },
    )
    renaming?.let { macro ->
        MacroRenameDialog(
            macro = macro,
            current = settings.value.selectionMacros.labels[macro],
            onDismiss = { renaming = null },
        ) { label ->
            renaming = null
            scope.launch { repository.setSelectionMacroLabel(macro, label) }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.selection_macros_actions_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_clear))
                }
            }
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (query.isNotBlank()) {
        if (matches.isEmpty()) {
            CaptionText(stringResource(R.string.selection_macros_actions_search_empty, query))
            return
        }
        SettingsGroup(stringResource(R.string.selection_macros_actions_results_title)) {
            for (macro in matches) item { row(macro) }
        }
        return
    }

    // The order the bar draws: only what is on, and never Undo, which is
    // pinned first whatever this says. Anything switched on later joins at
    // the end, where a new chip is easiest to notice.
    // It also decides which rows the group holds, so it is read here.
    val onRow = settings.watch { s ->
        val prefs = s.selectionMacros
        prefs.order.filter { it in prefs.macros && it != SelectionMacro.UNDO && it !in SelectionMacros.ladderOnly }
    }
    val names = SelectionMacro.entries.associateWith { stringResource(it.labelRes) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(
        stringResource(R.string.selection_macros_reorder_title),
        info = stringResource(R.string.selection_macros_reorder_info),
        action = if (onRow.size > 1) {
            {
                IconButton(onClick = { reordering = !reordering }) {
                    Icon(
                        if (reordering) Icons.Outlined.Check else Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.selection_macros_reorder_desc),
                    )
                }
            }
        } else {
            null
        },
    ) {
        if (reordering) {
            item {
                ReorderableColumn(
                    onRow,
                    label = { names.getValue(it) },
                    onReorder = { moved ->
                        val order = settings.value.selectionMacros.order
                        scope.launch { repository.setSelectionMacroOrder(moved + order.filter { it !in moved }) }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            item { CaptionText(onRow.joinToString(" · ") { names.getValue(it) }) }
        }
    }
    if (reordering) return

    MacroGroups.forEach { (category, macros) ->
        SettingsGroup(
            stringResource(categoryTitle(category)),
            foldKey = category.name.lowercase(Locale.ROOT),
            foldSummary = { macros.map { stringResource(it.labelRes) }.joinToString(", ") },
        ) {
            for (macro in macros) item { row(macro) }
        }
    }
}

/** One action: its chip word, what it does, the pencil that renames it, the arrow to its options and the switch. */
@Composable
private fun MacroRow(
    macro: SelectionMacro,
    on: Boolean,
    /** The user's own name for it (#970), or null. */
    label: String?,
    optionsRoute: String?,
    optionsDesc: String,
    onToggle: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onRename: () -> Unit,
) {
    WmRow(
        title = label ?: stringResource(macro.labelRes),
        subtitle = stringResource(macroDescription(macro)),
        // The chip's own glyph, so the list reads as the bar it configures. The
        // case options have none on the bar (their names are written in the case
        // they apply) and share one here, so the column of tiles stays unbroken.
        icon = selectionMacroIcon(macro) ?: Icons.Outlined.Abc,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.selection_macros_rename_desc))
                }
                if (optionsRoute != null) {
                    IconButton(onClick = { onOpen(optionsRoute) }) {
                        Icon(Icons.Outlined.Tune, contentDescription = optionsDesc)
                    }
                }
                Switch(checked = on, onCheckedChange = onToggle)
            }
        },
        onClick = { onToggle(!on) },
    )
}

/**
 * Asks for an action's name on the bar (#970). Empty, or Reset, goes back to
 * the shipped word.
 */
@Composable
private fun MacroRenameDialog(
    macro: SelectionMacro,
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    val shipped = stringResource(macro.labelRes)
    var name by rememberSaveable { mutableStateOf(current ?: shipped) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.selection_macros_rename_title, shipped)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MacroLabelMax) },
                singleLine = true,
                label = { Text(stringResource(R.string.selection_macros_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().takeIf { it.isNotEmpty() && it != shipped }) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onSave(null) }) { Text(stringResource(CommonR.string.common_reset)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

/** Long enough for a word or two, short enough to stay a chip. */
private const val MacroLabelMax = 24

private fun macroOptionsRoute(macro: SelectionMacro): String? = when (macro) {
    SelectionMacro.AI -> SelectionMacroAiRoute
    SelectionMacro.TIME_ZONES -> SelectionMacroZonesRoute
    else -> null
}

/** Name plus description per macro, folded the way the settings search folds, in the shown language. */
@Composable
private fun rememberMacroSearchIndex(): Map<SelectionMacro, String> {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) {
        SelectionMacros.configurable.associateWith { macro ->
            normalizeForSearch(resources.getString(macro.labelRes) + ' ' + resources.getString(macroDescription(macro)))
        }
    }
}

@StringRes
internal fun categoryTitle(category: MacroCategory): Int = when (category) {
    MacroCategory.EDITING -> R.string.selection_macros_group_editing_title
    MacroCategory.LINES -> R.string.selection_macros_group_lines_title
    MacroCategory.FORMAT -> R.string.selection_macros_group_format_title
    MacroCategory.CONVERT -> R.string.selection_macros_group_convert_title
    MacroCategory.LANGUAGE -> R.string.selection_macros_group_language_title
    MacroCategory.LOOKUP -> R.string.selection_macros_group_lookup_title
    MacroCategory.OPEN_IN -> R.string.selection_macros_group_open_in_title
}

/** What each action does, in one line under its chip word. Exhaustive, so a new action has to say. */
@StringRes
internal fun macroDescription(macro: SelectionMacro): Int = when (macro) {
    SelectionMacro.UNDO -> R.string.selection_macros_desc_undo
    SelectionMacro.SELECT_ALL -> R.string.selection_macros_desc_select_all
    SelectionMacro.COPY -> R.string.selection_macros_desc_copy
    SelectionMacro.CUT -> R.string.selection_macros_desc_cut
    SelectionMacro.PASTE -> R.string.selection_macros_desc_paste
    SelectionMacro.DELETE -> R.string.selection_macros_desc_delete
    SelectionMacro.SHARE -> R.string.selection_macros_desc_share
    SelectionMacro.SAVE_NOTE -> R.string.selection_macros_desc_save_note
    SelectionMacro.FORMAT -> R.string.selection_macros_desc_format
    SelectionMacro.FIND -> R.string.selection_macros_desc_find
    SelectionMacro.REPLACE -> R.string.selection_macros_desc_replace
    SelectionMacro.REPEAT -> R.string.selection_macros_desc_repeat
    SelectionMacro.SNIPPET -> R.string.selection_macros_desc_snippet
    SelectionMacro.LINES_SORT -> R.string.selection_macros_desc_lines_sort
    SelectionMacro.LINES_DEDUPE -> R.string.selection_macros_desc_lines_dedupe
    SelectionMacro.LINES_NUMBER -> R.string.selection_macros_desc_lines_number
    SelectionMacro.LINES_BULLET -> R.string.selection_macros_desc_lines_bullet
    SelectionMacro.LINES_CHECKBOX -> R.string.selection_macros_desc_lines_checkbox
    SelectionMacro.LINES_UP -> R.string.selection_macros_desc_lines_up
    SelectionMacro.LINES_DOWN -> R.string.selection_macros_desc_lines_down
    SelectionMacro.SEARCH -> R.string.selection_macros_desc_search
    SelectionMacro.TRANSLATE -> R.string.selection_macros_desc_translate
    SelectionMacro.GRAMMAR_FIX -> R.string.selection_macros_desc_grammar
    SelectionMacro.DEEPL_WRITE -> R.string.selection_macros_desc_deepl_write
    SelectionMacro.AI -> R.string.selection_macros_desc_ai
    SelectionMacro.TO_BANGLA -> R.string.selection_macros_desc_to_bangla
    SelectionMacro.TO_BANGLISH -> R.string.selection_macros_desc_to_banglish
    SelectionMacro.TO_HINDI -> R.string.selection_macros_desc_to_hindi
    SelectionMacro.TO_HINGLISH -> R.string.selection_macros_desc_to_hinglish
    SelectionMacro.DIGITS_LATIN -> R.string.selection_macros_desc_digits_latin
    SelectionMacro.ZAWGYI -> R.string.selection_macros_desc_zawgyi
    SelectionMacro.KOREAN_SPACING -> R.string.selection_macros_desc_korean_spacing
    SelectionMacro.COLOUR -> R.string.selection_macros_desc_colour
    SelectionMacro.JSON_FORMAT -> R.string.selection_macros_desc_json
    SelectionMacro.BASE64_DECODE -> R.string.selection_macros_desc_base64
    SelectionMacro.URL_DECODE -> R.string.selection_macros_desc_url_decode
    SelectionMacro.STRIP_TRACKERS -> R.string.selection_macros_desc_strip_trackers
    SelectionMacro.CHAT_BOLD -> R.string.selection_macros_desc_chat_bold
    SelectionMacro.CHAT_ITALIC -> R.string.selection_macros_desc_chat_italic
    SelectionMacro.CHAT_STRIKE -> R.string.selection_macros_desc_chat_strike
    SelectionMacro.CHAT_MONO -> R.string.selection_macros_desc_chat_mono
    SelectionMacro.READ_ALOUD -> R.string.selection_macros_desc_read_aloud
    SelectionMacro.EDIT_WORD -> R.string.selection_macros_desc_edit_word
    SelectionMacro.TIME_ZONES -> R.string.selection_macros_desc_time_zones
    SelectionMacro.CALL -> R.string.selection_macros_desc_call
    SelectionMacro.SMS -> R.string.selection_macros_desc_sms
    SelectionMacro.WHATSAPP -> R.string.selection_macros_desc_whatsapp
    SelectionMacro.EMAIL -> R.string.selection_macros_desc_email
    SelectionMacro.OPEN -> R.string.selection_macros_desc_open
    SelectionMacro.QR -> R.string.selection_macros_desc_qr
    SelectionMacro.ADD_CONTACT -> R.string.selection_macros_desc_add_contact
    SelectionMacro.MAP -> R.string.selection_macros_desc_map
    SelectionMacro.CALENDAR -> R.string.selection_macros_desc_calendar
    SelectionMacro.FANCY -> R.string.selection_macros_desc_fancy
    SelectionMacro.CASE_LOWER, SelectionMacro.CASE_TITLE, SelectionMacro.CASE_UPPER, SelectionMacro.CASE_SENTENCE,
    SelectionMacro.CASE_CAMEL, SelectionMacro.CASE_SNAKE, SelectionMacro.CASE_KEBAB, SelectionMacro.CASE_CONSTANT ->
        R.string.selection_macros_desc_case
}

/**
 * Selection actions › AI buttons: which AI actions get a chip of their own
 * on the bar, beside the AI chip. Picked from the AI tool's own list, in its
 * order; an action that asks for its prompt each run cannot be a one-tap
 * button and is not offered.
 */
@Composable
internal fun SelectionMacroAiScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Decides which rows the group holds; each row reads whether it is picked.
    val actions = settings.watch { s ->
        orderedAiActions(s.ai.customActions, s.ai.actionOrder).filter { !it.askEachRun }
    }
    SettingsGroup(
        stringResource(R.string.selection_macros_ai_title),
        info = stringResource(R.string.selection_macros_ai_info),
    ) {
        for (action in actions) {
            item {
                val on = settings.watch { action.id in it.selectionMacros.aiDirectActions }
                WmRow(
                    title = aiActionName(action),
                    leading = {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked ->
                                val picked = settings.value.selectionMacros.aiDirectActions.toSet()
                                val next = actions.map { it.id }.filter { it in picked && it != action.id || (checked && it == action.id) }
                                scope.launch { repository.setSelectionMacroAiActions(next) }
                            },
                        )
                    },
                    onClick = {
                        val picked = settings.value.selectionMacros.aiDirectActions.toSet()
                        val next = actions.map { it.id }.filter { it in picked && it != action.id || (!on && it == action.id) }
                        scope.launch { repository.setSelectionMacroAiActions(next) }
                    },
                )
            }
        }
    }
    SettingsGroup {
        item {
            NavRow(R.string.selection_macros_ai_manage_title, route = "ai_actions") { onNavigate("ai_actions") }
        }
    }
}

/**
 * Selection actions › Icon only (#679): which of the actions that are on draw
 * as their icon alone. Only the ones with an icon are listed; a case option is
 * written in the case it applies and has nothing else to show.
 */
@Composable
internal fun SelectionMacroIconsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    // Decides which rows the group holds; each row reads whether it is picked.
    val shown = settings.watch { s ->
        val prefs = s.selectionMacros
        prefs.order.filter { it in prefs.macros && it != SelectionMacro.UNDO && selectionMacroIcon(it) != null }
    }
    SettingsGroup(
        stringResource(R.string.selection_macros_icons_title),
        info = stringResource(R.string.selection_macros_icons_info),
    ) {
        if (shown.isEmpty()) {
            item { CaptionText(stringResource(R.string.selection_macros_icons_empty)) }
        }
        for (macro in shown) {
            item {
                val on = settings.watch { macro in it.selectionMacros.iconOnly }
                WmRow(
                    title = stringResource(macro.labelRes),
                    icon = selectionMacroIcon(macro) ?: Icons.Outlined.Abc,
                    trailing = {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked -> scope.launch { repository.setSelectionMacroIconOnly(macro, checked) } },
                        )
                    },
                    onClick = { scope.launch { repository.setSelectionMacroIconOnly(macro, !on) } },
                )
            }
        }
    }
}

/**
 * Selection actions › Time zones: the zones the Zones ladder renders a
 * selected time in. Empty means UTC and the device's own zone; the list is
 * reorderable, and a search over every zone the device knows adds to it.
 */
@Composable
internal fun SelectionMacroZonesScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val locale = Locale.getDefault()
    val deviceZone = TimeZone.getDefault().id
    val zones = settings.watch { it.selectionMacros.effectiveTimeZones(deviceZone) }
    // Decides whether the default-zones caption is a row of the group.
    val defaultZones = settings.watch { it.selectionMacros.timeZones.isEmpty() }
    val labels = remember(zones, locale) { zones.associateWith { zoneLabel(it, locale) } }
    SettingsGroup(
        stringResource(R.string.selection_macros_zones_title),
        info = stringResource(R.string.selection_macros_zones_info),
    ) {
        item {
            ReorderableColumn(
                zones,
                label = { labels.getValue(it) },
                onReorder = { next -> scope.launch { repository.setSelectionMacroTimeZones(next) } },
                onDelete = { id -> scope.launch { repository.setSelectionMacroTimeZones(zones - id) } },
                keepLast = false,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (defaultZones) {
            item { CaptionText(stringResource(R.string.selection_macros_zones_default_caption)) }
        }
        item {
            ToggleSetting(
                R.string.selection_macros_zones_sort_title,
                stringResource(R.string.selection_macros_zones_sort_subtitle),
                settings.watch { it.selectionMacros.sortZonesByTime },
                default = SettingsDefaults.selectionMacros.sortZonesByTime,
            ) { scope.launch { repository.setSelectionMacroSortZones(it) } }
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(locale) {
        TimeZone.getAvailableIDs()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
            .associateWith { id -> normalizeForSearch(id.replace('_', ' ').replace('/', ' ') + ' ' + zoneLabel(id, locale)) }
    }
    val matches = remember(query, all, zones) {
        val tokens = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) emptyList() else all.keys.filter { id -> id !in zones && tokens.all { all.getValue(id).contains(it) } }.take(MAX_ZONE_RESULTS)
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.selection_macros_zones_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (matches.isNotEmpty()) {
        SettingsGroup {
            for (id in matches) {
                item {
                    WmRow(
                        title = zoneLabel(id, locale),
                        subtitle = id,
                        trailing = {
                            IconButton(onClick = { scope.launch { repository.setSelectionMacroTimeZones(zones + id) } }) {
                                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.selection_macros_zones_add_desc))
                            }
                        },
                        onClick = { scope.launch { repository.setSelectionMacroTimeZones(zones + id) } },
                    )
                }
            }
        }
    }
}

private const val MAX_ZONE_RESULTS = 50

/** `Dhaka (UTC+06:00)`: the city and the offset, which is what tells two zones apart on a list. */
private fun zoneLabel(id: String, locale: Locale): String {
    if (id == "UTC") return "UTC"
    val zone = TimeZone.getTimeZone(id)
    val offset = zone.rawOffset / 60_000
    val sign = if (offset < 0) "-" else "+"
    val hours = kotlin.math.abs(offset) / 60
    val minutes = kotlin.math.abs(offset) % 60
    val city = id.substringAfterLast('/').replace('_', ' ')
    return "%s (UTC%s%02d:%02d)".format(locale, city, sign, hours, minutes)
}

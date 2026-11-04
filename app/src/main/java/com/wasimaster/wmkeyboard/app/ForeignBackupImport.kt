package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutName
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.foreign.ForeignBackup
import com.wasimaster.wmkeyboard.core.settings.foreign.ForeignKeyboard
import com.wasimaster.wmkeyboard.core.settings.foreign.ForeignSettingKind
import com.wasimaster.wmkeyboard.core.settings.ToolHoldAction
import com.wasimaster.wmkeyboard.core.settings.ToolHoldActions
import com.wasimaster.wmkeyboard.core.settings.foreign.ForeignTheme
import com.wasimaster.wmkeyboard.core.settings.foreign.LeftBehind
import com.wasimaster.wmkeyboard.core.settings.foreign.MappedSetting
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.groupAsFamily
import com.wasimaster.wmkeyboard.core.theme.withFreshIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything in another keyboard's backup, one row each, every row ticked to
 * start with. Import stores the ticked ones and nothing else.
 *
 * A full-screen dialog rather than the import dialog's usual one question: a
 * HeliBoard backup can hold a dozen layouts, a few themes, three languages and
 * twenty settings, and someone moving over may want all of it but the one
 * theme they never liked.
 */
@Composable
internal fun ForeignBackupDialog(
    backup: ForeignBackup,
    repository: SettingsRepository,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notify = rememberDownloadNotifier()
    val rows = remember(backup) { foreignRows(backup) }
    var selected by remember(backup) { mutableStateOf<Set<String>>(rows.mapTo(HashSet()) { it.id }) }
    var working by remember { mutableStateOf(false) }
    // The button's glyph: a spinner while the items are stored, then the tick
    // for a moment before the dialog gives way to what landed.
    var phase by remember { mutableStateOf(RunPhase.IDLE) }
    val reduceMotion = LocalReduceMotion.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_cancel))
                    }
                    Text(
                        stringResource(R.string.foreign_backup_title, keyboardName(context, backup.keyboard)),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    Text(
                        stringResource(R.string.foreign_backup_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // Each heading and row a beat after the one above, counted
                    // down the whole list; past the first few they arrive together.
                    var arrival = 0
                    for (section in ForeignSection.entries) {
                        val inSection = rows.filter { it.section == section }
                        if (inSection.isEmpty()) continue
                        StaggerIn(arrival++) {
                            ForeignSectionHeader(
                                title = stringResource(section.titleRes),
                                state = when (inSection.count { it.id in selected }) {
                                    0 -> ToggleableState.Off
                                    inSection.size -> ToggleableState.On
                                    else -> ToggleableState.Indeterminate
                                },
                                onToggle = { on ->
                                    val ids = inSection.map { it.id }.toSet()
                                    selected = if (on) selected + ids else selected - ids
                                },
                            )
                        }
                        for (row in inSection) {
                            StaggerIn(arrival++) {
                                ForeignItemRow(
                                    row = row,
                                    checked = row.id in selected,
                                    onChecked = { on ->
                                        selected = if (on) selected + row.id else selected - row.id
                                    },
                                )
                            }
                        }
                        if (section == ForeignSection.LANGUAGES) {
                            CaptionText(stringResource(R.string.foreign_backup_languages_note))
                        }
                    }
                    if (backup.leftBehind.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                        Text(
                            stringResource(R.string.foreign_backup_left_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                        for (left in backup.leftBehind) {
                            Text(
                                "• " + stringResource(leftBehindRes(left.kind)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Button(
                        enabled = !working && selected.isNotEmpty(),
                        onClick = {
                            if (working) return@Button
                            working = true
                            phase = RunPhase.RUNNING
                            scope.launch {
                                val message = applyForeignBackup(context, repository, backup, selected, notify)
                                phase = RunPhase.DONE
                                if (!reduceMotion) delay(IMPORT_DONE_HOLD_MS)
                                working = false
                                onDone(message)
                            }
                        },
                    ) {
                        RunGlyph(phase)
                        // Ticks along as rows are ticked and unticked.
                        CountingText(
                            target = selected.size.toLong(),
                            format = { n ->
                                pluralStringResource(R.plurals.foreign_backup_import_count, n.toInt(), n.toInt())
                            },
                            style = LocalTextStyle.current,
                        )
                    }
                }
            }
        }
    }
}

/** The list's sections, in the order they are shown. */
internal enum class ForeignSection(val titleRes: Int) {
    LAYOUTS(R.string.foreign_backup_section_layouts),
    THEMES(R.string.foreign_backup_section_themes),
    LANGUAGES(R.string.foreign_backup_section_languages),
    WORDS(R.string.foreign_backup_section_words),
    SETTINGS(R.string.foreign_backup_section_settings),
}

/**
 * One row of the list. [id] is what the selection holds and what
 * [applyForeignBackup] reads back, `<kind>:<index>`, so the two cannot disagree
 * about which item a tick belongs to.
 */
internal data class ForeignRow(
    val id: String,
    val section: ForeignSection,
    val title: (Context) -> String,
    val subtitle: (Context) -> String?,
    val swatch: List<Long> = emptyList(),
)

internal fun foreignRows(backup: ForeignBackup): List<ForeignRow> = buildList {
    backup.layouts.forEachIndexed { i, layout ->
        add(
            ForeignRow("layout:$i", ForeignSection.LAYOUTS, { layout.converted.layout.name }, {
                LanguageRegistry.byId(layout.langId).displayName
            }),
        )
    }
    backup.themes.forEachIndexed { i, theme ->
        val spec = themeSpecOf(theme)
        add(
            ForeignRow(
                "theme:$i",
                ForeignSection.THEMES,
                { theme.name },
                { ctx ->
                    ctx.getString(
                        if (theme.ref == backup.activeThemeRef) R.string.foreign_backup_theme_active
                        else R.string.foreign_backup_theme_saved,
                    )
                },
                swatch = listOfNotNull(spec?.boardBackground, spec?.keyBackground, spec?.accent),
            ),
        )
    }
    backup.languages.forEachIndexed { i, language ->
        val custom = language.customRef?.let { ref -> backup.layouts.firstOrNull { it.ref == ref } }
        add(
            ForeignRow("language:$i", ForeignSection.LANGUAGES, { LanguageRegistry.byId(language.langId).displayName }, { ctx ->
                if (custom != null) {
                    ctx.getString(R.string.foreign_backup_language_custom, custom.converted.layout.name)
                } else {
                    ctx.getString(R.string.foreign_backup_language_layout, resolveLayoutName(emptyList(), language.layoutId))
                }
            }),
        )
    }
    backup.dictionaries.forEachIndexed { i, dictionary ->
        val language = LanguageRegistry.byId(dictionary.langId).displayName
        add(
            ForeignRow(
                "dictionary:$i",
                ForeignSection.WORDS,
                { ctx ->
                    if (dictionary.learned) ctx.getString(R.string.foreign_backup_learned_title, language)
                    else dictionary.candidate.name.substringAfterLast('/')
                },
                { ctx ->
                    val count = dictionary.candidate.words.size
                    val pairs = dictionary.candidate.ngrams.size
                    var counted = ctx.resources.getQuantityString(R.plurals.foreign_backup_word_count, count, count)
                    // A learned list can carry the pairs typed together too.
                    if (pairs > 0) {
                        counted += ", " + ctx.resources.getQuantityString(R.plurals.customdict_pair_count, pairs, pairs)
                    }
                    ctx.getString(R.string.foreign_backup_dictionary_subtitle, language, counted)
                },
            ),
        )
    }
    if (backup.personalWords.isNotEmpty()) {
        add(
            ForeignRow("words", ForeignSection.WORDS, { it.getString(R.string.foreign_backup_personal_title) }, { ctx ->
                val count = backup.personalWords.size
                ctx.resources.getQuantityString(R.plurals.foreign_backup_word_count, count, count) + ": " +
                    backup.personalWords.take(SAMPLE_WORDS).joinToString { it.word }
            }),
        )
    }
    if (backup.blacklist.isNotEmpty()) {
        add(
            ForeignRow("blacklist", ForeignSection.WORDS, { it.getString(R.string.foreign_backup_blacklist_title) }, { ctx ->
                val count = backup.blacklist.size
                ctx.resources.getQuantityString(R.plurals.foreign_backup_word_count, count, count) + ": " +
                    backup.blacklist.take(SAMPLE_WORDS).joinToString()
            }),
        )
    }
    if (backup.snippets.isNotEmpty()) {
        add(
            ForeignRow("snippets", ForeignSection.WORDS, { it.getString(R.string.foreign_backup_snippets_title) }, { ctx ->
                val count = backup.snippets.size
                ctx.resources.getQuantityString(R.plurals.foreign_backup_snippet_count, count, count) + ": " +
                    backup.snippets.take(SAMPLE_WORDS).joinToString { it.trigger ?: it.label }
            }),
        )
    }
    backup.settings.forEach { setting ->
        add(
            ForeignRow(
                "setting:${setting.kind.name}",
                ForeignSection.SETTINGS,
                { it.getString(settingLabelRes(setting.kind)) },
                { settingValueText(it, setting) },
            ),
        )
    }
}

private fun themeSpecOf(theme: ForeignTheme): ThemeSpec? = when (theme) {
    is ForeignTheme.Colours -> theme.theme
    is ForeignTheme.Flex -> theme.result.themes.firstOrNull()?.theme
    is ForeignTheme.Futo -> theme.result.converted.theme
    is ForeignTheme.Kinetica -> theme.result.theme
}

@Composable
private fun ForeignSectionHeader(title: String, state: ToggleableState, onToggle: (Boolean) -> Unit) {
    val description = stringResource(R.string.foreign_backup_section_toggle_desc, title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(state != ToggleableState.On) }
            .padding(start = 4.dp, end = 16.dp, top = 12.dp),
    ) {
        TriStateCheckbox(
            state = state,
            onClick = { onToggle(state != ToggleableState.On) },
            modifier = Modifier.semantics { contentDescription = description },
        )
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ForeignItemRow(row: ForeignRow, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(start = 28.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(Modifier.weight(1f)) {
            Text(row.title(context), style = MaterialTheme.typography.bodyLarge)
            row.subtitle(context)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (row.swatch.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (colour in row.swatch) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .background(Color(colour), CircleShape),
                    )
                }
            }
        }
    }
}

internal fun keyboardName(context: Context, keyboard: ForeignKeyboard): String = context.getString(
    when (keyboard) {
        ForeignKeyboard.HELIBOARD -> R.string.foreign_keyboard_heliboard
        ForeignKeyboard.LEANTYPE -> R.string.foreign_keyboard_leantype
        ForeignKeyboard.FUTO -> R.string.foreign_keyboard_futo
        ForeignKeyboard.FLORISBOARD -> R.string.foreign_keyboard_florisboard
        ForeignKeyboard.CLEVERKEYS -> R.string.foreign_keyboard_cleverkeys
        ForeignKeyboard.ANYSOFTKEYBOARD -> R.string.foreign_keyboard_anysoftkeyboard
        ForeignKeyboard.KINETICA -> R.string.foreign_keyboard_kinetica
    },
)

private fun settingLabelRes(kind: ForeignSettingKind): Int = when (kind) {
    ForeignSettingKind.NUMBER_ROW -> R.string.foreign_setting_number_row
    ForeignSettingKind.KEY_POPUP -> R.string.foreign_setting_key_popup
    ForeignSettingKind.HAPTICS -> R.string.foreign_setting_haptics
    ForeignSettingKind.HAPTIC_STRENGTH -> R.string.foreign_setting_haptic_strength
    ForeignSettingKind.KEY_SOUND -> R.string.foreign_setting_key_sound
    ForeignSettingKind.SOUND_VOLUME -> R.string.foreign_setting_sound_volume
    ForeignSettingKind.AUTOCORRECT -> R.string.foreign_setting_autocorrect
    ForeignSettingKind.AUTO_CAPITALIZE -> R.string.foreign_setting_auto_capitalize
    ForeignSettingKind.DOUBLE_SPACE_PERIOD -> R.string.foreign_setting_double_space_period
    ForeignSettingKind.SUGGESTIONS -> R.string.foreign_setting_suggestions
    ForeignSettingKind.EMOJI_SUGGESTIONS -> R.string.foreign_setting_emoji_suggestions
    ForeignSettingKind.GESTURE_TYPING -> R.string.foreign_setting_gesture_typing
    ForeignSettingKind.LONG_PRESS_DELAY -> R.string.foreign_setting_long_press_delay
    ForeignSettingKind.KEY_HEIGHT -> R.string.foreign_setting_key_height
    ForeignSettingKind.SPLIT_KEYBOARD -> R.string.foreign_setting_split_keyboard
    ForeignSettingKind.THEME_MODE -> R.string.foreign_setting_theme_mode
    ForeignSettingKind.TOOL_HOLD_ACTIONS -> R.string.foreign_setting_tool_holds
}

private fun settingValueText(context: Context, setting: MappedSetting): String = when (val value = setting.value) {
    is Boolean -> context.getString(if (value) R.string.foreign_value_on else R.string.foreign_value_off)
    is Float -> context.getString(R.string.foreign_value_percent, (value * PERCENT).toInt())
    is Int -> when (setting.kind) {
        ForeignSettingKind.KEY_HEIGHT -> context.getString(R.string.foreign_value_dp, value)
        else -> context.getString(R.string.foreign_value_ms, value)
    }
    is String -> if (setting.kind == ForeignSettingKind.TOOL_HOLD_ACTIONS) {
        toolHoldsText(context, value)
    } else {
        when (runCatching { ThemeMode.valueOf(value) }.getOrNull()) {
            ThemeMode.SYSTEM -> context.getString(R.string.foreign_value_mode_system)
            ThemeMode.LIGHT -> context.getString(R.string.foreign_value_mode_light)
            ThemeMode.DARK -> context.getString(R.string.foreign_value_mode_dark)
            ThemeMode.AMOLED -> context.getString(R.string.foreign_value_mode_amoled)
            null -> value
        }
    }
    else -> value.toString()
}

/** Each bound tool and what its hold runs, in the toolbar's own words. */
private fun toolHoldsText(context: Context, csv: String): String =
    ToolHoldActions.decode(csv).entries.joinToString(", ") { (tool, action) ->
        context.getString(
            R.string.foreign_value_tool_hold,
            context.getString(toolTitle(tool)),
            when (action) {
                ToolHoldAction.None -> context.getString(R.string.tooldetail_hold_nothing_value)
                ToolHoldAction.OpenSettings -> context.getString(R.string.tooldetail_hold_settings_title)
                is ToolHoldAction.Run -> context.getString(toolTitle(action.tool))
            },
        )
    }

private fun leftBehindRes(kind: LeftBehind.Kind): Int = when (kind) {
    LeftBehind.Kind.CLIPBOARD -> R.string.foreign_backup_left_clipboard
    LeftBehind.Kind.OTHER_LAYOUTS -> R.string.foreign_backup_left_other_layouts
    LeftBehind.Kind.THEME_FILES -> R.string.foreign_backup_left_theme_files
    LeftBehind.Kind.FONTS -> R.string.foreign_backup_left_fonts
    LeftBehind.Kind.BACKGROUND_IMAGES -> R.string.foreign_backup_left_backgrounds
    LeftBehind.Kind.MODELS -> R.string.foreign_backup_left_models
    LeftBehind.Kind.SHORTCUTS -> R.string.foreign_backup_left_shortcuts
    LeftBehind.Kind.CHORDS -> R.string.foreign_backup_left_chords
    LeftBehind.Kind.BASE_DICTIONARIES -> R.string.foreign_backup_left_base_dictionaries
}

/**
 * Stores the ticked items of [backup] and says what landed.
 *
 * Layouts first, then themes, because a language and the active theme refer
 * to them by the other keyboard's name and need the id each got here. Nothing
 * is switched over except what the other keyboard had switched on: the theme
 * that was in use, and the languages.
 */
internal suspend fun applyForeignBackup(
    context: Context,
    repository: SettingsRepository,
    backup: ForeignBackup,
    selected: Set<String>,
    notify: DownloadNotifier?,
): String {
    val stamp = System.currentTimeMillis()
    val done = ArrayList<String>()
    fun counted(plural: Int, count: Int) {
        if (count > 0) done += context.resources.getQuantityString(plural, count, count)
    }

    // Layouts, keyed by the backup's own name for each.
    val layoutIds = HashMap<String, String>()
    backup.layouts.forEachIndexed { i, layout ->
        if ("layout:$i" !in selected) return@forEachIndexed
        val id = "custom_${stamp}_$i"
        repository.upsertCustomLayout(layout.converted.withLanguage(layout.langId).copy(id = id))
        layoutIds[layout.ref] = id
    }
    counted(R.plurals.foreign_backup_done_layouts, layoutIds.size)

    // Themes.
    var themes = 0
    var activeThemeId: String? = null
    val imageDir = withContext(Dispatchers.IO) { File(context.filesDir, "theme_images").apply { mkdirs() } }
    backup.themes.forEachIndexed { i, theme ->
        if ("theme:$i" !in selected) return@forEachIndexed
        val base = "custom_${stamp}_t$i"
        val stored = when (theme) {
            is ForeignTheme.Colours -> theme.theme.copy(id = base)
            is ForeignTheme.Futo -> withContext(Dispatchers.IO) {
                theme.result.converted.stored(base, imageDir, FontStore.get(context))
            }
            is ForeignTheme.Flex -> withContext(Dispatchers.IO) {
                val variants = theme.result.themes.mapIndexed { index, converted ->
                    converted.stored(if (index == 0) base else "${base}_v$index", imageDir, FontStore.get(context))
                }
                groupAsFamily(variants, theme.result.title.ifBlank { theme.name })
            }
            // A family when it followed the phone, so the light half gets an id too.
            is ForeignTheme.Kinetica -> theme.result.theme.withFreshIds(base)
        }
        repository.upsertCustomTheme(stored)
        themes++
        if (theme.ref == backup.activeThemeRef) activeThemeId = stored.id
    }
    activeThemeId?.let { repository.setKeyboardThemeId(it) }
    counted(R.plurals.foreign_backup_done_themes, themes)

    // Languages, added in front of the ones already on, in the other
    // keyboard's order; its first is the one typed in now.
    val languageIds = backup.languages.withIndex()
        .filter { (i, _) -> "language:$i" in selected }
        .map { (_, language) -> language.customRef?.let(layoutIds::get) ?: language.layoutId }
        .distinct()
    if (languageIds.isNotEmpty()) {
        val current = repository.settings.first().enabledLayoutIds
        repository.setEnabledLayoutIds((languageIds + current).distinct())
        repository.setActiveLayoutId(languageIds.first())
        val langIds = backup.languages.withIndex()
            .filter { (i, _) -> "language:$i" in selected }
            .map { it.value.langId }
            .distinct()
        startMissingLanguageData(context, langIds, notify)
    }
    counted(R.plurals.foreign_backup_done_languages, languageIds.size)

    // Settings, verified by reading them back like any restore.
    val settings = backup.settings.filter { "setting:${it.kind.name}" in selected }
    var rolledBack = false
    when (repository.importForeignSettings(settings.map { it.entry })) {
        is SettingsRepository.ImportResult.Applied -> counted(R.plurals.foreign_backup_done_settings, settings.size)
        SettingsRepository.ImportResult.RolledBack -> rolledBack = true
        SettingsRepository.ImportResult.NotABackup -> Unit
    }

    // Word lists, each its own list in the language it is for.
    val lists = backup.dictionaries.withIndex().filter { (i, _) -> "dictionary:$i" in selected }
    if (lists.isNotEmpty()) {
        val written = withContext(Dispatchers.IO) {
            lists.count { (_, dictionary) ->
                CustomDictionaries.write(
                    context.filesDir,
                    dictionary.langId,
                    dictionary.candidate,
                    CustomDictionaries.ImportParts.ALL,
                ).total > 0
            }
        }
        repository.bumpCustomDictVersion()
        counted(R.plurals.foreign_backup_done_word_lists, written)
    }

    // Words added by hand there were vouched for, so they are added by hand here.
    if ("words" in selected && backup.personalWords.isNotEmpty()) {
        withContext(Dispatchers.IO) {
            val lexicon = UserLexicon(File(context.filesDir, "learning/user_lexicon.json"))
            for (word in backup.personalWords) lexicon.addWord(word.word)
            lexicon.save()
        }
        repository.bumpLexiconVersion()
        counted(R.plurals.foreign_backup_done_words, backup.personalWords.size)
    }

    if ("blacklist" in selected && backup.blacklist.isNotEmpty()) {
        repository.addSuggestionBlacklistWords(backup.blacklist)
        counted(R.plurals.foreign_backup_done_blocked, backup.blacklist.size)
    }

    // Expansions in a folder of their own, named for the keyboard they came
    // from, which gives the set one off switch.
    if ("snippets" in selected && backup.snippets.isNotEmpty()) {
        withContext(Dispatchers.IO) {
            val store = SnippetStore(File(context.filesDir, "snippets/snippets.json"))
            val folder = store.addFolder(keyboardName(context, backup.keyboard)).id
            store.addAll(backup.snippets, fallbackFolderId = folder)
            store.save()
        }
        counted(R.plurals.foreign_backup_done_snippets, backup.snippets.size)
    }

    return buildString {
        append(
            if (done.isEmpty()) {
                context.getString(R.string.foreign_backup_done_nothing)
            } else {
                context.getString(R.string.foreign_backup_done, done.joinToString())
            },
        )
        if (rolledBack) append("\n\n").append(context.getString(R.string.foreign_backup_settings_rolled_back))
    }
}

/** How many words of a list the row's subtitle shows. */
private const val SAMPLE_WORDS = 5

/** How long the finished tick stays on the button before the result replaces the dialog. */
private const val IMPORT_DONE_HOLD_MS = 600L
private const val PERCENT = 100

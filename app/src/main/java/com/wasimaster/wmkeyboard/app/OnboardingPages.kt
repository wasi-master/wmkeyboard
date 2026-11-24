package com.wasimaster.wmkeyboard.app

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BrightnessAuto
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CalendarMonth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Contrast
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DarkMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditCalendar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EventRepeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LightMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mosque
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.feedback.HapticPlayer
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPlayer
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutLanguage
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutName
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.DefaultToolOrder
import com.wasimaster.wmkeyboard.core.settings.EverythingTools
import com.wasimaster.wmkeyboard.core.settings.RecommendedTools
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// The wizard's fixed pages, one composable per [OnboardingPage] entry. The
// shell in Onboarding.kt draws each page's hero (title, subtitle, icon), so
// the composables here start straight at their content. The persona, discover
// and try pages live in their own files.

@Composable
internal fun WelcomePage(
    repository: SettingsRepository,
    canRestore: Boolean,
    restored: Boolean,
    onRestored: () -> Unit,
    onReady: () -> Unit,
    onSetupChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val setup = rememberKeyboardSetup(context, onReady)
    LaunchedEffect(setup.ready) { onSetupChanged(setup.ready) }
    // Set as the user leaves for the system keyboard settings, so the wizard
    // knows to watch that screen and come back on its own.
    var awaitingEnable by rememberSaveable { mutableStateOf(false) }
    ReturnAfterEnabling(awaitingEnable) { awaitingEnable = false }
    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        SetupCard(context, setup = setup, onEnableRequested = { awaitingEnable = true })
    }
    when {
        restored && !setup.ready -> CaptionText(stringResource(R.string.onboarding_restored_info))
        !setup.ready -> CaptionText(stringResource(R.string.onboarding_welcome_required))
    }
    if (canRestore) RestoreBackupButton(repository, onRestored)
}

/**
 * Restores a backup instead of answering the wizard: one of this app's, or a
 * whole backup from another keyboard, through the same dialog an opened file
 * gets. [onRestored] runs once the result has been read and closed, so the
 * wizard does not move on under the dialog that says what came across.
 *
 * A restored list of languages is no use without its dictionaries, so the
 * missing ones are fetched, the way a language added on the wizard's own
 * page is.
 */
@Composable
private fun RestoreBackupButton(repository: SettingsRepository, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notify = rememberDownloadNotifier()
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    var imported by remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // Held until the dialog closes: it reads the file a composition later,
        // and the grant the picker hands back may not last that long (#1078).
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        picked = uri
    }
    fun letGo(uri: android.net.Uri) {
        picked = null
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        OutlinedButton(onClick = { launcher.launchPicker(context, arrayOf("*/*")) }) {
            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_restore_action))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_restore_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    picked?.let { uri ->
        ImportFileDialog(
            repository,
            uri,
            onImported = { imported = true },
            onChooseAnother = {
                letGo(uri)
                launcher.launchPicker(context, arrayOf("*/*"))
            },
            onClose = {
                letGo(uri)
                if (imported) {
                    imported = false
                    // The downloads start before the wizard moves on, since
                    // moving on can close it and cancel this scope.
                    scope.launch {
                        val languages = repository.settings.first().enabledLanguages.map { it.id }
                        startMissingLanguageData(context, languages, notify)
                        onRestored()
                    }
                }
            },
        )
    }
}

@Composable
internal fun LanguagesPage(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // What decides which rows the page holds; each switch reads its own value.
    val enabledLanguages = settings.watch { it.enabledLanguages }
    val enabledLayoutIds = settings.watch { it.enabledLayoutIds }
    val customLayouts = settings.watch { it.customLayouts }
    // The enabled set, grouped by language (deduped, in switch order); toggling
    // a layout off is how you drop one during setup, and the search below adds
    // any of the rest.
    for (language in enabledLanguages) {
        Text(
            language.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        // From the layout index, never a parse: this runs for every enabled
        // layout under every enabled language on each recomposition, and
        // parsing them here was a main-thread ANR on low-end phones.
        val layoutIds = enabledLayoutIds.filter {
            resolveLayoutLanguage(customLayouts, it).id == language.id
        }
        for (layoutId in layoutIds) {
            ListItem(
                headlineContent = { Text(resolveLayoutName(customLayouts, layoutId)) },
                trailingContent = {
                    Switch(
                        checked = layoutId in enabledLayoutIds,
                        onCheckedChange = { enable ->
                            val current = settings.value.enabledLayoutIds
                            scope.launch {
                                val next =
                                    if (enable) current + layoutId
                                    else current - layoutId
                                if (next.isNotEmpty()) {
                                    repository.setEnabledLayoutIds(next.distinct())
                                }
                            }
                        },
                    )
                },
            )
            HorizontalDivider()
        }
    }
    AddLanguageSection(repository, settings)
}

/**
 * How many not-yet-added languages the onboarding list draws at once. The
 * wizard scrolls its pages in a plain `Column`, so the whole registry cannot be
 * composed the way the lazy Settings screen composes it; the search narrows the
 * list long before the cap bites, and the cap announces itself when it does.
 */
private const val ONBOARDING_LANGUAGE_LIMIT = 30

/**
 * How many device-derived suggestions the wizard offers. Shorter than the
 * settings screen's list: this one sits above the search box on a page the user
 * is trying to get past, so it has to stay glanceable.
 */
private const val ONBOARDING_SUGGESTION_LIMIT = 4

/**
 * Adds any language in the registry, without leaving the wizard. Tapping a
 * language with one layout enables it on the spot; a language with several
 * asks which of them to enable first — Bengali alone ships three input systems
 * (Avro, Probhat, National) and most people type in exactly one of them, so
 * enabling all three unasked put two dead layouts on the 🌐 cycle. Secondary
 * suggestion sources stay in Settings → Languages, which has room for them.
 */
@Composable
private fun AddLanguageSection(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    // The language whose layout-picker dialog is open, by id — the id rather
    // than the LanguageDef so the open dialog survives rotation.
    var layoutChoice by rememberSaveable { mutableStateOf<String?>(null) }
    val enabledLangIds = settings.watch { s -> s.enabledLanguages.mapTo(HashSet()) { it.id } }
    val q = query.trim().lowercase()
    val matches = searchLanguages(q).filter { it.id !in enabledLangIds }
    val suggested = rememberSuggestedLanguages(settings, limit = ONBOARDING_SUGGESTION_LIMIT)
    // The wizard fetches rather than asking. Settings → Languages still puts
    // the question up, but a first run that stopped for a download dialog on
    // every language was three taps per language and a wall of choices before
    // the user had typed anything; the notice under this list says what the
    // page does instead, and the data can be deleted later.
    val context = LocalContext.current
    val notifyDownload = rememberDownloadNotifier()
    val fetchData: (LanguageDef) -> Unit = { language ->
        startLanguageDataDownload(context, languageData(language.id), notifyDownload)
    }
    val add: (LanguageDef) -> Unit = { language ->
        if (language.layoutIds.size > 1) {
            layoutChoice = language.id
        } else {
            addLanguage(scope, repository, settings.value, language)
            fetchData(language)
            query = ""
        }
    }

    layoutChoice?.let { langId ->
        val language = LanguageRegistry.byId(langId)
        val customLayouts = settings.watch { it.customLayouts }
        LayoutPickerDialog(
            language = language,
            layoutName = { resolveLayoutName(customLayouts, it) },
            layoutSpec = { resolveLayout(customLayouts, it) },
            onConfirm = { chosen ->
                layoutChoice = null
                query = ""
                fetchData(language)
                val enabled = settings.value.enabledLayoutIds
                scope.launch {
                    repository.setEnabledLayoutIds(
                        (enabled + chosen).distinct(),
                    )
                }
            },
            onDismiss = { layoutChoice = null },
        )
    }

    // Above the search box, because for most people this is the whole step:
    // the languages their phone is already in are the ones they came to add.
    if (suggested.isNotEmpty()) {
        Text(
            stringResource(R.string.onboarding_language_suggested_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        for (suggestion in suggested) {
            ListItem(
                headlineContent = { Text(suggestion.language.displayName) },
                supportingContent = { Text(suggestionReasonLabel(suggestion)) },
                trailingContent = {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = stringResource(
                            R.string.onboarding_language_add_desc,
                            suggestion.language.englishName,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { add(suggestion.language) },
            )
            HorizontalDivider()
        }
        CaptionText(stringResource(R.string.onboarding_language_suggested_info))
    }

    Text(
        stringResource(R.string.onboarding_language_add_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.onboarding_language_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
    for (language in matches.take(ONBOARDING_LANGUAGE_LIMIT)) {
        ListItem(
            headlineContent = { Text(language.displayName) },
            supportingContent = { Text(languageRowSubtitle(language)) },
            trailingContent = {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(
                        R.string.onboarding_language_add_desc,
                        language.englishName,
                    ),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { add(language) },
        )
        HorizontalDivider()
    }
    if (matches.isEmpty()) {
        CaptionText(
            if (q.isEmpty()) stringResource(R.string.onboarding_language_all_added)
            else stringResource(R.string.onboarding_language_no_match, query),
        )
    } else if (matches.size > ONBOARDING_LANGUAGE_LIMIT) {
        val extra = matches.size - ONBOARDING_LANGUAGE_LIMIT
        CaptionText(pluralStringResource(R.plurals.onboarding_language_more_count, extra, extra))
    }
    OnboardingNotice(stringResource(R.string.onboarding_language_auto_download_info))
}

/**
 * Asks which of a multi-layout language's layouts to enable, as it is added.
 * The default (first) layout starts checked, so Add without touching anything
 * does what adding the language always did; unchecking it and checking another
 * is the whole point — the person who types Bengali in Probhat should never
 * have Avro on their 🌐 cycle. At least one box must stay checked: a language
 * added with no layout would not exist.
 *
 * Each choice carries a miniature of its own key grid. The names alone
 * ("Avro", "Probhat", "National") mean nothing to someone who has not already
 * chosen between them, and the difference between two layouts is *visible* —
 * which is the one thing a list of checkboxes could not show.
 */
@Composable
private fun LayoutPickerDialog(
    language: LanguageDef,
    layoutName: (String) -> String,
    layoutSpec: (String) -> LayoutSpec,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(language.id) {
        mutableStateOf(setOfNotNull(language.layoutIds.firstOrNull()))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            WmIconTile(
                Icons.Outlined.Keyboard,
                OnboardingPageAccents.getValue(OnboardingPage.LANGUAGES),
            )
        },
        title = {
            Text(stringResource(R.string.onboarding_layout_picker_title, language.displayName))
        },
        text = {
            // Scrollable for the long tail: Chinese ships six input systems,
            // and a small-screen dialog has to reach all of them.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.onboarding_layout_picker_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                for (layoutId in language.layoutIds) {
                    val checked = layoutId in selected
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                if (checked) 2.dp else 1.dp,
                                if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(16.dp),
                            )
                            .background(
                                if (checked) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            )
                            .clickable {
                                selected =
                                    if (layoutId in selected) selected - layoutId
                                    else selected + layoutId
                            }
                            .padding(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                // The card is the click target; a second one on
                                // the box would double-toggle under TalkBack.
                                onCheckedChange = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(layoutName(layoutId), style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        MiniLayoutPreview(
                            layoutSpec(layoutId),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                // Filtered through layoutIds rather than passed as the set, so
                // the layouts enable in the language's shipped order however
                // the boxes were ticked.
                onClick = { onConfirm(language.layoutIds.filter { it in selected }) },
            ) { Text(stringResource(CommonR.string.common_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * Two questions and nothing else: light or dark, and which keyboard theme.
 *
 * Material You is not asked about — it is on for everyone, and the person who
 * wants flat colours finds the switch in Appearance. The two row switches that
 * used to sit here are gone for the same reason: the seeded modes already turn
 * the emoji row on in chat apps and the symbol row on in email and code
 * fields, so the shipped answer is right for nearly everybody and the wizard
 * spent a screen asking anyway.
 */
@Composable
internal fun LookPage(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // Decides what the page holds below the mode row.
    val themeMode = settings.watch { it.themeMode }
    ThemeModeChoice(themeMode) { mode ->
        val selectedId = settings.value.keyboardThemeId
        scope.launch {
            repository.setThemeMode(mode)
            // A fixed mode has one keyboard theme, so the light/dark pair has
            // to stand down — while it is on it overrides the selected id
            // outright, and every tap on the list below would do nothing.
            if (mode != ThemeMode.SYSTEM) repository.setAutoThemeEnabled(false)
            // The list below is about to stop showing the selected theme.
            // Leaving a dark keyboard selected under "Light" would be a
            // choice the user can no longer see, so it goes back to the
            // default, which follows the mode.
            val stillShown = selectedId == DEFAULT_THEME_ID ||
                themesForMode(mode).any { it.id == selectedId }
            if (!stillShown) repository.setKeyboardThemeId(DEFAULT_THEME_ID)
        }
    }
    Text(
        stringResource(R.string.onboarding_keyboard_theme_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    if (themeMode == ThemeMode.SYSTEM) {
        AutoThemeChooser(repository, settings)
        return
    }
    // Only the themes that match the choice above. Someone who has just asked
    // for a light keyboard has no use for eleven dark ones, and a shorter list
    // is a choice rather than a catalogue. The full set stays in Appearance.
    val themes = remember(themeMode) { themesForMode(themeMode) }
    ThemeChoiceList(
        themes = themes,
        selectedId = settings.watch { it.keyboardThemeId },
        onSelect = { id -> scope.launch { repository.setKeyboardThemeId(id) } },
    )
}

/**
 * The keyboard theme under "Auto": one light theme and one dark theme, chosen
 * separately, behind a tab each.
 *
 * Auto means the board follows the system, so it has two answers rather than
 * one — and the mixed list this replaced offered dark themes to someone
 * looking at a light phone with no way to say which half they were picking.
 * Picking either half turns the auto-theme pair on; it is the setting that
 * makes two chosen themes mean anything, and choosing one is the only honest
 * signal that the user wants it.
 *
 * The tab that opens is the half in effect right now, so the theme marked as
 * selected is the theme on screen.
 */
@Composable
private fun AutoThemeChooser(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()
    var darkTab by rememberSaveable { mutableStateOf(systemDark) }
    // Before the pair is on there is one selected theme, not two. Show it in
    // the half it belongs to and leave the other on the default, so turning
    // the pair on keeps what was already showing.
    val lightId = settings.watch { s ->
        if (s.autoTheme.enabled) s.autoTheme.lightThemeId else s.keyboardThemeId.inHalf(false)
    }
    val darkId = settings.watch { s ->
        if (s.autoTheme.enabled) s.autoTheme.darkThemeId else s.keyboardThemeId.inHalf(true)
    }
    TabRow(
        selectedTabIndex = if (darkTab) 1 else 0,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Tab(
            selected = !darkTab,
            onClick = { darkTab = false },
            text = { Text(stringResource(R.string.onboarding_theme_mode_light)) },
            icon = { Icon(Icons.Outlined.LightMode, contentDescription = null) },
        )
        Tab(
            selected = darkTab,
            onClick = { darkTab = true },
            text = { Text(stringResource(R.string.onboarding_theme_mode_dark)) },
            icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
        )
    }
    CaptionText(stringResource(R.string.onboarding_keyboard_theme_auto_info))
    ThemeChoiceList(
        themes = remember(darkTab) { BuiltInThemes.flattenedThemes().filter { it.dark == darkTab } },
        selectedId = if (darkTab) darkId else lightId,
        onSelect = { id ->
            scope.launch {
                if (darkTab) repository.setAutoThemeDarkId(id) else repository.setAutoThemeLightId(id)
                repository.setAutoThemeEnabled(true)
            }
        },
    )
}

/**
 * This id if it belongs to the [dark] half, and the default theme otherwise —
 * the default is the one entry that exists in both halves, since it is drawn
 * from the Material scheme.
 */
private fun String.inHalf(dark: Boolean): String =
    takeIf {
        BuiltInThemes.flattenedThemes().find { theme -> theme.id == this }?.dark == dark
    } ?: DEFAULT_THEME_ID

/**
 * The themes on offer, two to a row.
 *
 * A grid rather than the side-scrolling strip this replaced (which hid most of
 * what it held past the right edge) and rather than a single column (which
 * made twelve themes a very long page for a preview 100 dp wide). Built by
 * hand from chunks, because the wizard scrolls its pages in a plain Column and
 * a lazy grid inside one would nest two vertical scrolls.
 */
@Composable
private fun ThemeChoiceList(
    themes: List<ThemeSpec>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    // The default sits in the same grid as the rest, as its first cell.
    val cells = remember(themes) { listOf<ThemeSpec?>(null) + themes }
    for (pair in cells.chunked(2)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
        ) {
            for (theme in pair) {
                OnboardingThemeCard(
                    name = theme?.name
                        ?: stringResource(R.string.onboarding_keyboard_theme_material_you_label),
                    selected = selectedId == (theme?.id ?: DEFAULT_THEME_ID),
                    onSelect = { onSelect(theme?.id ?: DEFAULT_THEME_ID) },
                    modifier = Modifier.weight(1f),
                ) {
                    if (theme == null) {
                        // The stand-in for "no theme spec at all" takes the
                        // same shape a real preview would, so the first cell
                        // of the grid is the same size as its neighbour.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(ThemePreviewAspect)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(CommonR.string.common_default),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    } else {
                        ThemePreview(theme, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            // An odd count leaves a gap rather than a double-width card.
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Shape of a theme miniature, copied from `ThemePreview`'s own portrait
 * aspect so the default cell matches the real ones exactly.
 */
private const val ThemePreviewAspect = 1.7f

/**
 * The built-in themes worth offering under [mode]. Auto shows all of them —
 * the keyboard follows the system either way, so both halves are reachable —
 * and each fixed mode shows only the themes that match it. AMOLED counts as
 * dark: its own pitch-black theme is in that half.
 */
internal fun themesForMode(mode: ThemeMode): List<ThemeSpec> = when (mode) {
    ThemeMode.SYSTEM -> BuiltInThemes.flattenedThemes()
    ThemeMode.LIGHT -> BuiltInThemes.flattenedThemes().filter { !it.dark }
    ThemeMode.DARK, ThemeMode.AMOLED -> BuiltInThemes.flattenedThemes().filter { it.dark }
}

/** Light / dark / AMOLED, as a segmented row with a glyph on each choice. */
@Composable
private fun ThemeModeChoice(selected: ThemeMode, onChange: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                // The check mark lane costs half the width of a four-way row;
                // the icon *is* the selected state here, next to the border and
                // the fill the control already draws.
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            themeModeIcon(mode),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(themeModeLabel(mode)),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                },
            )
        }
    }
}

private fun themeModeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
    ThemeMode.AMOLED -> Icons.Outlined.Contrast
}

@StringRes
private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.onboarding_theme_mode_auto
    ThemeMode.LIGHT -> R.string.onboarding_theme_mode_light
    ThemeMode.DARK -> R.string.onboarding_theme_mode_dark
    ThemeMode.AMOLED -> R.string.onboarding_theme_mode_amoled
}

/**
 * A note about something the keyboard decides for itself, next to the setting
 * it overrides. Drawn on its own surface rather than as loose caption text, so
 * it does not read as the subtitle of the row above it.
 */
/**
 * How the wizard puts a block on the page after the answer that asked for it:
 * fade and grow, so the rows below are seen to move rather than found
 * somewhere new a frame later. Reduce motion keeps the instant swap.
 *
 * Only for blocks that appear under the user's own tap. Content that changes
 * because the page was rebuilt (a different page, a returning screen) already
 * has the transition it needs.
 */
internal fun onboardingRevealEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(snap()) else fadeIn() + expandVertically()

/** The [onboardingRevealEnter] half that plays when the answer is taken back. */
internal fun onboardingRevealExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(snap()) else fadeOut() + shrinkVertically()

@Composable
internal fun OnboardingNotice(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One theme in the grid: its miniature, its name, and a radio saying whether
 * it is the one. The radio as well as the border because the auto tabs hold
 * two of these grids, each with its own separate answer.
 */
@Composable
private fun OnboardingThemeCard(
    name: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp),
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .clickable(onClick = onSelect)
            .padding(8.dp),
    ) {
        content()
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                // Always two lines tall, so the two cards of a row end level
                // whether or not a name wraps — cheaper and safer than
                // measuring the row's intrinsic height around an aspect-ratio
                // child.
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The card is the click target; a second one on the radio would
            // double-toggle under TalkBack.
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
internal fun FeedbackPage(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The system haptic styles are played through a real view, so hand the
    // player one — without it they fall back to a generic hardware click and
    // the buzz here would not be the buzz the keys give.
    val view = LocalView.current
    ListItem(
        headlineContent = { Text(stringResource(R.string.onboarding_haptics_title)) },
        supportingContent = { Text(stringResource(R.string.onboarding_haptics_subtitle)) },
        trailingContent = {
            Switch(
                checked = settings.watch { it.haptics.enabled },
                onCheckedChange = { enable ->
                    scope.launch { repository.setHapticFeedback(enable) }
                    // Fired straight from the click rather than after the
                    // DataStore write lands, or the switch feels dead.
                    if (enable) {
                        val haptics = settings.value.haptics
                        HapticPlayer.preview(
                            context,
                            haptics.style,
                            haptics.amplitude,
                            haptics.strengthMs,
                            view,
                        )
                    }
                },
            )
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.onboarding_key_sound_title)) },
        supportingContent = { Text(stringResource(R.string.onboarding_key_sound_subtitle)) },
        trailingContent = {
            Switch(
                checked = settings.watch { it.sound.enabled },
                onCheckedChange = { enable ->
                    scope.launch { repository.setKeySound(enable) }
                    // Same bargain as the haptics switch above: turning it on
                    // plays the sound it just turned on, so nobody has to leave
                    // setup and type somewhere to find out what they chose.
                    if (enable) {
                        val sound = settings.value.sound
                        KeySoundPlayer.previewStroke(
                            context,
                            sound.style,
                            sound.volume,
                            sound.customId,
                        )
                    }
                },
            )
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.onboarding_key_popup_title)) },
        supportingContent = { Text(stringResource(R.string.onboarding_key_popup_subtitle)) },
        trailingContent = {
            Switch(
                checked = settings.watch { it.popup.enabled },
                onCheckedChange = { scope.launch { repository.setKeyPopup(it) } },
            )
        },
    )
}

@Composable
internal fun GesturesPage(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // The badge follows the persona answer rather than being baked into one
    // row's title, so the recommendation is honest: it always marks a choice
    // that matches what the quiz actually set.
    val recommended = settings.watch { recommendedSpacebarChoice(it.onboarding) }
    for (choice in SpacebarChoice.entries) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(choice.titleRes),
                        // Yields to the badge instead of splitting the row in
                        // half: "Only change the language" is wider than what
                        // an even share leaves, and the badge was the one that
                        // lost, wrapping "Recommended" onto two lines.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (choice == recommended) {
                        Text(
                            stringResource(R.string.onboarding_recommended_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            },
            supportingContent = { Text(stringResource(choice.subtitleRes)) },
            leadingContent = {
                val selected = settings.watch {
                    it.spaceShortSwipe == choice.short && it.spaceLongSwipe == choice.long
                }
                RadioButton(selected = selected, onClick = null)
            },
            modifier = Modifier.clickable {
                scope.launch {
                    repository.setSpaceShortSwipe(choice.short)
                    repository.setSpaceLongSwipe(choice.long)
                }
            },
        )
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    // Both switches below change the shape of the board itself, so each one
    // draws the board. "Emoji key instead of 🌐" is a sentence about a key
    // nobody has looked at yet; the miniature answers it before it is read.
    PreviewedSwitch(
        title = stringResource(R.string.onboarding_emoji_key_title),
        subtitle = stringResource(R.string.onboarding_emoji_key_subtitle),
        checked = settings.watch { it.globeAsEmoji },
        onCheckedChange = { scope.launch { repository.setGlobeAsEmoji(it) } },
    ) {
        MiniKeyboardPreview(
            numberRow = settings.watch { it.numberRow },
            globeAsEmoji = settings.watch { it.globeAsEmoji },
            highlight = MiniKeyHighlight.GLOBE,
        )
    }
    PreviewedSwitch(
        title = stringResource(R.string.onboarding_number_row_title),
        subtitle = stringResource(R.string.onboarding_number_row_subtitle),
        checked = settings.watch { it.numberRow },
        onCheckedChange = { scope.launch { repository.setNumberRow(it) } },
    ) {
        MiniKeyboardPreview(
            numberRow = settings.watch { it.numberRow },
            globeAsEmoji = settings.watch { it.globeAsEmoji },
            highlight = MiniKeyHighlight.NUMBER_ROW,
        )
    }
}

/** A switch row with a miniature of what it does drawn under it. */
@Composable
private fun PreviewedSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        )
        Box(modifier = Modifier.padding(horizontal = 16.dp)) { content() }
    }
}

/**
 * Per-tool first-run choices, one section per enabled tool from
 * [ToolSetupTools]. Follows the tools page so it reflects what was just
 * switched on; every option lives in the tool's settings too.
 */
@Composable
internal fun ToolSetupPage(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // Which sections the page holds; each row reads its own value.
    val calendarOn = settings.watch { ToolbarTool.CALENDAR in it.enabledTools }
    val weatherOn = settings.watch { ToolbarTool.WEATHER in it.enabledTools }
    val compassOn = settings.watch { ToolbarTool.COMPASS in it.enabledTools }
    if (calendarOn) {
        OnboardingSectionTitle(stringResource(toolTitle(ToolbarTool.CALENDAR)))
        // The two rows below are additions, not replacements, and reading them
        // as a calendar *picker* is the obvious mistake. So the Gregorian
        // calendar gets a row of its own, above them and with no control on
        // it: the thing that is always there, drawn as always there.
        ListItem(
            leadingContent = { OnboardingRowIcon(Icons.Outlined.CalendarMonth) },
            headlineContent = {
                Text(stringResource(R.string.onboarding_calendar_gregorian_title))
            },
            supportingContent = {
                Text(stringResource(R.string.onboarding_calendar_gregorian_subtitle))
            },
            trailingContent = {
                Text(
                    stringResource(R.string.onboarding_calendar_gregorian_always),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        // All three already open on what the device's region suggests — the
        // Bengali calendar in Bangladesh, era years in Japan, a Friday-Saturday
        // weekend across much of the Middle East. This page is where someone
        // sees that guess and corrects it.
        AltCalendarSetting(
            title = stringResource(R.string.onboarding_calendar_first_title),
            subtitle = stringResource(R.string.onboarding_calendar_first_subtitle),
            selected = settings.watch { it.calendarTool.altOne },
            icon = Icons.Outlined.EditCalendar,
            onChange = { scope.launch { repository.setCalendarAltOne(it) } },
        )
        AltCalendarSetting(
            title = stringResource(R.string.onboarding_calendar_second_title),
            subtitle = stringResource(R.string.onboarding_calendar_second_subtitle),
            selected = settings.watch { it.calendarTool.altTwo },
            icon = Icons.Outlined.EventRepeat,
            onChange = { scope.launch { repository.setCalendarAltTwo(it) } },
        )
        WeekendSetting(
            selected = settings.watch { it.calendarTool.weekend },
            onChange = { scope.launch { repository.setCalendarWeekend(it) } },
        )
    }
    if (weatherOn) {
        OnboardingSectionTitle(stringResource(toolTitle(ToolbarTool.WEATHER)))
        // The tool is dead without a place, so the same search-or-coordinates
        // editor the settings screen uses is right here rather than a pointer
        // to it.
        WeatherLocationSetting(repository, settings)
        ListItem(
            leadingContent = { OnboardingRowIcon(Icons.Outlined.Thermostat) },
            headlineContent = { Text(stringResource(R.string.onboarding_weather_fahrenheit_title)) },
            supportingContent = {
                Text(stringResource(R.string.onboarding_weather_fahrenheit_subtitle))
            },
            trailingContent = {
                Switch(
                    checked = settings.watch { it.weather.fahrenheit },
                    onCheckedChange = { scope.launch { repository.setWeatherFahrenheit(it) } },
                )
            },
        )
    }
    if (compassOn) {
        OnboardingSectionTitle(stringResource(toolTitle(ToolbarTool.COMPASS)))
        ListItem(
            leadingContent = { OnboardingRowIcon(Icons.Outlined.Mosque) },
            headlineContent = { Text(stringResource(R.string.onboarding_compass_qibla_title)) },
            supportingContent = {
                Text(stringResource(R.string.onboarding_compass_qibla_subtitle))
            },
            trailingContent = {
                Switch(
                    checked = settings.watch { it.sensorTools.compassQibla },
                    onCheckedChange = { scope.launch { repository.setCompassShowQibla(it) } },
                )
            },
        )
        // Grows out of the switch that asked for it — a location editor
        // appearing in one frame reads as the page having jumped.
        val reduceMotion = settings.watch { it.reduceMotion }
        AnimatedVisibility(
            visible = settings.watch { it.sensorTools.compassQibla },
            enter = onboardingRevealEnter(reduceMotion),
            exit = onboardingRevealExit(reduceMotion),
        ) {
            Column {
                Text(
                    stringResource(R.string.onboarding_compass_qibla_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                // Same place, so only offer the editor here when the weather
                // section above isn't already showing one.
                if (!weatherOn) {
                    WeatherLocationSetting(repository, settings)
                }
            }
        }
    }
}

/**
 * The leading glyph of a wizard row. Tinted with the primary colour rather
 * than drawn on an accent tile: the wizard's rows sit in plain lists, and a
 * column of tiles would compete with the page's own hero tile.
 */
@Composable
internal fun OnboardingRowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun OnboardingSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * One of the two tool presets. The pair splits the width between them so it
 * reads as a choice rather than as two links lost above the list, and each
 * label may wrap to a second line rather than being cut off — which is why the
 * row that holds them measures to the taller of the two.
 */
@Composable
private fun ToolPresetButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ToolsPage(
    repository: SettingsRepository,
    settings: LiveSettings,
    seeded: Boolean,
    onSeeded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playServices = remember { PlayServices.available }
    // The set this persona would have started with, whatever page landed it.
    val persona = settings.watch { it.onboarding }
    val starter = remember(persona, playServices) {
        starterTools(persona, playServices, ::isSupportedTool).orEmpty()
    }
    // First visit swaps the enable-everything default for the persona's
    // starter set — but only over an untouched default, so a user who
    // already toggled tools (or reinstalled with settings intact) keeps
    // their selection. The persona page usually got here first; its answer
    // marks the seed done.
    LaunchedEffect(Unit) {
        if (!seeded) {
            onSeeded()
            if (settings.value.enabledTools.toSet() == EverythingTools.toSet()) {
                repository.setEnabledTools(starter.ifEmpty { RecommendedTools })
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolPresetButton(
            icon = Icons.Outlined.AutoAwesome,
            label = stringResource(R.string.onboarding_tools_recommended_action),
            modifier = Modifier.weight(1f),
        ) {
            scope.launch { repository.setEnabledTools(starter.ifEmpty { RecommendedTools }) }
        }
        ToolPresetButton(
            icon = Icons.Outlined.SelectAll,
            label = stringResource(R.string.onboarding_tools_everything_action),
            modifier = Modifier.weight(1f),
        ) { scope.launch { repository.setEnabledTools(EverythingTools) } }
    }
    // Most-used-by-most-people first — same order the toolbox itself opens with.
    for (tool in DefaultToolOrder.filter(::isSupportedTool)) {
        ListItem(
            leadingContent = {
                SlotIcon(
                    IconSlots.forTool(tool),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = { Text(stringResource(toolTitle(tool))) },
            supportingContent = { Text(stringResource(toolDescription(tool))) },
            trailingContent = {
                Switch(
                    checked = settings.watch { tool in it.enabledTools },
                    onCheckedChange = { scope.launch { repository.setToolEnabled(tool, it) } },
                )
            },
        )
    }
}

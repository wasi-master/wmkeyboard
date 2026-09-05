package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import android.os.Build
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

// ---- languages ----

/** [ReturnAnchor] key for the Languages screen's "Your languages" list. */
private const val LANGUAGES_ANCHOR = "languages"
@Composable
internal fun LanguageSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Same prompt the add-language list shows, so the shortlist below cannot be
    // the one path that downloads a language's data without asking.
    val dataPrompt = rememberLanguageDataPrompt()
    // The language this screen was last left from. Someone who went into Bangla
    // to fetch its dictionary comes back to Bangla, not to the top of a list of
    // eleven languages they then have to find it in again.
    val returnTo = remember { ReturnAnchor.take(LANGUAGES_ANCHOR) }
    // "Your languages" is the enabled set (deduped, in switch order); each opens
    // its detail. Adding one is a search over the whole registry.
    SettingsGroup(
        stringResource(R.string.langemoji_lang_your_languages_title),
        info = stringResource(R.string.langemoji_lang_intro_body),
    ) {
        for (language in settings.enabledLanguages) {
            item {
                val names = settings.enabledLayoutIds
                    .filter { resolveLayout(settings.customLayouts, it).language().id == language.id }
                    .joinToString { resolveLayout(settings.customLayouts, it).name }
                ScrollAnchor(language.id == returnTo) {
                    NavRow(
                        language.displayName,
                        subtitle = names.ifBlank { null },
                        route = "language/${language.id}",
                    ) {
                        ReturnAnchor.arm(LANGUAGES_ANCHOR, language.id)
                        onNavigate("language/${language.id}")
                    }
                }
            }
        }
        item {
            NavRow(
                R.string.langemoji_lang_add_title,
                subtitle = pluralStringResource(
                    R.plurals.langemoji_lang_add_subtitle,
                    LanguageRegistry.all.size,
                    LanguageRegistry.all.size,
                ),
                route = "add_language",
            ) { onNavigate("add_language") }
        }
    }
    // A short device-derived shortlist, so the common case never has to go
    // through the full registry. The reasoning lives in LanguageSuggestions.
    val suggested = rememberSuggestedLanguages(settings, limit = LANGUAGE_SCREEN_SUGGESTIONS)
    if (suggested.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.langemoji_lang_suggested_title),
            info = stringResource(R.string.langemoji_lang_suggested_source_body),
        ) {
            for (suggestion in suggested) {
                item {
                    NavRow(
                        suggestion.language.displayName,
                        subtitle = suggestionReasonLabel(suggestion),
                    ) {
                        dataPrompt.ask(suggestion.language) {
                            addLanguage(scope, repository, settings, suggestion.language)
                            // It is about to be one of "your languages", and
                            // coming back is where the user will look for it.
                            ReturnAnchor.arm(LANGUAGES_ANCHOR, suggestion.language.id)
                            onNavigate("language/${suggestion.language.id}")
                        }
                    }
                }
            }
        }
    }
    // The master switch over every download the keyboard would otherwise start
    // on its own. Here rather than under Emoji because it covers prediction
    // data too, and this is the screen where languages arrive.
    SettingsGroup(stringResource(R.string.langemoji_lang_data_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_lang_auto_download_title,
                stringResource(R.string.langemoji_lang_auto_download_subtitle),
                settings.autoDownloadLanguageData,
                info = stringResource(R.string.langemoji_lang_auto_download_info),
                default = SettingsDefaults.autoDownloadLanguageData,
            ) { scope.launch { repository.setAutoDownloadLanguageData(it) } }
        }
        item {
            // The metered-download confirmation moved to the data-saver
            // screen, where it is one answer among three rather than a switch,
            // and where it now covers the model downloads too. This row stays
            // as the signpost for anyone who came here looking for it.
            NavRow(
                R.string.langemoji_lang_metered_title,
                stringResource(R.string.langemoji_lang_metered_subtitle),
                onClick = { onNavigate("datasaver") },
            )
        }
        item {
            ToggleSetting(
                R.string.langemoji_lang_autopair_title,
                stringResource(R.string.langemoji_lang_autopair_subtitle),
                settings.autoPairRomanized,
                info = stringResource(R.string.langemoji_lang_autopair_info),
                default = SettingsDefaults.autoPairRomanized,
            ) { scope.launch { repository.setAutoPairRomanized(it) } }
        }
        if (settings.perAppLanguage.layoutByPackage.isNotEmpty()) {
            item {
                ActionRow(
                    title = R.string.langemoji_lang_forget_apps_title,
                    subtitle = pluralStringResource(
                        R.plurals.langemoji_lang_forget_apps_subtitle,
                        settings.perAppLanguage.layoutByPackage.size,
                        settings.perAppLanguage.layoutByPackage.size,
                    ),
                    action = stringResource(CommonR.string.common_clear),
                    confirm = stringResource(R.string.langemoji_lang_forget_apps_confirm),
                    lock = AppLockTargets["action_forget_app_languages"],
                ) { scope.launch { repository.clearPerAppLayouts() } }
            }
        }
    }
    // Reorder the switch ring (spacebar swipe / 🌐 cycle) across every enabled
    // layout, not just languages, so AZERTY and QWERTY keep distinct slots.
    if (settings.enabledLayoutIds.size > 1) {
        // Two layouts of one language, and two languages on one layout, both
        // read the same by layout name alone, so each row carries its language.
        val switchOrderItem = stringResource(R.string.langemoji_lang_switch_order_item_label)
        SettingsGroup {
            item {
                ReorderSetting(
                    stringResource(R.string.langemoji_lang_switch_order_title),
                    stringResource(R.string.langemoji_lang_switch_order_dialog_title),
                    settings.enabledLayoutIds,
                    label = {
                        val layout = resolveLayout(settings.customLayouts, it)
                        val language = layout.language().displayName
                        // A layout named after its own language would say it twice.
                        if (layout.name == language) language
                        else switchOrderItem.format(language, layout.name)
                    },
                    onReordered = { scope.launch { repository.setEnabledLayoutIds(it) } },
                )
            }
        }
    }
    // Custom layouts get their own group after the languages: they are the
    // user's own grids (edited on the Key layouts screen), not a language to add.
    // Only the user's own grids. An override of a shipped layout — built-in or
    // JSON asset — is an edit of that layout, not a layout of their own, and
    // listing it here would show the same name twice: once as the language's
    // layout above, once as if they had made it.
    // Secondary layouts are left out too: they are not languages, cannot be
    // switched on, and are reached from a key or the Custom layout tool.
    val customs = settings.customLayouts
        .filter {
            !it.secondary &&
                com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.byId(it.id) == null &&
                com.wasimaster.wmkeyboard.core.layout.AssetLayouts.byId(it.id) == null
        }
        .sortedBy { it.name.lowercase() }
    // Turning a layout on is gated on it validating; switching one off never is,
    // or a layout broken while enabled would be impossible to put away.
    val enableGate = rememberLayoutEnableGate(settings)
    SettingsGroup(stringResource(R.string.langemoji_lang_your_layouts_title)) {
        for (layout in customs) {
            item {
                // An installed layout arrives switched off and this switch is
                // what finishes the install, so its addon's Use button lands
                // here — on the layout's own row, not on the group.
                HighlightableItem(layout.id) {
                    ToggleSetting(
                        layout.name,
                        stringResource(
                            R.string.langemoji_lang_custom_layout_subtitle,
                            baseModeTitle(layout),
                        ),
                        layout.id in settings.enabledLayoutIds,
                        default = layout.id in SettingsDefaults.enabledLayoutIds,
                    ) { enable ->
                        fun write() {
                            scope.launch {
                                val next =
                                    if (enable) settings.enabledLayoutIds + layout.id
                                    else settings.enabledLayoutIds - layout.id
                                if (next.isNotEmpty()) {
                                    repository.setEnabledLayoutIds(next.distinct())
                                }
                            }
                        }
                        if (enable) enableGate(layout.id) { write() } else write()
                    }
                }
            }
        }
        item {
            NavRow(
                R.string.langemoji_lang_keymaps_title,
                subtitle = if (customs.isEmpty()) {
                    stringResource(R.string.langemoji_lang_keymaps_empty_subtitle)
                } else {
                    stringResource(R.string.langemoji_lang_keymaps_subtitle)
                },
                route = "keymaps",
            ) { onNavigate("keymaps") }
        }
        item { AddonStoreRow(AddonType.Layout, onNavigate) }
    }
    SettingsGroup(stringResource(R.string.langemoji_lang_per_app_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_lang_per_app_toggle_title,
                stringResource(R.string.langemoji_lang_per_app_toggle_subtitle),
                settings.perAppLanguage.enabled,
                info = stringResource(R.string.langemoji_lang_per_app_toggle_info),
                default = SettingsDefaults.perAppLanguage.enabled,
            ) { scope.launch { repository.setRememberLayoutPerApp(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_lang_system_switcher_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_lang_os_switcher_title,
                stringResource(R.string.langemoji_lang_os_switcher_subtitle),
                settings.osLanguageSwitcher,
                info = stringResource(R.string.langemoji_lang_os_switcher_info),
                default = SettingsDefaults.osLanguageSwitcher,
            ) { scope.launch { repository.setOsLanguageSwitcher(it) } }
        }
        if (settings.osLanguageSwitcher) {
            item {
                ToggleSetting(
                    R.string.langemoji_lang_app_name_first_title,
                    stringResource(R.string.langemoji_lang_app_name_first_subtitle),
                    settings.subtypeAppNameFirst,
                    info = stringResource(R.string.langemoji_lang_app_name_first_info),
                    default = SettingsDefaults.subtypeAppNameFirst,
                ) { scope.launch { repository.setSubtypeAppNameFirst(it) } }
            }
            item {
                NavRow(
                    R.string.langemoji_lang_subtype_enabler_title,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        stringResource(R.string.langemoji_lang_subtype_enabler_subtitle)
                    } else {
                        stringResource(R.string.langemoji_lang_subtype_enabler_legacy_subtitle)
                    },
                ) { openSubtypeEnabler(context) }
            }
        }
    }
    // Conjunct-aware backspace used to live here as one switch across every
    // cluster-forming script at once. It is per language now, on each language's
    // own screen, next to that language's other options — see
    // [ConjunctBackspaceSetting].
}

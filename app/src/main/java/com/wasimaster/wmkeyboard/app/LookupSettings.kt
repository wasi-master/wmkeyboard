package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.AnkiMode
import com.wasimaster.wmkeyboard.core.settings.LookupCardStyle
import com.wasimaster.wmkeyboard.core.settings.LookupSettings
import com.wasimaster.wmkeyboard.core.settings.LookupWebSites
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The Look up card's settings (#765), on the Dictionary tool's page: the card
 * answers from the tool's sources, so they live beside them. How a tapped
 * word is reached (the floating button's Look up action) is set on the
 * floating button's own page, which the last row opens.
 */
@Composable
internal fun LookupSettingsGroup(repository: SettingsRepository, settings: LiveSettings, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsGroup(
        stringResource(R.string.lookup_settings_group),
        info = stringResource(R.string.lookup_settings_info),
        level = SettingLevel.STANDARD,
    ) {
        item {
            ToggleSetting(
                R.string.lookup_settings_selection_title,
                stringResource(R.string.lookup_settings_selection_subtitle),
                settings.watch { it.lookup.selectionMenu },
                default = SettingsDefaults.lookup.selectionMenu,
            ) { on ->
                LookupSelectionMenu.apply(context, on)
                scope.launch { repository.updateLookup { it.copy(selectionMenu = on) } }
            }
        }
        if (ChannelFeatures.SCREEN_CONTEXT) {
            item {
                NavRow(
                    R.string.lookup_settings_tap_title,
                    stringResource(R.string.lookup_settings_tap_subtitle),
                    route = FloatingButtonRoute,
                ) { onNavigate(FloatingButtonRoute) }
            }
            item {
                ChoiceSetting(
                    R.string.lookup_settings_style_title,
                    subtitle = stringResource(R.string.lookup_settings_style_subtitle),
                    options = listOf(
                        LookupCardStyle.SHEET to stringResource(R.string.lookup_settings_style_sheet),
                        LookupCardStyle.POPUP to stringResource(R.string.lookup_settings_style_popup),
                    ),
                    selected = settings.watch { it.lookup.cardStyle },
                    default = SettingsDefaults.lookup.cardStyle,
                ) { style -> scope.launch { repository.updateLookup { it.copy(cardStyle = style) } } }
            }
        }
        // Dictionary sites with no API, opened on the word from the Dictionary's
        // Open in menu (#1150, #1161). Brand names, so not resources.
        for (site in LookupWebSites.ALL) {
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    title = site.name,
                    subtitle = stringResource(R.string.lookup_settings_site_subtitle, site.url.substringAfter("://").substringBefore('/')),
                    checked = settings.watch { site.id in it.lookup.webSites },
                ) { on ->
                    scope.launch {
                        repository.updateLookup { l -> l.copy(webSites = if (on) l.webSites + site.id else l.webSites - site.id) }
                    }
                }
            }
        }
        item(level = SettingLevel.STANDARD) {
            TextFieldSetting(
                label = stringResource(R.string.lookup_settings_site_custom_title),
                value = settings.watch { it.lookup.customSite },
                hint = stringResource(R.string.lookup_settings_site_custom_hint),
                default = "",
            ) { url -> repository.updateLookup { it.copy(customSite = url) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                R.string.lookup_settings_anki_title,
                subtitle = stringResource(R.string.lookup_settings_anki_subtitle),
                options = listOf(
                    AnkiMode.AUTO to stringResource(R.string.lookup_settings_anki_auto),
                    AnkiMode.DIRECT to stringResource(R.string.lookup_settings_anki_direct),
                    AnkiMode.EDITOR to stringResource(R.string.lookup_settings_anki_editor),
                ),
                selected = settings.watch { it.lookup.ankiMode },
                default = SettingsDefaults.lookup.ankiMode,
                detail = { mode ->
                    ChoiceDetail(
                        stringResource(
                            when (mode) {
                                AnkiMode.AUTO -> R.string.lookup_settings_anki_auto_desc
                                AnkiMode.DIRECT -> R.string.lookup_settings_anki_direct_desc
                                AnkiMode.EDITOR -> R.string.lookup_settings_anki_editor_desc
                            },
                        ),
                    )
                },
            ) { mode -> scope.launch { repository.updateLookup { it.copy(ankiMode = mode) } } }
        }
        item(level = SettingLevel.ADVANCED) {
            TextFieldSetting(
                label = stringResource(R.string.lookup_settings_deck_title),
                value = settings.watch { it.lookup.ankiDeck },
                hint = stringResource(R.string.lookup_settings_deck_hint),
                default = LookupSettings.DEFAULT_ANKI_DECK,
            ) { deck -> repository.updateLookup { it.copy(ankiDeck = deck) } }
        }
    }
}

/**
 * "Look up" in other apps' text selection menus is an activity alias, so
 * switching the setting off takes it out of every menu, rather than leaving
 * an entry that does nothing.
 */
internal object LookupSelectionMenu {
    private const val ALIAS = "com.wasimaster.wmkeyboard.app.LookupSelectionMenu"

    fun apply(context: Context, enabled: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, ALIAS),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

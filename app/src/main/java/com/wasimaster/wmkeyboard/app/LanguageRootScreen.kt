package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
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
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith

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
    // The language this screen was last left from. Someone who went into Bangla
    // to fetch its dictionary comes back to Bangla, not to the top of a list of
    // eleven languages they then have to find it in again.
    val returnTo = remember { ReturnAnchor.take(LANGUAGES_ANCHOR) }
    // "Your languages" is the enabled set (deduped, in switch order); each opens
    // its detail. Adding one is a search over the whole registry.
    // The pencil on the heading swaps the list for the same list with drag
    // handles: the switch ring (spacebar swipe / 🌐 cycle) is every enabled
    // layout, not just each language, so AZERTY and QWERTY keep distinct slots.
    var reordering by rememberSaveable { mutableStateOf(false) }
    val switchOrderItem = stringResource(R.string.langemoji_lang_switch_order_item_label)
    val layoutLabel: (String) -> String = {
        val layout = resolveLayout(settings.customLayouts, it)
        val language = layout.language().displayName
        // A layout named after its own language would say it twice.
        if (layout.name == language) language else switchOrderItem.format(language, layout.name)
    }
    // The two states of the list are one card stack growing or shrinking into
    // the other, not a swap between frames: the heading is identical in both,
    // so all the eye follows is the rows changing shape under it.
    AnimatedContent(
        targetState = reordering,
        transitionSpec = {
            (fadeIn(tween(150, delayMillis = 60)) + scaleIn(tween(200), initialScale = 0.97f))
                .togetherWith(fadeOut(tween(90)))
                .using(SizeTransform(clip = false))
        },
        label = "your-languages",
    ) { editing ->
    SettingsGroup(
        stringResource(R.string.langemoji_lang_your_languages_title),
        info = stringResource(R.string.langemoji_lang_intro_body),
        action = if (settings.enabledLayoutIds.size > 1) {
            {
            IconButton(onClick = { reordering = !reordering }) {
                Icon(
                    if (reordering) Icons.Outlined.Check else Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.langemoji_lang_switch_order_title),
                )
            }
            }
        } else null,
    ) {
        if (editing) {
            item {
                ReorderableColumn(
                    settings.enabledLayoutIds,
                    label = layoutLabel,
                    onReorder = { scope.launch { repository.setEnabledLayoutIds(it) } },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    // The same pencil is the way out of the list: a language
                    // added by mistake is dropped here rather than through its
                    // own screen. The bin takes the layout it sits on, so a
                    // language with two of them loses one and stays; losing its
                    // last one is what removes the language. The column itself
                    // refuses the last row, so the keyboard always has a layout.
                    onDelete = { layoutId ->
                        scope.launch {
                            val next = settings.enabledLayoutIds - layoutId
                            if (next.isNotEmpty()) repository.setEnabledLayoutIds(next)
                        }
                    },
                )
            }
        }
        for (language in settings.enabledLanguages) {
            if (editing) break
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

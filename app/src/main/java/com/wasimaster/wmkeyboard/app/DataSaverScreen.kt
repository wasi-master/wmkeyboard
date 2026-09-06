package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.DataSaverTrigger
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredPolicy
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.backgroundPolicies
import kotlinx.coroutines.launch

/**
 * Settings › Data saver: what the keyboard may fetch while the connection
 * costs money.
 *
 * The two groups are the design. The first is work the keyboard starts on its
 * own, where there is nobody to ask, so each row is on or off. The second is
 * work the user started — a panel opened, a download pressed, an action run —
 * where a third answer exists and is usually the right one: let it happen, but
 * only after saying so.
 */
@Composable
internal fun DataSaverSettingsScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val ds = settings.dataSaver

    /** Every row in the second group has this shape. */
    @Composable
    fun policyRow(
        @StringRes title: Int,
        @StringRes subtitle: Int,
        selected: MeteredPolicy,
        default: MeteredPolicy,
        background: Boolean = false,
        onChange: (MeteredPolicy) -> Unit,
    ) {
        val options = if (background) backgroundPolicies else MeteredPolicy.entries
        ChoiceSetting(
            title,
            subtitle = stringResource(subtitle),
            options = options.map { it to stringResource(it.labelRes) },
            // A stored "ask" on a background row cannot be shown as itself, so
            // it shows as what it actually does. Only reachable through an
            // imported backup or a downgrade; see `stopsBackgroundWork`.
            selected = if (background && selected == MeteredPolicy.ASK) {
                MeteredPolicy.BLOCK
            } else {
                selected
            },
            default = default,
            detail = { policy -> ChoiceDetail(stringResource(meteredPolicyDescRes(policy))) },
            onChange = onChange,
        )
    }

    SettingsGroup(stringResource(R.string.datasaver_when_group)) {
        item {
            ToggleSetting(
                R.string.datasaver_manual_title,
                stringResource(R.string.datasaver_manual_subtitle),
                ds.manual,
                info = stringResource(R.string.datasaver_manual_info),
                default = SettingsDefaults.dataSaver.manual,
            ) { scope.launch { repository.setDataSaverManual(it) } }
        }
        item {
            ChoiceSetting(
                R.string.datasaver_trigger_title,
                subtitle = stringResource(R.string.datasaver_trigger_subtitle),
                info = stringResource(R.string.datasaver_trigger_info),
                options = DataSaverTrigger.entries.map { it to stringResource(it.labelRes) },
                selected = ds.trigger,
                default = SettingsDefaults.dataSaver.trigger,
            ) { scope.launch { repository.setDataSaverTrigger(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.datasaver_background_group)) {
        item {
            policyRow(
                R.string.datasaver_link_previews_title,
                R.string.datasaver_link_previews_subtitle,
                ds.linkPreviews,
                SettingsDefaults.dataSaver.linkPreviews,
                background = true,
            ) { scope.launch { repository.setDataSaverLinkPreviews(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_dictionary_title,
                R.string.datasaver_dictionary_subtitle,
                ds.dictionaryLookup,
                SettingsDefaults.dataSaver.dictionaryLookup,
                background = true,
            ) { scope.launch { repository.setDataSaverDictionaryLookup(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_photos_title,
                R.string.datasaver_photos_subtitle,
                ds.photoBackgrounds,
                SettingsDefaults.dataSaver.photoBackgrounds,
                background = true,
            ) { scope.launch { repository.setDataSaverPhotoBackgrounds(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_weather_title,
                R.string.datasaver_weather_subtitle,
                ds.weatherChip,
                SettingsDefaults.dataSaver.weatherChip,
                background = true,
            ) { scope.launch { repository.setDataSaverWeatherChip(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_rates_title,
                R.string.datasaver_rates_subtitle,
                ds.currencyRates,
                SettingsDefaults.dataSaver.currencyRates,
                background = true,
            ) { scope.launch { repository.setDataSaverCurrencyRates(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_addons_title,
                R.string.datasaver_addons_subtitle,
                ds.addonRefresh,
                SettingsDefaults.dataSaver.addonRefresh,
                background = true,
            ) { scope.launch { repository.setDataSaverAddonRefresh(it) } }
        }
    }

    SettingsGroup(
        stringResource(R.string.datasaver_ondemand_group),
        info = stringResource(R.string.datasaver_info),
    ) {
        item {
            policyRow(
                R.string.datasaver_media_title,
                R.string.datasaver_media_subtitle,
                ds.mediaSearch,
                SettingsDefaults.dataSaver.mediaSearch,
            ) { scope.launch { repository.setDataSaverMediaSearch(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_search_title,
                R.string.datasaver_search_subtitle,
                ds.webSearch,
                SettingsDefaults.dataSaver.webSearch,
            ) { scope.launch { repository.setDataSaverWebSearch(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_animated_emoji_title,
                R.string.datasaver_animated_emoji_subtitle,
                ds.animatedEmoji,
                SettingsDefaults.dataSaver.animatedEmoji,
            ) { scope.launch { repository.setDataSaverAnimatedEmoji(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_downloads_title,
                R.string.datasaver_downloads_subtitle,
                ds.downloads,
                SettingsDefaults.dataSaver.downloads,
            ) { scope.launch { repository.setDataSaverDownloads(it) } }
        }
        item {
            policyRow(
                R.string.datasaver_ai_title,
                R.string.datasaver_ai_subtitle,
                ds.cloudAi,
                SettingsDefaults.dataSaver.cloudAi,
            ) { scope.launch { repository.setDataSaverCloudAi(it) } }
        }
    }
}

/**
 * What each answer means for one kind of fetch, one line, for the picker
 * sheet. "Turn off" and "Ask each time" are short enough to read as vague:
 * the line says what the panel actually does.
 */
private fun meteredPolicyDescRes(policy: MeteredPolicy): Int = when (policy) {
    MeteredPolicy.ALLOW -> R.string.datasaver_policy_allow_desc
    MeteredPolicy.ASK -> R.string.datasaver_policy_ask_desc
    MeteredPolicy.BLOCK -> R.string.datasaver_policy_block_desc
}

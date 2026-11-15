package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.music.MusicCardStyle
import com.wasimaster.wmkeyboard.core.music.MusicClip
import com.wasimaster.wmkeyboard.core.music.MusicHistory
import com.wasimaster.wmkeyboard.core.music.MusicLink
import com.wasimaster.wmkeyboard.core.music.MusicTextFormat
import com.wasimaster.wmkeyboard.core.music.MusicTextFormats
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Tools → Music ID: who is asked (the provider list), what Insert types,
 * what the Picture chip makes, how long the microphone listens, and the
 * history.
 */
@Composable
internal fun MusicToolSettings(repository: SettingsRepository, settings: LiveSettings, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val defaults = SettingsDefaults.music

    ProvidersGroup(
        repository,
        settings,
        ProviderTool.MUSIC,
        titleRes = R.string.music_providers_title,
        info = stringResource(R.string.music_providers_info),
    )

    SettingsGroup(stringResource(R.string.music_insert_group)) {
        item {
            ChoiceSetting(
                title = R.string.music_text_format_title,
                subtitle = stringResource(R.string.music_text_format_subtitle),
                options = MusicTextFormat.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.music.textFormat },
                default = defaults.textFormat,
                info = stringResource(R.string.music_text_format_info),
            ) { scope.launch { repository.setMusicTextFormat(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.music_card_style_title,
                subtitle = stringResource(R.string.music_card_style_subtitle),
                options = MusicCardStyle.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.music.cardStyle },
                default = defaults.cardStyle,
                info = stringResource(R.string.music_card_style_info),
            ) { scope.launch { repository.setMusicCardStyle(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            val none = stringResource(R.string.music_open_link_first)
            ChoiceSetting(
                title = R.string.music_open_link_title,
                subtitle = stringResource(R.string.music_open_link_subtitle),
                options = listOf<Pair<MusicLink?, String>>(null to none) + MusicLink.entries.map { it to it.label },
                selected = settings.watch { it.music.openLink },
                default = defaults.openLink,
                info = stringResource(R.string.music_open_link_info),
            ) { scope.launch { repository.setMusicOpenLink(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.music_listening_group), level = SettingLevel.STANDARD) {
        item(level = SettingLevel.ADVANCED) {
            val seconds = stringResource(R.string.music_clip_seconds_value)
            SliderSetting(
                R.string.music_clip_seconds_title,
                stringResource(R.string.music_clip_seconds_subtitle),
                value = settings.watch { it.music.clipSeconds }.toFloat(),
                range = MusicClip.MIN_SECONDS.toFloat()..MusicClip.MAX_SECONDS.toFloat(),
                display = { seconds.format(it.roundToInt()) },
                default = defaults.clipSeconds.toFloat(),
                info = stringResource(R.string.music_clip_seconds_info),
            ) { scope.launch { repository.setMusicClipSeconds(it.roundToInt()) } }
        }
        item {
            ToggleSetting(
                R.string.music_now_playing_first_title,
                stringResource(R.string.music_now_playing_first_subtitle),
                settings.watch { it.music.nowPlayingFirst },
                default = defaults.nowPlayingFirst,
                info = stringResource(R.string.music_now_playing_first_info),
            ) { scope.launch { repository.setMusicNowPlayingFirst(it) } }
        }
    }

    val history by MusicHistory.get(context).state.collectAsState()
    SettingsGroup(stringResource(R.string.music_history_group)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.music_keep_history_title,
                stringResource(R.string.music_keep_history_subtitle),
                settings.watch { it.music.keepHistory },
                default = defaults.keepHistory,
                info = stringResource(R.string.music_keep_history_info),
            ) { scope.launch { repository.setMusicKeepHistory(it) } }
        }
        item {
            NavRow(
                R.string.music_history_open_title,
                stringResource(R.string.music_history_open_subtitle),
                value = pluralStringResource(R.plurals.music_history_count, history.entries.size, history.entries.size),
                route = MUSIC_HISTORY_ROUTE,
            ) { onNavigate(MUSIC_HISTORY_ROUTE) }
        }
    }
}

/** Settings → Tools → Music ID → History: every song recognised, newest first, with the text each would type. */
@Composable
internal fun MusicHistoryScreen(settings: LiveSettings) {
    val context = LocalContext.current
    val history = remember { MusicHistory.get(context) }
    val data by history.state.collectAsState()
    val format = settings.watch { it.music.textFormat }
    val link = settings.watch { it.music.openLink }
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (data.entries.isEmpty()) {
            Text(
                stringResource(R.string.music_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        SettingsGroup {
            data.entries.forEach { entry ->
                item {
                    ListItem(
                        headlineContent = { Text(MusicTextFormats.render(entry.match, format, link).lineSequence().first()) },
                        supportingContent = {
                            Text(
                                listOf(entry.match.album, entry.match.year, time.format(Date(entry.at))).filter { it.isNotBlank() }.joinToString(" · "),
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { history.remove(entry.id) }) { Text(stringResource(CommonR.string.common_delete)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(visible = data.entries.isNotEmpty()) {
                NavRow(R.string.music_history_clear_title, stringResource(R.string.music_history_clear_subtitle)) { history.clear() }
            }
        }
    }
}

internal const val MUSIC_HISTORY_ROUTE = "music/history"

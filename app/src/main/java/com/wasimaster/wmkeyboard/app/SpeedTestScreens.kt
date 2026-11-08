package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.speedtest.LibreSpeedServers
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardLayout
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSize
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardStyle
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestMath
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedUnit
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The speed test tool's page: how a run goes, how its card is drawn, and
 * the servers it may use. The card rows are the same choices the panel's
 * chips make, kept here so they can be found by search and reset.
 */
@Composable
internal fun SpeedTestToolSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val secondsFormat = stringResource(R.string.values_seconds)
    val numberFormat = stringResource(R.string.values_number)

    SettingsGroup(
        stringResource(R.string.tooldetail_speed_run_group),
        info = stringResource(R.string.tooldetail_speed_run_info),
    ) {
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.tooldetail_speed_duration_title,
                subtitle = stringResource(R.string.tooldetail_speed_duration_subtitle),
                value = settings.watch { it.speedTest.duration }.toFloat(),
                range = 5f..30f,
                display = { secondsFormat.format(it.roundToInt()) },
                default = SettingsDefaults.speedTest.duration.toFloat(),
            ) { scope.launch { repository.setSpeedTestDuration(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.tooldetail_speed_connections_title,
                subtitle = stringResource(R.string.tooldetail_speed_connections_subtitle),
                value = settings.watch { it.speedTest.connections }.toFloat(),
                range = 1f..8f,
                steps = wholeSteps(1f..8f),
                display = { numberFormat.format(it.roundToInt()) },
                default = SettingsDefaults.speedTest.connections.toFloat(),
            ) { scope.launch { repository.setSpeedTestConnections(it.roundToInt()) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.tooldetail_speed_upload_title,
                stringResource(R.string.tooldetail_speed_upload_subtitle),
                settings.watch { it.speedTest.upload },
                default = SettingsDefaults.speedTest.upload,
            ) { scope.launch { repository.setSpeedTestUpload(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_speed_unit_title,
                options = SpeedUnit.entries.map { it to SpeedTestMath.unitLabel(it) },
                selected = settings.watch { it.speedTest.unit },
                default = SettingsDefaults.speedTest.unit,
            ) { scope.launch { repository.setSpeedTestUnit(it) } }
        }
    }

    SettingsGroup(
        stringResource(R.string.tooldetail_speed_card_group),
        info = stringResource(R.string.tooldetail_speed_card_info),
        level = SettingLevel.STANDARD,
    ) {
        item {
            ChoiceSetting(
                R.string.tooldetail_speed_card_style_title,
                options = SpeedCardStyle.entries.map { it to stringResource(speedStyleLabel(it)) },
                selected = settings.watch { it.speedTest.cardStyle },
                default = SettingsDefaults.speedTest.cardStyle,
            ) { scope.launch { repository.setSpeedTestCardStyle(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_speed_card_size_title,
                options = SpeedCardSize.entries.map { it to stringResource(speedSizeLabel(it)) },
                selected = settings.watch { it.speedTest.cardSize },
                default = SettingsDefaults.speedTest.cardSize,
                detail = { size -> ChoiceDetail("${size.width} × ${size.height}") },
            ) { scope.launch { repository.setSpeedTestCardSize(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_speed_card_layout_title,
                options = SpeedCardLayout.entries.map { it to stringResource(speedLayoutLabel(it)) },
                selected = settings.watch { it.speedTest.cardLayout },
                default = SettingsDefaults.speedTest.cardLayout,
            ) { scope.launch { repository.setSpeedTestCardLayout(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_speed_send_mode_title,
                subtitle = stringResource(R.string.tooldetail_speed_send_mode_subtitle),
                options = listOf(
                    MediaSendMode.IMAGE to stringResource(R.string.tooldetail_speed_send_mode_image),
                    MediaSendMode.STICKER to stringResource(R.string.tooldetail_speed_send_mode_sticker),
                ),
                selected = settings.watch { it.speedTest.sendMode },
                default = SettingsDefaults.speedTest.sendMode,
            ) { scope.launch { repository.setSpeedTestSendMode(it) } }
        }
        item {
            ToggleSetting(
                R.string.tooldetail_speed_save_gallery_title,
                stringResource(R.string.tooldetail_speed_save_gallery_subtitle),
                settings.watch { it.speedTest.saveToGallery },
                default = SettingsDefaults.speedTest.saveToGallery,
            ) { scope.launch { repository.setSpeedTestSaveToGallery(it) } }
        }
        item {
            ToggleSetting(
                R.string.tooldetail_speed_show_ip_title,
                stringResource(R.string.tooldetail_speed_show_ip_subtitle),
                settings.watch { it.speedTest.showIp },
                default = SettingsDefaults.speedTest.showIp,
            ) { scope.launch { repository.setSpeedTestShowIp(it) } }
        }
    }

    ProvidersGroup(
        repository,
        settings,
        ProviderTool.SPEED_TEST,
        titleRes = R.string.tooldetail_speed_servers_group,
        info = stringResource(R.string.tooldetail_speed_servers_info),
        level = SettingLevel.STANDARD,
    )
}

internal fun speedStyleLabel(style: SpeedCardStyle): Int = when (style) {
    SpeedCardStyle.THEME -> R.string.tooldetail_speed_style_theme
    SpeedCardStyle.DARK -> R.string.tooldetail_speed_style_dark
    SpeedCardStyle.LIGHT -> R.string.tooldetail_speed_style_light
    SpeedCardStyle.GRADIENT -> R.string.tooldetail_speed_style_gradient
    SpeedCardStyle.MONO -> R.string.tooldetail_speed_style_mono
}

internal fun speedSizeLabel(size: SpeedCardSize): Int = when (size) {
    SpeedCardSize.SQUARE -> R.string.tooldetail_speed_size_square
    SpeedCardSize.PORTRAIT -> R.string.tooldetail_speed_size_portrait
    SpeedCardSize.STORY -> R.string.tooldetail_speed_size_story
    SpeedCardSize.WIDE -> R.string.tooldetail_speed_size_wide
    SpeedCardSize.STICKER -> R.string.tooldetail_speed_size_sticker
}

internal fun speedLayoutLabel(layout: SpeedCardLayout): Int = when (layout) {
    SpeedCardLayout.FULL -> R.string.tooldetail_speed_layout_full
    SpeedCardLayout.GAUGE -> R.string.tooldetail_speed_layout_gauge
    SpeedCardLayout.COMPACT -> R.string.tooldetail_speed_layout_compact
    SpeedCardLayout.MINIMAL -> R.string.tooldetail_speed_layout_minimal
}

/**
 * A LibreSpeed row's own screen: a picker over the public list of volunteer
 * servers, so a server can be chosen by city instead of typed. Picking one
 * writes its address, name and paths onto the row's credential; the Setup
 * row above still takes a typed address for a server of the user's own.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun LibreSpeedServerSettings(repository: SettingsRepository, settings: LiveSettings, row: ResolvedProvider?) {
    val scope = rememberCoroutineScope()
    val credentialId = row?.credential?.id ?: return
    var picking by remember { mutableStateOf(false) }
    val current = row.customName.ifBlank { row.url }
    SettingsGroup(stringResource(R.string.tooldetail_speed_librespeed_group)) {
        item {
            NavRow(
                R.string.tooldetail_speed_librespeed_pick_title,
                subtitle = stringResource(R.string.tooldetail_speed_librespeed_pick_subtitle),
                value = current.takeIf { it.isNotBlank() },
            ) { picking = true }
        }
    }
    if (picking) {
        LibreSpeedPickerSheet(
            onPick = { entry ->
                picking = false
                scope.launch {
                    repository.updateProviderCredential(credentialId) { credential ->
                        credential.copy(
                            url = LibreSpeedServers.normaliseBase(entry.server),
                            name = entry.name,
                            extra = credential.extra + LibreSpeedServers.extrasOf(entry),
                        )
                    }
                }
            },
            onDismiss = { picking = false },
        )
    }
}

/** The public list, fetched when the sheet opens, with a filter box over it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibreSpeedPickerSheet(onPick: (LibreSpeedServers.Entry) -> Unit, onDismiss: () -> Unit) {
    val servers by produceState<List<LibreSpeedServers.Entry>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { LibreSpeedServers.fetch() }.getOrNull() ?: emptyList() }
    }
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
            Text(
                stringResource(R.string.tooldetail_speed_librespeed_pick_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.tooldetail_speed_librespeed_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                label = { Text(stringResource(R.string.tooldetail_speed_librespeed_filter)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
            val list = servers
            when {
                list == null -> Text(
                    stringResource(R.string.tooldetail_speed_librespeed_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                list.isEmpty() -> Text(
                    stringResource(R.string.tooldetail_speed_librespeed_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                else -> {
                    val shown = list.filter { entry ->
                        filter.isBlank() || entry.name.contains(filter, ignoreCase = true) ||
                            entry.sponsorName.contains(filter, ignoreCase = true)
                    }
                    for (entry in shown) {
                        WmRow(
                            title = entry.name,
                            subtitle = listOf(entry.sponsorName, LibreSpeedServers.hostOf(entry.server))
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            onClick = { onPick(entry) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

package com.wasimaster.wmkeyboard.app.kdeconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.app.SwipeActionsRow
import com.wasimaster.wmkeyboard.app.deleteSwipeAction
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Computer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Laptop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tablet
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tv
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.BannerTone
import com.wasimaster.wmkeyboard.app.ChoiceDetail
import com.wasimaster.wmkeyboard.app.ChoiceSetting
import com.wasimaster.wmkeyboard.app.ExpandableCard
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.SettingLevel
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.SliderSetting
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.StateBanner
import com.wasimaster.wmkeyboard.app.TextFieldSetting
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.app.rememberDisclosedSpecialAccess
import com.wasimaster.wmkeyboard.app.rememberGrantState
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeClipboardPause
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDeviceType
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairState
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePluginKeys
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.KdeConnectSettings
import com.wasimaster.wmkeyboard.core.settings.KdeLinkLifetime
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

object KdeDevices {
    const val ROUTE = KdeConnectHub.DEVICES_ROUTE
}

/**
 * The KDE Connect tool's own page (issue #285): the switch, the way to the
 * devices, and every option the tool has. Nothing here is mirrored from
 * another page — the tool owns all of it.
 */
@Composable
internal fun KdeConnectToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val defaults = SettingsDefaults.kdeConnect
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hub by KdeConnectHub.state.collectAsState()
    // The keyboard feeds the hub its settings; this page may be open in a
    // process where the keyboard has not run since the last change.
    val kde = settings.watch { it.kdeConnect }
    LaunchedEffect(kde) {
        KdeConnectHub.attach(context)
        KdeConnectHub.applySettings(kde)
    }
    // What decides which rows and groups the page holds; each row reads its own value.
    val kdeOn = settings.watch { it.kdeConnect.enabled }
    val remoteTyping = settings.watch { it.kdeConnect.remoteTyping }
    val clipboardReceive = settings.watch { it.kdeConnect.clipboardReceive }
    val exposeMedia = settings.watch { it.kdeConnect.exposeMedia }
    val connected = hub.connected
    val pairedCount = if (hub.running) hub.paired.size else if (KdeConnectHub.hasPairedDevices()) 1 else 0

    when {
        !kdeOn -> StateBanner(stringResource(R.string.kdeconnect_banner_off))
        !DirectBoot.isUserUnlocked(context) -> StateBanner(stringResource(R.string.kdeconnect_banner_locked), tone = BannerTone.WARNING)
        !InternetPermission.granted -> StateBanner(
            stringResource(OfflineBuild.refused()),
            tone = BannerTone.WARNING,
        )
        connected.isNotEmpty() -> StateBanner(
            stringResource(R.string.kdeconnect_banner_connected, connected.joinToString { it.name }),
            action = stringResource(R.string.kdeconnect_banner_action_devices),
        ) { onNavigate(KdeDevices.ROUTE) }
        pairedCount == 0 -> StateBanner(
            stringResource(R.string.kdeconnect_banner_no_devices),
            action = stringResource(R.string.kdeconnect_banner_action_pair),
        ) { onNavigate(KdeDevices.ROUTE) }
        else -> StateBanner(
            stringResource(R.string.kdeconnect_banner_idle, pairedCount),
            action = stringResource(R.string.kdeconnect_banner_action_devices),
        ) { onNavigate(KdeDevices.ROUTE) }
    }

    SettingsGroup(stringResource(R.string.kdeconnect_group_main), info = stringResource(R.string.kdeconnect_group_main_info)) {
        item {
            ToggleSetting(
                R.string.kdeconnect_enabled_title,
                stringResource(R.string.kdeconnect_enabled_subtitle),
                kdeOn,
                default = defaults.enabled,
            ) { scope.launch { repository.setKdeEnabled(it) } }
        }
        item(visible = kdeOn) {
            NavRow(
                title = R.string.kdeconnect_devices_title,
                subtitle = if (pairedCount == 0) {
                    stringResource(R.string.kdeconnect_devices_subtitle_none)
                } else {
                    stringResource(R.string.kdeconnect_devices_subtitle, pairedCount)
                },
            ) { onNavigate(KdeDevices.ROUTE) }
        }
        item(visible = kdeOn, level = SettingLevel.STANDARD) {
            TextFieldSetting(
                label = stringResource(R.string.kdeconnect_name_label),
                value = settings.watch { it.kdeConnect.deviceName },
                hint = stringResource(R.string.kdeconnect_name_hint),
                default = defaults.deviceName,
            ) { repository.setKdeDeviceName(it) }
        }
    }
    if (!kdeOn) return

    SettingsGroup(stringResource(R.string.kdeconnect_group_connection), level = SettingLevel.ADVANCED) {
        item {
            val details = mapOf(
                KdeLinkLifetime.PANEL to stringResource(R.string.kdeconnect_lifetime_panel_detail),
                KdeLinkLifetime.KEYBOARD to stringResource(R.string.kdeconnect_lifetime_keyboard_detail),
                KdeLinkLifetime.ALWAYS to stringResource(R.string.kdeconnect_lifetime_always_detail),
            )
            ChoiceSetting(
                R.string.kdeconnect_lifetime_title,
                info = stringResource(R.string.kdeconnect_lifetime_info),
                options = listOf(
                    KdeLinkLifetime.PANEL to stringResource(R.string.kdeconnect_lifetime_panel),
                    KdeLinkLifetime.KEYBOARD to stringResource(R.string.kdeconnect_lifetime_keyboard),
                    KdeLinkLifetime.ALWAYS to stringResource(R.string.kdeconnect_lifetime_always),
                ),
                selected = settings.watch { it.kdeConnect.lifetime },
                default = defaults.lifetime,
                detail = { choice -> details[choice]?.let { ChoiceDetail(it) } },
            ) { scope.launch { repository.setKdeLifetime(it) } }
        }
        item {
            ToggleSetting(
                R.string.kdeconnect_auto_connect_title,
                stringResource(R.string.kdeconnect_auto_connect_subtitle),
                settings.watch { it.kdeConnect.autoConnect },
                default = defaults.autoConnect,
            ) { scope.launch { repository.setKdeAutoConnect(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.kdeconnect_group_clipboard), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.kdeconnect_clipboard_receive_title,
                stringResource(R.string.kdeconnect_clipboard_receive_subtitle),
                clipboardReceive,
                default = defaults.clipboardReceive,
            ) { scope.launch { repository.setKdeClipboardReceive(it) } }
        }
        item(visible = clipboardReceive, level = SettingLevel.ADVANCED) {
            val minutesFormat = stringResource(R.string.values_minutes)
            val off = stringResource(R.string.kdeconnect_clear_secrets_off)
            SliderSetting(
                R.string.kdeconnect_clear_secrets_title,
                subtitle = stringResource(R.string.kdeconnect_clear_secrets_subtitle),
                value = settings.watch { it.kdeConnect.clearSecretsMinutes }.toFloat(),
                range = 0f..KdeConnectSettings.MAX_CLEAR_SECRETS_MINUTES.toFloat(),
                display = { if (it.toInt() == 0) off else minutesFormat.format(it.toInt()) },
                info = stringResource(R.string.kdeconnect_clear_secrets_info),
                default = defaults.clearSecretsMinutes.toFloat(),
            ) { scope.launch { repository.setKdeClearSecretsMinutes(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.kdeconnect_clipboard_send_title,
                stringResource(R.string.kdeconnect_clipboard_send_subtitle),
                settings.watch { it.kdeConnect.clipboardSend },
                info = stringResource(R.string.kdeconnect_clipboard_send_info),
                default = defaults.clipboardSend,
            ) { scope.launch { repository.setKdeClipboardSend(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.kdeconnect_group_typing), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.kdeconnect_remote_typing_title,
                stringResource(R.string.kdeconnect_remote_typing_subtitle),
                remoteTyping,
                default = defaults.remoteTyping,
            ) { scope.launch { repository.setKdeRemoteTyping(it) } }
        }
        item(visible = remoteTyping, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.kdeconnect_pipeline_title,
                stringResource(R.string.kdeconnect_pipeline_subtitle),
                settings.watch { it.kdeConnect.remoteTypingPipeline },
                info = stringResource(R.string.kdeconnect_pipeline_info),
                default = defaults.remoteTypingPipeline,
            ) { scope.launch { repository.setKdeRemoteTypingPipeline(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.kdeconnect_group_touchpad), level = SettingLevel.STANDARD) {
        val times: (Float) -> String = { String.format(Locale.getDefault(), "%.1f×", it) }
        item {
            SliderSetting(
                R.string.kdeconnect_pad_speed_title,
                value = settings.watch { it.kdeConnect.padSensitivity },
                range = KdeConnectSettings.PAD_SPEED_RANGE,
                display = times,
                default = defaults.padSensitivity,
            ) { scope.launch { repository.setKdePadSensitivity(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.kdeconnect_pad_accel_title,
                stringResource(R.string.kdeconnect_pad_accel_subtitle),
                settings.watch { it.kdeConnect.padAcceleration },
                default = defaults.padAcceleration,
            ) { scope.launch { repository.setKdePadAcceleration(it) } }
        }
        item {
            SliderSetting(
                R.string.kdeconnect_scroll_speed_title,
                value = settings.watch { it.kdeConnect.scrollSpeed },
                range = KdeConnectSettings.PAD_SPEED_RANGE,
                display = times,
                default = defaults.scrollSpeed,
            ) { scope.launch { repository.setKdeScrollSpeed(it) } }
        }
        item {
            ToggleSetting(
                R.string.kdeconnect_natural_scroll_title,
                stringResource(R.string.kdeconnect_natural_scroll_subtitle),
                settings.watch { it.kdeConnect.naturalScroll },
                default = defaults.naturalScroll,
            ) { scope.launch { repository.setKdeNaturalScroll(it) } }
        }
        item {
            ToggleSetting(
                R.string.kdeconnect_tap_click_title,
                stringResource(R.string.kdeconnect_tap_click_subtitle),
                settings.watch { it.kdeConnect.tapToClick },
                default = defaults.tapToClick,
            ) { scope.launch { repository.setKdeTapToClick(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.kdeconnect_pad_haptics_title,
                null,
                settings.watch { it.kdeConnect.padHaptics },
                default = defaults.padHaptics,
            ) { scope.launch { repository.setKdePadHaptics(it) } }
        }
    }

    val hasAccess = rememberGrantState(::hasNotificationAccess)
    val openAccess = rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATIONS)
    SettingsGroup(stringResource(R.string.kdeconnect_group_sharing), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.kdeconnect_receive_files_title,
                stringResource(R.string.kdeconnect_receive_files_subtitle),
                settings.watch { it.kdeConnect.receiveFiles },
                default = defaults.receiveFiles,
            ) { scope.launch { repository.setKdeReceiveFiles(it) } }
        }
        item {
            ToggleSetting(
                R.string.kdeconnect_share_sheet_title,
                stringResource(R.string.kdeconnect_share_sheet_subtitle),
                settings.watch { it.kdeConnect.shareSheet },
                default = defaults.shareSheet,
            ) { scope.launch { repository.setKdeShareSheet(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.kdeconnect_battery_title,
                stringResource(R.string.kdeconnect_battery_subtitle),
                settings.watch { it.kdeConnect.batteryReport },
                default = defaults.batteryReport,
            ) { scope.launch { repository.setKdeBatteryReport(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.kdeconnect_codes_title,
                stringResource(R.string.kdeconnect_codes_subtitle),
                settings.watch { it.kdeConnect.sendCodes },
                info = stringResource(R.string.kdeconnect_codes_info),
                default = defaults.sendCodes,
            ) { scope.launch { repository.setKdeSendCodes(it) } }
        }
        item {
            ToggleSetting(
                R.string.kdeconnect_media_title,
                stringResource(R.string.kdeconnect_media_subtitle),
                exposeMedia,
                default = defaults.exposeMedia,
            ) { scope.launch { repository.setKdeExposeMedia(it) } }
        }
        item(visible = exposeMedia) {
            NavRow(
                title = R.string.kdeconnect_media_access_title,
                subtitle = stringResource(
                    if (hasAccess) R.string.kdeconnect_media_access_granted else R.string.kdeconnect_media_access_missing,
                ),
            ) { openAccess() }
        }
    }

    ExpandableCard(stringResource(R.string.kdeconnect_help_title)) {
        Text(stringResource(R.string.kdeconnect_help_body), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Paired computers and the ones nearby: pair, accept, unpair, the per-device
 * switches, and the addresses announced to directly. While this screen is up
 * the hub is held and browsing, so computers that are not paired yet appear —
 * which they otherwise never do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KdeDevicesScreen(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hub by KdeConnectHub.state.collectAsState()
    val kde = settings.watch { it.kdeConnect }
    LaunchedEffect(kde) {
        KdeConnectHub.attach(context)
        KdeConnectHub.applySettings(kde)
    }
    DisposableEffect(Unit) {
        KdeConnectHub.attach(context)
        KdeConnectHub.hold(KdeConnectHub.Reason.SETTINGS)
        KdeConnectHub.setBrowsing(KdeConnectHub.Reason.SETTINGS, true)
        onDispose { KdeConnectHub.release(KdeConnectHub.Reason.SETTINGS) }
    }
    if (!settings.watch { it.kdeConnect.enabled }) {
        StateBanner(stringResource(R.string.kdeconnect_devices_off), action = stringResource(R.string.kdeconnect_enabled_title)) {
            scope.launch { repository.setKdeEnabled(true) }
        }
        return
    }
    if (!InternetPermission.granted) {
        StateBanner(stringResource(OfflineBuild.refused()), tone = BannerTone.WARNING)
        return
    }
    val engine = KdeConnectHub.engine

    hub.devices.firstOrNull { it.pairState == KdePairState.REQUESTED || it.pairState == KdePairState.REQUESTED_BY_PEER }
        ?.let { PairingCard(it) }

    // Redrawn as a timed pause ends: nothing else changes when it does.
    val pauses = settings.watch { it.kdeConnect.clipboardPauses }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pauses) {
        while (true) {
            now = System.currentTimeMillis()
            val end = KdeClipboardPause.nextEnd(pauses, now) ?: break
            delay((end - now).coerceAtLeast(0) + 50)
        }
    }

    SettingsGroup(stringResource(R.string.kdeconnect_devices_paired)) {
        if (hub.paired.size > 1 || KdeClipboardPause.allPausedUntil(pauses, now) > 0) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.kdeconnect_pause_all_title), fontWeight = FontWeight.Medium)
                    ClipboardPauseRow(KdeClipboardPause.allPausedUntil(pauses, now), deviceId = null, resumeId = null)
                }
            }
        }
        for (device in hub.paired) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    DeviceHeader(
                        device,
                        stringResource(if (device.reachable) R.string.kdeconnect_device_connected else R.string.kdeconnect_device_unreachable),
                    ) {
                        TextButton(onClick = { engine?.unpair(device.id) }) { Text(stringResource(R.string.kdeconnect_device_unpair)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((key, label) in DEVICE_SWITCHES) {
                            FilterChip(
                                selected = key !in device.disabled,
                                onClick = { engine?.setPluginEnabled(device.id, key, key in device.disabled) },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                    if (KdePluginKeys.CLIPBOARD !in device.disabled) {
                        val until = KdeClipboardPause.pausedUntil(pauses, device.id, now)
                        val byAll = until > 0 && KdeClipboardPause.allPausedUntil(pauses, now) >= until
                        ClipboardPauseRow(until, device.id, resumeId = if (byAll) null else device.id)
                    }
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.kdeconnect_devices_nearby), info = stringResource(R.string.kdeconnect_devices_pair_code_info)) {
        if (hub.nearby.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.kdeconnect_devices_nearby_empty), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { KdeConnectHub.refresh() }) { Text(stringResource(R.string.kdeconnect_refresh)) }
                }
            }
        }
        for (device in hub.nearby) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    DeviceHeader(device, device.address) {
                        Button(onClick = { engine?.requestPair(device.id) }) { Text(stringResource(R.string.kdeconnect_device_pair)) }
                    }
                }
            }
        }
    }

    // Decides which rows the group holds; the add row reuses its size.
    val hosts = settings.watch { it.kdeConnect.hosts.sorted() }
    SettingsGroup(stringResource(R.string.kdeconnect_hosts_title), info = stringResource(R.string.kdeconnect_hosts_info)) {
        item {
            var draft by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(64) },
                    label = { Text(stringResource(R.string.kdeconnect_hosts_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = draft.isNotBlank() && hosts.size < KdeConnectSettings.MAX_HOSTS,
                    onClick = {
                        val host = draft.trim()
                        draft = ""
                        scope.launch { repository.addKdeHost(host) }
                        engine?.announceTo(host)
                    },
                ) { Text(stringResource(R.string.kdeconnect_hosts_add)) }
            }
        }
        for (host in hosts) {
            item(key = host) {
                val removeLabel = stringResource(R.string.kdeconnect_hosts_remove, host)
                SwipeActionsRow(
                    listOf(deleteSwipeAction(removeLabel) { scope.launch { repository.removeKdeHost(host) } }),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        IconButton(onClick = { scope.launch { repository.removeKdeHost(host) } }) {
                            Icon(Icons.Outlined.Close, removeLabel)
                        }
                    }
                }
            }
        }
    }

    if (hub.selfFingerprint.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.kdeconnect_this_device)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(hub.selfName, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.kdeconnect_this_device_fingerprint), style = MaterialTheme.typography.labelMedium)
                    Text(hub.selfFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Pause or resume clipboard sharing (#918) with one computer, or with all of
 * them when [deviceId] is null. [until] is when the pause in force ends, 0 for
 * none. A computer held by the pause on all of them resumes all ([resumeId] null).
 */
@Composable
private fun ClipboardPauseRow(until: Long, deviceId: String?, resumeId: String?) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val status = when {
            until == 0L -> stringResource(R.string.kdeconnect_pause_off)
            until == KdeClipboardPause.FOREVER -> stringResource(R.string.kdeconnect_pause_forever)
            else -> stringResource(
                R.string.kdeconnect_pause_until,
                android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(until)),
            )
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (until > 0) {
            TextButton(onClick = { KdeConnectHub.resumeClipboard(context, resumeId) }) {
                Text(stringResource(R.string.kdeconnect_pause_resume))
            }
        } else {
            Box {
                TextButton(onClick = { menu = true }) { Text(stringResource(R.string.kdeconnect_pause_action)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (minutes in KdeClipboardPause.PRESET_MINUTES) {
                        DropdownMenuItem(
                            text = { Text(stringResource(pauseLabel(minutes))) },
                            onClick = {
                                menu = false
                                KdeConnectHub.pauseClipboard(context, deviceId, minutes)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun pauseLabel(minutes: Int): Int = when (minutes) {
    15 -> R.string.kdeconnect_pause_15m
    60 -> R.string.kdeconnect_pause_1h
    else -> R.string.kdeconnect_pause_until_resumed
}

@Composable
private fun PairingCard(device: KdeDevice) {
    val engine = KdeConnectHub.engine
    val incoming = device.pairState == KdePairState.REQUESTED_BY_PEER
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(device.pairDeadlineMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val total = if (incoming) 25_000f else 30_000f
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(if (incoming) R.string.kdeconnect_pairing_incoming else R.string.kdeconnect_pairing_outgoing, device.name),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            device.verificationKey.orEmpty().chunked(2).joinToString(" "),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { ((device.pairDeadlineMs - now) / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (incoming) {
                OutlinedButton(onClick = { engine?.cancelPair(device.id) }) { Text(stringResource(R.string.kdeconnect_pairing_reject)) }
                Button(onClick = { engine?.acceptPair(device.id) }) { Text(stringResource(R.string.kdeconnect_pairing_accept)) }
            } else {
                OutlinedButton(onClick = { engine?.cancelPair(device.id) }) { Text(stringResource(R.string.kdeconnect_pairing_cancel)) }
            }
        }
    }
}

@Composable
private fun DeviceHeader(device: KdeDevice, status: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(deviceIcon(device.type), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

private fun deviceIcon(type: KdeDeviceType): ImageVector = when (type) {
    KdeDeviceType.DESKTOP -> Icons.Outlined.Computer
    KdeDeviceType.LAPTOP -> Icons.Outlined.Laptop
    KdeDeviceType.PHONE -> Icons.Outlined.Smartphone
    KdeDeviceType.TABLET -> Icons.Outlined.Tablet
    KdeDeviceType.TV -> Icons.Outlined.Tv
}

private val DEVICE_SWITCHES = listOf(
    KdePluginKeys.CLIPBOARD to R.string.kdeconnect_device_clipboard,
    KdePluginKeys.REMOTE_TYPING to R.string.kdeconnect_device_typing,
    KdePluginKeys.SHARE to R.string.kdeconnect_device_files,
    KdePluginKeys.MEDIA to R.string.kdeconnect_device_media,
    KdePluginKeys.BATTERY to R.string.kdeconnect_device_battery,
    KdePluginKeys.SYNC to R.string.kdeconnect_device_sync,
)

package com.wasimaster.wmkeyboard.ime.ui.send

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Computer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Devices
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Inventory2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Laptop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCodeScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tablet
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tv
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wifi
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDeviceType
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairState
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeState
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransfer
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransferState
import com.wasimaster.wmkeyboard.core.send.LanTransfer
import com.wasimaster.wmkeyboard.core.settings.SendMode
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.send.BrowserFailure
import com.wasimaster.wmkeyboard.ime.send.BrowserUi
import com.wasimaster.wmkeyboard.ime.send.DirectFailure
import com.wasimaster.wmkeyboard.ime.send.DirectUi
import com.wasimaster.wmkeyboard.ime.send.JoinStep
import com.wasimaster.wmkeyboard.ime.send.SendAction
import com.wasimaster.wmkeyboard.ime.send.SendSheet
import com.wasimaster.wmkeyboard.ime.send.SendUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockCard
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Where a draft can go (#729): the TO row of destination cards, the radar
 * that is Nearby's device view, the transfers, and the status chips the tray
 * shows for a nearby destination. Network state is read straight from
 * [KdeConnectHub.state], as the KDE panel does, so a progress tick never
 * enters the keyboard state. The controller's mode is still Link | Nearby:
 * picking a card sets it, so the engine runs exactly when a nearby
 * destination is in play.
 */

/** A place the tray can send to. */
internal sealed interface SendDest {
    /** The Send server: an encrypted link. */
    data object Link : SendDest

    /** A paired or found device from the KDE Connect engine. */
    data class Device(val id: String) : SendDest

    /** The page served to any browser on this Wi-Fi (#927). */
    data object Browser : SendDest

    /** A Wi-Fi this phone opens for the other one. */
    data object Direct : SendDest
}

/** Which nearby destination the cards have picked, besides a device. */
internal enum class NearbyPick { DEVICE, BROWSER, DIRECT }

/**
 * The panel's own small state: what is UI and nothing the controller needs
 * to know. Lost with the composition, which is fine for toggles.
 */
@Stable
internal class SendPanelLocal {
    /** The radar is up in place of the tray. */
    var radarOpen by mutableStateOf(false)
    var pick by mutableStateOf(NearbyPick.DEVICE)

    /** The pile is spread into tiles with their ✕. */
    var pileOpen by mutableStateOf(false)
    var transfersOpen by mutableStateOf(false)

    /** The receipt's password chip shows the letters. */
    var passwordShown by mutableStateOf(false)

    /** The nearby transfer set whose sending face was put away, so the tray can be used meanwhile. */
    var hiddenSendKey by mutableStateOf<String?>(null)
}

/** The card the Send circle sends to, from the controller's mode and the panel's pick. */
internal fun selectedDest(ui: SendUi, hub: KdeState, local: SendPanelLocal): SendDest {
    if (ui.mode == SendMode.LINK || !ui.nearbyAvailable) return SendDest.Link
    return when (local.pick) {
        NearbyPick.BROWSER -> SendDest.Browser
        NearbyPick.DIRECT -> SendDest.Direct
        NearbyPick.DEVICE -> {
            val id = hub.device(ui.nearby.targetId)?.id ?: hub.connected.firstOrNull()?.id ?: hub.paired.firstOrNull()?.id
            if (id != null) SendDest.Device(id) else SendDest.Link
        }
    }
}

/** The look of a destination wherever it is drawn: a card, the sending face's centre. */
internal class DestLook(val icon: ImageVector, val name: String, val sub: String, val subColor: Color? = null)

@Composable
internal fun destLook(dest: SendDest, ui: SendUi, hub: KdeState): DestLook {
    val context = LocalContext.current
    return when (dest) {
        SendDest.Link -> DestLook(Icons.Outlined.Link, stringResource(R.string.ime_send_dest_link), hostText(ui.instance) + " · " + bytesText(context, ui.maxBytes))
        is SendDest.Device -> {
            val device = hub.device(dest.id)
            if (device == null) {
                DestLook(Icons.Outlined.Devices, stringResource(R.string.ime_send_devices_count_zero), stringResource(R.string.ime_send_nearby_unreachable))
            } else {
                deviceLook(device)
            }
        }
        SendDest.Browser -> DestLook(
            Icons.Outlined.Language,
            stringResource(R.string.ime_send_dest_browser),
            when (val b = ui.nearby.browser) {
                BrowserUi.Off -> stringResource(R.string.ime_send_dest_browser_sub)
                BrowserUi.Starting -> stringResource(R.string.ime_send_direct_starting)
                is BrowserUi.Serving -> b.hotspot?.ssid ?: b.url.removePrefix("http://").substringBefore('/')
                is BrowserUi.Failed -> stringResource(R.string.ime_send_transfer_failed)
            },
            if (ui.nearby.browser is BrowserUi.Failed) FailedRed else if (ui.nearby.browser is BrowserUi.Serving) ConnectedGreen else null,
        )
        SendDest.Direct -> DestLook(
            Icons.Outlined.WifiTethering,
            stringResource(R.string.ime_send_dest_direct),
            when (val d = ui.nearby.direct) {
                DirectUi.Off -> stringResource(R.string.ime_send_dest_direct_sub)
                DirectUi.Starting -> stringResource(R.string.ime_send_direct_starting)
                is DirectUi.Hosting -> d.joined ?: stringResource(R.string.ime_send_direct_hosting_short)
                is DirectUi.Joining -> if (d.step == JoinStep.LINKED) d.code.name else stringResource(R.string.ime_send_direct_step_short)
                is DirectUi.Failed -> stringResource(R.string.ime_send_transfer_failed)
            },
            when (val d = ui.nearby.direct) {
                is DirectUi.Failed -> FailedRed
                is DirectUi.Hosting -> if (d.joined != null) ConnectedGreen else null
                is DirectUi.Joining -> if (d.step == JoinStep.LINKED) ConnectedGreen else null
                else -> null
            },
        )
    }
}

@Composable
private fun deviceLook(device: KdeDevice): DestLook = DestLook(
    deviceIcon(device.type),
    device.name,
    when {
        device.pairState == KdePairState.REQUESTED_BY_PEER -> stringResource(R.string.ime_send_nearby_wants_pair)
        device.pairState == KdePairState.REQUESTED -> stringResource(R.string.ime_send_direct_step_pairing)
        !device.paired -> stringResource(R.string.ime_send_nearby_pair)
        device.reachable -> stringResource(R.string.ime_send_nearby_connected)
        else -> stringResource(R.string.ime_send_nearby_unreachable)
    },
    when {
        device.pairState == KdePairState.REQUESTED_BY_PEER || !device.paired -> LocalKbTheme.current.accent
        device.reachable -> ConnectedGreen
        else -> null
    },
)

// ---- the TO row ----

/**
 * One carousel of destination cards: the Link server, every device the
 * engine knows, the browser page, the direct Wi-Fi, a code to scan, and the
 * radar. The chosen one wears the active-chip fill and an accent ring.
 */
@Composable
internal fun DestinationRow(ui: SendUi, hub: KdeState, dest: SendDest, local: SendPanelLocal, act: (SendAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.panels
    val devices = remember(hub.devices) { hub.paired.sortedByDescending { it.reachable } + hub.nearby }
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        item(key = "link") {
            val look = destLook(SendDest.Link, ui, hub)
            val selected = dest == SendDest.Link
            DestCard(
                look, selected = selected, index = 0, animate = animate, chevron = true,
                onLongClick = { act(SendAction.OpenSheet(SendSheet.INSTANCES)) },
            ) {
                if (selected) act(SendAction.OpenSheet(SendSheet.INSTANCES)) else act(SendAction.SetMode(SendMode.LINK))
            }
        }
        if (!ui.nearbyAvailable) {
            item(key = "nearby-off") {
                DestCard(
                    DestLook(Icons.Outlined.Wifi, stringResource(R.string.ime_send_mode_nearby), stringResource(R.string.ime_send_dest_nearby_off_sub)),
                    selected = false, index = 1, animate = animate, chevron = true,
                ) { act(SendAction.OpenSettings) }
            }
            return@LazyRow
        }
        itemsIndexed(devices, key = { _, d -> "d-" + d.id }) { i, device ->
            val selected = (dest as? SendDest.Device)?.id == device.id
            DestCard(deviceLook(device), selected = selected, index = 1 + i, animate = animate) {
                act(SendAction.SetMode(SendMode.NEARBY))
                local.pick = NearbyPick.DEVICE
                act(SendAction.PickDevice(device.id))
                if (!device.paired && device.pairState == KdePairState.NOT_PAIRED) act(SendAction.PairDevice(device.id))
            }
        }
        val after = 1 + devices.size
        item(key = "browser") {
            DestCard(destLook(SendDest.Browser, ui, hub), selected = dest == SendDest.Browser, index = after, animate = animate) {
                act(SendAction.SetMode(SendMode.NEARBY))
                local.pick = NearbyPick.BROWSER
            }
        }
        item(key = "direct") {
            DestCard(destLook(SendDest.Direct, ui, hub), selected = dest == SendDest.Direct, index = after + 1, animate = animate) {
                act(SendAction.SetMode(SendMode.NEARBY))
                local.pick = NearbyPick.DIRECT
            }
        }
        if (ui.canScan) {
            item(key = "scan") {
                DestCard(
                    DestLook(Icons.Outlined.QrCodeScanner, stringResource(R.string.ime_send_dest_scan), stringResource(R.string.ime_send_dest_scan_sub)),
                    selected = false, index = after + 2, animate = animate,
                ) {
                    act(SendAction.SetMode(SendMode.NEARBY))
                    act(SendAction.ScanDirect)
                }
            }
        }
        item(key = "radar") {
            val count = hub.devices.count { it.reachable }
            DestCard(
                DestLook(
                    Icons.Outlined.Devices,
                    stringResource(R.string.ime_send_mode_nearby),
                    if (count == 0) stringResource(R.string.ime_send_devices_count_zero) else pluralStringResource(R.plurals.ime_send_devices_count, count, count),
                ),
                selected = false, index = after + 3, animate = animate, chevron = true,
            ) {
                act(SendAction.SetMode(SendMode.NEARBY))
                local.radarOpen = true
            }
        }
    }
}

/** A destination card: an icon in a soft accent circle, the name, a one-line sub; the chosen one filled and ringed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DestCard(
    look: DestLook,
    selected: Boolean,
    index: Int,
    animate: Boolean,
    modifier: Modifier = Modifier,
    chevron: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    val fg = if (selected) kb.chipActiveText else kb.chipText
    Box(
        modifier
            .popIn(index, animate)
            .size(DEST_W, DEST_H)
            .pressScale(source, kb.motion.any)
            .clip(shape)
            .then(if (selected) Modifier.background(kb.chipActive) else Modifier.frostGlass(kb, shape, FrostSurface.CARDS).background(kb.chip.copy(alpha = kb.chip.alpha * 0.75f)))
            .then(if (selected) Modifier.border(2.dp, kb.accent, shape) else Modifier.chipBorder(kb, shape))
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = {
                    feedback()
                    onClick()
                },
                onLongClick = onLongClick?.let { hold -> { feedback(); hold() } },
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (selected) fg.copy(alpha = 0.16f) else kb.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, contentDescription = null, tint = if (selected) fg else kb.accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.height(3.dp))
            Text(look.name, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                look.sub,
                color = if (selected) fg.copy(alpha = 0.75f) else look.subColor ?: kb.secondaryText,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chevron) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = fg.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(12.dp),
            )
        }
    }
}

/** The destination as the sending face draws it: the card's inside, in a filled circle. */
@Composable
internal fun DestBadge(look: DestLook, modifier: Modifier = Modifier, size: Dp = 110.dp) {
    val kb = LocalKbTheme.current
    val fg = kb.chipActiveText
    Column(
        modifier.size(size).clip(CircleShape).background(kb.chipActive).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(look.icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.24f))
        Spacer(Modifier.height(2.dp))
        Text(look.name, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(look.sub, color = fg.copy(alpha = 0.8f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- the tray's status chips for a nearby destination ----

/** Whether [NearbyStatusChips] has anything to say for [dest]. */
internal fun nearbyStatusVisible(ui: SendUi, hub: KdeState, dest: SendDest): Boolean {
    if (anyTransfers(ui, hub)) return true
    return when (dest) {
        SendDest.Link -> false
        is SendDest.Device -> {
            val device = hub.device(dest.id) ?: return false
            device.pairState != KdePairState.PAIRED || (ui.draft.isEmpty && ui.nearby.sentTo == device.name)
        }
        SendDest.Browser -> ui.nearby.browser != BrowserUi.Off
        SendDest.Direct -> ui.nearby.direct != DirectUi.Off
    }
}

internal fun anyTransfers(ui: SendUi, hub: KdeState): Boolean =
    hub.devices.any { it.transfers.isNotEmpty() } || (ui.nearby.browser as? BrowserUi.Serving)?.transfers?.isNotEmpty() == true

/**
 * What the tray says about a nearby destination, as one row of chips:
 * pairing, the browser page's controls, the direct Wi-Fi's step and code,
 * and the transfers chip when any are under way or done.
 */
@Composable
internal fun RowScope.NearbyStatusChips(ui: SendUi, hub: KdeState, dest: SendDest, local: SendPanelLocal, act: (SendAction) -> Unit) {
    when (dest) {
        SendDest.Link -> Unit
        is SendDest.Device -> {
            val device = hub.device(dest.id)
            if (device != null) {
                when (device.pairState) {
                    KdePairState.REQUESTED_BY_PEER -> {
                        device.verificationKey?.let { MetaText(it) }
                        ToolPanelChip(stringResource(R.string.ime_send_nearby_accept), selected = true) { act(SendAction.AcceptPair(device.id)) }
                        ToolPanelChip(stringResource(R.string.ime_send_nearby_reject)) { act(SendAction.RejectPair(device.id)) }
                    }
                    KdePairState.REQUESTED -> MetaText(stringResource(R.string.ime_send_nearby_pairing, device.verificationKey.orEmpty()))
                    KdePairState.NOT_PAIRED -> ToolPanelChip(stringResource(R.string.ime_send_nearby_pair), selected = true) { act(SendAction.PairDevice(device.id)) }
                    KdePairState.PAIRED -> Unit
                }
                if (device.pairState == KdePairState.PAIRED && ui.draft.isEmpty && ui.nearby.sentTo == device.name) {
                    MetaText(stringResource(R.string.ime_send_nearby_sent_to, device.name), color = ConnectedGreen)
                }
            }
        }
        SendDest.Browser -> BrowserChips(ui.nearby.browser, act)
        SendDest.Direct -> DirectChips(ui.nearby.direct, act)
    }
    if (anyTransfers(ui, hub)) {
        val count = hub.devices.sumOf { it.transfers.size } + ((ui.nearby.browser as? BrowserUi.Serving)?.transfers?.size ?: 0)
        ToolPanelChip(pluralStringResource(R.plurals.ime_send_transfers_count, count, count)) { local.transfersOpen = true }
    }
}

@Composable
private fun RowScope.BrowserChips(browser: BrowserUi, act: (SendAction) -> Unit) {
    when (browser) {
        BrowserUi.Off -> Unit
        BrowserUi.Starting -> {
            Radar(Modifier.size(16.dp))
            MetaText(stringResource(R.string.ime_send_direct_starting))
            ToolPanelChip(stringResource(R.string.ime_send_browser_stop)) { act(SendAction.StopBrowser) }
        }
        is BrowserUi.Serving -> {
            ToolPanelChip(stringResource(R.string.ime_send_direct_show_code), selected = true) { act(SendAction.OpenSheet(SendSheet.BROWSER_CODE)) }
            ToolPanelChip(stringResource(R.string.ime_send_browser_uploads), selected = browser.uploads) { act(SendAction.BrowserUploads(!browser.uploads)) }
            if (browser.hotspot == null) {
                ToolPanelChip(stringResource(R.string.ime_send_browser_hotspot)) { act(SendAction.OpenBrowserHotspot) }
            }
            ToolPanelChip(stringResource(R.string.ime_send_copy)) { act(SendAction.CopyBrowserLink) }
            ToolPanelChip(stringResource(R.string.ime_send_browser_stop)) { act(SendAction.StopBrowser) }
        }
        is BrowserUi.Failed -> {
            MetaText(
                stringResource(
                    when (browser.hotspot) {
                        DirectFailure.NO_PERMISSION -> R.string.ime_send_direct_failed_permission
                        DirectFailure.WIFI_OFF -> R.string.ime_send_direct_failed_wifi_off
                        DirectFailure.UNSUPPORTED -> R.string.ime_send_direct_failed_unsupported
                        null -> when (browser.reason) {
                            BrowserFailure.NO_NETWORK -> R.string.ime_send_browser_failed_network
                            BrowserFailure.SERVER -> R.string.ime_send_browser_failed_server
                            BrowserFailure.HOTSPOT -> R.string.ime_send_direct_failed_group
                        }
                        else -> R.string.ime_send_direct_failed_group
                    },
                ),
                color = FailedRed,
            )
            when {
                browser.hotspot == DirectFailure.NO_PERMISSION ->
                    ToolPanelChip(stringResource(R.string.ime_send_direct_allow), selected = true) { act(SendAction.OpenBrowserHotspot) }
                browser.reason == BrowserFailure.NO_NETWORK ->
                    ToolPanelChip(stringResource(R.string.ime_send_browser_open_hotspot), selected = true) { act(SendAction.OpenBrowserHotspot) }
            }
            ToolPanelChip(stringResource(R.string.ime_send_close)) { act(SendAction.StopBrowser) }
        }
    }
}

@Composable
private fun RowScope.DirectChips(direct: DirectUi, act: (SendAction) -> Unit) {
    when (direct) {
        DirectUi.Off -> Unit
        DirectUi.Starting -> {
            Radar(Modifier.size(16.dp))
            MetaText(stringResource(R.string.ime_send_direct_starting))
            ToolPanelChip(stringResource(R.string.ime_send_direct_stop)) { act(SendAction.StopDirect) }
        }
        is DirectUi.Hosting -> {
            if (direct.joined == null) Radar(Modifier.size(16.dp))
            MetaText(
                if (direct.joined != null) stringResource(R.string.ime_send_direct_joined, direct.joined) else stringResource(R.string.ime_send_direct_hosting_title),
                color = if (direct.joined != null) ConnectedGreen else null,
            )
            ToolPanelChip(stringResource(R.string.ime_send_direct_show_code), selected = true) { act(SendAction.OpenSheet(SendSheet.DIRECT_CODE)) }
            ToolPanelChip(stringResource(R.string.ime_send_direct_stop)) { act(SendAction.StopDirect) }
        }
        is DirectUi.Joining -> {
            if (direct.step != JoinStep.LINKED) Radar(Modifier.size(16.dp))
            MetaText(
                stringResource(
                    when (direct.step) {
                        JoinStep.WIFI -> R.string.ime_send_direct_step_wifi
                        JoinStep.FINDING -> R.string.ime_send_direct_step_finding
                        JoinStep.PAIRING -> R.string.ime_send_direct_step_pairing
                        JoinStep.LINKED -> R.string.ime_send_direct_step_linked
                    },
                    direct.code.name,
                ),
                color = if (direct.step == JoinStep.LINKED) ConnectedGreen else null,
            )
            ToolPanelChip(stringResource(R.string.ime_send_direct_stop)) { act(SendAction.StopDirect) }
        }
        is DirectUi.Failed -> {
            MetaText(
                stringResource(
                    when (direct.reason) {
                        DirectFailure.NO_PERMISSION -> R.string.ime_send_direct_failed_permission
                        DirectFailure.WIFI_OFF -> R.string.ime_send_direct_failed_wifi_off
                        DirectFailure.UNSUPPORTED -> R.string.ime_send_direct_failed_unsupported
                        DirectFailure.GROUP_FAILED -> R.string.ime_send_direct_failed_group
                        DirectFailure.JOIN_FAILED -> R.string.ime_send_direct_failed_join
                        DirectFailure.NOT_FOUND -> R.string.ime_send_direct_failed_not_found
                        DirectFailure.TIMEOUT -> R.string.ime_send_direct_failed_timeout
                    },
                ),
                color = FailedRed,
            )
            if (direct.reason == DirectFailure.NO_PERMISSION) {
                ToolPanelChip(stringResource(R.string.ime_send_direct_allow), selected = true) { act(SendAction.AskNearbyPermission) }
            }
            ToolPanelChip(stringResource(R.string.ime_send_close)) { act(SendAction.StopDirect) }
        }
    }
}

// ---- the radar ----

/** One thing on the radar: who, where, and what a tap does. */
private class RadarNode(
    val key: String,
    val look: DestLook,
    val reachable: Boolean,
    val slot: Int,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

/**
 * Nearby's device view: a sweep turning over three faint rings, the devices
 * placed around it (angle from a stable hash of the id, the near ring for
 * ones in reach, the far ring dim for the rest), the browser page and the
 * direct Wi-Fi as nodes when they are up, and the pile in the middle. A tap
 * on a device sends the pile to it, or pairs. A footer says what the radar
 * is doing, with Refresh, the transfers, and the way back to the tray.
 */
@Composable
internal fun RadarFace(ui: SendUi, hub: KdeState, local: SendPanelLocal, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val motion = kb.motion.panels && !kb.reduceMotion
    val draft = ui.draft
    val nodes = remember(hub.devices, ui.nearby, draft.isEmpty) {
        val wanted = ArrayList<Pair<Int, (Int) -> RadarNode>>()
        for (device in hub.paired + hub.nearby) {
            wanted += slotFor(device.id) to { slot: Int ->
                RadarNode(
                    "d-" + device.id, deviceLookPlain(device), device.reachable, slot,
                    onClick = {
                        act(SendAction.SetMode(SendMode.NEARBY))
                        local.pick = NearbyPick.DEVICE
                        when {
                            device.pairState == KdePairState.REQUESTED_BY_PEER -> act(SendAction.AcceptPair(device.id))
                            !device.paired -> act(SendAction.PairDevice(device.id))
                            device.reachable && !draft.isEmpty -> act(SendAction.SendNearby(device.id))
                            device.reachable -> {
                                act(SendAction.PickDevice(device.id))
                                local.radarOpen = false
                            }
                            else -> act(SendAction.PickDevice(device.id))
                        }
                    },
                    onLongClick = if (device.pairState == KdePairState.REQUESTED_BY_PEER) ({ act(SendAction.RejectPair(device.id)) }) else null,
                )
            }
        }
        (ui.nearby.browser as? BrowserUi.Serving)?.let { b ->
            wanted += BROWSER_SLOT to { slot: Int ->
                RadarNode(
                    "browser",
                    DestLook(Icons.Outlined.Language, "", b.hotspot?.ssid ?: b.url.removePrefix("http://").substringBefore('/'), ConnectedGreen),
                    reachable = true,
                    slot = slot,
                    onClick = { act(SendAction.OpenSheet(SendSheet.BROWSER_CODE)) },
                )
            }
        }
        (ui.nearby.direct as? DirectUi.Hosting)?.let { d ->
            wanted += DIRECT_SLOT to { slot: Int ->
                RadarNode(
                    "direct",
                    DestLook(Icons.Outlined.WifiTethering, "", d.joined ?: d.code.ssid, if (d.joined != null) ConnectedGreen else null),
                    reachable = true,
                    slot = slot,
                    onClick = { act(SendAction.OpenSheet(SendSheet.DIRECT_CODE)) },
                )
            }
        }
        placeSlots(wanted)
    }
    val sweep = if (motion) {
        rememberInfiniteTransition(label = "send-sweep")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Restart), label = "send-sweep-angle").value
    } else {
        SWEEP_STILL
    }
    val pulse = if (motion) {
        rememberInfiniteTransition(label = "send-pulse")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart), label = "send-pulse-phase").value
    } else {
        0.5f
    }
    val bob = if (motion) {
        rememberInfiniteTransition(label = "send-bob")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(BOB_MS, easing = LinearEasing), RepeatMode.Restart), label = "send-bob-phase").value
    } else {
        0f
    }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val radius = minOf(maxWidth / 2 - 40.dp, maxHeight / 2 - 24.dp).coerceAtLeast(60.dp)
            val ring = kb.divider
            val accent = kb.accent
            val centreR = CENTRE_SIZE / 2
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = radius.toPx()
                scale(1f, SQUASH, pivot = c) {
                    drawCircle(ring.copy(alpha = ring.alpha * 0.9f), r * RING_NEAR * 0.55f, c, style = Stroke(1.dp.toPx()))
                    drawCircle(ring.copy(alpha = ring.alpha * 0.7f), r * RING_NEAR, c, style = Stroke(1.dp.toPx()))
                    drawCircle(ring.copy(alpha = ring.alpha * 0.45f), r * RING_FAR, c, style = Stroke(1.dp.toPx()))
                    rotate(sweep, pivot = c) {
                        drawCircle(
                            Brush.sweepGradient(0f to Color.Transparent, 0.8f to Color.Transparent, 1f to accent.copy(alpha = 0.24f), center = c),
                            r * RING_FAR,
                            c,
                        )
                    }
                    drawCircle(accent.copy(alpha = (1f - pulse) * 0.5f), centreR.toPx() * (1f + pulse * 0.9f), c, style = Stroke(1.5.dp.toPx()))
                }
            }
            if (nodes.isEmpty()) {
                Text(
                    stringResource(R.string.ime_send_nearby_empty_body),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 40.dp, vertical = 6.dp),
                )
            }
            // The pile in the middle: what a tap on a device sends. A tap here goes back to the tray.
            RadarCentre(ui, local, Modifier.align(Alignment.Center))
            nodes.forEachIndexed { i, node ->
                val angle = SLOT_ANGLES[node.slot] * PI.toFloat() / 180f
                val f = if (node.reachable) RING_NEAR else RING_FAR
                val lift = if (node.reachable && motion) sin((bob + i * 0.37f) * 2f * PI.toFloat()) * 3f else 0f
                RadarAvatar(
                    node,
                    Modifier
                        .align(Alignment.Center)
                        .offset {
                            IntOffset(
                                (radius * f * cos(angle)).roundToPx(),
                                ((radius * f * sin(angle) * SQUASH) + lift.dp).roundToPx(),
                            )
                        },
                    index = i,
                    animate = kb.motion.panels,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(if (hub.running) kb.accent else kb.secondaryText)
            Text(
                stringResource(R.string.ime_send_radar_hint),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (anyTransfers(ui, hub)) {
                val count = hub.devices.sumOf { it.transfers.size } + ((ui.nearby.browser as? BrowserUi.Serving)?.transfers?.size ?: 0)
                ToolPanelChip(pluralStringResource(R.plurals.ime_send_transfers_count, count, count)) { local.transfersOpen = true }
            }
            RoundButton(Icons.Outlined.Refresh, stringResource(R.string.ime_send_nearby_refresh), size = 30.dp) { act(SendAction.RefreshDevices) }
            PillButton(stringResource(R.string.ime_send_tray), icon = Icons.Outlined.Inventory2, height = 30.dp) { local.radarOpen = false }
        }
    }
}

/** A device's look without composition, for the node list built in a remember. */
private fun deviceLookPlain(device: KdeDevice): DestLook = DestLook(
    deviceIcon(device.type),
    device.name,
    "",
    when {
        !device.paired -> null
        device.reachable -> ConnectedGreen
        else -> null
    },
)

@Composable
private fun RadarCentre(ui: SendUi, local: SendPanelLocal, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    val draft = ui.draft
    val fg = kb.chipActiveText
    Column(
        modifier
            .size(CENTRE_SIZE)
            .pressScale(source, kb.motion.any)
            .clip(CircleShape)
            .background(kb.chipActive)
            .combinedClickableNoIndication(source) {
                feedback()
                local.radarOpen = false
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (draft.isEmpty) {
            Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.ime_send_tray), color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        } else {
            FilePile(draft.files, animate = kb.motion.panels, tileSize = 18.dp, shown = 3)
            Text(
                pluralStringResource(R.plurals.ime_send_files_count, draft.files.size, draft.files.size),
                color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
            Text(bytesText(context, draft.totalSize), color = fg.copy(alpha = 0.8f), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoIndication(source: MutableInteractionSource, onClick: () -> Unit): Modifier =
    this.combinedClickable(interactionSource = source, indication = null, onClick = onClick)

/** A device on the radar: its icon in a ringed circle (green in reach, dim out of it), the name under, a sub line when there is one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RadarAvatar(node: RadarNode, modifier: Modifier, index: Int, animate: Boolean) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    val ringColor = if (!node.reachable) kb.divider else node.look.subColor ?: kb.accent
    Column(
        modifier
            .popIn(index, animate, key = node.key)
            .graphicsLayer { alpha = if (node.reachable) 1f else 0.55f }
            .pressScale(source, kb.motion.any)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = {
                    feedback()
                    node.onClick()
                },
                onLongClick = node.onLongClick?.let { hold -> { feedback(); hold() } },
            )
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(AVATAR_SIZE)
                .clip(CircleShape)
                .background(kb.chip)
                .border(2.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(node.look.icon, contentDescription = node.look.name, tint = if (node.reachable) kb.chipText else kb.secondaryText, modifier = Modifier.size(20.dp))
        }
        if (node.look.name.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(node.look.name, color = if (node.reachable) kb.suggestionText else kb.secondaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
        }
        if (node.look.sub.isNotEmpty()) {
            Text(node.look.sub, color = node.look.subColor ?: kb.secondaryText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
        } else if (!node.reachable) {
            Text(stringResource(R.string.ime_send_nearby_unreachable), color = kb.secondaryText, fontSize = 9.sp, maxLines = 1, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
        }
    }
}

/** The slot a device prefers, from a hash of its id, so it keeps its place across refreshes. */
private fun slotFor(id: String): Int = ((id.hashCode() % SLOT_ANGLES.size) + SLOT_ANGLES.size) % SLOT_ANGLES.size

/** Hands out slots: each takes its preferred one or the next free going round, so two never overlap. */
private fun placeSlots(wanted: List<Pair<Int, (Int) -> RadarNode>>): List<RadarNode> {
    val taken = BooleanArray(SLOT_ANGLES.size)
    return wanted.sortedBy { it.first }.map { (preferred, make) ->
        var slot = preferred
        var tries = 0
        while (taken[slot] && tries < SLOT_ANGLES.size) {
            slot = (slot + 1) % SLOT_ANGLES.size
            tries++
        }
        taken[slot] = true
        make(slot)
    }
}

// ---- transfers ----

/**
 * The transfers under way and finished, to and from devices and through the
 * browser page, as a sheet over the panel. Clear takes the finished ones away.
 */
@Composable
internal fun TransfersSheet(ui: SendUi, hub: KdeState, act: (SendAction) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val transfers = hub.devices.flatMap { d -> d.transfers.map { d to it } }.sortedByDescending { it.second.id }
    val lan = (ui.nearby.browser as? BrowserUi.Serving)?.transfers.orEmpty()
    SheetCard(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).heightIn(max = 300.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ime_send_transfers), color = kb.popupText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val finished = transfers.any { it.second.state != KdeTransferState.RUNNING && it.second.state != KdeTransferState.WAITING }
            if (finished) {
                ToolPanelChip(stringResource(R.string.ime_send_transfer_clear)) {
                    transfers.map { it.first.id }.distinct().forEach { act(SendAction.ClearTransfers(it)) }
                }
            }
            RoundButton(Icons.Outlined.Close, stringResource(R.string.ime_send_close), size = 30.dp, onClick = onClose)
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(transfers, key = { "t-" + it.first.id + "-" + it.second.id }) { (device, transfer) -> TransferRow(device, transfer, act) }
            items(lan, key = { "l-" + it.id }) { t -> BrowserTransferRow(t, act) }
        }
    }
}

@Composable
internal fun TransferRow(device: KdeDevice, t: KdeTransfer, act: (SendAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val active = t.state == KdeTransferState.WAITING || t.state == KdeTransferState.RUNNING
    ClockCard(modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(fileIcon("", t.fileName), contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.fileName, color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(
                    device.name + " · " + stringResource(
                        when (t.state) {
                            KdeTransferState.WAITING -> R.string.ime_send_transfer_waiting
                            KdeTransferState.RUNNING -> if (t.outgoing) R.string.ime_send_transfer_sending else R.string.ime_send_transfer_receiving
                            KdeTransferState.DONE -> R.string.ime_send_transfer_done
                            KdeTransferState.FAILED -> R.string.ime_send_transfer_failed
                            KdeTransferState.CANCELLED -> R.string.ime_send_transfer_cancelled
                        },
                    ) + if (t.size > 0) " · " + bytesText(context, t.size) else "",
                    color = if (t.state == KdeTransferState.FAILED) FailedRed else null,
                )
            }
            when {
                active -> ToolPanelChip(stringResource(R.string.ime_send_cancel)) { act(SendAction.CancelTransfer(t.id)) }
                !t.outgoing && t.state == KdeTransferState.DONE && t.location.isNotEmpty() ->
                    ToolPanelChip(stringResource(R.string.ime_send_open), selected = true) { act(SendAction.OpenTransfer(t.location, t.fileName)) }
            }
        }
        if (t.state == KdeTransferState.RUNNING) {
            Spacer(Modifier.height(4.dp))
            if (t.size > 0) {
                LinearProgressIndicator(
                    progress = { (t.done.toFloat() / t.size).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = kb.accent,
                    trackColor = kb.divider,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = kb.accent, trackColor = kb.divider)
            }
        }
    }
}

/** A file that went through the browser page (#927), either way. */
@Composable
private fun BrowserTransferRow(t: LanTransfer, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    ClockCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(fileIcon(t.type, t.name), contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(
                    listOf(
                        t.peer,
                        stringResource(
                            when (t.state) {
                                LanTransfer.State.RUNNING -> if (t.incoming) R.string.ime_send_transfer_receiving else R.string.ime_send_transfer_sending
                                LanTransfer.State.DONE -> R.string.ime_send_transfer_done
                                LanTransfer.State.FAILED -> R.string.ime_send_transfer_failed
                            },
                        ),
                        if (t.size > 0) bytesText(context, t.size) else "",
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = if (t.state == LanTransfer.State.FAILED) FailedRed else null,
                )
                if (t.state == LanTransfer.State.RUNNING && t.size > 0) {
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { (t.done.toFloat() / t.size).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                        color = kb.accent,
                        trackColor = kb.divider,
                    )
                }
            }
            val location = t.location
            if (t.incoming && t.state == LanTransfer.State.DONE && location != null) {
                Spacer(Modifier.width(6.dp))
                ToolPanelChip(stringResource(R.string.ime_send_open), selected = true) { act(SendAction.OpenTransfer(location, t.name)) }
            }
        }
    }
}

internal fun deviceIcon(type: KdeDeviceType): ImageVector = when (type) {
    KdeDeviceType.DESKTOP -> Icons.Outlined.Computer
    KdeDeviceType.LAPTOP -> Icons.Outlined.Laptop
    KdeDeviceType.PHONE -> Icons.Outlined.Smartphone
    KdeDeviceType.TABLET -> Icons.Outlined.Tablet
    KdeDeviceType.TV -> Icons.Outlined.Tv
}

/** The destination cards' size, as the mockup had them. */
private val DEST_W = 96.dp
private val DEST_H = 76.dp
private val CENTRE_SIZE = 64.dp
private val AVATAR_SIZE = 40.dp

/**
 * Where a node may sit, in degrees from 3 o'clock: two lobes, left and
 * right, leaving the top and bottom clear so nothing meets the centre or
 * the footer. Browser and Direct prefer the lower corners.
 */
private val SLOT_ANGLES = floatArrayOf(-40f, -13f, 13f, 40f, 140f, 167f, 193f, 220f)
private const val BROWSER_SLOT = 4
private const val DIRECT_SLOT = 3

/** The rings' share of the radius: in reach near, out of reach far. */
private const val RING_NEAR = 0.62f
private const val RING_FAR = 0.95f

/** The radar is wider than tall, so the rings are drawn as ellipses. */
private const val SQUASH = 0.8f
private const val SWEEP_MS = 3_000
private const val SWEEP_STILL = 300f
private const val PULSE_MS = 2_200
private const val BOB_MS = 2_600

package com.wasimaster.wmkeyboard.ime.ui.send

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCodeScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeState
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransfer
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransferState
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import com.wasimaster.wmkeyboard.ime.send.DirectUi
import com.wasimaster.wmkeyboard.ime.send.LinkStage
import com.wasimaster.wmkeyboard.ime.send.ReceiveUi
import com.wasimaster.wmkeyboard.ime.send.SendAction
import com.wasimaster.wmkeyboard.ime.send.SendCallbacks
import com.wasimaster.wmkeyboard.ime.send.SendSheet
import com.wasimaster.wmkeyboard.ime.send.SendUi
import com.wasimaster.wmkeyboard.ime.ui.CaptureCallbacks
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.MotionCrossfade
import com.wasimaster.wmkeyboard.ime.ui.blockTouches
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton

/**
 * The Send files tool's panel (#729). One face at a time, cross-faded: the
 * tray with its destinations (the default), the radar that is Nearby's
 * device view, the sending face a transfer wears, the receipt a finished
 * link becomes, a link being received, or what went wrong. Sheets on a
 * scrim over it; a notice line that fades.
 *
 * Which face is up follows the controller's stage first, then the panel's
 * own toggles ([SendPanelLocal]): the controller owns the upload, the
 * panel owns which view of it the user asked for.
 *
 * Tall while a sheet needs the room (the QR code, the lists). Collapsed over
 * the key rows while one of its boxes (a password, a link, an address) has
 * the keys, the way the KDE panel collapses for its address box.
 */
// The capture bundle is the keyboard's; the panel's boxes draw through
// SearchQueryText, which reads the caret from the same local. Kept as a
// parameter so the dispatcher's shape matches the KDE panel's.
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SendPanel(
    state: KeyboardUiState,
    callbacks: SendCallbacks,
    capture: CaptureCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val kb = LocalKbTheme.current
    val hub by KdeConnectHub.state.collectAsState()
    val local = remember { SendPanelLocal() }
    val typing = state.sendTypingActive
    val outgoing = remember(hub.devices) { outgoingTransfers(hub) }
    val outgoingKey = outgoing.firstOrNull()?.let { (device, _) -> device.id + ":" + outgoing.minOf { it.second.id } }
    // A put-away sending face comes back for the next send.
    LaunchedEffect(outgoingKey) { if (outgoingKey == null) local.hiddenSendKey = null }
    val face = faceFor(ui, local, outgoing, outgoingKey)
    val tall = ui.sheet == SendSheet.QR || ui.sheet == SendSheet.HISTORY || ui.sheet == SendSheet.INSTANCES || ui.sheet == SendSheet.DIRECT_CODE ||
        ui.sheet == SendSheet.BROWSER_CODE || local.transfersOpen
    val screen = LocalConfiguration.current.screenHeightDp.dp
    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        // The radar grows the keyboard a little, as the clock does: at the
        // key rows' height its rings were a small ellipse of the face.
        extraHeight = if (tall) screen else if (face == Face.Radar) RadarExtraHeight else 0.dp,
        compact = typing,
        compactHeight = TypingCompactHeight,
        headerActions = { SendHeader(if (typing) Face.Tray else face, ui, hub, local, act) },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (typing) {
                TypingBar(state, ui, act)
            } else {
                MotionCrossfade(
                    target = face,
                    animate = kb.motion.panels,
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { it.key },
                    label = "send-face",
                ) { shown ->
                    Box(Modifier.fillMaxSize().blockTouches(shown.key != face.key)) {
                        when (shown) {
                            Face.Tray -> TrayFace(ui, hub, selectedDest(ui, hub, local), local, act)
                            Face.Radar -> RadarFace(ui, hub, local, act)
                            is Face.SendingLink -> SendingFace(
                                look = destLook(SendDest.Link, ui, hub),
                                sent = shown.stage.sent,
                                total = shown.stage.total,
                                startedAtMs = shown.stage.startedAtMs,
                                files = ui.draft.files,
                                fileCount = shown.stage.fileCount,
                                note = stringResource(R.string.ime_send_sending_note_link),
                            )
                            is Face.SendingNearby -> {
                                val startedAt = remember(shown.key) { SystemClock.elapsedRealtime() }
                                val device = hub.device(shown.deviceId)
                                SendingFace(
                                    look = destLook(SendDest.Device(shown.deviceId), ui, hub),
                                    sent = shown.sent,
                                    total = shown.total,
                                    startedAtMs = startedAt,
                                    files = ui.draft.files,
                                    fileCount = shown.count,
                                    note = stringResource(R.string.ime_send_sending_note_device, device?.name.orEmpty()),
                                )
                            }
                            is Face.Receipt -> ReceiptFace(ui, shown.stage, local, act)
                            is Face.Receive -> ReceiveFace(ui, shown.receive, act)
                            is Face.Failed -> FailedFace(shown.stage, act)
                        }
                    }
                }
            }
            SendScrim(visible = ui.sheet != null || ui.dataAsk, onDismiss = { act(SendAction.Dismiss) }) {
                SendSheets(ui, act)
            }
            SendScrim(visible = local.transfersOpen, onDismiss = { local.transfersOpen = false }) {
                TransfersSheet(ui, hub, act, onClose = { local.transfersOpen = false }, modifier = Modifier.align(Alignment.Center))
            }
            SendNoticeLine(ui.notice, Modifier.align(Alignment.TopCenter))
        }
    }
}

// ---- which face ----

/** What the body shows; [key] is what makes it a different picture, so a progress tick is not. */
private sealed interface Face {
    val key: String

    data object Tray : Face {
        override val key get() = "tray"
    }

    data object Radar : Face {
        override val key get() = "radar"
    }

    data class SendingLink(val stage: LinkStage.Uploading) : Face {
        override val key get() = "sending-link"
    }

    data class SendingNearby(val deviceId: String, val sent: Long, val total: Long, val count: Int, override val key: String) : Face

    data class Receipt(val stage: LinkStage.Done) : Face {
        override val key get() = "receipt-" + stage.upload.id
    }

    data class Receive(val receive: ReceiveUi) : Face {
        override val key: String
            get() = when {
                receive.error != null -> "receive-error"
                receive.done -> "receive-done"
                receive.remote != null -> "receive-ready"
                receive.needsPassword -> "receive-password"
                else -> "receive-checking"
            }
    }

    data class Failed(val stage: LinkStage.Failed) : Face {
        override val key get() = "failed"
    }
}

private fun faceFor(ui: SendUi, local: SendPanelLocal, outgoing: List<Pair<KdeDevice, KdeTransfer>>, outgoingKey: String?): Face {
    when (val link = ui.link) {
        is LinkStage.Uploading -> return Face.SendingLink(link)
        is LinkStage.Done -> return Face.Receipt(link)
        is LinkStage.Receiving -> return Face.Receive(link.ui)
        is LinkStage.Failed -> return Face.Failed(link)
        LinkStage.Compose -> Unit
    }
    if (outgoingKey != null && local.hiddenSendKey != outgoingKey) {
        val device = outgoing.first().first
        val mine = outgoing.filter { it.first.id == device.id }.map { it.second }
        return Face.SendingNearby(
            deviceId = device.id,
            sent = mine.sumOf { it.done },
            total = if (mine.all { it.size > 0 }) mine.sumOf { it.size } else 0L,
            count = mine.size,
            key = "sending-nearby-$outgoingKey",
        )
    }
    if (local.radarOpen && ui.nearbyAvailable) return Face.Radar
    return Face.Tray
}

/** Files on their way to devices, newest device first. */
private fun outgoingTransfers(hub: KdeState): List<Pair<KdeDevice, KdeTransfer>> = hub.devices.flatMap { d ->
    d.transfers.filter { it.outgoing && (it.state == KdeTransferState.WAITING || it.state == KdeTransferState.RUNNING) }.map { d to it }
}

// ---- headers ----

/** The header row for each face: the title, and the one or two things the face needs up there. */
@Composable
private fun RowScope.SendHeader(face: Face, ui: SendUi, hub: KdeState, local: SendPanelLocal, act: (SendAction) -> Unit) {
    when (face) {
        Face.Tray -> {
            HeaderTitle(stringResource(R.string.ime_send_title))
            RoundButton(Icons.Outlined.History, stringResource(R.string.ime_send_history), size = 30.dp, primary = ui.sheet == SendSheet.HISTORY) {
                act(SendAction.OpenSheet(if (ui.sheet == SendSheet.HISTORY) null else SendSheet.HISTORY))
            }
            Spacer(Modifier.width(6.dp))
            PillButton(stringResource(R.string.ime_send_receive_chip), height = 30.dp) { act(SendAction.LinkFocus) }
            Spacer(Modifier.width(6.dp))
            RoundButton(Icons.Outlined.Settings, stringResource(R.string.ime_send_settings_desc), size = 30.dp) { act(SendAction.OpenSettings) }
        }
        Face.Radar -> {
            val count = hub.devices.count { it.reachable }
            HeaderTitle(
                stringResource(R.string.ime_send_mode_nearby) + " · " +
                    if (count == 0) stringResource(R.string.ime_send_devices_count_zero) else pluralStringResource(R.plurals.ime_send_devices_count, count, count),
            )
            if (ui.canScan) {
                RoundButton(Icons.Outlined.QrCodeScanner, stringResource(R.string.ime_send_direct_scan), size = 30.dp) { act(SendAction.ScanDirect) }
                Spacer(Modifier.width(6.dp))
            }
            if (ui.nearby.direct == DirectUi.Off) {
                RoundButton(Icons.Outlined.WifiTethering, stringResource(R.string.ime_send_direct_host), size = 30.dp) { act(SendAction.HostDirect) }
                Spacer(Modifier.width(6.dp))
            }
            RoundButton(Icons.Outlined.History, stringResource(R.string.ime_send_history), size = 30.dp, primary = ui.sheet == SendSheet.HISTORY) {
                act(SendAction.OpenSheet(if (ui.sheet == SendSheet.HISTORY) null else SendSheet.HISTORY))
            }
        }
        is Face.SendingLink -> {
            HeaderTitle(stringResource(R.string.ime_send_sending_to, hostText(ui.instance)))
            PillButton(stringResource(R.string.ime_send_cancel), height = 30.dp) { act(SendAction.Cancel) }
        }
        is Face.SendingNearby -> {
            HeaderTitle(stringResource(R.string.ime_send_sending_to, hub.device(face.deviceId)?.name.orEmpty()))
            PillButton(stringResource(R.string.ime_send_cancel), height = 30.dp) {
                hub.device(face.deviceId)?.transfers
                    ?.filter { it.outgoing && (it.state == KdeTransferState.WAITING || it.state == KdeTransferState.RUNNING) }
                    ?.forEach { act(SendAction.CancelTransfer(it.id)) }
            }
            Spacer(Modifier.width(6.dp))
            PillButton(stringResource(R.string.ime_send_hide), height = 30.dp) { local.hiddenSendKey = face.key.removePrefix("sending-nearby-") }
        }
        is Face.Receipt -> {
            Row(Modifier.padding(start = 8.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = ConnectedGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val count = face.stage.upload.files.size.coerceAtLeast(1)
                Text(
                    pluralStringResource(R.plurals.ime_send_uploaded_count, count, count),
                    color = ConnectedGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PillButton(stringResource(R.string.ime_send_new), height = 30.dp) { act(SendAction.Reset) }
        }
        is Face.Receive -> {
            HeaderTitle(stringResource(R.string.ime_send_receive_from, hostText(face.receive.link.base)))
            PillButton(stringResource(if (face.receive.done) R.string.ime_send_done else R.string.ime_send_back), height = 30.dp) { act(SendAction.Reset) }
        }
        is Face.Failed -> {
            HeaderTitle(stringResource(R.string.ime_send_failed_title))
            PillButton(stringResource(R.string.ime_send_back), height = 30.dp) { act(SendAction.Reset) }
        }
    }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun RowScope.HeaderTitle(text: String) {
    val kb = LocalKbTheme.current
    Text(
        text,
        color = kb.secondaryText,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 8.dp).weight(1f),
    )
}

/** Room for the box, its label and its chips over the key rows. */
private val TypingCompactHeight = 136.dp

/** Room the radar face grows the keyboard by, so its rings reach most of the width. */
private val RadarExtraHeight = 56.dp

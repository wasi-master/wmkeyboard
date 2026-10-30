package com.wasimaster.wmkeyboard.ime.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCode2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.send.SendInstance
import com.wasimaster.wmkeyboard.core.send.SendInstances
import com.wasimaster.wmkeyboard.core.send.SendReceived
import com.wasimaster.wmkeyboard.core.send.SendUpload
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.send.BrowserUi
import com.wasimaster.wmkeyboard.ime.send.DirectUi
import com.wasimaster.wmkeyboard.ime.send.SendAction
import com.wasimaster.wmkeyboard.ime.send.SendDelivered
import com.wasimaster.wmkeyboard.ime.send.SendShareAlias
import com.wasimaster.wmkeyboard.ime.send.SendSheet
import com.wasimaster.wmkeyboard.ime.send.SendUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.SwipeToDeleteCard
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.clock.SectionLabel

/**
 * The cards drawn over the panel (#729), one at a time, on the scrim: the
 * ways to upload, the QR code, the instance picker, the history, the direct
 * link's code, the browser page's code, and data saving's question. Drawn
 * in-panel rather than in a Popup, which would sit over the app. The
 * transfers sheet lives with the radar (SendNearbyBody.kt).
 */
@Composable
internal fun BoxScope.SendSheets(ui: SendUi, act: (SendAction) -> Unit) {
    when {
        ui.dataAsk -> DataAskCard(act)
        ui.sheet == SendSheet.UPLOAD_MENU -> UploadMenu(ui, act)
        ui.sheet == SendSheet.QR -> QrSheet(ui, act)
        ui.sheet == SendSheet.INSTANCES -> InstancesSheet(ui, act)
        ui.sheet == SendSheet.HISTORY -> HistorySheet(ui, act)
        ui.sheet == SendSheet.DIRECT_CODE -> DirectCodeSheet(ui, act)
        ui.sheet == SendSheet.BROWSER_CODE -> BrowserCodeSheet(ui, act)
        else -> Unit
    }
}

/**
 * The browser page's code (#927). On a Wi-Fi this phone opened it is two
 * codes, one after the other: the Wi-Fi to join, then the page.
 */
@Composable
private fun BoxScope.BrowserCodeSheet(ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val serving = ui.nearby.browser as? BrowserUi.Serving ?: return
    val hotspot = serving.hotspot
    var showWifi by remember(hotspot) { mutableStateOf(hotspot != null) }
    SheetCard(Modifier.align(Alignment.Center).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QrImage(if (showWifi && hotspot != null) hotspot.joinCode else serving.url, Modifier.size(180.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (hotspot != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolPanelChip(stringResource(R.string.ime_send_browser_step_wifi), selected = showWifi) { showWifi = true }
                        ToolPanelChip(stringResource(R.string.ime_send_browser_step_page), selected = !showWifi) { showWifi = false }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (showWifi && hotspot != null) {
                    Text(stringResource(R.string.ime_send_browser_scan_wifi), color = kb.popupText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.ime_send_direct_wifi, hotspot.ssid), color = kb.popupText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hotspot.passphrase.isNotEmpty()) {
                        Text(stringResource(R.string.ime_send_direct_password, hotspot.passphrase), color = kb.popupText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(stringResource(R.string.ime_send_browser_scan_page), color = kb.popupText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ime_send_browser_scan_page_body), color = kb.popupText.copy(alpha = 0.8f), fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(serving.url.removePrefix("http://"), color = kb.accent, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showWifi && hotspot != null) {
                        PillButton(stringResource(R.string.ime_send_browser_next), primary = true, height = 32.dp) { showWifi = false }
                    } else {
                        PillButton(stringResource(R.string.ime_send_copy), icon = Icons.Outlined.ContentCopy, height = 32.dp) { act(SendAction.CopyBrowserLink) }
                        PillButton(stringResource(R.string.ime_send_close), primary = true, height = 32.dp) { act(SendAction.OpenSheet(null)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.DataAskCard(act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    SheetCard(Modifier.align(Alignment.Center).width(280.dp)) {
        Text(stringResource(R.string.ime_send_data_title), color = kb.popupText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ime_send_data_body), color = kb.popupText.copy(alpha = 0.8f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(stringResource(R.string.ime_send_data_refuse), height = 32.dp) { act(SendAction.DataRefuse) }
            Spacer(Modifier.width(6.dp))
            PillButton(stringResource(R.string.ime_send_data_allow), primary = true, height = 32.dp) { act(SendAction.DataAllow) }
        }
    }
}

@Composable
private fun BoxScope.UploadMenu(ui: SendUi, act: (SendAction) -> Unit) {
    SheetCard(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).width(270.dp)) {
        if (ui.hasField) MenuRow(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.ime_send_menu_insert)) { act(SendAction.Upload(SendDelivered.INSERTED)) }
        MenuRow(Icons.Outlined.ContentCopy, stringResource(R.string.ime_send_menu_copy)) { act(SendAction.Upload(SendDelivered.COPIED)) }
        MenuRow(Icons.Outlined.QrCode2, stringResource(R.string.ime_send_menu_qr)) { act(SendAction.Upload(null, showQr = true)) }
        MenuRow(Icons.Outlined.Visibility, stringResource(R.string.ime_send_menu_show)) { act(SendAction.Upload(null)) }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(kb.chipShape())
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kb.popupText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = kb.popupText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BoxScope.QrSheet(ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val upload = (ui.link as? com.wasimaster.wmkeyboard.ime.send.LinkStage.Done)?.upload ?: return
    SheetCard(Modifier.align(Alignment.Center).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QrImage(upload.url, Modifier.size(180.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ime_send_qr_title), color = kb.popupText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.ime_send_qr_body), color = kb.popupText.copy(alpha = 0.8f), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(upload.url.removePrefix("https://"), color = kb.accent, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                ActionBar(
                    listOf(
                        KitAction(stringResource(R.string.ime_send_copy), Icons.Outlined.ContentCopy, primary = true) { act(SendAction.CopyLink) },
                        KitAction(stringResource(R.string.ime_send_share), Icons.Outlined.Share) { act(SendAction.ShareLink) },
                        KitAction(stringResource(R.string.ime_send_close), Icons.Outlined.Close) { act(SendAction.OpenSheet(null)) },
                    ),
                    height = 36.dp,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.DirectCodeSheet(ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val hosting = ui.nearby.direct as? DirectUi.Hosting ?: return
    SheetCard(Modifier.align(Alignment.Center).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QrImage(hosting.code.encodeWeb(SendShareAlias.DOCS_BASE), Modifier.size(180.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Radar(Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (hosting.joined != null) stringResource(R.string.ime_send_direct_joined, hosting.joined) else stringResource(R.string.ime_send_direct_hosting_title),
                        color = kb.popupText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    )
                }
                Text(stringResource(R.string.ime_send_direct_hosting_body), color = kb.popupText.copy(alpha = 0.8f), fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.ime_send_direct_wifi, hosting.code.ssid), color = kb.popupText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hosting.code.passphrase.isNotEmpty()) {
                    Text(stringResource(R.string.ime_send_direct_password, hosting.code.passphrase), color = kb.popupText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton(stringResource(R.string.ime_send_direct_stop), height = 32.dp) { act(SendAction.StopDirect) }
                    PillButton(stringResource(R.string.ime_send_close), primary = true, height = 32.dp) { act(SendAction.OpenSheet(null)) }
                }
            }
        }
    }
}

// ---- instances ----

@Composable
private fun BoxScope.InstancesSheet(ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    SheetCard(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).heightIn(max = 460.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ime_send_instances_title), color = kb.popupText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            RoundButton(Icons.Outlined.Close, stringResource(R.string.ime_send_close), size = 30.dp) { act(SendAction.OpenSheet(null)) }
        }
        Spacer(Modifier.height(4.dp))
        val own = ui.ownInstances
        val public = SendInstances.catalogue.filterNot { it.url in own }
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (own.isNotEmpty()) {
                item(key = "h-own") { SectionLabel(stringResource(R.string.ime_send_instances_own)) }
                items(own, key = { "o-$it" }) { url ->
                    InstanceRow(ui, SendInstance(url), own = true, act)
                }
            }
            item(key = "add") {
                Row(
                    Modifier.fillMaxWidth().clip(kb.chipShape()).clickable { act(SendAction.InstanceFocus) }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ime_send_instance_add), color = kb.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            item(key = "h-public") { SectionLabel(stringResource(R.string.ime_send_instances_public)) }
            items(public, key = { "p-" + it.url }) { instance -> InstanceRow(ui, instance, own = false, act) }
            item(key = "note") {
                Text(
                    stringResource(R.string.ime_send_instances_note),
                    color = kb.popupText.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InstanceRow(ui: SendUi, instance: SendInstance, own: Boolean, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val selected = ui.instance == instance.url
    val status = ui.instanceStatus[instance.url]
    val config = status?.config
    val maxBytes = config?.maxFileSize ?: instance.maxBytes
    val maxDays = config?.let { (it.maxExpireSeconds / 86_400).toInt() } ?: instance.maxDays
    val maxDownloads = config?.maxDownloads ?: instance.maxDownloads
    val meta = buildList {
        if (maxBytes > 0) add(bytesText(context, maxBytes))
        if (maxDays > 0) add(pluralStringResource(R.plurals.ime_send_instance_days, maxDays, maxDays))
        if (maxDownloads > 0) add(pluralStringResource(R.plurals.ime_send_downloads_count, maxDownloads, maxDownloads))
        if (instance.country.isNotEmpty()) add(instance.country)
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(kb.chipShape())
            .background(if (selected) kb.accent.copy(alpha = 0.14f) else kb.popup.copy(alpha = 0f))
            .clickable { act(SendAction.PickInstance(instance.url)) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            when {
                status == null -> kb.popupText.copy(alpha = 0.3f)
                status.reachable -> ConnectedGreen
                else -> FailedRed
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(instance.host, color = kb.popupText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when {
                status != null && !status.reachable -> stringResource(R.string.ime_send_instance_unreachable)
                instance.reference -> listOf(meta, stringResource(R.string.ime_send_instance_reference)).filter { it.isNotEmpty() }.joinToString(" · ")
                meta.isEmpty() && status == null -> stringResource(R.string.ime_send_instance_checking)
                else -> meta
            }
            if (line.isNotEmpty()) Text(line, color = kb.popupText.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
        if (own) {
            Spacer(Modifier.width(6.dp))
            ToolPanelChip(stringResource(R.string.ime_send_instance_remove)) { act(SendAction.InstanceRemove(instance.url)) }
        }
    }
}

// ---- history ----

private enum class HistoryTab { SENT, RECEIVED }

@Composable
private fun BoxScope.HistorySheet(ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    var tab by remember { mutableStateOf(if (ui.uploads.isEmpty() && ui.received.isNotEmpty()) HistoryTab.RECEIVED else HistoryTab.SENT) }
    SheetCard(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).heightIn(max = 460.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolPanelChip(stringResource(R.string.ime_send_history_sent), selected = tab == HistoryTab.SENT) { tab = HistoryTab.SENT }
            ToolPanelChip(stringResource(R.string.ime_send_history_received), selected = tab == HistoryTab.RECEIVED) { tab = HistoryTab.RECEIVED }
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Outlined.Close, stringResource(R.string.ime_send_close), size = 30.dp) { act(SendAction.OpenSheet(null)) }
        }
        Spacer(Modifier.height(6.dp))
        when (tab) {
            HistoryTab.SENT -> if (ui.uploads.isEmpty()) {
                EmptyState(Icons.Outlined.Link, stringResource(R.string.ime_send_history_empty_title), stringResource(R.string.ime_send_history_empty_body), Modifier.height(160.dp))
            } else {
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(ui.uploads, key = { it.instance + it.id }) { upload -> UploadRow(ui, upload, act) }
                    item(key = "hint") {
                        Text(stringResource(R.string.ime_send_history_swipe_hint), color = kb.popupText.copy(alpha = 0.6f), fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(6.dp))
                    }
                }
            }
            HistoryTab.RECEIVED -> if (ui.received.isEmpty()) {
                EmptyState(Icons.Outlined.Link, stringResource(R.string.ime_send_history_received_empty), "", Modifier.height(160.dp))
            } else {
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(ui.received, key = { it.location }) { item -> ReceivedRow(ui, item, act) }
                }
            }
        }
    }
}

@Composable
private fun UploadRow(ui: SendUi, upload: SendUpload, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val left = ((upload.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    val info = ui.uploadInfo[upload.id]
    SwipeToDeleteCard(onDelete = { act(SendAction.DeleteUpload(upload)) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(kb.chipShape())
                .background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
                .clickable { act(SendAction.ShowUpload(upload)) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(fileIcon(upload.type, upload.name), contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(upload.name, color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(
                    listOf(
                        bytesText(context, upload.size),
                        hostText(upload.instance),
                        stringResource(R.string.ime_send_left_short, durationText(left)),
                        if (info != null) pluralStringResource(R.plurals.ime_send_downloads_used, info.downloadLimit, info.downloads, info.downloadLimit) else "",
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolPanelChip(stringResource(R.string.ime_send_copy), icon = Icons.Outlined.ContentCopy) { act(SendAction.CopyUploadLink(upload)) }
                if (ui.hasField) ToolPanelChip(stringResource(R.string.ime_send_insert)) { act(SendAction.InsertUploadLink(upload)) }
            }
        }
    }
}

@Composable
private fun ReceivedRow(ui: SendUi, item: SendReceived, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(kb.chipShape())
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(fileIcon(item.type, item.name), contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaText(listOf(if (item.size > 0) bytesText(context, item.size) else "", item.from).filter { it.isNotEmpty() }.joinToString(" · "))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolPanelChip(stringResource(R.string.ime_send_open), selected = true) { act(SendAction.OpenReceived(item)) }
            if (ui.hasField && ui.fieldTakesImages && item.type.startsWith("image/")) {
                ToolPanelChip(stringResource(R.string.ime_send_insert_file)) { act(SendAction.InsertReceived(item)) }
            }
            ToolPanelChip(stringResource(R.string.ime_send_forget)) { act(SendAction.ForgetReceived(item)) }
        }
    }
}

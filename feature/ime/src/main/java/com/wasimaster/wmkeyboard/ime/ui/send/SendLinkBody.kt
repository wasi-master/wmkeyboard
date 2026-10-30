package com.wasimaster.wmkeyboard.ime.ui.send

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInBrowser
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCode2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Upload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeState
import com.wasimaster.wmkeyboard.core.send.SendInstances
import com.wasimaster.wmkeyboard.core.send.SendServerConfig
import com.wasimaster.wmkeyboard.core.send.SendUpload
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.send.BrowserUi
import com.wasimaster.wmkeyboard.ime.send.DirectUi
import com.wasimaster.wmkeyboard.ime.send.JoinStep
import com.wasimaster.wmkeyboard.ime.send.LinkStage
import com.wasimaster.wmkeyboard.ime.send.ReceiveUi
import com.wasimaster.wmkeyboard.ime.send.SendAction
import com.wasimaster.wmkeyboard.ime.send.SendDelivered
import com.wasimaster.wmkeyboard.ime.send.SendDraftFile
import com.wasimaster.wmkeyboard.ime.send.SendSheet
import com.wasimaster.wmkeyboard.ime.send.SendSource
import com.wasimaster.wmkeyboard.ime.send.SendTextField
import com.wasimaster.wmkeyboard.ime.send.SendUi
import com.wasimaster.wmkeyboard.ime.ui.ConfettiBurst
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.ScrambledText
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockCard
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import kotlinx.coroutines.delay

/*
 * The faces of the Send files panel (#729): the tray with its destinations
 * and sources, the sending face a transfer wears, the receipt a finished
 * link becomes, a link being received, what went wrong, and the one box
 * that has the keys while something is typed. The panel picks which is up
 * (see SendPanel.kt); each face draws what the controller publishes.
 */

// ---- the box while the keys type into it ----

/**
 * The panel collapsed over the key rows: the one box that has the keys, with
 * what can be done to it. A password shows dots unless asked to show itself.
 */
@Composable
internal fun TypingBar(state: KeyboardUiState, ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val entry = state.sendText ?: return
    val field = entry.field
    val masked = field == SendTextField.PASSWORD && !ui.passwordVisible
    val shown = if (masked) "•".repeat(entry.text.length) else entry.text
    val receiving = ui.link is LinkStage.Receiving
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(
            stringResource(
                when (field) {
                    SendTextField.PASSWORD -> if (receiving) R.string.ime_send_password_enter else R.string.ime_send_password
                    SendTextField.LINK -> R.string.ime_send_paste_link
                    SendTextField.INSTANCE -> R.string.ime_send_instance_add
                },
            ),
            color = kb.secondaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(kb.chipShape())
                .frostGlass(kb, kb.chipShape(), FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, kb.chipShape())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (field == SendTextField.PASSWORD) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            SearchQueryText(
                query = shown,
                placeholder = stringResource(
                    when (field) {
                        SendTextField.PASSWORD -> R.string.ime_send_password_hint
                        SendTextField.LINK -> R.string.ime_send_link_hint
                        SendTextField.INSTANCE -> R.string.ime_send_instance_hint
                    },
                ),
                active = true,
                textColor = kb.chipText,
                placeholderColor = kb.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (field == SendTextField.PASSWORD) {
                Spacer(Modifier.width(6.dp))
                ToolPanelChip(
                    stringResource(if (ui.passwordVisible) R.string.ime_send_password_hide else R.string.ime_send_password_show),
                    icon = if (ui.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                ) { act(SendAction.PasswordToggleVisible) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (field == SendTextField.PASSWORD && !receiving) {
                ToolPanelChip(stringResource(R.string.ime_send_password_generate)) { act(SendAction.PasswordGenerate) }
            }
            if (receiving && (ui.link as LinkStage.Receiving).ui.wrongPassword) {
                Text(stringResource(R.string.ime_send_wrong_password), color = FailedRed, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.ime_send_cancel), height = 30.dp) { act(SendAction.Dismiss) }
            PillButton(
                stringResource(
                    when (field) {
                        SendTextField.PASSWORD -> R.string.ime_send_done
                        SendTextField.LINK -> R.string.ime_send_receive_go
                        SendTextField.INSTANCE -> R.string.ime_send_instance_add
                    },
                ),
                primary = true,
                height = 30.dp,
                enabled = entry.text.isNotBlank() || field == SendTextField.PASSWORD,
            ) { act(SendAction.TextDone) }
        }
    }
}

// ---- the tray ----

private enum class Picking { EXPIRY, DOWNLOADS }

/**
 * The compose face: TO as one carousel of destination cards, the four
 * sources as a row of chips, and the tray under them, a dashed trough that
 * holds the pile, its size against the cap, the link's limits and the Send
 * circle. The circle sends to whichever card is chosen.
 */
@Composable
internal fun TrayFace(ui: SendUi, hub: KdeState, dest: SendDest, local: SendPanelLocal, act: (SendAction) -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = 10.dp)) {
        TinyLabel(stringResource(R.string.ime_send_to), Modifier.padding(start = 14.dp, top = 2.dp, bottom = 4.dp))
        DestinationRow(ui, hub, dest, local, act)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceChip(stringResource(R.string.ime_send_source_files), Modifier.weight(1f)) { act(SendAction.AddFrom(SendSource.FILES)) }
            SourceChip(stringResource(R.string.ime_send_source_gallery), Modifier.weight(1f)) { act(SendAction.AddFrom(SendSource.GALLERY)) }
            SourceChip(stringResource(R.string.ime_send_source_clipboard), Modifier.weight(1f)) { act(SendAction.AddFrom(SendSource.CLIPBOARD)) }
            SourceChip(stringResource(R.string.ime_send_source_field), Modifier.weight(1f)) { act(SendAction.AddFrom(SendSource.FIELD_TEXT)) }
        }
        Spacer(Modifier.height(8.dp))
        // Capped: a sheet grows the panel to the screen, and the trough
        // should not stretch into a wall behind it.
        Tray(ui, hub, dest, local, act, Modifier.weight(1f).heightIn(max = TrayMaxHeight).fillMaxWidth().padding(horizontal = 12.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tray(ui: SendUi, hub: KdeState, dest: SendDest, local: SendPanelLocal, act: (SendAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val animate = kb.motion.panels
    val draft = ui.draft
    val overCap = dest == SendDest.Link && ui.overCap && !draft.isEmpty
    val showStatus = overCap || nearbyStatusVisible(ui, hub, dest)
    var picking by remember { mutableStateOf<Picking?>(null) }
    Column(modifier.dashedTrough(kb).padding(horizontal = 12.dp, vertical = 8.dp)) {
        AnimatedVisibility(visible = showStatus) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (overCap) {
                    SendInstances.roomFor(draft.totalSize).take(4).forEach { instance ->
                        ToolPanelChip(stringResource(R.string.ime_send_try_instance, instance.host)) { act(SendAction.PickInstance(instance.url)) }
                    }
                } else {
                    NearbyStatusChips(ui, hub, dest, local, act)
                }
            }
        }
        if (draft.isEmpty) {
            EmptyTray(ui, hub, dest, act, Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        Spacer(Modifier.weight(1f))
        if (local.pileOpen) {
            // The pile spread out: each tile with its ✕, and the way back.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 4.dp, top = 4.dp)) {
                    items(draft.files, key = { it.id }) { file ->
                        FileTile(file, Modifier.animateItem(), compact = true, tileSize = 46.dp, onRemove = { act(SendAction.RemoveFile(file.id)) })
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolPanelChip(stringResource(R.string.ime_send_done), selected = true) { local.pileOpen = false }
                    ToolPanelChip(stringResource(R.string.ime_send_clear)) {
                        act(SendAction.ClearFiles)
                        local.pileOpen = false
                    }
                }
            }
            return@Column
        }
        val total = bytesText(context, draft.totalSize)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            FilePile(draft.files, animate, onClick = { local.pileOpen = true })
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.ime_send_files_count, draft.files.size, draft.files.size) + " · " +
                        when {
                            overCap -> stringResource(R.string.ime_send_over_cap, hostText(ui.instance))
                            dest == SendDest.Link -> stringResource(R.string.ime_send_total_of, total, bytesText(context, ui.maxBytes))
                            else -> total
                        },
                    color = if (overCap) FailedRed else kb.suggestionText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dest == SendDest.Link) {
                    Spacer(Modifier.height(5.dp))
                    SizeMeter(
                        fraction = if (ui.maxBytes > 0) draft.totalSize.toFloat() / ui.maxBytes else 0f,
                        over = ui.overCap,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(5.dp))
                    LinkOptions(ui, picking, animate, act) { picking = it }
                } else {
                    Spacer(Modifier.height(4.dp))
                    MetaText(destLook(dest, ui, hub).let { it.name + " · " + it.sub })
                }
            }
            Spacer(Modifier.width(8.dp))
            val send = sendCircleFor(dest, ui, hub, act)
            SendCircle(send, count = draft.files.size)
        }
    }
}

/** The link's limits as chips: expiry, downloads, password, each picking in place. */
@Composable
private fun LinkOptions(ui: SendUi, picking: Picking?, animate: Boolean, act: (SendAction) -> Unit, onPick: (Picking?) -> Unit) {
    val draft = ui.draft
    val config = ui.config ?: SendServerConfig.DEFAULT
    AnimatedContent(
        targetState = picking,
        transitionSpec = { fadeIn(tween(if (animate) 140 else 0)) togetherWith fadeOut(tween(if (animate) 100 else 0)) },
        label = "send-options",
    ) { pick ->
        // Clipped: a scrolled chip otherwise draws on under the Send circle.
        Row(
            Modifier.fillMaxWidth().clipToBounds().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (pick) {
                null -> {
                    ToolPanelChip(durationText(draft.expireSeconds)) { onPick(Picking.EXPIRY) }
                    ToolPanelChip(pluralStringResource(R.plurals.ime_send_downloads_count, draft.downloads, draft.downloads)) { onPick(Picking.DOWNLOADS) }
                    ToolPanelChip(
                        when {
                            !draft.passwordOn -> stringResource(R.string.ime_send_password)
                            draft.password.isEmpty() -> stringResource(R.string.ime_send_password_hint)
                            ui.passwordVisible -> draft.password
                            else -> stringResource(R.string.ime_send_meta_password)
                        },
                        selected = draft.passwordOn,
                    ) { act(if (draft.passwordOn) SendAction.PasswordFocus else SendAction.PasswordOn(true)) }
                    if (draft.passwordOn) {
                        ToolPanelChip(stringResource(R.string.ime_send_remove)) { act(SendAction.PasswordOn(false)) }
                    }
                }
                Picking.EXPIRY -> {
                    config.expireChoices.forEach { seconds ->
                        ToolPanelChip(durationText(seconds), selected = seconds == draft.expireSeconds) {
                            act(SendAction.SetExpiry(seconds))
                            onPick(null)
                        }
                    }
                    ToolPanelChip(stringResource(R.string.ime_send_back)) { onPick(null) }
                }
                Picking.DOWNLOADS -> {
                    config.downloadChoices.forEach { count ->
                        ToolPanelChip(count.toString(), selected = count == draft.downloads) {
                            act(SendAction.SetDownloads(count))
                            onPick(null)
                        }
                    }
                    ToolPanelChip(stringResource(R.string.ime_send_back)) { onPick(null) }
                }
            }
        }
    }
}

/** The empty trough: what it is for, and, for a destination that works with nothing in it, the way to start it. */
@Composable
private fun EmptyTray(ui: SendUi, hub: KdeState, dest: SendDest, act: (SendAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        when (dest) {
            SendDest.Link, is SendDest.Device -> {
                Icon(Icons.Outlined.Upload, contentDescription = null, tint = kb.secondaryText.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(stringResource(R.string.ime_send_tray_empty), color = kb.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
                Text(
                    if (dest == SendDest.Link) {
                        stringResource(R.string.ime_send_tray_encrypted)
                    } else {
                        stringResource(R.string.ime_send_sending_note_device, destLook(dest, ui, hub).name)
                    },
                    color = kb.secondaryText.copy(alpha = 0.7f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SendDest.Browser -> {
                // Two short lines and a low pill: the trough is about 70 dp
                // inside on the phone, and the pill was cut off at its floor.
                Text(stringResource(R.string.ime_send_browser_idle), color = kb.secondaryText, fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
                if (ui.nearby.browser == BrowserUi.Off) {
                    Spacer(Modifier.height(4.dp))
                    PillButton(stringResource(R.string.ime_send_start_page), icon = Icons.Outlined.OpenInBrowser, primary = true, height = 26.dp) { act(SendAction.StartBrowser) }
                }
            }
            SendDest.Direct -> {
                Text(stringResource(R.string.ime_send_direct_body), color = kb.secondaryText, fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
                if (ui.nearby.direct == DirectUi.Off) {
                    Spacer(Modifier.height(4.dp))
                    PillButton(stringResource(R.string.ime_send_direct_host), icon = Icons.Outlined.WifiTethering, primary = true, height = 26.dp) { act(SendAction.HostDirect) }
                }
            }
        }
    }
}

/** What the Send circle does for a destination. */
private class SendPress(val icon: ImageVector, val enabled: Boolean, val onTap: () -> Unit, val onHold: (() -> Unit)? = null)

private fun sendCircleFor(dest: SendDest, ui: SendUi, hub: KdeState, act: (SendAction) -> Unit): SendPress = when (dest) {
    SendDest.Link -> SendPress(Icons.AutoMirrored.Outlined.Send, enabled = !ui.overCap, onTap = { act(SendAction.Upload()) }, onHold = { act(SendAction.OpenSheet(SendSheet.UPLOAD_MENU)) })
    is SendDest.Device -> {
        val device = hub.device(dest.id)
        SendPress(Icons.AutoMirrored.Outlined.Send, enabled = device != null && device.paired && device.reachable, onTap = { act(SendAction.SendNearby(dest.id)) })
    }
    SendDest.Browser -> when (ui.nearby.browser) {
        BrowserUi.Off, is BrowserUi.Failed -> SendPress(Icons.Outlined.OpenInBrowser, enabled = true, onTap = { act(SendAction.StartBrowser) })
        BrowserUi.Starting -> SendPress(Icons.Outlined.OpenInBrowser, enabled = false, onTap = {})
        is BrowserUi.Serving -> SendPress(Icons.Outlined.QrCode2, enabled = true, onTap = { act(SendAction.OpenSheet(SendSheet.BROWSER_CODE)) })
    }
    SendDest.Direct -> when (val d = ui.nearby.direct) {
        DirectUi.Off, is DirectUi.Failed -> SendPress(Icons.Outlined.WifiTethering, enabled = true, onTap = { act(SendAction.HostDirect) })
        DirectUi.Starting -> SendPress(Icons.Outlined.WifiTethering, enabled = false, onTap = {})
        is DirectUi.Hosting -> SendPress(Icons.Outlined.QrCode2, enabled = true, onTap = { act(SendAction.OpenSheet(SendSheet.DIRECT_CODE)) })
        is DirectUi.Joining -> SendPress(Icons.AutoMirrored.Outlined.Send, enabled = d.step == JoinStep.LINKED && d.code.name.isNotEmpty(), onTap = {
            hub.connected.firstOrNull { it.name == d.code.name }?.let { act(SendAction.SendNearby(it.id)) }
        })
    }
}

/** The accent circle that sends: a tap does the destination's thing, a hold (for a link) opens the ways to. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendCircle(press: SendPress, count: Int) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    Box(Modifier.size(52.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .align(Alignment.BottomStart)
                .pressScale(source, kb.motion.any)
                .clip(CircleShape)
                .background(kb.accent.copy(alpha = if (press.enabled) 1f else 0.4f))
                .combinedClickable(
                    enabled = press.enabled,
                    interactionSource = source,
                    indication = null,
                    onClick = {
                        feedback()
                        press.onTap()
                    },
                    onLongClick = press.onHold?.let { hold -> { feedback(); hold() } },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(press.icon, contentDescription = stringResource(R.string.ime_send_upload), tint = maxContrastOn(kb.accent), modifier = Modifier.size(22.dp))
        }
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(kb.popup)
                    .chipBorder(kb, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(count.toString(), color = kb.popupText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- sending ----

/**
 * A transfer under way: the destination in the middle of a ring that fills
 * with it, the pile flying into it, the percent large, what has gone of
 * what, the speed and what is left, and a note along the bottom. The link
 * upload and a nearby send wear the same face.
 */
@Composable
internal fun SendingFace(
    look: DestLook,
    sent: Long,
    total: Long,
    startedAtMs: Long,
    files: List<SendDraftFile>,
    fileCount: Int,
    note: String,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    val fraction = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
    val elapsed = ((now - startedAtMs) / 1000f).coerceAtLeast(0.5f)
    val speed = sent / elapsed
    val eta = if (speed > 1 && total > sent) ((total - sent) / speed).toLong() else -1L
    val motion = kb.motion.panels && !kb.reduceMotion
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ring = minOf(RING_SIZE, maxHeight - 90.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            ProgressRing(fraction = if (sent > 0) fraction else null, size = ring, stroke = 6.dp) {
                DestBadge(look, size = ring - 38.dp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (sent > 0) "${(fraction * 100).toInt()}%" else "…",
                    color = kb.suggestionText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                Column {
                    MetaText(stringResource(R.string.ime_send_total_of, bytesText(context, sent), bytesText(context, total)))
                    if (sent > 0) {
                        MetaText(
                            stringResource(R.string.ime_send_speed, bytesText(context, speed.toLong())) +
                                if (eta >= 0) " · " + stringResource(R.string.ime_send_eta, durationText(eta)) else "",
                        )
                    } else {
                        MetaText(stringResource(R.string.ime_send_starting))
                    }
                }
            }
        }
        if (motion) {
            // The pile on its way in: each tile rises to the ring, shrinking, a beat after the one before, again and again.
            val travel = maxHeight / 2 - 13.dp
            FlyingPile(files, fileCount, travel = travel, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp))
        }
        Text(
            note,
            color = kb.secondaryText.copy(alpha = 0.8f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun FlyingPile(files: List<SendDraftFile>, count: Int, travel: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shown = count.coerceIn(1, FLYERS)
    val transition = rememberInfiniteTransition(label = "send-fly")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
        for (i in 0 until shown) {
            val t by transition.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(FLY_MS, easing = FastOutSlowInEasing), RepeatMode.Restart, initialStartOffset = StartOffset(i * FLY_STAGGER_MS)),
                label = "send-fly-$i",
            )
            val file = files.getOrNull(i)
            Box(
                Modifier
                    .graphicsLayer {
                        translationY = -travel.toPx() * t
                        val s = 1f - 0.8f * t
                        scaleX = s
                        scaleY = s
                        alpha = if (t < 0.7f) 1f else (1f - (t - 0.7f) / 0.3f).coerceIn(0f, 1f)
                    },
            ) {
                if (file != null) {
                    FileTile(file, onRemove = null, compact = true, tileSize = 36.dp)
                } else {
                    Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(kb.accent.copy(alpha = 0.3f)))
                }
            }
        }
    }
}

// ---- the receipt ----

/**
 * The link, ready, as a receipt that rises in: the QR code at the left, the
 * host small over the hash in monospace, chips for what it allows, the bar
 * of ways to send it on, Manage behind a chip, and the latest earlier
 * upload under RECENT as the door to History.
 */
@Composable
internal fun ReceiptFace(ui: SendUi, stage: LinkStage.Done, local: SendPanelLocal, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val upload = stage.upload
    val appear = remember(upload.id) { MutableTransitionState(kb.reduceMotion).apply { targetState = true } }
    val animate = kb.motion.panels
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
            AnimatedVisibility(
                visibleState = appear,
                enter = slideInVertically(tween(if (kb.reduceMotion) 0 else 260)) { it / 3 } + fadeIn(tween(if (kb.reduceMotion) 0 else 180)),
            ) {
                ReceiptCard(ui, stage, local, act)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.popIn(1, animate, key = upload.id)) {
                ActionBar(
                    buildList {
                        if (ui.hasField) {
                            add(KitAction(stringResource(R.string.ime_send_insert_link), Icons.AutoMirrored.Outlined.Send, primary = true) { act(SendAction.InsertLink) })
                            add(KitAction(stringResource(R.string.ime_send_copy), Icons.Outlined.ContentCopy) { act(SendAction.CopyLink) })
                        } else {
                            add(KitAction(stringResource(R.string.ime_send_copy), Icons.Outlined.ContentCopy, primary = true) { act(SendAction.CopyLink) })
                        }
                        add(KitAction(stringResource(R.string.ime_send_share), Icons.Outlined.Share) { act(SendAction.ShareLink) })
                        add(KitAction(stringResource(R.string.ime_send_qr), Icons.Outlined.QrCode2) { act(SendAction.OpenSheet(SendSheet.QR)) })
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).popIn(2, animate, key = upload.id),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolPanelChip(stringResource(R.string.ime_send_manage), selected = stage.manageOpen) { act(SendAction.ManageOpen(!stage.manageOpen)) }
                if (stage.manageOpen) {
                    MetaText(stringResource(R.string.ime_send_downloads))
                    (ui.config ?: SendServerConfig.DEFAULT).downloadChoices.forEach { count ->
                        ToolPanelChip(count.toString(), selected = count == upload.downloadLimit) { act(SendAction.SetUploadDownloads(upload, count)) }
                    }
                    if (stage.password != null) {
                        ToolPanelChip(stringResource(R.string.ime_send_copy_password)) { act(SendAction.CopyPassword) }
                    }
                    ToolPanelChip(stringResource(R.string.ime_send_delete)) { act(SendAction.DeleteUpload(upload)) }
                }
            }
            Spacer(Modifier.weight(1f))
            val recent = ui.uploads.firstOrNull { it.id != upload.id }
            if (recent != null) {
                Column(Modifier.popIn(3, animate, key = upload.id)) {
                    TinyLabel(stringResource(R.string.ime_send_recent), Modifier.padding(start = 2.dp, bottom = 4.dp))
                    RecentRow(ui, recent) { act(SendAction.OpenSheet(SendSheet.HISTORY)) }
                }
            }
        }
        if (kb.motion.toolResults && stage.delivered != null) {
            ConfettiBurst(key = upload.id, accent = kb.accent, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ReceiptCard(ui: SendUi, stage: LinkStage.Done, local: SendPanelLocal, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val upload = stage.upload
    val shape = kb.cardShape()
    val bare = upload.url.removePrefix("https://").removePrefix("http://")
    val host = bare.substringBefore('/')
    val tail = bare.substring(host.length).trimStart('/')
    val pathHead = if (tail.contains('/')) tail.substringBefore('/') + "/" else ""
    val hash = tail.removePrefix(pathHead).replace("/#", "#").trimStart('/')
    val left = ((upload.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    val info = ui.uploadInfo[upload.id]
    val downloads = if (info != null) {
        pluralStringResource(R.plurals.ime_send_downloads_used, info.downloadLimit, info.downloads, info.downloadLimit)
    } else {
        pluralStringResource(R.plurals.ime_send_downloads_used, upload.downloadLimit, 0, upload.downloadLimit)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
            .chipBorder(kb, shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrImage(
            upload.url,
            sizePx = 256,
            modifier = Modifier.size(84.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act(SendAction.OpenSheet(SendSheet.QR)) },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(host + "/" + pathHead, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                hash,
                color = kb.suggestionText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                ReceiptChip(stringResource(R.string.ime_send_meta_expires, durationText(left)))
                ReceiptChip(downloads)
                val password = stage.password
                if (password != null) {
                    ScrambledText(if (local.passwordShown) password else "•".repeat(password.length.coerceAtMost(10)), animate = kb.motion.panels) { shown ->
                        ReceiptChip(shown, icon = Icons.Outlined.Lock, mono = true) { local.passwordShown = !local.passwordShown }
                    }
                } else if (upload.password) {
                    ReceiptChip(stringResource(R.string.ime_send_meta_password), icon = Icons.Outlined.Lock)
                }
            }
            when (stage.delivered) {
                SendDelivered.INSERTED -> MetaText(stringResource(R.string.ime_send_inserted_line), Modifier.padding(top = 3.dp), color = ConnectedGreen)
                SendDelivered.COPIED -> MetaText(stringResource(R.string.ime_send_copied_line), Modifier.padding(top = 3.dp), color = ConnectedGreen)
                null -> Unit
            }
        }
    }
}

/** A small flat chip on the receipt; with [onClick] it is a toggle. */
@Composable
private fun ReceiptChip(text: String, icon: ImageVector? = null, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        Modifier
            .clip(shape)
            .background(kb.chip)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = kb.chipText, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, color = kb.chipText, fontSize = 10.sp, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 1)
    }
}

/** The latest earlier upload: a glyph, its name, and how it stands; a tap opens History. */
@Composable
private fun RecentRow(ui: SendUi, upload: SendUpload, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val now = System.currentTimeMillis()
    val info = ui.uploadInfo[upload.id]
    val gone = upload.expiresAt <= now || (info != null && info.downloads >= info.downloadLimit)
    ClockCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(kb.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(fileIcon(upload.type, upload.name), contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(upload.name, color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(
                    listOf(
                        DateUtils.getRelativeTimeSpanString(upload.createdAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                        if (info != null) pluralStringResource(R.plurals.ime_send_downloads_used, info.downloadLimit, info.downloads, info.downloadLimit) else hostText(upload.instance),
                        if (gone) stringResource(R.string.ime_send_recent_gone) else stringResource(R.string.ime_send_left_short, durationText(((upload.expiresAt - now) / 1000).coerceAtLeast(0))),
                    ).joinToString(" · "),
                )
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(14.dp))
        }
    }
}

// ---- receiving ----

@Composable
internal fun ReceiveFace(ui: SendUi, r: ReceiveUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val host = hostText(r.link.base)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(kb.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(
                    if (r.needsPassword && r.remote == null) Icons.Outlined.Lock else fileIcon(r.remote?.metadata?.type.orEmpty(), r.remote?.metadata?.name.orEmpty()),
                    contentDescription = null,
                    tint = kb.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        r.done -> stringResource(R.string.ime_send_saved_title)
                        r.remote != null -> r.remote.metadata.name
                        else -> stringResource(R.string.ime_send_receive_title)
                    },
                    color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                MetaText(stringResource(R.string.ime_send_receive_from, host))
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            r.error != null -> {
                Text(stringResource(errorText(r.error)), color = kb.suggestionText, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.ime_send_retry), primary = true, height = 32.dp) { act(SendAction.Receive(r.link.url)) }
            }
            r.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                Radar(Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                MetaText(stringResource(R.string.ime_send_checking, host))
            }
            r.done -> {
                MetaText(stringResource(R.string.ime_send_saved_where))
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.saved.forEachIndexed { i, item ->
                        ClockCard(Modifier.fillMaxWidth().popIn(i, kb.motion.panels), padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(fileIcon(item.type, item.name), contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = kb.chipText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.size > 0) MetaText(bytesText(context, item.size))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ToolPanelChip(stringResource(R.string.ime_send_open), selected = true) { act(SendAction.OpenReceived(item)) }
                                    if (ui.hasField && (ui.fieldTakesImages && item.type.startsWith("image/"))) {
                                        ToolPanelChip(stringResource(R.string.ime_send_insert_file)) { act(SendAction.InsertReceived(item)) }
                                    }
                                    ToolPanelChip(stringResource(R.string.ime_send_share_file)) { act(SendAction.ShareReceived(item)) }
                                }
                            }
                        }
                    }
                }
            }
            r.remote == null && r.needsPassword -> {
                Text(
                    stringResource(if (r.wrongPassword) R.string.ime_send_wrong_password else R.string.ime_send_needs_password),
                    color = if (r.wrongPassword) FailedRed else kb.suggestionText,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.ime_send_password_enter), icon = Icons.Outlined.Lock, primary = true, height = 32.dp) { act(SendAction.PasswordFocus) }
            }
            r.remote != null -> {
                val meta = r.remote.metadata
                MetaText(
                    bytesText(context, meta.size) +
                        (if (meta.isArchive) " · " + pluralStringResource(R.plurals.ime_send_files_count, meta.files.size, meta.files.size) else "") +
                        " · " + stringResource(R.string.ime_send_meta_expires, durationText(r.remote.ttlMs / 1000)),
                )
                if (r.remote.finalDownload) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.ime_send_final_download), color = kb.accent, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                val progress = r.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { if (progress.second > 0) (progress.first.toFloat() / progress.second).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = kb.accent,
                        trackColor = kb.divider,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetaText(stringResource(R.string.ime_send_total_of, bytesText(context, progress.first), bytesText(context, progress.second)), Modifier.weight(1f))
                        ToolPanelChip(stringResource(R.string.ime_send_cancel)) { act(SendAction.Cancel) }
                    }
                } else {
                    PillButton(stringResource(R.string.ime_send_download), primary = true, height = 32.dp) { act(SendAction.Download) }
                }
            }
        }
    }
}

// ---- failed ----

@Composable
internal fun FailedFace(stage: LinkStage.Failed, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(FailedRed.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = FailedRed, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ime_send_failed_title), color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(errorText(stage.error)), color = kb.secondaryText, fontSize = 12.sp)
            }
        }
        if (stage.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                stage.suggestions.take(5).forEach { instance ->
                    ToolPanelChip(stringResource(R.string.ime_send_try_instance, instance.host)) { act(SendAction.PickInstance(instance.url)) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ActionBar(
            listOf(
                KitAction(stringResource(R.string.ime_send_retry), Icons.AutoMirrored.Outlined.Send, primary = true) { act(SendAction.Upload()) },
                KitAction(stringResource(R.string.ime_send_back), Icons.AutoMirrored.Outlined.ArrowBack) { act(SendAction.Reset) },
            ),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** The sending face's ring, at most; on a short panel it is the height instead. */
private val RING_SIZE = 150.dp

/** The trough's tallest: what it needs on a normal panel, and no more when a sheet grows the panel. */
private val TrayMaxHeight = 170.dp

/** Tiles that fly into the ring at once. */
private const val FLYERS = 3
private const val FLY_MS = 1_600
private const val FLY_STAGGER_MS = 350

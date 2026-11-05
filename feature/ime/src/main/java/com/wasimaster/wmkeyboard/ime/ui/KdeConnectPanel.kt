package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardReturn
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeOff
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AttachFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Computer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Laptop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Phonelink
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Repeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RepeatOne
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shuffle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tablet
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeClick
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeClipboardPause
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeConnectEngine
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDeviceType
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeLoop
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeMediaAction
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairFailure
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairState
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePlayer
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeSpecialKey
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeState
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransfer
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransferState
import com.wasimaster.wmkeyboard.core.media.MediaSnapshot
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KdeAction
import com.wasimaster.wmkeyboard.ime.KdeModKey
import com.wasimaster.wmkeyboard.ime.KdeTab
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import kotlinx.coroutines.delay

/** How tall the panel stays while the key rows are up under it, typing on the computer. */
internal val KdeTypingCompactHeight = 168.dp

/**
 * The KDE Connect tool (issue #285).
 *
 * Two sources of truth, kept apart on purpose. Everything that arrives over
 * the network — devices, pairing, players, volumes, transfers — is read here
 * from [KdeConnectHub.state] directly; the keyboard's own [KeyboardUiState]
 * carries only what the *keys* need (which tab, which buffer). So a pointer
 * drag goes finger → hub → socket without touching the state the key rows are
 * compared against on every keystroke, and a player's position tick redraws
 * this panel and nothing else.
 *
 * The panel is a ladder of states, each of which says what to do next:
 * off → looking for devices → pairing → connected (tabs) — with "not
 * reachable" folding back into the device list rather than being a dead end.
 */
@Composable
internal fun KdeConnectPanel(state: KeyboardUiState, capture: CaptureCallbacks) {
    val kb = LocalKbTheme.current
    val hub by KdeConnectHub.state.collectAsState()
    val settings = state.settings.kdeConnect
    val onKde = capture.onKde

    val stage = when {
        state.deviceLocked -> KdeStage(KdeStep.LOCKED)
        !settings.enabled -> KdeStage(KdeStep.OFF)
        !InternetPermission.granted -> KdeStage(KdeStep.NO_INTERNET)
        else -> {
            val device = hub.device(state.kde.deviceId)?.takeIf { it.paired && it.reachable }
                ?: hub.connected.firstOrNull()
            val pairing = hub.devices.firstOrNull {
                it.pairState == KdePairState.REQUESTED || it.pairState == KdePairState.REQUESTED_BY_PEER
            }
            when {
                pairing != null -> KdeStage(KdeStep.PAIRING, pairing = pairing)
                device == null || state.kde.showDevices -> KdeStage(KdeStep.DEVICES, device = device)
                else -> KdeStage(KdeStep.CONNECTED, device = device)
            }
        }
    }
    // Read through a State so a step on its way out compares against the
    // step now showing, whichever version of this lambda it was given.
    val currentStep = rememberUpdatedState(stage.step)

    CompactLines { Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        // A step of the ladder cross-fades into the next (found → pairing →
        // connected). Keyed on the step alone: the hub's steady stream of
        // updates — a battery, a player's position — re-renders the step in
        // place. The step fading out takes no taps, so an Accept already
        // accepted is not sent twice.
        MotionCrossfade(
            target = stage,
            animate = kb.motion.any,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.step },
            label = "kdeStep",
        ) { shown ->
            Box(Modifier.fillMaxSize().blockTouches(shown.step != currentStep.value)) {
                when (shown.step) {
                    KdeStep.LOCKED -> CenterNotice(kb, Icons.Outlined.Phonelink, stringResource(R.string.ime_kde_locked), "")
                    KdeStep.OFF -> Intro(kb) { onKde(KdeAction.TurnOn) }
                    KdeStep.NO_INTERNET -> CenterNotice(
                        kb, Icons.Outlined.Phonelink, stringResource(OfflineBuild.refused()), "",
                    )
                    KdeStep.PAIRING -> shown.pairing?.let { PairingCard(kb, it) }
                    KdeStep.DEVICES -> DeviceList(kb, state, hub, shown.device != null, onKde)
                    KdeStep.CONNECTED -> shown.device?.let { Connected(kb, state, it, capture) }
                }
            }
        }
    } }
}

/** The rungs of the panel's ladder. */
private enum class KdeStep { LOCKED, OFF, NO_INTERNET, PAIRING, DEVICES, CONNECTED }

/** One rung and the device it is about, carried so a rung fading out still has its own. */
private data class KdeStage(
    val step: KdeStep,
    val device: KdeDevice? = null,
    val pairing: KdeDevice? = null,
)

/**
 * A device-list row's arrival, departure and move: a computer turning up on
 * the network fades in, one that pairs slides from Nearby up to Paired.
 */
private fun LazyItemScope.rowMotion(animate: Boolean): Modifier =
    if (!animate) {
        Modifier
    } else {
        Modifier.animateItem(
            fadeInSpec = tween(ROW_FADE_IN_MS),
            placementSpec = spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            fadeOutSpec = tween(ROW_FADE_OUT_MS),
        )
    }

/**
 * Material's bodyLarge, which every `Text` in the keyboard inherits, sets a
 * 24 sp line height: a 10–12 sp line then sits in a box twice its size, so
 * wrapped hints spread apart and a button grows to 40 dp around one line.
 */
@Composable
private fun CompactLines(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified), content = content)
}

/**
 * The whole header after the back button: the tool's name, the device chip
 * (opens the list) and settings. The panel passes no title of its own so the
 * name lives here, where the chip can be the one that gives way: a long
 * computer name ellipsizes before it pushes the settings button off the row.
 */
@Composable
internal fun RowScope.KdeHeaderActions(state: KeyboardUiState, onKde: (KdeAction) -> Unit) = CompactLines {
    val kb = LocalKbTheme.current
    val hub by KdeConnectHub.state.collectAsState()
    val enabled = state.settings.kdeConnect.enabled
    val device = hub.device(state.kde.deviceId)?.takeIf { it.paired && it.reachable } ?: hub.connected.firstOrNull()
    Text(
        stringResource(R.string.ime_tool_kde_connect),
        color = kb.secondaryText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp),
    )
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
        if (enabled && (device != null || hub.devices.isNotEmpty())) {
            DeviceChip(kb, state, device, onKde)
        }
    }
    Spacer(Modifier.width(6.dp))
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).background(kb.toolCircle)
            .clickable { onKde(KdeAction.OpenSettings) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Settings, stringResource(R.string.ime_kde_settings), tint = kb.toolbarIcon, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DeviceChip(kb: KbTheme, state: KeyboardUiState, device: KdeDevice?, onKde: (KdeAction) -> Unit) {
    Row(
        modifier = Modifier
            .clip(kb.chipShape())
            .frostGlass(kb, kb.chipShape(), FrostSurface.CHIPS)
            .background(if (state.kde.showDevices) kb.chipActive else kb.chip)
            .chipBorder(kb, kb.chipShape())
            .clickable { onKde(KdeAction.ShowDevices(!state.kde.showDevices)) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (state.kde.showDevices) kb.chipActiveText else kb.chipText
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (device != null) ConnectedGreen else kb.secondaryText),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            device?.name ?: stringResource(R.string.ime_kde_devices),
            color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        device?.battery?.let { battery ->
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.ime_kde_battery, battery.charge),
                color = tint.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1,
            )
        }
    }
}

// ---- off ----

@Composable
private fun Intro(kb: KbTheme, onTurnOn: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Phonelink, null, tint = kb.accent, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ime_kde_intro_title), color = kb.suggestionText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ime_kde_intro_body), color = kb.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ime_kde_intro_network), color = kb.secondaryText.copy(alpha = 0.8f), fontSize = 11.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        AccentButton(kb, stringResource(R.string.ime_kde_turn_on), onClick = onTurnOn)
    }
}

// ---- devices ----

@Composable
private fun DeviceList(kb: KbTheme, state: KeyboardUiState, hub: KdeState, canGoBack: Boolean, onKde: (KdeAction) -> Unit) {
    val engine = KdeConnectHub.engine
    Column(Modifier.fillMaxSize()) {
        if (state.kde.hostEntry) {
            Row(
                Modifier.fillMaxWidth().clip(kb.chipShape()).background(kb.chip).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchQueryText(
                    query = state.kde.hostDraft,
                    placeholder = stringResource(R.string.ime_kde_address_placeholder),
                    active = true,
                    textColor = kb.chipText,
                    placeholderColor = kb.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                ToolPanelChip(stringResource(R.string.ime_kde_pair_cancel)) { onKde(KdeAction.HostEntry(false)) }
                Spacer(Modifier.width(6.dp))
                ToolPanelChip(stringResource(R.string.ime_kde_address_add), selected = true, enabled = state.kde.hostDraft.isNotBlank()) {
                    onKde(KdeAction.SubmitHost)
                }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Radar(kb, Modifier.size(18.dp), animate = !state.settings.reduceMotion)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ime_kde_searching), color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ToolPanelChip(stringResource(R.string.ime_kde_refresh)) { KdeConnectHub.refresh() }
            Spacer(Modifier.width(6.dp))
            ToolPanelChip(stringResource(R.string.ime_kde_add_by_address)) { onKde(KdeAction.HostEntry(true)) }
            // A WMK Desktop pairing code; the scanner pairs when it sees one.
            Spacer(Modifier.width(6.dp))
            ToolPanelChip(stringResource(R.string.ime_kde_scan_code)) { onKde(KdeAction.ScanPairCode) }
        }
        val motion = kb.motion.any
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val paired = hub.paired
            val nearby = hub.nearby
            if (paired.isNotEmpty()) {
                item(key = "h-paired") { SectionLabel(kb, stringResource(R.string.ime_kde_section_paired), rowMotion(motion)) }
                items(paired, key = { "p-" + it.id }) { device ->
                    DeviceRow(
                        kb, device,
                        status = stringResource(if (device.reachable) R.string.ime_kde_status_connected else R.string.ime_kde_status_unreachable),
                        onClick = if (device.reachable) ({ onKde(KdeAction.SelectDevice(device.id)) }) else null,
                        modifier = rowMotion(motion),
                    ) {
                        ToolPanelChip(stringResource(R.string.ime_kde_unpair)) { engine?.unpair(device.id) }
                    }
                }
            }
            if (nearby.isNotEmpty()) {
                item(key = "h-nearby") { SectionLabel(kb, stringResource(R.string.ime_kde_section_nearby), rowMotion(motion)) }
                items(nearby, key = { "n-" + it.id }) { device ->
                    DeviceRow(
                        kb, device,
                        status = device.pairFailure?.let { stringResource(failureText(it)) }.orEmpty(),
                        onClick = null,
                        modifier = rowMotion(motion),
                    ) {
                        ToolPanelChip(stringResource(R.string.ime_kde_pair), selected = true) { engine?.requestPair(device.id) }
                    }
                }
            }
            item(key = "help") {
                Column(rowMotion(motion).padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
                    if (!hub.listening && hub.running) {
                        Text(stringResource(R.string.ime_kde_not_listening), color = kb.secondaryText, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(stringResource(R.string.ime_kde_help_title), color = kb.suggestionText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.ime_kde_help_body), color = kb.secondaryText, fontSize = 11.sp)
                }
            }
        }
        if (canGoBack) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                ToolPanelChip(stringResource(R.string.ime_kde_run_close)) { onKde(KdeAction.ShowDevices(false)) }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    kb: KbTheme,
    device: KdeDevice,
    status: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(kb.keyRadiusDp.dp)).background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(deviceIcon(device.type), null, tint = if (device.reachable) kb.accent else kb.secondaryText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = kb.chipText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(status, device.address).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun PairingCard(kb: KbTheme, device: KdeDevice) {
    val engine = KdeConnectHub.engine
    val incoming = device.pairState == KdePairState.REQUESTED_BY_PEER
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(device.pairDeadlineMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val total = if (incoming) 25_000f else 30_000f
    val left = ((device.pairDeadlineMs - now) / total).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            stringResource(if (incoming) R.string.ime_kde_pair_incoming else R.string.ime_kde_pair_waiting, device.name),
            color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // Spaced in pairs: eight hex digits are compared by eye, across two screens.
            device.verificationKey.orEmpty().chunked(2).joinToString(" "),
            color = kb.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        )
        Text(stringResource(R.string.ime_kde_pair_code_hint), color = kb.secondaryText, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { left },
            modifier = Modifier.width(180.dp).height(3.dp).clip(CircleShape),
            color = kb.accent,
            trackColor = kb.divider,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (incoming) {
                ToolPanelChip(stringResource(R.string.ime_kde_pair_reject)) { engine?.cancelPair(device.id) }
                AccentButton(kb, stringResource(R.string.ime_kde_pair_accept)) { engine?.acceptPair(device.id) }
            } else {
                ToolPanelChip(stringResource(R.string.ime_kde_pair_cancel)) { engine?.cancelPair(device.id) }
            }
        }
    }
}

// ---- connected ----

@Composable
private fun Connected(kb: KbTheme, state: KeyboardUiState, device: KdeDevice, capture: CaptureCallbacks) {
    val engine = KdeConnectHub.engine ?: return
    val onKde = capture.onKde
    val typing = state.kdeTypingActive
    val tabs = remember(device.accepts) {
        buildList {
            add(KdeTab.INPUT)
            if (device.canMedia || device.canVolume) add(KdeTab.MEDIA)
            add(KdeTab.SEND)
            if (device.canRun) add(KdeTab.RUN)
            if (device.canPresent || device.canMouse) add(KdeTab.SLIDES)
            add(KdeTab.DEVICE)
        }
    }
    val tab = state.kde.tab.takeIf { it in tabs } ?: KdeTab.INPUT
    // The ring's CHIPS region is the tab rail.
    PanelFocusTarget(PanelMode.KDE_CONNECT, count = if (typing) 0 else tabs.size, columns = tabs.size, region = FocusRegion.CHIPS) { index ->
        tabs.getOrNull(index)?.let { onKde(KdeAction.SetTab(it)) }
    }
    val focusedTab = state.focusedIndex(FocusRegion.CHIPS)
    Column(Modifier.fillMaxSize()) {
        if (!typing) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, t ->
                    ToolPanelChip(
                        stringResource(tabLabel(t)),
                        selected = t == tab,
                        modifier = Modifier.focusRing(focusedTab == index, kb.chipShape()),
                    ) { onKde(KdeAction.SetTab(t)) }
                }
            }
        }
        Notice(kb, state)
        // The touchpads inside take every move they see, so a swipe only
        // changes the tab off them.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .swipeTabs(tabs.indexOf(tab), tabs.size, enabled = !typing) { onKde(KdeAction.SetTab(tabs[it])) },
        ) {
            when (tab) {
                KdeTab.INPUT -> InputTab(kb, state, device, engine, capture)
                KdeTab.MEDIA -> MediaTab(kb, device, engine)
                KdeTab.SEND -> SendTab(kb, device, engine, onKde)
                KdeTab.RUN -> RunTab(kb, device, engine)
                KdeTab.SLIDES -> SlidesTab(kb, device, engine)
                KdeTab.DEVICE -> DeviceTab(kb, state, device, engine, onKde)
            }
        }
    }
}

/** One line of feedback that fades by itself. */
@Composable
private fun Notice(kb: KbTheme, state: KeyboardUiState) {
    val text = state.kde.notice
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state.kde.noticeAtMs) {
        shown = text.isNotEmpty() && SystemClock.uptimeMillis() - state.kde.noticeAtMs < NOTICE_MS
        if (shown) {
            delay(NOTICE_MS)
            shown = false
        }
    }
    if (shown) {
        Text(
            text, color = kb.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        )
    }
}

// ---- input ----

@Composable
private fun InputTab(kb: KbTheme, state: KeyboardUiState, device: KdeDevice, engine: KdeConnectEngine, capture: CaptureCallbacks) {
    if (!device.canMouse) {
        CenterNotice(kb, Icons.Outlined.Computer, stringResource(R.string.ime_kde_input_unsupported), "")
        return
    }
    val settings = state.settings.kdeConnect
    val pad = remember(device.id, engine) {
        KdePadCallbacks(
            onMove = { dx, dy -> engine.mousepad.move(device.id, dx, dy) },
            onScroll = { dx, dy -> engine.mousepad.scroll(device.id, dx, dy) },
            onClick = { engine.mousepad.click(device.id, it) },
            onHold = { engine.mousepad.hold(device.id, it) },
        )
    }
    val typing = state.kdeTypingActive
    Column(Modifier.fillMaxSize()) {
        if (typing) {
            KeyStrip(kb, state, capture)
            Spacer(Modifier.height(4.dp))
            EchoLine(kb, state, capture)
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            KdeRemotePad(
                settings, pad,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                hint = if (typing) "" else stringResource(R.string.ime_kde_pad_hint),
                subHint = if (typing) "" else stringResource(R.string.ime_kde_pad_hint_gestures),
                description = stringResource(R.string.ime_kde_pad_desc),
            )
            if (!typing) {
                Spacer(Modifier.width(6.dp))
                Column(Modifier.width(54.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PadButton(kb, "L", stringResource(R.string.ime_kde_click_left), Modifier.weight(1f)) { engine.mousepad.click(device.id, KdeClick.LEFT) }
                    PadButton(kb, "M", stringResource(R.string.ime_kde_click_middle), Modifier.weight(0.7f)) { engine.mousepad.click(device.id, KdeClick.MIDDLE) }
                    PadButton(kb, "R", stringResource(R.string.ime_kde_click_right), Modifier.weight(1f)) { engine.mousepad.click(device.id, KdeClick.RIGHT) }
                }
            }
        }
        if (!typing) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AccentButton(kb, stringResource(R.string.ime_kde_type_on_pc), enabled = device.acceptsKeys) { capture.onKde(KdeAction.SetTyping(true)) }
                Spacer(Modifier.weight(1f))
                QuickKey(kb, stringResource(R.string.ime_kde_key_esc)) { capture.onKdeKey(KdeSpecialKey.ESCAPE, state.kde.mods) }
                QuickKey(kb, stringResource(R.string.ime_kde_key_tab)) { capture.onKdeKey(KdeSpecialKey.TAB, state.kde.mods) }
                QuickKey(kb, stringResource(R.string.ime_kde_key_enter), icon = Icons.AutoMirrored.Outlined.KeyboardReturn) {
                    capture.onKdeKey(KdeSpecialKey.ENTER, state.kde.mods)
                }
            }
        }
    }
}

/** Esc, Tab, the sticky modifiers, the arrows and the rest: what a phone layout has no keys for. */
@Composable
private fun KeyStrip(kb: KbTheme, state: KeyboardUiState, capture: CaptureCallbacks) {
    val mods = state.kde.mods
    fun key(k: KdeSpecialKey) = capture.onKdeKey(k, mods)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickKey(kb, stringResource(R.string.ime_kde_key_esc)) { key(KdeSpecialKey.ESCAPE) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_tab)) { key(KdeSpecialKey.TAB) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_ctrl), armed = mods.ctrl) { capture.onKde(KdeAction.ToggleModifier(KdeModKey.CTRL)) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_alt), armed = mods.alt) { capture.onKde(KdeAction.ToggleModifier(KdeModKey.ALT)) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_super), armed = mods.meta) { capture.onKde(KdeAction.ToggleModifier(KdeModKey.META)) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_shift), armed = mods.shift) { capture.onKde(KdeAction.ToggleModifier(KdeModKey.SHIFT)) }
        QuickKey(kb, icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft) { key(KdeSpecialKey.LEFT) }
        QuickKey(kb, icon = Icons.Outlined.KeyboardArrowUp) { key(KdeSpecialKey.UP) }
        QuickKey(kb, icon = Icons.Outlined.KeyboardArrowDown) { key(KdeSpecialKey.DOWN) }
        QuickKey(kb, icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { key(KdeSpecialKey.RIGHT) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_del)) { key(KdeSpecialKey.DELETE) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_home)) { key(KdeSpecialKey.HOME) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_end)) { key(KdeSpecialKey.END) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_pgup)) { key(KdeSpecialKey.PAGE_UP) }
        QuickKey(kb, stringResource(R.string.ime_kde_key_pgdn)) { key(KdeSpecialKey.PAGE_DOWN) }
        for (n in 1..12) {
            val f = KdeSpecialKey.fromCode(KdeSpecialKey.F1.code + n - 1) ?: continue
            QuickKey(kb, "F$n") { key(f) }
        }
    }
}

/** What has been typed on this line, so there is something to look at besides the monitor. */
@Composable
private fun EchoLine(kb: KbTheme, state: KeyboardUiState, capture: CaptureCallbacks) {
    val compose = state.settings.kdeConnect.composeMode
    Row(
        Modifier.fillMaxWidth().clip(kb.chipShape()).background(kb.chip).padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            // Live mode keeps a long tail for diffing; the eye needs only the end of it.
            query = if (compose) state.kde.line else state.kde.line.takeLast(ECHO_CHARS),
            placeholder = stringResource(if (compose) R.string.ime_kde_compose_placeholder else R.string.ime_kde_type_placeholder),
            active = true,
            textColor = kb.chipText,
            placeholderColor = kb.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        ToolPanelChip(stringResource(R.string.ime_kde_compose), selected = compose) { capture.onKde(KdeAction.SetCompose(!compose)) }
        if (compose) {
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(stringResource(R.string.ime_kde_compose_send), selected = true, enabled = state.kde.line.isNotEmpty()) { capture.onKdeSend(false) }
        }
        Spacer(Modifier.width(4.dp))
        ToolPanelChip("✕") { capture.onKde(KdeAction.SetTyping(false)) }
    }
}

// ---- media ----

@Composable
private fun MediaTab(kb: KbTheme, device: KdeDevice, engine: KdeConnectEngine) {
    val mpris = device.mpris
    val player = mpris.current
    Column(Modifier.fillMaxSize()) {
        if (mpris.players.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (name in mpris.players) {
                    ToolPanelChip(name, selected = name == mpris.selected) { engine.mpris.select(device.id, name) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (player == null && !device.canMedia) {
                // The tab is here for the volume alone: KDE Connect on macOS has no player plugin.
                CenterNotice(kb, Icons.Outlined.Computer, stringResource(R.string.ime_kde_media_unsupported_title), stringResource(R.string.ime_kde_media_unsupported_body))
            } else if (player == null) {
                CenterNotice(kb, Icons.Outlined.Computer, stringResource(R.string.ime_kde_media_empty_title), stringResource(R.string.ime_kde_media_empty_body))
            } else {
                val art = remember(player.albumArtUrl, mpris.artRevision) {
                    engine.mpris.art(player.albumArtUrl)?.let(::decodeAlbumArt)
                }
                val snapshot = remember(player, art) { player.toSnapshot(device.name, art) }
                NowPlaying(
                    kb, snapshot,
                    onPlayPause = { engine.mpris.action(device.id, player.name, KdeMediaAction.PLAY_PAUSE) },
                    onNext = { engine.mpris.action(device.id, player.name, KdeMediaAction.NEXT) },
                    onPrevious = { engine.mpris.action(device.id, player.name, KdeMediaAction.PREVIOUS) },
                    onSeek = { engine.mpris.seekTo(device.id, player.name, it) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            if (player?.shuffle != null) {
                SmallToggle(kb, Icons.Outlined.Shuffle, stringResource(R.string.ime_kde_shuffle), on = player.shuffle == true) {
                    engine.mpris.setShuffle(device.id, player.name, player.shuffle != true)
                }
                Spacer(Modifier.width(4.dp))
            }
            if (player?.loop != null) {
                SmallToggle(
                    kb, if (player.loop == KdeLoop.TRACK) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                    stringResource(R.string.ime_kde_loop), on = player.loop != KdeLoop.NONE,
                ) {
                    val next = when (player.loop) {
                        KdeLoop.NONE -> KdeLoop.PLAYLIST
                        KdeLoop.PLAYLIST -> KdeLoop.TRACK
                        else -> KdeLoop.NONE
                    }
                    engine.mpris.setLoop(device.id, player.name, next)
                }
                Spacer(Modifier.width(8.dp))
            }
            // The computer's own output if it has told us about one; else the player's volume.
            val sink = device.volume.sinks.firstOrNull { it.isDefault } ?: device.volume.sinks.firstOrNull()
            when {
                sink != null -> {
                    SmallToggle(
                        kb, if (sink.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                        stringResource(if (sink.muted) R.string.ime_kde_volume_unmute else R.string.ime_kde_volume_mute), on = sink.muted,
                    ) { engine.volume.setMuted(device.id, sink.name, !sink.muted) }
                    VolumeSlider(kb, sink.fraction.coerceIn(0f, 1f), Modifier.weight(1f)) { engine.volume.setVolume(device.id, sink.name, it) }
                }
                player != null && player.volume >= 0 -> {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = kb.secondaryText, modifier = Modifier.size(18.dp))
                    VolumeSlider(kb, player.volume / 100f, Modifier.weight(1f)) { engine.mpris.setVolume(device.id, player.name, (it * 100).toInt()) }
                }
                else -> Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VolumeSlider(kb: KbTheme, value: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    // Dragged locally and sent as it moves; the computer's echo would otherwise
    // fight the thumb on every step.
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    Slider(
        value = if (dragging) local else value,
        onValueChange = {
            dragging = true
            local = it
            onChange(it)
        },
        onValueChangeFinished = { dragging = false },
        colors = SliderDefaults.colors(thumbColor = kb.accent, activeTrackColor = kb.accent, inactiveTrackColor = kb.divider),
        modifier = modifier.height(24.dp).padding(horizontal = 6.dp),
    )
}

private fun KdePlayer.toSnapshot(deviceName: String, art: android.graphics.Bitmap?): MediaSnapshot {
    // The panel extrapolates against elapsedRealtime; the engine stamps wall time.
    val age = (System.currentTimeMillis() - positionAtMs).coerceAtLeast(0)
    return MediaSnapshot(
        title = title,
        artist = artist,
        album = album,
        packageName = "",
        appLabel = "$name · $deviceName",
        art = art,
        playing = playing,
        positionMs = positionMs,
        durationMs = lengthMs.coerceAtLeast(0),
        positionUpdateTimeMs = SystemClock.elapsedRealtime() - age,
        speed = 1f,
        canNext = canNext,
        canPrev = canPrevious,
        canSeek = canSeek && lengthMs > 0,
    )
}

// ---- send ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendTab(kb: KbTheme, device: KdeDevice, engine: KdeConnectEngine, onKde: (KdeAction) -> Unit) {
    if (device.transfers.isEmpty()) {
        // Nothing to list yet: the three ways to send take the room the list would.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.widthIn(max = SEND_TILES_MAX_WIDTH).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SendTile(kb, Icons.Outlined.ContentPaste, stringResource(R.string.ime_kde_send_clipboard), enabled = device.canClipboard, accent = true) {
                    onKde(KdeAction.SendClipboard)
                }
                SendTile(kb, Icons.Outlined.TextFields, stringResource(R.string.ime_kde_send_field), enabled = device.canShare) {
                    onKde(KdeAction.SendFieldText)
                }
                SendTile(kb, Icons.Outlined.AttachFile, stringResource(R.string.ime_kde_send_files), enabled = device.canShare) {
                    onKde(KdeAction.PickFiles)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (device.canShare) R.string.ime_kde_send_empty else R.string.ime_kde_send_unsupported),
                color = kb.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolPanelChip(stringResource(R.string.ime_kde_send_clipboard), selected = true, enabled = device.canClipboard) { onKde(KdeAction.SendClipboard) }
            ToolPanelChip(stringResource(R.string.ime_kde_send_field), enabled = device.canShare) { onKde(KdeAction.SendFieldText) }
            ToolPanelChip(stringResource(R.string.ime_kde_send_files), enabled = device.canShare) { onKde(KdeAction.PickFiles) }
            if (device.transfers.any { it.state != KdeTransferState.RUNNING && it.state != KdeTransferState.WAITING }) {
                ToolPanelChip(stringResource(R.string.ime_kde_transfer_clear)) { engine.share.clearFinished(device.id) }
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(device.transfers.asReversed(), key = { it.id }) { transfer -> TransferRow(kb, transfer, engine, onKde) }
        }
    }
}

/** One of the Send tab's empty-state buttons: an icon over its label, a third of the row. */
@Composable
private fun RowScope.SendTile(kb: KbTheme, icon: ImageVector, label: String, enabled: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val fill = if (accent) kb.accent else kb.chip
    val tint = if (accent) maxContrastOn(kb.accent) else kb.chipText
    Column(
        Modifier.weight(1f).height(SEND_TILE_HEIGHT).alpha(if (enabled) 1f else BUTTON_DISABLED_ALPHA)
            .clip(RoundedCornerShape(kb.keyRadiusDp.dp)).background(fill)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TransferRow(kb: KbTheme, t: KdeTransfer, engine: KdeConnectEngine, onKde: (KdeAction) -> Unit) {
    val active = t.state == KdeTransferState.RUNNING || t.state == KdeTransferState.WAITING
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(kb.keyRadiusDp.dp)).background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.fileName, color = kb.chipText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        when (t.state) {
                            KdeTransferState.WAITING -> R.string.ime_kde_transfer_waiting
                            KdeTransferState.RUNNING -> if (t.outgoing) R.string.ime_kde_transfer_sending else R.string.ime_kde_transfer_receiving
                            KdeTransferState.DONE -> if (t.outgoing) R.string.ime_kde_transfer_done_sent else R.string.ime_kde_transfer_done_received
                            KdeTransferState.FAILED -> R.string.ime_kde_transfer_failed
                            KdeTransferState.CANCELLED -> R.string.ime_kde_transfer_cancelled
                        },
                    ),
                    color = if (t.state == KdeTransferState.FAILED) FailedRed else kb.secondaryText, fontSize = 11.sp,
                )
            }
            when {
                active -> ToolPanelChip(stringResource(R.string.ime_kde_transfer_cancel)) { engine.share.cancel(t.id) }
                !t.outgoing && t.state == KdeTransferState.DONE && t.location.isNotEmpty() ->
                    ToolPanelChip(stringResource(R.string.ime_kde_transfer_open), selected = true) { onKde(KdeAction.Open(t.location, t.fileName)) }
            }
        }
        if (t.state == KdeTransferState.RUNNING) {
            Spacer(Modifier.height(4.dp))
            if (t.size > 0) {
                LinearProgressIndicator(
                    progress = { (t.done.toFloat() / t.size).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = kb.accent, trackColor = kb.divider,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = kb.accent, trackColor = kb.divider)
            }
        }
    }
}

// ---- run ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunTab(kb: KbTheme, device: KdeDevice, engine: KdeConnectEngine) {
    val commands = device.commands
    val run = commands.running
    Column(Modifier.fillMaxSize()) {
        if (run != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run.command.ifEmpty { stringResource(R.string.ime_kde_run_running) },
                    color = kb.suggestionText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        when {
                            !run.finished -> R.string.ime_kde_run_running
                            run.success -> R.string.ime_kde_run_done
                            else -> R.string.ime_kde_run_failed
                        },
                    ),
                    color = if (run.finished && !run.success) FailedRed else kb.secondaryText, fontSize = 11.sp,
                )
                Spacer(Modifier.width(6.dp))
                if (!run.finished) ToolPanelChip(stringResource(R.string.ime_kde_run_stop)) { engine.commands.stop(device.id) }
                Spacer(Modifier.width(4.dp))
                ToolPanelChip(stringResource(R.string.ime_kde_run_close)) { engine.commands.dismissOutput(device.id) }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(kb.keyRadiusDp.dp))
                    .background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f)).padding(8.dp),
            ) {
                Text(
                    run.lines.takeLast(OUTPUT_LINES_SHOWN).joinToString("\n"),
                    color = kb.chipText, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState(), reverseScrolling = true),
                )
            }
            return@Column
        }
        if (commands.list.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.ime_kde_run_empty_title), color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.ime_kde_run_empty_body), color = kb.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                if (commands.canAdd) {
                    Spacer(Modifier.height(8.dp))
                    AccentButton(kb, stringResource(R.string.ime_kde_run_setup)) { engine.commands.setup(device.id) }
                }
            }
            return@Column
        }
        FlowRow(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (command in commands.list) {
                Box(
                    Modifier.clip(RoundedCornerShape(kb.keyRadiusDp.dp)).background(kb.chip)
                        .clickable { engine.commands.run(device.id, command.key) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) { Text(command.name, color = kb.chipText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
            }
            if (commands.canAdd) ToolPanelChip(stringResource(R.string.ime_kde_run_setup)) { engine.commands.setup(device.id) }
        }
    }
}

// ---- slides ----

@Composable
private fun SlidesTab(kb: KbTheme, device: KdeDevice, engine: KdeConnectEngine) {
    fun key(k: KdeSpecialKey) = engine.mousepad.special(device.id, k)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BigKey(kb, Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_kde_slides_previous), Modifier.weight(1f).fillMaxHeight()) { key(KdeSpecialKey.PAGE_UP) }
        Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (device.canPresent) {
                KdePointerPad(
                    onMove = { dx, dy -> engine.presenter.move(device.id, dx, dy) },
                    onStop = { engine.presenter.stop(device.id) },
                    modifier = Modifier.weight(1f),
                    hint = stringResource(R.string.ime_kde_slides_pointer),
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ime_kde_slides_no_pointer), color = kb.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                // F5 starts a slideshow in Impress, PowerPoint, Okular and most browsers' PDF viewers.
                ToolPanelChip(stringResource(R.string.ime_kde_slides_start)) { key(KdeSpecialKey.F5) }
                ToolPanelChip(stringResource(R.string.ime_kde_slides_end)) { key(KdeSpecialKey.ESCAPE) }
            }
        }
        BigKey(kb, Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_kde_slides_next), Modifier.weight(1f).fillMaxHeight(), accent = true) { key(KdeSpecialKey.PAGE_DOWN) }
    }
}

// ---- device ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceTab(kb: KbTheme, state: KeyboardUiState, device: KdeDevice, engine: KdeConnectEngine, onKde: (KdeAction) -> Unit) {
    val failed = stringResource(R.string.ime_kde_notice_failed)
    val pinged = stringResource(R.string.ime_kde_notice_ping_sent)
    val ringing = stringResource(R.string.ime_kde_notice_ringing)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Icon(deviceIcon(device.type), null, tint = kb.accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val battery = device.battery?.let {
                    stringResource(if (it.charging) R.string.ime_kde_battery_charging else R.string.ime_kde_battery, it.charge)
                }
                Text(listOfNotNull(device.address.takeIf { it.isNotEmpty() }, battery).joinToString(" · "), color = kb.secondaryText, fontSize = 11.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (device.canPing) ToolPanelChip(stringResource(R.string.ime_kde_ping)) {
                onKde(KdeAction.Notice(if (engine.ping.ping(device.id)) pinged else failed))
            }
            if (device.canRing) ToolPanelChip(stringResource(R.string.ime_kde_ring)) {
                onKde(KdeAction.Notice(if (engine.findDevice.ring(device.id)) ringing else failed))
            }
            if (device.canLock) {
                val locked = device.lock.known && device.lock.locked
                ToolPanelChip(stringResource(if (locked) R.string.ime_kde_unlock else R.string.ime_kde_lock), selected = locked) {
                    if (!engine.lockDevice.setLocked(device.id, !locked)) onKde(KdeAction.Notice(failed))
                }
            }
            ToolPanelChip(stringResource(R.string.ime_kde_devices)) { onKde(KdeAction.ShowDevices(true)) }
            ToolPanelChip(stringResource(R.string.ime_kde_settings)) { onKde(KdeAction.OpenSettings) }
            ToolPanelChip(stringResource(R.string.ime_kde_unpair)) { engine.unpair(device.id) }
        }
        if (device.canClipboard) {
            Spacer(Modifier.height(8.dp))
            ClipboardPause(kb, state.settings.kdeConnect.clipboardPauses, device.id, onKde)
        }
        if (device.fingerprint.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ime_kde_fingerprint, device.fingerprint.take(FINGERPRINT_SHOWN)),
                color = kb.secondaryText.copy(alpha = 0.8f), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Pause and resume clipboard sharing (#918): with this computer or all of
 * them, for a while or until resumed. While paused the row says until when,
 * and redraws as that time comes, since nothing else changes when it does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipboardPause(kb: KbTheme, pauses: Map<String, Long>, deviceId: String, onKde: (KdeAction) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pauses) {
        while (true) {
            now = System.currentTimeMillis()
            val end = KdeClipboardPause.nextEnd(pauses, now) ?: break
            delay((end - now).coerceAtLeast(0) + 50)
        }
    }
    val until = KdeClipboardPause.pausedUntil(pauses, deviceId, now)
    var choosing by remember(deviceId) { mutableStateOf(false) }
    var all by remember(deviceId) { mutableStateOf(false) }
    if (until > 0) {
        val everyone = KdeClipboardPause.allPausedUntil(pauses, now) >= until
        val time = if (until == KdeClipboardPause.FOREVER) null else formatTime(until)
        val line = when {
            everyone && time == null -> stringResource(R.string.ime_kde_clipboard_paused_all_forever)
            everyone -> stringResource(R.string.ime_kde_clipboard_paused_all_until, time!!)
            time == null -> stringResource(R.string.ime_kde_clipboard_paused_forever)
            else -> stringResource(R.string.ime_kde_clipboard_paused_until, time)
        }
        Text(line, color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        ToolPanelChip(stringResource(R.string.ime_kde_clipboard_resume_chip), selected = true) {
            choosing = false
            onKde(KdeAction.ResumeClipboard(if (everyone) null else deviceId))
        }
        return
    }
    if (!choosing) {
        ToolPanelChip(stringResource(R.string.ime_kde_clipboard_pause)) { choosing = true }
        return
    }
    SectionLabel(kb, stringResource(R.string.ime_kde_clipboard_pause_for))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        for (minutes in KdeClipboardPause.PRESET_MINUTES) {
            ToolPanelChip(stringResource(pauseLabel(minutes))) {
                choosing = false
                onKde(KdeAction.PauseClipboard(if (all) null else deviceId, minutes))
            }
        }
        ToolPanelChip(stringResource(R.string.ime_kde_clipboard_pause_all), selected = all) { all = !all }
    }
}

@Composable
private fun formatTime(ms: Long): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(ms))
}

private fun pauseLabel(minutes: Int): Int = when (minutes) {
    15 -> R.string.ime_kde_clipboard_pause_15m
    60 -> R.string.ime_kde_clipboard_pause_1h
    else -> R.string.ime_kde_clipboard_pause_forever
}

// ---- small parts ----

/**
 * The panel's one filled button. Its words are black or white against the
 * accent itself: `toolCircleActiveIcon` belongs on `toolCircleActive`, and on
 * many themes it *is* the accent, which left the label invisible. Faded as a
 * whole when disabled, fill included.
 */
@Composable
private fun AccentButton(kb: KbTheme, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.alpha(if (enabled) 1f else BUTTON_DISABLED_ALPHA).height(QUICK_KEY_HEIGHT).clip(RoundedCornerShape(kb.keyRadiusDp.dp))
            .background(kb.accent).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = maxContrastOn(kb.accent), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun QuickKey(kb: KbTheme, label: String = "", icon: ImageVector? = null, armed: Boolean = false, onClick: () -> Unit) {
    val tick = LocalHapticFeedback.current
    Box(
        Modifier.height(QUICK_KEY_HEIGHT).widthIn(min = 40.dp).clip(RoundedCornerShape(kb.keyRadiusDp.dp))
            .background(if (armed) kb.accent else kb.modifierKey)
            .clickable {
                tick()
                onClick()
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (armed) maxContrastOn(kb.accent) else kb.modifierKeyText
        if (icon != null) Icon(icon, label.ifEmpty { null }, tint = tint, modifier = Modifier.size(18.dp))
        else Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun PadButton(kb: KbTheme, label: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    val tick = LocalHapticFeedback.current
    Box(
        modifier.fillMaxWidth().clip(kb.keyShape()).background(kb.modifierKey)
            .clickable(onClickLabel = description) {
                tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = kb.modifierKeyText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun BigKey(kb: KbTheme, icon: ImageVector, description: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val tick = LocalHapticFeedback.current
    Box(
        modifier.clip(kb.keyShape()).background(if (accent) kb.accent else kb.modifierKey).clickable {
            tick()
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (accent) maxContrastOn(kb.accent) else kb.modifierKeyText, modifier = Modifier.size(40.dp)) }
}

@Composable
private fun SmallToggle(kb: KbTheme, icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (on) kb.accent else kb.toolCircle).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (on) maxContrastOn(kb.accent) else kb.toolbarIcon, modifier = Modifier.size(16.dp)) }
}

@Composable
private fun SectionLabel(kb: KbTheme, text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = kb.secondaryText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(start = 4.dp, top = 2.dp))
}

@Composable
private fun CenterNotice(kb: KbTheme, icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = kb.secondaryText, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(title, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) Text(body, color = kb.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

/** A sweep, so "looking" looks like looking. Still under reduce-motion. */
@Composable
private fun Radar(kb: KbTheme, modifier: Modifier, animate: Boolean) {
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "kde-radar")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "kde-radar-phase").value
    } else {
        0.6f
    }
    val color = kb.accent
    Box(
        modifier.drawBehind {
            val r = size.minDimension / 2
            drawCircle(color.copy(alpha = 0.9f), r * 0.22f)
            drawCircle(color.copy(alpha = (1f - phase) * 0.8f), r * (0.3f + 0.7f * phase), style = Stroke(width = r * 0.14f))
        },
    )
}

private fun deviceIcon(type: KdeDeviceType): ImageVector = when (type) {
    KdeDeviceType.DESKTOP -> Icons.Outlined.Computer
    KdeDeviceType.LAPTOP -> Icons.Outlined.Laptop
    KdeDeviceType.PHONE -> Icons.Outlined.Smartphone
    KdeDeviceType.TABLET -> Icons.Outlined.Tablet
    KdeDeviceType.TV -> Icons.Outlined.Tv
}

private fun tabLabel(tab: KdeTab): Int = when (tab) {
    KdeTab.INPUT -> R.string.ime_kde_tab_input
    KdeTab.MEDIA -> R.string.ime_kde_tab_media
    KdeTab.SEND -> R.string.ime_kde_tab_send
    KdeTab.RUN -> R.string.ime_kde_tab_run
    KdeTab.SLIDES -> R.string.ime_kde_tab_slides
    KdeTab.DEVICE -> R.string.ime_kde_tab_device
}

private fun failureText(failure: KdePairFailure): Int = when (failure) {
    KdePairFailure.TIMED_OUT -> R.string.ime_kde_pair_failed_timeout
    KdePairFailure.REJECTED -> R.string.ime_kde_pair_failed_rejected
    KdePairFailure.CLOCKS_DISAGREE -> R.string.ime_kde_pair_failed_clock
    KdePairFailure.DISCONNECTED -> R.string.ime_kde_pair_failed_lost
    KdePairFailure.NOT_REACHABLE -> R.string.ime_kde_pair_failed_unreachable
}

private val ConnectedGreen = Color(0xFF43A047)
private val FailedRed = Color(0xFFE53935)
private const val NOTICE_MS = 3_500L
private const val ROW_FADE_IN_MS = 180
private const val ROW_FADE_OUT_MS = 140
private const val BUTTON_DISABLED_ALPHA = 0.4f

/** Esc, Tab, the arrows and the "Type on PC" button beside them: one row height. */
private val QUICK_KEY_HEIGHT = 34.dp

/** The Send tab's empty-state buttons; capped so a tablet's width does not stretch them into bars. */
private val SEND_TILE_HEIGHT = 84.dp
private val SEND_TILES_MAX_WIDTH = 480.dp
private const val ECHO_CHARS = 120
private const val OUTPUT_LINES_SHOWN = 200
private const val FINGERPRINT_SHOWN = 47

/** Longest edge album art is decoded at. A panel never draws one bigger. */
private const val ALBUM_ART_MAX_PX = 512

/**
 * Album art from the paired computer, sampled down on the way in.
 *
 * The bytes come off another machine and are allowed to be up to six megabytes
 * of compressed image (`MprisPlugin.MAX_ART_BYTES`). Decoding that whole is
 * `width * height * 4` — tens of megabytes, and for a large cover hundreds —
 * inside the keyboard's process, to draw a square a couple of hundred dp wide.
 * The sample stops while the long edge still covers [ALBUM_ART_MAX_PX], so
 * nothing visible changes.
 */
private fun decodeAlbumArt(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null
    var sample = 1
    while (longest / (sample * 2) >= ALBUM_ART_MAX_PX) sample *= 2
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

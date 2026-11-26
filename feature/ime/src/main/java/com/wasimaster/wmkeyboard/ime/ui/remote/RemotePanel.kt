package com.wasimaster.wmkeyboard.ime.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Input
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Home
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Menu
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PowerSettingsNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tv
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolumeOff
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.remote.IrButton
import com.wasimaster.wmkeyboard.ime.remote.RemoteAction
import com.wasimaster.wmkeyboard.ime.remote.RemoteCallbacks
import com.wasimaster.wmkeyboard.ime.remote.RemoteKey
import com.wasimaster.wmkeyboard.ime.remote.RemoteStatus
import com.wasimaster.wmkeyboard.ime.remote.RemoteUi
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Remote tool: the remembered remotes as chips along the header, and a
 * TV remote's pad under them. Power and the volume rocker on the left, the
 * arrows and OK in the middle, input and the channel rocker on the right,
 * Back, Home and Menu along the bottom; an imported infrared file's other
 * buttons follow as chips. A key the remote has no code for is greyed out.
 * Volume, channel and the arrows repeat while held.
 */
@Composable
internal fun RemotePanel(
    state: KeyboardUiState,
    callbacks: RemoteCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        headerActions = { Header(ui, act) },
    ) {
        if (ui.devices.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Tv,
                title = stringResource(R.string.ime_remote_empty_title),
                body = stringResource(R.string.ime_remote_empty_body),
            ) {
                PillButton(stringResource(R.string.ime_remote_add), icon = Icons.Outlined.Add, primary = true) { act(RemoteAction.OpenSettings) }
            }
        } else {
            Pad(ui, act)
        }
    }
}

@Composable
private fun RowScope.Header(ui: RemoteUi, act: (RemoteAction) -> Unit) {
    Row(
        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(2.dp))
        ui.devices.forEach { device ->
            ToolPanelChip(device.name, selected = device.id == ui.current?.id) { act(RemoteAction.Select(device.id)) }
        }
    }
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_remote_settings_desc),
        active = false,
    ) { act(RemoteAction.OpenSettings) }
}

@Composable
private fun Pad(ui: RemoteUi, act: (RemoteAction) -> Unit) {
    val name = ui.current?.name.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusLine(ui.status, name, act)
        val enabled = ui.status != RemoteStatus.NO_EMITTER && ui.status != RemoteStatus.OFFLINE_BUILD
        fun has(key: RemoteKey) = enabled && key in ui.keys
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PadKey(RemoteKey.POWER, Icons.Outlined.PowerSettingsNew, R.string.ime_remote_key_power, has(RemoteKey.POWER), act, Modifier.weight(1f), accent = true)
                Rocker(
                    up = RemoteKey.VOL_UP,
                    down = RemoteKey.VOL_DOWN,
                    label = stringResource(R.string.ime_remote_volume),
                    upLabel = R.string.ime_remote_key_vol_up,
                    downLabel = R.string.ime_remote_key_vol_down,
                    has = ::has,
                    act = act,
                    modifier = Modifier.weight(3f),
                )
            }
            DPad(::has, act, Modifier.weight(1.5f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PadKey(RemoteKey.INPUT, Icons.AutoMirrored.Outlined.Input, R.string.ime_remote_key_input, has(RemoteKey.INPUT), act, Modifier.weight(1f))
                Rocker(
                    up = RemoteKey.CH_UP,
                    down = RemoteKey.CH_DOWN,
                    label = stringResource(R.string.ime_remote_channel),
                    upLabel = R.string.ime_remote_key_ch_up,
                    downLabel = R.string.ime_remote_key_ch_down,
                    has = ::has,
                    act = act,
                    modifier = Modifier.weight(3f),
                )
            }
        }
        Row(Modifier.fillMaxWidth().weight(0.32f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadKey(RemoteKey.BACK, Icons.AutoMirrored.Outlined.ArrowBack, R.string.ime_remote_key_back, has(RemoteKey.BACK), act, Modifier.weight(1f))
            PadKey(RemoteKey.MUTE, Icons.Outlined.VolumeOff, R.string.ime_remote_key_mute, has(RemoteKey.MUTE), act, Modifier.weight(1f))
            PadKey(RemoteKey.HOME, Icons.Outlined.Home, R.string.ime_remote_key_home, has(RemoteKey.HOME), act, Modifier.weight(1f))
            PadKey(RemoteKey.MENU, Icons.Outlined.Menu, R.string.ime_remote_key_menu, has(RemoteKey.MENU), act, Modifier.weight(1f))
        }
        if (ui.extras.isNotEmpty()) Extras(ui.extras, enabled, act)
    }
}

/** A line over the pad while the remote is not simply ready, with the one thing to do about it. */
@Composable
private fun StatusLine(status: RemoteStatus, name: String, act: (RemoteAction) -> Unit) {
    val kb = LocalKbTheme.current
    val text = when (status) {
        RemoteStatus.READY -> return
        RemoteStatus.CONNECTING -> stringResource(R.string.ime_remote_connecting, name)
        RemoteStatus.UNREACHABLE -> stringResource(R.string.ime_remote_unreachable, name)
        RemoteStatus.NOT_PAIRED -> stringResource(R.string.ime_remote_not_paired, name)
        RemoteStatus.BLOCKED -> stringResource(R.string.ime_remote_blocked)
        RemoteStatus.NO_EMITTER -> stringResource(R.string.ime_remote_no_emitter)
        RemoteStatus.OFFLINE_BUILD -> stringResource(R.string.ime_remote_offline_build)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text,
            color = kb.secondaryText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when (status) {
            RemoteStatus.UNREACHABLE, RemoteStatus.BLOCKED -> ToolPanelChip(stringResource(R.string.ime_remote_retry)) { act(RemoteAction.Retry) }
            RemoteStatus.NOT_PAIRED -> ToolPanelChip(stringResource(R.string.ime_remote_pair)) { act(RemoteAction.OpenSettings) }
            else -> Unit
        }
    }
}

/** Up, Left, OK, Right, Down, in a cross. */
@Composable
private fun DPad(has: (RemoteKey) -> Boolean, act: (RemoteAction) -> Unit, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(Modifier.weight(1f))
                PadKey(RemoteKey.UP, Icons.Outlined.KeyboardArrowUp, R.string.ime_remote_key_up, has(RemoteKey.UP), act, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadKey(RemoteKey.LEFT, Icons.AutoMirrored.Outlined.KeyboardArrowLeft, R.string.ime_remote_key_left, has(RemoteKey.LEFT), act, Modifier.weight(1f))
                PadKey(RemoteKey.OK, null, R.string.ime_remote_key_ok, has(RemoteKey.OK), act, Modifier.weight(1f), shape = CircleShape, accent = true)
                PadKey(RemoteKey.RIGHT, Icons.AutoMirrored.Outlined.KeyboardArrowRight, R.string.ime_remote_key_right, has(RemoteKey.RIGHT), act, Modifier.weight(1f))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(Modifier.weight(1f))
                PadKey(RemoteKey.DOWN, Icons.Outlined.KeyboardArrowDown, R.string.ime_remote_key_down, has(RemoteKey.DOWN), act, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A tall pill with a plus at the top, its name in the middle and a minus at the bottom. */
@Composable
private fun Rocker(
    up: RemoteKey,
    down: RemoteKey,
    label: String,
    upLabel: Int,
    downLabel: Int,
    has: (RemoteKey) -> Boolean,
    act: (RemoteAction) -> Unit,
    modifier: Modifier,
) {
    val kb = LocalKbTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(kb.modifierKey),
    ) {
        PadKey(up, Icons.Outlined.Add, upLabel, has(up), act, Modifier.weight(1f), shape = RoundedCornerShape(18.dp), flat = true)
        Text(
            label,
            color = kb.modifierKeyText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        PadKey(down, Icons.Outlined.Remove, downLabel, has(down), act, Modifier.weight(1f), shape = RoundedCornerShape(18.dp), flat = true)
    }
}

/**
 * One button. A press sends at once; a held repeating key sends again after
 * a moment and then steadily until the finger lifts. TalkBack gets a plain
 * button with the key's name.
 */
@Composable
private fun PadKey(
    key: RemoteKey,
    icon: ImageVector?,
    label: Int,
    enabled: Boolean,
    act: (RemoteAction) -> Unit,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    accent: Boolean = false,
    flat: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val name = stringResource(label)
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val onAct by rememberUpdatedState(act)
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val background = when {
        pressed -> kb.pressedKey
        flat -> kb.modifierKey.copy(alpha = 0f)
        accent -> kb.enterKey
        else -> kb.key
    }
    val content = when {
        accent && !pressed -> kb.enterKeyText
        else -> kb.keyText
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(shape)
            .background(background.copy(alpha = background.alpha * alpha))
            .pointerInput(key, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onAct(RemoteAction.Press(key))
                        val repeat = if (key.repeats) {
                            scope.launch {
                                delay(REPEAT_DELAY_MS)
                                while (true) {
                                    onAct(RemoteAction.Press(key))
                                    delay(REPEAT_EVERY_MS)
                                }
                            }
                        } else {
                            null
                        }
                        tryAwaitRelease()
                        repeat?.cancel()
                        pressed = false
                    },
                )
            }
            .semantics {
                contentDescription = name
                role = Role.Button
                if (enabled) {
                    onClick {
                        onAct(RemoteAction.Press(key))
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content.copy(alpha = content.alpha * alpha), modifier = Modifier.size(22.dp))
        } else {
            Text(name, color = content.copy(alpha = content.alpha * alpha), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** An imported file's other buttons, as chips in a scrolling row. */
@Composable
private fun ColumnScope.Extras(extras: List<IrButton>, enabled: Boolean, act: (RemoteAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        extras.forEach { button ->
            ToolPanelChip(button.name, enabled = enabled) { act(RemoteAction.Extra(button)) }
        }
    }
}

private const val DISABLED_ALPHA = 0.35f
private const val REPEAT_DELAY_MS = 420L
private const val REPEAT_EVERY_MS = 130L

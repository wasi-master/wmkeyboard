package com.wasimaster.wmkeyboard.ime.ui.wmpass

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardTab
import com.wasimaster.wmkeyboard.core.icons.symbols.filled.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Autorenew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Badge
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fingerprint
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LockOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Password
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Payments
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Person
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shield
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Storage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tag
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Terminal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerifiedUser
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VpnKey
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wifi
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.IconHint
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.MiniRing
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import com.wasimaster.wmkeyboard.ime.wmpass.VaultWire
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassAction
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassBridge
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassCallbacks
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassController
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassFilter
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassNotice
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassPage
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassTextEntry
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassTextField
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassUi
import com.wasimaster.wmkeyboard.ime.wmpass.admits
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The panel's height while its text box shares the window with the key rows. */
private val WmPassTypingHeight = 164.dp

/** What a secret shows in place of itself. */
private const val DOTS = "••••••••"

/** Past this many items, the list is cut into letters. */
private const val LETTERS_FROM = 12

/** Rows past this one arrive without the pop, so a long list does not ripple for a second. */
private const val POP_ROWS = 10

/**
 * WM Pass's vault as a password book in the keyboard (#1077).
 *
 * Locked, it opens without leaving the app: WM Pass's own fingerprint prompt
 * over it, or the master password typed into the panel's box and handed to
 * WM Pass. Open, it is a list with chips to narrow it, the logins saved for
 * the app in front as cards on top, each with Fill, which types the username,
 * moves to the next box and types the password there. An item opens to its
 * fields: type one, have WM Pass copy it, or show a secret for ten seconds.
 * The generator has a page of its own, and a new login or a new password is
 * typed into the panel's box with a strength meter under it.
 *
 * The list holds no secret; a secret is asked for on the tap that uses it.
 * `WmPassController` owns all of it; this draws what it publishes.
 */
@Composable
internal fun WmPassPanel(
    state: KeyboardUiState,
    callbacks: WmPassCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.wmPassText
    val open = ui.status == WmPassBridge.Status.OPEN && !state.fieldIncognito
    val listing = open && typing == null && ui.page == WmPassPage.LIST
    val shown = remember(ui.rows, ui.filter) { ui.rows.filter { ui.filter.admits(it) } }
    val suggested = remember(shown, ui.query, ui.filter) {
        if (ui.query.isBlank() && ui.filter == WmPassFilter.ALL) shown.filter { it.matches } else emptyList()
    }
    val rest = remember(shown, suggested) { if (suggested.isEmpty()) shown else shown.filterNot { it.matches } }

    PanelFocusTarget(
        panel = PanelMode.WM_PASS,
        region = FocusRegion.RESULTS,
        count = if (listing) suggested.size + rest.size else 0,
        columns = 1,
    ) { index -> (suggested + rest).getOrNull(index)?.let { act(WmPassAction.Open(it.id)) } }

    FullBleedTool(
        state = state,
        title = if (open || typing != null) "" else stringResource(R.string.ime_tool_wm_pass),
        onClose = onClose,
        compact = typing != null,
        compactHeight = WmPassTypingHeight,
        headerActions = {
            when {
                typing != null -> TypingHeader(typing, ui, act)
                !open -> Unit
                ui.page == WmPassPage.LIST -> ListHeader(ui, act)
                else -> PageHeader(ui, act)
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                state.fieldIncognito -> Message(
                    Icons.Outlined.Shield,
                    stringResource(R.string.ime_wmpass_private_field),
                    listOf(KitAction(stringResource(R.string.ime_wmpass_open), Icons.Outlined.OpenInNew, primary = true) { act(WmPassAction.OpenApp) }),
                )
                typing?.field == WmPassTextField.MASTER -> MasterBody(ui)
                ui.status == null -> Unit
                ui.status == WmPassBridge.Status.LOCKED -> LockedFace(ui, act)
                ui.status != WmPassBridge.Status.OPEN -> StatusMessage(ui.status, act)
                typing != null -> TypingBody(typing, ui, act)
                else -> Pages(state, ui, suggested, rest, act)
            }
            NoticeToast(ui.notice, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The faces of an open vault, sliding sideways as one opens from another. */
@Composable
private fun Pages(
    state: KeyboardUiState,
    ui: WmPassUi,
    suggested: List<VaultWire.Row>,
    rest: List<VaultWire.Row>,
    act: (WmPassAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    AnimatedContent(
        targetState = ui.page,
        transitionSpec = {
            if (!kb.motion.any) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                val deeper = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(PAGE_MS)) { if (deeper) it / 4 else -it / 4 } + fadeIn(tween(PAGE_MS))) togetherWith
                    (slideOutHorizontally(tween(PAGE_MS)) { if (deeper) -it / 4 else it / 4 } + fadeOut(tween(PAGE_MS / 2)))
            }
        },
        label = "wmpass-page",
    ) { page ->
        when (page) {
            WmPassPage.LIST -> ItemList(state, ui, suggested, rest, act)
            WmPassPage.ITEM -> ItemPage(ui, act)
            WmPassPage.ADD -> AddForm(ui, act)
            WmPassPage.CHANGE -> ChangeForm(ui, act)
            WmPassPage.GENERATE -> GeneratorPage(ui, act)
        }
    }
}

// ---- headers ----

/** The search pill, which hands the keys to the panel's box, then New password, Add a login and Lock. */
@Composable
private fun RowScope.ListHeader(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    BoxPill(Modifier.weight(1f), onClick = { act(WmPassAction.StartSearch) }) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = ui.query,
            placeholder = stringResource(R.string.ime_wmpass_search_hint),
            active = false,
            textColor = kb.chipText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (ui.query.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_wmpass_clear_search_desc),
                tint = kb.secondaryText,
                modifier = Modifier.size(16.dp).clickable { act(WmPassAction.ClearSearch) },
            )
        }
    }
    Spacer(Modifier.width(4.dp))
    RoundButton(Icons.Outlined.Password, stringResource(R.string.ime_wmpass_generate)) { act(WmPassAction.StartGenerate) }
    RoundButton(Icons.Outlined.Add, stringResource(R.string.ime_wmpass_add)) { act(WmPassAction.StartAdd) }
    RoundButton(Icons.Outlined.Lock, stringResource(R.string.ime_wmpass_lock)) { act(WmPassAction.Lock) }
    Spacer(Modifier.width(2.dp))
}

/** Back to the list (or the item), what is open, and the one thing to do with it. */
@Composable
private fun RowScope.PageHeader(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    RoundButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ime_wmpass_back)) { act(WmPassAction.Back) }
    Spacer(Modifier.width(6.dp))
    Text(
        when (ui.page) {
            WmPassPage.ADD -> stringResource(R.string.ime_wmpass_new_login)
            WmPassPage.CHANGE -> stringResource(R.string.ime_wmpass_new_password_for, ui.item?.title.orEmpty())
            WmPassPage.GENERATE -> stringResource(R.string.ime_wmpass_generate)
            else -> ui.item?.title.orEmpty()
        },
        color = kb.suggestionText,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    when (ui.page) {
        WmPassPage.ITEM -> if (!ui.inPass) {
            RoundButton(Icons.Outlined.OpenInNew, stringResource(R.string.ime_wmpass_open_item)) { act(WmPassAction.OpenInPass) }
        }
        WmPassPage.GENERATE -> RoundButton(Icons.Outlined.Autorenew, stringResource(R.string.ime_wmpass_another)) { act(WmPassAction.Generate) }
        else -> ToolPanelChip(stringResource(R.string.ime_wmpass_save), selected = true, enabled = canSave(ui)) { act(WmPassAction.Save) }
    }
    Spacer(Modifier.width(4.dp))
}

/** While the keys type into the panel, the header is the box, then Next, Done or Unlock. */
@Composable
private fun RowScope.TypingHeader(entry: WmPassTextEntry, ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    val masked = entry.field.secret && (entry.field == WmPassTextField.MASTER || !ui.showPassword)
    BoxPill(Modifier.weight(1f).padding(start = 2.dp)) {
        Icon(
            when (entry.field) {
                WmPassTextField.SEARCH -> Icons.Outlined.Search
                WmPassTextField.MASTER -> Icons.Outlined.Lock
                WmPassTextField.PASSWORD, WmPassTextField.NEW_PASSWORD -> Icons.Outlined.Password
                WmPassTextField.USERNAME -> Icons.Outlined.Person
                WmPassTextField.WEBSITE -> Icons.Outlined.Link
                WmPassTextField.NAME -> Icons.Outlined.Tag
            },
            contentDescription = null,
            tint = kb.secondaryText,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = if (masked) "•".repeat(entry.text.length) else entry.text,
            placeholder = hintFor(entry.field),
            active = true,
            textColor = kb.chipText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.width(6.dp))
    when (entry.field) {
        WmPassTextField.MASTER -> ToolPanelChip(
            stringResource(R.string.ime_wmpass_unlock),
            selected = true,
            enabled = entry.text.isNotEmpty() && !ui.unlocking,
        ) { act(WmPassAction.Unlock) }
        WmPassTextField.NAME, WmPassTextField.USERNAME, WmPassTextField.PASSWORD -> ToolPanelChip(stringResource(R.string.ime_wmpass_next)) {
            act(
                WmPassAction.Edit(
                    when (entry.field) {
                        WmPassTextField.NAME -> WmPassTextField.USERNAME
                        WmPassTextField.USERNAME -> WmPassTextField.PASSWORD
                        else -> WmPassTextField.WEBSITE
                    },
                ),
            )
        }
        else -> ToolPanelChip(stringResource(R.string.ime_wmpass_done), selected = true) { act(WmPassAction.TextDone) }
    }
    Spacer(Modifier.width(4.dp))
}

// ---- locked ----

/**
 * The locked face: the lock, why it is shut and how long a wait has left,
 * and the ways in along the bottom. The fingerprint comes first when WM Pass
 * has it set up; the master password always works, unless a wait is on.
 */
@Composable
private fun LockedFace(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    val left = rememberWaitLeft(ui.retryAt)
    val waiting = left > 0
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            LockSeal(size = 72, open = false, busy = false, shakes = ui.wrongTries)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ime_wmpass_locked_title),
                    color = kb.suggestionText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (ui.appName.isNotEmpty() && !ui.inPass) {
                        stringResource(R.string.ime_wmpass_locked_body_app, ui.appName)
                    } else {
                        stringResource(R.string.ime_wmpass_locked_body)
                    },
                    color = kb.secondaryText,
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                )
                if (waiting) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.ime_wmpass_wait, formatWait(left)),
                        color = errorColour(kb),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                    )
                }
            }
        }
        val fingerprint = stringResource(R.string.ime_wmpass_fingerprint)
        val password = stringResource(R.string.ime_wmpass_use_password)
        val typePassword = stringResource(R.string.ime_wmpass_type_master)
        val inPass = stringResource(R.string.ime_wmpass_unlock_in_pass)
        val openApp = stringResource(R.string.ime_wmpass_open)
        ActionBar(
            when {
                waiting -> listOf(KitAction(openApp, Icons.Outlined.OpenInNew, primary = true) { act(WmPassAction.OpenApp) })
                ui.fingerprint -> listOf(
                    KitAction(fingerprint, Icons.Outlined.Fingerprint, primary = true) { act(WmPassAction.UnlockWithFingerprint) },
                    KitAction(password, Icons.Outlined.Password) { act(WmPassAction.StartPassword) },
                    KitAction(openApp, Icons.Outlined.OpenInNew) { act(WmPassAction.OpenApp) },
                )
                else -> listOf(
                    KitAction(typePassword, Icons.Outlined.Password, primary = true) { act(WmPassAction.StartPassword) },
                    // No fingerprint set up: the same prompt brings WM Pass's door over the app instead.
                    KitAction(inPass, Icons.Outlined.LockOpen) { act(WmPassAction.UnlockWithFingerprint) },
                )
            },
            height = 42.dp,
        )
    }
}

/** Under the master password box: the lock, what happens to the password, and how the last try went. */
@Composable
private fun MasterBody(ui: WmPassUi) {
    val kb = LocalKbTheme.current
    val left = rememberWaitLeft(ui.retryAt)
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LockSeal(size = 44, open = false, busy = ui.unlocking, shakes = ui.wrongTries)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            when {
                ui.unlocking -> Text(stringResource(R.string.ime_wmpass_unlocking), color = kb.suggestionText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                left > 0 -> Text(stringResource(R.string.ime_wmpass_wait, formatWait(left)), color = errorColour(kb), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                ui.wrongTries > 0 -> Text(stringResource(R.string.ime_wmpass_wrong), color = errorColour(kb), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text(stringResource(R.string.ime_wmpass_master_info), color = kb.secondaryText, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 3)
        }
    }
}

/**
 * The lock as a seal: a soft accent disc with the lock in it, a halo that
 * breathes while it waits, a shake for each wrong password, a spinner while
 * WM Pass checks one.
 */
@Composable
private fun LockSeal(size: Int, open: Boolean, busy: Boolean, shakes: Int) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakes) {
        if (shakes == 0 || !animate) return@LaunchedEffect
        for (x in listOf(10f, -9f, 7f, -5f, 3f, 0f)) shake.animateTo(x, tween(SHAKE_STEP_MS))
    }
    val halo = if (animate) {
        val pulse = rememberInfiniteTransition(label = "wmpass-halo")
        pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(HALO_MS, easing = LinearEasing), RepeatMode.Restart), label = "wmpass-halo-t")
    } else {
        null
    }
    val accent = kb.accent
    Box(
        Modifier.size(size.dp).offset { IntOffset(shake.value.roundToInt(), 0) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val t = halo?.value
            if (t != null && !busy) {
                drawCircle(accent.copy(alpha = 0.22f * (1f - t)), radius = r * (0.72f + 0.28f * t))
            }
            drawCircle(accent.copy(alpha = if (kb.dark) 0.24f else 0.15f), radius = r * 0.72f)
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size((size * 0.42f).dp), strokeWidth = 2.5.dp, color = accent)
        } else {
            Icon(
                if (open) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size((size * 0.36f).dp),
            )
        }
    }
}

/** Seconds left of a wait that ends at [retryAt] by the wall clock, ticking once a second. */
@Composable
private fun rememberWaitLeft(retryAt: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAt) {
        now = System.currentTimeMillis()
        while (retryAt > now) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    return ((retryAt - now + 999) / 1000).coerceAtLeast(0)
}

private fun formatWait(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

// ---- the list ----

@Composable
private fun ItemList(
    state: KeyboardUiState,
    ui: WmPassUi,
    suggested: List<VaultWire.Row>,
    rest: List<VaultWire.Row>,
    act: (WmPassAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    if (ui.rows.isEmpty()) {
        Message(
            Icons.Outlined.Key,
            if (ui.query.isBlank()) stringResource(R.string.ime_wmpass_empty) else stringResource(R.string.ime_wmpass_no_match, ui.query.trim()),
            if (ui.query.isBlank()) {
                listOf(KitAction(stringResource(R.string.ime_wmpass_add), Icons.Outlined.Add, primary = true) { act(WmPassAction.StartAdd) })
            } else {
                emptyList()
            },
        )
        return
    }
    val focused = state.focusedIndex(FocusRegion.RESULTS)
    val animate = kb.motion.any
    val letters = ui.query.isBlank() && rest.size > LETTERS_FROM
    Column(Modifier.fillMaxSize()) {
        FilterRow(ui, act)
        if (suggested.isEmpty() && rest.isEmpty()) {
            Message(Icons.Outlined.Search, stringResource(R.string.ime_wmpass_filter_empty))
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (suggested.isNotEmpty()) {
                item(key = "for-app-label") {
                    SectionLabel(
                        if (ui.appName.isNotEmpty()) stringResource(R.string.ime_wmpass_saved_for, ui.appName) else stringResource(R.string.ime_wmpass_matches_desc),
                        accent = true,
                    )
                }
                item(key = "for-app") {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(suggested, key = { _, row -> "s-" + row.id }) { index, row ->
                            SuggestionCard(row, focused = focused == index, index = index, animate = animate, act = act)
                        }
                    }
                }
                if (rest.isNotEmpty()) item(key = "all-label") { SectionLabel(stringResource(R.string.ime_wmpass_back), accent = false) }
            }
            var last: Char? = null
            rest.forEachIndexed { index, row ->
                if (letters) {
                    val letter = initialOf(row.title)
                    if (letter != last) {
                        last = letter
                        item(key = "letter-$letter-$index") { LetterHeader(letter) }
                    }
                }
                item(key = row.id) {
                    ItemRow(
                        row,
                        focused = focused == suggested.size + index,
                        modifier = Modifier.popIn(index, animate && index < POP_ROWS, row.id),
                        act = act,
                    )
                }
            }
            if (ui.vault.isNotEmpty()) {
                item(key = "footer") {
                    Text(
                        pluralStringResource(R.plurals.ime_wmpass_item_count, ui.rows.size, ui.vault, ui.rows.size),
                        color = kb.secondaryText.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 40.dp),
                    )
                }
            }
        }
    }
}

/** The chips that narrow the list: only those with something under them, and All. */
@Composable
private fun FilterRow(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val present = remember(ui.rows) { WmPassFilter.entries.filter { f -> f == WmPassFilter.ALL || ui.rows.any { f.admits(it) } } }
    // A row with only All on it says nothing.
    if (present.size <= 2 && ui.filter == WmPassFilter.ALL) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        present.forEach { filter ->
            val count = if (filter == WmPassFilter.ALL) ui.rows.size else ui.rows.count { filter.admits(it) }
            FilterChip(filterName(filter), count, selected = ui.filter == filter) { act(WmPassAction.Filter(filter)) }
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .height(28.dp)
            .pressScale(source, kb.motion.any)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (selected) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable(interactionSource = source, indication = null) {
                feedback()
                onClick()
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) kb.chipActiveText else kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        Text(
            count.toString(),
            color = (if (selected) kb.chipActiveText else kb.secondaryText).copy(alpha = 0.75f),
            fontSize = 11.sp,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

@Composable
private fun filterName(filter: WmPassFilter): String = stringResource(
    when (filter) {
        WmPassFilter.ALL -> R.string.ime_wmpass_filter_all
        WmPassFilter.FOR_APP -> R.string.ime_wmpass_filter_app
        WmPassFilter.FAVOURITES -> R.string.ime_wmpass_filter_favourites
        WmPassFilter.LOGINS -> R.string.ime_wmpass_filter_logins
        WmPassFilter.CODES -> R.string.ime_wmpass_filter_codes
        WmPassFilter.CARDS -> R.string.ime_wmpass_filter_cards
        WmPassFilter.NOTES -> R.string.ime_wmpass_filter_notes
        WmPassFilter.OTHER -> R.string.ime_wmpass_filter_other
    },
)

@Composable
private fun SectionLabel(text: String, accent: Boolean) {
    val kb = LocalKbTheme.current
    Text(
        text.uppercase(),
        color = if (accent) kb.accent else kb.secondaryText,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp),
    )
}

@Composable
private fun LetterHeader(letter: Char) {
    val kb = LocalKbTheme.current
    Text(
        letter.toString(),
        color = kb.accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 22.dp, top = 6.dp, bottom = 2.dp),
    )
}

/**
 * A login saved for the app in front, as a card: its mark, name and
 * username, and Fill as the one thing to do, with the single fields beside.
 */
@Composable
private fun SuggestionCard(row: VaultWire.Row, focused: Boolean, index: Int, animate: Boolean, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(232.dp)
            .popIn(index, animate, row.id)
            .pressScale(source, animate)
            .focusRing(focused, shape)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.85f))
            .chipBorder(kb, shape)
            .clickable(interactionSource = source, indication = null) {
                feedback()
                act(WmPassAction.Open(row.id))
            }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(row.title, row.kind, size = 34)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.subtitle.isNotEmpty()) {
                    Text(row.subtitle, color = kb.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canFill(row)) {
                AccentPill(stringResource(R.string.ime_wmpass_fill), Icons.AutoMirrored.Outlined.KeyboardTab, stringResource(R.string.ime_wmpass_fill_desc), Modifier.weight(1f), fill = true) {
                    act(WmPassAction.Fill(row.id))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            QuickTypes(row, act)
        }
    }
}

/**
 * One item: its mark, name and the line under it, which opens it. A login
 * wears its fields as small round buttons that type straight away; opening
 * the tool was the first of the two taps.
 */
@Composable
private fun ItemRow(row: VaultWire.Row, focused: Boolean, modifier: Modifier, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.cardShape()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .focusRing(focused, shape)
            .clip(shape)
            .clickable {
                feedback()
                act(WmPassAction.Open(row.id))
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.title, row.kind, size = 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    color = kb.suggestionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.favourite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.ime_wmpass_favourite_desc),
                        tint = FAVOURITE,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            if (row.subtitle.isNotEmpty()) {
                Text(row.subtitle, color = kb.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (row.kind == "login") {
            QuickTypes(row, act)
        } else {
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = kb.secondaryText.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
        }
    }
}

/** A login's username, password and code as round buttons that type each one. */
@Composable
private fun QuickTypes(row: VaultWire.Row, act: (WmPassAction) -> Unit) {
    if (row.kind != "login") return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row.subtitle.isNotEmpty()) {
            RoundButton(Icons.Outlined.Person, stringResource(R.string.ime_wmpass_type_username), size = 30) { act(WmPassAction.Type(row.id, VaultWire.USERNAME)) }
        }
        if (row.hasPassword) {
            RoundButton(Icons.Outlined.Password, stringResource(R.string.ime_wmpass_type_password), size = 30) { act(WmPassAction.Type(row.id, VaultWire.PASSWORD)) }
        }
        if (row.hasCode) {
            RoundButton(Icons.Outlined.Pin, stringResource(R.string.ime_wmpass_type_code), size = 30) { act(WmPassAction.Type(row.id, VaultWire.CODE)) }
        }
    }
}

private fun canFill(row: VaultWire.Row) = row.kind == "login" && row.subtitle.isNotEmpty() && row.hasPassword

/**
 * An item's mark: its first letter on a colour of its own, the same colour
 * every time for the same name, with its kind in a small badge.
 */
@Composable
private fun Avatar(title: String, kind: String, size: Int) {
    val kb = LocalKbTheme.current
    val colour = remember(title, kb.dark) { avatarColour(title, kb.dark) }
    val letter = remember(title) { title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
    Box(Modifier.size((size + 4).dp)) {
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape((size * 0.3f).dp))
                .background(colour),
            contentAlignment = Alignment.Center,
        ) {
            if (letter != null) {
                Text(letter.toString(), color = Color.White, fontSize = (size * 0.44f).sp, fontWeight = FontWeight.Bold)
            } else {
                Icon(kindIcon(kind), contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.5f).dp))
            }
        }
        if (letter != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size((size * 0.46f).dp)
                    .clip(CircleShape)
                    .background(kb.board)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(kb.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kindIcon(kind), contentDescription = null, tint = kb.accent, modifier = Modifier.size((size * 0.28f).dp))
            }
        }
    }
}

/** A steady colour for a name: its hash picks the hue, the theme the lightness. */
private fun avatarColour(title: String, dark: Boolean): Color {
    val hue = abs(title.trim().lowercase().hashCode() % 360).toFloat()
    return Color.hsl(hue, 0.52f, if (dark) 0.40f else 0.50f)
}

private fun initialOf(title: String): Char {
    val c = title.trim().firstOrNull()?.uppercaseChar() ?: return '#'
    return if (c.isLetter()) c else '#'
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "login" -> Icons.Outlined.Key
    "card" -> Icons.Outlined.Payments
    "wifi" -> Icons.Outlined.Wifi
    "note" -> Icons.Outlined.StickyNote2
    "identity" -> Icons.Outlined.Badge
    "ssh" -> Icons.Outlined.Terminal
    "api" -> Icons.Outlined.VpnKey
    "database" -> Icons.Outlined.Storage
    "licence" -> Icons.Outlined.VerifiedUser
    "recovery" -> Icons.Outlined.Restore
    "otp" -> Icons.Outlined.Pin
    "document" -> Icons.Outlined.Description
    else -> Icons.Outlined.Lock
}

@Composable
private fun kindName(kind: String): String = stringResource(
    when (kind) {
        "login" -> R.string.ime_wmpass_kind_login
        "card" -> R.string.ime_wmpass_kind_card
        "wifi" -> R.string.ime_wmpass_kind_wifi
        "note" -> R.string.ime_wmpass_kind_note
        "identity" -> R.string.ime_wmpass_kind_identity
        "ssh" -> R.string.ime_wmpass_kind_ssh
        "api" -> R.string.ime_wmpass_kind_api
        "database" -> R.string.ime_wmpass_kind_database
        "licence" -> R.string.ime_wmpass_kind_licence
        "recovery" -> R.string.ime_wmpass_kind_recovery
        "otp" -> R.string.ime_wmpass_kind_otp
        "document" -> R.string.ime_wmpass_kind_document
        else -> R.string.ime_wmpass_kind_other
    },
)

// ---- an item ----

/**
 * An item opened: a head with its mark, name and kind, Fill for a login, then
 * each field on a card of its own with Type and Copy, and Show for a secret,
 * which shows it for ten seconds with a ring counting them down.
 */
@Composable
private fun ItemPage(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    val item = ui.item ?: return
    val animate = kb.motion.any
    val fillable = item.kind == "login" &&
        item.fields.any { it.key == VaultWire.USERNAME && !it.value.isNullOrEmpty() } &&
        item.fields.any { it.key == VaultWire.PASSWORD }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(item.title, item.kind, size = 42)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = kb.suggestionText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(kindName(item.kind), item.subtitle).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (fillable) {
                    Spacer(Modifier.width(8.dp))
                    AccentPill(stringResource(R.string.ime_wmpass_fill), Icons.AutoMirrored.Outlined.KeyboardTab, stringResource(R.string.ime_wmpass_fill_desc)) {
                        act(WmPassAction.Fill(item.id))
                    }
                }
            }
        }
        itemsIndexed(item.fields, key = { _, field -> field.key }) { index, field ->
            FieldCard(
                item.id,
                field,
                peek = ui.peek?.takeIf { it.id == item.id && it.key == field.key },
                modifier = Modifier.popIn(index, animate && index < POP_ROWS, item.id + field.key),
                act = act,
            )
        }
        if (item.changes != null) {
            item(key = "change") {
                Row(Modifier.padding(top = 6.dp, bottom = 40.dp)) {
                    ToolPanelChip(stringResource(R.string.ime_wmpass_change_password), icon = null) { act(WmPassAction.StartChange) }
                }
            }
        }
    }
}

@Composable
private fun FieldCard(
    id: String,
    field: VaultWire.Field,
    peek: com.wasimaster.wmkeyboard.ime.wmpass.WmPassPeek?,
    modifier: Modifier,
    act: (WmPassAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
            .chipBorder(kb, shape)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(fieldIcon(field), contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                labelFor(field).uppercase(),
                color = kb.secondaryText,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp,
                maxLines = 1,
            )
            when {
                peek != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        secretText(peek.value, kb),
                        color = kb.suggestionText,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    PeekRing(peek.until)
                }
                field.secret -> Text(DOTS, color = kb.suggestionText, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                else -> Text(field.value.orEmpty(), color = kb.suggestionText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(6.dp))
        if (field.secret) {
            RoundButton(
                if (peek != null) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                stringResource(if (peek != null) R.string.ime_wmpass_hide else R.string.ime_wmpass_peek),
                size = 32,
            ) { act(WmPassAction.Peek(id, field.key)) }
        }
        RoundButton(Icons.Outlined.Keyboard, stringResource(R.string.ime_wmpass_type), size = 32) { act(WmPassAction.Type(id, field.key)) }
        RoundButton(Icons.Outlined.ContentCopy, stringResource(R.string.ime_wmpass_copy), size = 32) { act(WmPassAction.Copy(id, field.key)) }
    }
}

/** The ten seconds a shown secret has left, as a ring that empties. */
@Composable
private fun PeekRing(until: Long) {
    val kb = LocalKbTheme.current
    var left by remember(until) { mutableStateOf(1f) }
    LaunchedEffect(until) {
        while (true) {
            val f = (until - SystemClock.elapsedRealtime()).toFloat() / WmPassController.PEEK_MS
            left = f.coerceIn(0f, 1f)
            if (f <= 0f) break
            delay(PEEK_TICK_MS)
        }
    }
    MiniRing(left, kb.accent, size = 14.dp, stroke = 2.5.dp)
}

private fun fieldIcon(field: VaultWire.Field): ImageVector = when {
    field.key == VaultWire.USERNAME -> Icons.Outlined.Person
    field.key == VaultWire.PASSWORD -> Icons.Outlined.Password
    field.key == VaultWire.CODE -> Icons.Outlined.Pin
    field.key == VaultWire.NOTES -> Icons.Outlined.Notes
    field.key.startsWith(VaultWire.SITE) -> Icons.Outlined.Link
    field.key.startsWith(VaultWire.CUSTOM) -> Icons.Outlined.Tag
    field.secret -> Icons.Outlined.Key
    else -> Icons.Outlined.Tag
}

/** A field's name: this keyboard's own words for what every item can hold, WM Pass's for its kinds' fields. */
@Composable
private fun labelFor(field: VaultWire.Field): String = when {
    field.key == VaultWire.CODE -> stringResource(R.string.ime_wmpass_code)
    field.key == VaultWire.NOTES -> stringResource(R.string.ime_wmpass_field_notes)
    field.key.startsWith(VaultWire.SITE) -> stringResource(R.string.ime_wmpass_field_website)
    else -> field.label
}

/** A password as it is read aloud letter by letter: digits in the accent, symbols in a second colour. */
private fun secretText(value: String, kb: KbTheme): AnnotatedString = buildAnnotatedString {
    val symbol = if (kb.dark) Color(0xFFFFB86B) else Color(0xFFB25E00)
    value.forEach { c ->
        when {
            c.isDigit() -> withStyle(SpanStyle(color = kb.accent)) { append(c) }
            !c.isLetterOrDigit() -> withStyle(SpanStyle(color = symbol)) { append(c) }
            else -> append(c)
        }
    }
}

// ---- the generator ----

/** A new password from WM Pass's generator: big, coloured by kind of character, its strength, and what to do with it. */
@Composable
private fun GeneratorPage(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CARDS)
                .background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
                .chipBorder(kb, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = ui.generated,
                transitionSpec = {
                    if (kb.motion.any) {
                        (fadeIn(tween(PAGE_MS)) + slideInVertically(tween(PAGE_MS)) { it / 3 }) togetherWith fadeOut(tween(PAGE_MS / 2))
                    } else {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    }
                },
                label = "wmpass-generated",
            ) { made ->
                if (made.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = kb.accent)
                } else {
                    Text(
                        secretText(made, kb),
                        color = kb.suggestionText,
                        fontSize = if (made.length > 24) 16.sp else 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        StrengthBar(ui.generated)
        Spacer(Modifier.height(6.dp))
        ActionBar(
            listOf(
                KitAction(stringResource(R.string.ime_wmpass_type_it), Icons.Outlined.Keyboard, enabled = ui.generated.isNotEmpty(), primary = true) { act(WmPassAction.TypeGenerated) },
                KitAction(stringResource(R.string.ime_wmpass_another), Icons.Outlined.Autorenew) { act(WmPassAction.Generate) },
                KitAction(stringResource(R.string.ime_wmpass_save_as_login), Icons.Outlined.Add, enabled = ui.generated.isNotEmpty()) { act(WmPassAction.SaveGenerated) },
            ),
            height = 40.dp,
        )
    }
}

/**
 * How hard a password is to guess, as four segments and a word. A rough
 * count: the kinds of character in it set the alphabet, repeats count once
 * and a half, and the length does the rest.
 */
internal fun passwordBits(password: String): Int {
    if (password.isEmpty()) return 0
    var pool = 0
    if (password.any { it.isLowerCase() }) pool += 26
    if (password.any { it.isUpperCase() }) pool += 26
    if (password.any { it.isDigit() }) pool += 10
    if (password.any { !it.isLetterOrDigit() && !it.isWhitespace() }) pool += 33
    if (password.any { it.isWhitespace() }) pool += 1
    if (password.any { it.code > 127 && it.isLetter() }) pool += 40
    val distinct = password.toSet().size
    val length = min(password.length, distinct * 3 / 2 + 1)
    return (length * ln(max(pool, 2).toDouble()) / ln(2.0)).roundToInt()
}

internal fun strengthLevel(bits: Int): Int = when {
    bits < 28 -> 0
    bits < 40 -> 1
    bits < 60 -> 2
    bits < 80 -> 3
    else -> 4
}

private val StrengthColours = listOf(Color(0xFFE5484D), Color(0xFFF76B15), Color(0xFFFFC53D), Color(0xFF46A758), Color(0xFF12A594))

@Composable
private fun StrengthBar(password: String) {
    val kb = LocalKbTheme.current
    if (password.isEmpty()) return
    val bits = passwordBits(password)
    val level = strengthLevel(bits)
    val colour = StrengthColours[level]
    val track = kb.divider
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(5.dp)) {
            val gap = 4.dp.toPx()
            val w = (size.width - gap * 3) / 4
            for (i in 0 until 4) {
                val on = i < max(level, 1)
                drawRoundRect(
                    if (on) colour else track,
                    topLeft = Offset(i * (w + gap), 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                when (level) {
                    0 -> R.string.ime_wmpass_strength_0
                    1 -> R.string.ime_wmpass_strength_1
                    2 -> R.string.ime_wmpass_strength_2
                    3 -> R.string.ime_wmpass_strength_3
                    else -> R.string.ime_wmpass_strength_4
                },
            ),
            color = colour,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.ime_wmpass_strength_bits, bits), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
    }
}

// ---- the forms ----

/** A new login, laid out whole while no box has the keys: a tap on a line types into it. */
@Composable
private fun AddForm(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 2.dp)) {
        for (field in listOf(WmPassTextField.NAME, WmPassTextField.USERNAME, WmPassTextField.PASSWORD, WmPassTextField.WEBSITE)) {
            FormLine(field, ui.draft.of(field), ui.showPassword) { act(WmPassAction.Edit(field)) }
            if (field == WmPassTextField.PASSWORD && ui.draft.password.isNotEmpty()) {
                Box(Modifier.padding(start = 28.dp, bottom = 2.dp)) { StrengthBar(ui.draft.password) }
            }
        }
        Spacer(Modifier.height(6.dp))
        FormChips(ui, act)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ime_wmpass_new_login_info), color = kb.secondaryText, fontSize = 11.sp, maxLines = 2)
    }
}

/** A new password for the open item, the same way. */
@Composable
private fun ChangeForm(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 2.dp)) {
        FormLine(WmPassTextField.NEW_PASSWORD, ui.newPassword, ui.showPassword) { act(WmPassAction.Edit(WmPassTextField.NEW_PASSWORD)) }
        if (ui.newPassword.isNotEmpty()) Box(Modifier.padding(start = 28.dp, bottom = 2.dp)) { StrengthBar(ui.newPassword) }
        Spacer(Modifier.height(6.dp))
        FormChips(ui, act)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ime_wmpass_new_password_info), color = kb.secondaryText, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun FormLine(field: WmPassTextField, text: String, shown: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(formIcon(field), contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(nameOf(field), color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.width(80.dp), maxLines = 1)
        Text(
            when {
                text.isEmpty() -> stringResource(R.string.ime_wmpass_not_set)
                field.secret && !shown -> "•".repeat(text.length)
                else -> text
            },
            color = if (text.isEmpty()) kb.secondaryText.copy(alpha = 0.7f) else kb.suggestionText,
            fontSize = 14.sp,
            fontFamily = if (field.secret && text.isNotEmpty()) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formIcon(field: WmPassTextField): ImageVector = when (field) {
    WmPassTextField.NAME -> Icons.Outlined.Tag
    WmPassTextField.USERNAME -> Icons.Outlined.Person
    WmPassTextField.PASSWORD, WmPassTextField.NEW_PASSWORD, WmPassTextField.MASTER -> Icons.Outlined.Password
    WmPassTextField.WEBSITE -> Icons.Outlined.Link
    WmPassTextField.SEARCH -> Icons.Outlined.Search
}

/** Under a form: a password from WM Pass's generator, Show or Hide, Save. */
@Composable
private fun FormChips(ui: WmPassUi, act: (WmPassAction) -> Unit, typing: WmPassTextField? = null) {
    val secretInView = typing == null || typing.secret
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secretInView) {
            ToolPanelChip(stringResource(R.string.ime_wmpass_generate), icon = null) { act(WmPassAction.Generate) }
            ToolPanelChip(
                stringResource(if (ui.showPassword) R.string.ime_wmpass_hide else R.string.ime_wmpass_show),
                icon = if (ui.showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            ) { act(WmPassAction.ToggleShow) }
        }
        if (typing != null) {
            ToolPanelChip(stringResource(R.string.ime_wmpass_save), selected = true, enabled = canSave(ui)) { act(WmPassAction.Save) }
        }
    }
}

/**
 * Under the box while it types. A search shows what it finds; a form shows
 * its other boxes, to jump to, what can be done to this one, and a password's
 * strength as it is typed.
 */
@Composable
private fun TypingBody(entry: WmPassTextEntry, ui: WmPassUi, act: (WmPassAction) -> Unit) {
    if (entry.field == WmPassTextField.SEARCH) {
        if (ui.rows.isEmpty() && ui.query.isNotBlank()) {
            Message(Icons.Outlined.Search, stringResource(R.string.ime_wmpass_no_match, ui.query.trim()))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(ui.rows, key = { _, row -> row.id }) { _, row -> ItemRow(row, focused = false, modifier = Modifier, act = act) }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (ui.page == WmPassPage.ADD) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (field in listOf(WmPassTextField.NAME, WmPassTextField.USERNAME, WmPassTextField.PASSWORD, WmPassTextField.WEBSITE)) {
                    ToolPanelChip(nameOf(field), selected = field == entry.field) { act(WmPassAction.Edit(field)) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (entry.field.secret && entry.text.isNotEmpty()) {
            StrengthBar(entry.text)
            Spacer(Modifier.height(6.dp))
        }
        FormChips(ui, act, typing = entry.field)
    }
}

/** Whether Save has something to send: any line of a new login, or a new password. */
private fun canSave(ui: WmPassUi): Boolean = !ui.busy && when (ui.page) {
    WmPassPage.ADD -> !ui.draft.blank
    WmPassPage.CHANGE -> ui.newPassword.isNotEmpty()
    else -> false
}

@Composable
private fun nameOf(field: WmPassTextField): String = when (field) {
    WmPassTextField.NAME -> stringResource(R.string.ime_wmpass_field_name)
    WmPassTextField.USERNAME -> stringResource(R.string.ime_wmpass_username)
    WmPassTextField.PASSWORD, WmPassTextField.NEW_PASSWORD -> stringResource(R.string.ime_wmpass_password)
    WmPassTextField.WEBSITE -> stringResource(R.string.ime_wmpass_field_website)
    WmPassTextField.SEARCH -> stringResource(R.string.ime_wmpass_search_hint)
    WmPassTextField.MASTER -> stringResource(R.string.ime_wmpass_master_hint)
}

@Composable
private fun hintFor(field: WmPassTextField): String = when (field) {
    WmPassTextField.SEARCH -> stringResource(R.string.ime_wmpass_search_hint)
    WmPassTextField.NAME -> stringResource(R.string.ime_wmpass_name_hint)
    WmPassTextField.USERNAME -> stringResource(R.string.ime_wmpass_username_hint)
    WmPassTextField.PASSWORD -> stringResource(R.string.ime_wmpass_password)
    WmPassTextField.WEBSITE -> stringResource(R.string.ime_wmpass_website_hint)
    WmPassTextField.NEW_PASSWORD -> stringResource(R.string.ime_wmpass_new_password_hint)
    WmPassTextField.MASTER -> stringResource(R.string.ime_wmpass_master_hint)
}

// ---- parts ----

/** A rounded box in the chips' look, holding a search or the box being typed in. */
@Composable
private fun BoxPill(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A round icon button in the chips' look, naming itself on a hold. */
@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Int = 34, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    IconHint(label) {
        Box(
            Modifier
                .padding(horizontal = 1.dp)
                .size(size.dp)
                .pressScale(source, kb.motion.any)
                .clip(CircleShape)
                .frostGlass(kb, CircleShape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, CircleShape)
                .clickable(interactionSource = source, indication = null) {
                    feedback()
                    onClick()
                }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = kb.chipText, modifier = Modifier.size((size * 0.5f).dp))
        }
    }
}

/** The accent pill for the one thing a card or a head is for: Fill. */
@Composable
private fun AccentPill(
    label: String,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    val shape = kb.chipShape()
    val fg = maxContrastOn(kb.accent)
    IconHint(description, modifier) {
        Row(
            Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .height(32.dp)
                .pressScale(source, kb.motion.any)
                .clip(shape)
                .background(kb.accent)
                .clickable(interactionSource = source, indication = null) {
                    feedback()
                    onClick()
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** A short line after something was done: a tick or a warning, sliding up from the bottom and away. */
@Composable
private fun NoticeToast(notice: WmPassNotice?, modifier: Modifier) {
    val kb = LocalKbTheme.current
    // The last line said, so it can slide away still saying it.
    val last = remember { arrayOfNulls<WmPassNotice>(1) }
    if (notice != null) last[0] = notice
    val animate = kb.motion.any
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
        enter = if (animate) slideInVertically(tween(PAGE_MS)) { it } + fadeIn(tween(PAGE_MS)) else fadeIn(tween(0)),
        exit = if (animate) slideOutVertically(tween(PAGE_MS)) { it } + fadeOut(tween(PAGE_MS)) else fadeOut(tween(0)),
    ) {
        val shown = last[0] ?: return@AnimatedVisibility
        val shape = kb.chipShape()
        Row(
            Modifier
                .padding(10.dp)
                .graphicsLayer {
                    shadowElevation = 6.dp.toPx()
                    this.shape = shape
                    clip = true
                }
                .background(kb.popup)
                .chipBorder(kb, shape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    shown.failure -> Icons.Outlined.ErrorOutline
                    shown == WmPassNotice.UNLOCKED -> Icons.Outlined.LockOpen
                    shown == WmPassNotice.LOCKED -> Icons.Outlined.Lock
                    else -> Icons.Outlined.CheckCircle
                },
                contentDescription = null,
                tint = if (shown.failure) errorColour(kb) else kb.accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(noticeText(shown), color = kb.popupText, fontSize = 12.sp, maxLines = 2)
        }
    }
}

@Composable
private fun noticeText(notice: WmPassNotice): String = stringResource(
    when (notice) {
        WmPassNotice.COPIED -> R.string.ime_wmpass_copied
        WmPassNotice.COPY_FAILED -> R.string.ime_wmpass_copy_failed
        WmPassNotice.TYPE_FAILED -> R.string.ime_wmpass_type_failed
        WmPassNotice.SAVED -> R.string.ime_wmpass_saved
        WmPassNotice.SAVE_FAILED -> R.string.ime_wmpass_save_failed
        WmPassNotice.CHANGED -> R.string.ime_wmpass_changed
        WmPassNotice.CHANGE_FAILED -> R.string.ime_wmpass_change_failed
        WmPassNotice.NO_PASSWORD -> R.string.ime_wmpass_no_password
        WmPassNotice.GONE -> R.string.ime_wmpass_gone
        WmPassNotice.UNLOCKED -> R.string.ime_wmpass_unlocked
        WmPassNotice.LOCKED -> R.string.ime_wmpass_locked_now
        WmPassNotice.PROMPT_FAILED -> R.string.ime_wmpass_prompt_failed
        WmPassNotice.NEEDS_DOOR -> R.string.ime_wmpass_needs_door
        WmPassNotice.UNLOCK_FAILED -> R.string.ime_wmpass_unlock_failed
        WmPassNotice.FILL_NEXT -> R.string.ime_wmpass_fill_next
        WmPassNotice.NO_USERNAME -> R.string.ime_wmpass_no_username
    },
)

/** A red that reads on the theme, for a wrong password and a wait. */
private fun errorColour(kb: KbTheme): Color = if (kb.dark) Color(0xFFFF8A80) else Color(0xFFC62828)

private val FAVOURITE = Color(0xFFFFB400)

/** Where WM Pass is not ready: why, and the one way forward. */
@Composable
private fun StatusMessage(status: WmPassBridge.Status?, act: (WmPassAction) -> Unit) {
    val open = stringResource(R.string.ime_wmpass_open)
    val retry = stringResource(R.string.ime_wmpass_retry)
    when (status) {
        WmPassBridge.Status.NO_VAULT -> Message(
            Icons.Outlined.Shield,
            stringResource(R.string.ime_wmpass_no_vault),
            listOf(
                KitAction(open, Icons.Outlined.OpenInNew, primary = true) { act(WmPassAction.OpenApp) },
                KitAction(retry, Icons.Outlined.Autorenew) { act(WmPassAction.Refresh) },
            ),
        )
        WmPassBridge.Status.OFF -> Message(
            Icons.Outlined.Shield,
            stringResource(R.string.ime_wmpass_off),
            listOf(
                KitAction(open, Icons.Outlined.OpenInNew, primary = true) { act(WmPassAction.OpenApp) },
                KitAction(retry, Icons.Outlined.Autorenew) { act(WmPassAction.Refresh) },
            ),
        )
        WmPassBridge.Status.UNTRUSTED -> Message(Icons.Outlined.ErrorOutline, stringResource(R.string.ime_wmpass_untrusted))
        else -> Message(
            Icons.Outlined.Shield,
            stringResource(R.string.ime_wmpass_missing),
            listOf(
                KitAction(stringResource(R.string.ime_wmpass_get), Icons.Outlined.OpenInNew, primary = true) { act(WmPassAction.GetApp) },
                KitAction(retry, Icons.Outlined.Autorenew) { act(WmPassAction.Refresh) },
            ),
        )
    }
}

/** A mark, a few words, and the ways forward along the bottom. */
@Composable
private fun Message(icon: ImageVector, words: String, actions: List<KitAction> = emptyList()) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(kb.accent.copy(alpha = if (kb.dark) 0.22f else 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = kb.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(words, color = kb.suggestionText, fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        }
        if (actions.isNotEmpty()) ActionBar(actions, height = 40.dp)
    }
}

private const val PAGE_MS = 220
private const val SHAKE_STEP_MS = 45
private const val HALO_MS = 2_400
private const val TICK_MS = 1_000L
private const val PEEK_TICK_MS = 100L

package com.wasimaster.wmkeyboard.ime.ui.wmpass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logins from the WM Pass app, typed into the field. The list holds no secret: a password is asked
 * for when its chip is tapped, goes to the field through the same path as a generated password, and
 * is not kept. Unlocking always happens in WM Pass itself.
 */
@Composable
internal fun WmPassPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kb = LocalKbTheme.current
    var status by remember { mutableStateOf<WmPassBridge.Status?>(null) }
    var logins by remember { mutableStateOf<List<WmPassBridge.Login>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        val (found, rows) = withContext(Dispatchers.IO) {
            val now = WmPassBridge.status(context)
            now to if (now == WmPassBridge.Status.OPEN) WmPassBridge.logins(context) else emptyList()
        }
        status = found
        logins = rows
    }

    fun type(login: WmPassBridge.Login, field: String) {
        scope.launch {
            val value = withContext(Dispatchers.IO) { WmPassBridge.value(context, login.id, field) }
            if (value != null) onInsert(value) else refresh++
        }
    }

    FullBleedTool(
        state,
        title = stringResource(R.string.ime_tool_wm_pass),
        onClose = onClose,
    ) {
        when (status) {
            null -> Unit
            WmPassBridge.Status.OPEN -> {
                if (logins.isEmpty()) {
                    Message(stringResource(R.string.ime_wmpass_empty))
                } else {
                    PanelFocusTarget(
                        panel = PanelMode.WM_PASS,
                        region = FocusRegion.RESULTS,
                        count = logins.size,
                        columns = 1,
                    ) { index -> logins.getOrNull(index)?.let { type(it, "password") } }
                    val focused = state.focusedIndex(FocusRegion.RESULTS)
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                        itemsIndexed(logins, key = { _, login -> login.id }) { index, login ->
                            Column(Modifier.fillMaxWidth().focusRing(focused == index).padding(vertical = 6.dp)) {
                                Text(
                                    login.title,
                                    color = kb.suggestionText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (login.username.isNotEmpty()) {
                                    Text(
                                        login.username,
                                        color = kb.secondaryText,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (login.username.isNotEmpty()) {
                                        ToolPanelChip(stringResource(R.string.ime_wmpass_username), selected = false) { type(login, "username") }
                                    }
                                    if (login.hasPassword) {
                                        ToolPanelChip(stringResource(R.string.ime_wmpass_password), selected = false) { type(login, "password") }
                                    }
                                    if (login.hasCode) {
                                        ToolPanelChip(stringResource(R.string.ime_wmpass_code), selected = false) { type(login, "code") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            WmPassBridge.Status.LOCKED, WmPassBridge.Status.NO_VAULT -> Message(
                stringResource(if (status == WmPassBridge.Status.LOCKED) R.string.ime_wmpass_locked else R.string.ime_wmpass_no_vault),
                stringResource(R.string.ime_wmpass_open),
                { WmPassBridge.openApp(context) },
                { refresh++ },
            )
            WmPassBridge.Status.OFF -> Message(
                stringResource(R.string.ime_wmpass_off),
                stringResource(R.string.ime_wmpass_open),
                { WmPassBridge.openApp(context) },
                { refresh++ },
            )
            WmPassBridge.Status.UNTRUSTED -> Message(stringResource(R.string.ime_wmpass_untrusted))
            WmPassBridge.Status.MISSING -> Message(stringResource(R.string.ime_wmpass_missing))
        }
    }
}

@Composable
private fun Message(
    words: String,
    action: String? = null,
    onAction: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(words, color = kb.suggestionText, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (action != null) ToolPanelChip(action, selected = false, onClick = onAction)
            if (onRetry != null) ToolPanelChip(stringResource(R.string.ime_wmpass_retry), selected = false, onClick = onRetry)
        }
    }
}

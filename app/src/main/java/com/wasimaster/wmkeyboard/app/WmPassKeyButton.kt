package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WM Pass beside a key field. It shows once WM Pass is installed, holds a vault and lets this app
 * ask; on any other phone the field looks as it always did. Its menu fills the field from an API
 * key item in the vault, or keeps what the field holds as the [field] of the item called [title].
 * A locked vault opens WM Pass to be unlocked there: this app never sees its master password.
 *
 * [title] is the service the key is for. It names the item a save makes, so a service's key and its
 * secret, saved one after the other, end up in one item, and it puts that service's keys first.
 */
@Composable
internal fun WmPassKeyButton(
    title: String,
    value: String,
    field: String = WmPassBridge.API_KEY,
    onFill: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<WmPassBridge.Status?>(null) }
    var menu by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf<List<WmPassBridge.ApiKey>?>(null) }
    // Asked again on every return, since the usual way back here is from WM Pass, just set up or unlocked.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { status = withContext(Dispatchers.IO) { WmPassBridge.status(context) } }
    }
    if (status != WmPassBridge.Status.OPEN && status != WmPassBridge.Status.LOCKED) return

    // The vault may have locked since the button showed, so each press asks first.
    fun whenOpen(action: suspend () -> Unit) {
        scope.launch {
            val now = withContext(Dispatchers.IO) { WmPassBridge.status(context) }
            status = now
            when (now) {
                WmPassBridge.Status.OPEN -> action()
                WmPassBridge.Status.LOCKED -> if (WmPassBridge.openApp(context)) toast(context, context.getString(R.string.wmpass_key_unlock_hint))
                else -> Unit
            }
        }
    }

    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Outlined.Key, contentDescription = stringResource(R.string.wmpass_key_menu_desc))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wmpass_key_fill_action)) },
                onClick = {
                    menu = false
                    whenOpen {
                        val found = withContext(Dispatchers.IO) { WmPassBridge.apiKeys(context, title) }
                        if (found.isEmpty()) toast(context, context.getString(R.string.wmpass_key_empty)) else keys = found
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wmpass_key_save_action)) },
                enabled = value.isNotBlank(),
                onClick = {
                    menu = false
                    whenOpen {
                        val saved = withContext(Dispatchers.IO) { WmPassBridge.saveApiKey(context, title, field, value.trim()) }
                        toast(
                            context,
                            when (saved) {
                                WmPassBridge.Saved.CREATED -> context.getString(R.string.wmpass_key_saved_info, title)
                                WmPassBridge.Saved.UPDATED -> context.getString(R.string.wmpass_key_updated_info, title)
                                null -> context.getString(R.string.wmpass_key_save_error)
                            },
                        )
                    }
                },
            )
        }
    }

    keys?.let { list ->
        val anyNamed = list.any { it.matches }
        ChoiceSheet(
            title = stringResource(R.string.wmpass_key_pick_title),
            subtitle = if (anyNamed) stringResource(R.string.wmpass_key_pick_subtitle) else null,
            options = list.map { it.id to it.title },
            selected = "",
            detail = { id ->
                list.firstOrNull { it.id == id }?.provider?.takeIf { it.isNotBlank() }?.let { ChoiceDetail(description = it) }
            },
            onPick = { id ->
                keys = null
                scope.launch {
                    val picked = withContext(Dispatchers.IO) { WmPassBridge.value(context, id, field) }
                    if (picked == null) toast(context, context.getString(R.string.wmpass_key_no_value_error)) else onFill(picked)
                }
            },
            onDismiss = { keys = null },
        )
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

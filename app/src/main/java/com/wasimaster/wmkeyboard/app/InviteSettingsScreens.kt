package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.calendar.invite.InviteFailure
import com.wasimaster.wmkeyboard.ime.calendar.invite.MobilizonClient
import com.wasimaster.wmkeyboard.ime.calendar.invite.PretixClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tools → Calendar → Invite online (#1274): the Mobilizon account and the
 * Pretix organizer a calendar event can be published to.
 *
 * Mobilizon is signed in to here, once: the password is sent with the
 * `login` mutation and dropped, and only the tokens that come back are kept.
 * Pretix takes a team API token, kept like every other provider key, and a
 * Check button that asks for the organizer with it.
 */
@Composable
internal fun InviteSettingsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val inv = settings.watch { it.calendarTool.invites }

    SettingsGroup(stringResource(R.string.invite_group), info = stringResource(R.string.invite_info)) {
        // ---- Mobilizon ----
        item {
            TextFieldSetting(
                label = stringResource(R.string.invite_mobilizon_url_title),
                value = inv.mobilizonUrl,
                hint = stringResource(R.string.invite_mobilizon_url_hint),
                default = "",
            ) { v ->
                // Tokens belong to one instance: another address signs out.
                repository.updateInvites {
                    if (it.mobilizonUrl.trim() == v.trim()) it else it.copy(mobilizonUrl = v, mobilizonAccessToken = "", mobilizonRefreshToken = "")
                }
            }
        }
        if (inv.mobilizonSignedIn) {
            item {
                ActionRow(
                    R.string.invite_mobilizon_signed_in_title,
                    subtitle = stringResource(R.string.invite_mobilizon_signed_in_subtitle, inv.mobilizonEmail.ifBlank { inv.mobilizonUrl }),
                    action = stringResource(R.string.invite_sign_out),
                ) {
                    scope.launch { repository.updateInvites { it.copy(mobilizonAccessToken = "", mobilizonRefreshToken = "") } }
                }
            }
        } else if (inv.mobilizonUrl.isNotBlank()) {
            item {
                var email by remember(inv.mobilizonEmail) { mutableStateOf(inv.mobilizonEmail) }
                var password by remember { mutableStateOf("") }
                var status by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                HighlightableRow(stringResource(R.string.invite_mobilizon_email_title)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.invite_mobilizon_email_title)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                HighlightableRow(stringResource(R.string.invite_mobilizon_password_title)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.invite_mobilizon_password_title)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text(stringResource(R.string.invite_mobilizon_password_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                ActionRow(
                    R.string.invite_mobilizon_sign_in_title,
                    subtitle = status ?: stringResource(R.string.invite_mobilizon_sign_in_subtitle),
                    action = stringResource(R.string.invite_sign_in),
                    enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                ) {
                    busy = true
                    status = context.getString(R.string.invite_checking)
                    scope.launch {
                        val url = inv.mobilizonUrl
                        val got = withContext(Dispatchers.IO) { runCatching { MobilizonClient(url).login(email, password) } }
                        got.fold(
                            onSuccess = { tokens ->
                                password = ""
                                status = null
                                repository.updateInvites {
                                    it.copy(mobilizonEmail = email.trim(), mobilizonAccessToken = tokens.access, mobilizonRefreshToken = tokens.refresh)
                                }
                            },
                            onFailure = { e -> status = failureText(context, e, "Mobilizon") },
                        )
                        busy = false
                    }
                }
            }
        }

        // ---- Pretix ----
        item {
            TextFieldSetting(
                label = stringResource(R.string.invite_pretix_url_title),
                value = inv.pretixUrl,
                hint = stringResource(R.string.invite_pretix_url_hint),
                default = "",
            ) { v -> repository.updateInvites { it.copy(pretixUrl = v) } }
        }
        if (inv.pretixUrl.isNotBlank()) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.invite_pretix_organizer_title),
                    value = inv.pretixOrganizer,
                    hint = stringResource(R.string.invite_pretix_organizer_hint),
                    default = "",
                ) { v -> repository.updateInvites { it.copy(pretixOrganizer = v) } }
            }
            item {
                ApiKeyField(
                    label = stringResource(R.string.invite_pretix_token_title),
                    value = inv.pretixToken,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.invite_pretix_token_hint),
                    wmPassTitle = "Pretix",
                ) { v -> repository.updateInvites { it.copy(pretixToken = v) } }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.invite_pretix_currency_title),
                    value = inv.pretixCurrency,
                    hint = stringResource(R.string.invite_pretix_currency_hint),
                    default = "",
                ) { v -> repository.updateInvites { it.copy(pretixCurrency = v) } }
            }
        }
        if (inv.pretixConfigured) {
            item {
                var status by remember(inv.pretixUrl, inv.pretixToken, inv.pretixOrganizer) { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                ActionRow(
                    R.string.invite_pretix_check_title,
                    subtitle = status ?: stringResource(R.string.invite_pretix_check_subtitle),
                    action = stringResource(R.string.invite_check),
                    enabled = !busy,
                ) {
                    busy = true
                    status = context.getString(R.string.invite_checking)
                    scope.launch {
                        val client = PretixClient(inv.pretixUrl, inv.pretixToken, inv.pretixOrganizer)
                        val got = withContext(Dispatchers.IO) { runCatching { client.check() } }
                        status = got.fold(
                            onSuccess = { name -> context.getString(R.string.invite_pretix_check_ok, name) },
                            onFailure = { e -> failureText(context, e, "Pretix") },
                        )
                        busy = false
                    }
                }
            }
        }
    }
}

/** A failed sign-in or check, in words for the row under it. */
private fun failureText(context: android.content.Context, e: Throwable, service: String): String {
    val f = e as? InviteFailure
    return when (f?.kind) {
        InviteFailure.Kind.BAD_ADDRESS -> context.getString(R.string.invite_fail_address, service)
        InviteFailure.Kind.BAD_LOGIN, InviteFailure.Kind.SIGNED_OUT -> context.getString(R.string.invite_fail_login, service)
        InviteFailure.Kind.REFUSED -> context.getString(R.string.invite_fail_refused, service)
        else -> context.getString(R.string.invite_fail_other, service, (f?.message ?: e.message ?: e.javaClass.simpleName))
    }
}

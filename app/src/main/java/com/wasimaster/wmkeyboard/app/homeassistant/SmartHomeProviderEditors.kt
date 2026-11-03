package com.wasimaster.wmkeyboard.app.homeassistant

import com.wasimaster.wmkeyboard.core.modules.ModuleState
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.StoredTextField
import com.wasimaster.wmkeyboard.app.findActivity
import com.wasimaster.wmkeyboard.core.homeassistant.HaFound
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttpException
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HaConnection
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueApi
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueBridgeInfo
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueUrl
import com.wasimaster.wmkeyboard.core.smarthome.tuya.TuyaRegions
import com.wasimaster.wmkeyboard.ime.homeassistant.HaAndroidMdns
import com.wasimaster.wmkeyboard.ime.homeassistant.HomeBackends
import com.wasimaster.wmkeyboard.ime.homeassistant.HueAndroidMdns
import com.wasimaster.wmkeyboard.ime.homeassistant.googlehome.GoogleHomeSupport
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the provider editor shows under its plain fields for a home: the
 * server found on the LAN, the sign-in or the pairing, the test. Each kind
 * has its own way in, and each way in ends with the credential saved
 * through [onSave], which is the editor's own Save: the sign-ins need the
 * row stored before the browser comes back.
 */
@Composable
internal fun HomeProviderExtras(
    repository: SettingsRepository,
    type: ProviderType,
    /** What WM Pass calls this home's token, for the WM Pass button on it. */
    keyTitle: String,
    draft: ProviderCredential,
    isNew: Boolean,
    onDraft: (ProviderCredential) -> Unit,
    onSave: (ProviderCredential) -> Unit,
    onDismiss: () -> Unit,
) {
    when (HomeKind.of(type.id)) {
        HomeKind.HOME_ASSISTANT -> HomeAssistantExtras(repository, keyTitle, draft, isNew, onDraft, onSave, onDismiss)
        HomeKind.SMARTTHINGS -> SmartThingsExtras(repository, keyTitle, draft, isNew, onSave, onDismiss)
        HomeKind.HUE -> HueExtras(draft, onDraft)
        HomeKind.OPENHAB -> OpenHabExtras()
        HomeKind.TUYA -> TuyaExtras(keyTitle, draft, onDraft)
        HomeKind.SWITCHBOT -> SwitchBotExtras(keyTitle, draft, onDraft)
        HomeKind.GOOGLE_HOME -> GoogleHomeExtras(draft, onSave)
        null -> Unit
    }
}

/** Whether the editor's Save may be pressed for a home: the fields its kind cannot do without. */
internal fun homeReady(type: ProviderType, draft: ProviderCredential): Boolean = when (HomeKind.of(type.id)) {
    HomeKind.HOME_ASSISTANT -> draft.url.isNotBlank()
    HomeKind.HUE -> draft.url.isNotBlank() && draft.apiKey.isNotBlank()
    HomeKind.OPENHAB -> draft.url.isNotBlank()
    HomeKind.TUYA -> draft.url.isNotBlank() && draft.apiKey.isNotBlank() && !draft.extra[HomeKeys.CLIENT_ID].isNullOrBlank()
    HomeKind.SWITCHBOT -> draft.apiKey.isNotBlank() && !draft.extra[HomeKeys.SECRET].isNullOrBlank()
    HomeKind.SMARTTHINGS, HomeKind.GOOGLE_HOME, null -> true
}

// ---- Home Assistant ----------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("detekt.LongMethod")
private fun HomeAssistantExtras(
    repository: SettingsRepository,
    keyTitle: String,
    draft: ProviderCredential,
    isNew: Boolean,
    onDraft: (ProviderCredential) -> Unit,
    onSave: (ProviderCredential) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = draft.homeAccount()
    val signedIn = account.signedIn
    var token by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Found on the LAN, while nothing is signed in: a tap fills the address in.
    if (!signedIn) {
        val found = remember { mutableStateOf<List<HaFound>>(emptyList()) }
        DisposableEffect(Unit) {
            val mdns = HaAndroidMdns(context)
            mdns.start { f ->
                scope.launch {
                    found.value = (found.value.filterNot { it.uuid != null && it.uuid == f.uuid || it.host == f.host } + f).filterNot { it.isLandingPage }
                }
            }
            onDispose { mdns.stop() }
        }
        if (found.value.isNotEmpty()) {
            Text(stringResource(R.string.ha_find_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                found.value.forEach { f ->
                    val internal = f.internal ?: return@forEach
                    FilterChip(
                        selected = draft.url == internal.base,
                        onClick = {
                            onDraft(
                                draft.copy(
                                    url = internal.base,
                                    name = draft.name.ifBlank { f.name },
                                    extra = draft.extra + listOfNotNull(f.external?.let { HomeKeys.EXTERNAL_URL to it.base }, f.uuid?.let { HomeKeys.INSTANCE_ID to it }),
                                ),
                            )
                        },
                        label = { Text("${f.name} · ${internal.host}") },
                    )
                }
            }
        }
    }

    StoredTextField(
        label = stringResource(R.string.ha_external_url_label),
        value = draft.extra[HomeKeys.EXTERNAL_URL].orEmpty(),
        supporting = stringResource(R.string.ha_external_url_hint),
        keyboardType = KeyboardType.Uri,
    ) { v -> onDraft(draft.copy(extra = if (v.isBlank()) draft.extra - HomeKeys.EXTERNAL_URL else draft.extra + (HomeKeys.EXTERNAL_URL to v.trim()))) }
    if (draft.extra[HomeKeys.EXTERNAL_URL].orEmpty().isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ha_prefer_internal_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.ha_prefer_internal_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = draft.extra[HomeKeys.PREFER_INTERNAL] != "false",
                onCheckedChange = { on -> onDraft(draft.copy(extra = if (on) draft.extra - HomeKeys.PREFER_INTERNAL else draft.extra + (HomeKeys.PREFER_INTERNAL to "false"))) },
            )
        }
    }

    Text(
        when {
            !signedIn -> stringResource(R.string.ha_sign_in_subtitle)
            draft.apiKey.isNotBlank() -> stringResource(R.string.ha_signed_in_token)
            else -> stringResource(R.string.ha_signed_in_browser)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
        Button(
            enabled = draft.url.isNotBlank() && !busy,
            onClick = {
                val trimmed = draft.copy(url = draft.url.trim(), name = draft.name.trim())
                if (HaSignIn.start(context, repository, trimmed, isNew)) onDismiss()
            },
        ) { Text(stringResource(if (signedIn) R.string.ha_sign_in_again_button else R.string.ha_sign_in_button)) }
        if (signedIn) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    result = null
                    scope.launch {
                        result = HaSignIn.test(context, draft).fold(
                            onSuccess = { true to context.getString(R.string.ha_test_ok, it.name) },
                            onFailure = { f -> false to context.getString(R.string.ha_test_failed, (f as? HaHttpException)?.let { "HTTP ${it.status}" } ?: f.message.orEmpty()) },
                        )
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.ha_test_button)) }
            TextButton(onClick = {
                scope.launch {
                    HaSignIn.signOut(context, repository, draft)
                    onDraft(draft.copy(apiKey = "", extra = HaConnection.withoutTokens(draft.extra)))
                }
            }) { Text(stringResource(R.string.ha_sign_out_button)) }
        }
    }
    if (!signedIn || draft.apiKey.isNotBlank()) {
        StoredTextField(
            label = stringResource(R.string.ha_token_label),
            value = token,
            supporting = stringResource(R.string.ha_token_supporting),
            password = true,
            keyboardType = KeyboardType.Password,
            multiLine = true,
            wmPassTitle = keyTitle,
        ) { token = it }
        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
            Button(
                enabled = token.isNotBlank() && draft.url.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        result = HaSignIn.useToken(context, repository, draft.copy(url = draft.url.trim()), token, isNew).fold(
                            onSuccess = {
                                token = ""
                                true to context.getString(R.string.ha_token_ok, it)
                            },
                            onFailure = { f -> false to context.getString(R.string.ha_token_failed, (f as? HaHttpException)?.let { "HTTP ${it.status}" } ?: f.message.orEmpty()) },
                        )
                        busy = false
                        if (result?.first == true) onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.ha_token_use_button)) }
        }
    }
    ResultLine(busy, result)
}

// ---- SmartThings -------------------------------------------------------------------

@Composable
private fun SmartThingsExtras(
    repository: SettingsRepository,
    keyTitle: String,
    draft: ProviderCredential,
    isNew: Boolean,
    onSave: (ProviderCredential) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val signedIn = draft.homeAccount().signedIn
    var token by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text(
        when {
            !signedIn -> stringResource(if (SmartThingsSignIn.available) R.string.st_sign_in_subtitle else R.string.st_sign_in_no_client)
            draft.apiKey.isNotBlank() -> stringResource(R.string.st_signed_in_token)
            else -> stringResource(R.string.st_signed_in_browser)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
        if (SmartThingsSignIn.available) {
            Button(enabled = !busy, onClick = { if (SmartThingsSignIn.start(context, repository, draft, isNew)) onDismiss() }) {
                Text(stringResource(if (signedIn) R.string.ha_sign_in_again_button else R.string.ha_sign_in_button))
            }
        }
        if (signedIn) {
            TextButton(onClick = { scope.launch { SmartThingsSignIn.signOut(repository, draft); onDismiss() } }) { Text(stringResource(R.string.ha_sign_out_button)) }
        }
    }
    StoredTextField(
        label = stringResource(R.string.st_token_label),
        value = token,
        supporting = stringResource(R.string.st_token_supporting),
        password = true,
        keyboardType = KeyboardType.Password,
        multiLine = true,
        wmPassTitle = keyTitle,
    ) { token = it }
    Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Button(
            enabled = token.isNotBlank() && !busy,
            onClick = {
                busy = true
                scope.launch {
                    result = SmartThingsSignIn.useToken(repository, draft, token, isNew).fold(
                        onSuccess = {
                            token = ""
                            true to context.getString(R.string.ha_token_ok, it)
                        },
                        onFailure = { f -> false to context.getString(R.string.ha_token_failed, f.message.orEmpty()) },
                    )
                    busy = false
                    if (result?.first == true) onDismiss()
                }
            },
        ) { Text(stringResource(R.string.ha_token_use_button)) }
    }
    ResultLine(busy, result)
    // The plain Save keeps a name; there is nothing else of SmartThings' to type.
    @Suppress("UNUSED_EXPRESSION") onSave
}

// ---- Hue -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HueExtras(draft: ProviderCredential, onDraft: (ProviderCredential) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired = draft.apiKey.isNotBlank()
    var pairing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val found = remember { mutableStateOf<List<HueBridgeInfo>>(emptyList()) }
    if (!paired) {
        DisposableEffect(Unit) {
            val mdns = HueAndroidMdns(context)
            mdns.start { host ->
                scope.launch {
                    val base = HueUrl.base(host) ?: return@launch
                    val http = HomeBackends.hueHttp(null)
                    val info = runCatching { HueApi.info(http, base) }.getOrNull() ?: return@launch
                    if (found.value.none { it.id == info.id }) found.value = found.value + info
                }
            }
            onDispose { mdns.stop() }
        }
        Text(
            if (found.value.isEmpty()) stringResource(R.string.hue_find_searching) else stringResource(R.string.hue_find_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (found.value.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                found.value.forEach { b ->
                    FilterChip(
                        selected = draft.url == b.host,
                        onClick = { onDraft(draft.copy(url = b.host, name = draft.name.ifBlank { b.name }, extra = draft.extra + (HomeKeys.BRIDGE_ID to b.id))) },
                        label = { Text("${b.name} · ${b.host}") },
                    )
                }
            }
        }
    }
    Text(
        stringResource(if (paired) R.string.hue_paired else R.string.hue_pair_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
        Button(
            enabled = draft.url.isNotBlank() && !pairing,
            onClick = {
                pairing = true
                result = null
                scope.launch {
                    val base = HueUrl.base(draft.url)
                    if (base == null) {
                        result = false to context.getString(R.string.hue_pair_failed, "")
                        pairing = false
                        return@launch
                    }
                    var seen: String? = null
                    val http = HomeBackends.hueHttp(null) { seen = it }
                    var key: String? = null
                    var failure: Throwable? = null
                    // The bridge's button stays armed for thirty seconds; ask it a few times.
                    repeat(PAIR_TRIES) {
                        if (key != null || failure != null) return@repeat
                        key = runCatching { HueApi.pair(http, base) }.onFailure { failure = it }.getOrNull()
                        if (key == null && failure == null) delay(PAIR_DELAY_MS)
                    }
                    val k = key
                    if (k != null) {
                        val info = runCatching { HueApi.info(http, base) }.getOrNull()
                        onDraft(
                            draft.copy(
                                apiKey = k,
                                name = draft.name.ifBlank { info?.name.orEmpty() },
                                extra = draft.extra + listOfNotNull(seen?.let { HomeKeys.CERT_SHA256 to it }, info?.id?.let { HomeKeys.BRIDGE_ID to it }),
                            ),
                        )
                        result = true to context.getString(R.string.hue_pair_ok)
                    } else {
                        result = false to (failure?.message?.let { context.getString(R.string.hue_pair_failed, it) } ?: context.getString(R.string.hue_pair_no_button))
                    }
                    pairing = false
                }
            },
        ) { Text(stringResource(if (paired) R.string.hue_pair_again_button else R.string.hue_pair_button)) }
    }
    ResultLine(pairing, result)
}

// ---- openHAB ---------------------------------------------------------------------------

@Composable
private fun OpenHabExtras() {
    Text(
        stringResource(R.string.oh_token_supporting),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

// ---- Tuya ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuyaExtras(keyTitle: String, draft: ProviderCredential, onDraft: (ProviderCredential) -> Unit) {
    Hint(stringResource(R.string.tuya_setup_steps))
    Text(stringResource(R.string.tuya_region_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
        TuyaRegions.all.forEach { r ->
            FilterChip(
                selected = TuyaRegions.base(draft.url) == r.base,
                onClick = { onDraft(draft.copy(url = r.base)) },
                label = { Text(r.label) },
            )
        }
    }
    StoredTextField(
        label = stringResource(R.string.tuya_access_id_label),
        value = draft.extra[HomeKeys.CLIENT_ID].orEmpty(),
        supporting = stringResource(R.string.tuya_access_id_supporting),
        wmPassTitle = keyTitle,
    ) { v -> onDraft(draft.copy(extra = if (v.isBlank()) draft.extra - HomeKeys.CLIENT_ID else draft.extra + (HomeKeys.CLIENT_ID to v.trim()))) }
    StoredTextField(
        label = stringResource(R.string.tuya_access_secret_label),
        value = draft.apiKey,
        supporting = stringResource(R.string.tuya_access_secret_supporting),
        password = true,
        keyboardType = KeyboardType.Password,
        wmPassTitle = keyTitle,
        wmPassField = WmPassBridge.API_SECRET,
    ) { onDraft(draft.copy(apiKey = it.trim())) }
}

// ---- SwitchBot -------------------------------------------------------------------------

@Composable
private fun SwitchBotExtras(keyTitle: String, draft: ProviderCredential, onDraft: (ProviderCredential) -> Unit) {
    Hint(stringResource(R.string.switchbot_setup_steps))
    StoredTextField(
        label = stringResource(R.string.switchbot_token_label),
        value = draft.apiKey,
        supporting = stringResource(R.string.switchbot_token_supporting),
        password = true,
        keyboardType = KeyboardType.Password,
        multiLine = true,
        wmPassTitle = keyTitle,
    ) { onDraft(draft.copy(apiKey = it.trim())) }
    StoredTextField(
        label = stringResource(R.string.switchbot_secret_label),
        value = draft.extra[HomeKeys.SECRET].orEmpty(),
        supporting = stringResource(R.string.switchbot_secret_supporting),
        password = true,
        keyboardType = KeyboardType.Password,
        wmPassTitle = keyTitle,
        wmPassField = WmPassBridge.API_SECRET,
    ) { v -> onDraft(draft.copy(extra = if (v.isBlank()) draft.extra - HomeKeys.SECRET else draft.extra + (HomeKeys.SECRET to v.trim()))) }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

// ---- Google Home ----------------------------------------------------------------------

/**
 * Google Home has no field to fill, so the sheet shows Close rather than Save,
 * and Close drops the draft. A grant and a sign-out are therefore saved
 * straight away through [onSave]. Only a grant that lands while this editor
 * is open counts: [GoogleHomeSupport.grants] keeps the last one for the
 * process, and an editor opened later must not take it for its own.
 */
@Composable
private fun GoogleHomeExtras(draft: ProviderCredential, onSave: (ProviderCredential) -> Unit) {
    val context = LocalContext.current
    val grant by GoogleHomeSupport.grants.collectAsState()
    val before = remember { GoogleHomeSupport.grants.value }
    val granted = draft.homeAccount().signedIn
    // Play: the SDK is an on-demand module, fetched on the first tap of Choose.
    val module by GoogleHomeSupport.moduleState.collectAsState()
    LaunchedEffect(grant) {
        val g = grant?.takeIf { it !== before } ?: return@LaunchedEffect
        if (draft.extra[HomeKeys.STRUCTURE_ID] != g.structureId || draft.extra[HomeKeys.GRANTED] != "1") {
            onSave(
                draft.copy(
                    name = draft.name.ifBlank { g.structureName },
                    extra = draft.extra + mapOf(HomeKeys.GRANTED to "1", HomeKeys.STRUCTURE_ID to g.structureId, HomeKeys.ACCOUNT to g.account, HomeKeys.INSTANCE_NAME to g.structureName),
                ),
            )
        }
    }
    Text(
        when {
            !GoogleHomeSupport.AVAILABLE -> stringResource(R.string.gh_unavailable)
            module is ModuleState.Installing -> stringResource(R.string.gh_module_installing)
            (module as? ModuleState.Missing)?.failed == true -> stringResource(R.string.gh_module_failed)
            granted -> stringResource(R.string.gh_granted, draft.extra[HomeKeys.INSTANCE_NAME].orEmpty())
            else -> stringResource(R.string.gh_choose_subtitle)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
        val activity = context.findActivity() as? ComponentActivity
        Button(
            enabled = GoogleHomeSupport.AVAILABLE && activity != null && module !is ModuleState.Installing,
            onClick = { activity?.let { GoogleHomeSupport.requestAccess(it, force = granted) } },
        ) { Text(stringResource(if (granted) R.string.gh_change_button else R.string.gh_choose_button)) }
        if (granted) {
            TextButton(onClick = {
                onSave(draft.copy(extra = draft.extra - HomeKeys.GRANTED - HomeKeys.STRUCTURE_ID - HomeKeys.ACCOUNT))
            }) { Text(stringResource(R.string.ha_sign_out_button)) }
        }
    }
    if (granted) {
        Text(
            stringResource(R.string.gh_revoke_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ResultLine(busy: Boolean, result: Pair<Boolean, String>?) {
    if (busy) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.providers_testing), style = MaterialTheme.typography.bodySmall)
        }
    }
    result?.let { (ok, text) ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
    }
}

private const val PAIR_TRIES = 12
private const val PAIR_DELAY_MS = 2_500L

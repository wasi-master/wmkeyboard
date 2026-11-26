package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lan
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tv
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.ime.remote.AndroidTvPairing
import com.wasimaster.wmkeyboard.ime.remote.FoundDevice
import com.wasimaster.wmkeyboard.ime.remote.IrBlaster
import com.wasimaster.wmkeyboard.ime.remote.IrCatalog
import com.wasimaster.wmkeyboard.ime.remote.IrCodes
import com.wasimaster.wmkeyboard.ime.remote.RemoteDevice
import com.wasimaster.wmkeyboard.ime.remote.RemoteDiscovery
import com.wasimaster.wmkeyboard.ime.remote.RemoteException
import com.wasimaster.wmkeyboard.ime.remote.RemoteKey
import com.wasimaster.wmkeyboard.ime.remote.RemoteKind
import com.wasimaster.wmkeyboard.ime.remote.RemotePairing
import com.wasimaster.wmkeyboard.ime.remote.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.ime.R as ImeR

/**
 * The Remote tool's page: the remembered remotes, and the ways to add one.
 * Infrared rows appear only on a phone with an emitter, network rows only in a
 * build with the internet permission. Pairing happens here, where a code can
 * be typed into a real text box, never in the keyboard's panel.
 */
@Composable
internal fun RemoteToolSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { RemoteStore.load(context) }
    val store by RemoteStore.flow.collectAsState()
    val hasIr = remember { IrBlaster.available(context) }
    val network = !OfflineBuild.isThisBuild
    var dialog by remember { mutableStateOf<RemoteDialog?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val added = withContext(Dispatchers.IO) { importIrFile(context, uri) }
                if (added == null) {
                    Toast.makeText(context, R.string.tooldetail_remote_import_empty, Toast.LENGTH_LONG).show()
                } else {
                    dialog = RemoteDialog.Edit(added.id)
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.tooldetail_remote_devices_group),
        info = stringResource(R.string.tooldetail_remote_devices_info),
    ) {
        if (store.devices.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.tooldetail_remote_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        for (device in store.devices) {
            item(key = device.id) {
                NavRow(
                    title = device.name,
                    subtitle = remoteSubtitle(device),
                    icon = Icons.Outlined.Tv,
                ) { dialog = RemoteDialog.Edit(device.id) }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.tooldetail_remote_add_group),
        info = stringResource(R.string.tooldetail_remote_add_info),
    ) {
        if (hasIr) {
            item {
                NavRow(
                    R.string.tooldetail_remote_add_ir_title,
                    subtitle = stringResource(R.string.tooldetail_remote_add_ir_subtitle),
                    icon = Icons.Outlined.Add,
                ) { dialog = RemoteDialog.Brand(null) }
            }
            item {
                NavRow(
                    R.string.tooldetail_remote_import_title,
                    subtitle = stringResource(R.string.tooldetail_remote_import_subtitle),
                    icon = Icons.Outlined.FileOpen,
                ) { runCatching { importer.launch(arrayOf("*/*")) } }
            }
        }
        if (network) {
            item {
                NavRow(
                    R.string.tooldetail_remote_find_title,
                    subtitle = stringResource(R.string.tooldetail_remote_find_subtitle),
                    icon = Icons.Outlined.Search,
                ) { dialog = RemoteDialog.Scan }
            }
            item {
                NavRow(
                    R.string.tooldetail_remote_address_title,
                    subtitle = stringResource(R.string.tooldetail_remote_address_subtitle),
                    icon = Icons.Outlined.Lan,
                ) { dialog = RemoteDialog.Address }
            }
        }
        if (!hasIr && !network) {
            item {
                Text(
                    stringResource(R.string.tooldetail_remote_nothing_here),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is RemoteDialog.Brand -> BrandDialog(
            onPick = { brand -> dialog = RemoteDialog.Sets(brand, d.forDevice) },
            onDismiss = { dialog = null },
        )
        is RemoteDialog.Sets -> SetsDialog(
            brand = d.brand,
            onPick = { index ->
                if (d.forDevice != null) {
                    RemoteStore.update(context, d.forDevice) { it.copy(irBrand = d.brand, irSet = index) }
                } else {
                    RemoteStore.add(
                        context,
                        RemoteDevice(id = RemoteStore.newId(), kind = RemoteKind.IR.id, name = d.brand, irBrand = d.brand, irSet = index),
                    )
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        RemoteDialog.Scan -> ScanDialog(
            onPick = { found ->
                val added = addFound(context, found)
                dialog = RemoteDialog.Pairing(added.id)
            },
            onDismiss = { dialog = null },
        )
        RemoteDialog.Address -> AddressDialog(
            onAdd = { kind, host ->
                scope.launch {
                    val probed = RemoteDiscovery.probe(kind, host)
                    val added = addFound(context, probed ?: FoundDevice(kind, host, contextString(context, remoteKindLabel(kind))))
                    dialog = RemoteDialog.Pairing(added.id)
                }
            },
            onDismiss = { dialog = null },
        )
        is RemoteDialog.Pairing -> PairDialog(d.id, onDone = { dialog = null })
        is RemoteDialog.Edit -> EditDialog(
            id = d.id,
            onCodeSet = { brand -> dialog = if (brand.isEmpty()) RemoteDialog.Brand(d.id) else RemoteDialog.Sets(brand, d.id) },
            onPaste = { dialog = RemoteDialog.Paste(d.id) },
            onPair = { dialog = RemoteDialog.Pairing(d.id) },
            onDismiss = { dialog = null },
        )
        is RemoteDialog.Paste -> PasteDialog(d.id, onDone = { dialog = RemoteDialog.Edit(d.id) })
    }
}

private sealed interface RemoteDialog {
    /** Picking a brand: for a new remote, or a new code set for [forDevice]. */
    data class Brand(val forDevice: String?) : RemoteDialog
    data class Sets(val brand: String, val forDevice: String?) : RemoteDialog
    data object Scan : RemoteDialog
    data object Address : RemoteDialog
    data class Pairing(val id: String) : RemoteDialog
    data class Edit(val id: String) : RemoteDialog
    data class Paste(val id: String) : RemoteDialog
}

private fun contextString(context: Context, id: Int): String = context.getString(id)

internal fun remoteKindLabel(kind: RemoteKind): Int = when (kind) {
    RemoteKind.IR -> ImeR.string.ime_remote_kind_ir
    RemoteKind.ANDROID_TV -> ImeR.string.ime_remote_kind_android_tv
    RemoteKind.SAMSUNG -> ImeR.string.ime_remote_kind_samsung
    RemoteKind.LG -> ImeR.string.ime_remote_kind_lg
    RemoteKind.ROKU -> ImeR.string.ime_remote_kind_roku
}

@Composable
private fun remoteSubtitle(device: RemoteDevice): String {
    val kind = device.remoteKind ?: return device.kind
    val label = stringResource(remoteKindLabel(kind))
    return when {
        kind == RemoteKind.IR && device.irBrand.isNotEmpty() -> stringResource(R.string.tooldetail_remote_ir_subtitle, label, device.irBrand, device.irSet + 1)
        kind == RemoteKind.IR -> stringResource(R.string.tooldetail_remote_imported_subtitle, label, device.irCodes.size + device.irExtra.size)
        else -> listOf(label, device.model, device.host).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

private fun addFound(context: Context, found: FoundDevice): RemoteDevice {
    val existing = RemoteStore.flow.value.devices.firstOrNull { it.kind == found.kind.id && it.host == found.host }
    if (existing != null) return existing
    return RemoteStore.add(
        context,
        RemoteDevice(
            id = RemoteStore.newId(),
            kind = found.kind.id,
            name = found.name,
            host = found.host,
            mac = found.mac,
            model = found.model,
        ),
    )
}

/** A Flipper Zero `.ir` file as a new infrared remote, named after the file. Null when nothing in it can be sent. */
private fun importIrFile(context: Context, uri: Uri): RemoteDevice? {
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull() ?: return null
    val imported = IrCodes.parseFlipper(text)
    if (imported.isEmpty) return null
    val fileName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    val name = fileName?.substringBeforeLast('.')?.replace('_', ' ')?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.tooldetail_remote_imported_name)
    return RemoteStore.add(
        context,
        RemoteDevice(id = RemoteStore.newId(), kind = RemoteKind.IR.id, name = name, irCodes = imported.codes, irExtra = imported.extra),
    )
}

@Composable
private fun BrandDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val brands = remember { IrCatalog.brands(context) }
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tooldetail_remote_brand_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tooldetail_remote_brand_filter)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    brands.filter { filter.isBlank() || it.brand.contains(filter.trim(), ignoreCase = true) }.forEach { brand ->
                        Text(
                            brand.brand,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(brand.brand) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** A brand's code sets, each with a button that sends its Power code, so the one the TV answers can be found. */
@Composable
private fun SetsDialog(brand: String, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sets = remember(brand) { IrCatalog.brand(context, brand)?.sets.orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(brand) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tooldetail_remote_sets_body), style = MaterialTheme.typography.bodyMedium)
                sets.forEachIndexed { index, set ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.tooldetail_remote_set_title, index + 1),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            pluralStringResourceOrPlain(set.name, set.models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = set.keys.containsKey(RemoteKey.POWER.name),
                                onClick = { scope.launch { set.keys[RemoteKey.POWER.name]?.let { RemotePairing.tryCode(context, it) } } },
                            ) { Text(stringResource(R.string.tooldetail_remote_try_power)) }
                            TextButton(onClick = { onPick(index) }) { Text(stringResource(R.string.tooldetail_remote_use_set)) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun pluralStringResourceOrPlain(name: String, models: Int): String =
    if (models > 1) stringResource(R.string.tooldetail_remote_set_models, name, models) else name

/** The local network searched for a few seconds; a tap adds what was found and goes on to pairing. */
@Composable
private fun ScanDialog(onPick: (FoundDevice) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<FoundDevice>() }
    var searching by remember { mutableStateOf(true) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        searching = true
        runCatching { RemoteDiscovery.scan(context) { device -> found.add(device) } }
        searching = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tooldetail_remote_find_title)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (searching) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.tooldetail_remote_searching), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (found.isEmpty()) {
                    Text(stringResource(R.string.tooldetail_remote_found_none), style = MaterialTheme.typography.bodyMedium)
                }
                found.toList().forEach { device ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(device) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOf(stringResource(remoteKindLabel(device.kind)), device.model, device.host).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !searching, onClick = { found.clear(); round++ }) { Text(stringResource(R.string.tooldetail_remote_search_again)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** A network remote by its address, for a TV the search did not find. */
@Composable
private fun AddressDialog(onAdd: (RemoteKind, String) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(RemoteKind.ANDROID_TV) }
    var host by remember { mutableStateOf("") }
    val valid = host.trim().let { it.isNotEmpty() && it.none { c -> c.isWhitespace() || c == '/' } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tooldetail_remote_address_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tooldetail_remote_address_body), style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RemoteKind.entries.filter { it.network }.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(stringResource(remoteKindLabel(k))) })
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tooldetail_remote_address_field)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(kind, host.trim()) }) { Text(stringResource(R.string.tooldetail_remote_add_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private enum class PairStep { WORKING, CODE, DONE, FAILED }

/**
 * Pairing one network remote. Android TV shows a code to type here; Samsung
 * and LG show an Allow prompt on the TV, answered with the TV's own remote;
 * a Roku only has to answer.
 */
@Composable
private fun PairDialog(id: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device = remember(id) { RemoteStore.get(id) }
    val kind = device?.remoteKind
    if (device == null || kind == null || kind == RemoteKind.IR) {
        LaunchedEffect(id) { onDone() }
        return
    }
    var step by remember { mutableStateOf(PairStep.WORKING) }
    var failure by remember { mutableStateOf<RemoteException.Reason?>(null) }
    var code by remember { mutableStateOf("") }
    val pairing = remember(id) { if (kind == RemoteKind.ANDROID_TV) AndroidTvPairing(context, device.host) else null }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        step = PairStep.WORKING
        failure = null
        step = try {
            if (pairing != null) {
                pairing.start()
                PairStep.CODE
            } else {
                RemotePairing.connectOnce(context, id)
                PairStep.DONE
            }
        } catch (e: RemoteException) {
            failure = e.reason
            PairStep.FAILED
        }
    }
    androidx.compose.runtime.DisposableEffect(id) { onDispose { pairing?.close() } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.tooldetail_remote_pair_title, device.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (step) {
                    PairStep.WORKING -> {
                        Text(
                            stringResource(
                                when (kind) {
                                    RemoteKind.SAMSUNG -> R.string.tooldetail_remote_pair_samsung
                                    RemoteKind.LG -> R.string.tooldetail_remote_pair_lg
                                    else -> R.string.tooldetail_remote_pair_connecting
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                    PairStep.CODE -> {
                        Text(stringResource(R.string.tooldetail_remote_pair_code_body), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = code,
                            onValueChange = { v -> code = v.filter { it.isLetterOrDigit() }.take(6).uppercase() },
                            singleLine = true,
                            label = { Text(stringResource(R.string.tooldetail_remote_pair_code_field)) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        failure?.let { Text(stringResource(R.string.tooldetail_remote_pair_wrong_code), color = MaterialTheme.colorScheme.error) }
                    }
                    PairStep.DONE -> Text(stringResource(R.string.tooldetail_remote_pair_done), style = MaterialTheme.typography.bodyMedium)
                    PairStep.FAILED -> Text(stringResource(pairFailure(kind, failure)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when (step) {
                PairStep.CODE -> TextButton(
                    enabled = code.length == 6,
                    onClick = {
                        scope.launch {
                            try {
                                pairing?.finish(code)
                                step = PairStep.WORKING
                                // The first connection to the remote port pins the TV's certificate.
                                runCatching { RemotePairing.connectOnce(context, id) }
                                step = PairStep.DONE
                            } catch (e: RemoteException) {
                                if (e.reason == RemoteException.Reason.REFUSED && e.message == "code") {
                                    failure = e.reason
                                } else {
                                    failure = e.reason
                                    step = PairStep.FAILED
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.tooldetail_remote_pair_button)) }
                PairStep.FAILED -> TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.tooldetail_remote_try_again)) }
                PairStep.DONE -> TextButton(onClick = onDone) { Text(stringResource(android.R.string.ok)) }
                PairStep.WORKING -> Unit
            }
        },
        dismissButton = {
            if (step != PairStep.DONE) TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun pairFailure(kind: RemoteKind, reason: RemoteException.Reason?): Int = when (reason) {
    RemoteException.Reason.BLOCKED -> R.string.tooldetail_remote_pair_roku_blocked
    RemoteException.Reason.REFUSED, RemoteException.Reason.NOT_PAIRED -> R.string.tooldetail_remote_pair_refused
    RemoteException.Reason.TIMED_OUT -> R.string.tooldetail_remote_pair_timed_out
    else -> if (kind == RemoteKind.ANDROID_TV) R.string.tooldetail_remote_pair_unreachable_atv else R.string.tooldetail_remote_pair_unreachable
}

/** One remote's settings: its name, its codes or its pairing, and removing it. */
@Composable
private fun EditDialog(
    id: String,
    onCodeSet: (String) -> Unit,
    onPaste: () -> Unit,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store by RemoteStore.flow.collectAsState()
    val device = store.devices.firstOrNull { it.id == id }
    if (device == null) {
        LaunchedEffect(id) { onDismiss() }
        return
    }
    var name by remember(id) { mutableStateOf(device.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    val kind = device.remoteKind
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(remoteSubtitle(device), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tooldetail_remote_name_field)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind == RemoteKind.IR) {
                    TextButton(onClick = { onCodeSet(device.irBrand) }) {
                        Text(stringResource(if (device.irBrand.isEmpty()) R.string.tooldetail_remote_pick_brand else R.string.tooldetail_remote_change_set))
                    }
                    TextButton(onClick = onPaste) { Text(stringResource(R.string.tooldetail_remote_paste_title)) }
                } else if (kind != null) {
                    TextButton(onClick = onPair) { Text(stringResource(R.string.tooldetail_remote_pair_again)) }
                }
                if (confirmDelete) {
                    Text(stringResource(R.string.tooldetail_remote_delete_confirm), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        RemoteStore.remove(context, id)
                        onDismiss()
                    }) { Text(stringResource(R.string.tooldetail_remote_delete), color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.tooldetail_remote_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty() && trimmed != device.name) RemoteStore.update(context, id) { it.copy(name = trimmed) }
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

/** A code for one pad key, typed or pasted as Pronto hex or raw timings, tried before it is kept. */
@Composable
private fun PasteDialog(id: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(RemoteKey.POWER) }
    var code by remember { mutableStateOf(RemoteStore.get(id)?.irCodes?.get(RemoteKey.POWER.name).orEmpty()) }
    val valid = IrCodes.isValid(code)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.tooldetail_remote_paste_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tooldetail_remote_paste_body), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteKey.entries.forEach { k ->
                        FilterChip(
                            selected = key == k,
                            onClick = {
                                key = k
                                code = RemoteStore.get(id)?.irCodes?.get(k.name).orEmpty()
                            },
                            label = { Text(stringResource(remoteKeyLabel(k))) },
                        )
                    }
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.tooldetail_remote_paste_field)) },
                    isError = code.isNotBlank() && !valid,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(enabled = valid, onClick = { scope.launch { RemotePairing.tryCode(context, code) } }) {
                    Text(stringResource(R.string.tooldetail_remote_paste_try))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid || code.isBlank(),
                onClick = {
                    RemoteStore.update(context, id) { d ->
                        val codes = if (code.isBlank()) d.irCodes - key.name else d.irCodes + (key.name to code.trim())
                        d.copy(irCodes = codes)
                    }
                    onDone()
                },
            ) { Text(stringResource(R.string.tooldetail_remote_paste_save)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) } },
    )
}

internal fun remoteKeyLabel(key: RemoteKey): Int = when (key) {
    RemoteKey.POWER -> ImeR.string.ime_remote_key_power
    RemoteKey.VOL_UP -> ImeR.string.ime_remote_key_vol_up
    RemoteKey.VOL_DOWN -> ImeR.string.ime_remote_key_vol_down
    RemoteKey.MUTE -> ImeR.string.ime_remote_key_mute
    RemoteKey.CH_UP -> ImeR.string.ime_remote_key_ch_up
    RemoteKey.CH_DOWN -> ImeR.string.ime_remote_key_ch_down
    RemoteKey.INPUT -> ImeR.string.ime_remote_key_input
    RemoteKey.UP -> ImeR.string.ime_remote_key_up
    RemoteKey.DOWN -> ImeR.string.ime_remote_key_down
    RemoteKey.LEFT -> ImeR.string.ime_remote_key_left
    RemoteKey.RIGHT -> ImeR.string.ime_remote_key_right
    RemoteKey.OK -> ImeR.string.ime_remote_key_ok
    RemoteKey.BACK -> ImeR.string.ime_remote_key_back
    RemoteKey.HOME -> ImeR.string.ime_remote_key_home
    RemoteKey.MENU -> ImeR.string.ime_remote_key_menu
}

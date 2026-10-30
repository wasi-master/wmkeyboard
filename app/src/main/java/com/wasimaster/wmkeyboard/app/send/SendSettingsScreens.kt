package com.wasimaster.wmkeyboard.app.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.ActionRow
import com.wasimaster.wmkeyboard.app.BannerTone
import com.wasimaster.wmkeyboard.app.ChoiceSetting
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.PermissionDisclosures
import com.wasimaster.wmkeyboard.app.SettingLevel
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.StateBanner
import com.wasimaster.wmkeyboard.app.SwipeAction
import com.wasimaster.wmkeyboard.app.SwipeActionsRow
import com.wasimaster.wmkeyboard.app.TextFieldSetting
import com.wasimaster.wmkeyboard.core.send.PairDropClient
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.app.deleteSwipeAction
import com.wasimaster.wmkeyboard.app.rememberDisclosedPermissionRequest
import com.wasimaster.wmkeyboard.app.toolRoute
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.send.SendClient
import com.wasimaster.wmkeyboard.core.send.SendError
import com.wasimaster.wmkeyboard.core.send.SendException
import com.wasimaster.wmkeyboard.core.send.SendInfo
import com.wasimaster.wmkeyboard.core.send.SendInstance
import com.wasimaster.wmkeyboard.core.send.SendInstances
import com.wasimaster.wmkeyboard.core.send.SendServerConfig
import com.wasimaster.wmkeyboard.core.send.SendStore
import com.wasimaster.wmkeyboard.core.send.SendUpload
import com.wasimaster.wmkeyboard.core.settings.SendAfterUpload
import com.wasimaster.wmkeyboard.core.settings.SendPasswordMode
import com.wasimaster.wmkeyboard.core.settings.SendSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.send.DirectWifi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Send files tool's page (#729): the instance, the defaults a new link
 * starts from, receiving, Nearby, and the share sheet. Under the common
 * header rows the tool page frame draws.
 */
@Composable
internal fun SendToolSettings(repository: SettingsRepository, settings: LiveSettings, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val d = SettingsDefaults.send
    fun update(change: (SendSettings) -> SendSettings) {
        scope.launch { repository.updateSend(change) }
    }

    SettingsGroup(stringResource(R.string.send_server_group)) {
        item {
            NavRow(
                R.string.send_instance_title,
                stringResource(R.string.send_instance_subtitle),
                value = SendInstances.displayHost(settings.watch { it.send.instanceUrl }),
            ) { onNavigate("send/instances") }
        }
    }

    SettingsGroup(stringResource(R.string.send_defaults_group)) {
        item {
            ChoiceSetting(
                R.string.send_expire_title,
                subtitle = stringResource(R.string.send_expire_subtitle),
                options = listOf(
                    300L to stringResource(R.string.send_expire_5min),
                    3_600L to stringResource(R.string.send_expire_1hour),
                    86_400L to stringResource(R.string.send_expire_1day),
                    604_800L to stringResource(R.string.send_expire_7days),
                ),
                selected = settings.watch { it.send.expireSeconds },
                default = d.expireSeconds,
            ) { v -> update { it.copy(expireSeconds = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.send_downloads_title,
                subtitle = stringResource(R.string.send_downloads_subtitle),
                options = SendServerConfig.DEFAULT.downloadCounts.map { it to it.toString() },
                selected = settings.watch { it.send.downloads },
                default = d.downloads,
            ) { v -> update { it.copy(downloads = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.send_password_title,
                subtitle = stringResource(R.string.send_password_subtitle),
                options = listOf(
                    SendPasswordMode.OFF to stringResource(R.string.send_password_off),
                    SendPasswordMode.ASK to stringResource(R.string.send_password_ask),
                    SendPasswordMode.GENERATE to stringResource(R.string.send_password_generate),
                ),
                selected = settings.watch { it.send.password },
                default = d.password,
            ) { v -> update { it.copy(password = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.send_after_title,
                subtitle = stringResource(R.string.send_after_subtitle),
                options = listOf(
                    SendAfterUpload.INSERT to stringResource(R.string.send_after_insert),
                    SendAfterUpload.COPY to stringResource(R.string.send_after_copy),
                    SendAfterUpload.SHOW to stringResource(R.string.send_after_show),
                    SendAfterUpload.ASK to stringResource(R.string.send_after_ask),
                ),
                selected = settings.watch { it.send.afterUpload },
                default = d.afterUpload,
            ) { v -> update { it.copy(afterUpload = v) } }
        }
    }

    SettingsGroup(stringResource(R.string.send_receive_group), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.send_detect_title,
                stringResource(R.string.send_detect_subtitle),
                settings.watch { it.send.detectLinks },
                default = d.detectLinks,
            ) { v -> update { it.copy(detectLinks = v) } }
        }
        item {
            ToggleSetting(
                R.string.send_open_after_title,
                stringResource(R.string.send_open_after_subtitle),
                settings.watch { it.send.openAfterDownload },
                default = d.openAfterDownload,
            ) { v -> update { it.copy(openAfterDownload = v) } }
        }
    }

    val nearby = settings.watch { it.send.nearby }
    val pairDrop = settings.watch { it.send.pairDrop }
    var granted by remember { mutableStateOf(DirectWifi.hasPermission(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = DirectWifi.hasPermission(context) }
    val ask = rememberDisclosedPermissionRequest(PermissionDisclosures.NEARBY_WIFI) { granted = DirectWifi.hasPermission(context) }
    if (nearby && !granted) {
        StateBanner(
            stringResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.send_nearby_permission_missing
                else R.string.send_nearby_permission_missing_location,
            ),
            action = stringResource(R.string.send_nearby_permission_allow),
            tone = BannerTone.INFO,
        ) { ask() }
    }
    SettingsGroup(stringResource(R.string.send_nearby_group), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.send_nearby_title,
                stringResource(R.string.send_nearby_subtitle),
                nearby,
                default = d.nearby,
            ) { v -> update { it.copy(nearby = v) } }
        }
        item(visible = nearby, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.send_keep_direct_title,
                stringResource(R.string.send_keep_direct_subtitle),
                settings.watch { it.send.keepDirectLink },
                default = d.keepDirectLink,
            ) { v -> update { it.copy(keepDirectLink = v) } }
        }
        item(visible = nearby) {
            ToggleSetting(
                R.string.send_localsend_title,
                stringResource(R.string.send_localsend_subtitle),
                settings.watch { it.send.localSend },
                default = d.localSend,
            ) { v -> update { it.copy(localSend = v) } }
        }
        item(visible = nearby, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.send_pairdrop_title,
                stringResource(R.string.send_pairdrop_subtitle),
                pairDrop,
                info = stringResource(R.string.send_pairdrop_info),
                default = d.pairDrop,
            ) { v -> update { it.copy(pairDrop = v) } }
        }
        item(visible = nearby && pairDrop, level = SettingLevel.ADVANCED) {
            TextFieldSetting(
                stringResource(R.string.send_pairdrop_server_title),
                settings.watch { it.send.pairDropServer },
                hint = PairDropClient.DEFAULT_SERVER,
                default = d.pairDropServer,
            ) { v -> update { it.copy(pairDropServer = v.trim()) } }
        }
        item(visible = nearby) {
            NavRow(
                R.string.send_nearby_name_title,
                stringResource(R.string.send_nearby_name_subtitle),
                value = settings.watch { it.kdeConnect.deviceName }.ifBlank { Build.MODEL },
            ) { onNavigate(toolRoute(ToolbarTool.KDE_CONNECT)) }
        }
    }

    SettingsGroup(stringResource(R.string.send_sharing_group), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.send_share_sheet_title,
                stringResource(R.string.send_share_sheet_subtitle),
                settings.watch { it.send.shareSheet },
                default = d.shareSheet,
            ) { v -> update { it.copy(shareSheet = v) } }
        }
        item {
            ToggleSetting(
                R.string.send_history_title,
                stringResource(R.string.send_history_subtitle),
                settings.watch { it.send.keepHistory },
                default = d.keepHistory,
            ) { v -> update { it.copy(keepHistory = v) } }
        }
        item {
            NavRow(R.string.send_history_open_title, stringResource(R.string.send_history_open_subtitle)) { onNavigate("send/history") }
        }
    }
}

/** One client for the settings screens' probes and owner calls, logged as the tool's traffic. */
private fun sendClient(): SendClient = SendClient(
    OkHttpClient.Builder()
        .addInterceptor(InternetGate)
        .addNetworkInterceptor(NetLogInterceptor(NetSource.SEND))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
)

/** `send/instances`: the user's own instances, then the public list, each with a probe. */
@Composable
internal fun SendInstancesScreen(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val client = remember { sendClient() }
    val current = settings.watch { it.send.instanceUrl }
    val own = settings.watch { it.send.ownInstances }
    val status = remember { mutableStateMapOf<String, SendServerConfig?>() }
    val checking = remember { mutableStateMapOf<String, Boolean>() }
    fun update(change: (SendSettings) -> SendSettings) {
        scope.launch { repository.updateSend(change) }
    }
    fun probe(url: String) {
        if (checking[url] == true) return
        checking[url] = true
        scope.launch {
            val config = withContext(Dispatchers.IO) { runCatching { client.config(url) }.getOrNull() }
            status[url] = config
            checking[url] = false
        }
    }
    LaunchedEffect(current) { if (current !in status) probe(current) }

    SettingsGroup(stringResource(R.string.send_instances_own_group)) {
        item {
            var draft by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(200) },
                    label = { Text(stringResource(R.string.send_instances_add_label)) },
                    placeholder = { Text("send.example.org") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = SendInstances.normaliseBase(draft) != null && own.size < SendSettings.MAX_OWN_INSTANCES,
                    onClick = {
                        val base = SendInstances.normaliseBase(draft) ?: return@Button
                        draft = ""
                        update { it.copy(ownInstances = (it.ownInstances.filterNot { u -> u == base } + base).takeLast(SendSettings.MAX_OWN_INSTANCES), instanceUrl = base) }
                        probe(base)
                    },
                ) { Text(stringResource(R.string.send_instances_add)) }
            }
        }
        for (url in own) {
            item {
                InstanceRow(
                    instance = SendInstance(url),
                    selected = url == current,
                    config = status[url],
                    checking = checking[url] == true,
                    probed = url in status,
                    onSelect = { update { it.copy(instanceUrl = url) } },
                    onProbe = { probe(url) },
                ) {
                    TextButton(onClick = {
                        update { s ->
                            val rest = s.ownInstances.filterNot { it == url }
                            s.copy(ownInstances = rest, instanceUrl = if (s.instanceUrl == url) SendInstances.DEFAULT_URL else s.instanceUrl)
                        }
                    }) { Text(stringResource(R.string.send_instances_remove)) }
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.send_instances_public_group), info = stringResource(R.string.send_instances_public_info)) {
        item {
            ActionRow(R.string.send_instance_check_all, subtitle = null, action = stringResource(R.string.send_instance_check)) {
                SendInstances.catalogue.forEach { probe(it.url) }
            }
        }
        for (instance in SendInstances.catalogue) {
            item {
                InstanceRow(
                    instance = instance,
                    selected = instance.url == current,
                    config = status[instance.url],
                    checking = checking[instance.url] == true,
                    probed = instance.url in status,
                    onSelect = { update { it.copy(instanceUrl = instance.url) } },
                    onProbe = { probe(instance.url) },
                )
            }
        }
    }
}

@Composable
private fun InstanceRow(
    instance: SendInstance,
    selected: Boolean,
    config: SendServerConfig?,
    checking: Boolean,
    probed: Boolean,
    onSelect: () -> Unit,
    onProbe: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val maxBytes = config?.maxFileSize ?: instance.maxBytes
    val maxDays = config?.let { (it.maxExpireSeconds / 86_400).toInt() } ?: instance.maxDays
    val maxDownloads = config?.maxDownloads ?: instance.maxDownloads
    val meta = buildList {
        if (maxBytes > 0) add(Formatter.formatShortFileSize(context, maxBytes))
        if (maxDays > 0) add(pluralStringResource(R.plurals.send_days, maxDays, maxDays))
        if (maxDownloads > 0) add(pluralStringResource(R.plurals.send_downloads_n, maxDownloads, maxDownloads))
        if (instance.country.isNotEmpty()) add(instance.country)
        if (instance.reference) add(stringResource(R.string.send_instance_reference))
    }.joinToString(" · ")
    val state = when {
        checking -> stringResource(R.string.send_instance_checking)
        probed && config == null -> stringResource(R.string.send_instance_unreachable)
        probed -> stringResource(R.string.send_instance_ok)
        else -> ""
    }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(instance.host, style = MaterialTheme.typography.bodyLarge)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isNotEmpty()) {
                Text(
                    state,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probed && config == null && !checking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!probed && !checking) TextButton(onClick = onProbe) { Text(stringResource(R.string.send_instance_check)) }
        trailing()
    }
}

/** `send/history`: the links this phone made, with how long they have left, and the files it received. */
@Composable
internal fun SendHistoryScreen(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SendStore(File(context.filesDir, SendStore.DIR)) }
    val client = remember { sendClient() }
    var uploads by remember { mutableStateOf<List<SendUpload>>(emptyList()) }
    var received by remember { mutableStateOf(store.received()) }
    val info = remember { mutableStateMapOf<String, SendInfo>() }
    var notice by remember { mutableStateOf<String?>(null) }
    fun reload() {
        scope.launch {
            val (u, r) = withContext(Dispatchers.IO) { store.uploads() to store.received() }
            uploads = u
            received = r
        }
    }
    LaunchedEffect(Unit) {
        reload()
    }
    LaunchedEffect(uploads.size) {
        uploads.take(10).forEach { upload ->
            if (upload.id in info) return@forEach
            launch {
                withContext(Dispatchers.IO) { runCatching { client.info(upload.instance, upload.id, upload.ownerToken) } }
                    .onSuccess { info[upload.id] = it }
                    .onFailure { e ->
                        if ((e as? SendException)?.error == SendError.NOT_FOUND) {
                            withContext(Dispatchers.IO) { store.remove(upload) }
                            reload()
                        }
                    }
            }
        }
    }
    notice?.let { text ->
        StateBanner(text, tone = BannerTone.INFO)
    }
    if (settings.watch { !it.send.keepHistory }) {
        StateBanner(stringResource(R.string.send_history_subtitle), action = stringResource(R.string.send_history_title), tone = BannerTone.INFO) {
            scope.launch { repository.updateSend { it.copy(keepHistory = true) } }
        }
    }

    SettingsGroup(stringResource(R.string.send_history_sent)) {
        if (uploads.isEmpty()) {
            item { Text(stringResource(R.string.send_history_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
        }
        for (upload in uploads) {
            item(key = upload.id) {
                val left = ((upload.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                val used = info[upload.id]
                val copyLink = {
                    copyText(context, upload.url)
                    notice = context.getString(R.string.send_history_copied)
                }
                val deleteUpload = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { client.delete(upload.instance, upload.id, upload.ownerToken) }.map { true }
                                .getOrElse { (it as? SendException)?.error == SendError.NOT_FOUND }
                        }
                        if (ok) {
                            withContext(Dispatchers.IO) { store.remove(upload) }
                            notice = context.getString(R.string.send_history_deleted)
                            reload()
                        }
                    }
                    Unit
                }
                SwipeActionsRow(
                    listOf(
                        SwipeAction(Icons.Outlined.ContentCopy, stringResource(R.string.send_history_copy), onClick = copyLink),
                        deleteSwipeAction(stringResource(R.string.send_history_delete), deleteUpload),
                    ),
                ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(upload.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOf(
                            Formatter.formatShortFileSize(context, upload.size),
                            SendInstances.displayHost(upload.instance),
                            stringResource(R.string.send_history_left, shortDuration(context, left)),
                            if (used != null) pluralStringResource(R.plurals.send_downloads_used, used.downloadLimit, used.downloads, used.downloadLimit) else "",
                            if (upload.password) stringResource(R.string.send_history_password) else "",
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = copyLink) { Text(stringResource(R.string.send_history_copy)) }
                        TextButton(onClick = deleteUpload) { Text(stringResource(R.string.send_history_delete)) }
                    }
                }
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.send_history_received)) {
        if (received.isEmpty()) {
            item { Text(stringResource(R.string.send_history_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
        } else {
            item {
                ActionRow(
                    R.string.send_history_clear_received,
                    subtitle = null,
                    action = stringResource(R.string.send_history_forget),
                    confirm = stringResource(R.string.send_history_clear_confirm),
                ) {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.clearReceived() }
                        reload()
                    }
                }
            }
        }
        for (item in received) {
            item(key = item.location) {
                val open = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.parse(item.location), item.type.ifBlank { "*/*" })
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    Unit
                }
                val forget = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.removeReceived(item.location) }
                        reload()
                    }
                    Unit
                }
                SwipeActionsRow(
                    listOf(
                        SwipeAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.send_history_open), onClick = open),
                        deleteSwipeAction(stringResource(R.string.send_history_forget), forget),
                    ),
                ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOf(if (item.size > 0) Formatter.formatShortFileSize(context, item.size) else "", item.from).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = open) { Text(stringResource(R.string.send_history_open)) }
                        TextButton(onClick = forget) { Text(stringResource(R.string.send_history_forget)) }
                    }
                }
                }
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Send link", text)) }
}

/** "2 days", "3 hr", "5 min": the largest unit that is not zero. */
internal fun shortDuration(context: Context, seconds: Long): String {
    val format = android.icu.text.MeasureFormat.getInstance(
        context.resources.configuration.locales[0],
        android.icu.text.MeasureFormat.FormatWidth.SHORT,
    )
    val (value, unit) = when {
        seconds >= 2 * 86_400 -> seconds / 86_400 to android.icu.util.MeasureUnit.DAY
        seconds >= 3_600 -> seconds / 3_600 to android.icu.util.MeasureUnit.HOUR
        seconds >= 60 -> seconds / 60 to android.icu.util.MeasureUnit.MINUTE
        else -> seconds.coerceAtLeast(0) to android.icu.util.MeasureUnit.SECOND
    }
    return format.format(android.icu.util.Measure(value, unit))
}

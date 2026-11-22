package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.McpAuth
import com.wasimaster.wmkeyboard.core.tools.McpAuthState
import com.wasimaster.wmkeyboard.core.tools.McpCatalogEntry
import com.wasimaster.wmkeyboard.core.tools.McpCatalogStore
import com.wasimaster.wmkeyboard.core.tools.McpClient
import com.wasimaster.wmkeyboard.core.tools.McpConfigImport
import com.wasimaster.wmkeyboard.core.tools.McpHeader
import com.wasimaster.wmkeyboard.core.tools.McpServerSpec
import com.wasimaster.wmkeyboard.core.tools.McpTool
import com.wasimaster.wmkeyboard.core.tools.McpToolPolicy
import com.wasimaster.wmkeyboard.core.tools.McpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- MCP servers (#573) ----

/**
 * One MCP server's editor, as flights name it. The pattern
 * (`ai_mcp_edit/{serverId}`) is the same string for every server and would
 * hang one key on all of them; see [aiActionEditRoute].
 */
internal fun aiMcpEditRoute(serverId: String): String = "ai_mcp_edit/$serverId"

/**
 * Past this many tools on offer, the list warns. Every tool's name, description
 * and input schema go into every request, so thirty tools can cost more than
 * the text the user asked about.
 */
private const val MANY_TOOLS = 20

/**
 * The MCP servers the user added: one row each, with its switch and what its
 * last Connect found, plus a way in for a config copied from another app.
 *
 * The tool lists come from [McpCatalogStore], not from settings: a server's
 * schemas can run to tens of kilobytes, which the preference store would hand
 * to the keyboard on every change. [McpCatalogStore.version] ticks on each
 * write, so a Connect that finishes while this screen is open redraws it.
 */
@Composable
internal fun AiMcpSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = settings.watch { it.ai.mcpServers }
    val catalogVersion by McpCatalogStore.version.collectAsState()
    val catalog = remember(catalogVersion) { McpCatalogStore.all(context) }
    // Servers an import is connecting right now. Their rows say so instead of
    // "Not connected", which would read as a failure for the second it lasts.
    val connecting = remember { mutableStateListOf<String>() }
    var importing by rememberSaveable { mutableStateOf(false) }

    // The same test the runner applies (McpCatalogStore.entryFor): a list
    // fetched from another address does not describe this server any more.
    fun entryOf(server: McpServerSpec): McpCatalogEntry? =
        catalog[server.id]?.takeIf { it.url == server.url.trim() }

    val liveTools = servers.filter { it.enabled }.sumOf { server ->
        entryOf(server)?.tools?.count { server.policyFor(it) != McpToolPolicy.OFF } ?: 0
    }
    if (liveTools > MANY_TOOLS) {
        StateBanner(
            pluralStringResource(R.plurals.toolai_ai_mcp_many_tools_body, liveTools, liveTools),
            tone = BannerTone.WARNING,
        )
    }
    SettingsGroup(
        stringResource(R.string.toolai_ai_mcp_servers_group_title),
        info = stringResource(R.string.toolai_ai_mcp_info),
    ) {
        // The group draws nothing at all with no rows, and its "?" with it, so
        // an empty list still holds one line saying how to fill it.
        item(visible = servers.isEmpty(), waitsOnSetting = false) {
            Text(
                stringResource(R.string.toolai_ai_mcp_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        for (server in servers) {
            item(key = server.id) {
                val entry = entryOf(server)
                val status = when {
                    server.id in connecting -> stringResource(R.string.toolai_ai_mcp_connecting_progress)
                    entry == null -> stringResource(R.string.toolai_ai_mcp_not_connected)
                    else -> mcpToolCounts(server, entry.tools)
                }
                val route = aiMcpEditRoute(server.id)
                WmRow(
                    title = server.label,
                    supporting = {
                        Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    swipeActions = listOf(
                        SwipeAction(Icons.Outlined.Edit, stringResource(CommonR.string.common_edit)) { onNavigate(route) },
                        deleteSwipeAction(stringResource(CommonR.string.common_delete)) {
                            scope.launch {
                                repository.deleteMcpServer(server.id)
                                withContext(Dispatchers.IO) { McpCatalogStore.remove(context, server.id) }
                                McpClient.forget(server.id)
                            }
                        },
                    ),
                    trailing = {
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { on ->
                                scope.launch { repository.setMcpServerEnabled(server.id, on) }
                            },
                        )
                    },
                    flightTo = route,
                    onClick = { onNavigate(route) },
                )
            }
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_mcp_import_group_title)) {
        item {
            NavRow(
                title = R.string.toolai_ai_mcp_import_title,
                subtitle = stringResource(R.string.toolai_ai_mcp_import_subtitle),
                onClick = { importing = true },
            )
        }
    }
    if (importing) {
        McpImportDialog(onDismiss = { importing = false }) { found ->
            importing = false
            // One id each, from the same clock reading: two servers added in
            // the same millisecond would otherwise share one.
            val base = System.currentTimeMillis()
            val added = found.mapIndexed { index, server ->
                McpServerSpec(
                    id = McpServerSpec.ID_PREFIX + (base + index),
                    name = server.name,
                    url = server.url,
                    transport = server.transport,
                    enabled = true,
                    bridgeCommand = server.bridgeCommand,
                ) to server.headers
            }
            connecting.addAll(added.map { it.first.id })
            scope.launch {
                for ((spec, headers) in added) repository.upsertMcpServer(spec, headers)
                // Connected one by one in parallel, so a dead server does not
                // hold up the rest. A failure needs no message here: the row
                // says "Not connected", and its editor's Connect says why.
                for ((spec, headers) in added) {
                    launch {
                        withContext(Dispatchers.IO) {
                            runCatching { McpClient.connect(spec, headers) }
                                .onSuccess { McpCatalogStore.put(context, spec.id, it) }
                        }
                        connecting.remove(spec.id)
                    }
                }
            }
        }
    }
    RegisterAddFab(stringResource(R.string.toolai_ai_mcp_add_desc)) {
        onNavigate(aiMcpEditRoute(McpServerSpec.ID_PREFIX + System.currentTimeMillis()))
    }
}

/** "12 tools: 3 run, 7 ask, 2 off", under a server that has been connected. */
@Composable
private fun mcpToolCounts(server: McpServerSpec, tools: List<McpTool>): String {
    val policies = tools.map { server.policyFor(it) }
    return stringResource(
        R.string.toolai_ai_mcp_status_counts,
        pluralStringResource(R.plurals.toolai_ai_mcp_tool_count, tools.size, tools.size),
        policies.count { it == McpToolPolicy.ALLOW },
        policies.count { it == McpToolPolicy.ASK },
        policies.count { it == McpToolPolicy.OFF },
    )
}

/**
 * Paste a config copied from another app's MCP settings, see what it holds,
 * then add it. Two steps rather than one: a config usually mixes servers a
 * phone can reach with ones it cannot, and the user should learn which before
 * anything is saved, not by finding half of them missing.
 */
@Composable
private fun McpImportDialog(
    onDismiss: () -> Unit,
    onAdd: (List<McpConfigImport.Server>) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<McpConfigImport.Result?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val found = result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolai_ai_mcp_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (found == null) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            invalid = false
                        },
                        label = { Text(stringResource(R.string.toolai_ai_mcp_import_label)) },
                        isError = invalid,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (invalid) {
                                        R.string.toolai_ai_mcp_import_error
                                    } else {
                                        R.string.toolai_ai_mcp_import_hint
                                    },
                                ),
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    McpImportSummary(found)
                }
            }
        },
        confirmButton = {
            when {
                found == null -> TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        val parsed = McpConfigImport.parse(text)
                        if (parsed == null) invalid = true else result = parsed
                    },
                ) { Text(stringResource(R.string.toolai_ai_mcp_import_read_action)) }
                found.servers.isNotEmpty() -> TextButton(onClick = { onAdd(found.servers) }) {
                    Text(
                        pluralStringResource(
                            R.plurals.toolai_ai_mcp_import_add_action,
                            found.servers.size,
                            found.servers.size,
                        ),
                    )
                }
                else -> TextButton(onClick = onDismiss) {
                    Text(stringResource(CommonR.string.common_close))
                }
            }
        },
        dismissButton = if (found != null && found.servers.isEmpty()) {
            null
        } else {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        },
    )
}

/** What a read config holds: the servers it adds, and why the others are left out. */
@Composable
private fun McpImportSummary(found: McpConfigImport.Result) {
    if (found.isEmpty) {
        Text(stringResource(R.string.toolai_ai_mcp_import_none_empty))
        return
    }
    if (found.servers.isNotEmpty()) {
        Text(
            stringResource(R.string.toolai_ai_mcp_import_found_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        for (server in found.servers) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (server.name.isNotBlank()) {
                    Text(server.name, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    server.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server.bridgeCommand.isNotEmpty()) {
                    Text(
                        stringResource(R.string.toolai_ai_mcp_import_bridged),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (found.skipped.isNotEmpty()) {
        Text(
            stringResource(R.string.toolai_ai_mcp_import_skipped_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = if (found.servers.isEmpty()) 0.dp else 12.dp),
        )
        for (skipped in found.skipped) {
            Text(
                stringResource(
                    R.string.toolai_ai_mcp_import_skipped_line,
                    skipped.name,
                    stringResource(skipped.reason.labelRes()),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@StringRes
private fun McpConfigImport.SkipReason.labelRes(): Int = when (this) {
    McpConfigImport.SkipReason.COMMAND -> R.string.toolai_ai_mcp_skip_command
    McpConfigImport.SkipReason.NO_URL -> R.string.toolai_ai_mcp_skip_no_url
    McpConfigImport.SkipReason.BAD_URL -> R.string.toolai_ai_mcp_skip_bad_url
}

@StringRes
private fun McpTransport.labelRes(): Int = when (this) {
    McpTransport.AUTO -> R.string.toolai_ai_mcp_transport_auto_label
    McpTransport.STREAMABLE_HTTP -> R.string.toolai_ai_mcp_transport_http_label
    McpTransport.SSE -> R.string.toolai_ai_mcp_transport_sse_label
}

@StringRes
private fun McpToolPolicy.labelRes(): Int = when (this) {
    McpToolPolicy.ALLOW -> R.string.toolai_ai_mcp_policy_allow_label
    McpToolPolicy.ASK -> R.string.toolai_ai_mcp_policy_ask_label
    McpToolPolicy.OFF -> R.string.toolai_ai_mcp_policy_off_label
}

/** What the last Connect on the editor came back with. */
private sealed interface McpConnectOutcome {
    /** [server] is the name and version the server gave, or the row's own label. */
    data class Connected(val server: String, val tools: Int) : McpConnectOutcome

    data class Failed(val message: String) : McpConnectOutcome
}

/**
 * Add a server, or edit one: its address and headers, a Connect that lists
 * its tools, and what the model may do with each tool.
 *
 * The fields are a draft, written only by Save or Connect, so a half-typed
 * address never reaches the keyboard. The per-tool choices are the exception:
 * each one is saved as it is pressed, because it changes nothing about how
 * the server is reached.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiMcpEditor(
    repository: SettingsRepository,
    settings: LiveSettings,
    serverId: String,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Null for a server that is not saved yet: the add button makes up a new
    // id and comes straight here.
    val existing = settings.watch { s -> s.ai.mcpServers.firstOrNull { it.id == serverId } }

    var name by remember(serverId) { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember(serverId) { mutableStateOf(existing?.url.orEmpty()) }
    var transport by remember(serverId) { mutableStateOf(existing?.transport ?: McpTransport.AUTO) }
    val headers = remember(serverId) {
        mutableStateListOf<McpHeader>().apply {
            addAll(settings.value.ai.mcpHeaders[serverId].orEmpty())
        }
    }
    var connecting by remember(serverId) { mutableStateOf(false) }
    var outcome by remember(serverId) { mutableStateOf<McpConnectOutcome?>(null) }

    val catalogVersion by McpCatalogStore.version.collectAsState()
    // The raw entry, not entryFor: an entry from another address is still the
    // list the user set policies on, and the screen says it is out of date
    // rather than hiding it.
    val entry = remember(catalogVersion, serverId) { McpCatalogStore.all(context)[serverId] }
    val validUrl = McpConfigImport.isHttp(url)
    val stale = entry != null && entry.url != url.trim()

    // Read at the moment of a save rather than captured: the list screen's
    // switch and the per-tool choices below both write the same server.
    fun draft(): McpServerSpec {
        val saved = settings.value.ai.mcpServers.firstOrNull { it.id == serverId }
        return McpServerSpec(
            id = serverId,
            name = name.trim(),
            url = url.trim(),
            transport = transport,
            enabled = saved?.enabled ?: true,
            toolPolicies = saved?.toolPolicies.orEmpty(),
            bridgeCommand = saved?.bridgeCommand.orEmpty(),
        )
    }
    // A row with no name is a row the user added and never filled in.
    fun sentHeaders(): List<McpHeader> =
        headers.filter { it.name.isNotBlank() }.map { McpHeader(it.name.trim(), it.value.trim()) }

    val signedIn = settings.watch { it.ai.mcpAuth[serverId] }
    val exchanging by McpAuth.exchanging.collectAsState()
    var signingIn by remember(serverId) { mutableStateOf(false) }

    // Saves the draft, then asks the server for its tools, signed in when
    // there is a sign-in: what Connect runs, and what a finished sign-in runs.
    fun connectNow() {
        val spec = draft()
        val sent = sentHeaders()
        connecting = true
        outcome = null
        scope.launch {
            // Saved first: the list Connect fetches is filed under
            // this server's id and address, and both have to be the
            // ones the keyboard will use.
            repository.upsertMcpServer(spec, sent)
            val auth: McpAuthState? = settings.value.ai.mcpAuth[spec.id]
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    McpClient.connect(spec, sent, auth) { fresh ->
                        scope.launch { repository.setMcpAuth(spec.id, fresh) }
                    }
                }.onSuccess { McpCatalogStore.put(context, spec.id, it) }
            }
            McpClient.forget(spec.id)
            outcome = result.fold(
                onSuccess = { found ->
                    McpConnectOutcome.Connected(
                        server = listOf(found.serverName, found.serverVersion)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifEmpty { spec.label },
                        tools = found.tools.size,
                    )
                },
                onFailure = { McpConnectOutcome.Failed(McpClient.message(context, it)) },
            )
            connecting = false
        }
    }

    // The browser hands back to the app; the exchange finishes in McpAuth,
    // and a sign-in for this server connects at once.
    val authResult by McpAuth.result.collectAsState()
    LaunchedEffect(authResult) {
        val done = authResult ?: return@LaunchedEffect
        if (done.serverId != serverId) return@LaunchedEffect
        McpAuth.consume()
        when (done.outcome) {
            McpAuth.Outcome.SIGNED_IN -> connectNow()
            McpAuth.Outcome.FAILED -> outcome = McpConnectOutcome.Failed(
                if (done.message.isBlank()) {
                    context.getString(R.string.toolai_ai_mcp_sign_in_failed)
                } else {
                    context.getString(R.string.toolai_ai_mcp_sign_in_failed_reason, done.message)
                },
            )
            McpAuth.Outcome.CANCELLED -> Unit
        }
    }

    SettingsGroup {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.toolai_ai_mcp_name_label)) },
                supportingText = { Text(stringResource(R.string.toolai_ai_mcp_name_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            val badAddress = url.isNotBlank() && !validUrl
            val plainRemote = validUrl && isPlainRemoteHttp(url)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.toolai_ai_mcp_url_label)) },
                isError = badAddress,
                supportingText = {
                    when {
                        badAddress -> Text(stringResource(R.string.toolai_ai_mcp_url_error))
                        plainRemote -> Text(
                            stringResource(R.string.toolai_ai_mcp_url_plain_warning),
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text(stringResource(R.string.toolai_ai_mcp_url_hint))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            ControlSetting(
                R.string.toolai_ai_mcp_transport_title,
                info = stringResource(R.string.toolai_ai_mcp_transport_info),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    for (choice in McpTransport.entries) {
                        FilterChip(
                            selected = transport == choice,
                            onClick = { transport = choice },
                            label = { Text(stringResource(choice.labelRes()), maxLines = 1) },
                        )
                    }
                }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.toolai_ai_mcp_headers_title),
        info = stringResource(R.string.toolai_ai_mcp_headers_info),
    ) {
        headers.forEachIndexed { index, header ->
            item {
                McpHeaderRow(
                    header,
                    onChange = { if (index < headers.size) headers[index] = it },
                    onDelete = { if (index < headers.size) headers.removeAt(index) },
                )
            }
        }
        item {
            TextButton(
                onClick = { headers.add(McpHeader()) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.toolai_ai_mcp_header_add_action))
            }
        }
    }
    val bridge = existing?.bridgeCommand.orEmpty()
    if (bridge.isNotEmpty()) McpBridgeGroup(bridge)
    SettingsGroup(
        stringResource(R.string.toolai_ai_mcp_sign_in_group_title),
        info = stringResource(R.string.toolai_ai_mcp_sign_in_info),
    ) {
        item {
            val busy = signingIn || exchanging == serverId
            WmRow(
                title = stringResource(
                    if (signedIn != null) R.string.toolai_ai_mcp_signed_in_title else R.string.toolai_ai_mcp_sign_in_title,
                ),
                supporting = {
                    Text(
                        stringResource(
                            when {
                                exchanging == serverId -> R.string.toolai_ai_mcp_sign_in_working
                                signedIn != null -> R.string.toolai_ai_mcp_signed_in_subtitle
                                else -> R.string.toolai_ai_mcp_sign_in_subtitle
                            },
                        ),
                    )
                },
                trailing = {
                    if (signedIn != null) {
                        TextButton(onClick = {
                            scope.launch {
                                repository.setMcpAuth(serverId, null)
                                McpClient.forget(serverId)
                            }
                        }) { Text(stringResource(R.string.toolai_ai_mcp_sign_out_action)) }
                    } else {
                        OutlinedButton(
                            enabled = validUrl && !busy,
                            onClick = {
                                val spec = draft()
                                val sent = sentHeaders()
                                signingIn = true
                                outcome = null
                                scope.launch {
                                    repository.upsertMcpServer(spec, sent)
                                    val page = withContext(Dispatchers.IO) {
                                        runCatching { McpAuth.prepare(context, spec, sent) }
                                    }
                                    signingIn = false
                                    page.onSuccess { address ->
                                        val opened = runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(address),
                                                ),
                                            )
                                        }
                                        if (opened.isFailure) {
                                            outcome = McpConnectOutcome.Failed(context.getString(R.string.toolai_ai_mcp_sign_in_no_browser))
                                        }
                                    }.onFailure { failure ->
                                        outcome = McpConnectOutcome.Failed(McpClient.message(context, failure))
                                    }
                                }
                            },
                        ) {
                            if (busy) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.toolai_ai_mcp_sign_in_action))
                        }
                    }
                },
            )
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        if (existing != null) {
            TextButton(onClick = {
                scope.launch {
                    repository.deleteMcpServer(serverId)
                    withContext(Dispatchers.IO) { McpCatalogStore.remove(context, serverId) }
                    McpClient.forget(serverId)
                    onDone()
                }
            }) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(CommonR.string.common_delete))
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            enabled = validUrl && !connecting,
            onClick = { connectNow() },
        ) {
            if (connecting) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.toolai_ai_mcp_connect_action))
        }
        Spacer(Modifier.width(8.dp))
        Button(
            enabled = validUrl,
            onClick = {
                val spec = draft()
                val sent = sentHeaders()
                scope.launch {
                    repository.upsertMcpServer(spec, sent)
                    // A changed address or header makes a remembered session
                    // meaningless.
                    McpClient.forget(spec.id)
                    onDone()
                }
            },
        ) { Text(stringResource(CommonR.string.common_save)) }
    }
    when (val shown = outcome) {
        is McpConnectOutcome.Connected -> StateBanner(
            pluralStringResource(R.plurals.toolai_ai_mcp_connected_body, shown.tools, shown.server, shown.tools),
        )
        is McpConnectOutcome.Failed -> StateBanner(shown.message, tone = BannerTone.WARNING)
        null -> Unit
    }
    if (entry != null) {
        if (stale) StateBanner(stringResource(R.string.toolai_ai_mcp_stale_body))
        SettingsGroup(
            stringResource(R.string.toolai_ai_mcp_tools_title),
            info = stringResource(R.string.toolai_ai_mcp_tools_info),
        ) {
            item(visible = entry.tools.isEmpty(), waitsOnSetting = false) {
                Text(
                    stringResource(R.string.toolai_ai_mcp_tools_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (tool in entry.tools) {
                item {
                    val policy = settings.watch { s ->
                        s.ai.mcpServers.firstOrNull { it.id == serverId }?.policyFor(tool)
                    } ?: McpToolPolicy.defaultFor(tool)
                    McpToolRow(tool, policy) { picked ->
                        scope.launch { repository.setMcpToolPolicy(serverId, tool.name, picked) }
                    }
                }
            }
        }
    }
}

/**
 * Extra headers for the chosen AI provider (#648): a session id and a
 * User-Agent for OpenCode Go, a token for a gateway in front of a provider.
 *
 * Each edit is saved as it is made, like the key and model fields above it.
 * The rows are held here and never read back from settings while the screen
 * is open, so an echo of an earlier keystroke cannot rewind the field; they
 * reload only when the provider changes.
 */
@Composable
internal fun AiProviderHeadersGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
    provider: AiProvider,
) {
    val scope = rememberCoroutineScope()
    val headers = remember(provider) {
        mutableStateListOf<McpHeader>().apply {
            addAll(settings.value.ai.providerHeaders[provider.name].orEmpty())
        }
    }
    fun save() {
        val snapshot = headers.toList()
        scope.launch { repository.setAiProviderHeaders(provider, snapshot) }
    }
    SettingsGroup(
        stringResource(R.string.toolai_ai_provider_headers_title),
        info = stringResource(R.string.toolai_ai_provider_headers_info),
    ) {
        headers.forEachIndexed { index, header ->
            item {
                McpHeaderRow(
                    header,
                    onChange = {
                        if (index < headers.size) {
                            headers[index] = it
                            save()
                        }
                    },
                    onDelete = {
                        if (index < headers.size) {
                            headers.removeAt(index)
                            save()
                        }
                    },
                )
            }
        }
        item {
            TextButton(
                onClick = { headers.add(McpHeader()) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.toolai_ai_mcp_header_add_action))
            }
        }
    }
}

/**
 * A server that starts as a program on a computer, imported from a config:
 * the line that runs it behind a bridge the keyboard can reach, to copy into
 * Termux or a computer's terminal.
 */
@Composable
private fun McpBridgeGroup(command: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    SettingsGroup(
        stringResource(R.string.toolai_ai_mcp_bridge_group_title),
        info = stringResource(R.string.toolai_ai_mcp_bridge_info),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.toolai_ai_mcp_bridge_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(
                        command,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(12.dp),
                    )
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(command))
                    copied = true
                }) {
                    Text(
                        stringResource(
                            if (copied) R.string.toolai_ai_mcp_bridge_copied else R.string.toolai_ai_mcp_bridge_copy_action,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * One header: its name, and its value hidden behind an eye. The value is
 * nearly always a token, and a settings screen is a thing people show to
 * others and take screenshots of.
 */
@Composable
private fun McpHeaderRow(
    header: McpHeader,
    onChange: (McpHeader) -> Unit,
    onDelete: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            OutlinedTextField(
                value = header.name,
                onValueChange = { onChange(header.copy(name = it)) },
                label = { Text(stringResource(R.string.toolai_ai_mcp_header_name_label)) },
                placeholder = { Text(stringResource(R.string.toolai_ai_mcp_header_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = header.value,
                onValueChange = { onChange(header.copy(value = it)) },
                label = { Text(stringResource(R.string.toolai_ai_mcp_header_value_label)) },
                placeholder = { Text(stringResource(R.string.toolai_ai_mcp_header_value_hint)) },
                singleLine = true,
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { shown = !shown }) {
                        Icon(
                            if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (shown) {
                                    R.string.toolai_ai_mcp_header_hide_desc
                                } else {
                                    R.string.toolai_ai_mcp_header_show_desc
                                },
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.toolai_ai_mcp_header_delete_desc),
            )
        }
    }
}

/**
 * One tool and its Run / Ask / Off choice. The server's own read-only and
 * destructive marks show as badges, since they are what the default was
 * decided from and what a user changing it most wants to know.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpToolRow(tool: McpTool, policy: McpToolPolicy, onPick: (McpToolPolicy) -> Unit) {
    WmRow(
        title = tool.label,
        supporting = {
            Column {
                if (tool.description.isNotBlank()) {
                    Text(tool.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (tool.readOnly || tool.destructive) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        if (tool.readOnly) McpBadge(stringResource(R.string.toolai_ai_mcp_read_only_label))
                        if (tool.destructive) {
                            McpBadge(stringResource(R.string.toolai_ai_mcp_destructive_label), warning = true)
                        }
                    }
                }
                ToolPolicyChoices(policy, onPick, Modifier.padding(top = 8.dp))
            }
        },
    )
}

/**
 * Run / Ask / Off for one tool, as a row of three. Shared by a server's tools
 * and the keyboard's own (#698), which carry the same choice.
 */
@Composable
internal fun ToolPolicyChoices(
    policy: McpToolPolicy,
    onPick: (McpToolPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = McpToolPolicy.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = policy == choice,
                onClick = { onPick(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                label = { Text(stringResource(choice.labelRes()), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun McpBadge(text: String, warning: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (warning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Whether [url] is plain `http://` to a host off the local network. On the
 * network it is the usual way to reach a server on a laptop; anywhere else
 * the header token and every tool input cross the internet in the clear.
 */
internal fun isPlainRemoteHttp(url: String): Boolean {
    val lower = url.trim().lowercase()
    if (!lower.startsWith("http://")) return false
    val authority = lower.removePrefix("http://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.removePrefix("[").substringBefore(']')
    } else {
        authority.substringBefore(':')
    }
    return host.isNotEmpty() && !isLocalHost(host)
}

private fun isLocalHost(host: String): Boolean {
    if (host == "localhost" || host == "::1" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    return when (parts[0]) {
        10, 127 -> true
        192 -> parts[1] == 168
        172 -> parts[1] in 16..31
        169 -> parts[1] == 254
        else -> false
    }
}

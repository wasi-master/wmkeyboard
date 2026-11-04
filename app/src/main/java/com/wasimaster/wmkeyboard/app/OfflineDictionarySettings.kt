package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryCatalog
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryDownloads
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryDownloads.Status
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryPack

/**
 * The Dictionary tool's offline dictionaries (#665): a row per pack with its
 * download, or its Delete once it is here, and the import row for a phone
 * that cannot download (the no-internet build, or no signal). Which of them
 * the tool asks, and in what order, is the source list above.
 */
@Composable
internal fun OfflineDictionariesGroup(
    settings: LiveSettings,
    /** The packs to list: one source's own on its provider screen. */
    packs: List<OfflineDictionaryPack> = OfflineDictionaryCatalog.entries,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val states by OfflineDictionaryDownloads.states.collectAsState()
    val downloadDecision = rememberDownloadDecision(settings)
    val notifyDownload = rememberDownloadNotifier()
    var confirmMetered by remember { mutableStateOf<(() -> Unit)?>(null) }
    var blocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { OfflineDictionaryDownloads.refresh(filesDir) }
    val canDownload = InternetPermission.granted

    fun gated(action: () -> Unit) {
        when (downloadDecision()) {
            MeteredDecision.ALLOWED -> action()
            MeteredDecision.ASK -> confirmMetered = action
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    SettingsGroup(
        stringResource(R.string.tooldetail_dictionary_offline_group),
        info = stringResource(R.string.tooldetail_dictionary_offline_info),
    ) {
        for (pack in packs) {
            item {
                OfflineDictionaryRow(
                    pack = pack,
                    status = states[pack.id] ?: Status.NotDownloaded,
                    canDownload = canDownload,
                    onDownload = {
                        gated {
                            OfflineDictionaryDownloads.start(filesDir, pack)
                            notifyDownload(pack.id, pack.name, DownloadProgressFlows.offlineDictionary(context, pack.id))
                        }
                    },
                    onCancel = { OfflineDictionaryDownloads.cancel(pack.id) },
                    onDelete = { OfflineDictionaryDownloads.delete(filesDir, pack.id) },
                )
            }
        }
        item {
            OfflineImportRow(
                title = stringResource(R.string.tooldetail_dictionary_offline_import_title),
                subtitle = stringResource(R.string.tooldetail_dictionary_offline_import_subtitle),
                links = packs.map { OfflineLink(it.name, it.url) },
                onImported = { OfflineDictionaryDownloads.refresh(filesDir) },
            )
        }
    }
    confirmMetered?.let { action ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = { confirmMetered = null; action() },
            onDismiss = { confirmMetered = null },
        )
    }
    if (blocked) MeteredBlockedDialog { blocked = false }
}

@Composable
private fun OfflineDictionaryRow(
    pack: OfflineDictionaryPack,
    status: Status,
    canDownload: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val busy = status is Status.Downloading || status == Status.Queued
    val subtitle = when (status) {
        is Status.Downloaded -> stringResource(R.string.tooldetail_dictionary_offline_installed, formatBytes(status.sizeBytes), pack.license)
        is Status.Downloading -> stringResource(
            R.string.tooldetail_dictionary_offline_downloading,
            formatBytes(status.bytes),
            formatBytes(status.totalBytes),
        )
        Status.Queued -> stringResource(R.string.tooldetail_dictionary_offline_queued)
        is Status.Failed -> when {
            status.notAPack -> stringResource(R.string.tooldetail_dictionary_offline_failed_not_a_pack)
            status.httpStatus != 0 -> stringResource(R.string.tooldetail_dictionary_offline_failed_http, status.httpStatus.toString())
            else -> stringResource(OfflineBuild.download(CommonR.string.common_error_network))
        }
        Status.NotDownloaded -> if (pack.approxGzBytes > 0) {
            stringResource(R.string.tooldetail_dictionary_offline_available, formatBytes(pack.approxGzBytes), pack.license)
        } else {
            pack.license
        }
    }
    Column {
        WmRow(
            title = pack.name,
            supporting = { Text(subtitle) },
            swipeActions = if (status is Status.Downloaded) {
                listOf(
                    deleteSwipeAction(
                        stringResource(R.string.tooldetail_dictionary_offline_delete_desc, pack.name),
                        onDelete,
                    ),
                )
            } else {
                emptyList()
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        status is Status.Downloaded -> IconButton(onClick = onDelete) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.tooldetail_dictionary_offline_delete_desc, pack.name))
                        }
                        busy -> IconButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Close, stringResource(CommonR.string.common_cancel))
                        }
                        // The no-internet build only imports; the row below does that.
                        canDownload -> IconButton(onClick = onDownload) {
                            Icon(
                                if (status is Status.Failed) Icons.Outlined.Refresh else Icons.Outlined.Download,
                                stringResource(R.string.tooldetail_dictionary_offline_download_desc, pack.name),
                            )
                        }
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (status is Status.Downloading) {
            LinearProgressIndicator(
                progress = { if (status.totalBytes > 0) (status.bytes.toFloat() / status.totalBytes).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

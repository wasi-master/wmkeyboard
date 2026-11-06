package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupCrypto
import com.wasimaster.wmkeyboard.core.settings.BackupInstall
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * One automatic backup as the restore list shows it.
 *
 * [device] is already the sentence: "This device", the name another phone
 * wrote into the file name, or the words for a backup from before names
 * carried one. [group] is the sync group the backup was taken in, empty for
 * none.
 */
private data class RemoteBackup(
    val entry: SinkEntry,
    val device: String,
    val mine: Boolean,
    val takenAtMs: Long,
    val encrypted: Boolean,
    val group: String = "",
)

private sealed interface RemoteStage {
    data object Loading : RemoteStage
    /** [group] is the filter to start on: this device's group when it has backups here, else all. */
    data class Listed(val backups: List<RemoteBackup>, val group: String?) : RemoteStage
    data object Downloading : RemoteStage
    data class Failed(val message: String) : RemoteStage
    data class Ready(val uri: Uri, val file: File) : RemoteStage
}

/**
 * Restores one of the automatic backups from the destination they went to.
 *
 * The only way back for a Google Drive backup: `appDataFolder` is invisible
 * to every app but this one, so there is no file for the ordinary import to
 * pick. It is also quicker for the others than finding the file in another
 * app's folder by hand.
 *
 * The list is every device's backups, not just this one's, because the
 * obvious reason to open it is a new phone. Where several people share the
 * location, each backup carries its sync group, and the list can be narrowed
 * to one group. Picking one downloads it to the
 * cache and hands it to [ImportFileDialog], which is the confirmation, the
 * section summary and the merge every other import goes through. An
 * encrypted backup is opened with the stored passphrase first; only when that
 * does not open it does the dialog ask.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteRestoreDialog(
    repository: SettingsRepository,
    auto: AutoBackupSettings,
    location: BackupLocation,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<RemoteStage>(RemoteStage.Loading) }

    // Null for all groups. Set from the listing, then by the chips.
    var group by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        stage = list(context, location, auto.sync.group).also { listed ->
            if (listed is RemoteStage.Listed) group = listed.group
        }
    }

    when (val current = stage) {
        RemoteStage.Loading, RemoteStage.Downloading -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.backup_location_restore, location.title(context))) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(
                        stringResource(
                            if (current == RemoteStage.Loading) {
                                R.string.backup_remote_restore_loading
                            } else {
                                R.string.backup_remote_restore_downloading
                            },
                        ),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )

        is RemoteStage.Failed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.backup_location_restore, location.title(context))) },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )

        is RemoteStage.Listed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.backup_location_restore, location.title(context))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.backup_remote_restore_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Chips only when there is something to tell apart.
                    val groups = current.backups.map { it.group }.distinct().sorted()
                    if (groups.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = group == null,
                                onClick = { group = null },
                                label = { Text(stringResource(R.string.backup_remote_restore_all_groups)) },
                            )
                            for (each in groups) {
                                FilterChip(
                                    selected = group == each,
                                    onClick = { group = each },
                                    label = {
                                        Text(each.ifEmpty { stringResource(R.string.backup_sync_group_no_group) })
                                    },
                                )
                            }
                        }
                    }
                    val shown = current.backups.filter { group == null || it.group == group }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.entry.id }) { backup ->
                            RemoteBackupRow(context, backup) {
                                stage = RemoteStage.Downloading
                                scope.launch { stage = download(context, auto, location, backup) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )

        is RemoteStage.Ready -> ImportFileDialog(repository, current.uri) {
            current.file.delete()
            onClose()
        }
    }
}

@Composable
private fun RemoteBackupRow(context: Context, backup: RemoteBackup, onClick: () -> Unit) {
    val taken = DateUtils.formatDateTime(
        context,
        backup.takenAtMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )
    val size = backup.entry.sizeBytes.takeIf { it >= 0 }
        ?.let { Formatter.formatShortFileSize(context, it) }
    ListItem(
        headlineContent = {
            Text(
                if (backup.group.isEmpty()) {
                    backup.device
                } else {
                    stringResource(R.string.backup_remote_restore_device_group, backup.device, backup.group)
                },
            )
        },
        supportingContent = {
            Text(
                if (size != null) {
                    stringResource(R.string.backup_remote_restore_entry, taken, size)
                } else {
                    taken
                },
            )
        },
        trailingContent = if (backup.encrypted) {
            {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = stringResource(R.string.backup_remote_restore_encrypted),
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/**
 * Every automatic backup at the destination, newest first, starting on
 * [myGroup] when any backup there is in it.
 */
private suspend fun list(context: Context, location: BackupLocation, myGroup: String): RemoteStage {
    val sink = AutoBackupRunner.sinkFor(context, location)
        ?: return RemoteStage.Failed(errorText(context, SinkError.NOT_CONFIGURED, location))
    // Backups only: a location lists sync files too, and those are not
    // something to restore from.
    val entries = sink.list().getOrElse { failure ->
        return RemoteStage.Failed(errorText(context, (failure as? BackupSinkException)?.reason, location))
    }.filter { AutoBackupNaming.isOurs(it.name) || AutoBackupNaming.isOurs(it.name, frequent = true) }
    if (entries.isEmpty()) {
        return RemoteStage.Failed(context.getString(R.string.backup_remote_restore_empty))
    }
    val me = BackupInstall.id(context)
    val backups = entries.map { entry ->
        val parsed = AutoBackupNaming.parse(entry.name)
        val mine = parsed?.installId == me
        val device = parsed?.device
        RemoteBackup(
            entry = entry,
            device = when {
                mine -> context.getString(R.string.backup_remote_restore_this_device)
                device != null -> device.replace('-', ' ')
                else -> context.getString(R.string.backup_remote_restore_unknown_device)
            },
            mine = mine,
            takenAtMs = parsed?.stampMs ?: entry.modifiedAtMs,
            encrypted = parsed?.encrypted ?: false,
            group = parsed?.group.orEmpty(),
        )
    }
    // Start on this device's group only where groups are in use at all: an
    // ungrouped location lists everything, as it always did.
    val start = myGroup.takeIf { it.isNotEmpty() && backups.any { b -> b.group == it } }
    return RemoteStage.Listed(backups.sortedByDescending { it.takenAtMs }, start)
}

/**
 * Downloads [backup] into the cache for [ImportFileDialog] to open.
 *
 * An encrypted one is tried against the stored passphrase here, and written
 * out as plain text when that works, so the dialog shows its ordinary summary
 * instead of asking for something the app already has. When it does not work
 * (another phone's backup, or a passphrase changed since), the ciphertext is
 * written as it came and the dialog asks.
 */
private suspend fun download(
    context: Context,
    auto: AutoBackupSettings,
    location: BackupLocation,
    backup: RemoteBackup,
): RemoteStage {
    val failed = RemoteStage.Failed(context.getString(R.string.backup_remote_restore_failed))
    val sink = AutoBackupRunner.sinkFor(context, location) ?: return failed
    return withContext(Dispatchers.IO) {
        runCancellable {
            // Straight to the cache as it comes, never held whole (#1080).
            val dir = File(context.cacheDir, "remote-restore").apply {
                deleteRecursively()
                mkdirs()
            }
            val downloaded = File(dir, backup.entry.name)
            sink.read(backup.entry).getOrThrow().use { input ->
                downloaded.outputStream().buffered().use { input.copyTo(it) }
            }
            val head = ByteArray(BackupCrypto.MAGIC_SIZE)
            val headRead = downloaded.inputStream().use { it.read(head) }
            val encrypted = headRead == head.size && BackupCrypto.looksEncrypted(head)
            // Opened a piece at a time into a file of its own. A file that
            // does not open, or fails a tag anywhere, is left as it came for
            // the dialog to ask about, and its half-written plaintext goes.
            val opened = if (encrypted && auto.passphrase.isNotEmpty()) {
                val plain = File(dir, "restore.wmconfig.json")
                val failure = downloaded.inputStream().use { input ->
                    plain.outputStream().buffered().use { out ->
                        BackupCrypto.decryptTo(input, auto.passphrase.toCharArray(), out)
                    }
                }
                if (failure == null) plain else null.also { plain.delete() }
            } else {
                null
            }
            val file = opened?.also { downloaded.delete() } ?: downloaded
            RemoteStage.Ready(Uri.fromFile(file), file) as RemoteStage
        }.getOrElse { failed }
    }
}

private fun errorText(context: Context, reason: SinkError?, location: BackupLocation): String =
    autoBackupErrorText(context, (reason ?: SinkError.IO).name, location.type)
        ?: context.getString(R.string.backup_auto_error_io)

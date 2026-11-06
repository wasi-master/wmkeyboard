package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.drive.driveAuthorizer
import com.wasimaster.wmkeyboard.app.oauth.BackupOAuth
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.DriveSpace
import com.wasimaster.wmkeyboard.core.settings.GitProvider
import com.wasimaster.wmkeyboard.core.settings.ImapSecurity
import com.wasimaster.wmkeyboard.core.settings.S3Account
import com.wasimaster.wmkeyboard.core.settings.S3Preset
import com.wasimaster.wmkeyboard.core.settings.WebDavPreset
import com.wasimaster.wmkeyboard.core.settings.WebDavServerField
import com.wasimaster.wmkeyboard.core.settings.LocationStatus
import com.wasimaster.wmkeyboard.core.settings.PlaceWire
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.isLink
import com.wasimaster.wmkeyboard.core.settings.signsIn
import com.wasimaster.wmkeyboard.core.settings.sink.ProfileCode
import com.wasimaster.wmkeyboard.core.settings.sink.ProfileLink
import com.wasimaster.wmkeyboard.core.settings.sync.SyncScheduler
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.DriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.GitSink
import com.wasimaster.wmkeyboard.core.settings.sink.NextcloudLogin
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.SftpSink
import com.wasimaster.wmkeyboard.core.settings.sink.QnapSink
import com.wasimaster.wmkeyboard.core.settings.sink.SynologySink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.WebDavSink
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A destination's brand-ish colour, for its tile. Muted where there is no brand. */
internal val BackupDestination.accent: Color
    get() = when (this) {
        BackupDestination.FOLDER -> Color(0xFFF9A825)
        BackupDestination.WEBDAV -> Color(0xFF00897B)
        BackupDestination.S3 -> Color(0xFFEF6C00)
        BackupDestination.FTP -> Color(0xFF546E7A)
        BackupDestination.DRIVE -> Color(0xFF1E8E3E)
        BackupDestination.DROPBOX -> Color(0xFF0061FE)
        BackupDestination.ONEDRIVE -> Color(0xFF0078D4)
        BackupDestination.SFTP -> Color(0xFF37474F)
        BackupDestination.SMB -> Color(0xFF5C6BC0)
        BackupDestination.GIT -> Color(0xFFF05032)
        BackupDestination.IMAP -> Color(0xFFC62828)
        BackupDestination.NEARBY -> Color(0xFF7E57C2)
        BackupDestination.PROFILE -> Color(0xFF5E35B1)
        BackupDestination.BOX -> Color(0xFF0061D5)
        BackupDestination.PCLOUD -> Color(0xFF17BED0)
        BackupDestination.SYNOLOGY -> Color(0xFF3D5A80)
        BackupDestination.QNAP -> Color(0xFF1565C0)
        BackupDestination.NFS -> Color(0xFF6D4C41)
        BackupDestination.MEGA -> Color(0xFFD9272E)
        BackupDestination.ICLOUD -> Color(0xFF3693F3)
        BackupDestination.PROTON -> Color(0xFF6D4AFF)
    }

/** WM Pass's shield runs emerald to teal; its tile on the add sheet takes the teal. */
private val WM_PASS_ACCENT = Color(0xFF00897B)

/** The destination's own name: "Dropbox", "WebDAV", "Folder". */
internal fun destinationName(context: Context, type: BackupDestination): String = context.getString(
    when (type) {
        BackupDestination.FOLDER -> R.string.backup_auto_dest_folder
        BackupDestination.WEBDAV -> R.string.backup_auto_dest_webdav
        BackupDestination.S3 -> R.string.backup_auto_dest_s3
        BackupDestination.FTP -> R.string.backup_auto_dest_ftp
        BackupDestination.DRIVE -> R.string.backup_auto_dest_drive
        BackupDestination.DROPBOX -> R.string.backup_auto_dest_dropbox
        BackupDestination.ONEDRIVE -> R.string.backup_auto_dest_onedrive
        BackupDestination.SFTP -> R.string.backup_auto_dest_sftp
        BackupDestination.SMB -> R.string.backup_auto_dest_smb
        BackupDestination.GIT -> R.string.backup_auto_dest_git
        BackupDestination.IMAP -> R.string.backup_auto_dest_imap
        BackupDestination.NEARBY -> R.string.backup_auto_dest_nearby
        BackupDestination.PROFILE -> R.string.backup_auto_dest_profile
        BackupDestination.BOX -> R.string.backup_auto_dest_box
        BackupDestination.PCLOUD -> R.string.backup_auto_dest_pcloud
        BackupDestination.SYNOLOGY -> R.string.backup_auto_dest_synology
        BackupDestination.QNAP -> R.string.backup_auto_dest_qnap
        BackupDestination.NFS -> R.string.backup_auto_dest_nfs
        BackupDestination.MEGA -> R.string.backup_auto_dest_mega
        BackupDestination.ICLOUD -> R.string.backup_auto_dest_icloud
        BackupDestination.PROTON -> R.string.backup_auto_dest_proton
    },
)

/** What the user called a location, or its destination's name. */
internal fun BackupLocation.title(context: Context): String = name.ifEmpty { destinationName(context, type) }

/**
 * The line that tells two locations of one kind apart: the folder, the
 * server, the bucket. Null where there is nothing to say (one account per
 * service), and where the title already says it.
 */
internal fun BackupLocation.detail(context: Context): String? = when (type) {
    // A tree URI's last segment is "primary:Download/Backups", or on some
    // providers a raw path. Either way the person wants the folder, not the
    // volume's mount point.
    BackupDestination.FOLDER -> folderUri.takeIf { it.isNotEmpty() }?.let {
        Uri.decode(it.substringAfterLast('/')).substringAfterLast(':')
            .removePrefix("/storage/emulated/0/").removePrefix("/sdcard/").ifEmpty { null }
    }
    BackupDestination.WEBDAV -> webDavUrl.takeIf { it.isNotEmpty() }?.let {
        runCatching { Uri.parse(it).host }.getOrNull() ?: it
    }
    BackupDestination.S3 -> s3.bucket.ifEmpty { null }
    BackupDestination.FTP -> ftp.host.ifEmpty { null }
    BackupDestination.SFTP -> sftp.host.ifEmpty { null }
    BackupDestination.SMB -> smb.host.takeIf { it.isNotEmpty() }?.let { "\\\\$it\\${smb.share}" }
    BackupDestination.GIT -> git.repository.ifEmpty { null }
    BackupDestination.IMAP -> imap.user.takeIf { it.isNotEmpty() }?.let { if (it.contains('@')) it else "$it@${imap.host}" }
    BackupDestination.DRIVE -> driveFolder.takeIf { driveSpace == DriveSpace.FOLDER }
    BackupDestination.BOX -> box.folder.ifEmpty { null }
    BackupDestination.PCLOUD -> pcloud.email.ifEmpty { null }
    BackupDestination.SYNOLOGY, BackupDestination.QNAP -> nas.server.ifEmpty { null }
    BackupDestination.NFS -> nfs.host.takeIf { it.isNotEmpty() }?.let { "$it:${nfs.export}" }
    BackupDestination.MEGA -> mega.email.ifEmpty { null }
    BackupDestination.ICLOUD -> icloud.appleId.ifEmpty { null }
    BackupDestination.PROTON -> proton.user.ifEmpty { null }
    else -> null
}?.takeIf { name.isEmpty() || it != name } ?: if (name.isNotEmpty()) destinationName(context, type) else null

/** The destinations this build can reach, in the order the add sheet lists them. */
internal fun availableDestinations(): List<BackupDestination> = buildList {
    add(BackupDestination.FOLDER)
    // The offline build has no internet permission, so every other place is
    // a socket it may not open. A folder still reaches whatever the phone's
    // own storage providers sync.
    if (!InternetPermission.granted) return@buildList
    if (driveAuthorizer().available) add(BackupDestination.DRIVE)
    if (BackupClients.dropboxAvailable) add(BackupDestination.DROPBOX)
    if (BackupClients.oneDriveAvailable) add(BackupDestination.ONEDRIVE)
    if (BackupClients.boxAvailable) add(BackupDestination.BOX)
    add(BackupDestination.ICLOUD)
    add(BackupDestination.PROTON)
    add(BackupDestination.MEGA)
    add(BackupDestination.PCLOUD)
    add(BackupDestination.WEBDAV)
    add(BackupDestination.S3)
    add(BackupDestination.SYNOLOGY)
    add(BackupDestination.QNAP)
    add(BackupDestination.SFTP)
    add(BackupDestination.SMB)
    add(BackupDestination.NFS)
    add(BackupDestination.FTP)
    add(BackupDestination.GIT)
    add(BackupDestination.IMAP)
    // Never leaves the phone, but it is a socket all the same: 127.0.0.1.
    add(BackupDestination.PROFILE)
}

/** An example name for [type], under the optional Name field. */
private fun nameHintRes(type: BackupDestination): Int = when (type) {
    BackupDestination.FOLDER -> R.string.backup_location_name_hint_folder
    BackupDestination.WEBDAV -> R.string.backup_location_name_hint_webdav
    BackupDestination.S3 -> R.string.backup_location_name_hint_s3
    BackupDestination.FTP -> R.string.backup_location_name_hint_ftp
    BackupDestination.DRIVE -> R.string.backup_location_name_hint_drive
    BackupDestination.DROPBOX -> R.string.backup_location_name_hint_dropbox
    BackupDestination.ONEDRIVE -> R.string.backup_location_name_hint_onedrive
    BackupDestination.SFTP -> R.string.backup_location_name_hint_sftp
    BackupDestination.SMB -> R.string.backup_location_name_hint_smb
    BackupDestination.GIT -> R.string.backup_location_name_hint_git
    BackupDestination.IMAP -> R.string.backup_location_name_hint_imap
    BackupDestination.NEARBY -> R.string.backup_location_name_hint_nearby
    BackupDestination.PROFILE -> R.string.backup_location_name_hint_profile
    BackupDestination.BOX -> R.string.backup_location_name_hint_box
    BackupDestination.PCLOUD -> R.string.backup_location_name_hint_pcloud
    BackupDestination.SYNOLOGY -> R.string.backup_location_name_hint_synology
    BackupDestination.QNAP -> R.string.backup_location_name_hint_qnap
    BackupDestination.NFS -> R.string.backup_location_name_hint_nfs
    BackupDestination.MEGA -> R.string.backup_location_name_hint_mega
    BackupDestination.ICLOUD -> R.string.backup_location_name_hint_icloud
    BackupDestination.PROTON -> R.string.backup_location_name_hint_proton
}

/** What a location that cannot be tried yet is missing, in the words for its kind. */
internal fun needsSetupRes(type: BackupDestination): Int = when {
    type == BackupDestination.FOLDER -> R.string.backup_auto_enabled_needs_folder
    type.signsIn -> R.string.backup_auto_enabled_needs_sign_in
    else -> R.string.backup_auto_enabled_needs_details
}

/**
 * The Locations card on the Backup & restore screen: one row per location,
 * with how its last backup went, and a row that adds another.
 */
@Composable
internal fun LocationsGroup(
    repository: SettingsRepository,
    auto: AutoBackupSettings,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BackupLocation?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<BackupLocation?>(null) }
    var removing by remember { mutableStateOf<BackupLocation?>(null) }

    // A place WM Pass handed over opens in the editor as a new one, not yet
    // saved: an account still needs its sign-in here, a folder its folder.
    val fromWmPass = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val raw = result.data?.getStringExtra(PlaceWire.EXTRA) ?: return@rememberLauncherForActivityResult
        val place = PlaceWire.decode(raw)
        when {
            place == null -> onMessage(context.getString(R.string.wmpass_place_from_unknown))
            place.type !in availableDestinations() -> onMessage(
                context.getString(R.string.wmpass_place_from_unavailable, destinationName(context, place.type)),
            )
            else -> {
                editingIsNew = true
                editing = place
            }
        }
    }

    fun resync() = scope.launch {
        AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
    }

    // How a browser sign-in ended, said here rather than in the sheet that
    // started it: the sheet may be closed by the time the browser hands back,
    // and a result nobody consumed would surface much later, out of place.
    val signIn by BackupOAuth.result.collectAsStateWithLifecycle()
    LaunchedEffect(signIn) {
        val delivered = signIn ?: return@LaunchedEffect
        BackupOAuth.consume()
        when (delivered.outcome) {
            BackupOAuth.Outcome.SIGNED_IN -> Unit
            BackupOAuth.Outcome.CANCELLED -> onMessage(context.getString(R.string.backup_auto_oauth_cancelled))
            BackupOAuth.Outcome.FAILED -> onMessage(context.getString(R.string.backup_auto_oauth_failed))
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_locations_title),
        info = stringResource(R.string.backup_locations_info),
    ) {
        for (location in auto.locations) {
            item(key = location.id) {
                LocationRow(
                    location = location,
                    syncs = auto.sync.enabled && location.id in auto.sync.locationIds,
                    status = auto.locationStatus[location.id] ?: LocationStatus(),
                    onOpen = {
                        editingIsNew = false
                        editing = location
                    },
                    onRestore = { restoring = location },
                    onRemove = { removing = location },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.backup_location_add),
                subtitle = stringResource(R.string.backup_location_add_subtitle),
                icon = Icons.Outlined.Add,
                onClick = { adding = true },
            )
        }
    }

    if (adding) {
        LocationTypeSheet(
            onPick = { type ->
                adding = false
                editingIsNew = true
                editing = BackupLocation(
                    id = BackupLocation.newId(),
                    type = type,
                    // A link only syncs.
                    backup = !type.isLink,
                    profileCode = if (type == BackupDestination.PROFILE) ProfileCode.format(ProfileCode.generate()) else "",
                )
            },
            onFromWmPass = {
                adding = false
                // Checked again at the tap: the package could have changed
                // hands since the sheet opened.
                if (WmPassBridge.isInstalled(context)) {
                    try {
                        fromWmPass.launch(WmPassBridge.placePicker())
                    } catch (_: ActivityNotFoundException) {
                        onMessage(context.getString(R.string.wmpass_place_from_missing))
                    }
                }
            }.takeIf { WmPassBridge.isInstalled(context) },
            onDismiss = { adding = false },
        )
    }
    editing?.let { location ->
        LocationEditorSheet(
            repository = repository,
            initial = location,
            isNew = editingIsNew,
            live = auto.locations.firstOrNull { it.id == location.id },
            all = auto.locations,
            onMessage = onMessage,
            onDismiss = {
                editing = null
                resync()
            },
        )
    }
    restoring?.let { location ->
        RemoteRestoreDialog(repository, auto, location) { restoring = null }
    }
    removing?.let { location ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.backup_location_remove_title, location.title(context))) },
            text = { Text(stringResource(R.string.backup_location_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        repository.removeBackupLocation(location.id)
                        repository.setSyncLocation(location.id, on = false)
                        releaseFolderGrant(context, location, auto.locations)
                        // The last link to another profile: its mailbox goes too.
                        if (location.type == BackupDestination.PROFILE &&
                            auto.locations.none { it.id != location.id && it.type == BackupDestination.PROFILE }
                        ) {
                            ProfileLink.forget(context)
                        }
                        resync()
                    }
                }) { Text(stringResource(R.string.backup_location_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

/**
 * Gives back the folder grant a removed Folder location held, unless another
 * location still points at the same folder. Android caps how many grants an
 * app may keep, and one nobody uses counts against it forever.
 */
private fun releaseFolderGrant(context: Context, removed: BackupLocation, all: List<BackupLocation>) {
    if (removed.type != BackupDestination.FOLDER) return
    releaseFolderUri(context, removed.folderUri, all.filter { it.id != removed.id })
}

/** Gives back the grant on [uri], unless one of [others] still uses that folder. */
private fun releaseFolderUri(context: Context, uri: String, others: List<BackupLocation>) {
    if (uri.isEmpty() || others.any { it.folderUri == uri }) return
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

/** One location: its tile, what it is, how its last backup went, and a menu. */
@Composable
private fun LocationRow(
    location: BackupLocation,
    syncs: Boolean,
    status: LocationStatus,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val title = location.title(context)
    val detail = location.detail(context)
    val (backupState, problem) = when {
        // A location carried over from another build, in one that cannot go
        // online: say why it never runs, rather than offering a sign-in that
        // has nothing to sign in through.
        !InternetPermission.granted && location.type != BackupDestination.FOLDER ->
            stringResource(R.string.backup_location_no_internet) to true
        !location.enabled -> stringResource(R.string.backup_location_paused) to false
        !location.configured -> stringResource(needsSetupRes(location.type)) to true
        !location.backup -> stringResource(R.string.backup_location_not_backed_up) to false
        status.backupError.isNotEmpty() ->
            (autoBackupErrorText(context, status.backupError, location.type) ?: "") to true
        status.backupAtMs > 0 -> stringResource(
            R.string.backup_location_last_backup,
            DateUtils.getRelativeTimeSpanString(status.backupAtMs).toString(),
        ) to false
        else -> stringResource(R.string.backup_location_never) to false
    }
    val state = if (syncs && location.configured) {
        stringResource(R.string.backup_location_state_syncs, backupState)
    } else {
        backupState
    }
    var menu by remember { mutableStateOf(false) }
    WmRow(
        title = title,
        leading = {
            WmIconTile(
                icon = destinationIcon(location.type),
                accent = if (location.enabled) location.type.accent else MaterialTheme.colorScheme.outline,
            )
        },
        supporting = {
            Column {
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (problem) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                        )
                    }
                    Text(
                        state,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        swipeActions = buildList {
            // A link holds sync files only, never a backup to restore.
            if (location.configured && !location.type.isLink) {
                add(SwipeAction(Icons.Outlined.Restore, stringResource(R.string.backup_location_restore, title), onClick = onRestore))
            }
            add(deleteSwipeAction(stringResource(R.string.backup_location_remove), onRemove))
        },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.backup_location_more, title),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.backup_location_restore, title)) },
                        enabled = location.configured && !location.type.isLink,
                        onClick = {
                            menu = false
                            onRestore()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.backup_location_remove)) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        },
        onClick = onOpen,
    )
}

/** The glyph each destination is drawn with, the same one the picker uses. */
internal fun destinationIcon(type: BackupDestination) =
    ChoiceOptionIcons[type] ?: Icons.Outlined.Add

/**
 * "What kind of location?" as a sheet of places, each in its own colour with
 * what it needs. Not a [ChoiceSheet]: that draws radio buttons, which read as
 * a setting with one current answer, and nothing here is selected yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationTypeSheet(
    onPick: (BackupDestination) -> Unit,
    /** Null where WM Pass is not installed, or is not the real one. */
    onFromWmPass: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The sheet already keeps its content clear of the navigation bar.
        // Padding for it again inside the scroll made the content's height
        // depend on where the sheet was, and a scroll that nudged the sheet
        // set it chasing its own resting place up and down. The rail says the
        // list goes on: a dozen kinds of place do not fit a phone.
        ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
            Text(
                stringResource(R.string.backup_location_type_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.backup_location_type_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (onFromWmPass != null) {
                WmRow(
                    title = stringResource(R.string.wmpass_place_from_title),
                    subtitle = stringResource(R.string.wmpass_place_from_subtitle),
                    leading = { WmIconTile(icon = Icons.Outlined.Key, accent = WM_PASS_ACCENT) },
                    onClick = onFromWmPass,
                )
            }
            for (type in availableDestinations()) {
                WmRow(
                    title = destinationName(context, type),
                    subtitle = stringResource(destinationDescRes(type)),
                    leading = { WmIconTile(icon = destinationIcon(type), accent = type.accent) },
                    onClick = { onPick(type) },
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Setting up or changing one location, in a sheet over the Backup screen.
 *
 * Typed fields edit a draft that is saved by Save, so a half-typed address is
 * never tried by a backup that happens to run meanwhile. The two things that
 * are not typed save themselves: a sign-in (Dropbox, OneDrive) stores its
 * token when the browser hands back, and a folder grant is taken when the
 * picker returns. [live] is the stored copy, for those.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationEditorSheet(
    repository: SettingsRepository,
    initial: BackupLocation,
    isNew: Boolean,
    live: BackupLocation?,
    all: List<BackupLocation>,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    val others = all.filter { it.id != initial.id }
    // Closed without saving: a folder picked here and not kept by anything
    // gives its grant back. The saved folder, if any, stays granted.
    val cancel = {
        if (draft.folderUri != (live?.folderUri ?: "")) releaseFolderUri(context, draft.folderUri, all)
        onDismiss()
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // Set by Forget the server key, so the key a backup stored meanwhile does
    // not come straight back from [live].
    var keyForgotten by remember { mutableStateOf(false) }
    // The same for a NAS certificate, with Forget the certificate.
    var certForgotten by remember { mutableStateOf(false) }
    // The stored token wins over the draft's: it is what the sign-in wrote.
    // An SFTP host key the draft lacks comes from the stored copy the same way,
    // since a backup that ran meanwhile may have recorded it.
    val current = draft.copy(
        refreshToken = live?.refreshToken ?: draft.refreshToken,
        sftp = if (draft.sftp.hostKey.isEmpty() && !keyForgotten) {
            draft.sftp.copy(hostKey = live?.sftp?.hostKey.orEmpty())
        } else {
            draft.sftp
        },
        nas = if (draft.nas.certPin.isEmpty() && !certForgotten && live?.nas?.server == draft.nas.server) {
            draft.nas.copy(certPin = live.nas.certPin)
        } else {
            draft.nas
        },
        // Tokens a backup refreshed while the sheet was open; signing out
        // here (an empty uid, no trust token) keeps the draft's.
        proton = if (draft.proton.uid.isNotEmpty() && live?.proton?.uid == draft.proton.uid) {
draft.proton.copy(accessToken = live.proton.accessToken, refreshToken = live.proton.refreshToken)
        } else {
            draft.proton
        },
        icloud = if (draft.icloud.trustToken.isNotEmpty() && live?.icloud?.trustedAtMs == draft.icloud.trustedAtMs) {
draft.icloud.copy(cookies = live.icloud.cookies, trustToken = live.icloud.trustToken)
        } else {
            draft.icloud
        },
    )

    fun save(then: () -> Unit = {}) = scope.launch {
        repository.upsertBackupLocation(current)
        // Only sync goes through it, so a new one is ticked for sync with sync
        // on, as pairing a computer does: nothing else would make it do
        // anything. The first pass puts this copy's file in the mailbox.
        if (isNew && current.type == BackupDestination.PROFILE) {
            repository.setSyncLocation(current.id, true)
            repository.setSyncEnabled(true)
            SyncScheduler.requestSoon(context, repository.settings.first().autoBackup, delayMs = 0L)
        }
        // A folder replaced by another gives its grant back.
        val before = live?.folderUri.orEmpty()
        if (before != current.folderUri) releaseFolderUri(context, before, others + current)
        then()
    }

    ModalBottomSheet(
        onDismissRequest = cancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // As the kind picker above: no inset padding of its own, and a rail
        // for a form that runs past the screen with Save at the bottom.
        ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                WmIconTile(icon = destinationIcon(draft.type), accent = draft.type.accent)
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(destinationName(context, draft.type), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(destinationDescRes(draft.type)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StoredTextField(
                label = stringResource(R.string.backup_location_name_label),
                value = draft.name,
                supporting = stringResource(nameHintRes(draft.type)),
            ) { draft = draft.copy(name = it) }

            when (draft.type) {
                BackupDestination.FOLDER -> FolderPart(draft) { uri ->
                    draft = draft.copy(folderUri = uri)
                    testResult = null
                }
                BackupDestination.WEBDAV -> WebDavPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.S3 -> S3Part(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.FTP -> FtpPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.DROPBOX, BackupDestination.ONEDRIVE ->
                    SignInPart(repository, current, onMessage)
                BackupDestination.DRIVE -> DrivePart(draft, onMessage) {
                    draft = it
                    testResult = null
                }
                BackupDestination.SFTP -> SftpPart(current, onForget = {
                    keyForgotten = true
                    draft = draft.copy(sftp = draft.sftp.copy(hostKey = ""))
                    testResult = null
                }) {
                    draft = it
                    testResult = null
                }
                BackupDestination.SMB -> SmbPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.GIT -> GitPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.IMAP -> ImapPart(draft) {
                    draft = it
                    testResult = null
                }
                // Nothing to fill in: the pairing made it.
                BackupDestination.NEARBY -> Unit
                BackupDestination.PROFILE -> ProfilePart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.BOX -> BoxPart(repository, current, draft, onMessage) {
                    draft = it
                    testResult = null
                }
                BackupDestination.PCLOUD -> PCloudPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.SYNOLOGY, BackupDestination.QNAP -> NasPart(current, onForgetCertificate = {
                    certForgotten = true
                    draft = draft.copy(nas = draft.nas.copy(certPin = ""))
                    testResult = null
                }) {
                    draft = it
                    testResult = null
                }
                BackupDestination.NFS -> NfsPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.MEGA -> MegaPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.ICLOUD -> ICloudPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.PROTON -> ProtonPart(draft) {
                    draft = it
                    testResult = null
                }
            }

            // Test: the readiness call a backup makes first, with what is on
            // screen now. The cheapest way to find a typo in a host name is
            // before the first night's backup fails on it.
            if (current.configured) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OutlinedButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                // Not a place to write to: the question is
                                // whether the other copy answers.
                                if (current.type == BackupDestination.PROFILE) {
                                    val answered = ProfileCode.normalize(current.profileCode)
                                        ?.let { ProfileLink.probe(it) } == true
                                    testing = false
                                    testResult = answered to context.getString(
                                        if (answered) R.string.backup_profile_test_ok else R.string.backup_profile_test_failed,
                                    )
                                    return@launch
                                }
                                // SFTP records the server key here, into the
                                // draft, so Save keeps what this test saw.
                                val pinned = { pin: String ->
                                    draft = draft.copy(nas = draft.nas.copy(certPin = pin))
                                    certForgotten = false
                                }
                                val sink = when (current.type) {
                                    BackupDestination.SFTP -> SftpSink(current.sftp) { key ->
                                        draft = draft.copy(sftp = draft.sftp.copy(hostKey = key))
                                        keyForgotten = false
                                    }
                                    // As SFTP: the certificate this test saw goes into the draft.
                                    BackupDestination.SYNOLOGY -> SynologySink(current.nas, pinned)
                                    BackupDestination.QNAP -> QnapSink(current.nas, pinned)
                                    else -> AutoBackupRunner.sinkFor(context, current)
                                }
                                val failure = sink?.readiness()?.exceptionOrNull()
                                    ?: if (sink == null) BackupSinkException(SinkError.NOT_CONFIGURED) else null
                                testing = false
                                testResult = if (failure == null) {
                                    true to context.getString(R.string.backup_location_test_ok)
                                } else {
                                    val reason = (failure as? BackupSinkException)?.reason ?: SinkError.IO
                                    false to (
                                        autoBackupErrorText(context, reason.name, current.type)
                                            ?: context.getString(R.string.backup_auto_error_io)
                                        )
                                }
                            }
                        },
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.backup_location_testing))
                        } else {
                            Text(stringResource(R.string.backup_location_test))
                        }
                    }
                }
                testResult?.let { (ok, text) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        Icon(
                            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                TextButton(onClick = cancel) { Text(stringResource(CommonR.string.common_cancel)) }
                Button(onClick = { save(onDismiss) }) {
                    Text(stringResource(if (isNew) CommonR.string.common_add else CommonR.string.common_save))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Other profile (#1017): the code both copies of the keyboard hold, the
 * buttons that carry it from one profile to the other, whether the two are
 * linked now, and on Android 17 the adb grant without which they cannot reach
 * each other at all.
 */
@Composable
private fun ProfilePart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val context = LocalContext.current
    val code = ProfileCode.normalize(draft.profileCode)
    val linked by ProfileLink.linked.collectAsStateWithLifecycle()
    var pasteFailed by remember { mutableStateOf(false) }
    Text(
        stringResource(R.string.backup_profile_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    StoredTextField(
        label = stringResource(R.string.backup_profile_code_label),
        value = draft.profileCode,
        supporting = stringResource(
            when {
                pasteFailed -> R.string.backup_profile_paste_failed
                code == null -> R.string.backup_profile_code_invalid
                else -> R.string.backup_profile_code_hint
            },
        ),
        // Shown, but typed as a password: the keyboard does not learn the
        // code into its dictionary, nor offer it back as a suggestion.
        keyboardType = KeyboardType.Password,
    ) {
        pasteFailed = false
        onChange(draft.copy(profileCode = it))
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        FilledTonalButton(
            enabled = code != null,
            onClick = { code?.let { copyText(context, ProfileCode.format(it), sensitive = true) } },
        ) { Text(stringResource(R.string.backup_profile_copy)) }
        // Android shares the clipboard between profiles unless a work
        // policy forbids it, so a copy in one pastes in the other.
        OutlinedButton(onClick = {
            val pasted = pastedText(context)?.let(ProfileCode::normalize)
            pasteFailed = pasted == null
            if (pasted != null) onChange(draft.copy(profileCode = ProfileCode.format(pasted)))
        }) { Text(stringResource(R.string.backup_profile_paste)) }
    }
    if (code != null) {
        Text(
            stringResource(if (linked == code) R.string.backup_profile_linked else R.string.backup_profile_not_linked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    if (ProfileLink.separated(context)) {
        val command = ProfileLink.grantCommand(context)
        StateBanner(
            stringResource(R.string.backup_profile_separated, command),
            action = stringResource(R.string.backup_profile_copy_command),
            tone = BannerTone.WARNING,
        ) { copyText(context, command, sensitive = false) }
    }
}

/** Puts [text] on the clipboard; [sensitive] keeps it out of Android's preview of what was copied. */
private fun copyText(context: Context, text: String, sensitive: Boolean) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("", text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    manager.setPrimaryClip(clip)
}

/** The text on the clipboard, or null. */
private fun pastedText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()

/** The folder a Folder location writes to, and the button that picks it. */
@Composable
private fun FolderPart(draft: BackupLocation, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (taken) {
            PickerPlaces.remember(context, PickerPlaces.BACKUP_FOLDER, uri)
            onPicked(uri.toString())
        }
    }
    // Opens on this location's own folder, or else where the last backup
    // folder pick went, rather than wherever the app's last pick of any kind
    // did (#1131).
    val pick = {
        val here = draft.folderUri.takeIf { it.isNotEmpty() }?.let { PickerPlaces.folderOf(Uri.parse(it)) }
        launcher.launch(here ?: PickerPlaces.initial(context, PickerPlaces.BACKUP_FOLDER))
    }
    WmRow(
        title = stringResource(R.string.backup_auto_folder_title),
        subtitle = draft.detail(context) ?: stringResource(R.string.backup_auto_folder_none),
        icon = SettingsRowIcons[R.string.backup_auto_folder_title],
        onClick = pick,
    )
    FilledTonalButton(
        onClick = pick,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(
                if (draft.folderUri.isEmpty()) {
                    R.string.backup_location_folder_choose
                } else {
                    R.string.backup_location_folder_change
                },
            ),
        )
    }
}

/**
 * WebDAV: a service from the list, which turns a server name and a folder into
 * the address, or the whole address typed by hand. Nextcloud can also sign in
 * through the browser and hand back an app password.
 */
@Composable
private fun WebDavPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val preset = WebDavPreset.of(draft.webDavPreset)
    val other = stringResource(R.string.backup_webdav_preset_other)
    // Rebuilds the address from the parts whenever one of them changes.
    fun withParts(next: BackupLocation): BackupLocation {
        val p = WebDavPreset.of(next.webDavPreset)
        if (p == WebDavPreset.CUSTOM) return next
        return next.copy(webDavUrl = p.url(next.webDavServer, next.webDavUser, next.webDavFolder))
    }
    ChoiceSetting(
        title = R.string.backup_webdav_preset_title,
        options = WebDavPreset.entries.map { it to if (it == WebDavPreset.CUSTOM) other else it.label },
        selected = preset,
    ) { picked ->
        onChange(
            withParts(
                draft.copy(
                    webDavPreset = picked.id,
                    webDavFolder = draft.webDavFolder.ifEmpty { WebDavPreset.DEFAULT_FOLDER },
                ),
            ),
        )
    }
    if (preset == WebDavPreset.CUSTOM) {
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_url_label),
            value = draft.webDavUrl,
            supporting = stringResource(R.string.backup_auto_webdav_url_hint),
            keyboardType = KeyboardType.Uri,
        ) { onChange(draft.copy(webDavUrl = it)) }
    } else if (preset.needsServer) {
        StoredTextField(
            label = stringResource(
                when (preset.serverField) {
                    WebDavServerField.HOST -> R.string.backup_webdav_server_label
                    WebDavServerField.ACCOUNT_ID -> R.string.backup_webdav_server_id_label
                    WebDavServerField.TAILDRIVE_SHARE -> R.string.backup_webdav_taildrive_share_label
                },
            ),
            value = draft.webDavServer,
            supporting = stringResource(
                when (preset.serverField) {
                    WebDavServerField.HOST -> R.string.backup_webdav_server_hint
                    WebDavServerField.ACCOUNT_ID -> R.string.backup_webdav_server_id_hint
                    WebDavServerField.TAILDRIVE_SHARE -> R.string.backup_webdav_taildrive_share_hint
                },
            ),
            keyboardType = KeyboardType.Uri,
        ) { onChange(withParts(draft.copy(webDavServer = it))) }
    }
    if (preset == WebDavPreset.NEXTCLOUD) NextcloudSignIn(draft) { onChange(withParts(it)) }
    if (preset.signsIn) {
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_user_label),
            value = draft.webDavUser,
            supporting = stringResource(R.string.backup_auto_webdav_user_hint),
        ) { onChange(withParts(draft.copy(webDavUser = it))) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_password_label),
            value = draft.webDavPassword,
            supporting = stringResource(R.string.backup_auto_webdav_password_hint),
            password = true,
        ) { onChange(draft.copy(webDavPassword = it)) }
    }
    if (preset != WebDavPreset.CUSTOM) {
        StoredTextField(
            label = stringResource(R.string.backup_location_folder_label),
            value = draft.webDavFolder,
            supporting = stringResource(R.string.backup_location_folder_made_hint),
        ) { onChange(withParts(draft.copy(webDavFolder = it))) }
        if (draft.webDavUrl.isNotEmpty()) {
            Text(
                stringResource(R.string.backup_location_address, draft.webDavUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
    val https = draft.webDavUrl.trim().startsWith("https://", ignoreCase = true)
    if (draft.webDavUrl.isNotEmpty() && !https) {
        if (WebDavSink.isTailnet(draft.webDavUrl)) {
            StateBanner(stringResource(R.string.backup_webdav_tailnet_note), tone = BannerTone.INFO)
        } else {
            StateBanner(stringResource(R.string.backup_auto_webdav_needs_https), tone = BannerTone.WARNING)
        }
    }
}

/**
 * Nextcloud's browser sign-in: opens the server's own login page, waits for
 * the approval, and fills in the user name and a new app password.
 */
@Composable
private fun NextcloudSignIn(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        FilledTonalButton(
            enabled = !waiting && draft.webDavServer.isNotBlank(),
            onClick = {
                waiting = true
                failed = false
                scope.launch {
                    val started = NextcloudLogin.start(draft.webDavServer)
                    val opened = started?.takeIf {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(it.loginUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.isSuccess
                    }
                    val got = opened?.let { NextcloudLogin.await(it) }
                    waiting = false
                    if (got == null) {
                        failed = true
                    } else {
                        val host = got.server.removePrefix("https://").removePrefix("http://").trimEnd('/')
                        onChange(
                            draft.copy(
                                webDavServer = host.ifEmpty { draft.webDavServer },
                                webDavUser = got.loginName,
                                webDavPassword = got.appPassword,
                            ),
                        )
                    }
                }
            },
        ) { Text(stringResource(R.string.backup_webdav_nextcloud_sign_in)) }
        if (waiting) {
            Spacer(Modifier.size(12.dp))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.backup_webdav_nextcloud_waiting), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (failed) StateBanner(stringResource(R.string.backup_webdav_nextcloud_failed), tone = BannerTone.WARNING)
}

/** S3: a service from the list, which fills in the endpoint and addressing, or everything typed by hand. */
@Composable
private fun S3Part(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val s3 = draft.s3
    val preset = S3Preset.of(s3.preset)
    val other = stringResource(R.string.backup_s3_preset_other)
    fun apply(next: com.wasimaster.wmkeyboard.core.settings.S3Config) {
        val p = S3Preset.of(next.preset)
        val fixed = if (p == S3Preset.CUSTOM) next else next.copy(endpoint = p.endpoint(next.region, next.account), pathStyle = p.pathStyle)
        onChange(draft.copy(s3 = fixed))
    }
    ChoiceSetting(
        title = R.string.backup_s3_preset_title,
        options = S3Preset.entries.map { it to if (it == S3Preset.CUSTOM) other else it.label },
        selected = preset,
    ) { picked ->
        val region = if (picked == S3Preset.CUSTOM) s3.region else picked.defaultRegion
        apply(s3.copy(preset = picked.id, region = region))
    }
    if (preset == S3Preset.CUSTOM) {
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_endpoint_label),
            value = s3.endpoint,
            supporting = stringResource(R.string.backup_auto_s3_endpoint_hint),
            keyboardType = KeyboardType.Uri,
        ) { apply(s3.copy(endpoint = it)) }
    }
    if (preset.account != S3Account.NONE) {
        StoredTextField(
            label = stringResource(
                when (preset.account) {
                    S3Account.ACCOUNT_ID -> R.string.backup_s3_account_id_label
                    S3Account.ENDPOINT -> R.string.backup_s3_account_endpoint_label
                    else -> R.string.backup_s3_account_server_label
                },
            ),
            value = s3.account,
            supporting = stringResource(
                when (preset.account) {
                    S3Account.ACCOUNT_ID -> R.string.backup_s3_account_id_hint
                    S3Account.ENDPOINT -> R.string.backup_s3_account_endpoint_hint
                    else -> R.string.backup_s3_account_server_hint
                },
            ),
            keyboardType = KeyboardType.Uri,
        ) { apply(s3.copy(account = it)) }
    }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_bucket_label),
        value = s3.bucket,
        supporting = "",
    ) { apply(s3.copy(bucket = it)) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_region_label),
        value = s3.region,
        supporting = if (preset.regions.isEmpty()) {
            stringResource(R.string.backup_auto_s3_region_hint)
        } else {
            stringResource(R.string.backup_s3_region_examples, preset.regions.joinToString(", "))
        },
    ) { apply(s3.copy(region = it)) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_prefix_label),
        value = s3.prefix,
        supporting = stringResource(R.string.backup_auto_s3_prefix_hint),
    ) { apply(s3.copy(prefix = it)) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_key_label),
        value = s3.accessKeyId,
        supporting = "",
    ) { apply(s3.copy(accessKeyId = it)) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_secret_label),
        value = s3.secretAccessKey,
        supporting = "",
        password = true,
    ) { apply(s3.copy(secretAccessKey = it)) }
    if (preset == S3Preset.CUSTOM) {
        ToggleSetting(
            R.string.backup_auto_s3_path_style_title,
            stringResource(R.string.backup_auto_s3_path_style_subtitle),
            s3.pathStyle,
            default = SettingsDefaults.autoBackup.s3.pathStyle,
        ) { on -> apply(s3.copy(pathStyle = on)) }
    } else if (s3.endpoint.isNotEmpty()) {
        Text(
            stringResource(R.string.backup_s3_endpoint_is, s3.endpoint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    if (S3Sink.isCleartext(s3.endpoint)) {
        StateBanner(stringResource(R.string.backup_auto_s3_cleartext), tone = BannerTone.WARNING)
    }
}

@Composable
private fun FtpPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val ftp = draft.ftp
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_host_label),
        value = ftp.host,
        supporting = "",
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(ftp = ftp.copy(host = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_port_label),
        value = ftp.port.toString(),
        supporting = "",
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(ftp = ftp.copy(port = it))) } }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_user_label),
        value = ftp.user,
        supporting = "",
    ) { onChange(draft.copy(ftp = ftp.copy(user = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_password_label),
        value = ftp.password,
        supporting = "",
        password = true,
    ) { onChange(draft.copy(ftp = ftp.copy(password = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_path_label),
        value = ftp.path,
        supporting = stringResource(R.string.backup_auto_ftp_path_hint),
    ) { onChange(draft.copy(ftp = ftp.copy(path = it))) }
    ToggleSetting(
        R.string.backup_auto_ftp_secure_title,
        stringResource(R.string.backup_auto_ftp_secure_subtitle),
        ftp.secure,
        default = SettingsDefaults.autoBackup.ftp.secure,
    ) { on -> onChange(draft.copy(ftp = ftp.copy(secure = on))) }
    if (!ftp.secure) {
        StateBanner(stringResource(R.string.backup_auto_ftp_cleartext), tone = BannerTone.WARNING)
    }
}

/** SFTP: the server, a password or a key, and the server key the first connection saw. */
@Composable
private fun SftpPart(draft: BackupLocation, onForget: () -> Unit, onChange: (BackupLocation) -> Unit) {
    val sftp = draft.sftp
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_host_label),
        value = sftp.host,
        supporting = "",
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(sftp = sftp.copy(host = it.trim()))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_port_label),
        value = sftp.port.toString(),
        supporting = "",
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(sftp = sftp.copy(port = it))) } }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_user_label),
        value = sftp.user,
        supporting = "",
    ) { onChange(draft.copy(sftp = sftp.copy(user = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_password_label),
        value = sftp.password,
        supporting = stringResource(R.string.backup_sftp_password_hint),
        password = true,
    ) { onChange(draft.copy(sftp = sftp.copy(password = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_sftp_key_label),
        value = sftp.privateKey,
        supporting = stringResource(R.string.backup_sftp_key_hint),
        multiLine = true,
    ) { onChange(draft.copy(sftp = sftp.copy(privateKey = it))) }
    if (sftp.privateKey.isNotBlank()) {
        StoredTextField(
            label = stringResource(R.string.backup_sftp_key_passphrase_label),
            value = sftp.keyPassphrase,
            supporting = stringResource(R.string.backup_sftp_key_passphrase_hint),
            password = true,
        ) { onChange(draft.copy(sftp = sftp.copy(keyPassphrase = it))) }
    }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_path_label),
        value = sftp.path,
        supporting = stringResource(R.string.backup_sftp_path_hint),
    ) { onChange(draft.copy(sftp = sftp.copy(path = it))) }
    WmRow(
        title = stringResource(R.string.backup_sftp_host_key_title),
        subtitle = SftpSink.fingerprint(sftp.hostKey)?.let {
            stringResource(R.string.backup_sftp_host_key_value, sftp.hostKey.substringBefore(' '), it)
        } ?: stringResource(R.string.backup_sftp_host_key_none),
        icon = SettingsRowIcons[R.string.backup_sftp_host_key_title],
    )
    if (sftp.hostKey.isNotEmpty()) {
        OutlinedButton(onClick = onForget, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.backup_sftp_host_key_forget))
        }
    }
    ToggleSetting(
        R.string.backup_sftp_legacy_title,
        stringResource(R.string.backup_sftp_legacy_subtitle),
        sftp.legacyAlgorithms,
        default = false,
    ) { on -> onChange(draft.copy(sftp = sftp.copy(legacyAlgorithms = on))) }
}

/** SMB: the server, the share and a folder in it, the sign-in, and encryption. */
@Composable
private fun SmbPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val smb = draft.smb
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_host_label),
        value = smb.host,
        supporting = stringResource(R.string.backup_smb_host_hint),
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(smb = smb.copy(host = it.trim().trimStart('\\', '/')))) }
    StoredTextField(
        label = stringResource(R.string.backup_smb_share_label),
        value = smb.share,
        supporting = stringResource(R.string.backup_smb_share_hint),
    ) { onChange(draft.copy(smb = smb.copy(share = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_location_folder_label),
        value = smb.path,
        supporting = stringResource(R.string.backup_smb_path_hint),
    ) { onChange(draft.copy(smb = smb.copy(path = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_user_label),
        value = smb.user,
        supporting = "",
    ) { onChange(draft.copy(smb = smb.copy(user = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_password_label),
        value = smb.password,
        supporting = "",
        password = true,
    ) { onChange(draft.copy(smb = smb.copy(password = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_smb_domain_label),
        value = smb.domain,
        supporting = stringResource(R.string.backup_smb_domain_hint),
    ) { onChange(draft.copy(smb = smb.copy(domain = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_port_label),
        value = smb.port.toString(),
        supporting = "",
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(smb = smb.copy(port = it))) } }
    ToggleSetting(
        R.string.backup_smb_encrypt_title,
        stringResource(R.string.backup_smb_encrypt_subtitle),
        smb.encrypt,
        default = true,
    ) { on -> onChange(draft.copy(smb = smb.copy(encrypt = on))) }
    if (!smb.encrypt) StateBanner(stringResource(R.string.backup_smb_cleartext), tone = BannerTone.WARNING)
}

/**
 * Git: the host, the repository, where in it, the token, and how commits
 * look. A whole repository address pasted into the repository field is split
 * into server and repository on the spot.
 */
@Composable
private fun GitPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val git = draft.git
    ChoiceSetting(
        title = R.string.backup_git_provider_title,
        options = listOf(
            GitProvider.GITHUB to stringResource(R.string.backup_git_provider_github),
            GitProvider.GITLAB to stringResource(R.string.backup_git_provider_gitlab),
            GitProvider.GITEA to stringResource(R.string.backup_git_provider_gitea),
        ),
        selected = git.provider,
    ) { onChange(draft.copy(git = git.copy(provider = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_git_server_label),
        value = git.server,
        supporting = stringResource(R.string.backup_git_server_hint, git.provider.defaultServer.removePrefix("https://")),
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(git = git.copy(server = it.trim()))) }
    if (git.server.trim().startsWith("http://", ignoreCase = true)) {
        StateBanner(stringResource(R.string.backup_git_cleartext), tone = BannerTone.WARNING)
    }
    StoredTextField(
        label = stringResource(R.string.backup_git_repository_label),
        value = git.repository,
        supporting = stringResource(R.string.backup_git_repository_hint),
        keyboardType = KeyboardType.Uri,
    ) { typed ->
        val remote = GitSink.parseRemote(typed)
        onChange(
            if (remote == null) {
                draft.copy(git = git.copy(repository = typed.trim()))
            } else {
                val (server, repository) = remote
                val public = git.provider.defaultServer.equals(server, ignoreCase = true)
                draft.copy(
                    git = git.copy(
                        repository = repository,
                        server = if (public) "" else server,
                        provider = when {
                            server.contains("github", ignoreCase = true) -> GitProvider.GITHUB
                            server.contains("gitlab", ignoreCase = true) -> GitProvider.GITLAB
                            server.contains("codeberg", ignoreCase = true) -> GitProvider.GITEA
                            else -> git.provider
                        },
                    ),
                )
            },
        )
    }
    StoredTextField(
        label = stringResource(R.string.backup_git_token_label),
        value = git.token,
        supporting = stringResource(
            when (git.provider) {
                GitProvider.GITHUB -> R.string.backup_git_token_hint_github
                GitProvider.GITLAB -> R.string.backup_git_token_hint_gitlab
                GitProvider.GITEA -> R.string.backup_git_token_hint_gitea
            },
        ),
        password = true,
    ) { onChange(draft.copy(git = git.copy(token = it.trim()))) }
    StoredTextField(
        label = stringResource(R.string.backup_git_branch_label),
        value = git.branch,
        supporting = stringResource(R.string.backup_git_branch_hint),
    ) { onChange(draft.copy(git = git.copy(branch = it.trim()))) }
    StoredTextField(
        label = stringResource(R.string.backup_location_folder_label),
        value = git.path,
        supporting = stringResource(R.string.backup_git_path_hint),
    ) { onChange(draft.copy(git = git.copy(path = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_git_message_label),
        value = git.message,
        supporting = stringResource(R.string.backup_git_message_hint),
    ) { onChange(draft.copy(git = git.copy(message = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_git_author_name_label),
        value = git.authorName,
        supporting = "",
    ) { onChange(draft.copy(git = git.copy(authorName = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_git_author_email_label),
        value = git.authorEmail,
        supporting = stringResource(R.string.backup_git_author_hint),
        keyboardType = KeyboardType.Email,
    ) { onChange(draft.copy(git = git.copy(authorEmail = it.trim()))) }
    ToggleSetting(
        R.string.backup_git_skip_ci_title,
        stringResource(R.string.backup_git_skip_ci_subtitle),
        git.skipCi,
        default = true,
    ) { on -> onChange(draft.copy(git = git.copy(skipCi = on))) }
    ToggleSetting(
        R.string.backup_git_allow_public_title,
        stringResource(R.string.backup_git_allow_public_subtitle),
        git.allowPublic,
        default = false,
    ) { on -> onChange(draft.copy(git = git.copy(allowPublic = on))) }
    StateBanner(
        stringResource(if (git.allowPublic) R.string.backup_git_public_warning else R.string.backup_git_info),
        tone = if (git.allowPublic) BannerTone.WARNING else BannerTone.INFO,
    )
}

/** IMAP: the mail server, how it is protected, the login, and the folder. */
@Composable
private fun ImapPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val imap = draft.imap
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_host_label),
        value = imap.host,
        supporting = stringResource(R.string.backup_imap_host_hint),
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(imap = imap.copy(host = it.trim()))) }
    ChoiceSetting(
        title = R.string.backup_imap_security_title,
        options = listOf(
            ImapSecurity.TLS to stringResource(R.string.backup_imap_security_tls),
            ImapSecurity.STARTTLS to stringResource(R.string.backup_imap_security_starttls),
            ImapSecurity.NONE to stringResource(R.string.backup_imap_security_none),
        ),
        selected = imap.security,
        default = ImapSecurity.TLS,
    ) { picked ->
        // A port still at the old mode's default follows the new mode.
        val port = if (imap.port == imap.security.defaultPort) picked.defaultPort else imap.port
        onChange(draft.copy(imap = imap.copy(security = picked, port = port)))
    }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_port_label),
        value = imap.port.toString(),
        supporting = "",
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(imap = imap.copy(port = it))) } }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_user_label),
        value = imap.user,
        supporting = "",
        keyboardType = KeyboardType.Email,
    ) { onChange(draft.copy(imap = imap.copy(user = it.trim()))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_password_label),
        value = imap.password,
        supporting = stringResource(R.string.backup_imap_password_hint),
        password = true,
    ) { onChange(draft.copy(imap = imap.copy(password = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_imap_mailbox_label),
        value = imap.mailbox,
        supporting = stringResource(R.string.backup_imap_mailbox_hint),
    ) { onChange(draft.copy(imap = imap.copy(mailbox = it))) }
    if (imap.security == ImapSecurity.NONE) {
        StateBanner(stringResource(R.string.backup_imap_cleartext), tone = BannerTone.WARNING)
    }
    StateBanner(stringResource(R.string.backup_imap_info))
}

/**
 * Dropbox, OneDrive or Box: who is signed in, a progress bar while the browser's
 * code is traded for a token, and the button.
 */
@Composable
internal fun SignInPart(repository: SettingsRepository, location: BackupLocation, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exchanging by BackupOAuth.exchanging.collectAsStateWithLifecycle()
    val signingIn = exchanging == location.id
    val clientId = when (location.type) {
        BackupDestination.DROPBOX -> BackupClients.dropboxClientId
        BackupDestination.BOX -> BackupClients.boxClientId
        else -> BackupClients.oneDriveClientId
    }

    WmRow(
        title = stringResource(
            when {
                signingIn -> R.string.backup_auto_oauth_signing_in
                location.refreshToken.isNotEmpty() -> R.string.backup_auto_oauth_signed_in
                else -> R.string.backup_auto_oauth_signed_out
            },
        ),
        icon = SettingsRowIcons[R.string.backup_auto_dest_dropbox],
    )
    if (signingIn) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    FilledTonalButton(
        enabled = !signingIn,
        onClick = {
            val activity = context.hostActivity() ?: return@FilledTonalButton
            if (location.refreshToken.isNotEmpty()) {
                scope.launch { repository.upsertBackupLocation(location.copy(refreshToken = "")) }
            } else if (!BackupOAuth.start(activity, location.type, clientId, location.id)) {
                onMessage(context.getString(R.string.backup_auto_oauth_no_browser))
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            stringResource(
                if (location.refreshToken.isNotEmpty()) {
                    R.string.backup_auto_oauth_sign_out
                } else {
                    R.string.backup_auto_oauth_sign_in
                },
            ),
        )
    }
    StateBanner(
        stringResource(
            when (location.type) {
                BackupDestination.DROPBOX -> R.string.backup_auto_dropbox_info
                BackupDestination.BOX -> R.string.backup_box_info
                else -> R.string.backup_auto_onedrive_info
            },
        ),
    )
}

/**
 * Google Drive: which part of it, the folder when it is a visible one, whether
 * the app may use that part, and the button that asks.
 */
@Composable
private fun DrivePart(draft: BackupLocation, onMessage: (String) -> Unit, onChange: (BackupLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = remember { driveAuthorizer() }
    val driveScope = DriveSink.scopeFor(draft.driveSpace)
    var authorized by remember(driveScope) { mutableStateOf<Boolean?>(null) }
    var asking by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // Ask Google again: the result code alone says nothing reliable. No
        // grant after the consent screen closed is worth a sentence; see
        // BackupScreens history for the account picker that did nothing.
        scope.launch {
            val granted = authorizer.authorized(context, driveScope)
            authorized = granted
            if (!granted) onMessage(context.getString(R.string.backup_auto_oauth_failed))
        }
    }
    LaunchedEffect(driveScope) { authorized = authorizer.authorized(context, driveScope) }

    ChoiceSetting(
        title = R.string.backup_drive_space_title,
        options = listOf(
            DriveSpace.APP_DATA to stringResource(R.string.backup_drive_space_hidden),
            DriveSpace.FOLDER to stringResource(R.string.backup_drive_space_folder),
        ),
        selected = draft.driveSpace,
        default = DriveSpace.APP_DATA,
    ) { onChange(draft.copy(driveSpace = it)) }
    if (draft.driveSpace == DriveSpace.FOLDER) {
        StoredTextField(
            label = stringResource(R.string.backup_drive_folder_label),
            value = draft.driveFolder,
            supporting = stringResource(R.string.backup_drive_folder_hint),
        ) { onChange(draft.copy(driveFolder = it)) }
    }
    WmRow(
        title = stringResource(
            when (authorized) {
                true -> R.string.backup_auto_drive_authorized
                false -> R.string.backup_auto_drive_not_authorized
                null -> R.string.backup_auto_drive_checking
            },
        ),
        icon = SettingsRowIcons[R.string.backup_auto_drive_title],
    )
    if (authorized != true) {
        FilledTonalButton(
            enabled = !asking,
            onClick = {
                val activity = context.hostActivity() ?: return@FilledTonalButton
                asking = true
                scope.launch {
                    val granted = authorizer.authorize(activity, driveScope) { sender ->
                        consent.launch(IntentSenderRequest.Builder(sender).build())
                    }
                    asking = false
                    if (granted) authorized = true
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.backup_auto_drive_authorize)) }
    }
    StateBanner(
        stringResource(
            if (draft.driveSpace == DriveSpace.FOLDER) {
                R.string.backup_drive_folder_info
            } else {
                R.string.backup_auto_drive_info
            },
        ),
    )
}

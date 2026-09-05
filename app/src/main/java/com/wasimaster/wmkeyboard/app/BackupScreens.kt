package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.text.format.DateUtils
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.wasimaster.wmkeyboard.app.drive.driveAuthorizer
import com.wasimaster.wmkeyboard.app.oauth.BackupOAuth
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsBackup
import com.wasimaster.wmkeyboard.core.settings.AutoBackupIntervals
import com.wasimaster.wmkeyboard.core.settings.AutoBackupKeepRange
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.FtpConfig
import com.wasimaster.wmkeyboard.core.settings.S3Config
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.destinationConfigured
import com.wasimaster.wmkeyboard.core.settings.needsNetwork
import com.wasimaster.wmkeyboard.core.settings.sectionSet
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import kotlinx.coroutines.launch

// ---- backup ----

/** Human name for a bundle section, used in toggles and the import dialog. */
@StringRes
internal fun sectionLabelRes(section: ConfigBackup.Section): Int = when (section) {
    ConfigBackup.Section.SETTINGS -> R.string.backup_section_settings_label
    ConfigBackup.Section.THEMES -> R.string.backup_section_themes_label
    ConfigBackup.Section.DICTIONARY -> R.string.backup_section_dictionary_label
    ConfigBackup.Section.CLIPBOARD -> R.string.backup_section_clipboard_label
    ConfigBackup.Section.SNIPPETS -> R.string.backup_section_snippets_label
    ConfigBackup.Section.STICKERS -> R.string.backup_section_stickers_label
    ConfigBackup.Section.ICONS -> R.string.backup_section_icons_label
    ConfigBackup.Section.WORDLISTS -> R.string.backup_section_wordlists_label
    ConfigBackup.Section.ADDONS -> R.string.backup_section_addons_label
    ConfigBackup.Section.EMOJI -> R.string.backup_section_emoji_label
    ConfigBackup.Section.STATISTICS -> R.string.backup_section_statistics_label
}
internal fun sectionLabel(context: Context, section: ConfigBackup.Section): String =
    context.getString(sectionLabelRes(section))
/**
 * The same name for the middle of a sentence ("Restored themes, snippets.").
 * A translation cannot be lowercased in code, so each name carries its own
 * lower-case value.
 */
internal fun sectionLabelLowercase(context: Context, section: ConfigBackup.Section): String =
    context.getString(
        when (section) {
            ConfigBackup.Section.SETTINGS -> R.string.backup_section_settings_label_lowercase
            ConfigBackup.Section.THEMES -> R.string.backup_section_themes_label_lowercase
            ConfigBackup.Section.DICTIONARY -> R.string.backup_section_dictionary_label_lowercase
            ConfigBackup.Section.CLIPBOARD -> R.string.backup_section_clipboard_label_lowercase
            ConfigBackup.Section.SNIPPETS -> R.string.backup_section_snippets_label_lowercase
            ConfigBackup.Section.STICKERS -> R.string.backup_section_stickers_label_lowercase
            ConfigBackup.Section.ICONS -> R.string.backup_section_icons_label_lowercase
            ConfigBackup.Section.WORDLISTS -> R.string.backup_section_wordlists_label_lowercase
            ConfigBackup.Section.ADDONS -> R.string.backup_section_addons_label_lowercase
            ConfigBackup.Section.EMOJI -> R.string.backup_section_emoji_label_lowercase
            ConfigBackup.Section.STATISTICS -> R.string.backup_section_statistics_label_lowercase
        },
    )
@PluralsRes
private fun sectionCountPlural(section: ConfigBackup.Section): Int = when (section) {
    ConfigBackup.Section.SETTINGS -> R.plurals.backup_section_settings_count
    ConfigBackup.Section.THEMES -> R.plurals.backup_section_themes_count
    ConfigBackup.Section.DICTIONARY -> R.plurals.backup_section_dictionary_count
    ConfigBackup.Section.CLIPBOARD -> R.plurals.backup_section_clipboard_count
    ConfigBackup.Section.SNIPPETS -> R.plurals.backup_section_snippets_count
    ConfigBackup.Section.STICKERS -> R.plurals.backup_section_stickers_count
    ConfigBackup.Section.ICONS -> R.plurals.backup_section_icons_count
    ConfigBackup.Section.WORDLISTS -> R.plurals.backup_section_wordlists_count
    ConfigBackup.Section.ADDONS -> R.plurals.backup_section_addons_count
    ConfigBackup.Section.EMOJI -> R.plurals.backup_section_emoji_count
    ConfigBackup.Section.STATISTICS -> R.plurals.backup_section_statistics_count
}
/** "3 themes", "1 snippet": the count line shown per section on import. */
internal fun sectionSummary(context: Context, section: ConfigBackup.Section, count: Int): String =
    context.resources.getQuantityString(sectionCountPlural(section), count, count)

/** A file picked for import, once we know which of the two formats it is. */
private sealed interface PendingImport {
    val text: String
    data class Config(override val text: String) : PendingImport
    data class Legacy(override val text: String) : PendingImport
}
/**
 * Where [hours] sits on [AutoBackupIntervals], for the slider's thumb.
 *
 * Nearest rather than exact: a value stored before the ladder existed, or by a
 * restored backup from a build with a different one, still has to put the thumb
 * somewhere sensible instead of snapping to the first stop.
 */
private fun intervalSliderIndex(hours: Int): Int =
    AutoBackupIntervals.indices.minBy { kotlin.math.abs(AutoBackupIntervals[it] - hours) }
/** The ladder value under a slider position. */
private fun intervalAt(index: Float): Int =
    AutoBackupIntervals[index.roundToInt().coerceIn(AutoBackupIntervals.indices)]
/** "Every 6 hours", "Every day", "Every 3 days". */
private fun backupIntervalLabel(context: Context, hours: Int): String =
    if (hours % 24 == 0) {
        context.resources.getQuantityString(
            R.plurals.backup_auto_interval_days,
            hours / 24,
            hours / 24,
        )
    } else {
        context.resources.getQuantityString(R.plurals.backup_auto_interval_hours, hours, hours)
    }
/** One sentence for a recorded failure, or null when the last run was fine. */
private fun autoBackupErrorText(context: Context, error: String): String? = when (error) {
    "" -> null
    SinkError.PERMISSION_LOST.name -> context.getString(R.string.backup_auto_error_permission)
    SinkError.TARGET_MISSING.name -> context.getString(R.string.backup_auto_error_target)
    SinkError.OUT_OF_SPACE.name -> context.getString(R.string.backup_auto_error_space)
    else -> context.getString(R.string.backup_auto_error_io)
}
/**
 * A text field backed by the settings store, for the handful of backup values
 * that are typed rather than picked.
 *
 * Same shape and same reason as the layout editor's `SheetField`: the value is
 * read back out of the repository a frame or more after the keystroke that
 * caused it, and fed straight back in it rewinds the text and the cursor
 * mid-word. The text lives here, and an incoming value is taken only while
 * nothing of ours is in flight.
 *
 * [password] masks the text and adds the reveal button. It also sets the field
 * to a password type, which matters more here than it usually would: this is
 * the keyboard, and an ordinary field would learn the passphrase into the very
 * dictionary the backup is about to carry.
 */
@Composable
private fun StoredTextField(
    label: String,
    value: String,
    supporting: String,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    var pending by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    when {
        pending == null -> if (value != text) text = value
        value == pending -> pending = null
    }
    val masked = password && !visible
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            pending = it
            onChange(it)
        },
        label = { Text(label) },
        supportingText = if (supporting.isEmpty()) null else ({ Text(supporting) }),
        singleLine = true,
        visualTransformation =
        if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
        ),
        trailingIcon = if (!password) {
            null
        } else {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (visible) {
                                R.string.backup_auto_passphrase_hide
                            } else {
                                R.string.backup_auto_passphrase_show
                            },
                        ),
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The activity a composable is drawn in, by unwrapping the context.
 *
 * `:core:common` has one of these, and it is `internal` there, so it stops at
 * that module's boundary. Needed here for the one thing on this screen that has
 * to launch a system consent screen.
 */
private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}
/**
 * Which of the three destinations backups go to.
 *
 * Google Drive is left out of the list entirely on a build with no Play
 * services rather than shown and refused: on F-Droid there is nothing behind it
 * at all, and a choice that cannot be chosen is worse than no choice.
 */
@Composable
private fun DestinationRow(
    selected: BackupDestination,
    onChange: (BackupDestination) -> Unit,
) {
    val options = buildList {
        add(BackupDestination.FOLDER to stringResource(R.string.backup_auto_dest_folder))
        add(BackupDestination.WEBDAV to stringResource(R.string.backup_auto_dest_webdav))
        add(BackupDestination.S3 to stringResource(R.string.backup_auto_dest_s3))
        add(BackupDestination.FTP to stringResource(R.string.backup_auto_dest_ftp))
        if (driveAuthorizer().available) {
            add(BackupDestination.DRIVE to stringResource(R.string.backup_auto_dest_drive))
        }
        // Left out rather than shown and refused when this build has no client
        // id compiled in: there is nothing behind them, and a choice that
        // cannot be chosen is worse than no choice.
        if (BackupClients.dropboxAvailable) {
            add(BackupDestination.DROPBOX to stringResource(R.string.backup_auto_dest_dropbox))
        }
        if (BackupClients.oneDriveAvailable) {
            add(BackupDestination.ONEDRIVE to stringResource(R.string.backup_auto_dest_onedrive))
        }
    }
    ChoiceSetting(
        R.string.backup_auto_dest_title,
        subtitle = stringResource(R.string.backup_auto_dest_subtitle),
        options = options,
        selected = selected,
        default = SettingsDefaults.autoBackup.destination,
        onChange = onChange,
    )
}
/** Server, user and password for a WebDAV collection. */
@Composable
private fun WebDavRows(repository: SettingsRepository, auto: AutoBackupSettings) {
    val scope = rememberCoroutineScope()
    Column {
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_url_label),
            value = auto.webDavUrl,
            supporting = stringResource(R.string.backup_auto_webdav_url_hint),
            keyboardType = KeyboardType.Uri,
        ) { entered -> scope.launch { repository.setAutoBackupWebDavUrl(entered) } }
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_user_label),
            value = auto.webDavUser,
            supporting = "",
        ) { entered -> scope.launch { repository.setAutoBackupWebDavUser(entered) } }
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_password_label),
            value = auto.webDavPassword,
            supporting = stringResource(R.string.backup_auto_webdav_password_hint),
            password = true,
        ) { entered -> scope.launch { repository.setAutoBackupWebDavPassword(entered) } }
        if (auto.webDavUrl.isNotEmpty() &&
            !auto.webDavUrl.startsWith("https://", ignoreCase = true)
        ) {
            // The credentials go in an Authorization header, which is the
            // password in base64. Over plain HTTP that is the password in the
            // clear, so the sink refuses it and this says why before the user
            // waits for a failed backup to find out.
            StateBanner(stringResource(R.string.backup_auto_webdav_needs_https), tone = BannerTone.WARNING)
        }
    }
}
/** Endpoint, bucket and key pair for an S3-compatible service. */
@Composable
private fun S3Rows(repository: SettingsRepository, auto: AutoBackupSettings) {
    val scope = rememberCoroutineScope()
    val s3 = auto.s3
    fun update(change: S3Config) = scope.launch { repository.setAutoBackupS3(change) }

    Column {
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_endpoint_label),
            value = s3.endpoint,
            supporting = stringResource(R.string.backup_auto_s3_endpoint_hint),
            keyboardType = KeyboardType.Uri,
        ) { update(s3.copy(endpoint = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_bucket_label),
            value = s3.bucket,
            supporting = "",
        ) { update(s3.copy(bucket = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_region_label),
            value = s3.region,
            supporting = stringResource(R.string.backup_auto_s3_region_hint),
        ) { update(s3.copy(region = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_prefix_label),
            value = s3.prefix,
            supporting = stringResource(R.string.backup_auto_s3_prefix_hint),
        ) { update(s3.copy(prefix = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_key_label),
            value = s3.accessKeyId,
            supporting = "",
        ) { update(s3.copy(accessKeyId = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_secret_label),
            value = s3.secretAccessKey,
            supporting = "",
            password = true,
        ) { update(s3.copy(secretAccessKey = it)) }
        ToggleSetting(
            R.string.backup_auto_s3_path_style_title,
            stringResource(R.string.backup_auto_s3_path_style_subtitle),
            s3.pathStyle,
            default = SettingsDefaults.autoBackup.s3.pathStyle,
        ) { on -> update(s3.copy(pathStyle = on)) }
        if (S3Sink.isCleartext(s3.endpoint)) {
            StateBanner(stringResource(R.string.backup_auto_s3_cleartext), tone = BannerTone.WARNING)
        }
    }
}
/** Host, login and directory for an FTP server. */
@Composable
private fun FtpRows(repository: SettingsRepository, auto: AutoBackupSettings) {
    val scope = rememberCoroutineScope()
    val ftp = auto.ftp
    fun update(change: FtpConfig) = scope.launch { repository.setAutoBackupFtp(change) }

    Column {
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_host_label),
            value = ftp.host,
            supporting = "",
            keyboardType = KeyboardType.Uri,
        ) { update(ftp.copy(host = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_port_label),
            value = ftp.port.toString(),
            supporting = "",
            keyboardType = KeyboardType.Number,
        ) { entered -> entered.toIntOrNull()?.let { update(ftp.copy(port = it)) } }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_user_label),
            value = ftp.user,
            supporting = "",
        ) { update(ftp.copy(user = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_password_label),
            value = ftp.password,
            supporting = "",
            password = true,
        ) { update(ftp.copy(password = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_path_label),
            value = ftp.path,
            supporting = stringResource(R.string.backup_auto_ftp_path_hint),
        ) { update(ftp.copy(path = it)) }
        ToggleSetting(
            R.string.backup_auto_ftp_secure_title,
            stringResource(R.string.backup_auto_ftp_secure_subtitle),
            ftp.secure,
            default = SettingsDefaults.autoBackup.ftp.secure,
        ) { on -> update(ftp.copy(secure = on)) }
        if (!ftp.secure) {
            StateBanner(stringResource(R.string.backup_auto_ftp_cleartext), tone = BannerTone.WARNING)
        }
    }
}
/** Signing in to Dropbox or OneDrive, which is the same flow for both. */
@Composable
private fun OAuthRow(
    repository: SettingsRepository,
    destination: BackupDestination,
    token: String,
    clientId: String,
    @StringRes titleRes: Int,
    @StringRes infoRes: Int,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pending by BackupOAuth.result.collectAsStateWithLifecycle()

    // The browser comes back into a different activity, so the code arrives
    // here through a flow rather than an activity result.
    LaunchedEffect(pending) {
        val delivered = pending ?: return@LaunchedEffect
        if (delivered.destination != destination) return@LaunchedEffect
        BackupOAuth.consume()
        val code = delivered.code
        if (code == null) {
            onMessage(context.getString(R.string.backup_auto_oauth_cancelled))
            return@LaunchedEffect
        }
        val tokens = when (destination) {
            BackupDestination.DROPBOX -> BackupClients.dropbox()
            else -> BackupClients.oneDrive()
        }
        val refresh = withContext(Dispatchers.IO) {
            tokens?.exchangeCode(code, delivered.verifier, BackupOAuth.REDIRECT_URI)
        }
        if (refresh == null) {
            onMessage(context.getString(R.string.backup_auto_oauth_failed))
            return@LaunchedEffect
        }
        when (destination) {
            BackupDestination.DROPBOX -> repository.setAutoBackupDropboxToken(refresh)
            else -> repository.setAutoBackupOneDriveToken(refresh)
        }
    }

    Column {
        ListItem(
            headlineContent = { Text(stringResource(titleRes)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (token.isNotEmpty()) {
                            R.string.backup_auto_oauth_signed_in
                        } else {
                            R.string.backup_auto_oauth_signed_out
                        },
                    ),
                )
            },
        )
        OutlinedButton(
            onClick = {
                val activity = context.hostActivity() ?: return@OutlinedButton
                if (token.isNotEmpty()) {
                    scope.launch {
                        when (destination) {
                            BackupDestination.DROPBOX -> repository.setAutoBackupDropboxToken("")
                            else -> repository.setAutoBackupOneDriveToken("")
                        }
                    }
                } else if (!BackupOAuth.start(activity, destination, clientId)) {
                    onMessage(context.getString(R.string.backup_auto_oauth_no_browser))
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(
                    if (token.isNotEmpty()) {
                        R.string.backup_auto_oauth_sign_out
                    } else {
                        R.string.backup_auto_oauth_sign_in
                    },
                ),
            )
        }
        StateBanner(stringResource(infoRes))
    }
}
/** Authorizing this app's own hidden folder in the user's Google Drive. */
@Composable
private fun DriveRow(
    repository: SettingsRepository,
    auto: AutoBackupSettings,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val authorizer = remember { driveAuthorizer() }
    var authorized by remember { mutableStateOf<Boolean?>(null) }
    var asking by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        // Whatever the result says, the only trustworthy answer is to ask
        // Google again.
        scope.launch { authorized = authorizer.authorized(context) }
    }

    LaunchedEffect(auto.destination) {
        authorized = authorizer.authorized(context)
    }

    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_auto_drive_title)) },
            supportingContent = {
                Text(
                    stringResource(
                        when (authorized) {
                            true -> R.string.backup_auto_drive_authorized
                            false -> R.string.backup_auto_drive_not_authorized
                            null -> R.string.backup_auto_drive_checking
                        },
                    ),
                )
            },
        )
        if (authorized != true) {
            OutlinedButton(
                enabled = !asking,
                onClick = {
                    val activity = context.hostActivity() ?: return@OutlinedButton
                    asking = true
                    scope.launch {
                        val granted = authorizer.authorize(activity) { sender ->
                            consent.launch(IntentSenderRequest.Builder(sender).build())
                        }
                        asking = false
                        authorized = granted
                        if (granted) {
                            repository.setAutoBackupOutcome(ranAtMs = 0L, error = "")
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.backup_auto_drive_authorize)) }
        }
        StateBanner(stringResource(R.string.backup_auto_drive_info))
    }
}
/**
 * The backup that runs without being asked.
 *
 * Every row here is inert until the chosen destination is usable, because there
 * is no default destination that would not be a place the user did not ask for.
 */
@Composable
private fun AutoBackupGroup(
    repository: SettingsRepository,
    auto: AutoBackupSettings,
    onPickFolder: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    val configured = auto.destinationConfigured
    val encrypted = auto.encrypt && auto.passphrase.isNotEmpty()
    val personal = ConfigBackup.Section.DICTIONARY.id in auto.sections ||
        ConfigBackup.Section.CLIPBOARD.id in auto.sections

    SettingsGroup(stringResource(R.string.backup_auto_group_title)) {
        item {
            DestinationRow(auto.destination) { picked ->
                scope.launch {
                    repository.setAutoBackupDestination(picked)
                    AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
                }
            }
        }
        when (auto.destination) {
            BackupDestination.FOLDER -> item {
                NavRow(
                    R.string.backup_auto_folder_title,
                    subtitle = stringResource(R.string.backup_auto_folder_subtitle),
                    value = if (auto.folderUri.isNotEmpty()) {
                        Uri.decode(auto.folderUri.substringAfterLast('/'))
                    } else {
                        stringResource(R.string.backup_auto_folder_none)
                    },
                    onClick = onPickFolder,
                )
            }
            BackupDestination.WEBDAV -> {
                item { WebDavRows(repository, auto) }
            }
            BackupDestination.DRIVE -> {
                item { DriveRow(repository, auto, onMessage) }
            }
            BackupDestination.S3 -> item { S3Rows(repository, auto) }
            BackupDestination.FTP -> item { FtpRows(repository, auto) }
            BackupDestination.DROPBOX -> item {
                OAuthRow(
                    repository = repository,
                    destination = BackupDestination.DROPBOX,
                    token = auto.dropboxRefreshToken,
                    clientId = BackupClients.dropboxClientId,
                    titleRes = R.string.backup_auto_dest_dropbox,
                    infoRes = R.string.backup_auto_dropbox_info,
                    onMessage = onMessage,
                )
            }
            BackupDestination.ONEDRIVE -> item {
                OAuthRow(
                    repository = repository,
                    destination = BackupDestination.ONEDRIVE,
                    token = auto.oneDriveRefreshToken,
                    clientId = BackupClients.oneDriveClientId,
                    titleRes = R.string.backup_auto_dest_onedrive,
                    infoRes = R.string.backup_auto_onedrive_info,
                    onMessage = onMessage,
                )
            }
        }
        item {
            ToggleSetting(
                R.string.backup_auto_enabled_title,
                stringResource(
                    if (configured) {
                        R.string.backup_auto_enabled_subtitle
                    } else {
                        R.string.backup_auto_enabled_needs_folder
                    },
                ),
                auto.enabled && configured,
                default = SettingsDefaults.autoBackup.enabled && configured,
            ) { on ->
                scope.launch {
                    repository.setAutoBackupEnabled(on)
                    AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
                }
            }
        }
        if (auto.enabled && configured) {
            item {
                // The slider walks the ladder by index, so every stop is a value
                // somebody would choose and one a drag can actually land on. A
                // plain 1..168 range makes "once a day" a pixel-hunt.
                SliderSetting(
                    R.string.backup_auto_interval_title,
                    value = intervalSliderIndex(auto.intervalHours).toFloat(),
                    range = 0f..(AutoBackupIntervals.size - 1).toFloat(),
                    display = { backupIntervalLabel(context, intervalAt(it)) },
                    default = intervalSliderIndex(
                        SettingsDefaults.autoBackup.intervalHours,
                    ).toFloat(),
                ) { index ->
                    scope.launch {
                        repository.setAutoBackupIntervalHours(intervalAt(index))
                        AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
                    }
                }
            }
            item {
                SliderSetting(
                    R.string.backup_auto_keep_title,
                    subtitle = stringResource(R.string.backup_auto_keep_subtitle),
                    value = auto.keep.toFloat(),
                    range = AutoBackupKeepRange.first.toFloat()..
                        AutoBackupKeepRange.last.toFloat(),
                    display = { kept ->
                        context.resources.getQuantityString(
                            R.plurals.backup_auto_keep_value,
                            kept.roundToInt(),
                            kept.roundToInt(),
                        )
                    },
                    default = SettingsDefaults.autoBackup.keep.toFloat(),
                ) { kept -> scope.launch { repository.setAutoBackupKeep(kept.roundToInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.backup_auto_charging_title,
                    stringResource(R.string.backup_auto_charging_subtitle),
                    auto.requireCharging,
                    info = stringResource(R.string.backup_auto_charging_info),
                    default = SettingsDefaults.autoBackup.requireCharging,
                ) { on ->
                    scope.launch {
                        repository.setAutoBackupRequireCharging(on)
                        AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
                    }
                }
            }
            // A folder is storage on this device, so a network requirement there
            // would only ever stop a backup that costs nothing.
            if (auto.destination.needsNetwork) {
                item {
                    ToggleSetting(
                        R.string.backup_auto_unmetered_title,
                        stringResource(R.string.backup_auto_unmetered_subtitle),
                        auto.requireUnmetered,
                        info = stringResource(R.string.backup_auto_unmetered_info),
                        default = SettingsDefaults.autoBackup.requireUnmetered,
                    ) { on ->
                        scope.launch {
                            repository.setAutoBackupRequireUnmetered(on)
                            AutoBackupScheduler.sync(
                                context,
                                repository.settings.first().autoBackup,
                            )
                        }
                    }
                }
            }
        }
        item {
            ToggleSetting(
                R.string.backup_auto_encrypt_title,
                stringResource(R.string.backup_auto_encrypt_subtitle),
                auto.encrypt,
                info = stringResource(R.string.backup_auto_encrypt_info),
                default = SettingsDefaults.autoBackup.encrypt,
            ) { on -> scope.launch { repository.setAutoBackupEncrypt(on) } }
        }
        if (auto.encrypt) {
            item {
                StoredTextField(
                    label = stringResource(R.string.backup_auto_passphrase_label),
                    value = auto.passphrase,
                    supporting = "",
                    password = true,
                ) { entered -> scope.launch { repository.setAutoBackupPassphrase(entered) } }
            }
        }
    }

    // The one thing on this screen that has to be said out loud. Turning these
    // sections on for an automatic backup sends words the user typed, and things
    // they copied, off the device on a timer.
    if (personal && !encrypted) {
        StateBanner(stringResource(R.string.backup_auto_personal_warning), tone = BannerTone.WARNING)
    }

    SettingsGroup {
        item {
            OutlinedButton(
                enabled = configured && !running,
                onClick = {
                    running = true
                    scope.launch {
                        val outcome = AutoBackupRunner.run(context, repository, force = true)
                        running = false
                        onMessage(autoBackupOutcomeText(context, outcome))
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(
                        if (running) R.string.backup_auto_running else R.string.backup_auto_now_action,
                    ),
                )
            }
        }
    }

    val error = autoBackupErrorText(context, auto.lastError)
    StateBanner(
        when {
            error != null -> error
            auto.lastRunAtMs > 0L -> stringResource(
                R.string.backup_auto_last_run,
                DateUtils.getRelativeTimeSpanString(auto.lastRunAtMs).toString(),
            )
            else -> stringResource(R.string.backup_auto_never)
        },
        tone = if (error != null) BannerTone.WARNING else BannerTone.INFO,
    )
}
/** One sentence for whatever a run turned out to be. */
private fun autoBackupOutcomeText(
    context: Context,
    outcome: AutoBackupRunner.Outcome,
): String = when (outcome) {
    is AutoBackupRunner.Outcome.Done -> if (outcome.skipped.isEmpty()) {
        context.getString(R.string.backup_auto_done, outcome.name)
    } else {
        context.getString(
            R.string.backup_auto_done_skipped,
            outcome.name,
            outcome.skipped.joinToString { sectionLabelLowercase(context, it) },
        )
    }
    AutoBackupRunner.Outcome.Locked -> context.getString(R.string.backup_auto_locked)
    AutoBackupRunner.Outcome.Skipped -> context.getString(R.string.backup_auto_skipped)
    is AutoBackupRunner.Outcome.Failed ->
        autoBackupErrorText(context, outcome.reason.name)
            ?: context.getString(R.string.backup_auto_error_io)
}
@Composable
internal fun BackupSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // What a backup contains, stored rather than remembered. It used to be
    // eleven `remember`s that reset on every visit, which was tolerable while
    // the only way to make a backup was to press a button and pick a file. The
    // automatic backup has nobody there to set them, so they have to persist.
    val auto = settings.autoBackup
    val sections = auto.sectionSet
    val includeSecrets = auto.includeSecrets

    var message by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf<PendingImport?>(null) }

    fun setSection(section: ConfigBackup.Section, on: Boolean) {
        scope.launch {
            repository.setAutoBackupSections(
                if (on) sections + section else sections - section,
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigBackup.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCancellable {
                val text = repository.exportConfig(
                    sections = sections,
                    includeSecrets = includeSecrets,
                    appVersion = BuildConfig.VERSION_CODE,
                    appVersionName = BuildConfig.VERSION_NAME,
                )
                withContext(Dispatchers.IO) {
                    context.contentResolver.requireOutputStream(uri).use {
                        it.write(text.toByteArray())
                    }
                }
            }.isSuccess
            message = when {
                !ok -> context.getString(R.string.backup_export_write_error)
                ConfigBackup.Section.SETTINGS in sections && includeSecrets ->
                    context.getString(R.string.backup_export_done_with_keys)
                else -> context.getString(R.string.backup_export_done)
            }
        }
    }

    // Import reads the file first and asks before writing: restoring is not
    // something to discover you have done. Both the full-config bundle and the
    // older settings-only file are accepted.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { it.readBytes().decodeToString() }
                }.getOrNull()?.firstJsonDocument()
            }
            confirmImport = when {
                text == null -> {
                    message = context.getString(R.string.backup_import_read_error); null
                }
                ConfigBackup.decode(text) != null -> PendingImport.Config(text)
                SettingsBackup.decode(text) != null -> PendingImport.Legacy(text)
                else -> {
                    message = context.getString(R.string.backup_not_a_backup); null
                }
            }
        }
    }

    // Picking a folder is what arms the automatic backup, so the grant is taken
    // here and the switch is useless without it.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!taken) {
            message = context.getString(R.string.backup_auto_folder_denied)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            repository.setAutoBackupFolderUri(uri.toString())
            AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
        }
    }

    Text(
        stringResource(R.string.backup_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )

    AutoBackupGroup(
        repository = repository,
        auto = auto,
        onPickFolder = { folderLauncher.launch(null) },
        onMessage = { message = it },
    )

    SettingsGroup(stringResource(R.string.backup_include_group_title)) {
        item {
            ToggleSetting(
                R.string.backup_section_settings_label,
                stringResource(R.string.backup_include_settings_subtitle),
                ConfigBackup.Section.SETTINGS in sections,
                default = ConfigBackup.Section.SETTINGS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.SETTINGS, it) }
        }
        if (ConfigBackup.Section.SETTINGS in sections) {
            item {
                ToggleSetting(
                    R.string.backup_include_secrets_title,
                    stringResource(R.string.backup_include_secrets_subtitle),
                    includeSecrets,
                    info = stringResource(R.string.backup_include_secrets_info),
                    default = SettingsDefaults.autoBackup.includeSecrets,
                ) { on -> scope.launch { repository.setAutoBackupIncludeSecrets(on) } }
            }
        }
        item {
            ToggleSetting(
                R.string.backup_section_themes_label,
                stringResource(R.string.backup_include_themes_subtitle),
                ConfigBackup.Section.THEMES in sections,
                default = ConfigBackup.Section.THEMES.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_themes_info),
            ) { setSection(ConfigBackup.Section.THEMES, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_dictionary_label,
                stringResource(R.string.backup_include_dictionary_subtitle),
                ConfigBackup.Section.DICTIONARY in sections,
                default = ConfigBackup.Section.DICTIONARY.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_dictionary_info),
            ) { setSection(ConfigBackup.Section.DICTIONARY, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_clipboard_label,
                stringResource(R.string.backup_include_clipboard_subtitle),
                ConfigBackup.Section.CLIPBOARD in sections,
                default = ConfigBackup.Section.CLIPBOARD.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_clipboard_info),
            ) { setSection(ConfigBackup.Section.CLIPBOARD, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_snippets_label,
                stringResource(R.string.backup_include_snippets_subtitle),
                ConfigBackup.Section.SNIPPETS in sections,
                default = ConfigBackup.Section.SNIPPETS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.SNIPPETS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_stickers_label,
                stringResource(R.string.backup_include_stickers_subtitle),
                ConfigBackup.Section.STICKERS in sections,
                default = ConfigBackup.Section.STICKERS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_stickers_info),
            ) { setSection(ConfigBackup.Section.STICKERS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_icons_label,
                stringResource(R.string.backup_include_icons_subtitle),
                ConfigBackup.Section.ICONS in sections,
                default = ConfigBackup.Section.ICONS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_icons_info),
            ) { setSection(ConfigBackup.Section.ICONS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_wordlists_label,
                stringResource(R.string.backup_include_wordlists_subtitle),
                ConfigBackup.Section.WORDLISTS in sections,
                default = ConfigBackup.Section.WORDLISTS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.WORDLISTS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_addons_label,
                stringResource(R.string.backup_include_addons_subtitle),
                ConfigBackup.Section.ADDONS in sections,
                default = ConfigBackup.Section.ADDONS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_addons_info),
            ) { setSection(ConfigBackup.Section.ADDONS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_emoji_label,
                stringResource(R.string.backup_include_emoji_subtitle),
                ConfigBackup.Section.EMOJI in sections,
                default = ConfigBackup.Section.EMOJI.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.EMOJI, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_statistics_label,
                stringResource(R.string.backup_include_statistics_subtitle),
                ConfigBackup.Section.STATISTICS in sections,
                default = ConfigBackup.Section.STATISTICS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.STATISTICS, it) }
        }
    }

    SettingsGroup {
        item {
            OutlinedButton(
                enabled = sections.isNotEmpty(),
                // The one control here that copies the user's data out of the
                // app, API keys included, so it is one of the things the
                // fingerprint lock can be pointed at.
                onClick = rememberLockGuard(AppLockTargets["action_export_settings"]) {
                    // Datestamp the default name so successive backups don't
                    // overwrite each other and each file self-labels when it was made.
                    // Locale.US, not the default: on a Thai-Buddhist locale the
                    // platform formatter stamps 2569 for 2026, and a filename that
                    // sorts by date has to mean the same thing everywhere.
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    exportLauncher.launch(
                        "wmkeyboard-backup-$stamp.${ConfigBackup.FILE_EXTENSION}",
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.backup_export_action)) }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_import_group_title),
        info = stringResource(R.string.backup_import_note),
    ) {
        item {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.backup_import_action)) }
        }
    }
    Spacer(Modifier.height(16.dp))

    when (val pending = confirmImport) {
        is PendingImport.Config -> {
            val parsed = remember(pending.text) { ConfigBackup.decode(pending.text) }
            val counts = remember(pending.text) { parsed?.let { repository.describeConfig(it) }.orEmpty() }
            val hasSecrets = remember(pending.text) { parsed?.let { repository.configContainsSecrets(it) } ?: false }
            AlertDialog(
                onDismissRequest = { confirmImport = null },
                title = { Text(stringResource(R.string.backup_import_confirm_title)) },
                text = {
                    Text(
                        buildString {
                            append(context.getString(R.string.backup_import_contains))
                            append("\n")
                            for ((section, count) in counts) {
                                append("\n")
                                append(
                                    context.getString(
                                        R.string.backup_import_section_line,
                                        sectionLabel(context, section),
                                        sectionSummary(context, section, count),
                                    ),
                                )
                            }
                            append("\n\n")
                            append(context.getString(R.string.backup_import_merge_note))
                            if (hasSecrets) {
                                append("\n\n")
                                append(context.getString(R.string.backup_import_api_keys_note))
                            }
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmImport = null
                        scope.launch {
                            message = when (val result = repository.importConfig(pending.text)) {
                                is SettingsRepository.ConfigImportResult.Applied -> buildString {
                                    if (result.restored.isEmpty()) {
                                        append(context.getString(R.string.backup_restore_nothing))
                                    } else {
                                        append(
                                            context.getString(
                                                R.string.backup_restore_done,
                                                result.restored.joinToString {
                                                    sectionLabelLowercase(context, it)
                                                },
                                            ),
                                        )
                                    }
                                    if (result.settingsFailed) {
                                        append("\n\n")
                                        append(
                                            context.getString(R.string.backup_restore_settings_failed),
                                        )
                                    }
                                }
                                SettingsRepository.ConfigImportResult.NotABackup ->
                                    context.getString(R.string.backup_not_a_backup)
                            }
                        }
                    }) { Text(stringResource(CommonR.string.common_import)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmImport = null }) {
                        Text(stringResource(CommonR.string.common_cancel))
                    }
                },
            )
        }
        is PendingImport.Legacy -> {
            val parsed = remember(pending.text) { SettingsBackup.decode(pending.text) }
            AlertDialog(
                onDismissRequest = { confirmImport = null },
                title = { Text(stringResource(R.string.backup_import_settings_confirm_title)) },
                text = {
                    Text(
                        buildString {
                            val entries = parsed?.entries?.size ?: 0
                            append(
                                context.resources.getQuantityString(
                                    R.plurals.backup_import_settings_overwrite,
                                    entries,
                                    entries,
                                ),
                            )
                            if (parsed?.containsSecrets == true) {
                                append("\n\n")
                                append(context.getString(R.string.backup_import_api_keys_note))
                            }
                            val skipped = parsed?.skipped ?: 0
                            if (skipped > 0) {
                                append("\n\n")
                                append(
                                    context.resources.getQuantityString(
                                        R.plurals.backup_import_settings_skipped,
                                        skipped,
                                        skipped,
                                    ),
                                )
                            }
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmImport = null
                        scope.launch {
                            message = when (val result = repository.importSettings(pending.text)) {
                                is SettingsRepository.ImportResult.Applied ->
                                    context.resources.getQuantityString(
                                        R.plurals.backup_restore_settings_count,
                                        result.settings,
                                        result.settings,
                                    )
                                SettingsRepository.ImportResult.RolledBack ->
                                    context.getString(R.string.backup_restore_rolled_back)
                                SettingsRepository.ImportResult.NotABackup ->
                                    context.getString(R.string.backup_not_a_settings_backup)
                            }
                        }
                    }) { Text(stringResource(CommonR.string.common_import)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmImport = null }) {
                        Text(stringResource(CommonR.string.common_cancel))
                    }
                },
            )
        }
        null -> {}
    }

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
}

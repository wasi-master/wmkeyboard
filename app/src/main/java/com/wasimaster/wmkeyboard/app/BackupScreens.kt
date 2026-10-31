package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.text.format.DateUtils
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.storage.DownloadsBundle
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import com.wasimaster.wmkeyboard.core.util.readTextCapped
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.BackupCrypto
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsBackup
import com.wasimaster.wmkeyboard.core.settings.AutoBackupIntervals
import com.wasimaster.wmkeyboard.core.settings.AutoBackupKeepRange
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.FrequentBackup
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.FtpConfig
import com.wasimaster.wmkeyboard.core.settings.S3Config
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.needsNetwork
import com.wasimaster.wmkeyboard.core.settings.signsIn
import com.wasimaster.wmkeyboard.core.settings.sectionSet
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudDone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudQueue
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import com.wasimaster.wmkeyboard.core.settings.SyncMode
import com.wasimaster.wmkeyboard.core.settings.SyncSettings
import com.wasimaster.wmkeyboard.core.settings.activeLocations
import com.wasimaster.wmkeyboard.core.settings.backupTargets
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import androidx.compose.material3.Checkbox
import com.wasimaster.wmkeyboard.core.settings.exportSectionSet
import com.wasimaster.wmkeyboard.core.settings.sync.SyncRunner
import com.wasimaster.wmkeyboard.core.settings.keepLocalGroups
import com.wasimaster.wmkeyboard.core.settings.sync.SyncScheduler
import com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys
import com.wasimaster.wmkeyboard.core.settings.targets
import com.wasimaster.wmkeyboard.core.settings.BackupPause
import com.wasimaster.wmkeyboard.core.settings.paused
import com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming

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
    ConfigBackup.Section.VOCAB -> R.string.backup_section_vocab_label
    ConfigBackup.Section.SWIPE -> R.string.backup_section_swipe_label
    ConfigBackup.Section.NOTES -> R.string.backup_section_notes_label
    ConfigBackup.Section.CLOCK -> R.string.backup_section_clock_label
    ConfigBackup.Section.KAOMOJI -> R.string.backup_section_kaomoji_label
    ConfigBackup.Section.AI_MEMORY -> R.string.backup_section_ai_memory_label
    ConfigBackup.Section.TODO -> R.string.backup_section_todo_label
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
            ConfigBackup.Section.VOCAB -> R.string.backup_section_vocab_label_lowercase
            ConfigBackup.Section.SWIPE -> R.string.backup_section_swipe_label_lowercase
            ConfigBackup.Section.NOTES -> R.string.backup_section_notes_label_lowercase
            ConfigBackup.Section.CLOCK -> R.string.backup_section_clock_label_lowercase
            ConfigBackup.Section.KAOMOJI -> R.string.backup_section_kaomoji_label_lowercase
            ConfigBackup.Section.AI_MEMORY -> R.string.backup_section_ai_memory_label_lowercase
            ConfigBackup.Section.TODO -> R.string.backup_section_todo_label_lowercase
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
    ConfigBackup.Section.VOCAB -> R.plurals.backup_section_vocab_count
    ConfigBackup.Section.SWIPE -> R.plurals.backup_section_swipe_count
    ConfigBackup.Section.NOTES -> R.plurals.backup_section_notes_count
    ConfigBackup.Section.CLOCK -> R.plurals.backup_section_clock_count
    ConfigBackup.Section.KAOMOJI -> R.plurals.backup_section_kaomoji_count
    ConfigBackup.Section.AI_MEMORY -> R.plurals.backup_section_ai_memory_count
    ConfigBackup.Section.TODO -> R.plurals.backup_section_todo_count
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
/**
 * One sentence for a recorded failure, or null when the last run was fine.
 * [destination] picks the advice: a lost folder grant, a lost account
 * sign-in and a refused server password each need a different thing done.
 */
internal fun autoBackupErrorText(
    context: Context,
    error: String,
    destination: BackupDestination = BackupDestination.FOLDER,
): String? = when (error) {
    "" -> null
    SinkError.PERMISSION_LOST.name -> context.getString(
        when {
            destination == BackupDestination.FOLDER -> R.string.backup_auto_error_permission
            destination == BackupDestination.NFS -> R.string.backup_auto_error_permission_nfs
            destination.signsIn -> R.string.backup_auto_error_permission_account
            else -> R.string.backup_auto_error_permission_server
        },
    )
    SinkError.TARGET_MISSING.name -> context.getString(
        if (destination == BackupDestination.FOLDER) {
            R.string.backup_auto_error_target
        } else {
            R.string.backup_auto_error_target_remote
        },
    )
    SinkError.OUT_OF_SPACE.name -> context.getString(R.string.backup_auto_error_space)
    SinkError.UNSAFE.name -> context.getString(
        when (destination) {
            BackupDestination.SFTP -> R.string.backup_auto_error_unsafe_sftp
            BackupDestination.GIT -> R.string.backup_auto_error_unsafe_git
            BackupDestination.SMB -> R.string.backup_auto_error_unsafe_smb
            BackupDestination.SYNOLOGY, BackupDestination.QNAP -> R.string.backup_auto_error_unsafe_nas
            BackupDestination.PROTON -> R.string.backup_auto_error_unsafe_proton
            else -> R.string.backup_auto_error_unsafe
        },
    )
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
internal fun StoredTextField(
    label: String,
    value: String,
    supporting: String,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** For a pasted key or a template: lets the field grow instead of scrolling one line. */
    multiLine: Boolean = false,
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
        singleLine = !multiLine,
        maxLines = if (multiLine) MULTI_LINE_MAX else 1,
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
internal tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/**
 * What each destination needs from the user, one line, for the picker sheet.
 * The list is brand names, and a brand name says nothing about whether the
 * choice wants a sign-in, a URL or a pair of keys.
 */
internal fun destinationDescRes(destination: BackupDestination): Int = when (destination) {
    BackupDestination.FOLDER -> R.string.backup_auto_dest_folder_desc
    BackupDestination.WEBDAV -> R.string.backup_auto_dest_webdav_desc
    BackupDestination.DRIVE -> R.string.backup_auto_dest_drive_desc
    BackupDestination.S3 -> R.string.backup_auto_dest_s3_desc
    BackupDestination.FTP -> R.string.backup_auto_dest_ftp_desc
    BackupDestination.DROPBOX -> R.string.backup_auto_dest_dropbox_desc
    BackupDestination.ONEDRIVE -> R.string.backup_auto_dest_onedrive_desc
    BackupDestination.SFTP -> R.string.backup_auto_dest_sftp_desc
    BackupDestination.SMB -> R.string.backup_auto_dest_smb_desc
    BackupDestination.GIT -> R.string.backup_auto_dest_git_desc
    BackupDestination.IMAP -> R.string.backup_auto_dest_imap_desc
    BackupDestination.NEARBY -> R.string.backup_auto_dest_nearby_desc
    BackupDestination.BOX -> R.string.backup_auto_dest_box_desc
    BackupDestination.PCLOUD -> R.string.backup_auto_dest_pcloud_desc
    BackupDestination.SYNOLOGY -> R.string.backup_auto_dest_synology_desc
    BackupDestination.QNAP -> R.string.backup_auto_dest_qnap_desc
    BackupDestination.NFS -> R.string.backup_auto_dest_nfs_desc
    BackupDestination.MEGA -> R.string.backup_auto_dest_mega_desc
    BackupDestination.ICLOUD -> R.string.backup_auto_dest_icloud_desc
    BackupDestination.PROTON -> R.string.backup_auto_dest_proton_desc
}

/** One sentence for whatever a backup run turned out to be. */
private fun autoBackupOutcomeText(
    context: Context,
    outcome: AutoBackupRunner.Outcome,
    auto: AutoBackupSettings,
): String {
    fun typeOf(id: String?) = auto.locations.firstOrNull { it.id == id }?.type ?: BackupDestination.FOLDER
    return when (outcome) {
        is AutoBackupRunner.Outcome.Done -> buildString {
            append(
                if (outcome.skipped.isEmpty()) {
                    context.getString(R.string.backup_auto_done, outcome.name)
                } else {
                    context.getString(
                        R.string.backup_auto_done_skipped,
                        outcome.name,
                        outcome.skipped.joinToString { sectionLabelLowercase(context, it) },
                    )
                },
            )
            for ((id, reason) in outcome.failed) {
                val location = auto.locations.firstOrNull { it.id == id } ?: continue
                append("\n\n")
                append(location.title(context)).append(": ")
                append(autoBackupErrorText(context, reason.name, location.type).orEmpty())
            }
        }
        AutoBackupRunner.Outcome.Locked -> context.getString(R.string.backup_auto_locked)
        AutoBackupRunner.Outcome.Skipped -> context.getString(R.string.backup_auto_skipped)
        is AutoBackupRunner.Outcome.Failed ->
            autoBackupErrorText(context, outcome.reason.name, typeOf(auto.activeLocations.firstOrNull()?.id))
                ?: context.getString(R.string.backup_auto_error_io)
    }
}

/** One sentence for a sync pass. */
private fun syncOutcomeText(context: Context, outcome: SyncRunner.Outcome, auto: AutoBackupSettings): String =
    when (outcome) {
        is SyncRunner.Outcome.Done -> when {
            SyncRunner.ERROR_PASSPHRASE in outcome.failed.values ->
                context.getString(R.string.backup_sync_error_passphrase)
            outcome.applied == 0 -> context.getString(R.string.backup_sync_done_nothing)
            else -> context.resources.getQuantityString(
                R.plurals.backup_sync_done_changes,
                outcome.applied,
                outcome.applied,
            )
        }
        SyncRunner.Outcome.Locked -> context.getString(R.string.backup_sync_locked)
        SyncRunner.Outcome.Skipped -> context.getString(R.string.backup_sync_skipped)
        is SyncRunner.Outcome.Failed -> syncErrorText(context, outcome.reason, auto)
    }

/** A recorded sync failure in words, or null for none. */
private fun syncErrorText(context: Context, reason: String, auto: AutoBackupSettings): String =
    if (reason == SyncRunner.ERROR_PASSPHRASE) {
        context.getString(R.string.backup_sync_error_passphrase)
    } else {
        val type = auto.sync.targets(auto.locations).firstOrNull()?.type ?: BackupDestination.FOLDER
        autoBackupErrorText(context, reason, type) ?: context.getString(R.string.backup_auto_error_io)
    }

/**
 * The top of the Backup & restore screen: is the keyboard covered, and when
 * was it last, with the two buttons that act on it. The one place on the
 * screen that answers "am I safe?" without reading anything else.
 */
@Composable
private fun BackupStatusCard(
    repository: SettingsRepository,
    settings: LiveSettings,
    onMessage: (String) -> Unit,
) {
    val auto = settings.watch { it.autoBackup }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    var backupPhase by remember { mutableStateOf(RunPhase.IDLE) }
    var syncPhase by remember { mutableStateOf(RunPhase.IDLE) }
    val backingUp = backupPhase == RunPhase.RUNNING
    val syncing = syncPhase == RunPhase.RUNNING
    // The tick a run that worked earns stays on its button for a moment, then
    // the button is a button again.
    LaunchedEffect(backupPhase) {
        if (backupPhase == RunPhase.DONE) {
            delay(DONE_HOLD_MS)
            backupPhase = RunPhase.IDLE
        }
    }
    LaunchedEffect(syncPhase) {
        if (syncPhase == RunPhase.DONE) {
            delay(DONE_HOLD_MS)
            syncPhase = RunPhase.IDLE
        }
    }
    // The cloud gives a small hop when a backup you started has worked: the
    // card's answer to "am I safe?" changing in front of you.
    val hop = remember { Animatable(1f) }
    val active = auto.backupTargets
    val backupOn = auto.enabled && active.isNotEmpty()
    // Read once per composition: the card is not where a pause running out
    // has to show to the second.
    val backupPaused = backupOn && auto.paused()
    val syncPaused = auto.sync.enabled && auto.sync.paused()
    val error = autoBackupErrorText(context, auto.lastError, active.firstOrNull()?.type ?: BackupDestination.FOLDER)
    val syncError = auto.sync.lastError.takeIf { it.isNotEmpty() }?.let { syncErrorText(context, it, auto) }
    val problem = error != null || (auto.sync.enabled && syncError != null)
    val colors = MaterialTheme.colorScheme
    // A card that goes from "off" to "on", or into a problem, changes colour
    // over a moment rather than in a frame.
    val tint = if (reduceMotion) snap<Color>() else tween<Color>(durationMillis = CARD_TINT_MS)
    val container by animateColorAsState(
        when {
            problem -> colors.errorContainer
            backupOn || auto.sync.enabled -> colors.secondaryContainer
            else -> colors.surfaceContainerHigh
        },
        animationSpec = tint,
        label = "backupCardContainer",
    )
    val onContainer by animateColorAsState(
        when {
            problem -> colors.onErrorContainer
            backupOn || auto.sync.enabled -> colors.onSecondaryContainer
            else -> colors.onSurface
        },
        animationSpec = tint,
        label = "backupCardContent",
    )
    val buttonSize = if (reduceMotion) Modifier else Modifier.animateContentSize()

    Surface(
        color = container,
        contentColor = onContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = when {
                        problem -> Icons.Outlined.CloudOff
                        backupPaused -> Icons.Outlined.Pause
                        backupOn -> Icons.Outlined.CloudDone
                        else -> Icons.Outlined.CloudQueue
                    },
                    transitionSpec = { stateSwapTransform(reduceMotion, animateSize = false) },
                    label = "backupCardIcon",
                ) { icon ->
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .graphicsLayer {
                                scaleX = hop.value
                                scaleY = hop.value
                            },
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when {
                                backupPaused -> R.string.backup_hub_backup_paused
                                backupOn -> R.string.backup_hub_backup_on
                                else -> R.string.backup_hub_backup_off
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (active.isEmpty()) {
                            stringResource(R.string.backup_hub_no_locations)
                        } else {
                            pluralStringResource(
                                R.plurals.backup_hub_backup_where,
                                active.size,
                                backupIntervalLabel(context, auto.intervalHours),
                                active.size,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // "Never" to "Just now" when the first run lands: a hand-over, not
            // a swap in a frame.
            AnimatedContent(
                targetState = error ?: if (auto.lastRunAtMs > 0L) {
                    stringResource(
                        R.string.backup_auto_last_run,
                        DateUtils.getRelativeTimeSpanString(auto.lastRunAtMs).toString(),
                    )
                } else {
                    stringResource(R.string.backup_auto_never)
                },
                transitionSpec = { stateSwapTransform(reduceMotion) },
                label = "backupCardLastRun",
            ) { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
            if (auto.sync.enabled) {
                Text(
                    syncError ?: if (syncPaused) {
                        stringResource(R.string.backup_hub_sync_paused)
                    } else if (auto.sync.lastRunAtMs > 0L) {
                        stringResource(
                            R.string.backup_hub_last_sync,
                            DateUtils.getRelativeTimeSpanString(auto.sync.lastRunAtMs).toString(),
                        )
                    } else {
                        stringResource(R.string.backup_hub_never_synced)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Filled, not tonal: a tonal button on a container-coloured
                // card is the card's own colour, and reads as a text link.
                Button(
                    enabled = active.isNotEmpty() && !backingUp,
                    modifier = buttonSize,
                    onClick = {
                        backupPhase = RunPhase.RUNNING
                        scope.launch {
                            val outcome = AutoBackupRunner.run(context, repository, force = true)
                            val worked = outcome is AutoBackupRunner.Outcome.Done && outcome.failed.isEmpty()
                            backupPhase = if (worked) RunPhase.DONE else RunPhase.IDLE
                            if (worked && !reduceMotion) {
                                launch { hop.celebrate() }
                                // Long enough to see the tick land before the
                                // dialog's scrim covers it.
                                delay(CHECK_BEAT_MS)
                            }
                            onMessage(autoBackupOutcomeText(context, outcome, repository.settings.first().autoBackup))
                        }
                    },
                ) {
                    RunGlyph(backupPhase, LocalContentColor.current)
                    Text(
                        stringResource(if (backingUp) R.string.backup_auto_running else R.string.backup_auto_now_action),
                    )
                }
                if (auto.sync.enabled) {
                    OutlinedButton(
                        enabled = !syncing,
                        modifier = buttonSize,
                        onClick = {
                            syncPhase = RunPhase.RUNNING
                            scope.launch {
                                val outcome = SyncRunner.run(context, repository, force = true)
                                val worked = outcome.syncWorked()
                                syncPhase = if (worked) RunPhase.DONE else RunPhase.IDLE
                                if (worked && !reduceMotion) delay(CHECK_BEAT_MS)
                                onMessage(syncOutcomeText(context, outcome, repository.settings.first().autoBackup))
                            }
                        },
                    ) {
                        RunGlyph(syncPhase, MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (syncing) R.string.backup_hub_syncing else R.string.backup_hub_sync_now))
                    }
                }
            }
        }
    }
}

/** One section's row on a "what goes in" list: its subtitle and its "?". */
private class SectionRow(
    val section: ConfigBackup.Section,
    @StringRes val subtitle: Int,
    @StringRes val info: Int? = null,
)

private val SECTION_ROWS = listOf(
    SectionRow(ConfigBackup.Section.SETTINGS, R.string.backup_include_settings_subtitle),
    SectionRow(ConfigBackup.Section.THEMES, R.string.backup_include_themes_subtitle, R.string.backup_include_themes_info),
    SectionRow(
        ConfigBackup.Section.DICTIONARY,
        R.string.backup_include_dictionary_subtitle,
        R.string.backup_include_dictionary_info,
    ),
    SectionRow(
        ConfigBackup.Section.CLIPBOARD,
        R.string.backup_include_clipboard_subtitle,
        R.string.backup_include_clipboard_info,
    ),
    SectionRow(ConfigBackup.Section.SNIPPETS, R.string.backup_include_snippets_subtitle),
    SectionRow(
        ConfigBackup.Section.STICKERS,
        R.string.backup_include_stickers_subtitle,
        R.string.backup_include_stickers_info,
    ),
    SectionRow(ConfigBackup.Section.ICONS, R.string.backup_include_icons_subtitle, R.string.backup_include_icons_info),
    SectionRow(ConfigBackup.Section.WORDLISTS, R.string.backup_include_wordlists_subtitle),
    SectionRow(ConfigBackup.Section.ADDONS, R.string.backup_include_addons_subtitle, R.string.backup_include_addons_info),
    SectionRow(ConfigBackup.Section.EMOJI, R.string.backup_include_emoji_subtitle),
    SectionRow(ConfigBackup.Section.STATISTICS, R.string.backup_include_statistics_subtitle),
    SectionRow(ConfigBackup.Section.VOCAB, R.string.backup_include_vocab_subtitle),
    SectionRow(ConfigBackup.Section.SWIPE, R.string.backup_include_swipe_subtitle),
    SectionRow(ConfigBackup.Section.NOTES, R.string.backup_include_notes_subtitle, R.string.backup_include_notes_info),
    SectionRow(ConfigBackup.Section.CLOCK, R.string.backup_include_clock_subtitle, R.string.backup_include_clock_info),
    SectionRow(ConfigBackup.Section.KAOMOJI, R.string.backup_include_kaomoji_subtitle),
    SectionRow(
        ConfigBackup.Section.AI_MEMORY,
        R.string.backup_include_ai_memory_subtitle,
        R.string.backup_include_ai_memory_info,
    ),
    SectionRow(ConfigBackup.Section.TODO, R.string.backup_include_todo_subtitle),
)

/**
 * The same seventeen switches for the three lists that pick sections: the
 * export, the automatic backup and sync. [sections] picks the list's sections
 * out of the settings, which each row reads for itself. [secrets] adds the
 * API-key switch under Settings where the list has one, shown while
 * [settingsIncluded] (Settings is among the sections, read by the caller since
 * it decides the rows).
 */
private fun SettingsGroupScope.sectionRows(
    settings: LiveSettings,
    sections: (KeyboardSettings) -> Set<ConfigBackup.Section>,
    defaults: Set<String>,
    secrets: Pair<(KeyboardSettings) -> Boolean, (Boolean) -> Unit>?,
    settingsIncluded: Boolean = false,
    secretsEnabled: Boolean = true,
    @StringRes secretsSubtitle: Int = R.string.backup_include_secrets_subtitle,
    /** Only the sections sync may carry: for the sync list. */
    syncOnly: Boolean = false,
    onToggle: (ConfigBackup.Section, Boolean) -> Unit,
) {
    for (row in SECTION_ROWS) {
        if (syncOnly && !row.section.syncable) continue
        item {
            ToggleSetting(
                sectionLabelRes(row.section),
                stringResource(row.subtitle),
                settings.watch { row.section in sections(it) },
                info = row.info?.let { stringResource(it) },
                default = row.section.id in defaults,
            ) { onToggle(row.section, it) }
        }
        if (row.section == ConfigBackup.Section.SETTINGS && secrets != null) {
            item(visible = settingsIncluded) {
                ToggleSetting(
                    R.string.backup_include_secrets_title,
                    stringResource(secretsSubtitle),
                    settings.watch { secrets.first(it) },
                    info = stringResource(R.string.backup_include_secrets_info),
                    enabled = secretsEnabled,
                    default = false,
                ) { secrets.second(it) }
            }
        }
    }
}

/**
 * One location with a tick box, for the two lists that pick where things go:
 * Back up to, and Sync through. The whole row toggles, as a switch row does.
 */
@Composable
private fun LocationCheckRow(location: BackupLocation, checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    WmRow(
        title = location.title(context),
        subtitle = if (location.configured) {
            location.detail(context)
        } else {
            stringResource(needsSetupRes(location.type))
        },
        leading = { WmIconTile(icon = destinationIcon(location.type), accent = location.type.accent) },
        trailing = { Checkbox(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/**
 * Pause or resume, for the automatic backup and for sync: one row that says
 * which it is. Paused, the whole row resumes; otherwise it asks how long.
 * [pausedUntilMs] is the stored value (see [BackupPause]).
 */
@Composable
private fun PauseRow(
    pausedUntilMs: Long,
    @StringRes title: Int,
    @StringRes subtitle: Int,
    @StringRes pausedTitle: Int,
    onPause: (untilMs: Long) -> Unit,
    onResume: () -> Unit,
) {
    val context = LocalContext.current
    // Recomposes when a timed pause runs out, so the row does not go on
    // saying "paused" with the screen open.
    val now by produceState(System.currentTimeMillis(), pausedUntilMs) {
        while (BackupPause.isPaused(pausedUntilMs, value) && pausedUntilMs != BackupPause.FOREVER) {
            delay((pausedUntilMs - value).coerceIn(1_000L, 60_000L))
            value = System.currentTimeMillis()
        }
    }
    val paused = BackupPause.isPaused(pausedUntilMs, now)
    var asking by remember { mutableStateOf(false) }
    if (paused) {
        WmRow(
            title = stringResource(pausedTitle),
            subtitle = if (pausedUntilMs == BackupPause.FOREVER) {
                stringResource(R.string.backup_paused_forever)
            } else {
                stringResource(
                    R.string.backup_paused_until,
                    DateUtils.formatDateTime(
                        context,
                        pausedUntilMs,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                    ),
                )
            },
            icon = Icons.Outlined.PlayArrow,
            trailing = { TextButton(onClick = onResume) { Text(stringResource(R.string.backup_pause_resume)) } },
            onClick = onResume,
        )
    } else {
        WmRow(
            title = stringResource(title),
            subtitle = stringResource(subtitle),
            icon = Icons.Outlined.Pause,
            onClick = { asking = true },
        )
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.backup_pause_dialog_title)) },
            text = {
                Column {
                    for (hours in BackupPause.CHOICES) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        when (hours) {
                                            null -> R.string.backup_pause_until_resumed
                                            1 -> R.string.backup_pause_hour
                                            24 -> R.string.backup_pause_day
                                            else -> R.string.backup_pause_week
                                        },
                                    ),
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().clickable {
                                asking = false
                                onPause(BackupPause.until(System.currentTimeMillis(), hours))
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

/**
 * Picks the sync group: a name to type, and the groups other devices already
 * use at [locations], read from their sync files, to tap instead. [joining]
 * is the location just ticked when the dialog opened because it holds other
 * groups; it only changes the sentence on top.
 *
 * [onSave] gets the name as typed; the repository makes it a group name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncGroupDialog(
    current: String,
    locations: List<BackupLocation>,
    joining: BackupLocation?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(current) }
    // Null while the locations are still being listed.
    var found by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(locations) {
        found = locations.flatMap { SyncRunner.groupsAt(context, it).getOrNull().orEmpty() }.distinct().sorted()
    }
    val slug = SyncNaming.groupSlug(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_sync_group_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_sync_group_info), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (joining != null) {
                        stringResource(R.string.backup_sync_group_join_note, joining.title(context))
                    } else {
                        stringResource(R.string.backup_sync_group_change_note)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.backup_sync_group_label)) },
                    supportingText = {
                        Text(
                            if (slug.isNotEmpty() && slug != text) {
                                stringResource(R.string.backup_sync_group_saved_as, slug)
                            } else {
                                stringResource(R.string.backup_sync_group_supporting)
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.backup_sync_group_found), style = MaterialTheme.typography.titleSmall)
                val groups = found
                when {
                    groups == null -> Text(
                        stringResource(R.string.backup_sync_group_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    groups.isEmpty() -> Text(
                        stringResource(R.string.backup_sync_group_found_none),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (group in groups) {
                            FilterChip(
                                selected = slug == group,
                                onClick = { text = group },
                                label = {
                                    Text(group.ifEmpty { stringResource(R.string.backup_sync_group_no_group) })
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * The "include API keys" switch, which asks before it goes on without a
 * passphrase. It does not refuse: the keys and the storage are the user's.
 * It says plainly what the file will then hold.
 */
@Composable
private fun rememberKeysSwitch(encrypted: Boolean, onSet: (Boolean) -> Unit): (Boolean) -> Unit {
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.backup_secrets_warning_title)) },
            text = { Text(stringResource(R.string.backup_secrets_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onSet(true)
                }) { Text(stringResource(R.string.backup_secrets_warning_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
    return { on -> if (on && !encrypted) asking = true else onSet(on) }
}

/**
 * Backup & restore: where backups go, how the automatic backup and sync run,
 * and the one-off file export and import.
 */
@Composable
internal fun BackupSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var message by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf<PendingImport?>(null) }

    // Everything downloaded, as a zip of its own (#1045): see [DownloadsBundle].
    val downloadsExport = rememberLauncherForActivityResult(
        CreateDocumentAt("application/zip", PickerPlaces.BACKUP_EXPORT),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val done = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.requireOutputStream(uri).use { DownloadsBundle.export(context, it) }
                }
            }.getOrNull()
            message = if (done == null) {
                context.getString(R.string.backup_downloads_failed)
            } else {
                context.resources.getQuantityString(R.plurals.backup_downloads_exported, done.files, done.files)
            }
        }
    }
    val downloadsImport = rememberLauncherForActivityResult(OpenDocumentAt(PickerPlaces.BACKUP_IMPORT)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val done = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.requireInputStream(uri).use { DownloadsBundle.import(context, it) }
                }
            }.getOrNull()
            message = if (done == null) {
                context.getString(R.string.backup_downloads_failed)
            } else {
                context.resources.getQuantityString(R.plurals.backup_downloads_imported, done.files, done.files, done.refused)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        CreateDocumentAt(ConfigBackup.MIME_TYPE, PickerPlaces.BACKUP_EXPORT),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerPlaces.remember(context, PickerPlaces.BACKUP_EXPORT, uri)
        val requested = settings.value.autoBackup.exportSectionSet
        val includeSecrets = settings.value.autoBackup.includeSecrets
        scope.launch {
            var skipped = emptySet<ConfigBackup.Section>()
            val written = runCancellable {
                withContext(Dispatchers.IO) {
                    skipped = AutoBackupRunner.exportSectionsToDrop(repository, requested)
                    context.contentResolver.requireOutputStream(uri).use { raw ->
                        BufferedOutputStream(raw).use { out ->
                            repository.exportConfigTo(
                                out = out,
                                sections = requested - skipped,
                                includeSecrets = includeSecrets,
                                appVersion = BuildConfig.VERSION_CODE,
                                appVersionName = BuildConfig.VERSION_NAME,
                            )
                        }
                    }
                }
            }
            // The dialog has one sentence for every failure; the log says which.
            written.exceptionOrNull()?.let { BackupLog.w("export to file failed", it) }
            message = when {
                written.isFailure -> context.getString(R.string.backup_export_write_error)
                skipped.isNotEmpty() -> context.getString(
                    R.string.backup_auto_done_skipped,
                    WMFileTypes.displayName(context, uri),
                    skipped.joinToString { sectionLabelLowercase(context, it) },
                )
                ConfigBackup.Section.SETTINGS in requested && includeSecrets ->
                    context.getString(R.string.backup_export_done_with_keys)
                else -> context.getString(R.string.backup_export_done)
            }
        }
    }

    // Another keyboard's backup goes through the same dialog an opened file
    // does, which reads it and lists what is inside before anything is written.
    // A passphrase-protected bundle of our own goes there too: that dialog
    // already asks for the passphrase and decrypts.
    var foreignUri by remember { mutableStateOf<Uri?>(null) }
    // Import reads the file first and asks before writing: restoring is not
    // something to discover you have done. Both the full-config bundle and the
    // older settings-only file are accepted, plain or encrypted.
    val importLauncher = rememberLauncherForActivityResult(
        OpenDocumentAt(PickerPlaces.BACKUP_IMPORT),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerPlaces.remember(context, PickerPlaces.BACKUP_IMPORT, uri)
        scope.launch {
            // Before the text read, which would decode the ciphertext as UTF-8
            // and then call it not a backup.
            val encrypted = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use { input ->
                        val head = ByteArray(BackupCrypto.MAGIC_SIZE)
                        var read = 0
                        while (read < head.size) {
                            val n = input.read(head, read, head.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        BackupCrypto.looksEncrypted(head.copyOf(read))
                    }
                }.getOrDefault(false)
            }
            if (encrypted) {
                foreignUri = uri
                return@launch
            }
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.readTextCapped(uri)
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
    val foreignLauncher = rememberLauncherForActivityResult(
        OpenDocumentAt(PickerPlaces.BACKUP_FOREIGN),
    ) { uri ->
        PickerPlaces.remember(context, PickerPlaces.BACKUP_FOREIGN, uri)
        foreignUri = uri
    }
    val exportGuarded = rememberLockGuard(AppLockTargets["action_export_settings"]) {
        // Datestamped, Locale.US: see the automatic backup's names.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        exportLauncher.launch("wmkeyboard-backup-$stamp.${ConfigBackup.FILE_EXTENSION}")
    }

    BackupStatusCard(repository, settings) { message = it }

    LocationsGroup(repository, settings.watch { it.autoBackup }) { message = it }

    SettingsGroup {
        item {
            // The interval and the count kept, or null while it is off.
            val schedule = settings.watch { s ->
                s.autoBackup.takeIf { it.enabled && it.backupTargets.isNotEmpty() }?.let { it.intervalHours to it.keep }
            }
            val backupPaused = settings.watch { it.autoBackup.paused() }
            NavRow(
                R.string.backup_auto_group_title,
                subtitle = if (schedule != null && backupPaused) {
                    stringResource(R.string.backup_hub_paused)
                } else if (schedule != null) {
                    val (intervalHours, keep) = schedule
                    stringResource(
                        R.string.backup_hub_auto_summary,
                        backupIntervalLabel(context, intervalHours),
                        context.resources.getQuantityString(R.plurals.backup_auto_keep_value, keep, keep),
                    )
                } else {
                    stringResource(R.string.backup_hub_backup_off)
                },
                route = "backup/auto",
            ) { onNavigate("backup/auto") }
        }
        item(level = SettingLevel.STANDARD) {
            val syncOn = settings.watch { it.autoBackup.sync.enabled }
            val syncMode = settings.watch { it.autoBackup.sync.mode }
            val syncPaused = settings.watch { it.autoBackup.sync.paused() }
            NavRow(
                R.string.backup_sync_title,
                subtitle = stringResource(
                    if (!syncOn) {
                        R.string.backup_sync_nav_subtitle
                    } else if (syncPaused) {
                        R.string.backup_hub_paused
                    } else {
                        when (syncMode) {
                            SyncMode.SOON -> R.string.backup_sync_mode_soon
                            SyncMode.SCHEDULE -> R.string.backup_sync_mode_schedule
                            SyncMode.MANUAL -> R.string.backup_sync_mode_manual
                        }
                    },
                ),
                route = "backup/sync",
            ) { onNavigate("backup/sync") }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_downloads_title),
        info = stringResource(R.string.backup_downloads_info),
        level = SettingLevel.STANDARD,
    ) {
        item {
            ActionRow(
                title = R.string.backup_downloads_export_title,
                subtitle = stringResource(R.string.backup_downloads_export_subtitle),
                action = stringResource(R.string.backup_downloads_export_action),
            ) { downloadsExport.launch("wmkeyboard-downloads.zip") }
        }
        item {
            ActionRow(
                title = R.string.backup_downloads_import_title,
                subtitle = stringResource(R.string.backup_downloads_import_subtitle),
                action = stringResource(R.string.backup_downloads_import_action),
            ) { downloadsImport.launch(arrayOf("application/zip", "application/octet-stream")) }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_files_title),
        info = stringResource(R.string.backup_info),
    ) {
        item {
            WmRow(
                title = stringResource(R.string.backup_files_export_title),
                subtitle = stringResource(R.string.backup_files_export_subtitle),
                icon = Icons.Outlined.FileUpload,
                enabled = settings.watch { it.autoBackup.exportSectionSet.isNotEmpty() },
                onClick = exportGuarded,
            )
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.backup_files_contents_title,
                subtitle = stringResource(
                    R.string.backup_include_nav_subtitle,
                    settings.watch { it.autoBackup.exportSectionSet.size },
                    ConfigBackup.Section.entries.size,
                ),
                route = "backup/contents",
            ) { onNavigate("backup/contents") }
        }
        item {
            WmRow(
                title = stringResource(R.string.backup_files_import_title),
                subtitle = stringResource(R.string.backup_files_import_subtitle),
                icon = Icons.Outlined.FileDownload,
                onClick = { importLauncher.launch(arrayOf("*/*")) },
            )
        }
        item(level = SettingLevel.STANDARD) {
            WmRow(
                title = stringResource(R.string.backup_foreign_import_title),
                subtitle = stringResource(R.string.backup_foreign_import_subtitle),
                icon = Icons.Outlined.KeyboardAlt,
                onClick = { foreignLauncher.launch(arrayOf("*/*")) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    foreignUri?.let { uri -> ImportFileDialog(repository, uri) { foreignUri = null } }

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
                            // What the file leaves out, so a restore that
                            // brings back no clipboard says so before it runs:
                            // each kind of backup keeps its own list of parts.
                            val missing = ConfigBackup.Section.entries.filter { it !in counts.keys }
                            if (missing.isNotEmpty()) {
                                append("\n\n")
                                append(
                                    context.getString(
                                        R.string.backup_import_missing,
                                        missing.joinToString { sectionLabelLowercase(context, it) },
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

/**
 * The automatic backup on a page of its own: the switch, the schedule, the
 * passphrase and what goes in. Where the backups go is on the main screen,
 * as the list of locations.
 */
@Composable
internal fun BackupAutoSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows and groups the screen holds; each row reads its
    // own value.
    val autoOn = settings.watch { it.autoBackup.enabled }
    val configured = settings.watch { it.autoBackup.backupTargets.isNotEmpty() }
    val overNetwork = settings.watch { s -> s.autoBackup.backupTargets.any { it.type.needsNetwork } }
    val locations = settings.watch { it.autoBackup.locations }
    val encryptOn = settings.watch { it.autoBackup.encrypt }
    val encrypted = settings.watch { it.autoBackup.encrypt && it.autoBackup.passphrase.isNotEmpty() }
    // Locked notes ride on the same switch as the keys, so the row has to be
    // there whenever either section is.
    val settingsIncluded = settings.watch {
        ConfigBackup.Section.SETTINGS in it.autoBackup.sectionSet || ConfigBackup.Section.NOTES in it.autoBackup.sectionSet
    }
    val setKeys = rememberKeysSwitch(encrypted) { on -> scope.launch { repository.setBackupIncludeSecrets(on) } }
    val personal = settings.watch { s ->
        ConfigBackup.Section.DICTIONARY.id in s.autoBackup.sections ||
            ConfigBackup.Section.CLIPBOARD.id in s.autoBackup.sections ||
            ConfigBackup.Section.SWIPE.id in s.autoBackup.sections ||
            ConfigBackup.Section.AI_MEMORY.id in s.autoBackup.sections
    }

    fun resync() = scope.launch { AutoBackupScheduler.sync(context, repository.settings.first().autoBackup) }

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.backup_auto_enabled_title,
                stringResource(
                    when {
                        configured -> R.string.backup_auto_enabled_subtitle
                        locations.isEmpty() -> R.string.backup_auto_needs_location
                        else -> R.string.backup_auto_needs_target
                    },
                ),
                autoOn && configured,
                enabled = configured,
                default = SettingsDefaults.autoBackup.enabled && configured,
            ) { on ->
                scope.launch {
                    repository.setAutoBackupEnabled(on)
                    resync()
                }
            }
        }
        item(visible = autoOn && configured, level = SettingLevel.STANDARD) {
            PauseRow(
                pausedUntilMs = settings.watch { it.autoBackup.pausedUntilMs },
                title = R.string.backup_pause_title,
                subtitle = R.string.backup_pause_subtitle,
                pausedTitle = R.string.backup_paused_title,
                onPause = { until -> scope.launch { repository.setAutoBackupPausedUntil(until) } },
                // A backup the pause held back is due at once, and the job,
                // left in place, takes it at its next firing.
                onResume = { scope.launch { repository.setAutoBackupPausedUntil(0L) } },
            )
        }
        item(visible = autoOn && configured) {
            // The ladder by index, so every stop is a value somebody would pick.
            SliderSetting(
                R.string.backup_auto_interval_title,
                value = intervalSliderIndex(settings.watch { it.autoBackup.intervalHours }).toFloat(),
                range = 0f..(AutoBackupIntervals.size - 1).toFloat(),
                display = { backupIntervalLabel(context, intervalAt(it)) },
                // Hours, then days: a bare number cannot say which.
                typed = false,
                default = intervalSliderIndex(SettingsDefaults.autoBackup.intervalHours).toFloat(),
            ) { index ->
                scope.launch {
                    repository.setAutoBackupIntervalHours(intervalAt(index))
                    resync()
                }
            }
        }
        item(visible = autoOn && configured, level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.backup_auto_keep_title,
                subtitle = stringResource(R.string.backup_auto_keep_subtitle),
                value = settings.watch { it.autoBackup.keep }.toFloat(),
                range = AutoBackupKeepRange.first.toFloat()..AutoBackupKeepRange.last.toFloat(),
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
        // #970: older generations spread out in time, past the newest ones.
        item(visible = autoOn && configured, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.backup_auto_staggered_title,
                stringResource(R.string.backup_auto_staggered_subtitle),
                settings.watch { it.autoBackup.staggered },
                info = stringResource(R.string.backup_auto_staggered_info),
                default = SettingsDefaults.autoBackup.staggered,
            ) { scope.launch { repository.setAutoBackupStaggered(it) } }
        }
        item(visible = autoOn && configured, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.backup_auto_charging_title,
                stringResource(R.string.backup_auto_charging_subtitle),
                settings.watch { it.autoBackup.requireCharging },
                info = stringResource(R.string.backup_auto_charging_info),
                default = SettingsDefaults.autoBackup.requireCharging,
            ) { on ->
                scope.launch {
                    repository.setAutoBackupRequireCharging(on)
                    resync()
                }
            }
        }
        // A folder is storage on this device: a network condition there would
        // only ever delay a backup that costs nothing.
        item(visible = autoOn && configured && overNetwork, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.backup_auto_unmetered_title,
                stringResource(R.string.backup_auto_unmetered_subtitle),
                settings.watch { it.autoBackup.requireUnmetered },
                info = stringResource(R.string.backup_auto_unmetered_info),
                default = SettingsDefaults.autoBackup.requireUnmetered,
            ) { on ->
                scope.launch {
                    repository.setAutoBackupRequireUnmetered(on)
                    resync()
                }
            }
        }
    }

    // #970: a second, smaller backup on a schedule of its own.
    val frequentOn = settings.watch { it.autoBackup.frequent.enabled }
    val hasPassphrase = settings.watch { it.autoBackup.passphrase.isNotEmpty() }
    if (autoOn && configured) {
        SettingsGroup(
            stringResource(R.string.backup_frequent_title),
            info = stringResource(R.string.backup_frequent_info),
            level = SettingLevel.STANDARD,
        ) {
            item {
                ToggleSetting(
                    R.string.backup_frequent_enabled_title,
                    stringResource(R.string.backup_frequent_enabled_subtitle),
                    frequentOn,
                    default = SettingsDefaults.autoBackup.frequent.enabled,
                ) { on ->
                    scope.launch {
                        repository.setFrequentBackupEnabled(on)
                        resync()
                    }
                }
            }
            item(visible = frequentOn) {
                SliderSetting(
                    R.string.backup_frequent_interval_title,
                    value = intervalSliderIndex(settings.watch { it.autoBackup.frequent.intervalHours }).toFloat(),
                    range = 0f..(AutoBackupIntervals.size - 1).toFloat(),
                    display = { backupIntervalLabel(context, intervalAt(it)) },
                    typed = false,
                    default = intervalSliderIndex(SettingsDefaults.autoBackup.frequent.intervalHours).toFloat(),
                ) { index ->
                    scope.launch {
                        repository.setFrequentBackupIntervalHours(intervalAt(index))
                        resync()
                    }
                }
            }
            item(visible = frequentOn) {
                SliderSetting(
                    R.string.backup_frequent_keep_title,
                    subtitle = stringResource(R.string.backup_frequent_keep_subtitle),
                    value = settings.watch { it.autoBackup.frequent.keep }.toFloat(),
                    range = AutoBackupKeepRange.first.toFloat()..AutoBackupKeepRange.last.toFloat(),
                    display = { kept ->
                        context.resources.getQuantityString(R.plurals.backup_auto_keep_value, kept.roundToInt(), kept.roundToInt())
                    },
                    default = SettingsDefaults.autoBackup.frequent.keep.toFloat(),
                ) { kept -> scope.launch { repository.setFrequentBackupKeep(kept.roundToInt()) } }
            }
            item(visible = frequentOn) {
                ToggleSetting(
                    R.string.backup_frequent_encrypt_title,
                    stringResource(
                        if (hasPassphrase) R.string.backup_frequent_encrypt_subtitle else R.string.backup_frequent_encrypt_needs_passphrase,
                    ),
                    settings.watch { it.autoBackup.frequent.encrypt } && hasPassphrase,
                    enabled = hasPassphrase,
                    default = SettingsDefaults.autoBackup.frequent.encrypt,
                ) { on -> scope.launch { repository.setFrequentBackupEncrypt(on) } }
            }
        }
        if (frequentOn) {
            SettingsGroup(stringResource(R.string.backup_frequent_contents_title), level = SettingLevel.STANDARD) {
                // No API-key switch: the frequent backup never carries them.
                sectionRows(
                    settings = settings,
                    sections = { it.autoBackup.frequent.sectionSet },
                    defaults = FrequentBackup.DEFAULT_SECTIONS,
                    secrets = null,
                ) { section, on ->
                    scope.launch {
                        val current = repository.settings.first().autoBackup.frequent.sectionSet
                        repository.setFrequentBackupSections(if (on) current + section else current - section)
                    }
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_auto_targets_title),
        info = stringResource(R.string.backup_auto_targets_info),
    ) {
        if (locations.isEmpty()) {
            item { WmRow(title = stringResource(R.string.backup_auto_targets_none)) }
        }
        for (location in locations) {
            item {
                LocationCheckRow(location, location.backup) { on ->
                    scope.launch {
                        repository.upsertBackupLocation(location.copy(backup = on))
                        resync()
                    }
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_auto_protect_title),
        info = stringResource(R.string.backup_auto_protect_info),
    ) {
        item {
            ToggleSetting(
                R.string.backup_auto_encrypt_title,
                stringResource(R.string.backup_auto_encrypt_subtitle),
                encryptOn,
                info = stringResource(R.string.backup_auto_encrypt_info),
                default = SettingsDefaults.autoBackup.encrypt,
            ) { on -> scope.launch { repository.setAutoBackupEncrypt(on) } }
        }
        item(visible = encryptOn) {
            StoredTextField(
                label = stringResource(R.string.backup_auto_passphrase_label),
                value = settings.watch { it.autoBackup.passphrase },
                supporting = "",
                password = true,
            ) { entered -> scope.launch { repository.setAutoBackupPassphrase(entered) } }
        }
    }

    // The one thing here that has to be said out loud: these sections send
    // words the user typed, and things they copied, off the device on a timer.
    if (personal && !encrypted) {
        StateBanner(stringResource(R.string.backup_auto_personal_warning), tone = BannerTone.WARNING)
    }

    SettingsGroup(stringResource(R.string.backup_auto_contents_title), level = SettingLevel.STANDARD) {
        sectionRows(
            settings = settings,
            sections = { it.autoBackup.sectionSet },
            defaults = AutoBackupSettings.DEFAULT_SECTIONS,
            secrets = { s: KeyboardSettings -> s.autoBackup.backupIncludeSecrets } to setKeys,
            settingsIncluded = settingsIncluded,
            secretsSubtitle = if (encrypted) {
                R.string.backup_include_secrets_subtitle
            } else {
                R.string.backup_secrets_unprotected_subtitle
            },
        ) { section, on ->
            scope.launch {
                val current = repository.settings.first().autoBackup.sectionSet
                repository.setAutoBackupSections(if (on) current + section else current - section)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * Sync between devices: whether this device syncs, when, where, and what.
 * The explanation of how it works is the screen's subtitle; the rules about
 * what stays on one device sit behind the "What syncs" heading.
 */
@Composable
internal fun BackupSyncSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows the groups hold; each row reads its own value.
    val syncOn = settings.watch { it.autoBackup.sync.enabled }
    val syncMode = settings.watch { it.autoBackup.sync.mode }
    val locations = settings.watch { it.autoBackup.locations }
    val encrypted = settings.watch { it.autoBackup.encrypt && it.autoBackup.passphrase.isNotEmpty() }
    var message by remember { mutableStateOf<String?>(null) }
    val reduceMotion = LocalReduceMotion.current
    var syncPhase by remember { mutableStateOf(RunPhase.IDLE) }
    val syncing = syncPhase == RunPhase.RUNNING
    LaunchedEffect(syncPhase) {
        if (syncPhase == RunPhase.DONE) {
            delay(DONE_HOLD_MS)
            syncPhase = RunPhase.IDLE
        }
    }
    val setKeys = rememberKeysSwitch(encrypted) { on -> scope.launch { repository.setSyncIncludeSecrets(on) } }
    var pickingGroup by remember { mutableStateOf(false) }
    // The location just ticked, when it holds groups this device is not in.
    var joining by remember { mutableStateOf<BackupLocation?>(null) }

    fun runNow() {
        syncPhase = RunPhase.RUNNING
        scope.launch {
            val outcome = SyncRunner.run(context, repository, force = true)
            val worked = outcome.syncWorked()
            syncPhase = if (worked) RunPhase.DONE else RunPhase.IDLE
            if (worked && !reduceMotion) delay(CHECK_BEAT_MS)
            message = syncOutcomeText(context, outcome, repository.settings.first().autoBackup)
        }
    }

    SettingsGroup {
        item {
            val hasActive = settings.watch { it.autoBackup.activeLocations.isNotEmpty() }
            val hasTargets = settings.watch { s -> s.autoBackup.sync.targets(s.autoBackup.locations).isNotEmpty() }
            ToggleSetting(
                R.string.backup_sync_enabled_title,
                stringResource(
                    when {
                        !hasActive -> R.string.backup_auto_needs_location
                        syncOn && !hasTargets -> R.string.backup_sync_needs_target
                        else -> R.string.backup_sync_enabled_subtitle
                    },
                ),
                syncOn && hasActive,
                info = stringResource(R.string.backup_sync_first_note),
                enabled = hasActive,
                default = SettingsDefaults.autoBackup.sync.enabled,
            ) { on ->
                val auto = settings.value.autoBackup
                val active = auto.activeLocations
                val targets = auto.sync.targets(auto.locations)
                scope.launch {
                    // Turned on with nothing ticked: tick the first usable
                    // location, the focused default, rather than switch on a
                    // sync that has nowhere to go.
                    if (on && targets.isEmpty()) active.firstOrNull()?.let { repository.setSyncLocation(it.id, true) }
                    repository.setSyncEnabled(on)
                    // The first pass straight away, with the screen still
                    // open to say how it went.
                    if (on) runNow()
                }
            }
        }
        item(visible = syncOn, level = SettingLevel.STANDARD) {
            PauseRow(
                pausedUntilMs = settings.watch { it.autoBackup.sync.pausedUntilMs },
                title = R.string.sync_pause_title,
                subtitle = R.string.sync_pause_subtitle,
                pausedTitle = R.string.sync_paused_title,
                onPause = { until -> scope.launch { repository.setSyncPausedUntil(until) } },
                onResume = {
                    scope.launch {
                        repository.setSyncPausedUntil(0L)
                        // What the other devices changed meanwhile, now.
                        SyncScheduler.requestSoon(context, repository.settings.first().autoBackup, delayMs = 0L)
                    }
                },
            )
        }
        item(visible = syncOn, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.backup_sync_mode_title,
                options = listOf(
                    SyncMode.SOON to stringResource(R.string.backup_sync_mode_soon),
                    SyncMode.SCHEDULE to stringResource(R.string.backup_sync_mode_schedule),
                    SyncMode.MANUAL to stringResource(R.string.backup_sync_mode_manual),
                ),
                selected = syncMode,
                default = SettingsDefaults.autoBackup.sync.mode,
                detail = { mode ->
                    ChoiceDetail(
                        stringResource(
                            when (mode) {
                                SyncMode.SOON -> R.string.backup_sync_mode_soon_desc
                                SyncMode.SCHEDULE -> R.string.backup_sync_mode_schedule_desc
                                SyncMode.MANUAL -> R.string.backup_sync_mode_manual_desc
                            },
                        ),
                    )
                },
            ) { mode -> scope.launch { repository.setSyncMode(mode) } }
        }
        item(visible = syncOn && syncMode == SyncMode.SCHEDULE, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.backup_sync_interval_title,
                value = intervalSliderIndex(settings.watch { it.autoBackup.sync.intervalHours }).toFloat(),
                range = 0f..(AutoBackupIntervals.size - 1).toFloat(),
                display = { backupIntervalLabel(context, intervalAt(it)) },
                // Hours, then days: a bare number cannot say which.
                typed = false,
                default = intervalSliderIndex(SettingsDefaults.autoBackup.sync.intervalHours).toFloat(),
            ) { index -> scope.launch { repository.setSyncIntervalHours(intervalAt(index)) } }
        }
        item(visible = syncOn, level = SettingLevel.ADVANCED) {
            val keysProtected = settings.watch { it.autoBackup.encrypt && it.autoBackup.passphrase.isNotEmpty() }
            ToggleSetting(
                R.string.backup_sync_secrets_title,
                stringResource(
                    if (keysProtected) R.string.backup_sync_secrets_subtitle else R.string.backup_sync_secrets_unprotected,
                ),
                settings.watch { it.autoBackup.sync.includeSecrets },
                default = SettingsDefaults.autoBackup.sync.includeSecrets,
            ) { on -> setKeys(on) }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_sync_targets_title),
        info = stringResource(R.string.backup_sync_targets_info),
    ) {
        if (locations.isEmpty()) {
            item { WmRow(title = stringResource(R.string.backup_auto_targets_none)) }
        }
        for (location in locations) {
            item {
                LocationCheckRow(location, settings.watch { location.id in it.autoBackup.sync.locationIds }) { on ->
                    scope.launch {
                        repository.setSyncLocation(location.id, on)
                        // Joining a shared folder: when the devices already
                        // there sync in groups this one is not in, ask which
                        // to join before the first pass picks for the user.
                        if (on && location.type != BackupDestination.NEARBY) {
                            val groups = SyncRunner.groupsAt(context, location).getOrNull().orEmpty()
                            val mine = repository.settings.first().autoBackup.sync.group
                            if (groups.isNotEmpty() && mine !in groups) {
                                joining = location
                                pickingGroup = true
                            }
                        }
                    }
                }
            }
        }
        item(level = SettingLevel.ADVANCED) {
            val group = settings.watch { it.autoBackup.sync.group }
            WmRow(
                title = stringResource(R.string.backup_sync_group_title),
                subtitle = if (group.isEmpty()) {
                    stringResource(R.string.backup_sync_group_none)
                } else {
                    stringResource(R.string.backup_sync_group_value, group)
                },
                icon = Icons.Outlined.Group,
                onClick = {
                    joining = null
                    pickingGroup = true
                },
            )
        }
        // A computer paired over KDE Connect turns into a location above,
        // ticked; this is the one switch for whether that happens.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.backup_sync_paired_title,
                stringResource(R.string.backup_sync_paired_subtitle),
                settings.watch { it.autoBackup.sync.withPairedDevices },
                info = stringResource(R.string.backup_sync_paired_info),
                default = SettingsDefaults.autoBackup.sync.withPairedDevices,
            ) { on -> scope.launch { repository.setSyncWithPairedDevices(on) } }
        }
        // #928: mobile data is the user's to spend.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.backup_sync_wifi_only_title,
                stringResource(R.string.backup_sync_wifi_only_subtitle),
                settings.watch { it.autoBackup.sync.wifiOnly },
                default = SettingsDefaults.autoBackup.sync.wifiOnly,
            ) { on -> scope.launch { repository.setSyncWifiOnly(on) } }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_sync_contents_title),
        info = stringResource(R.string.backup_sync_local_note),
        level = SettingLevel.STANDARD,
    ) {
        sectionRows(
            settings = settings,
            sections = { it.autoBackup.sync.sectionSet },
            defaults = SyncSettings.DEFAULT_SECTIONS,
            secrets = null,
            syncOnly = true,
        ) { section, on ->
            scope.launch {
                val current = repository.settings.first().autoBackup.sync.sectionSet
                repository.setSyncSections(if (on) current + section else current - section)
            }
        }
    }

    // What to keep out of the synced settings, so gone with them.
    val syncsSettings = settings.watch { ConfigBackup.Section.SETTINGS in it.autoBackup.sync.sectionSet }
    SettingsGroup(
        stringResource(R.string.backup_sync_keep_local_title),
        info = stringResource(R.string.backup_sync_keep_local_info),
        level = SettingLevel.ADVANCED,
    ) {
        for (group in SyncKeys.LocalGroup.entries) {
            item(visible = syncsSettings) {
                ToggleSetting(
                    when (group) {
                        SyncKeys.LocalGroup.TOOLBAR -> R.string.backup_sync_keep_toolbar_title
                        SyncKeys.LocalGroup.LAYOUTS -> R.string.backup_sync_keep_layouts_title
                        SyncKeys.LocalGroup.THEME -> R.string.backup_sync_keep_theme_title
                    },
                    stringResource(
                        when (group) {
                            SyncKeys.LocalGroup.TOOLBAR -> R.string.backup_sync_keep_toolbar_subtitle
                            SyncKeys.LocalGroup.THEME -> R.string.backup_sync_keep_theme_subtitle
                            else -> R.string.backup_sync_keep_layouts_subtitle
                        },
                    ),
                    settings.watch { group in it.autoBackup.sync.keepLocalGroups },
                    default = false,
                ) { on ->
                    scope.launch {
                        repository.setSyncKeepLocal(group, on)
                        // Let go of: take what the other devices have now,
                        // not whenever the next change happens to be made.
                        if (!on) SyncScheduler.requestSoon(context, repository.settings.first().autoBackup, delayMs = 0L)
                    }
                }
            }
        }
    }

    SettingsGroup {
        item {
            val canSync = settings.watch { s ->
                s.autoBackup.sync.enabled && s.autoBackup.sync.targets(s.autoBackup.locations).isNotEmpty()
            }
            FilledTonalButton(
                enabled = canSync && !syncing,
                onClick = { runNow() },
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(if (reduceMotion) Modifier else Modifier.animateContentSize()),
            ) {
                RunGlyph(syncPhase, MaterialTheme.colorScheme.primary)
                Text(stringResource(if (syncing) R.string.backup_hub_syncing else R.string.backup_hub_sync_now))
            }
        }
    }
    val syncError = settings.watch { s ->
        s.autoBackup.sync.lastError.takeIf { it.isNotEmpty() }?.let { syncErrorText(context, it, s.autoBackup) }
    }
    val lastSyncAtMs = settings.watch { it.autoBackup.sync.lastRunAtMs }
    StateBanner(
        syncError ?: if (lastSyncAtMs > 0L) {
            stringResource(
                R.string.backup_hub_last_sync,
                DateUtils.getRelativeTimeSpanString(lastSyncAtMs).toString(),
            )
        } else {
            stringResource(R.string.backup_hub_never_synced)
        },
        tone = if (syncError != null) BannerTone.WARNING else BannerTone.INFO,
    )
    Spacer(Modifier.height(16.dp))

    if (pickingGroup) {
        val auto = settings.value.autoBackup
        SyncGroupDialog(
            current = auto.sync.group,
            locations = (auto.sync.targets(auto.locations) + listOfNotNull(joining))
                .filter { it.type != BackupDestination.NEARBY }
                .distinctBy { it.id },
            joining = joining,
            onSave = { entered ->
                pickingGroup = false
                joining = null
                scope.launch {
                    val changed = SyncRunner.joinGroup(context, repository, entered)
                    if (changed && repository.settings.first().autoBackup.sync.enabled) runNow()
                }
            },
            onDismiss = {
                pickingGroup = false
                joining = null
            },
        )
    }

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

/** What the one-off "Export to a file" puts in. The automatic backup and sync have their own lists. */
@Composable
internal fun BackupContentsSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // Decides whether the key switch is among the rows.
    val settingsIncluded = settings.watch {
        ConfigBackup.Section.SETTINGS in it.autoBackup.exportSectionSet ||
            ConfigBackup.Section.NOTES in it.autoBackup.exportSectionSet
    }
    SettingsGroup {
        sectionRows(
            settings = settings,
            sections = { it.autoBackup.exportSectionSet },
            defaults = AutoBackupSettings.DEFAULT_SECTIONS,
            secrets = { s: KeyboardSettings -> s.autoBackup.includeSecrets } to
                { on -> scope.launch { repository.setAutoBackupIncludeSecrets(on) } },
            settingsIncluded = settingsIncluded,
        ) { section, on ->
            scope.launch {
                val current = repository.settings.first().autoBackup.exportSectionSet
                repository.setExportSections(if (on) current + section else current - section)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** How tall a multi-line [StoredTextField] grows before it scrolls. */
private const val MULTI_LINE_MAX = 6

/** Whether a sync pass went through everywhere it was sent. */
private fun SyncRunner.Outcome.syncWorked(): Boolean =
    this is SyncRunner.Outcome.Done && failed.isEmpty()

/** The status card's cloud hopping: up a little, and back with a bounce. */
private suspend fun Animatable<Float, AnimationVector1D>.celebrate() {
    animateTo(HOP_SCALE, tween(durationMillis = HOP_UP_MS, easing = FastOutSlowInEasing))
    animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
}

/** How long a finished run's tick stays on its button. */
private const val DONE_HOLD_MS = 1_800L

/** How long the result waits for the tick to land: the tick's own draw, and a breath. */
private const val CHECK_BEAT_MS = CHECK_DRAW_MS + 120L

private const val CARD_TINT_MS = 300
private const val HOP_SCALE = 1.18f
private const val HOP_UP_MS = 140

package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.settings.AutoBackupIntervals
import com.wasimaster.wmkeyboard.core.settings.AutoBackupKeepRange
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LocationContents
import com.wasimaster.wmkeyboard.core.settings.LocationSchedule
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.needsNetwork
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One location's own page for the automatic backup: whether it gets backups,
 * and its schedule and contents, each either the shared ones from the
 * Automatic backup screen or its own. The same shape as a provider's page in
 * a tool's list: the list says where things go, the page says how.
 */
internal const val BACKUP_LOCATION_ROUTE = "backup/location/{id}"

internal fun backupLocationRoute(id: String): String = "backup/location/$id"

/** "Every day, 5 kept" for a schedule, as the hub's summary says it. */
internal fun scheduleSummary(context: Context, intervalHours: Int, keep: Int): String = context.getString(
    R.string.backup_hub_auto_summary,
    backupIntervalLabel(context, intervalHours),
    context.resources.getQuantityString(R.plurals.backup_auto_keep_value, keep, keep),
)

/**
 * A location on the Automatic backup screen's "Back up to" list: the tick
 * says whether backups go there, and the rest of the row opens its page,
 * where it can run on a schedule and with contents of its own.
 */
@Composable
internal fun LocationTargetRow(
    location: BackupLocation,
    onChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val own = buildList {
        location.schedule?.let { add(scheduleSummary(context, it.intervalHours, it.keep)) }
        if (location.contents != null) add(stringResource(R.string.backup_location_own_contents_short))
    }
    WmRow(
        title = location.title(context),
        subtitle = when {
            !location.configured -> stringResource(needsSetupRes(location.type))
            own.isNotEmpty() -> stringResource(R.string.backup_location_own_summary, own.joinToString(" · "))
            else -> stringResource(R.string.backup_location_shared_summary)
        },
        leading = { WmIconTile(icon = destinationIcon(location.type), accent = location.type.accent) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = location.backup, onCheckedChange = onChange)
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = stringResource(R.string.backup_location_page_open, location.title(context)),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onOpen,
    )
}

/** The page behind [BACKUP_LOCATION_ROUTE]. */
@Composable
internal fun BackupLocationSettings(repository: SettingsRepository, settings: LiveSettings, id: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val location = settings.watch { s -> s.autoBackup.locations.firstOrNull { it.id == id } }
    if (location == null) {
        // Removed on another screen, or a stale link.
        StateBanner(stringResource(R.string.backup_location_page_gone), tone = BannerTone.INFO)
        return
    }
    val shared = settings.watch { s -> LocationSchedule.from(s.autoBackup) }
    val hasPassphrase = settings.watch { it.autoBackup.passphrase.isNotEmpty() }

    /** Changes this location, with the shared settings at hand, and puts the job in step. */
    fun update(change: (BackupLocation, AutoBackupSettings) -> BackupLocation) = scope.launch {
        val auto = repository.settings.first().autoBackup
        repository.updateBackupLocation(id) { change(it, auto) }
        AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
    }

    /** Changes this location's own schedule; only called while it has one. */
    fun schedule(change: (LocationSchedule) -> LocationSchedule) =
        update { l, auto -> l.copy(schedule = change(l.schedule ?: LocationSchedule.from(auto))) }

    fun contents(change: (LocationContents) -> LocationContents) =
        update { l, auto -> l.copy(contents = change(l.contents ?: LocationContents.from(auto))) }

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.backup_location_backup_here_title,
                stringResource(R.string.backup_location_backup_here_subtitle),
                location.backup,
                default = true,
            ) { on -> update { l, _ -> l.copy(backup = on) } }
        }
    }

    val ownSchedule = location.schedule
    SettingsGroup(
        stringResource(R.string.backup_location_schedule_group),
        info = stringResource(R.string.backup_location_schedule_info),
    ) {
        item {
            ToggleSetting(
                R.string.backup_location_own_schedule_title,
                if (ownSchedule != null) {
                    stringResource(R.string.backup_location_own_schedule_on)
                } else {
                    stringResource(
                        R.string.backup_location_own_schedule_off,
                        scheduleSummary(context, shared.intervalHours, shared.keep),
                    )
                },
                ownSchedule != null,
                default = false,
            ) { on -> update { l, auto -> l.copy(schedule = if (on) LocationSchedule.from(auto) else null) } }
        }
        if (ownSchedule != null) {
            item {
                SliderSetting(
                    R.string.backup_auto_interval_title,
                    value = intervalSliderIndex(ownSchedule.intervalHours).toFloat(),
                    range = 0f..(AutoBackupIntervals.size - 1).toFloat(),
                    display = { backupIntervalLabel(context, intervalAt(it)) },
                    typed = false,
                    default = intervalSliderIndex(shared.intervalHours).toFloat(),
                ) { index -> schedule { it.copy(intervalHours = intervalAt(index)) } }
            }
            item {
                SliderSetting(
                    R.string.backup_auto_keep_title,
                    subtitle = stringResource(R.string.backup_auto_keep_subtitle),
                    value = ownSchedule.keep.toFloat(),
                    range = AutoBackupKeepRange.first.toFloat()..AutoBackupKeepRange.last.toFloat(),
                    display = { kept ->
                        context.resources.getQuantityString(R.plurals.backup_auto_keep_value, kept.roundToInt(), kept.roundToInt())
                    },
                    default = shared.keep.toFloat(),
                ) { kept -> schedule { it.copy(keep = kept.roundToInt()) } }
            }
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.backup_auto_staggered_title,
                    stringResource(R.string.backup_auto_staggered_subtitle),
                    ownSchedule.staggered,
                    info = stringResource(R.string.backup_auto_staggered_info),
                    default = shared.staggered,
                ) { on -> schedule { it.copy(staggered = on) } }
            }
            item {
                ToggleSetting(
                    R.string.backup_auto_charging_title,
                    stringResource(R.string.backup_auto_charging_subtitle),
                    ownSchedule.requireCharging,
                    info = stringResource(R.string.backup_auto_charging_info),
                    default = shared.requireCharging,
                ) { on -> schedule { it.copy(requireCharging = on) } }
            }
            // A folder is storage on this device: a network condition there
            // would only ever delay a backup that costs nothing.
            item(visible = location.type.needsNetwork) {
                ToggleSetting(
                    R.string.backup_auto_unmetered_title,
                    stringResource(R.string.backup_auto_unmetered_subtitle),
                    ownSchedule.requireUnmetered,
                    info = stringResource(R.string.backup_auto_unmetered_info),
                    default = shared.requireUnmetered,
                ) { on -> schedule { it.copy(requireUnmetered = on) } }
            }
        }
    }

    val ownContents = location.contents
    val setKeys = rememberKeysSwitch(ownContents?.encrypt == true && hasPassphrase) { on ->
        contents { it.copy(includeSecrets = on) }
    }
    SettingsGroup(
        stringResource(R.string.backup_location_contents_group),
        info = stringResource(R.string.backup_location_contents_info),
    ) {
        item {
            ToggleSetting(
                R.string.backup_location_own_contents_title,
                stringResource(
                    if (ownContents != null) R.string.backup_location_own_contents_on else R.string.backup_location_own_contents_off,
                ),
                ownContents != null,
                default = false,
            ) { on -> update { l, auto -> l.copy(contents = if (on) LocationContents.from(auto) else null) } }
        }
        if (ownContents != null) {
            item {
                ToggleSetting(
                    R.string.backup_auto_encrypt_title,
                    stringResource(
                        if (hasPassphrase) R.string.backup_auto_encrypt_subtitle else R.string.backup_location_encrypt_needs_passphrase,
                    ),
                    ownContents.encrypt && hasPassphrase,
                    enabled = hasPassphrase,
                    default = false,
                ) { on -> contents { it.copy(encrypt = on) } }
            }
            val ofThis = { s: KeyboardSettings ->
                s.autoBackup.locations.firstOrNull { it.id == id }?.contents
            }
            sectionRows(
                settings = settings,
                sections = { s -> ofThis(s)?.sectionSet.orEmpty() },
                defaults = AutoBackupSettings.DEFAULT_SECTIONS,
                secrets = { s: KeyboardSettings -> ofThis(s)?.includeSecrets == true } to setKeys,
                settingsIncluded = ConfigBackup.Section.SETTINGS in ownContents.sectionSet ||
                    ConfigBackup.Section.NOTES in ownContents.sectionSet,
                secretsSubtitle = if (ownContents.encrypt && hasPassphrase) {
                    R.string.backup_include_secrets_subtitle
                } else {
                    R.string.backup_secrets_unprotected_subtitle
                },
            ) { section, on ->
                contents { c ->
                    val ids = if (on) c.sections + section.id else c.sections - section.id
                    c.copy(sections = ids)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

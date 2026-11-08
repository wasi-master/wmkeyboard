package com.wasimaster.wmkeyboard.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.clock.ClockConfig
import com.wasimaster.wmkeyboard.core.clock.ClockEngine
import com.wasimaster.wmkeyboard.core.clock.ClockNotifications
import com.wasimaster.wmkeyboard.core.clock.ClockPrefs
import com.wasimaster.wmkeyboard.core.clock.InsertFormat
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.ime.R as ImeR

/**
 * Tools → Clock: how clocks read, what an alarm does when it rings, the
 * Pomodoro rhythm, the planner's working hours, how long history is kept,
 * and the three grants a reliable alarm needs.
 *
 * The values are [ClockConfig] in device-protected preferences rather than
 * settings in the DataStore, because the alarm receiver and the ring service
 * read them before the first unlock. So they are written straight through
 * [ClockPrefs] here, not through the repository.
 */
@Composable
internal fun ClockToolSettings(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val config by ClockPrefs.state(context).collectAsState()
    val defaults = remember { ClockConfig() }
    fun set(change: (ClockConfig) -> ClockConfig) = ClockPrefs.update(context, change)

    SettingsGroup {
        item {
            NavRow(
                R.string.clock_open_title,
                stringResource(R.string.clock_open_subtitle),
                route = ClockEngine.ROUTE,
            ) { onNavigate(ClockEngine.ROUTE) }
        }
        item(level = SettingLevel.STANDARD) { HomeScreenShortcutRow(R.string.home_shortcut_clock_label, ClockEngine.ROUTE) }
    }

    ClockGrantsGroup()

    SettingsGroup(stringResource(R.string.clock_display_group)) {
        item {
            ChoiceSetting(
                title = R.string.clock_hour_format_title,
                options = listOf(
                    null to stringResource(R.string.clock_hour_format_system),
                    false to stringResource(R.string.clock_hour_format_12),
                    true to stringResource(R.string.clock_hour_format_24),
                ),
                selected = config.hour24,
                default = defaults.hour24,
            ) { v -> set { it.copy(hour24 = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clock_show_seconds_title,
                stringResource(R.string.clock_show_seconds_subtitle),
                config.showSeconds,
                default = defaults.showSeconds,
            ) { v -> set { it.copy(showSeconds = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clock_sort_zones_title,
                stringResource(R.string.clock_sort_zones_subtitle),
                config.sortZonesByTime,
                default = defaults.sortZonesByTime,
            ) { v -> set { it.copy(sortZonesByTime = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clock_pin_local_title,
                stringResource(R.string.clock_pin_local_subtitle),
                config.pinLocalZone,
                default = defaults.pinLocalZone,
            ) { v -> set { it.copy(pinLocalZone = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clock_pin_utc_title,
                stringResource(R.string.clock_pin_utc_subtitle),
                config.pinUtc,
                default = defaults.pinUtc,
            ) { v -> set { it.copy(pinUtc = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clock_zones_grid_title,
                stringResource(R.string.clock_zones_grid_subtitle),
                config.zonesGrid,
                default = defaults.zonesGrid,
            ) { v -> set { it.copy(zonesGrid = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.clock_insert_format_title,
                subtitle = stringResource(R.string.clock_insert_format_subtitle),
                options = InsertFormat.entries.map { it.id to stringResource(insertFormatLabel(it)) },
                selected = config.insertFormat,
                default = defaults.insertFormat,
            ) { v -> set { it.copy(insertFormat = v) } }
        }
    }

    val minutes: (Float) -> String = { v -> context.resources.getQuantityString(R.plurals.clock_minutes_value, v.roundToInt(), v.roundToInt()) }
    val seconds: (Float) -> String = { v ->
        val n = v.roundToInt()
        if (n == 0) context.getString(R.string.clock_fade_off) else context.resources.getQuantityString(R.plurals.clock_seconds_value, n, n)
    }

    // Sound, vibration and fade-in are the ring service's to play. Where the
    // build has none (Play), the alarm channel plays them, so its system page
    // is where they are set.
    val service = ChannelFeatures.CLOCK_RING_SERVICE
    SettingsGroup(stringResource(R.string.clock_alarms_group), info = stringResource(R.string.clock_alarms_info)) {
        item(visible = !service && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NavRow(R.string.clock_channel_sound_title, stringResource(R.string.clock_channel_sound_subtitle)) {
                ClockNotifications.ensureRingChannels(context)
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ClockNotifications.CHANNEL_RING_SOUND),
                    )
                }
            }
        }
        item(visible = service) {
            SoundRow(
                title = R.string.clock_alarm_sound_title,
                uri = config.alarmSound,
            ) { v -> set { it.copy(alarmSound = v) } }
        }
        item(visible = service, level = SettingLevel.STANDARD) {
            SoundRow(
                title = R.string.clock_timer_sound_title,
                uri = config.timerSound,
                followsAlarm = true,
            ) { v -> set { it.copy(timerSound = v) } }
        }
        item(visible = service, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clock_vibrate_title,
                stringResource(R.string.clock_vibrate_subtitle),
                config.vibrate,
                default = defaults.vibrate,
            ) { v -> set { it.copy(vibrate = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.clock_snooze_title,
                value = config.snoozeMinutes.toFloat(),
                range = 1f..30f,
                display = minutes,
                default = defaults.snoozeMinutes.toFloat(),
            ) { v -> set { it.copy(snoozeMinutes = v.roundToInt()) } }
        }
        item(visible = service, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.clock_fade_title,
                stringResource(R.string.clock_fade_subtitle),
                value = config.fadeInSeconds.toFloat(),
                range = 0f..60f,
                display = seconds,
                default = defaults.fadeInSeconds.toFloat(),
            ) { v -> set { it.copy(fadeInSeconds = v.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.clock_auto_silence_title,
                stringResource(R.string.clock_auto_silence_subtitle),
                value = config.autoSilenceMinutes.toFloat(),
                range = 1f..30f,
                display = minutes,
                default = defaults.autoSilenceMinutes.toFloat(),
            ) { v -> set { it.copy(autoSilenceMinutes = v.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.clock_auto_snooze_title,
                stringResource(R.string.clock_auto_snooze_subtitle),
                // The last stop is "until answered" (#970): an alarm too
                // important to count as missed while nobody is there.
                value = config.autoSnoozes.coerceAtMost(AUTO_SNOOZE_STOPS).toFloat(),
                range = 0f..AUTO_SNOOZE_STOPS.toFloat(),
                display = { v ->
                    val n = v.roundToInt()
                    when (n) {
                        0 -> context.getString(R.string.clock_fade_off)
                        AUTO_SNOOZE_STOPS -> context.getString(R.string.clock_auto_snooze_forever)
                        else -> context.resources.getQuantityString(R.plurals.clock_times_value, n, n)
                    }
                },
                default = defaults.autoSnoozes.toFloat(),
            ) { v ->
                val n = v.roundToInt()
                set { it.copy(autoSnoozes = if (n >= AUTO_SNOOZE_STOPS) ClockConfig.SNOOZE_UNTIL_ANSWERED else n) }
            }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.clock_upcoming_title,
                subtitle = stringResource(R.string.clock_upcoming_subtitle),
                options = listOf(0, 15, 30, 60, 120).map { m ->
                    m to if (m == 0) stringResource(R.string.clock_fade_off) else minutes(m.toFloat())
                },
                selected = config.upcomingNoticeMinutes,
                default = defaults.upcomingNoticeMinutes,
            ) { v -> set { it.copy(upcomingNoticeMinutes = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clock_snoozed_notice_title,
                stringResource(R.string.clock_snoozed_notice_subtitle),
                config.snoozedNotice,
                default = defaults.snoozedNotice,
            ) { v -> set { it.copy(snoozedNotice = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.clock_missed_notice_title,
                stringResource(R.string.clock_missed_notice_subtitle),
                config.missedNotice,
                default = defaults.missedNotice,
            ) { v -> set { it.copy(missedNotice = v) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.clock_volume_keys_title,
                stringResource(R.string.clock_volume_keys_subtitle),
                config.volumeKeysSnooze,
                default = defaults.volumeKeysSnooze,
            ) { v -> set { it.copy(volumeKeysSnooze = v) } }
        }
    }

    SettingsGroup(
        stringResource(R.string.clock_pomodoro_group),
        info = stringResource(R.string.clock_pomodoro_info),
        level = SettingLevel.STANDARD,
    ) {
        item {
            SliderSetting(
                R.string.clock_focus_title,
                value = config.focusMinutes.toFloat(),
                range = 5f..90f,
                display = minutes,
                default = defaults.focusMinutes.toFloat(),
            ) { v -> set { it.copy(focusMinutes = v.roundToInt()) } }
        }
        item {
            SliderSetting(
                R.string.clock_short_break_title,
                value = config.shortBreakMinutes.toFloat(),
                range = 1f..30f,
                display = minutes,
                default = defaults.shortBreakMinutes.toFloat(),
            ) { v -> set { it.copy(shortBreakMinutes = v.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.clock_long_break_title,
                value = config.longBreakMinutes.toFloat(),
                range = 5f..60f,
                display = minutes,
                default = defaults.longBreakMinutes.toFloat(),
            ) { v -> set { it.copy(longBreakMinutes = v.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.clock_long_every_title,
                value = config.longBreakEvery.toFloat(),
                range = 2f..8f,
                steps = wholeSteps(2f..8f),
                display = { v -> context.resources.getQuantityString(R.plurals.clock_sessions_value, v.roundToInt(), v.roundToInt()) },
                default = defaults.longBreakEvery.toFloat(),
            ) { v -> set { it.copy(longBreakEvery = v.roundToInt()) } }
        }
    }

    val clockText: (Float) -> String = { v -> halfHourText(context, v.roundToInt(), config.is24(android.text.format.DateFormat.is24HourFormat(context))) }
    SettingsGroup(
        stringResource(R.string.clock_planner_group),
        info = stringResource(R.string.clock_planner_info),
        level = SettingLevel.ADVANCED,
    ) {
        item {
            SliderSetting(
                R.string.clock_work_start_title,
                value = (config.workStartMinutes / HALF_HOUR).toFloat(),
                range = 0f..47f,
                display = clockText,
                default = (defaults.workStartMinutes / HALF_HOUR).toFloat(),
            ) { v -> set { it.copy(workStartMinutes = v.roundToInt() * HALF_HOUR) } }
        }
        item {
            SliderSetting(
                R.string.clock_work_end_title,
                value = (config.workEndMinutes / HALF_HOUR).toFloat(),
                range = 1f..48f,
                display = clockText,
                default = (defaults.workEndMinutes / HALF_HOUR).toFloat(),
            ) { v -> set { it.copy(workEndMinutes = (v.roundToInt() * HALF_HOUR).coerceAtMost(DAY_MINUTES - 1)) } }
        }
    }

    SettingsGroup(stringResource(R.string.clock_history_group), level = SettingLevel.ADVANCED) {
        item {
            ChoiceSetting(
                title = R.string.clock_history_keep_title,
                options = HISTORY_DAYS.map { d ->
                    d to if (d == 0) stringResource(R.string.clock_history_keep_forever) else pluralStringResource(R.plurals.clock_days_value, d, d)
                },
                selected = config.historyDays,
                default = defaults.historyDays,
            ) { v -> set { it.copy(historyDays = v) } }
        }
    }
}

/**
 * The grants a reliable alarm needs, each row only while it is missing and
 * only on the Android versions that ask for it. Nothing at all once all are
 * there.
 */
@Composable
internal fun ClockGrantsGroup() {
    val exact = rememberGrantState(ClockEngine::canScheduleExact)
    val full = rememberGrantState(ClockNotifications::canUseFullScreen)
    val notify = rememberGrantState(WmNotifications::canPost)
    val askExact = rememberDisclosedSpecialAccess(SpecialAccess.EXACT_ALARMS)
    val askFull = rememberDisclosedSpecialAccess(SpecialAccess.FULL_SCREEN)
    val askNotify = rememberNotificationPermissionRequest { }
    SettingsGroup(stringResource(R.string.clock_grants_group), info = stringResource(R.string.clock_grants_info)) {
        item(visible = !exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            NavRow(R.string.clock_grant_exact_title, stringResource(R.string.clock_grant_exact_subtitle), onClick = askExact)
        }
        item(visible = !full && ChannelFeatures.CLOCK_FULL_SCREEN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NavRow(R.string.clock_grant_full_title, stringResource(R.string.clock_grant_full_subtitle), onClick = askFull)
        }
        item(visible = !notify) {
            NavRow(R.string.clock_grant_notify_title, stringResource(R.string.clock_grant_notify_subtitle), onClick = askNotify)
        }
    }
}

/**
 * A ringtone row: opens Android's own sound picker, which already lists the
 * phone's alarm sounds and lets the user add their own, and shows the name of
 * the one picked.
 */
@Composable
private fun SoundRow(
    title: Int,
    uri: String?,
    followsAlarm: Boolean = false,
    onPick: (String?) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = result.data?.pickedUri()
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        onPick(
            when {
                picked == null -> ""
                picked == default -> null
                else -> picked.toString()
            },
        )
    }
    val name = when {
        uri == null && followsAlarm -> stringResource(R.string.clock_sound_same_as_alarm)
        uri == null -> stringResource(R.string.clock_sound_default)
        uri.isEmpty() -> stringResource(R.string.clock_sound_silent)
        else -> remember(uri) { soundTitle(context, Uri.parse(uri)) } ?: stringResource(R.string.clock_sound_default)
    }
    NavRow(title, value = name) {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                when {
                    uri == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    uri.isEmpty() -> null
                    else -> Uri.parse(uri)
                },
            )
        runCatching { launcher.launch(intent) }
    }
}

@Suppress("DEPRECATION")
private fun Intent.pickedUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }

private fun soundTitle(context: Context, uri: Uri): String? =
    runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()

private fun halfHourText(context: Context, halfHours: Int, hour24: Boolean): String {
    val minutes = (halfHours * HALF_HOUR).coerceAtMost(DAY_MINUTES)
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minutes / 60 % 24)
        set(java.util.Calendar.MINUTE, minutes % 60)
    }
    val text = com.wasimaster.wmkeyboard.core.clock.ClockFormats.time(cal.timeInMillis, java.util.TimeZone.getDefault(), hour24)
    return if (minutes == DAY_MINUTES) context.getString(R.string.clock_midnight) else text
}

internal fun insertFormatLabel(format: InsertFormat): Int = when (format) {
    InsertFormat.TIME -> ImeR.string.ime_clock_format_time
    InsertFormat.DATE_TIME -> ImeR.string.ime_clock_format_date_time
    InsertFormat.DATE_LONG -> ImeR.string.ime_clock_format_date_long
    InsertFormat.TIME_PLACE -> ImeR.string.ime_clock_format_time_place
    InsertFormat.ISO -> ImeR.string.ime_clock_format_iso
    InsertFormat.RELATIVE -> ImeR.string.ime_clock_format_relative
    InsertFormat.DISCORD -> ImeR.string.ime_clock_format_discord
    InsertFormat.UNIX -> ImeR.string.ime_clock_format_unix
}

private const val HALF_HOUR = 30
private const val DAY_MINUTES = 24 * 60
private val HISTORY_DAYS = listOf(7, 30, 90, 365, 0)

/** The Snooze when nobody answers slider's last stop, which means until answered. */
private const val AUTO_SNOOZE_STOPS = 6

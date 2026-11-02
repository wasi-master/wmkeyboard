package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.AutomationExport
import com.wasimaster.wmkeyboard.core.settings.AutomationExportRecord
import com.wasimaster.wmkeyboard.core.settings.AutomationPermission
import com.wasimaster.wmkeyboard.core.settings.AutomationSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Allowed actions: one switch per [AutomationPermission], split into the
 * controls that are on by default and the ones that need more trust. Reached
 * from Privacy, under "Let other apps control the keyboard", and only drawn
 * there while that switch is on; opened some other way with it off, the rows
 * stay visible but greyed, under a banner that turns it on.
 */
@Composable
internal fun AutomationSettingsScreen(repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val automation by repository.automation.collectAsStateWithLifecycle(initialValue = AutomationSettings())
    if (!automation.enabled) {
        StateBanner(
            stringResource(R.string.automation_off_banner),
            action = stringResource(R.string.automation_off_banner_action),
            tone = BannerTone.WARNING,
        ) { scope.launch { repository.setAutomationEnabled(true) } }
    }
    val (basic, trust) = AutomationPermission.offeredEntries.partition { it.defaultAllowed }
    for ((title, info, permissions) in listOf(
        Triple(R.string.automation_group_basic_title, R.string.automation_group_basic_info, basic),
        Triple(R.string.automation_group_trust_title, R.string.automation_group_trust_info, trust),
    )) {
        SettingsGroup(stringResource(title), info = stringResource(info)) {
            for (permission in permissions) {
                // Off, the banner above is the page; the permissions come back with it.
                item(visible = automation.enabled) {
                    val (rowTitle, rowSubtitle) = automationRowStrings(permission)
                    ToggleSetting(
                        rowTitle,
                        stringResource(rowSubtitle),
                        permission in automation.allowed,
                        default = permission.defaultAllowed,
                    ) { scope.launch { repository.setAutomationAllowed(permission, it) } }
                }
            }
        }
    }
    ExportGroup(repository, automation)
}

/** Where [AutomationPermission.EXPORT_DATA] writes, and what it has done. Only while that row is on. */
@Composable
private fun ExportGroup(repository: SettingsRepository, automation: AutomationSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (taken) scope.launch { repository.setAutomationExportFolder(uri.toString()) }
    }
    val shown = automation.allows(AutomationPermission.EXPORT_DATA)
    SettingsGroup(
        stringResource(R.string.automation_export_group_title),
        info = stringResource(R.string.automation_export_group_info, AutomationExport.FILE_NAME),
    ) {
        item(visible = shown) {
            NavRow(
                R.string.automation_export_folder_title,
                exportFolderName(automation.exportFolder)
                    ?: stringResource(R.string.automation_export_folder_none),
            ) { pick.launch(null) }
        }
        item(visible = shown && automation.exportLog.isEmpty()) {
            WmRow(stringResource(R.string.automation_export_log_none))
        }
        for (record in automation.exportLog) {
            item(visible = shown) { ExportLogRow(record) }
        }
    }
}

@Composable
private fun ExportLogRow(record: AutomationExportRecord) {
    val context = LocalContext.current
    val sender = record.sender.ifEmpty { stringResource(R.string.automation_export_sender_unknown) }
    WmRow(
        DateUtils.formatDateTime(context, record.atMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
        subtitle = stringResource(
            if (record.failed) R.string.automation_export_log_failed else R.string.automation_export_log_written,
            record.sections,
            sender,
        ),
    )
}

/** The last part of the folder's path, the way a file manager names it; null with none picked. */
private fun exportFolderName(uri: String): String? = uri.takeIf { it.isNotEmpty() }?.let {
    runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)) }.getOrDefault(it)
        .substringAfterLast(':')
        .substringAfterLast('/')
        .ifEmpty { it }
}

/** The title and subtitle of [permission]'s row. */
internal fun automationRowStrings(permission: AutomationPermission): Pair<Int, Int> = when (permission) {
    AutomationPermission.LAYOUT -> R.string.automation_layout_title to R.string.automation_layout_subtitle
    AutomationPermission.MODE -> R.string.automation_mode_title to R.string.automation_mode_subtitle
    AutomationPermission.THEME -> R.string.automation_theme_title to R.string.automation_theme_subtitle
    AutomationPermission.SHOW_PIN -> R.string.automation_show_pin_title to R.string.automation_show_pin_subtitle
    AutomationPermission.FEEDBACK -> R.string.automation_feedback_title to R.string.automation_feedback_subtitle
    AutomationPermission.SAVERS -> R.string.automation_savers_title to R.string.automation_savers_subtitle
    AutomationPermission.POSITION -> R.string.automation_position_title to R.string.automation_position_subtitle
    AutomationPermission.OPEN_TOOL -> R.string.automation_open_tool_title to R.string.automation_open_tool_subtitle
    AutomationPermission.INCOGNITO_ON ->
        R.string.automation_incognito_on_title to R.string.automation_incognito_on_subtitle
    AutomationPermission.INCOGNITO_OFF ->
        R.string.automation_incognito_off_title to R.string.automation_incognito_off_subtitle
    AutomationPermission.WORDS -> R.string.automation_words_title to R.string.automation_words_subtitle
    AutomationPermission.BACKUP -> R.string.automation_backup_title to R.string.automation_backup_subtitle
    AutomationPermission.BACKUP_PAUSE ->
        R.string.automation_backup_pause_title to R.string.automation_backup_pause_subtitle
    AutomationPermission.EXPORT_DATA -> R.string.automation_export_title to R.string.automation_export_subtitle
    AutomationPermission.LAYOUT_EVENTS ->
        R.string.automation_layout_events_title to R.string.automation_layout_events_subtitle
    AutomationPermission.TYPE_TEXT -> R.string.automation_type_text_title to R.string.automation_type_text_subtitle
}

/** The "Other apps" group on Privacy: the master switch, and the way to the rows. */
@Composable
internal fun OtherAppsGroup(repository: SettingsRepository, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val automation by repository.automation.collectAsStateWithLifecycle(initialValue = AutomationSettings())
    SettingsGroup(stringResource(R.string.privacy_other_apps_group_title)) {
        item {
            ToggleSetting(
                R.string.automation_enabled_title,
                stringResource(R.string.automation_enabled_subtitle),
                automation.enabled,
                info = stringResource(R.string.automation_enabled_info),
                default = AutomationSettings().enabled,
            ) { scope.launch { repository.setAutomationEnabled(it) } }
        }
        if (automation.enabled) {
            item {
                NavRow(
                    R.string.automation_actions_title,
                    stringResource(R.string.automation_actions_subtitle),
                    value = stringResource(R.string.automation_actions_count, automation.allowed.count { it.offered }),
                    route = AUTOMATION_ROUTE,
                ) { onNavigate(AUTOMATION_ROUTE) }
            }
        }
    }
}

internal const val AUTOMATION_ROUTE = "automation"

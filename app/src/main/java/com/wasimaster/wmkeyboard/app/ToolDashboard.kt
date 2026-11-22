package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.storage.StorageCategories
import com.wasimaster.wmkeyboard.app.storage.StorageCategory
import com.wasimaster.wmkeyboard.app.storage.StorageEnv
import com.wasimaster.wmkeyboard.app.storage.diskUsage
import com.wasimaster.wmkeyboard.app.storage.storageRoots
import com.wasimaster.wmkeyboard.app.storage.storageRoute
import com.wasimaster.wmkeyboard.core.accessibility.SystemButtonsService
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataUsage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteForever
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.ToolUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * The tool dashboard (#1397): each tool with the permissions it uses, how
 * lately and how often it ran, and the space its downloads and data take,
 * with a way to take each permission back and to delete what it downloaded.
 *
 * What it does not do, and why: Android grants a permission to the whole
 * app, so one tool's use of the microphone cannot be switched off apart from
 * another's, only the app's grant, which is what the rows lead to. Nor can a
 * tool's code be uninstalled: the app is one package (Play's on-demand
 * modules aside, which Feature libraries already lists). Usage is the
 * keyboard's own count of runs; Android keeps no per-tool record of how
 * often a permission was used, and per-tool RAM, network and battery are
 * not measured (About › Resources shows the whole app's).
 */

internal const val TOOL_DASHBOARD_ROUTE = "tool_dashboard"
internal const val TOOL_DASHBOARD_TOOL_ROUTE = "tool_dashboard/{tool}"

internal fun toolDashboardRoute(tool: ToolbarTool): String = "tool_dashboard/${tool.name}"

/** A permission a tool reaches for: a runtime one with its disclosure, or a special grant read by [check]. */
internal enum class ToolGrant(
    @StringRes val title: Int,
    val disclosure: PermissionDisclosure? = null,
    val check: ((Context) -> Boolean)? = null,
) {
    MICROPHONE(R.string.privacy_permissions_mic_title, PermissionDisclosures.MICROPHONE),
    CAMERA(R.string.privacy_permissions_camera_title, PermissionDisclosures.CAMERA),
    LOCATION(R.string.privacy_permissions_location_title, PermissionDisclosures.LOCATION),
    CALENDAR(R.string.privacy_permissions_calendar_title, PermissionDisclosures.CALENDAR),
    CALENDAR_WRITE(R.string.privacy_permissions_calendar_write_title, PermissionDisclosures.CALENDAR_WRITE),
    CONTACTS(R.string.privacy_permissions_contacts_title, PermissionDisclosures.CONTACT_NAMES),
    PHOTOS(R.string.privacy_permissions_images_title, PermissionDisclosures.GALLERY),
    NEARBY(R.string.privacy_permissions_nearby_title, PermissionDisclosures.NEARBY_WIFI),
    NOTIFICATION_ACCESS(R.string.privacy_permissions_notifications_title, check = { hasNotificationAccess(it) }),
    SYSTEM_BUTTONS(R.string.privacy_permissions_system_buttons_title, check = { SystemButtonsService.isEnabled(it) }),
    ;

    fun granted(context: Context): Boolean = disclosure?.let {
        ContextCompat.checkSelfPermission(context, it.permission) == PackageManager.PERMISSION_GRANTED
    } ?: check?.invoke(context) ?: false
}

/**
 * What each tool reaches for, as the features ask for it: the tool's own
 * permission requests, read off their trampolines and settings rows.
 */
internal val ToolGrants: Map<ToolbarTool, List<ToolGrant>> = mapOf(
    ToolbarTool.VOICE to listOf(ToolGrant.MICROPHONE),
    ToolbarTool.MUSIC_ID to listOf(ToolGrant.MICROPHONE),
    ToolbarTool.CAMERA to listOf(ToolGrant.CAMERA),
    ToolbarTool.QR_SCAN to listOf(ToolGrant.CAMERA),
    ToolbarTool.DOC_SCAN to listOf(ToolGrant.CAMERA),
    ToolbarTool.OCR to listOf(ToolGrant.CAMERA),
    ToolbarTool.LOCATION to listOf(ToolGrant.LOCATION),
    ToolbarTool.WEATHER to listOf(ToolGrant.LOCATION),
    ToolbarTool.COMPASS to listOf(ToolGrant.LOCATION),
    ToolbarTool.CALENDAR to listOf(ToolGrant.CALENDAR, ToolGrant.CALENDAR_WRITE, ToolGrant.CONTACTS),
    ToolbarTool.GALLERY to listOf(ToolGrant.PHOTOS),
    ToolbarTool.SEND_FILES to listOf(ToolGrant.NEARBY),
    ToolbarTool.MEDIA_CONTROL to listOf(ToolGrant.NOTIFICATION_ACCESS),
    ToolbarTool.SCREENSHOT to listOf(ToolGrant.SYSTEM_BUTTONS),
    ToolbarTool.SYSTEM_BACK to listOf(ToolGrant.SYSTEM_BUTTONS),
    ToolbarTool.SYSTEM_HOME to listOf(ToolGrant.SYSTEM_BUTTONS),
    ToolbarTool.SYSTEM_RECENTS to listOf(ToolGrant.SYSTEM_BUTTONS),
)

/** Storage categories that belong to one tool beyond those whose manage button opens the tool's page. */
private val ExtraStorage: Map<ToolbarTool, List<String>> = mapOf(
    ToolbarTool.VOICE to listOf("voice_models", "romanized_dictation"),
    ToolbarTool.AI to listOf("ai_models", "ai_history", "cache_llm"),
    ToolbarTool.STICKER to listOf("stickers", "sticker_models"),
    ToolbarTool.VOCABULARY to listOf("vocab"),
    ToolbarTool.CLIPBOARD to listOf("clipboard"),
    ToolbarTool.SNIPPETS to listOf("snippets"),
    ToolbarTool.STATISTICS to listOf("typing_stats"),
    ToolbarTool.PLUGINS to listOf("plugins"),
)

/** The storage categories [tool] owns: its manage button opens the tool's page, or it is listed above. */
internal fun toolStorage(tool: ToolbarTool): List<StorageCategory> {
    val route = toolRoute(tool)
    val owned = StorageCategories.all.filter { it.manageRoute == route }
    val extra = ExtraStorage[tool].orEmpty().mapNotNull(StorageCategories::byId)
    return (owned + extra).distinctBy { it.id }
}

/** The tools worth a row: those with a permission, a download or a use to show. */
private fun dashboardTools(uses: Map<String, ToolUsage.Use>): List<ToolbarTool> =
    ToolbarTool.entries.filter { tool ->
        ToolGrants.containsKey(tool) || toolStorage(tool).isNotEmpty() || uses.containsKey(tool.name)
    }

private fun usageFile(context: Context) = File(context.filesDir, ToolUsage.FILE_PATH)

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData("package:${context.packageName}".toUri())

/** "3 days ago · 42 times", or that it never ran. */
private fun useLine(context: Context, use: ToolUsage.Use?): String {
    if (use == null || use.count == 0) return context.getString(R.string.tool_dashboard_never_used)
    val times = context.resources.getQuantityString(R.plurals.tool_dashboard_times, use.count, use.count)
    val last = use.lastMs?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    return if (last == null) times else context.getString(R.string.tool_dashboard_last_used, last, times)
}

/** The whole dashboard: every permission with the tools that use it, then every tool. */
@Composable
internal fun ToolDashboardScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val uses by produceState(emptyMap<String, ToolUsage.Use>()) {
        value = withContext(Dispatchers.IO) { ToolUsage.read(usageFile(context)) }
    }
    val sizes by produceState(emptyMap<ToolbarTool, Long>(), uses) {
        value = withContext(Dispatchers.IO) {
            val roots = storageRoots(context)
            dashboardTools(uses).associateWith { tool ->
                toolStorage(tool).sumOf { c -> c.paths(roots).sumOf { diskUsage(it, roots.blockSize) } }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.tool_dashboard_permissions_group),
        info = stringResource(R.string.tool_dashboard_info),
    ) {
        for (grant in ToolGrant.entries) {
            val tools = ToolGrants.filterValues { grant in it }.keys
            if (tools.isEmpty()) continue
            item {
                val granted = rememberGrantState(grant::granted)
                NavRow(
                    title = stringResource(grant.title),
                    subtitle = stringResource(
                        R.string.tool_dashboard_used_by,
                        tools.joinToString(", ") { context.getString(toolTitle(it)) },
                    ),
                    value = stringResource(
                        if (granted) R.string.privacy_permissions_status_granted else R.string.privacy_permissions_status_not_granted,
                    ),
                ) {
                    // Granted, it can only be taken back on Android's own page; not, the Permissions screen asks.
                    if (granted) runCatching { context.startActivity(appDetails(context)) } else onNavigate("permissions")
                }
            }
        }
    }
    SettingsGroup(stringResource(R.string.tool_dashboard_tools_group)) {
        // Most recently used first; the never-used after, by name.
        val tools = dashboardTools(uses).sortedWith(
            compareByDescending<ToolbarTool> { uses[it.name]?.lastMs ?: 0L }
                .thenBy { context.getString(toolTitle(it)).lowercase() },
        )
        for (tool in tools) {
            item {
                val grants = ToolGrants[tool].orEmpty()
                val lines = buildList {
                    add(useLine(context, uses[tool.name]))
                    if (grants.isNotEmpty()) {
                        add(
                            stringResource(
                                R.string.tool_dashboard_uses,
                                grants.joinToString(", ") { g ->
                                    context.getString(g.title) + " (" + context.getString(
                                        if (g.granted(context)) R.string.privacy_permissions_status_granted else R.string.privacy_permissions_status_not_granted,
                                    ) + ")"
                                },
                            ),
                        )
                    }
                    sizes[tool]?.takeIf { it > 0 }?.let {
                        add(stringResource(R.string.tool_dashboard_space, Formatter.formatShortFileSize(context, it)))
                    }
                }
                NavRow(
                    title = stringResource(toolTitle(tool)),
                    subtitle = lines.joinToString("\n"),
                ) { onNavigate(toolDashboardRoute(tool)) }
            }
        }
    }
}

/**
 * One tool's page on the dashboard: its permissions, each leading to where
 * it is given or taken back; how lately it ran; and its data, each kind with
 * its size, opening the storage screen that lists it, and one button that
 * deletes all of it through the stores that own it.
 */
@Composable
internal fun ToolDashboardToolScreen(repository: SettingsRepository, tool: ToolbarTool, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var generation by remember { mutableIntStateOf(0) }
    val use by produceState<ToolUsage.Use?>(null) {
        value = withContext(Dispatchers.IO) { ToolUsage.read(usageFile(context))[tool.name] }
    }
    val categories = remember(tool) { toolStorage(tool) }
    val sizes by produceState(emptyMap<String, Long>(), generation) {
        value = withContext(Dispatchers.IO) {
            val roots = storageRoots(context)
            categories.associate { c -> c.id to c.paths(roots).sumOf { diskUsage(it, roots.blockSize) } }
        }
    }
    SettingsGroup(stringResource(R.string.tool_dashboard_use_group)) {
        item {
            WmRow(
                title = stringResource(R.string.tool_dashboard_use_title),
                subtitle = useLine(context, use),
                icon = Icons.Outlined.History,
            )
        }
        item {
            NavRow(
                title = stringResource(R.string.tool_dashboard_settings_title),
                subtitle = stringResource(R.string.tool_dashboard_settings_subtitle),
                icon = Icons.Outlined.Settings,
            ) { onNavigate(toolRoute(tool)) }
        }
    }
    val grants = ToolGrants[tool].orEmpty()
    if (grants.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.tool_dashboard_permissions_group),
            info = stringResource(R.string.tool_dashboard_revoke_info),
        ) {
            for (grant in grants) {
                item {
                    val granted = rememberGrantState(grant::granted)
                    val others = ToolGrants.filter { (t, g) -> t != tool && grant in g }.keys
                    NavRow(
                        title = stringResource(grant.title),
                        subtitle = if (others.isEmpty()) {
                            stringResource(if (granted) R.string.tool_dashboard_revoke else R.string.tool_dashboard_grant)
                        } else {
                            stringResource(if (granted) R.string.tool_dashboard_revoke else R.string.tool_dashboard_grant) + "\n" +
                                stringResource(R.string.tool_dashboard_also_used_by, others.joinToString(", ") { context.getString(toolTitle(it)) })
                        },
                        value = stringResource(
                            if (granted) R.string.privacy_permissions_status_granted else R.string.privacy_permissions_status_not_granted,
                        ),
                    ) {
                        if (granted) runCatching { context.startActivity(appDetails(context)) } else onNavigate("permissions")
                    }
                }
            }
        }
    }
    if (categories.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.tool_dashboard_storage_group)) {
            for (c in categories) {
                item {
                    NavRow(
                        title = stringResource(c.title),
                        subtitle = stringResource(c.subtitle),
                        value = sizes[c.id]?.let { Formatter.formatShortFileSize(context, it) },
                        icon = c.icon,
                    ) { onNavigate(storageRoute(c.id)) }
                }
            }
            val total = sizes.values.sum()
            item(visible = total > 0, waitsOnSetting = false) {
                ActionRow(
                    R.string.tool_dashboard_delete_title,
                    subtitle = stringResource(R.string.tool_dashboard_delete_subtitle, Formatter.formatShortFileSize(context, total)),
                    action = stringResource(R.string.tool_dashboard_delete_action),
                    confirm = stringResource(R.string.tool_dashboard_delete_confirm, stringResource(toolTitle(tool))),
                    icon = Icons.Outlined.DeleteForever,
                ) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val env = StorageEnv(context, repository, storageRoots(context))
                            categories.filter { it.clearable }.forEach { runCatching { it.clear(env) } }
                        }
                        generation++
                    }
                }
            }
        }
    }
    if (grants.isEmpty() && categories.isEmpty()) {
        SettingsGroup {
            item {
                WmRow(
                    title = stringResource(R.string.tool_dashboard_nothing_title),
                    subtitle = stringResource(R.string.tool_dashboard_nothing_subtitle),
                    icon = Icons.Outlined.DataUsage,
                )
            }
        }
    }
}

package com.wasimaster.wmkeyboard.app.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.ChoiceSetting
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.ResolvedGlyph
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.StateBanner
import com.wasimaster.wmkeyboard.app.SystemPackIconGrid
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.rememberSystemIconPacks
import com.wasimaster.wmkeyboard.app.rememberSystemPackIcon
import com.wasimaster.wmkeyboard.app.systemPackLabel
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.SystemIconPack
import com.wasimaster.wmkeyboard.core.icons.SystemIconPacks
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route of the app icons screen (#1275). */
object LauncherAppIcons {
    const val ROUTE = "launcherappicons"
}

/** One app the screen lists: what it is called and where its icon comes from. */
private class IconApp(val packageName: String, val label: String)

/**
 * App icons out of launcher icon packs installed on the phone (#1275), for the
 * app launcher's grid, apps on the toolbar and the floating button's apps:
 * one pack for every app, as a home-screen launcher applies one, and a
 * different icon for any single app, picked out of any installed pack.
 */
@Composable
internal fun LauncherAppIconsScreen(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val packs = rememberSystemIconPacks()
    val pack = settings.watch { it.launcher.iconPack }
    val picks = settings.watch { it.launcher.appIcons }
    val apps by produceState(emptyList<IconApp>(), context) {
        value = withContext(Dispatchers.IO) { launchableApps(context) }
    }
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<IconApp?>(null) }

    Spacer(Modifier.height(12.dp))
    if (packs.isEmpty()) StateBanner(stringResource(R.string.launcherappicons_no_packs))
    SettingsGroup {
        item {
            // A pack that has gone stays named until another is picked, so
            // the row does not claim "None" while the setting still holds it.
            val gone = pack.isNotEmpty() && packs.none { it.packageName == pack }
            ChoiceSetting(
                R.string.launcherappicons_pack_title,
                subtitle = stringResource(R.string.launcherappicons_pack_subtitle),
                info = stringResource(R.string.launcherappicons_pack_info),
                options = listOf("" to stringResource(R.string.launcherappicons_pack_none)) +
                    packs.map { it.packageName to it.label } +
                    (if (gone) listOf(pack to pack) else emptyList()),
                selected = pack,
                default = SettingsDefaults.launcher.iconPack,
            ) { scope.launch { repository.setLauncherIconPack(it) } }
        }
        item {
            WmRow(
                title = stringResource(R.string.launcherappicons_clear_title),
                subtitle = if (picks.isEmpty()) {
                    stringResource(R.string.launcherappicons_clear_none_subtitle)
                } else {
                    pluralStringResource(R.plurals.launcherappicons_clear_count, picks.size, picks.size)
                },
                enabled = picks.isNotEmpty(),
                highlightKey = R.string.launcherappicons_clear_title,
                onClick = { scope.launch { repository.clearLauncherAppIcons() } },
            )
        }
    }
    if (packs.isEmpty()) return
    CaptionText(stringResource(R.string.launcherappicons_caption))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.launcherappicons_search_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val needle = query.trim()
    val shown = if (needle.isEmpty()) apps else apps.filter { it.label.contains(needle, ignoreCase = true) }
    SettingsGroup {
        for (app in shown) {
            item(key = app.packageName) {
                IconAppRow(app, pack, picks[app.packageName], packs) { picking = app }
            }
        }
    }

    picking?.let { app ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(app.label) },
            text = {
                SystemPackIconGrid(
                    packs = packs,
                    selected = picks[app.packageName],
                    onPick = { source ->
                        scope.launch { repository.setLauncherAppIcon(app.packageName, source) }
                        picking = null
                    },
                    leadFor = app.packageName,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.setLauncherAppIcon(app.packageName, null) }
                    picking = null
                }) { Text(stringResource(R.string.plugins_icons_picker_use_default_action)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

/**
 * One app, drawn the way the keyboard will draw it, and where that icon comes
 * from. A line icon (Arcticons) is tinted here so it shows on the settings
 * page's own background; the keyboard draws it as the pack made it.
 */
@Composable
private fun IconAppRow(
    app: IconApp,
    pack: String,
    pick: String?,
    packs: List<SystemIconPack>,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val fromPack by produceState<String?>(null, pack, app.packageName) {
        value = if (pack.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                SystemIconPacks.index(context, pack).drawableFor(app.packageName, null)
                    ?.let { SystemIconPacks.source(pack, it) }
            }
        }
    }
    val source = pick?.takeIf { SystemIconPacks.parseSource(it) != null } ?: fromPack
    val themed = rememberSystemPackIcon(source)
    val own by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val px = (OWN_ICON_DP * context.resources.displayMetrics.density).toInt()
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(px, px).asImageBitmap()
            }.getOrNull()
        }
    }
    val subtitle = when {
        pick != null -> systemPackLabel(packs, pick)
            ?.let { stringResource(R.string.launcherappicons_source_picked, it) }
            ?: stringResource(R.string.launcherappicons_source_own)
        fromPack != null -> systemPackLabel(packs, fromPack)
            ?.let { stringResource(R.string.plugins_icons_source_from_pack, it) }
            ?: stringResource(R.string.launcherappicons_source_own)
        else -> stringResource(R.string.launcherappicons_source_own)
    }
    WmRow(
        title = app.label,
        subtitle = subtitle,
        leading = {
            Box(modifier = Modifier.size(OWN_ICON_DP.dp), contentAlignment = Alignment.Center) {
                val bitmap = own
                when {
                    source != null -> ResolvedGlyph(themed)
                    bitmap != null -> Image(bitmap, contentDescription = null, modifier = Modifier.size(OWN_ICON_DP.dp))
                }
            }
        },
        onClick = onClick,
    )
}

/** Every app with a launcher entry, once per package, by name. Blocking. */
private fun launchableApps(context: Context): List<IconApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val found = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
    return found.mapNotNull { info ->
        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
        val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        IconApp(pkg, label)
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

/** The row's icon size, the size of a settings row's leading tile. */
private const val OWN_ICON_DP = 36

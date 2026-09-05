package com.wasimaster.wmkeyboard.app.media

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.SettingsRowIcons
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.DefaultMusicApps
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route and package-manager reading for the music-player picker. */
object MusicApps {

    const val ROUTE = "musicapps"

    /**
     * How many search hits the "Other apps" group draws at once.
     *
     * The group composes eagerly (it is a [SettingsGroup], not a lazy list),
     * and a phone with three hundred apps would otherwise build three hundred
     * rows on the first keystroke. Past this the screen says how many more
     * there are and asks for a longer query, which is faster to act on than a
     * list nobody scrolls to the end of.
     */
    const val SEARCH_LIMIT = 40

    /**
     * One installed app as the picker lists it.
     *
     * [player] marks the apps that lead the screen: anything declaring a
     * media browser service — the service Android Auto and Assistant use to
     * find players, so a real music app almost always has one — plus whatever
     * is already ticked, which keeps a hand-picked app from sliding down into
     * the long list the moment it is chosen.
     */
    data class Entry(
        val packageName: String,
        val label: String,
        val player: Boolean,
        val installed: Boolean = true,
    )

    /**
     * Every launchable app, players first, each alphabetical.
     *
     * Visibility rides on the manifest's MAIN/LAUNCHER `<queries>` entry, the
     * same one the app-launcher tool uses: it makes every launcher-listed
     * package fully visible, which is what lets the media-browser query below
     * see their services without QUERY_ALL_PACKAGES.
     *
     * [ticked] packages that are not installed are kept, marked
     * [Entry.installed] false, so a player uninstalled for a week is not
     * silently unticked by opening this screen.
     */
    suspend fun load(pm: PackageManager, ticked: Set<String>): List<Entry> =
        withContext(Dispatchers.IO) {
            val labels = runCatching {
                val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(main, 0)
                    .mapNotNull { it.activityInfo }
                    .associate { it.packageName to it.loadLabel(pm).toString() }
            }.getOrDefault(emptyMap())
            val players = runCatching {
                pm.queryIntentServices(Intent(MEDIA_BROWSER_SERVICE), 0)
                    .mapNotNull { it.serviceInfo?.packageName }
                    .toSet()
            }.getOrDefault(emptySet())
            val installed = labels.map { (packageName, label) ->
                Entry(
                    packageName = packageName,
                    label = label,
                    player = packageName in players || packageName in ticked,
                )
            }
            val missing = (ticked - labels.keys).map {
                Entry(packageName = it, label = it, player = true, installed = false)
            }
            (installed + missing).sortedWith(
                compareByDescending<Entry> { it.player }
                    .thenBy { it.label.lowercase() },
            )
        }

    /** The service a music app publishes so other apps can browse and drive it. */
    private const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
}

/**
 * Which apps count as music players for the media tool's toolbar pin.
 *
 * Two groups rather than one long list. The top one is the answer for almost
 * everybody — the installed apps that look like players, plus anything already
 * ticked — and the search box below it is how a player that declares no media
 * browser service, or a package with an unrecognisable name, gets found.
 */
@Composable
internal fun MusicAppsScreen(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ticked = settings.mediaControl.musicApps
    // Loaded once per screen, against the ticks as they were on entry: a list
    // that re-sorted itself under the thumb on every toggle would move the row
    // being tapped. New ticks still show — the group below filters on the live
    // set — they just do not jump.
    val entries by produceState<List<MusicApps.Entry>?>(null) {
        value = MusicApps.load(context.packageManager, ticked)
    }
    var query by remember { mutableStateOf("") }

    Spacer(Modifier.height(12.dp))
    CaptionText(stringResource(R.string.musicapps_caption))

    val all = entries
    if (all == null) {
        CaptionText(stringResource(R.string.musicapps_loading))
        return
    }

    fun toggle(packageName: String, counts: Boolean) {
        scope.launch { repository.toggleMusicApp(packageName, counts) }
    }

    SettingsGroup(stringResource(R.string.musicapps_players_group)) {
        for (entry in all.filter { it.player || it.packageName in ticked }) {
            item { MusicAppRow(entry, entry.packageName in ticked, ::toggle) }
        }
    }

    MusicAppSearch(query) { query = it }

    val needle = query.trim().lowercase()
    if (needle.isEmpty()) {
        CaptionText(stringResource(R.string.musicapps_search_prompt))
    } else {
        val hits = all.filter {
            !it.player && it.packageName !in ticked &&
                (needle in it.label.lowercase() || needle in it.packageName.lowercase())
        }
        if (hits.isEmpty()) {
            CaptionText(stringResource(R.string.musicapps_search_empty))
        } else {
            SettingsGroup(stringResource(R.string.musicapps_other_group)) {
                for (entry in hits.take(MusicApps.SEARCH_LIMIT)) {
                    item { MusicAppRow(entry, entry.packageName in ticked, ::toggle) }
                }
            }
            val extra = hits.size - MusicApps.SEARCH_LIMIT
            if (extra > 0) {
                CaptionText(stringResource(R.string.musicapps_search_more, extra))
            }
        }
    }

    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.musicapps_reset_title),
                subtitle = stringResource(R.string.musicapps_reset_subtitle),
                icon = SettingsRowIcons[R.string.musicapps_reset_title],
                enabled = ticked != DefaultMusicApps,
                onClick = { scope.launch { repository.resetMusicApps() } },
            )
        }
    }
}

/**
 * One app, its icon, and the tick that says its playback counts as music.
 *
 * Built from [WmRow] rather than `ToggleSetting` because the leading slot has
 * to hold the app's own icon: every settings row in the app draws a vector
 * glyph there, and a list of apps that all wore the same note glyph would be
 * unreadable at a glance.
 */
@Composable
private fun MusicAppRow(
    entry: MusicApps.Entry,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    WmRow(
        title = entry.label,
        subtitle = if (entry.installed) {
            entry.packageName
        } else {
            stringResource(R.string.musicapps_uninstalled_subtitle)
        },
        leading = { AppIcon(entry.packageName) },
        trailing = {
            Switch(checked = checked, onCheckedChange = { onToggle(entry.packageName, it) })
        },
        onClick = { onToggle(entry.packageName, !checked) },
    )
}

/** The app's launcher icon, with a placeholder tile until it decodes. */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val density = LocalContext.current.resources.displayMetrics.density
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val px = (32 * density).toInt().coerceAtLeast(1)
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(px, px)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val bitmap = icon
    val shape = RoundedCornerShape(8.dp)
    if (bitmap == null) {
        Box(
            Modifier
                .size(32.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape),
        )
    } else {
        Image(
            bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(32.dp)
                .clip(shape),
        )
    }
}

@Composable
private fun MusicAppSearch(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        label = { Text(stringResource(R.string.musicapps_search_hint)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(CommonR.string.common_clear),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

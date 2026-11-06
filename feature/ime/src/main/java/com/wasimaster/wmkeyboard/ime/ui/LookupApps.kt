package com.wasimaster.wmkeyboard.ime.ui

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.core.settings.LookupSettings
import com.wasimaster.wmkeyboard.core.settings.LookupWebSite
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.floating.rememberFloatingAppIcon

/**
 * Another app on the phone that can look a word up (#970): an offline
 * dictionary such as Aard 2, ColorDict or GoldenDict, an offline Wikipedia
 * such as Kiwix, or anything else that offers itself in the text selection
 * menu. The Dictionary and Wikipedia tools hand it their word.
 */
internal data class LookupApp(
    val packageName: String,
    val activity: String,
    val label: String,
    /** The intent it answered, which decides how the word is passed. */
    val action: String,
)

internal object LookupApps {
    /** Aard 2's own lookup. */
    private const val AARD2 = "aard2.lookup"

    /** The ColorDict intent most Android dictionary apps answer. */
    private const val COLORDICT = "colordict.intent.action.SEARCH"
    private const val COLORDICT_QUERY = "EXTRA_QUERY"

    /**
     * The apps that answer, one row per app, dictionary protocols first: an
     * app that answers both is asked the dictionary way, which opens straight
     * on the entry instead of on a selection menu action.
     */
    fun installed(context: Context): List<LookupApp> {
        val pm = context.packageManager
        val probes = listOf(
            Intent(AARD2),
            Intent(COLORDICT),
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
        )
        val seen = HashSet<String>()
        val apps = ArrayList<LookupApp>()
        for (probe in probes) {
            val found = runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
            for (info in found) {
                val activity = info.activityInfo ?: continue
                if (activity.packageName == context.packageName || !seen.add(activity.packageName)) continue
                val label = runCatching { pm.getApplicationLabel(activity.applicationInfo).toString() }
                    .getOrDefault(activity.packageName)
                apps += LookupApp(activity.packageName, activity.name, label, probe.action.orEmpty())
            }
        }
        return apps
    }

    /** Opens [site] on [word], in the browser or the site's own app. */
    fun openSite(context: Context, site: LookupWebSite, word: String) {
        if (word.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(site.urlFor(word))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun open(context: Context, app: LookupApp, word: String) {
        if (word.isBlank()) return
        val intent = Intent(app.action)
            .setClassName(app.packageName, app.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (app.action == Intent.ACTION_PROCESS_TEXT) {
            intent.setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        } else {
            // Aard 2 reads the search query, ColorDict and its followers their own extra.
            intent.putExtra(SearchManager.QUERY, word).putExtra(COLORDICT_QUERY, word)
        }
        runCatching { context.startActivity(intent) }
    }
}

/**
 * The chip that hands [word] to another app. Names the app when there is
 * one, else opens a menu of them; draws nothing when the phone has none.
 */
@Composable
internal fun LookupAppChip(
    modifier: Modifier = Modifier,
    /** Websites to offer beside the apps (#1150, #1161); see [LookupSettings.webLookups]. */
    sites: List<LookupWebSite> = emptyList(),
    word: () -> String,
) {
    val context = LocalContext.current
    val installed = remember { LookupApps.installed(context) }
    // One list for the menu: the apps, then the sites, each opened its own way.
    val apps = installed + sites.map { LookupApp(packageName = "", activity = it.id, label = it.name, action = SITE_ACTION) }
    if (apps.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val only = apps.singleOrNull()
    val go = { app: LookupApp ->
        val site = sites.firstOrNull { app.action == SITE_ACTION && it.id == app.activity }
        if (site != null) LookupApps.openSite(context, site, word()) else LookupApps.open(context, app, word())
    }
    Box(modifier) {
        ToolPanelChip(
            if (only != null) {
                stringResource(R.string.ime_lookup_app_one, only.label)
            } else {
                stringResource(R.string.ime_lookup_app_many)
            },
        ) {
            if (only != null) go(only) else open = true
        }
        if (open) {
            LookupAppMenu(apps, onPick = {
                open = false
                go(it)
            }) { open = false }
        }
    }
}

/** What a site's row in the menu carries as its action, telling it apart from an app's. */
private const val SITE_ACTION = "wmkb.lookup.site"

@Composable
private fun LookupAppMenu(apps: List<LookupApp>, onPick: (LookupApp) -> Unit, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    val scroll = rememberScrollState()
    Popup(onDismissRequest = onDismiss) {
        ScrollRail(
            state = rememberScrollRailState(scroll),
            modifier = Modifier
                .widthIn(min = 180.dp, max = 260.dp)
                .heightIn(max = 260.dp)
                .clip(kb.menuShape())
                .background(kb.popup)
                .popupBorder(kb, kb.menuShape())
                .padding(vertical = 4.dp),
            fadeColor = kb.popup,
            colors = kbRailColors(kb),
        ) {
            for (app in apps) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(app) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    val icon = if (app.packageName.isEmpty()) null else rememberFloatingAppIcon(app.packageName, 20)
                    if (icon != null) {
                        Image(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    } else {
                        Spacer(Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        app.label,
                        color = kb.popupText,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

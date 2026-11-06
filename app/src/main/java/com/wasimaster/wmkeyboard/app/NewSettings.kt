package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close

/**
 * The settings an update brought (#970), shown where they live: a gold border
 * on each new row, and a bar at the bottom of the screen that walks through
 * them one at a time.
 *
 * Nothing records which version a row arrived in, and nothing needs to. Each
 * version leaves behind the names of the rows it had, route and title
 * resource name, which are the same in every build and every language. The
 * first launch after an update compares the index against them, and what it
 * did not have before is new. The very first install records the names and
 * marks nothing: every setting is new then, and saying so says nothing.
 */
internal object NewSettings {
    private const val PREFS = "new_settings"
    private const val KEY_VERSION = "version_code"
    private const val KEY_KNOWN = "known"
    private const val KEY_FRESH = "fresh"

    /**
     * What the latest update that brought any settings brought, kept when the
     * bar is closed, so About can walk through them again. A recut that adds
     * nothing leaves it alone rather than emptying it.
     */
    private const val KEY_LAST = "last_added"

    /** The new rows, in the index's order, which is the order the screens list them. */
    var entries: List<SettingsSearchEntry> by mutableStateOf(emptyList())
        private set

    /** Title resources of [entries], which the rows match on. */
    var titles: Set<Int> by mutableStateOf(emptySet())
        private set

    /** Which of [entries] the bar last opened, or -1 before it has opened one. */
    var at: Int by mutableIntStateOf(-1)
        private set

    /** The rows the latest update added, which [replay] brings back; empty on a first install. */
    var lastAdded: List<SettingsSearchEntry> by mutableStateOf(emptyList())
        private set
    private var lastKeys: Set<String> = emptySet()

    /**
     * How much of the bottom of the window the bar covers, in pixels, or 0
     * while it is not drawn. A settings page leaves this much room under its
     * last row, so a new row at the foot of a page can still be scrolled
     * clear of the bar that points at it.
     */
    var barHeight: Int by mutableIntStateOf(0)

    /**
     * The title resource of the row the walk opened when another setting is
     * hiding it and it is drawn greyed out instead (see [peekHiddenRows]), or
     * 0. The bar says why the row is grey.
     */
    var hiddenHere: Int by mutableIntStateOf(0)

    /**
     * The whole settings index, kept from [load] so a step can seed the path
     * the page it opens really lives at, the way a search result does (#1111).
     */
    var wholeIndex: List<SettingsSearchEntry> = emptyList()
        private set

    /** Reads, and after an update works out, the new rows. Off the main thread: it builds the index. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        }.getOrDefault(0L)
        val res = context.resources
        val whole = settingsSearchIndex(res)
        wholeIndex = whole
        val index = whole.filter { it.titleRes != 0 }
        val keyed = index.associateBy { entry ->
            "${entry.route}#${runCatching { res.getResourceEntryName(entry.titleRes) }.getOrDefault(entry.title)}"
        }
        val fresh: Set<String>
        val last: Set<String>
        if (prefs.getLong(KEY_VERSION, -1L) == version) {
            fresh = prefs.getStringSet(KEY_FRESH, null).orEmpty()
            // A version from before KEY_LAST: its unseen rows are the best guess.
            last = prefs.getStringSet(KEY_LAST, null) ?: fresh
        } else {
            val known = prefs.getStringSet(KEY_KNOWN, null)
            // Nothing known is a first install, or a version from before this
            // existed: no way to tell what is new, so nothing is.
            val added = if (known.isNullOrEmpty()) emptySet() else keyed.keys - known
            last = added.ifEmpty { prefs.getStringSet(KEY_LAST, null).orEmpty() }
            fresh = added
            prefs.edit()
                .putLong(KEY_VERSION, version)
                .putStringSet(KEY_KNOWN, keyed.keys + known.orEmpty())
                .putStringSet(KEY_FRESH, added)
                .putStringSet(KEY_LAST, last)
                .apply()
        }
        lastKeys = last
        lastAdded = keyed.filterKeys { it in last }.values.distinctBy { it.titleRes }
        show(keyed.filterKeys { it in fresh }.values.distinctBy { it.titleRes })
    }

    /** Moves the bar to [index] and hands back the entry to open there. */
    fun step(index: Int): SettingsSearchEntry? {
        val entry = entries.getOrNull(index) ?: return null
        at = index
        hiddenHere = 0
        return entry
    }

    /** The user has seen enough: the borders and the bar go until the next update. */
    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FRESH, emptySet()).apply()
        show(emptyList())
    }

    /** About's way back in: the latest update's rows, bar and borders again, from the first. */
    fun replay(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FRESH, lastKeys).apply()
        show(lastAdded)
    }

    private fun show(found: List<SettingsSearchEntry>) {
        entries = found
        titles = found.mapTo(HashSet()) { it.titleRes }
        at = -1
    }
}

/** The border a new row wears. Gold, not the theme's: it has to read as "new" on every scheme. */
internal val NewSettingGold = Color(0xFFD4A62A)

/**
 * The bar at the bottom of every settings screen while an update's new rows
 * are unseen: how many there are, the way to the previous and next one, and
 * a close button that puts the borders away. [onOpen] arms the row's flash
 * and opens its screen.
 *
 * The open row's name and the screen it is on get the bar's whole width and
 * wrap rather than cut off: a name ending in "…" beside "3 of 7" said nothing
 * about which setting it was or where to look. The steps sit on a row of
 * their own under them for the same reason.
 */
@Composable
internal fun NewSettingsBar(onOpen: (SettingsSearchEntry) -> Unit, modifier: Modifier = Modifier) {
    val entries = NewSettings.entries
    if (entries.isEmpty()) return
    val context = LocalContext.current
    val at = NewSettings.at
    val go = { index: Int -> NewSettings.step(index)?.let(onOpen) }
    val open = entries.getOrNull(at)
    DisposableEffect(Unit) { onDispose { NewSettings.barHeight = 0 } }
    Surface(
        modifier = modifier
            .onSizeChanged { NewSettings.barHeight = it.height }
            .navigationBarsPadding()
            .padding(16.dp)
            .widthIn(max = 480.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.animateContentSize()) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 24.dp, bottomEnd = 16.dp))
                        .clickable { go(at.coerceAtLeast(0)) }
                        .padding(start = 20.dp, top = 14.dp, end = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        if (open == null) {
                            pluralStringResource(R.plurals.shell_new_settings_count, entries.size, entries.size)
                        } else {
                            stringResource(R.string.shell_new_settings_step, at + 1, entries.size)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = NewSettingGold,
                    )
                    Text(
                        open?.title ?: stringResource(R.string.shell_new_settings_hint),
                        style = if (open == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                        color = if (open == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (open != null && open.titleRes != 0 && open.titleRes == NewSettings.hiddenHere) {
                        Text(
                            stringResource(R.string.shell_new_settings_hidden),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (open != null && open.screenPath.isNotEmpty()) {
                        Text(
                            stringResource(R.string.shell_new_settings_where, open.screen),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { NewSettings.dismiss(context) }, modifier = Modifier.padding(top = 4.dp, end = 4.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.shell_new_settings_dismiss_desc))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
            ) {
                if (open == null) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { go(0) }) { Text(stringResource(R.string.shell_new_settings_show)) }
                } else {
                    TextButton(
                        onClick = { go(if (at <= 0) entries.lastIndex else at - 1) },
                    ) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.shell_new_settings_previous))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { go(if (at >= entries.lastIndex) 0 else at + 1) },
                    ) {
                        Text(stringResource(CommonR.string.common_next))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * A new row that another setting is hiding, drawn anyway while the walk points
 * at it: the Arrow keys row's own keys while the Arrow keys row is off.
 * Opening its screen and showing nothing said nothing about what was new.
 *
 * A hidden row is not composed, so nothing knows its name. While the walk has
 * a row to find, each hidden row of the group is composed once more without
 * being measured or drawn, under [LocalHighlightProbe], only to hear whether
 * it is the one. That one is then drawn in its place, greyed and closed to
 * touch ([LocalRowPeeked]), for as long as the screen lives or until its own
 * setting shows it.
 */
@Composable
internal fun peekHiddenRows(entries: List<SettingsGroupScope.Entry>): List<SettingsGroupScope.Entry> {
    val peeked = remember { mutableStateListOf<Int>() }
    val probing = SettingsHighlight.centre && SettingsHighlight.target != 0
    if (!probing && peeked.isEmpty()) return entries
    val hidden = entries.indices.filter { entries[it].conditional && !entries[it].visible }
    // Shown by its own setting now: an ordinary row again.
    SideEffect { peeked.removeAll { it !in hidden } }
    if (probing) {
        val target = SettingsHighlight.target
        Layout(
            content = {
                for (i in hidden) {
                    if (i in peeked) continue
                    key(i) {
                        CompositionLocalProvider(
                            LocalHighlightProbe provides {
                                if (i !in peeked) peeked += i
                                NewSettings.hiddenHere = target
                            },
                            content = entries[i].content,
                        )
                    }
                }
            },
        ) { _, _ -> layout(0, 0) {} }
    }
    if (peeked.isEmpty()) return entries
    return entries.mapIndexed { i, entry ->
        if (i !in peeked || entry.visible) return@mapIndexed entry
        SettingsGroupScope.Entry(visible = true, conditional = true, key = entry.key, level = entry.level) {
            CompositionLocalProvider(LocalRowPeeked provides true, entry.content)
        }
    }
}

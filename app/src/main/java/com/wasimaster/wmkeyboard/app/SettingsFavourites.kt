package com.wasimaster.wmkeyboard.app

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.filled.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bookmarks
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StarBorder
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import kotlinx.coroutines.launch

/*
 * Favourite settings (#898): screens and single rows the user pins, listed
 * by a star button in every settings screen's top bar, so the settings they
 * keep coming back to are two presses from any screen.
 *
 * A favourite is named by the same `wmkeyboard://` link a copied setting link
 * carries (#646), and opened the way an incoming link is: the screen
 * resolves through [SettingsRoutes], a row through the search index, and the
 * row is flashed with [SettingsHighlight]. A favourite that a later version
 * moves to another screen still opens, the way a stale link does.
 */

/**
 * One pinned screen or row. [label] and [path] are the names it was drawn
 * with when it was pinned: shown only where the app has no name of its own
 * for it now (a language's page, a mode the user made), since a row's own
 * resource reads in whatever language the app is in today.
 */
@Immutable
internal data class SettingsFavourite(
    val link: String,
    val label: String,
    val path: List<String> = emptyList(),
)

internal object SettingsFavourites {

    /** The link that names the screen at [route], a route [SettingsRoutes.resolve] gave. */
    fun screenLink(route: String): String =
        "${SettingsDeepLink.SCHEME}://${SettingsDeepLink.SCREEN_HOST}/$route"

    /** The link that names one row. No `since=`: this build is the one that has it. */
    fun rowLink(target: SettingLinkTarget): String = SettingLinks.appLink(target.route, target.name, "")

    /**
     * The stored form: one string per favourite, its fields split by tabs.
     * The repository joins the list with line breaks, so neither may appear
     * inside a field; a drawn name never needs one.
     */
    fun encode(items: List<SettingsFavourite>): List<String> = items.map { item ->
        (listOf(item.link, item.label) + item.path).joinToString(FIELD) { it.replace(Regex("[\t\n\r]"), " ") }
    }

    /** [encode] read back. An entry with no link is dropped, and so is a second copy of one. */
    fun decode(stored: List<String>): List<SettingsFavourite> = stored
        .map { it.split(FIELD) }
        .filter { it.first().isNotBlank() }
        .map { SettingsFavourite(it[0], it.getOrElse(1) { "" }, it.drop(2)) }
        .distinctBy { it.link }

    /**
     * What a favourite is called now: a row by its own title resource, a
     * screen by the name the search index gives its route, and anything
     * neither knows by the [SettingsFavourite.label] it was pinned with.
     */
    fun title(item: SettingsFavourite, links: SettingLinkContext?, resources: Resources, packageName: String): String {
        val target = SettingsDeepLink.parse(item.link) ?: return item.label
        if (target.setting.isNotEmpty()) {
            // Held to the shape of a resource name by the parse.
            @Suppress("DiscouragedApi")
            val id = resources.getIdentifier(target.setting, "string", packageName)
            if (id != 0) return runCatching { resources.getString(id) }.getOrNull() ?: item.label
            return item.label
        }
        return links?.screenTitle(target.route) ?: item.label
    }

    private const val FIELD = "\t"
}

/**
 * The favourites as the settings screens use them: the list, and the edits
 * that write it back. Each edit is the whole new list, written in one go.
 */
@Stable
internal class SettingsFavouritesState(
    val items: List<SettingsFavourite>,
    private val write: (List<SettingsFavourite>) -> Unit,
) {
    fun contains(link: String): Boolean = items.any { it.link == link }

    /** Adds [item] at the end, or takes it out when its link is already there. */
    fun toggle(item: SettingsFavourite) =
        write(if (contains(item.link)) items.filterNot { it.link == item.link } else items + item)

    fun remove(item: SettingsFavourite) = write(items.filterNot { it.link == item.link })

    fun reorder(order: List<SettingsFavourite>) = write(order)
}

/**
 * The favourites, published by the settings root. Null outside it, and while
 * the favourites button is off (Accessibility › Settings app): the button is
 * where the list opens, and with it gone so are the pins.
 */
internal val LocalSettingsFavourites = compositionLocalOf<SettingsFavouritesState?> { null }

/**
 * Opens a settings link from inside the app, as if it had come in from
 * outside: the activity resolves it and the graph navigates. Null outside
 * the settings activity.
 */
internal val LocalOpenSettingLink = compositionLocalOf<((String) -> Unit)?> { null }

/** The [SettingsFavouritesState] over [stored], the repository's own form. */
@Composable
internal fun rememberSettingsFavourites(stored: List<String>, repository: SettingsRepository): SettingsFavouritesState {
    val scope = rememberCoroutineScope()
    return remember(stored, repository) {
        SettingsFavouritesState(SettingsFavourites.decode(stored)) { items ->
            scope.launch { repository.setSettingsFavourites(SettingsFavourites.encode(items)) }
        }
    }
}

/**
 * The favourites button in a screen's top bar: opens [FavouritesSheet] with
 * the screen being drawn offered for pinning. Filled while that screen is a
 * favourite itself. Draws nothing where favourites are off.
 */
@Composable
internal fun FavouritesButton(title: String) {
    val favourites = LocalSettingsFavourites.current ?: return
    val route = currentLinkRoute()
    // The screen a link can name, if this is one; the home list is not worth
    // a pin, being where every back press already ends.
    val screen = remember(route) { route?.let { SettingsRoutes.resolve(it) }?.takeIf { it != "home" } }
    val link = screen?.let { SettingsFavourites.screenLink(it) }
    val trail = LocalSettingsCrumbTrail.current
    val entryId = currentCrumbEntry()?.id
    var open by remember { mutableStateOf(false) }
    val pinned = link != null && favourites.contains(link)
    IconButton(onClick = { open = true }) {
        Icon(
            // Home can't be pinned, and an empty star there read as "pin this
            // screen" (#1041): it shows the list the button opens instead.
            when {
                pinned -> Icons.Filled.Star
                link == null -> Icons.Outlined.Bookmarks
                else -> Icons.Outlined.StarBorder
            },
            contentDescription = stringResource(R.string.favourites_open_desc),
            tint = if (pinned) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
    if (open) {
        FavouritesSheet(
            here = link?.let {
                // Read when asked rather than while composing: the trail is
                // still settling on a screen's first frames.
                { SettingsFavourite(it, title, SettingLinks.path(trail?.path.orEmpty(), entryId, null).dropLast(1)) }
            },
            onDismiss = { open = false },
        )
    }
}

/**
 * The favourites list, opened by [FavouritesButton]. A press on an entry
 * opens it; Edit turns the list into one that drags into order and has a bin
 * on every entry. [here] names the screen the sheet was opened on, when it is
 * one a link can name, so the sheet can offer to pin or unpin it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavouritesSheet(here: (() -> SettingsFavourite)?, onDismiss: () -> Unit) {
    val favourites = LocalSettingsFavourites.current ?: return
    val open = LocalOpenSettingLink.current
    val links = LocalSettingLinks.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val items = favourites.items
    // Looked up once per list: a screen's name comes out of the search index.
    val titles = remember(items, links, context) {
        items.associate { it.link to SettingsFavourites.title(it, links, context.resources, context.packageName) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, end = 16.dp),
            ) {
                Text(
                    stringResource(R.string.favourites_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (items.isNotEmpty()) {
                    TextButton(onClick = { editing = !editing }) {
                        Text(stringResource(if (editing) R.string.favourites_done else R.string.favourites_edit))
                    }
                }
            }
            if (here != null && !editing) {
                val link = remember(here) { here().link }
                val pinned = favourites.contains(link)
                ListItem(
                    headlineContent = {
                        Text(stringResource(if (pinned) R.string.favourites_screen_remove else R.string.favourites_screen_add))
                    },
                    leadingContent = {
                        Icon(if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder, null)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { favourites.toggle(here()) },
                )
            }
            when {
                items.isEmpty() -> Text(
                    stringResource(R.string.favourites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                editing -> ReorderableColumn(
                    items = items,
                    label = { titles[it.link].orEmpty() },
                    onReorder = favourites::reorder,
                    onDelete = favourites::remove,
                    keepLast = false,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                else -> for (item in items) {
                    key(item.link) {
                        ListItem(
                            headlineContent = { Text(titles[item.link].orEmpty()) },
                            supportingContent = item.path.takeIf { it.isNotEmpty() }?.let { path ->
                                { Text(path.joinToString(PathSeparator)) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clickable {
                                    // Away first, so the screen it opens is not
                                    // drawn under a sheet on its way out.
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        onDismiss()
                                        open?.invoke(item.link)
                                    }
                                },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Between the steps of a favourite's path, as search draws a result's. */
private const val PathSeparator = " › "

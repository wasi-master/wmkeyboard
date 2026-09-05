package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Shortcut
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Apps

/**
 * The setting the user picked out of search, remembered just long enough for
 * its destination screen to compose and flash it.
 *
 * A plain object rather than a nav argument because the row composables that
 * do the flashing sit far below the NavHost and are shared by every screen —
 * threading an argument down to all ~200 call sites would mean touching all
 * of them, while this needs only the six row helpers to read it.
 */
internal object SettingsHighlight {
    /**
     * The string resource of the row to flash, or 0 when nothing is pending.
     *
     * A resource id rather than the drawn words: the index and the addon
     * screens both name a row before the row is composed, and the words differ
     * in every language while the id does not.
     */
    @get:StringRes
    var target: Int by mutableIntStateOf(0)
        private set

    /**
     * The list entries to flash, named by whatever id the screen holding them
     * uses — a theme or layout id, a pack id, a dictionary file path.
     *
     * A resource id cannot name one of these: they are the user's own things,
     * and no two installations have the same set. A row's title cannot either,
     * because two installed addons may well share a name. So an addon's Use
     * button hands over the local handle its install produced
     * ([com.wasimaster.wmkeyboard.core.addons.InstalledAddon.localRef]) and the
     * owning screen matches on it.
     *
     * A set, not one id: a snippet pack installs several snippets at once and
     * all of them are what the user just downloaded.
     */
    var targetItems: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * Bumped on every request.
     *
     * A screen being torn down clears any highlight it is leaving behind, so
     * that one which found no matching row doesn't flash something unrelated
     * later. But a settings screen can also *arm* a highlight on its way out —
     * an addon's Use button does exactly that — and then it would wipe its own
     * request a frame after making it. Comparing this against the value the
     * screen saw when it opened tells the two apart.
     */
    var serial: Int by mutableIntStateOf(0)
        private set

    /**
     * False until one of the flashing rows has scrolled itself into view.
     *
     * Several rows can match one request, and every one of them asking to be
     * brought into view would leave whichever was placed last on screen. The
     * first to claim it wins, which in a top-to-bottom composition is the
     * topmost — where the eye already is.
     */
    private var scrollClaimed = false

    fun request(@StringRes titleRes: Int) {
        target = titleRes
        targetItems = emptySet()
        scrollClaimed = false
        serial++
    }

    /**
     * Flashes the entries named by [keys], falling back to the row [titleRes]
     * names when the screen turns out not to list any of them.
     */
    fun requestItems(keys: Collection<String>, @StringRes titleRes: Int = 0) {
        target = titleRes
        targetItems = keys.filter { it.isNotBlank() }.toSet()
        scrollClaimed = false
        serial++
    }

    /** True for the one row that gets to scroll itself into view. */
    fun claimScroll(): Boolean {
        if (scrollClaimed) return false
        scrollClaimed = true
        return true
    }

    /** True once some row has scrolled itself into view for this request. */
    fun scrollTaken(): Boolean = scrollClaimed

    fun clear() {
        target = 0
        targetItems = emptySet()
    }

    /** Clears only if nothing new was requested since [serialAtEntry]. */
    fun clearIfUnchanged(serialAtEntry: Int) {
        if (serial == serialAtEntry) clear()
    }
}

/**
 * Where a list screen should be standing the next time it is composed, armed by
 * the screen itself on its way out.
 *
 * Navigation keeps a route's saveable state while it sits on the back stack, so
 * coming back from an addon's page or a language's page *usually* restores the
 * scroll position on its own. It stops being reliable the moment the content
 * measures shorter on the first frame than it did on the last — a catalogue
 * whose cards grow as their screenshots decode is exactly that shape, and a
 * scroll offset past the end of a half-built page is clamped to what fits.
 * Naming the entry instead of an offset survives it.
 *
 * Not [SettingsHighlight]: coming back to where you were is not the same event
 * as being sent somewhere, and it must not flash. It is also keyed by route, so
 * an anchor left by one screen can never be consumed by another.
 *
 * A plain map rather than Compose state — the only reader is a `remember` at
 * screen entry, and nothing re-reads it as it changes.
 */
internal object ReturnAnchor {
    private val pending = HashMap<String, String>()

    /** Called as the screen navigates away, naming the entry being opened. */
    fun arm(route: String, key: String) {
        if (key.isNotBlank()) pending[route] = key
    }

    /** Reads [route]'s anchor and forgets it, so it is used exactly once. */
    fun take(route: String): String? = pending.remove(route)
}

/**
 * Wraps a settings row so it scrolls itself into view and pulses once when it
 * is the setting the user searched for.
 *
 * Pass [highlightKey], the string resource of the row's own name, and the match
 * is on the resource: exact, and the same in every language. A row that only
 * has its drawn [title] is matched on the words instead, against the target id
 * resolved through the same resources, which is as unique as a title is within
 * one screen — the only scope where two rows are ever composed at the same
 * time.
 *
 * A row with neither is a row nothing can match on. It still gets the wrapper,
 * so that a group which names itself only once it has content ("Repositories")
 * keeps its children's state when the name appears. Branching on the title
 * around [content] instead would move the slot and discard everything inside.
 */
@Composable
internal fun HighlightableRow(
    title: String?,
    @StringRes highlightKey: Int = 0,
    /**
     * True for a whole section rather than one row. A group and a row inside it
     * can both answer to the same name, and then the row is the better answer —
     * see [HighlightFrame].
     */
    coarse: Boolean = false,
    content: @Composable () -> Unit,
) {
    val target = SettingsHighlight.target
    val requested = when {
        target == 0 -> false
        highlightKey != 0 -> highlightKey == target
        title == null -> false
        else -> title == stringResource(target)
    }
    HighlightFrame(
        requested = requested,
        rank = if (coarse) HighlightRank.SECTION else HighlightRank.ROW,
        content = content,
    )
}

/**
 * How precisely a wrapper names what the user asked for. The narrowest match on
 * screen is the one that scrolls and pulses; see [HighlightFrame].
 */
private enum class HighlightRank(val settleMillis: Long) {
    /** One entry of a list, matched on the screen's own id for it. */
    ITEM(80),

    /** One row, matched by name. */
    ROW(200),

    /** A whole named section. */
    SECTION(320),
}

/**
 * [HighlightableRow] for one entry of a list the user owns — an installed theme,
 * layout, pack or word list — matched on the screen's own id for it rather than
 * on a string resource.
 *
 * This is the other half of an addon's Use button: the section anchor lands the
 * user on the right screen, and this puts them in front of the thing they just
 * installed, which on a screen listing thirty fonts is the whole of the answer.
 */
@Composable
internal fun HighlightableItem(key: String, content: @Composable () -> Unit) {
    HighlightFrame(
        requested = key.isNotEmpty() && key in SettingsHighlight.targetItems,
        rank = HighlightRank.ITEM,
        content = content,
    )
}

/**
 * [HighlightableItem] for a card that stands for several ids at once — a theme
 * family's card answers for every variant in it, so a deep link naming a
 * variant still lands somewhere visible.
 */
@Composable
internal fun HighlightableItem(keys: List<String>, content: @Composable () -> Unit) {
    HighlightFrame(
        requested = keys.any { it.isNotEmpty() && it in SettingsHighlight.targetItems },
        rank = HighlightRank.ITEM,
        content = content,
    )
}

/**
 * Scrolls its content into view once, without flashing it.
 *
 * For "you were already here": the current selection in a picker, the entry a
 * screen was opened from. A pulse says *this is the thing you asked for*, and
 * spending it every time a dialog opens on its own default would leave it
 * meaning nothing when a search result or an addon actually needs it.
 */
@Composable
internal fun ScrollAnchor(active: Boolean, content: @Composable () -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // One frame's grace so the row has been placed before we scroll to it.
        delay(80)
        requester.bringIntoView()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester),
    ) {
        content()
    }
}

/**
 * The scroll-and-pulse itself, shared by the highlight wrappers.
 *
 * One request routinely matches at more than one size: an addon names both the
 * section that owns its kind and the entry it installed, and a group can carry
 * the same name as a row inside it. The narrowest match is the right answer —
 * landing on the font you installed beats landing on the words "Installed
 * fonts" — so each rank waits a little longer than the one below it and stands
 * down once something narrower has taken the screen. Flashing all of them would
 * pulse twice and scroll to whichever happened to be placed last.
 */
@Composable
private fun HighlightFrame(
    requested: Boolean,
    rank: HighlightRank,
    content: @Composable () -> Unit,
) {
    var flashing by remember { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    val color by animateColorAsState(
        targetValue = if (flashing) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else Color.Transparent,
        animationSpec = tween(durationMillis = 320),
        label = "settingHighlight",
    )
    LaunchedEffect(requested) {
        // Also the path out of a flash that the *first* matching row ended by
        // clearing the request: without it this one would keep its tint for as
        // long as the screen lives.
        if (!requested) {
            flashing = false
            return@LaunchedEffect
        }
        // A frame's grace so the row has been placed before we scroll to it,
        // plus however long this rank owes the narrower ones.
        delay(rank.settleMillis)
        if (rank != HighlightRank.ITEM && SettingsHighlight.scrollTaken()) return@LaunchedEffect
        if (SettingsHighlight.claimScroll()) requester.bringIntoView()
        flashing = true
        delay(1400)
        flashing = false
        // Consumed: a later visit to the same screen must not flash again.
        SettingsHighlight.clear()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .background(color),
    ) {
        content()
    }
}

/**
 * Full-screen search over every setting. Results carry their breadcrumb, and
 * tapping one opens the owning screen with the row flashed.
 */
/**
 * The glyph each destination is drawn with, keyed by route — the settings
 * home's own icons, extended to the screens that hang off it. A result
 * therefore looks like the row it will take you to, and the screens all share
 * an icon with their rows.
 *
 * Tool routes are absent on purpose: those draw the tool's own icon, which
 * the user can replace with an icon pack.
 */
internal object SettingsRouteIcons {
    // Builders rather than built vectors, for the same reason as
    // [SettingsRowIcons]: this table used to materialise every glyph in it
    // the first time any row in this file composed.
    private val map: Map<String, () -> ImageVector> = mapOf(
        "typing" to { Icons.Outlined.Keyboard },
        "typing/corrections" to { Icons.Outlined.Spellcheck },
        "typing/suggestions" to { Icons.Outlined.Lightbulb },
        "typing/chips" to { Icons.Outlined.AutoAwesome },
        "typing/codes" to { Icons.Outlined.Password },
        "typing/gestures" to { Icons.Outlined.Gesture },
        "typing/hardware" to { Icons.Outlined.Keyboard },
        "keypress" to { Icons.Outlined.TouchApp },
        "keypress/haptics" to { Icons.Outlined.Vibration },
        "keypress/popup" to { Icons.Outlined.Preview },
        "keypress/shortcuts" to { Icons.Outlined.Shortcut },
        "languages" to { Icons.Outlined.Language },
        "appearance" to { Icons.Outlined.Palette },
        "appearance/toolbar" to { Icons.Outlined.ViewDay },
        "appearance/toolbox" to { Icons.Outlined.Apps },
        "themes" to { Icons.Outlined.Palette },
        "photos" to { Icons.Outlined.Wallpaper },
        "photo_browse" to { Icons.Outlined.PhotoLibrary },
        "photo_library" to { Icons.Outlined.Collections },
        "photo_rotation" to { Icons.Outlined.Autorenew },
        "fonts" to { Icons.Outlined.TextFields },
        "icons" to { Icons.Outlined.Image },
        "layout" to { Icons.Outlined.AspectRatio },
        "layout/size" to { Icons.Outlined.FormatSize },
        "layout/onehanded" to { Icons.Outlined.PanTool },
        "keymaps" to { Icons.Outlined.GridOn },
        "rows" to { Icons.Outlined.ViewAgenda },
        "ai_actions" to { Icons.Outlined.AutoAwesome },
        "ai_history" to { Icons.Outlined.History },
        "ai_chat" to { Icons.AutoMirrored.Outlined.Chat },
        "modes" to { Icons.Outlined.Tune },
        "emoji" to { Icons.Outlined.EmojiEmotions },
        "emoji/panel" to { Icons.Outlined.GridView },
        "emojikeywords" to { Icons.Outlined.EmojiEmotions },
        "clipboard" to { Icons.Outlined.ContentPaste },
        "voice" to { Icons.Outlined.Mic },
        "expander" to { Icons.AutoMirrored.Outlined.TextSnippet },
        "tools" to { Icons.Outlined.Widgets },
        "sticker_packs" to { Icons.AutoMirrored.Outlined.StickyNote2 },
        "plugins" to { Icons.Outlined.Extension },
        "addons" to { Icons.Outlined.Extension },
        "accessibility" to { Icons.Outlined.Accessibility },
        "privacy" to { Icons.Outlined.Security },
        "permissions" to { Icons.Outlined.Key },
        "applock" to { Icons.Outlined.Fingerprint },
        "datasaver" to { Icons.Outlined.DataSaverOn },
        "advanced" to { Icons.Outlined.Tune },
        "backup" to { Icons.Outlined.Save },
        "about" to { Icons.Outlined.Info },
        "storage" to { Icons.Outlined.PieChart },
        "statistics" to { Icons.Outlined.QueryStats },
        "licenses" to { Icons.Outlined.Gavel },
        "debug_log" to { Icons.AutoMirrored.Outlined.Article },
        "dictionary" to { Icons.AutoMirrored.Outlined.MenuBook },
        "customdictionaries" to { Icons.AutoMirrored.Outlined.MenuBook },
        "blacklist" to { Icons.Outlined.VisibilityOff },
        "phoneformats" to { Icons.Outlined.Phone },
        "hwshortcuts" to { Icons.Outlined.Keyboard },
        "musicapps" to { Icons.Outlined.MusicNote },
    )

    operator fun get(route: String): ImageVector? = map[route]?.invoke()

    // Membership without materialising the glyph, for the index test's sweep.
    operator fun contains(route: String): Boolean = route in map
}

/** The index and the vocabulary the matcher reads, built together off-main. */
private class SettingsSearchCorpus(
    val index: List<SettingsSearchEntry>,
    val vocabulary: SettingsSearchVocabulary,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSearchScreen(
    settings: KeyboardSettings,
    onBack: () -> Unit,
    onOpen: (SettingsSearchEntry) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Built once per context: every entry resolves its own three strings, so
    // rebuilding it on each keystroke would read ~1000 resources a character.
    // Off the main thread as well — those reads used to run during this
    // screen's first composition, which is the middle of its opening
    // animation. Results are empty for the frame or two the build takes,
    // which is less time than reaching for the first key.
    val context = LocalContext.current
    val corpus by produceState<SettingsSearchCorpus?>(null, context) {
        value = withContext(Dispatchers.Default) {
            SettingsSearchCorpus(
                settingsSearchIndex(context.resources),
                settingsSearchVocabulary(context.resources),
            )
        }
    }
    val results = remember(query, corpus) {
        corpus?.let { searchSettings(query, it.index, it.vocabulary) }.orEmpty()
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.shell_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(
                                            CommonR.string.common_clear,
                                        ),
                                    )
                                }
                            }
                        },
                        // The field is the app bar, so it must not draw one of
                        // its own: no container fill, no indicator line.
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(CommonR.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            query.isBlank() -> SearchHint(Modifier.padding(padding))
            results.isEmpty() -> EmptyResults(query, Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(results, key = { "${it.route}/${it.titleRes}" }) { result ->
                    ResultRow(result, settings) {
                        keyboard?.hide()
                        onOpen(result)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(entry: SettingsSearchEntry, settings: KeyboardSettings, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        WmRow(
            title = entry.title,
            leading = { ResultIcon(entry, settings) },
            supporting = {
                Column {
                    if (entry.subtitle.isNotBlank()) Text(entry.subtitle)
                    Text(
                        entry.screen,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            onClick = onClick,
        )
    }
}

/**
 * The icon beside a result, on the same accent tile the home list uses: the
 * tool's own glyph on a tool page — icon pack and accent colour included, so it
 * matches the Tools list — otherwise the icon of the screen it lives on. The
 * magnifier is the fallback for a route with no icon of its own.
 */
@Composable
private fun ResultIcon(entry: SettingsSearchEntry, settings: KeyboardSettings) {
    val tool = entry.tool
    if (tool != null) {
        // The tile's own wash keeps the raw accent; only the glyph inside is
        // darkened, which is what WmIconTile does for a flat accent too.
        val paint = toolAccentPaint(tool, settings)
        val glyph = tileToolPaint(paint)
        WmIconTile(
            accent = paint?.color ?: MaterialTheme.colorScheme.primary,
            brush = paint?.brush,
        ) {
            SlotIcon(
                IconSlots.forTool(tool),
                contentDescription = null,
                modifier = Modifier.size(WmIconTileGlyph),
                brush = glyph?.brush,
            )
        }
        return
    }
    WmIconTile(
        SettingsRouteIcons[entry.route] ?: Icons.Outlined.Search,
        accent = routeAccent(entry.route),
    )
}

@Composable
private fun SearchHint(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(24.dp))
        CaptionText(stringResource(R.string.shell_search_help_body))
    }
}

@Composable
private fun EmptyResults(query: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(24.dp))
        CaptionText(stringResource(R.string.shell_search_empty, query))
    }
}

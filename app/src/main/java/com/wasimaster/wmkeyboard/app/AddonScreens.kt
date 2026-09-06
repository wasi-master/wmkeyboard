package com.wasimaster.wmkeyboard.app

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.addons.AddonApply
import com.wasimaster.wmkeyboard.core.addons.AddonDownloadManager
import com.wasimaster.wmkeyboard.core.addons.AddonEntry
import com.wasimaster.wmkeyboard.core.addons.AddonPreviewContent
import com.wasimaster.wmkeyboard.core.addons.AddonPreviewReader
import com.wasimaster.wmkeyboard.core.addons.AddonReconciler
import com.wasimaster.wmkeyboard.core.addons.AddonRepoCodec
import com.wasimaster.wmkeyboard.core.addons.AddonRepoInfo
import com.wasimaster.wmkeyboard.core.addons.AddonRepoRef
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.addons.InstalledAddon
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.addons.resolve
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.settings.DeviceNetworkState
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.stopsBackgroundWork
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.ime.R as ImeR
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The addon store: repositories the user added, what each one offers, and what
 * is installed from them.
 *
 * Three screens — the repository list, one repository's catalogue, and a single
 * addon's detail page. The detail route is keyed by the repository's *URL*
 * rather than its position in the list, because a `wmkeyboard://` deep link
 * carries a URL and has no idea what order anyone's repositories are in.
 */

// ---- routes ----------------------------------------------------------

/**
 * `addon_repo/{repoUrl}` with the URL percent-encoded into the path. [type]
 * pre-selects one of the catalogue's filter chips, which is how a "Download
 * more themes" row on a settings screen arrives showing only themes.
 */
internal fun addonRepoRoute(manifestUrl: String, type: AddonType? = null): String =
    "addon_repo/${java.net.URLEncoder.encode(manifestUrl, "UTF-8")}" +
        (type?.let { "?type=${it.name}" }.orEmpty())

/** The Addons screen with the add-repository dialog pre-filled from a link. */
internal fun addonsAddRoute(manifestUrl: String): String =
    "addons?add=${java.net.URLEncoder.encode(manifestUrl, "UTF-8")}"

/**
 * The Addons screen looking for one kind of addon: the repository list, with
 * the type carried through to whichever catalogue the user opens.
 */
internal fun addonsTypeRoute(type: AddonType): String = "addons?type=${type.name}"

/** An [AddonType] back from a route argument; null for a missing or stale name. */
internal fun addonTypeArg(name: String?): AddonType? =
    name?.takeIf { it.isNotEmpty() }?.let { value -> AddonType.entries.firstOrNull { it.name == value } }

/** `addon/{repoUrl}/{addonId}`, both segments percent-encoded. */
internal fun addonDetailRoute(manifestUrl: String, addonId: String): String =
    "addon/${java.net.URLEncoder.encode(manifestUrl, "UTF-8")}/" +
        java.net.URLEncoder.encode(addonId, "UTF-8")

internal fun decodeRouteArg(value: String?): String =
    runCatching { java.net.URLDecoder.decode(value.orEmpty(), "UTF-8") }.getOrDefault("")

/**
 * Stands in for the repository URL when an installed addon has none.
 *
 * Records written before the URL was stored don't have one, and neither does
 * one whose repository has been removed — or, as happened here, renamed, since
 * the id in the install key then matches no repository at all. The addon is
 * still installed and still has to be manageable, so the route carries this
 * instead and the page resolves the record by addon id. Not a valid URL by
 * construction: nothing will try to fetch it.
 */
private const val NO_REPO = "-"

// ---- per-type identity -----------------------------------------------

/**
 * Each addon type's glyph and colour.
 *
 * A catalogue is a wall of cards that all look alike, and "Icon pack" reads the
 * same as "Sticker pack" at a glance. A consistent glyph and hue per type makes
 * the wall scannable — and lets the filter chips, the cards and the detail page
 * agree on what a theme looks like.
 */
private val AddonType.icon
    get() = when (this) {
        AddonType.Theme -> Icons.Outlined.Palette
        AddonType.Layout -> Icons.Outlined.Keyboard
        AddonType.Dictionary -> Icons.AutoMirrored.Outlined.MenuBook
        AddonType.EmojiKeywords -> Icons.Outlined.Translate
        AddonType.Snippets -> Icons.Outlined.Description
        AddonType.Espanso -> Icons.Outlined.Bolt
        AddonType.Stickers -> Icons.Outlined.EmojiEmotions
        AddonType.IconPack -> Icons.Outlined.Category
        AddonType.Font -> Icons.Outlined.TextFields
        AddonType.EmojiFont -> Icons.Outlined.Mood
        AddonType.Sound -> Icons.Outlined.GraphicEq
        AddonType.SoundPack -> Icons.Outlined.LibraryMusic
        AddonType.Plugin -> Icons.Outlined.Code
        AddonType.Unknown -> Icons.Outlined.Extension
    }

/**
 * The type's hue, before it is adapted to the theme.
 *
 * Fixed rather than derived from the Material scheme: the point is that the
 * types are told apart from each other, which a single accent hue can't do.
 * [tintFor] is what makes each one legible in the current theme.
 */
private val AddonType.seed: Color
    get() = when (this) {
        AddonType.Theme -> Color(0xFF7E57C2)
        AddonType.Layout -> Color(0xFF3B82F6)
        AddonType.Dictionary -> Color(0xFF14B8A6)
        AddonType.EmojiKeywords -> Color(0xFF0EA5E9)
        AddonType.Snippets, AddonType.Espanso -> Color(0xFFF59E0B)
        AddonType.Stickers -> Color(0xFFEC4899)
        AddonType.IconPack -> Color(0xFF22A559)
        AddonType.Font -> Color(0xFF6366F1)
        AddonType.EmojiFont -> Color(0xFFEAB308)
        AddonType.Sound -> Color(0xFFEF4444)
        // A near neighbour of Sound's red rather than a new hue: the two
        // are the same choice made at two sizes, and the cards should read
        // as siblings.
        AddonType.SoundPack -> Color(0xFFF97316)
        AddonType.Plugin -> Color(0xFF06B6D4)
        AddonType.Unknown -> Color(0xFF6B7280)
    }

/**
 * An addon's own page, as flights name it. Distinct per addon, so a card in a
 * catalogue and a row in the installed list each fly into the right one.
 */
internal fun addonFlightRoute(manifestUrl: String, addonId: String): String =
    "addon-page/$manifestUrl/$addonId"

/** Version, size and author on one line — the same line on card and page. */
private fun addonMetaLine(version: String, sizeBytes: Long?, author: String): String = buildString {
    if (version.isNotBlank()) append("v$version")
    sizeBytes?.let {
        if (isNotEmpty()) append(" \u00b7 ")
        append(formatBytes(it))
    }
    if (author.isNotBlank()) {
        if (isNotEmpty()) append(" \u00b7 ")
        append(author)
    }
}

/**
 * What an addon's page is headed with: its type, drawn in the type's own
 * colour. Read from the cached manifest, or — for something installed from a
 * repository that has since gone away — from the install record.
 */
internal data class AddonHeading(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val accent: Color,
)

/**
 * A repository's own page, as flights name it, and what that page is headed
 * with — the repository's name over its author, rather than "Browse addons",
 * which named the act instead of the place.
 */
internal fun addonRepoFlightRoute(manifestUrl: String): String = "addon-repo-page/$manifestUrl"

internal data class RepoHeading(val name: String, val author: String)

@Composable
internal fun rememberRepoHeading(manifestUrl: String): RepoHeading {
    val context = LocalContext.current
    val store = remember { AddonStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val fallbackName = stringResource(R.string.addon_repo_title_fallback)
    return remember(revision, manifestUrl, fallbackName) {
        val repo = store.repo(manifestUrl)
            ?.let { AddonDownloadManager.cachedManifest(it) }
            ?.repo
        RepoHeading(
            name = repo?.name?.ifBlank { null } ?: fallbackName,
            author = repo?.author.orEmpty(),
        )
    }
}

@Composable
internal fun rememberAddonHeading(manifestUrl: String, addonId: String): AddonHeading {
    val context = LocalContext.current
    val store = remember { AddonStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val type = remember(revision, manifestUrl, addonId) {
        store.repo(manifestUrl)
            ?.let { AddonDownloadManager.cachedManifest(it) }
            ?.addons?.firstOrNull { it.id == addonId }?.type
            ?: store.installedFor(manifestUrl, addonId)?.second?.type
            ?: AddonType.Unknown
    }
    return AddonHeading(type.singularLabelRes, type.icon, tintFor(type))
}

/**
 * The type's hue pulled toward legibility on the current surface: darkened on a
 * light theme, lifted on a dark one. Amber on white and indigo on near-black are
 * both unreadable untreated.
 */
@Composable
private fun tintFor(type: AddonType): Color {
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    return remember(type, dark) {
        if (dark) lerp(type.seed, Color.White, 0.28f) else lerp(type.seed, Color.Black, 0.3f)
    }
}

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

// ---- the way in from a settings screen -------------------------------

/**
 * The "Download more …" row a settings screen carries.
 *
 * The addon store already knows which settings screen owns each type (see
 * [AddonType.settingsRoute]); this is the same link the other way round, so
 * that someone looking at their themes, icon packs or emoji fonts can get to
 * more of them without first having to know the store exists. It opens the
 * store already filtered to the one type, because a catalogue of everything is
 * not an answer to "more themes".
 *
 * Drawn in the type's own glyph and colour, the same pair the catalogue's cards
 * and filter chips use, so the row reads as a door into the store rather than
 * as one more setting.
 */
@Composable
internal fun AddonStoreRow(type: AddonType, onNavigate: (String) -> Unit) {
    val label = stringResource(type.labelRes)
    WmRow(
        title = stringResource(R.string.addon_get_more_title),
        subtitle = stringResource(R.string.addon_get_more_subtitle, label),
        icon = type.icon,
        accent = tintFor(type),
        trailing = {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = takeOffClick { onNavigate(addonsTypeRoute(type)) },
    )
}

/** [AddonStoreRow] as a group of its own, for a screen that has nowhere to put it. */
@Composable
internal fun AddonStoreGroup(type: AddonType, onNavigate: (String) -> Unit) {
    SettingsGroup { item { AddonStoreRow(type, onNavigate) } }
}

/** [ReturnAnchor] key for the Addons screen — its repositories and its installs. */
private const val ADDONS_ANCHOR = "addons"

/**
 * [ReturnAnchor] key for one repository's catalogue.
 *
 * Per repository, not one for the store: two catalogues can be open on the back
 * stack at once, and an id from one of them means nothing in the other.
 */
private fun repoAnchorKey(manifestUrl: String) = "addon-repo:$manifestUrl"

// ---- repository list -------------------------------------------------

/**
 * [prefillUrl] comes from a `wmkeyboard://repo?url=…` link: it opens the add
 * dialog with the address filled in, so the user still sees what they are
 * trusting and still has to confirm.
 *
 * [typeFilter] comes from an [AddonStoreRow]: the user asked for one kind of
 * addon, so the installed list narrows to it and it travels on into whichever
 * repository they open.
 */
@Composable
internal fun AddonsScreen(
    prefillUrl: String = "",
    typeFilter: AddonType? = null,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AddonStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val repos = remember(revision) { store.repos() }
    val autoRefresh = remember(revision) { store.autoRefresh() }
    val refreshUnmeteredOnly = remember(revision) { store.refreshUnmeteredOnly() }
    val installed = remember(revision, typeFilter) {
        // Matched on the category rather than the type: an Espanso pack is a
        // pack of snippets to a user, and browsing is the place that has to
        // agree with them. See AddonType.storeCategory.
        store.installed().filter { (_, record) ->
            typeFilter == null || record.type.storeCategory == typeFilter
        }
    }

    var showAdd by remember { mutableStateOf(prefillUrl.isNotBlank()) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // The row this screen was last left from, so coming back from a repository
    // or an addon lands on it. A repository is named by its manifest URL and an
    // installed addon by its "repoId/addonId" key, so one anchor serves both
    // lists without them ever being able to answer for each other.
    val returnTo = remember { ReturnAnchor.take(ADDONS_ANCHOR) }

    // Seed the sample repository the first time this screen is opened rather
    // than at startup: it costs nothing until someone actually looks for
    // addons, and it keeps the seeding decision next to the UI that explains it.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            store.seedIfNeeded()
            // Anything uninstalled from its own settings screen since the last
            // visit stops claiming to be installed here.
            AddonReconciler.reconcile(context, store)
        }
    }

    fun refreshAll() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                for (ref in store.repos()) {
                    AddonDownloadManager.fetchManifest(store, ref, context.cacheDir)
                }
            }
            refreshing = false
        }
    }

    // One fetch per visit, so a repository that published something new shows
    // it without anyone having to pull the list down. Both gates are the
    // user's; a pull ignores them, because making the gesture is the ask.
    LaunchedEffect(Unit) {
        if (!store.autoRefresh()) return@LaunchedEffect
        if (store.refreshUnmeteredOnly() && isMeteredNow(context)) return@LaunchedEffect
        // Data saving is the third gate, and the same kind of thing: the store
        // keeps its own switch for people who never open the data-saver screen,
        // and either one holding is enough to skip the visit's fetch. Read off
        // the repository rather than passed in — this screen is reached from a
        // deep link as well as from settings, so it has no settings object.
        val dataSaver = SettingsRepository(context).settings.first().dataSaver
        val network = DeviceNetworkState(metered = isMeteredNow(context))
        if (dataSaver.appliesTo(network) &&
            dataSaver.addonRefresh.stopsBackgroundWork
        ) {
            return@LaunchedEffect
        }
        refreshAll()
    }

    // Resolved here rather than in the coroutine below: a background lambda is
    // not a composable, so it cannot read a resource itself.
    val badUrlMessage = stringResource(
        R.string.addon_repo_add_invalid_url_error,
        AddonRepoCodec.MANIFEST_NAME,
    )
    val unreadableRepoMessage = stringResource(R.string.addon_repo_add_unreadable_error)

    if (showAdd) {
        AddRepositoryDialog(
            initialUrl = prefillUrl,
            onDismiss = { showAdd = false },
            onAdd = { pasted ->
                showAdd = false
                scope.launch {
                    val ref = store.addRepo(pasted)
                    if (ref == null) {
                        message = badUrlMessage
                        return@launch
                    }
                    val manifest = withContext(Dispatchers.IO) {
                        AddonDownloadManager.fetchManifest(store, ref, context.cacheDir)
                    }
                    if (manifest == null) {
                        store.removeRepo(ref.manifestUrl)
                        message = unreadableRepoMessage
                    } else {
                        onNavigate(addonRepoRoute(ref.manifestUrl))
                    }
                }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    AddonApplyPrompt()

    // Arrived from a settings screen asking for one kind of addon: that is a
    // state, said on a card, because everything below is narrowed by it and a
    // repository list that silently hides half of what it has is worse than
    // no filter at all. What a repository is rides on the list's heading.
    if (typeFilter != null) {
        StateBanner(
            stringResource(R.string.addon_repos_type_intro_body, stringResource(typeFilter.labelRes)),
        )
    }

    RegisterAddFab(stringResource(R.string.addon_repo_add_action)) { showAdd = true }
    // Pull the list down to fetch every repository again. `refreshAll` is a
    // no-op while one is running, so a second pull cannot start a second fetch.
    RegisterPullRefresh(refreshing, ::refreshAll)

    if (repos.isEmpty()) {
        CaptionText(stringResource(R.string.addon_repos_empty))
    }

    SettingsGroup(
        if (repos.isEmpty()) null else stringResource(R.string.addon_repos_section_title),
        info = stringResource(R.string.addon_repos_intro_body),
    ) {
        for (ref in repos) {
            item {
                ScrollAnchor(ref.manifestUrl == returnTo) {
                    RepositoryRow(ref, store, typeFilter, onNavigate)
                }
            }
        }
    }

    // Only worth showing once there is a repository to fetch from; with none,
    // the auto-fetch has nothing to do and the rows explain nothing.
    if (repos.isNotEmpty()) {
        RefreshSettingsGroup(store, autoRefresh, refreshUnmeteredOnly)
    }

    if (installed.isNotEmpty()) {
        // A record stores the manifest it came from; ones written before that
        // field existed don't, so fall back to matching the repository by the
        // id embedded in the install key.
        val urlByRepoId = remember(repos) {
            repos.mapNotNull { ref ->
                AddonDownloadManager.cachedManifest(ref)?.repo?.id?.let { it to ref.manifestUrl }
            }.toMap()
        }
        SettingsGroup(stringResource(R.string.addon_installed_section_title)) {
            for ((key, record) in installed) {
                item {
                    val url = record.manifestUrl
                        .ifBlank { urlByRepoId[key.substringBeforeLast('/')].orEmpty() }
                        .ifBlank { NO_REPO }
                    // The row's glyph and name are the addon's page, small:
                    // both fly into its heading and its title. The type is not
                    // tagged here — it is the heading's own word, and this row
                    // spends it inside a longer line.
                    val page = addonFlightRoute(url, key.substringAfterLast('/'))
                    ScrollAnchor(key == returnTo) {
                        WmRow(
                            title = record.name.ifBlank { key },
                            titleContent = {
                                Text(
                                    record.name.ifBlank { key },
                                    modifier = Modifier.wmSharedBounds(takeOffKey("name", page)),
                                )
                            },
                            // The type becomes the page's heading and the version
                            // its line of numbers, so they travel as their own
                            // words rather than inside one sentence.
                            supporting = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(record.type.singularLabelRes),
                                        modifier = Modifier.wmSharedBounds(takeOffKey("title", page)),
                                    )
                                    if (record.version.isNotBlank()) {
                                        Text(" · ")
                                        Text(
                                            addonMetaLine(record.version, null, ""),
                                            modifier = Modifier
                                                .wmSharedBounds(takeOffKey("meta", page)),
                                        )
                                    }
                                    if (record.repoName.isNotBlank()) {
                                        Text(" · ${record.repoName}", maxLines = 1)
                                    }
                                }
                            },
                            leading = {
                                Icon(
                                    record.type.icon,
                                    contentDescription = null,
                                    tint = tintFor(record.type),
                                    modifier = Modifier.wmSharedElement(takeOffKey("icon", page)),
                                )
                            },
                            onClick = takeOffClick {
                                ReturnAnchor.arm(ADDONS_ANCHOR, key)
                                onNavigate(addonDetailRoute(url, key.substringAfterLast('/')))
                            },
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
}

/**
 * "Installed. Switch to it?" — asked once, after the install lands.
 *
 * Installing used to apply as it went, so browsing a repository and tapping the
 * download arrow on three themes left the user wearing the third one. The two
 * decisions are now separate (see [com.wasimaster.wmkeyboard.core.addons.AddonApply]),
 * and this is where the second one is put to them.
 *
 * Every addon screen shows it, because the install outlives the screen that
 * started it: the download arrow on a catalogue card keeps working while the
 * user walks to the addon's own page or back to the repository list. Only one
 * of these screens is ever composed at a time, so only one dialog appears.
 */
@Composable
private fun AddonApplyPrompt() {
    val context = LocalContext.current
    val pending by AddonDownloadManager.pendingApply.collectAsStateWithLifecycle()
    val ask = pending ?: return

    AlertDialog(
        onDismissRequest = { AddonDownloadManager.clearPendingApply() },
        title = {
            // The kind's own name stands in when the addon states none. It is
            // read as a resource rather than lower-cased: a translated name
            // has its own capital letters and they are not ours to change.
            val kind = stringResource(ask.record.type.singularLabelRes)
            Text(stringResource(R.string.addon_apply_installed_title, ask.record.name.ifBlank { kind }))
        },
        text = { Text(stringResource(ask.questionRes)) },
        confirmButton = {
            TextButton(
                // Applied on the download manager's own scope rather than this
                // composable's: answering the question is what removes the
                // dialog, and a scope that dies with it would cancel the write
                // it was asked to make.
                onClick = { AddonDownloadManager.applyPending(context) },
            ) { Text(stringResource(AddonApply.confirmLabelRes(ask.record.type))) }
        },
        dismissButton = {
            TextButton(onClick = { AddonDownloadManager.clearPendingApply() }) {
                Text(stringResource(R.string.addon_apply_not_now_action))
            }
        },
    )
}

/**
 * The entries [entry] lists in [AddonEntry.requires] that resolve in this
 * manifest and are not installed yet. An id the manifest doesn't carry is
 * ignored — the dependency is soft either way.
 */
private fun missingRequirements(
    store: AddonStore,
    repoId: String,
    all: List<AddonEntry>,
    entry: AddonEntry,
): List<AddonEntry> {
    if (entry.requires.isEmpty()) return emptyList()
    val installed = store.installed()
    return entry.requires
        .mapNotNull { id -> all.firstOrNull { it.id == id } }
        .filter { installed[it.key(repoId)] == null }
}

/**
 * The install front door shared by the catalogue card and the detail page.
 *
 * [content] gets a `request` to call instead of [AddonDownloadManager.install]:
 * an addon whose [AddonEntry.requires] names uninstalled entries first asks
 * whether to bring them along (a theme's font and sound are their own addons,
 * so the user can reuse them anywhere — and so the theme still works without
 * them). "Download all" installs the dependencies first and the addon last;
 * skipping installs the addon alone, which its fallbacks make valid.
 */
@Composable
private fun RequiresAwareInstall(
    store: AddonStore,
    manifestUrl: String,
    repo: AddonRepoInfo,
    all: List<AddonEntry>,
    content: @Composable (request: (AddonEntry) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var prompt by remember { mutableStateOf<Pair<AddonEntry, List<AddonEntry>>?>(null) }
    content { entry ->
        // Following a link doesn't add the repository; choosing to install
        // from it does. No-op when it is already there.
        store.addRepo(manifestUrl)
        val missing = missingRequirements(store, repo.id, all, entry)
        if (missing.isEmpty()) {
            AddonDownloadManager.install(
                context = context,
                store = store,
                manifestUrl = manifestUrl,
                repo = repo,
                entry = entry,
                appVersionCode = BuildConfig.VERSION_CODE,
            )
        } else {
            prompt = entry to missing
        }
    }
    prompt?.let { (entry, missing) ->
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(stringResource(R.string.addon_requires_title, entry.name)) },
            text = {
                Column {
                    Text(stringResource(R.string.addon_requires_body))
                    Spacer(Modifier.height(12.dp))
                    missing.forEach { dep ->
                        Text(
                            buildString {
                                append(dep.name)
                                dep.sizeBytes?.let { append("  ·  ${formatBytes(it)}") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(dep.type.singularLabelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prompt = null
                        AddonDownloadManager.installAll(
                            context = context,
                            store = store,
                            manifestUrl = manifestUrl,
                            repo = repo,
                            // Dependencies first, and silently: only the addon
                            // that asked for them gets to ask the user anything.
                            dependencies = missing,
                            entry = entry,
                            appVersionCode = BuildConfig.VERSION_CODE,
                        )
                    },
                ) { Text(stringResource(R.string.addon_requires_all_action)) }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
                TextButton(
                    onClick = {
                        prompt = null
                        AddonDownloadManager.install(
                            context = context,
                            store = store,
                            manifestUrl = manifestUrl,
                            repo = repo,
                            entry = entry,
                            appVersionCode = BuildConfig.VERSION_CODE,
                        )
                    },
                ) { Text(stringResource(R.string.addon_requires_skip_action)) }
            },
        )
    }
}

@Composable
private fun RepositoryRow(
    ref: AddonRepoRef,
    store: AddonStore,
    /** Carried into the catalogue this row opens; see [addonRepoRoute]. */
    typeFilter: AddonType?,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manifest = remember(ref.cachedManifest) { AddonDownloadManager.cachedManifest(ref) }
    var menu by remember { mutableStateOf(false) }

    val page = addonRepoFlightRoute(ref.manifestUrl)
    val author = manifest?.repo?.author.orEmpty()
    WmRow(
        title = manifest?.repo?.name?.ifBlank { null } ?: ref.url,
        flightTo = page,
        // The author is its own word here because it becomes the page's
        // subtitle; the addon count stays behind.
        supporting = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    manifest == null && ref.fetchedAt == 0L ->
                        Text(stringResource(R.string.addon_repo_not_loaded))
                    manifest == null -> Text(stringResource(R.string.addon_repo_unreadable))
                    else -> {
                        Text(
                            pluralStringResource(
                                R.plurals.addon_repo_addon_count,
                                manifest.addons.size,
                                manifest.addons.size,
                            ),
                        )
                        if (author.isNotBlank()) {
                            Text(" · ")
                            Text(
                                author,
                                modifier = Modifier.wmSharedBounds(takeOffKey("subtitle", page)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        leading = manifest?.repo?.icon?.let { icon ->
            AddonRepoCodec.resolveAsset(ref.manifestUrl, icon)?.let { url ->
                {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        imageLoader = rememberMediaImageLoader(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.addon_repo_options_desc),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.addon_repo_refresh_action)) },
                        onClick = {
                            menu = false
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AddonDownloadManager.fetchManifest(store, ref, context.cacheDir)
                                }
                            }
                        },
                    )
                    val homepage = manifest?.repo?.homepage.orEmpty()
                    if (homepage.startsWith("https://")) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.addon_repo_homepage_action)) },
                            onClick = {
                                menu = false
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, homepage.toUri()),
                                    )
                                }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(CommonR.string.common_remove)) },
                        onClick = {
                            menu = false
                            store.removeRepo(ref.manifestUrl)
                        },
                    )
                }
            }
        },
        enabled = manifest != null,
        onClick = {
            ReturnAnchor.arm(ADDONS_ANCHOR, ref.manifestUrl)
            onNavigate(addonRepoRoute(ref.manifestUrl, typeFilter))
        },
    )
}

/**
 * The two gates on the fetch-every-visit, extracted from `AddonsScreen` rather
 * than inlined: that function is at detekt's cognitive-complexity ceiling, and
 * these rows are self-contained.
 */
@Composable
private fun RefreshSettingsGroup(
    store: AddonStore,
    autoRefresh: Boolean,
    refreshUnmeteredOnly: Boolean,
) {
    SettingsGroup(stringResource(R.string.addon_refresh_section_title)) {
        item {
            ToggleSetting(
                R.string.addon_auto_refresh_title,
                stringResource(R.string.addon_auto_refresh_subtitle),
                autoRefresh,
                info = stringResource(R.string.addon_auto_refresh_info),
                default = true,
            ) { store.setAutoRefresh(it) }
        }
        if (autoRefresh) {
            item {
                ToggleSetting(
                    R.string.addon_refresh_unmetered_title,
                    stringResource(R.string.addon_refresh_unmetered_subtitle),
                    refreshUnmeteredOnly,
                    default = false,
                ) { store.setRefreshUnmeteredOnly(it) }
            }
        }
    }
}

@Composable
private fun AddRepositoryDialog(
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialUrl) }
    val resolved = remember(text) { AddonRepoCodec.resolveManifestUrl(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.addon_repo_add_action)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.addon_repo_add_body, AddonRepoCodec.MANIFEST_NAME),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.addon_repo_url_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    // Show what will actually be fetched, so a lookalike host is
                    // visible before it is trusted.
                    Text(
                        if (resolved == null) {
                            stringResource(R.string.addon_repo_url_invalid_error)
                        } else {
                            stringResource(R.string.addon_repo_resolved_label, resolved)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resolved == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = resolved != null) {
                Text(stringResource(CommonR.string.common_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

// ---- one repository --------------------------------------------------

/**
 * [initialType] pre-selects a filter chip. It arrives from a settings screen's
 * "Download more …" row, which asked for one kind of addon; the chips are still
 * live, so the user can widen it back out on the spot.
 */
@Composable
internal fun AddonRepoScreen(
    manifestUrl: String,
    initialType: AddonType? = null,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { AddonStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val ref = remember(revision, manifestUrl) { store.repo(manifestUrl) }
    val manifest = remember(ref?.cachedManifest) { ref?.let { AddonDownloadManager.cachedManifest(it) } }

    // rememberSaveable, not remember: opening an addon and coming back should
    // land on the same filtered list you left. Navigation keeps a route's
    // saveable state while it sits on the back stack, so this survives the trip
    // where a plain remember is thrown away with the composition.
    var query by rememberSaveable(manifestUrl) { mutableStateOf("") }
    // Stored by name rather than as the enum — Bundle can hold a String.
    var typeFilterName by rememberSaveable(manifestUrl) {
        mutableStateOf(initialType?.name.orEmpty())
    }
    val typeFilter = remember(typeFilterName) {
        typeFilterName.takeIf { it.isNotEmpty() }
            ?.let { name -> AddonType.entries.firstOrNull { it.name == name } }
    }
    // The card an addon's page was opened from. Saved scroll state alone does
    // not survive this trip: a card grows when its screenshot decodes, so the
    // restored offset is clamped against a catalogue that is still short.
    val returnTo = remember(manifestUrl) { ReturnAnchor.take(repoAnchorKey(manifestUrl)) }

    if (ref == null || manifest == null) {
        CaptionText(stringResource(R.string.addon_repo_read_error))
        return
    }

    LaunchedEffect(manifest) {
        // Reconcile before recomputing statuses, so a theme deleted from the
        // Themes screen shows as available again rather than installed.
        withContext(Dispatchers.IO) { AddonReconciler.reconcile(context, store) }
        AddonDownloadManager.refresh(store, manifest.repo.id, manifest)
    }

    // The page draws from the cached manifest, so a pull is how someone asks
    // this repository what it has published since.
    val repoScope = rememberCoroutineScope()
    var repoRefreshing by remember(manifestUrl) { mutableStateOf(false) }
    RegisterPullRefresh(repoRefreshing) {
        if (repoRefreshing) return@RegisterPullRefresh
        repoRefreshing = true
        repoScope.launch {
            withContext(Dispatchers.IO) {
                AddonDownloadManager.fetchManifest(store, ref, context.cacheDir)
            }
            repoRefreshing = false
        }
    }

    if (manifest.repo.description.isNotBlank()) CaptionText(manifest.repo.description)


    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        label = { Text(stringResource(CommonR.string.common_search)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )

    val presentTypes = remember(manifest) {
        // Catalogue order, not manifest order, so the chip row doesn't reshuffle
        // between two repositories that list the same types.
        AddonType.entries
            .filter { type -> manifest.addons.any { it.type.storeCategory == type } }
            .map { it.storeCategory }
            .distinct()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = typeFilter == null,
            onClick = { typeFilterName = "" },
            label = { Text(stringResource(R.string.addon_filter_all_label)) },
        )
        for (type in presentTypes) {
            val tint = tintFor(type)
            FilterChip(
                selected = typeFilter == type,
                onClick = { typeFilterName = if (typeFilter == type) "" else type.name },
                label = { Text(stringResource(type.labelRes)) },
                leadingIcon = {
                    Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tint.copy(alpha = 0.18f),
                    selectedLabelColor = tint,
                    selectedLeadingIconColor = tint,
                    iconColor = tint,
                ),
            )
        }
    }

    val shown = remember(manifest, query, typeFilter) {
        manifest.addons.filter { entry ->
            (typeFilter == null || entry.type.storeCategory == typeFilter) && entry.matches(query)
        }
    }

    if (shown.isEmpty()) CaptionText(stringResource(R.string.addon_catalogue_empty))

    // Two per row, the same shape the themes gallery uses. A card can show the
    // addon's first screenshot, which a list row cannot, and screenshots are
    // most of what tells two themes apart.
    Spacer(Modifier.height(8.dp))
    RequiresAwareInstall(store, manifestUrl, manifest.repo, manifest.addons) { request ->
        for (row in shown.chunked(2)) {
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                for (entry in row) {
                    Box(modifier = Modifier.weight(1f)) {
                        ScrollAnchor(entry.id == returnTo) {
                            AddonCard(
                                entry,
                                manifest.repo,
                                manifestUrl,
                                onInstall = { request(entry) },
                            ) {
                                ReturnAnchor.arm(repoAnchorKey(manifestUrl), entry.id)
                                onNavigate(addonDetailRoute(manifestUrl, entry.id))
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    AddonApplyPrompt()
    Spacer(Modifier.height(16.dp))
}

private fun AddonEntry.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return name.contains(needle, ignoreCase = true) ||
        description.contains(needle, ignoreCase = true) ||
        author.contains(needle, ignoreCase = true) ||
        tags.any { it.contains(needle, ignoreCase = true) }
}

/**
 * How tall and how wide a catalogue card's screenshot is allowed to make it,
 * as width ÷ height.
 *
 * [MIN_PREVIEW_RATIO] is the tall end: a whole-phone screenshot is around 0.46
 * and, in a two-column grid, a card of that shape is most of a screen for one
 * addon. [MAX_PREVIEW_RATIO] is the short end, where a keyboard-only strip runs
 * past 2.5 and the card degenerates into a letterbox with a caption.
 */
private const val MIN_PREVIEW_RATIO = 0.62f
private const val MAX_PREVIEW_RATIO = 2.2f

/** Used until the image reports its own size, and for the glyph placeholder. */
private const val DEFAULT_PREVIEW_RATIO = 4f / 3f

/**
 * One addon in the catalogue grid: its first screenshot (or its type's glyph
 * when it has none), then the name and the metadata under it.
 */
@Composable
private fun AddonCard(
    entry: AddonEntry,
    repo: AddonRepoInfo,
    manifestUrl: String,
    /** The download arrow / Update badge. Routed through [RequiresAwareInstall]. */
    onInstall: () -> Unit,
    onClick: () -> Unit,
) {
    val states by AddonDownloadManager.states.collectAsStateWithLifecycle()
    val status = states[entry.key(repo.id)] ?: AddonDownloadManager.AddonStatus.NotInstalled
    val tint = tintFor(entry.type)
    val preview = remember(entry, manifestUrl) {
        entry.previews.firstNotNullOfOrNull { AddonRepoCodec.resolveAsset(manifestUrl, it) }
    }
    // The card is what the addon's page is made of: the screenshot, the name,
    // the type and the line of numbers all fly across rather than being
    // redrawn on the other side.
    val page = addonFlightRoute(manifestUrl, entry.id)

    Column(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.06f))
            .clickable(onClick = takeOffClick(onClick))
            .padding(6.dp),
    ) {
        // The card's shape follows its screenshot instead of forcing every one
        // into the same box. A theme is usually shot as a whole phone screen
        // and a sticker pack as a wide strip; cropping both to 4:3 threw away
        // the half of each that said what it was. Clamped at both ends so one
        // extreme picture can't own the whole column — past the clamp the image
        // letterboxes against the card's tint rather than being cut.
        var ratio by remember(preview) { mutableFloatStateOf(DEFAULT_PREVIEW_RATIO) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                AsyncImage(
                    model = preview,
                    contentDescription = null,
                    imageLoader = rememberMediaImageLoader(),
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        state.painter.intrinsicSize.takeIf { it.isSpecified }?.let { size ->
                            if (size.width > 0f && size.height > 0f) {
                                ratio = (size.width / size.height)
                                    .coerceIn(MIN_PREVIEW_RATIO, MAX_PREVIEW_RATIO)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .wmSharedBounds(takeOffKey("image", page)),
                )
            } else {
                Icon(
                    entry.type.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(40.dp)
                        .wmSharedElement(takeOffKey("icon", page)),
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                StatusBadge(status, hasPreview = preview != null, onInstall = onInstall)
            }
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 4.dp, top = 6.dp)
                .wmSharedBounds(takeOffKey("name", page)),
        )
        // The type is what the addon's page is headed with, so this is the
        // word that grows into that heading.
        Text(
            stringResource(entry.type.singularLabelRes),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 4.dp)
                .wmSharedBounds(takeOffKey("title", page)),
        )
        Text(
            addonMetaLine(entry.version, entry.sizeBytes, entry.author),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 4.dp, bottom = 4.dp)
                .wmSharedBounds(takeOffKey("meta", page)),
        )
    }
}

/**
 * The card's corner control: what this addon's state is, and — when there is
 * something to do about it — the tap that does it.
 *
 * [onInstall] runs on the download arrow and on Update, so the grid installs
 * without a trip through the detail page. It sits on top of a screenshot, so
 * with [hasPreview] it gets an opaque disc behind it; the same glyph over a
 * pale illustration is invisible.
 */
@Composable
private fun StatusBadge(
    status: AddonDownloadManager.AddonStatus,
    hasPreview: Boolean,
    onInstall: () -> Unit,
) {
    val scrim: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (hasPreview) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
                content = { content() },
            )
        } else {
            content()
        }
    }

    when (status) {
        is AddonDownloadManager.AddonStatus.Installed -> scrim {
            Icon(
                Icons.Outlined.Check,
                contentDescription = stringResource(R.string.addon_status_installed),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        is AddonDownloadManager.AddonStatus.UpdateAvailable ->
            AssistChip(
                onClick = onInstall,
                label = { Text(stringResource(CommonR.string.common_update)) },
            )
        is AddonDownloadManager.AddonStatus.Downloading,
        AddonDownloadManager.AddonStatus.Verifying,
        AddonDownloadManager.AddonStatus.Installing,
        -> scrim { CircularProgressIndicator(modifier = Modifier.size(20.dp)) }
        is AddonDownloadManager.AddonStatus.Failed -> scrim {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.addon_status_failed_desc),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        AddonDownloadManager.AddonStatus.NotInstalled -> scrim {
            IconButton(onClick = onInstall, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = stringResource(CommonR.string.common_install),
                )
            }
        }
    }
}

// ---- one addon -------------------------------------------------------

/**
 * The settings screen that owns an installed addon of this type — where Use
 * sends the user once it is installed.
 *
 * Every type lands somewhere it can actually be selected; installing a font
 * doesn't pick it for anything on its own, and neither does a layout or a
 * dictionary, so the route is how the install gets finished.
 */
private val AddonType.settingsRoute: String
    get() = when (this) {
        AddonType.Theme -> "themes"
        // Languages, not Key layouts. An installed layout arrives switched off,
        // and the switch that turns it on lives under Languages → Your layouts;
        // Key layouts lists only layouts that are already on, so sending
        // someone there to enable one would show them an empty section.
        AddonType.Layout -> "languages"
        AddonType.Dictionary -> "customdictionaries"
        AddonType.EmojiKeywords -> "emojikeywords"
        AddonType.Snippets, AddonType.Espanso -> "expander"
        AddonType.Stickers -> "sticker_packs"
        AddonType.IconPack -> "icons"
        AddonType.Font -> "fonts"
        AddonType.EmojiFont -> "emoji"
        AddonType.Sound -> "keypress"
        AddonType.SoundPack -> "keypress"
        AddonType.Plugin -> "plugins"
        AddonType.Unknown -> "home"
    }

/**
 * The row or section on [settingsRoute] that actually chooses an addon of this
 * type, matched by title through [SettingsHighlight].
 *
 * Landing on the right screen is only half of Use: "Emoji" is a long screen and
 * the emoji font sits a third of the way down it. Naming the control scrolls to
 * it and flashes it, exactly as picking a search result does.
 *
 * Null where the screen *is* the control — the themes gallery is nothing but
 * the choice, so there is nothing to single out.
 */
@get:StringRes
private val AddonType.settingsAnchor: Int
    get() = when (this) {
        AddonType.Theme -> 0
        // Languages is a long screen and the layout switches are two thirds of
        // the way down it, under the languages themselves.
        AddonType.Layout -> R.string.langemoji_lang_your_layouts_title
        AddonType.Dictionary -> 0
        AddonType.EmojiKeywords -> 0
        AddonType.Snippets, AddonType.Espanso -> 0
        AddonType.Stickers -> R.string.import_sticker_packs_section_title
        AddonType.IconPack -> R.string.plugins_icons_pack_title
        AddonType.Font -> R.string.fonts_installed_header
        AddonType.EmojiFont -> R.string.langemoji_emoji_font_title
        AddonType.Sound -> R.string.hardware_sound_style_title
        AddonType.SoundPack -> R.string.hardware_sound_pack_title
        // The master switch, not the installed list: someone sent here has
        // almost always come from "plugins are off".
        AddonType.Plugin -> R.string.plugins_allow_title
        AddonType.Unknown -> 0
    }

/**
 * Opens the screen that owns this type, scrolled to the addon itself.
 *
 * [localRef] is the handle the install produced — a custom theme or layout id, a
 * pack or font id, a word list's path. The owning screen lists its things under
 * exactly those ids, so it is what puts the user in front of the one they just
 * installed rather than at the top of a list of thirty. A snippet pack installs
 * several at once and hands over all of their ids.
 *
 * Blank for an addon that isn't installed yet (the Use button only appears once
 * it is) and for a record written before the type recorded a handle, and then
 * the type's section anchor is all there is — still the right screen, still the
 * right section, just not the right row.
 *
 * The anchor is armed before navigating because the destination's rows read it
 * during their first composition — the same order the search screen uses.
 */
private fun AddonType.openSettings(onNavigate: (String) -> Unit, localRef: String = "") {
    val anchor = settingsAnchor
    // Snippets are stored as one comma-joined list of the ids the import added.
    val keys = if (storeCategory == AddonType.Snippets) {
        localRef.split(',').map { it.trim() }
    } else {
        listOf(localRef)
    }
    SettingsHighlight.requestItems(keys, anchor)
    onNavigate(settingsRoute)
}

/**
 * What the Use button promises, in the language of the screen it opens.
 *
 * Three of these destinations are tools, and a tool has one name across the
 * whole app: those read the keyboard module's own resource rather than carry a
 * second copy for translators to keep in step.
 */
@get:StringRes
private val AddonType.useLabelRes: Int
    get() = when (this) {
        AddonType.Theme -> R.string.addon_use_target_theme_label
        AddonType.Layout -> R.string.addon_use_target_layout_label
        AddonType.Dictionary -> R.string.addon_use_target_dictionary_label
        AddonType.EmojiKeywords -> R.string.addon_use_target_emoji_keywords_label
        AddonType.Snippets, AddonType.Espanso -> ImeR.string.ime_tool_snippets
        AddonType.Stickers -> R.string.addon_use_target_stickers_label
        AddonType.IconPack -> R.string.addon_use_target_icons_label
        AddonType.Font -> R.string.addon_use_target_font_label
        AddonType.EmojiFont -> ImeR.string.ime_tool_emoji
        AddonType.Sound -> R.string.addon_use_target_sound_label
        AddonType.SoundPack -> R.string.addon_use_target_sound_pack_label
        AddonType.Plugin -> ImeR.string.ime_tool_plugins
        AddonType.Unknown -> CommonR.string.common_settings
    }

@Composable
internal fun AddonDetailScreen(
    manifestUrl: String,
    addonId: String,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AddonStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val ref = remember(revision, manifestUrl) { store.repo(manifestUrl) }
    var manifest by remember(ref?.cachedManifest) {
        mutableStateOf(ref?.let { AddonDownloadManager.cachedManifest(it) })
    }

    // A deep link can point at a repository the user has not added. Fetch its
    // manifest so the addon can be shown, but do NOT add the repository — a
    // link must not be able to change the user's repository list on its own.
    // Installing is what adds it, and that is the user's own tap.
    LaunchedEffect(manifestUrl) {
        if (manifest != null || !manifestUrl.startsWith("https://")) return@LaunchedEffect
        manifest = withContext(Dispatchers.IO) {
            AddonDownloadManager.fetchManifest(manifestUrl, context.cacheDir)
        }
    }

    val loaded = manifest
    val entry = loaded?.addons?.firstOrNull { it.id == addonId }
    if (loaded == null || entry == null) {
        // No manifest means offline, or a repository that dropped the addon,
        // or one the user removed. If it is installed none of that matters —
        // the record holds everything this page needs, and "manage the thing
        // you installed" should not require a working connection.
        val local = remember(revision, manifestUrl, addonId) {
            store.installedFor(manifestUrl, addonId)
        }
        // Uninstalling from the offline page empties the record out from under
        // it; "that addon couldn't be found" would be a strange thing to say
        // about something the user just removed on this screen.
        var hadLocal by remember(manifestUrl, addonId) { mutableStateOf(false) }
        LaunchedEffect(local != null) { if (local != null) hadLocal = true }
        when {
            local != null -> InstalledAddonDetail(local.first, local.second, store, onNavigate)
            hadLocal -> StateBanner(stringResource(R.string.addon_uninstalled_body))
            else -> StateBanner(stringResource(R.string.addon_detail_not_found), tone = BannerTone.WARNING)
        }
        return
    }

    val states by AddonDownloadManager.states.collectAsStateWithLifecycle()
    val key = entry.key(loaded.repo.id)
    val status = states[key] ?: AddonDownloadManager.AddonStatus.NotInstalled
    LaunchedEffect(loaded) {
        withContext(Dispatchers.IO) { AddonReconciler.reconcile(context, store) }
        AddonDownloadManager.refresh(store, loaded.repo.id, loaded)
    }

    val previews = remember(entry, manifestUrl) {
        entry.previews.mapNotNull { AddonRepoCodec.resolveAsset(manifestUrl, it) }
    }
    // The page is headed with the addon's type, in the type's own colour, so
    // the card's own type label is what grew into that heading — and the name
    // and the numbers under it are the card's, landed.
    PreviewGallery(previews, heroKey = landingKey("image"))

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            entry.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.wmSharedBounds(landingKey("name")),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                addonMetaLine(entry.version, entry.sizeBytes, entry.author),
                modifier = Modifier.wmSharedBounds(landingKey("meta")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(entry.description, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (entry.tags.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (tag in entry.tags) AssistChip(onClick = {}, label = { Text(tag) })
        }
    }

    if (entry.type.previewable) {
        AddonPreviewSection(manifestUrl, entry)
    }

    SettingsGroup(stringResource(R.string.addon_details_section_title)) {
        item {
            // The host is the security-relevant fact — a repository can call
            // itself anything, so show where the file actually comes from.
            val host = remember(manifestUrl) {
                runCatching { manifestUrl.toUri().host }.getOrNull().orEmpty()
            }
            // Read before the buildString: that block is not a composable.
            val unnamed = stringResource(R.string.addon_repo_unnamed)
            val notInList = stringResource(R.string.addon_detail_repo_not_added)
            DetailRow(
                stringResource(R.string.addon_detail_repository_label),
                buildString {
                    append(loaded.repo.name.ifBlank { unnamed })
                    if (host.isNotBlank()) append("\n$host")
                    if (ref == null) append("\n$notInList")
                },
            )
        }
        entry.sizeBytes?.let {
            item { DetailRow(stringResource(R.string.addon_detail_size_label), formatBytes(it)) }
        }
        val languages = entry.languages
        if (languages.isNotEmpty()) {
            item {
                DetailRow(
                    pluralStringResource(R.plurals.addon_detail_language_label, languages.size),
                    languages.joinToString { LanguageRegistry.byId(it).displayName },
                )
            }
        }
        if (entry.hasLicense) {
            item { LicenseRow(manifestUrl, entry) }
        }
    }

    val minAppVersion = entry.minAppVersion
    val tooOld = minAppVersion != null && minAppVersion > BuildConfig.VERSION_CODE
    if (tooOld) {
        StateBanner(stringResource(R.string.addon_detail_needs_newer_app), tone = BannerTone.WARNING)
    }

    // The plugin master switch gates installing, so say so *before* the tap
    // rather than refusing afterwards — and offer the way to the switch. The
    // Plugins *tool* being on the toolbar is a different setting entirely,
    // which is exactly why "turn on plugins" on its own reads as already done.
    val pluginStore = remember { PluginStore.get(context) }
    val pluginRevision by pluginStore.revision.collectAsStateWithLifecycle()
    val pluginsOff = remember(pluginRevision, entry.type) {
        entry.type == AddonType.Plugin && !pluginStore.subsystemEnabled()
    }
    if (pluginsOff) {
        StateBanner(
            stringResource(R.string.addon_detail_plugins_off_body),
            action = stringResource(R.string.addon_open_screen_action, stringResource(ImeR.string.ime_tool_plugins)),
        ) { AddonType.Plugin.openSettings(onNavigate) }
    }

    AddonActions(
        status = status,
        entry = entry,
        repo = loaded.repo,
        manifestUrl = manifestUrl,
        store = store,
        allEntries = loaded.addons,
        blocked = tooOld || pluginsOff,
        onUninstall = {
            scope.launch { AddonDownloadManager.uninstall(context, store, key, entry) }
        },
        onNavigate = onNavigate,
    )

    AddonApplyPrompt()
    Spacer(Modifier.height(24.dp))
}

/** Height a lone screenshot may not exceed, so the addon's name stays on screen. */
private val SINGLE_PREVIEW_MAX_HEIGHT = 340.dp

/** Height of the strip when there is more than one screenshot to scroll through. */
private val PREVIEW_STRIP_HEIGHT = 220.dp

/**
 * The addon's screenshots, above everything else on its page, and the way into
 * the full-screen viewer.
 *
 * A single screenshot is given the width of the page rather than the strip's
 * fixed height: one image in a scrolling row of one looks like a strip that
 * failed to load the rest, and most addons ship exactly one. It is still capped
 * — a whole-phone screenshot allowed to fill the width would be taller than the
 * screen and push the addon's own name below the fold, so past the cap it keeps
 * [SINGLE_PREVIEW_MAX_HEIGHT] and centres instead.
 */
@Composable
private fun PreviewGallery(previews: List<String>, heroKey: String? = null) {
    if (previews.isEmpty()) return
    val loader = rememberMediaImageLoader()
    var viewing by remember(previews) { mutableIntStateOf(-1) }

    if (previews.size == 1) {
        val url = previews.first()
        var ratio by remember(url) { mutableFloatStateOf(0f) }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val height = if (ratio > 0f) {
                minOf(maxWidth / ratio, SINGLE_PREVIEW_MAX_HEIGHT)
            } else {
                PREVIEW_STRIP_HEIGHT
            }
            // The flight lands on the box, not on the image: an AsyncImage
            // with nothing loaded yet measures zero wide, and a flight whose
            // target is an empty rect has nowhere to go — which is why opening
            // a card used to pop the page in instead of growing it out of the
            // card, while going back (image long since loaded) looked right.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .then(if (heroKey == null) Modifier else Modifier.wmSharedBounds(heroKey))
                    .clickable { viewing = 0 },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = stringResource(R.string.addon_screenshot_desc),
                    imageLoader = loader,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        state.painter.intrinsicSize.takeIf { it.isSpecified }?.let { size ->
                            if (size.width > 0f && size.height > 0f) ratio = size.width / size.height
                        }
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            previews.forEachIndexed { index, url ->
                // Same story as above: a definite box so the first shot is
                // somewhere to land even before it has loaded.
                Box(
                    modifier = Modifier
                        .width(PREVIEW_STRIP_HEIGHT * DEFAULT_PREVIEW_RATIO)
                        .height(PREVIEW_STRIP_HEIGHT)
                        .then(
                            if (index != 0 || heroKey == null) Modifier
                            else Modifier.wmSharedBounds(heroKey),
                        )
                        .clickable { viewing = index },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = url,
                        contentDescription = stringResource(
                            R.string.addon_screenshot_numbered_desc,
                            index + 1,
                        ),
                        imageLoader = loader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }

    if (viewing >= 0) {
        ImageViewerDialog(previews, viewing) { viewing = -1 }
    }
}

/**
 * The same page for an addon whose manifest we can't read — offline, repository
 * removed, or the addon delisted — built entirely from what the install
 * recorded.
 *
 * Update isn't offered here: without a manifest there is no version to compare
 * against. Everything else an installed addon's page is for — what it is, where
 * it came from, going to it, removing it — needs no network at all.
 */
@Composable
private fun InstalledAddonDetail(
    key: String,
    record: InstalledAddon,
    store: AddonStore,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tint = tintFor(record.type)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            record.name.ifBlank { key.substringAfterLast('/') },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                record.type.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            // Read before the buildString: that block is not a composable.
            val kind = stringResource(record.type.singularLabelRes)
            Text(
                buildString {
                    append(kind)
                    if (record.version.isNotBlank()) append(" · ${record.version}")
                    if (record.author.isNotBlank()) append(" · ${record.author}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (record.description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(record.description, style = MaterialTheme.typography.bodyMedium)
        }
    }

    SettingsGroup(stringResource(R.string.addon_details_section_title)) {
        item {
            DetailRow(
                stringResource(R.string.addon_detail_status_label),
                stringResource(R.string.addon_status_installed),
            )
        }
        if (record.repoName.isNotBlank()) {
            item {
                DetailRow(
                    stringResource(R.string.addon_detail_repository_label),
                    record.repoName,
                )
            }
        }
    }
    StateBanner(stringResource(R.string.addon_detail_offline_body))

    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UninstallButton {
            scope.launch { AddonDownloadManager.uninstall(context, store, key, entry = null) }
        }
    }
    OutlinedButton(
        onClick = { record.type.openSettings(onNavigate, record.localRef) },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                R.string.addon_use_open_action,
                stringResource(record.type.useLabelRes),
            ),
        )
    }
    Spacer(Modifier.height(24.dp))
}

/** Install / Update / Uninstall / Use, and the progress the transfer reports. */
@Composable
private fun AddonActions(
    status: AddonDownloadManager.AddonStatus,
    entry: AddonEntry,
    repo: AddonRepoInfo,
    manifestUrl: String,
    store: AddonStore,
    /** Every entry in the manifest, for resolving [AddonEntry.requires]. */
    allEntries: List<AddonEntry>,
    /** Install is refused: the app is too old, or the type's subsystem is off. */
    blocked: Boolean,
    onUninstall: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    when (status) {
        is AddonDownloadManager.AddonStatus.Downloading -> {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (status.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (status.bytes.toFloat() / status.totalBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (status.totalBytes > 0) {
                        stringResource(
                            R.string.addon_download_progress,
                            formatBytes(status.bytes),
                            formatBytes(status.totalBytes),
                        )
                    } else {
                        formatBytes(status.bytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { AddonDownloadManager.cancel() }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        }

        AddonDownloadManager.AddonStatus.Verifying ->
            CaptionText(stringResource(R.string.addon_checking_progress))

        AddonDownloadManager.AddonStatus.Installing ->
            CaptionText(stringResource(CommonR.string.common_installing))

        else -> {
            val installed = status is AddonDownloadManager.AddonStatus.Installed
            val updatable = status is AddonDownloadManager.AddonStatus.UpdateAvailable
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RequiresAwareInstall(store, manifestUrl, repo, allEntries) { request ->
                    Button(
                        onClick = { request(entry) },
                        enabled = !blocked && !installed,
                    ) {
                        Text(
                            when {
                                updatable -> stringResource(CommonR.string.common_update)
                                installed -> stringResource(R.string.addon_status_installed)
                                else -> stringResource(CommonR.string.common_install)
                            },
                        )
                    }
                }
                if (installed || updatable) UninstallButton(onUninstall)
            }
            // Installing puts the file on the device; for most types choosing it
            // is a second step on another screen. This is the way there — and
            // the install record is what names the thing to stand in front of
            // once the screen opens.
            if (installed || updatable) {
                val localRef = store.installed(entry.key(repo.id))?.localRef.orEmpty()
                OutlinedButton(
                    onClick = { entry.type.openSettings(onNavigate, localRef) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            R.string.addon_use_open_action,
                            stringResource(entry.type.useLabelRes),
                        ),
                    )
                }
            }
            if (status is AddonDownloadManager.AddonStatus.Failed) {
                Text(
                    status.text.resolve(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/**
 * Uninstall, in the error colour.
 *
 * It sits next to Install and Use and is the only one of the three that takes
 * something away; an outlined button in the default tint reads as one more
 * neutral option.
 */
@Composable
private fun UninstallButton(onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
        border = BorderStroke(1.dp, error.copy(alpha = 0.5f)),
    ) {
        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(CommonR.string.common_uninstall))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    WmRow(
        title = label,
        subtitle = value,
    )
}

/**
 * The addon's licence. An identifier shows inline; full text — declared in the
 * manifest or living in a file beside it — opens in a dialog, which is the only
 * honest way to show something that can run to hundreds of lines.
 */
@Composable
private fun LicenseRow(manifestUrl: String, entry: AddonEntry) {
    val context = LocalContext.current
    var showing by remember { mutableStateOf(false) }
    var text by remember(entry.id) { mutableStateOf(entry.licenseText.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    val hasFile = !entry.licenseFile.isNullOrBlank()
    val canShowText = text.isNotBlank() || hasFile

    LaunchedEffect(showing) {
        if (!showing || text.isNotBlank() || !hasFile) return@LaunchedEffect
        loading = true
        text = withContext(Dispatchers.IO) {
            AddonDownloadManager.fetchText(manifestUrl, entry.licenseFile.orEmpty(), context.cacheDir)
        }.orEmpty()
        loading = false
    }

    val licenceHint = if (canShowText) {
        stringResource(R.string.addon_licence_read_hint)
    } else {
        stringResource(R.string.addon_licence_not_stated)
    }
    WmRow(
        title = stringResource(R.string.addon_licence_title),
        subtitle = entry.license?.takeIf { it.isNotBlank() } ?: licenceHint,
        leading = { Icon(Icons.Outlined.Gavel, contentDescription = null) },
        trailing = if (canShowText) {
            { Icon(Icons.Outlined.Description, contentDescription = null) }
        } else {
            null
        },
        enabled = canShowText,
        onClick = { showing = true },
    )

    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = {
                Text(
                    entry.license?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.addon_licence_title),
                )
            },
            text = {
                // A licence runs to hundreds of lines; the dialog body scrolls
                // rather than pushing its own buttons off the screen.
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val body = when {
                        loading -> stringResource(CommonR.string.common_loading)
                        text.isBlank() -> stringResource(R.string.addon_licence_download_error)
                        else -> text
                    }
                    Text(withLinks(body), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showing = false }) {
                    Text(stringResource(CommonR.string.common_close))
                }
            },
        )
    }
}

/**
 * Bare URLs in plain text, made tappable.
 *
 * A licence is mostly a pointer: half of them are three lines of attribution
 * and a link to the deed that says what you may actually do. Leaving that as
 * dead text in a dialog nobody can copy out of makes the licence unreadable in
 * the only sense that matters.
 */
private val URL_PATTERN = Regex("""https?://[^\s<>"')\]]+""")

/** Trailing punctuation belongs to the sentence, not to the address. */
private const val URL_TRAILING = ".,;:!?"

@Composable
private fun withLinks(text: String): AnnotatedString {
    // Keyed on the colour, not on the TextLinkStyles: a fresh instance every
    // composition would make the remember do nothing.
    val accent = MaterialTheme.colorScheme.primary
    return remember(text, accent) {
        val style = TextLinkStyles(
            style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
        )
        buildAnnotatedString {
            var at = 0
            for (match in URL_PATTERN.findAll(text)) {
                val url = match.value.trimEnd { it in URL_TRAILING }
                if (url.isEmpty()) continue
                append(text.substring(at, match.range.first))
                withLink(LinkAnnotation.Url(url, style)) { append(url) }
                at = match.range.first + url.length
            }
            append(text.substring(at))
        }
    }
}

// ---- payload preview -------------------------------------------------

/**
 * "Show me what's actually in this before I install it."
 *
 * Only offered for the types where the content *is* the choice — the words in a
 * dictionary, the snippets in a pack, the sound itself, the sticker images. It
 * downloads the payload to the cache and reads it; nothing is installed and no
 * setting changes.
 */
@Composable
private fun AddonPreviewSection(manifestUrl: String, entry: AddonEntry) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember(entry.id) { mutableStateOf<AddonPreviewContent?>(null) }
    var loading by remember(entry.id) { mutableStateOf(false) }
    var failed by remember(entry.id) { mutableStateOf(false) }

    if (content == null) {
        OutlinedButton(
            onClick = {
                if (loading) return@OutlinedButton
                loading = true
                failed = false
                scope.launch {
                    val read = withContext(Dispatchers.IO) {
                        AddonDownloadManager.fetchPayload(manifestUrl, entry, context.cacheDir)
                            ?.let { AddonPreviewReader.read(entry, it) }
                    }
                    loading = false
                    if (read == null) failed = true else content = read
                }
            },
            enabled = !loading,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Outlined.Visibility,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (loading) stringResource(R.string.addon_preview_loading_progress)
                else stringResource(R.string.addon_preview_action),
            )
        }
        if (failed) {
            Text(
                stringResource(R.string.addon_preview_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        return
    }

    val previewTitle = stringResource(R.string.addon_preview_section_title)
    when (val shown = content) {
        is AddonPreviewContent.Snippets -> SettingsGroup(previewTitle) {
            // Before the snippets, not after: what a converted pack loses is
            // part of deciding whether to install it.
            if (shown.notes.isNotEmpty()) {
                item {
                    CaptionText(
                        stringResource(R.string.addon_preview_snippet_changes) + "\n" +
                            shown.notes.joinToString("\n") { "• ${it.resolve(context)}" },
                    )
                }
            }
            for (snippet in shown.entries) {
                item {
                    WmRow(
                        title = snippet.label,
                        supporting = {
                            Column {
                                Text(snippet.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                // Whether an installed snippet rewrites what you
                                // type or waits to be asked is the thing worth
                                // knowing before installing somebody else's
                                // pack, so both answers are spelled out rather
                                // than one being left to be inferred from the
                                // absence of the other. A snippet with no
                                // trigger does neither: it is panel-only.
                                if (snippet.trigger.isNotBlank()) {
                                    Text(
                                        stringResource(
                                            if (snippet.confirm) {
                                                R.string.addon_preview_snippet_asks
                                            } else {
                                                R.string.addon_preview_snippet_auto
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        trailing = snippet.trigger.takeIf { it.isNotBlank() }?.let {
                            { Text(it, style = MaterialTheme.typography.labelSmall) }
                        },
                    )
                }
            }
            if (shown.total > shown.entries.size) {
                item {
                    val more = shown.total - shown.entries.size
                    CaptionText(pluralStringResource(R.plurals.addon_preview_more_count, more, more))
                }
            }
        }

        is AddonPreviewContent.Dictionary -> DictionaryPreview(shown)

        is AddonPreviewContent.EmojiKeywords -> SettingsGroup(previewTitle) {
            item {
                CaptionText(
                    if (shown.truncated) {
                        pluralStringResource(
                            R.plurals.addon_preview_emoji_count_over,
                            shown.total,
                            shown.total,
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.addon_preview_emoji_count,
                            shown.total,
                            shown.total,
                        )
                    },
                )
            }
            for (sample in shown.samples) {
                item {
                    ListItem(
                        leadingContent = {
                            Text(sample.emoji, style = MaterialTheme.typography.titleLarge)
                        },
                        headlineContent = {
                            Text(sample.keywords, style = MaterialTheme.typography.bodySmall)
                        },
                        colors = transparentListColors(),
                    )
                }
            }
        }

        is AddonPreviewContent.Sound -> SettingsGroup(previewTitle) {
            item {
                WmRow(
                    title = stringResource(R.string.addon_preview_play_sound_title),
                    leading = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = { AddonSoundPreview.play(shown.file) },
                )
            }
        }

        is AddonPreviewContent.SoundPack -> SettingsGroup(previewTitle) {
            item {
                CaptionText(
                    pluralStringResource(
                        R.plurals.addon_preview_pack_variant_count,
                        shown.totalVariants,
                        shown.totalVariants,
                    ),
                )
            }
            // One row per variant rather than one Play button: hearing the
            // difference between them is the only way to judge a pack, and a
            // single button that plays a random one makes that a guessing game.
            for ((index, variant) in shown.variants.withIndex()) {
                item {
                    WmRow(
                        title = stringResource(R.string.addon_preview_play_variant_title, index + 1),
                        leading = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                        onClick = { AddonSoundPreview.play(variant) },
                    )
                }
            }
            // A pack with key-up recordings is two packs' worth of judgement,
            // and the rows above only ever played one of them.
            if (shown.releaseVariants.isNotEmpty()) {
                item {
                    CaptionText(
                        pluralStringResource(
                            R.plurals.addon_preview_pack_release_count,
                            shown.totalReleaseVariants,
                            shown.totalReleaseVariants,
                        ),
                    )
                }
                for ((index, variant) in shown.releaseVariants.withIndex()) {
                    item {
                        WmRow(
                            title = stringResource(
                                R.string.addon_preview_play_release_title,
                                index + 1,
                            ),
                            leading = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                            onClick = { AddonSoundPreview.play(variant) },
                        )
                    }
                }
            }
            if (shown.roles.isNotEmpty()) {
                item {
                    CaptionText(
                        stringResource(
                            R.string.addon_preview_pack_roles_caption,
                            shown.roles.joinToString(", "),
                        ),
                    )
                }
            }
        }

        is AddonPreviewContent.Stickers -> {
            SettingsGroup(previewTitle) {
                item {
                    CaptionText(
                        pluralStringResource(
                            R.plurals.addon_preview_sticker_count,
                            shown.total,
                            shown.total,
                        ),
                    )
                }
            }
            val loader = rememberMediaImageLoader()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (image in shown.images) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        imageLoader = loader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
        }

        is AddonPreviewContent.Plugin -> PluginPreview(shown)

        is AddonPreviewContent.Unreadable -> CaptionText(shown.text.resolve(context))
        null -> Unit
    }
}

/**
 * What a plugin is, and what it would be allowed to do, before it is installed.
 *
 * The one preview that exists for the user's safety rather than their taste.
 * Everything else in this file previews content so someone can decide whether
 * they like it; this previews *capability*, so they can decide whether to let a
 * stranger's code run on their keyboard at all. It is deliberately shown above
 * the Install button and phrased as plainly as the facts allow.
 *
 * The reassurance underneath is not marketing. There is no API in the sandbox
 * for reading typed text, the field, the clipboard or the network, so "it cannot
 * see what you type" is a statement about what was built, not a promise about
 * how it behaves.
 */
@Composable
private fun PluginPreview(plugin: AddonPreviewContent.Plugin) {
    SettingsGroup(stringResource(R.string.addon_plugin_permissions_title)) {
        if (plugin.permissions.isEmpty()) {
            item {
                WmRow(
                    title = stringResource(R.string.addon_plugin_no_permissions_title),
                    leading = {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    },
                )
            }
        } else {
            for (permission in plugin.permissions) {
                item {
                    WmRow(
                        title = stringResource(permission.labelRes),
                        leading = {
                            Icon(Icons.Outlined.Save, contentDescription = null)
                        },
                    )
                }
            }
        }
        item {
            CaptionText(stringResource(R.string.addon_plugin_sandbox_body))
        }
    }
}

/** How many words the panel itself shows before the dialog takes over. */
private const val INLINE_WORDS = 60

/**
 * A word list: a taste of it inline, the whole thing in a dialog.
 *
 * "Which words are in here" is the only question a dictionary raises, and a
 * sample can't answer it — the point of installing one is usually a specific
 * vocabulary. The dialog is a real scrolling list rather than more running
 * text, so a long list stays readable.
 */
@Composable
private fun DictionaryPreview(shown: AddonPreviewContent.Dictionary) {
    var listing by remember(shown) { mutableStateOf(false) }

    SettingsGroup(stringResource(R.string.addon_preview_section_title)) {
        item {
            CaptionText(
                if (shown.truncated) {
                    pluralStringResource(
                        R.plurals.addon_preview_word_count_over,
                        shown.total,
                        shown.total,
                    )
                } else {
                    pluralStringResource(
                        R.plurals.addon_preview_word_count,
                        shown.total,
                        shown.total,
                    )
                },
            )
        }
        item {
            Text(
                shown.words.take(INLINE_WORDS).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            OutlinedButton(
                onClick = { listing = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (shown.partial) {
                        pluralStringResource(
                            R.plurals.addon_preview_show_words_action,
                            shown.words.size,
                            shown.words.size,
                        )
                    } else {
                        stringResource(R.string.addon_preview_show_all_words_action)
                    },
                )
            }
        }
    }

    if (!listing) return
    AlertDialog(
        onDismissRequest = { listing = false },
        title = {
            Text(
                pluralStringResource(
                    R.plurals.addon_preview_word_count,
                    shown.words.size,
                    shown.words.size,
                ),
            )
        },
        text = {
            Column {
                if (shown.partial) {
                    CaptionText(
                        if (shown.truncated) {
                            pluralStringResource(
                                R.plurals.addon_preview_partial_words_over,
                                shown.words.size,
                                shown.words.size,
                                shown.total,
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.addon_preview_partial_words,
                                shown.words.size,
                                shown.words.size,
                                shown.total,
                            )
                        },
                    )
                }
                // One Text of newline-joined words, not a lazy list. A
                // LazyColumn inside AlertDialog's text slot lays out at its
                // maximum height and then will not scroll — it showed the
                // first fifteen words and ate every drag. This is also cheaper:
                // one composable instead of up to ten thousand.
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        shown.words.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { listing = false }) {
                Text(stringResource(CommonR.string.common_close))
            }
        },
    )
}

/**
 * Plays a preview sound off a cache file.
 *
 * `MediaPlayer` rather than the keyboard's own `SoundPool`: the pool is keyed by
 * installed-sound id and exists to fire the same short clip on every keystroke,
 * which is not what this is. One player, released as soon as it finishes.
 */
private object AddonSoundPreview {
    fun play(file: java.io.File) {
        runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release() }
                setOnErrorListener { player, _, _ -> player.release(); true }
                prepare()
                start()
            }
        }
    }
}

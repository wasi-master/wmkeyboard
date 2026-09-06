package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import com.wasimaster.wmkeyboard.core.tools.PhotoColor
import com.wasimaster.wmkeyboard.core.tools.PhotoFailure
import com.wasimaster.wmkeyboard.core.tools.PhotoItem
import com.wasimaster.wmkeyboard.core.tools.PhotoLinks
import com.wasimaster.wmkeyboard.core.tools.PhotoOrientation
import com.wasimaster.wmkeyboard.core.tools.PhotoQuery
import com.wasimaster.wmkeyboard.core.tools.PhotoSearchClient
import com.wasimaster.wmkeyboard.core.tools.PhotoSource
import com.wasimaster.wmkeyboard.core.tools.PhotoTopic
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.core.tools.resolve
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The photo browser.
 *
 * Everything that guards the request budget lives below this in
 * [PhotoSearchClient] — the cache, the in-flight coalescing and the rate-limit
 * gate — so this screen only has to decide *when* to ask.
 */
@Composable
fun PhotoBrowseScreen(
    settings: KeyboardSettings,
    themeId: String,
    onOpenPhoto: (PhotoItem) -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()

    val sources = remember(settings.photoBackground) { ToolApiKeys.photoSources(settings) }
    val keys = remember(settings.photoBackground) { ToolApiKeys.photoKeys(settings) }

    // Held across a trip into a photo's page, so coming back does not re-search
    // and spend a request the user already paid for.
    var query by rememberSaveable(themeId) { mutableStateOf("") }
    var topic by rememberSaveable(themeId) { mutableStateOf<String?>(null) }
    var provider by rememberSaveable(themeId) { mutableStateOf<PhotoSource?>(null) }
    var colour by rememberSaveable(themeId) { mutableStateOf(PhotoColor.ANY) }
    var wide by rememberSaveable(themeId) { mutableStateOf(true) }

    var items by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<PhotoFailure?>(null) }
    var noticeOnly by remember { mutableStateOf<PhotoFailure?>(null) }
    var reloads by remember { mutableIntStateOf(0) }

    fun queryFor(nextPage: Int) = PhotoQuery(
        text = query.trim(),
        page = nextPage,
        orientation = if (wide) PhotoOrientation.LANDSCAPE else PhotoOrientation.ANY,
        color = colour,
        safe = settings.photoBackground.safeSearch,
        topicId = topic.orEmpty(),
    )

    fun activeSources() = provider?.let { listOf(it) } ?: sources

    // First page: debounced, so typing a word is one request and not six.
    LaunchedEffect(query, topic, provider, colour, wide, reloads, sources) {
        if (sources.isEmpty()) return@LaunchedEffect
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        loading = true
        failure = null
        noticeOnly = null
        endReached = false
        page = 1
        val result = PhotoSearchClient.mixedPage(activeSources(), queryFor(1), keys)
        items = result.items
        endReached = !result.hasMore
        if (result.failedOutright) {
            failure = result.failures.firstOrNull()
        } else {
            // One service being out of requests must not empty a grid the
            // other one filled; it becomes a note above the results instead.
            noticeOnly = result.failures.firstOrNull()
        }
        loading = false
    }

    LoadMoreOnScrollEnd(
        state = gridState,
        // Never after a failure: a broken endpoint would otherwise be asked
        // again on every fling.
        enabled = !loading && !loadingMore && !endReached && failure == null && items.isNotEmpty(),
    ) {
        scope.launch {
            loadingMore = true
            val next = page + 1
            val result = PhotoSearchClient.mixedPage(activeSources(), queryFor(next), keys)
            if (result.items.isNotEmpty()) {
                // Deduped by key: two services can repeat an id, and a repeated
                // key in a lazy grid is a crash rather than a duplicate tile.
                val known = items.mapTo(HashSet()) { it.key }
                items = items + result.items.filterNot { it.key in known }
                page = next
            }
            endReached = !result.hasMore || result.items.isEmpty()
            loadingMore = false
        }
    }

    WmLazyScreen(
        title = stringResource(R.string.photo_find_title),
        onBack = { onNavigate(BACK_ROUTE) },
        route = PHOTO_BROWSE_ROUTE,
        subtitle = themeId.takeIf { it.isNotBlank() }?.let { id ->
            settings.customThemes.flattenedThemes().find { it.id == id }?.name
        },
        subtitleInBar = true,
    ) { padding ->
        // Only a pull shows the spinner. `loading` is also true for the first
        // fetch of a visit, and that one already has the grid's own empty
        // state to say so.
        var pulled by remember { mutableStateOf(false) }
        if (!loading) pulled = false
        RegisterPullRefresh(loading && pulled) {
            pulled = true
            reloads++
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH_DP.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding(),
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    PhotoSearchField(
                        query = query,
                        onQuery = { query = it; topic = null },
                        onSearch = { keyboard?.hide() },
                    )
                    if (sources.size > 1) {
                        ProviderChips(sources, provider) { provider = it }
                    }
                    TopicChips(topic) { topic = it; if (it != null) query = "" }
                    FilterChips(colour, wide, onColour = { colour = it }, onWide = { wide = it })
                    noticeOnly?.let { QuotaNote(it) }
                }
            }
            if (items.isNotEmpty()) {
                items(items, key = { it.key }) { photo ->
                    PhotoTile(photo = photo, saved = false, onClick = { onOpenPhoto(photo) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PhotoGridFooter(
                        loadingMore = loadingMore,
                        endReached = endReached,
                        error = null,
                        onRetry = {},
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyGridState(
                        sources = sources,
                        loading = loading,
                        failure = failure,
                        query = query,
                        onAddKey = { onNavigate(PHOTO_HUB_ROUTE) },
                        onRetry = { reloads++ },
                        resolveFailure = { it.resolveText(context) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoSearchField(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        label = { Text(stringResource(CommonR.string.common_search)) },
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
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun ProviderChips(
    sources: List<PhotoSource>,
    selected: PhotoSource?,
    onSelect: (PhotoSource?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.photo_provider_all_label)) },
        )
        sources.forEach { source ->
            FilterChip(
                selected = selected == source,
                onClick = { onSelect(source) },
                label = { Text(stringResource(PhotoLinks.displayNameRes(source))) },
            )
        }
    }
}

@Composable
private fun TopicChips(selected: String?, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoTopic.entries.forEach { entry ->
            FilterChip(
                selected = selected == entry.slug,
                // The chip shows a translated label; what goes to the service
                // is the slug, which is an API value and never translated.
                onClick = { onSelect(if (selected == entry.slug) null else entry.slug) },
                label = { Text(stringResource(entry.labelRes)) },
            )
        }
    }
}

@Composable
private fun FilterChips(
    colour: PhotoColor,
    wide: Boolean,
    onColour: (PhotoColor) -> Unit,
    onWide: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = wide,
            onClick = { onWide(!wide) },
            label = { Text(stringResource(R.string.photo_orientation_landscape_label)) },
        )
        // Circles rather than a segmented row: fourteen colours do not fit on
        // any phone as labelled buttons.
        PhotoColor.entries.forEach { entry ->
            val swatch = if (entry == PhotoColor.ANY) null else Color(entry.swatch.toInt())
            val name = stringResource(photoColorLabelRes(entry))
            Surface(
                shape = CircleShape,
                color = swatch ?: MaterialTheme.colorScheme.surfaceContainerHighest,
                border = if (colour == entry) {
                    androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
                modifier = Modifier.size(28.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .fillMaxSize()
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        onClick = { onColour(entry) },
                        modifier = Modifier
                            .fillMaxSize()
                            // A bare coloured circle says nothing to a screen
                            // reader, so each one carries its own name.
                            .semantics {
                                contentDescription = if (entry == PhotoColor.ANY) name else name
                                if (colour == entry) selected = true
                            },
                    ) {
                        if (entry == PhotoColor.ANY) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaNote(failure: PhotoFailure) {
    val text = when (failure) {
        is PhotoFailure.QuotaSpent -> stringResource(
            R.string.photo_quota_fallback_body,
            stringResource(PhotoLinks.displayNameRes(failure.source)),
        )
        // Everything else already has somewhere better to be said: a key
        // problem is a notice, and a network problem replaces the grid.
        is PhotoFailure.KeyRejected, is PhotoFailure.Offline,
        is PhotoFailure.Other, PhotoFailure.NoKey,
        -> null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun EmptyGridState(
    sources: List<PhotoSource>,
    loading: Boolean,
    failure: PhotoFailure?,
    query: String,
    onAddKey: () -> Unit,
    onRetry: () -> Unit,
    resolveFailure: (PhotoFailure) -> String,
) {
    when {
        sources.isEmpty() -> PhotoNotice(
            icon = Icons.Outlined.Wallpaper,
            title = stringResource(R.string.photo_need_key_title),
            body = stringResource(R.string.photo_need_key_body),
            actionLabel = stringResource(R.string.photo_need_key_action),
            onAction = onAddKey,
        )
        loading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(CommonR.string.common_loading))
        }
        failure is PhotoFailure.QuotaSpent -> PhotoNotice(
            icon = Icons.Outlined.Wallpaper,
            title = stringResource(R.string.photo_quota_title),
            body = stringResource(
                R.string.photo_quota_body,
                stringResource(PhotoLinks.displayNameRes(failure.source)),
            ),
            actionLabel = stringResource(R.string.photo_need_key_action),
            onAction = onAddKey,
        )
        failure is PhotoFailure.KeyRejected -> PhotoNotice(
            icon = Icons.Outlined.Wallpaper,
            title = stringResource(R.string.photo_key_rejected_title),
            body = stringResource(R.string.photo_key_rejected_body),
            actionLabel = stringResource(R.string.photo_need_key_action),
            onAction = onAddKey,
        )
        failure != null -> PhotoNotice(
            icon = Icons.Outlined.Wallpaper,
            title = stringResource(CommonR.string.common_error_network),
            body = resolveFailure(failure),
            actionLabel = stringResource(CommonR.string.common_retry),
            onAction = onRetry,
        )
        query.isNotBlank() -> Text(
            stringResource(R.string.photo_search_empty, query),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        else -> Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.photo_idle_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.photo_grid_band_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Puts a failure into words where it is drawn, in the language now in use. */
private fun PhotoFailure.resolveText(context: android.content.Context): String = when (this) {
    is PhotoFailure.Offline -> text.resolve(context)
    is PhotoFailure.Other -> text.resolve(context)
    is PhotoFailure.KeyRejected -> context.getString(R.string.photo_key_rejected_body)
    is PhotoFailure.QuotaSpent -> context.getString(R.string.photo_quota_title)
    PhotoFailure.NoKey -> context.getString(R.string.photo_need_key_body)
}

/** The same 450ms the GIF panel uses, so live search feels the same. */
private const val SEARCH_DEBOUNCE_MS = 450L

/** Two columns on a phone, three on a tablet. */
private const val TILE_MIN_WIDTH_DP = 150

/** Sentinel the caller turns into a back navigation. */
internal const val BACK_ROUTE = ".."

/** The name of a colour filter, for the swatch a screen reader has to announce. */
@StringRes
private fun photoColorLabelRes(color: PhotoColor): Int = when (color) {
    PhotoColor.ANY -> R.string.photo_color_any_label
    PhotoColor.MONOCHROME -> R.string.photo_color_monochrome_label
    PhotoColor.BLACK -> R.string.photo_color_black_label
    PhotoColor.WHITE -> R.string.photo_color_white_label
    PhotoColor.GRAY -> R.string.photo_color_gray_label
    PhotoColor.BROWN -> R.string.photo_color_brown_label
    PhotoColor.RED -> R.string.photo_color_red_label
    PhotoColor.ORANGE -> R.string.photo_color_orange_label
    PhotoColor.YELLOW -> R.string.photo_color_yellow_label
    PhotoColor.GREEN -> R.string.photo_color_green_label
    PhotoColor.TEAL -> R.string.photo_color_teal_label
    PhotoColor.BLUE -> R.string.photo_color_blue_label
    PhotoColor.PURPLE -> R.string.photo_color_purple_label
    PhotoColor.MAGENTA -> R.string.photo_color_magenta_label
}

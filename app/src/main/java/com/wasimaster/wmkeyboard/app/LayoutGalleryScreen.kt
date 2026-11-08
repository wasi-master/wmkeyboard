package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository

/** The route of a language's [LayoutGalleryScreen]. */
internal fun layoutGalleryRoute(langId: String): String = "language/$langId/layouts"

/**
 * A language's layouts all at once: the row of cards on its screen, opened out
 * into a grid that fills the window, so eight layouts are compared side by side
 * instead of two and a half at a time.
 *
 * The cards are the row's own, and they fly here: each one in view lifts out of
 * the row and lands in its cell (see [layoutCardFlight]), and flies back on the
 * way out, so the grid reads as the row unfolded rather than as another page.
 * They work as they do in the row — a tap switches the layout — and the row's
 * last card, the way to More layouts, is the grid's last card too.
 *
 * Compact: two columns on a phone, more as the window widens, with the gaps of
 * the row, so a phone shows most languages' whole shelf on one screen.
 */
@Composable
internal fun LayoutGalleryScreen(
    anim: AnimatedVisibilityScope,
    langId: String,
    repository: SettingsRepository,
    settings: LiveSettings,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lang = LanguageRegistry.byId(langId)
    val toggle = rememberLayoutToggle(settings, repository, scope) {}
    val overflow = settings.watch { overflowLayoutIds(lang, it) }
    val listed = settings.watch { shelfLayoutIds(lang, it, overflow) }
    val names = rememberLayoutNames(listed, settings)

    WmLazyScreen(
        anim = anim,
        title = stringResource(R.string.languages_layouts_title),
        onBack = onBack,
        route = layoutGalleryRoute(langId),
        subtitle = lang.displayName,
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        val start = padding.calculateStartPadding(layoutDirection)
        val end = padding.calculateEndPadding(layoutDirection)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val inner = maxWidth - start - end - GalleryEdge * 2
            val columns = galleryColumns(inner)
            val cardWidth = (inner - GalleryGap * (columns - 1)) / columns
            val nameLines = rememberLayoutNameLines(names, withMore = overflow.isNotEmpty(), cardWidth = cardWidth)
            // The tallest board so far, which the More card stands as tall as.
            val boardHeight = remember(cardWidth) { mutableIntStateOf(0) }
            val gridState = rememberLazyGridState()
            val held = rememberPreviewsHeld { gridState.isScrollInProgress }
            CompositionLocalProvider(LocalLayoutPreviewScrolling provides held) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(GalleryGap),
                    verticalArrangement = Arrangement.spacedBy(GalleryGap, Alignment.Top),
                    contentPadding = PaddingValues(
                        start = start + GalleryEdge,
                        end = end + GalleryEdge,
                        top = padding.calculateTopPadding() + 4.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(listed, key = { _, id -> id }) { index, layoutId ->
                        val on = settings.watch { layoutId in it.enabledLayoutIds }
                        LayoutCard(
                            name = names[layoutId].orEmpty(),
                            layoutId = layoutId,
                            on = on,
                            settings = settings,
                            nameLines = nameLines,
                            boardHeight = boardHeight,
                            onToggle = { enable -> toggle(layoutId, enable) },
                            modifier = Modifier.layoutCardFlight(layoutCardFlightKey(layoutId)) { index },
                        )
                    }
                    if (overflow.isNotEmpty()) {
                        item(key = GALLERY_MORE_KEY) {
                            MoreLayoutsCard(
                                count = overflow.size,
                                boardHeight = boardHeight.intValue,
                                width = cardWidth,
                                nameLines = nameLines,
                                onClick = { onNavigate(moreLayoutsRoute(langId)) },
                                modifier = Modifier.layoutCardFlight(MORE_CARD_FLIGHT) { listed.size },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Columns for a grid [width] wide: as many as keep a card readable, never fewer than two. */
private fun galleryColumns(width: Dp): Int =
    ((width + GalleryGap) / (GalleryCellMin + GalleryGap)).toInt().coerceAtLeast(2)

/** Narrowest a card gets before the grid drops a column; a phone gets two. */
private val GalleryCellMin = 150.dp

private val GalleryGap = 8.dp
private val GalleryEdge = 16.dp

private const val GALLERY_MORE_KEY = "\u0000more"

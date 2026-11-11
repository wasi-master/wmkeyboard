package com.wasimaster.wmkeyboard.ime.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
import com.wasimaster.wmkeyboard.core.media.gallery.AlbumKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryAlbum
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryFilter
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryStore
import com.wasimaster.wmkeyboard.core.settings.GalleryCaption
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.gallery.GalleryAction
import com.wasimaster.wmkeyboard.ime.gallery.GalleryCallbacks
import com.wasimaster.wmkeyboard.ime.gallery.GalleryNotice
import com.wasimaster.wmkeyboard.ime.gallery.GalleryTextField
import com.wasimaster.wmkeyboard.ime.gallery.GalleryUi
import com.wasimaster.wmkeyboard.ime.gallery.immich.ImmichLibrary
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.Banner
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.clock.clockChip

/**
 * The gallery tool (#708): the phone's photos and videos in a sheet that
 * opens at the keyboard's height and grows to nearly the whole screen, like
 * Telegram's attach sheet. Albums and a filter up top, the grid, and once
 * something is picked a bar to caption, size and send it. The viewer and the
 * editor take the whole sheet at its tallest.
 */
@Composable
internal fun GalleryPanel(
    state: KeyboardUiState,
    callbacks: GalleryCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val settings = state.settings.gallery
    val typing = state.galleryText
    val tall = ui.expanded || ui.viewer != null || ui.editor != null
    val screen = LocalConfiguration.current.screenHeightDp.dp

    // The header's ⋮ menu, drawn inside the sheet rather than as a popup
    // window, which would sit over the app behind the keyboard.
    var overflow by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { trimGalleryImageMemory() } }

    PanelFocusTarget(
        panel = PanelMode.GALLERY,
        region = FocusRegion.CHIPS,
        count = GalleryFilter.entries.size,
        columns = GalleryFilter.entries.size,
    ) { index -> GalleryFilter.entries.getOrNull(index)?.let { act(GalleryAction.SetFilter(it)) } }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        extraHeight = if (tall) screen else 0.dp,
        compact = typing != null,
        compactHeight = when (typing?.field) {
            GalleryTextField.LAYER -> 300.dp
            // A row or two of what the search finds, over the keys.
            GalleryTextField.SEARCH -> 240.dp
            else -> 200.dp
        },
        headerHidden = ui.viewer != null || ui.editor != null,
        headerActions = {
            if (typing?.field == GalleryTextField.CAPTION) {
                TypingHeader(typing.text, stringResource(R.string.ime_gallery_caption_hint), act)
            } else if (typing?.field == GalleryTextField.SEARCH) {
                TypingHeader(typing.text, stringResource(R.string.ime_gallery_search_hint), act, pending = ui.search?.pending == true)
            } else {
                Header(ui, act) { overflow = !overflow }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            // An Immich server is browsable without the phone's photos (#1205).
            if (ui.access == GalleryStore.Access.NONE && !ui.immich) {
                NoAccess(act)
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (ui.access == GalleryStore.Access.PARTIAL) {
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Banner(
                                text = stringResource(R.string.ime_gallery_partial_body),
                                action = stringResource(R.string.ime_gallery_partial_action),
                            ) { act(GalleryAction.AskPermission) }
                        }
                    } else if (ui.videosWithheld) {
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Banner(
                                text = stringResource(R.string.ime_gallery_videos_body),
                                action = stringResource(R.string.ime_gallery_videos_action),
                            ) { act(GalleryAction.AskPermission) }
                        }
                    }
                    GalleryGrid(
                        ui = ui,
                        settings = settings,
                        act = act,
                        bottomPadding = if (ui.selection.isNotEmpty()) SendBarHeight else 0.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            AnimatedVisibility(
                visible = ui.selection.isNotEmpty(),
                enter = slideInVertically(tween(motion(state, 220))) { it } + fadeIn(tween(motion(state, 160))),
                exit = slideOutVertically(tween(motion(state, 180))) { it } + fadeOut(tween(motion(state, 140))),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                GallerySelectionBar(ui, typing, settings.caption, act)
            }

            Scrimmed(ui.sendMenuOpen, onDismiss = { act(GalleryAction.SendMenu(false)) }) {
                SendMenu(
                    hasCaption = ui.caption.isNotBlank() && settings.caption != GalleryCaption.OFF,
                    canShareToApp = true,
                    canUploadToImmich = ui.immich && ui.selection.any { !it.isRemote && it.bucketId != ImmichLibrary.REMOTE_BUCKET },
                    act = act,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = SendBarHeight - 40.dp),
                )
            }

            AlbumSheet(ui, act)

            Scrimmed(overflow, onDismiss = { overflow = false }) {
                OverflowMenu(Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 2.dp)) { action ->
                    overflow = false
                    act(action)
                }
            }

            ui.peek?.let { Peek(it) }

            Scrimmed(ui.askFallback > 0, onDismiss = { act(GalleryAction.FallbackChoice(null)) }) {
                FallbackCard(ui.askFallback, act, Modifier.align(Alignment.Center))
            }

            ui.viewer?.let { GalleryViewer(ui, it, act) }
            ui.editor?.let { GalleryEditor(state, ui, it, callbacks) }

            // Over the viewer and the editor too, which say "saved" as well;
            // clear of their top bars there.
            val fullSheet = ui.viewer != null || ui.editor != null
            NoticeLine(ui.notice, Modifier.align(Alignment.TopCenter).padding(top = if (fullSheet) 56.dp else 8.dp)) {
                act(GalleryAction.NoticeDismiss)
            }
        }
    }
}

private fun motion(state: KeyboardUiState, ms: Int): Int = if (state.settings.reduceMotion) 0 else ms

/** The header: the album picker, the kind filter, and the sheet's own buttons. */
@Composable
private fun RowScope.Header(ui: GalleryUi, act: (GalleryAction) -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(2.dp))
        val search = ui.search
        if (search != null) {
            // The query stays up top while its results are the grid: a tap
            // types into it again, the cross gives the album back.
            SearchChip(search.query, onClick = { act(GalleryAction.SearchFocus) }) { act(GalleryAction.ClearSearch) }
        } else {
            AlbumChip(ui.album, ui.albumsOpen) { act(GalleryAction.ToggleAlbums) }
            GalleryFilter.entries.forEach { f ->
                if (f == GalleryFilter.VIDEOS && !ui.albums.any { it.kind == AlbumKind.VIDEOS }) return@forEach
                ToolPanelChip(stringResource(filterLabel(f)), selected = ui.filter == f) { act(GalleryAction.SetFilter(f)) }
            }
        }
        Spacer(Modifier.width(2.dp))
    }
    if (ui.searchAvailable && ui.search == null) {
        RoundButton(icon = Icons.Outlined.Search, description = stringResource(R.string.ime_gallery_search_desc), size = 34.dp) {
            act(GalleryAction.SearchFocus)
        }
        Spacer(Modifier.width(4.dp))
    }
    RoundButton(
        icon = if (ui.expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
        description = stringResource(if (ui.expanded) R.string.ime_gallery_collapse_desc else R.string.ime_gallery_expand_desc),
        size = 34.dp,
    ) { act(GalleryAction.ToggleExpanded) }
    Spacer(Modifier.width(4.dp))
    RoundButton(icon = Icons.Outlined.MoreVert, description = stringResource(R.string.ime_gallery_more_desc), size = 34.dp) {
        onMore()
    }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun OverflowMenu(modifier: Modifier, pick: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .widthIn(min = 200.dp)
            .clip(shape)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .padding(vertical = 4.dp),
    ) {
        OverflowRow(Icons.Outlined.PhotoLibrary, stringResource(R.string.ime_gallery_browse_all)) { pick(GalleryAction.PickWithSystem) }
        OverflowRow(Icons.Outlined.FileOpen, stringResource(R.string.ime_gallery_files)) { pick(GalleryAction.PickFiles) }
        OverflowRow(Icons.Outlined.Settings, stringResource(R.string.ime_gallery_settings)) { pick(GalleryAction.OpenSettings) }
    }
}

@Composable
private fun OverflowRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kb.popupText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = kb.popupText, fontSize = 14.sp)
    }
}

/**
 * While the caption or the search has the keys, the header is the box and a
 * Done. [pending]: a search is still being answered.
 */
@Composable
private fun RowScope.TypingHeader(text: String, placeholder: String, act: (GalleryAction) -> Unit, pending: Boolean = false) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = text,
            placeholder = placeholder,
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (pending) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(Modifier.size(14.dp), color = kb.secondaryText, strokeWidth = 1.5.dp)
        }
    }
    ToolPanelChip(stringResource(R.string.ime_gallery_done), selected = true) { act(GalleryAction.TextDone) }
    Spacer(Modifier.width(4.dp))
}

/** A search's query, in place of the album and the filters, with a cross to end it. */
@Composable
private fun SearchChip(query: String, onClick: () -> Unit, onClear: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .clockChip(selected = true)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = kb.chipActiveText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            query,
            color = kb.chipActiveText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Outlined.Close,
            contentDescription = stringResource(R.string.ime_gallery_search_clear_desc),
            tint = kb.chipActiveText,
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClear)
                .padding(5.dp),
        )
    }
}

@Composable
private fun AlbumChip(album: GalleryAlbum, open: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .clockChip(selected = open)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = album.cover
        if (cover != null) {
            AsyncImage(
                model = MediaThumb(cover, album.coverIsVideo, COVER_PX),
                contentDescription = null,
                imageLoader = rememberGalleryImageLoader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(6.dp))
        }
        val fg = if (open) kb.chipActiveText else kb.chipText
        Text(albumName(album), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun albumName(album: GalleryAlbum): String = when (album.kind) {
    AlbumKind.RECENTS -> stringResource(R.string.ime_gallery_album_recents)
    AlbumKind.FAVORITES -> stringResource(R.string.ime_gallery_album_favorites)
    AlbumKind.VIDEOS -> stringResource(R.string.ime_gallery_album_videos)
    AlbumKind.GIFS -> stringResource(R.string.ime_gallery_album_gifs)
    AlbumKind.SCREENSHOTS -> stringResource(R.string.ime_gallery_album_screenshots)
    AlbumKind.CAMERA -> stringResource(R.string.ime_gallery_album_camera)
    AlbumKind.SENT -> stringResource(R.string.ime_gallery_album_sent)
    AlbumKind.BUCKET -> album.name?.ifBlank { null } ?: stringResource(R.string.ime_gallery_album_unnamed)
    AlbumKind.IMMICH -> stringResource(R.string.ime_gallery_album_immich)
    AlbumKind.IMMICH_ALBUM -> album.name?.ifBlank { null } ?: stringResource(R.string.ime_gallery_album_unnamed)
}

private fun filterLabel(f: GalleryFilter): Int = when (f) {
    GalleryFilter.ALL -> R.string.ime_gallery_filter_all
    GalleryFilter.PHOTOS -> R.string.ime_gallery_filter_photos
    GalleryFilter.VIDEOS -> R.string.ime_gallery_filter_videos
    GalleryFilter.GIFS -> R.string.ime_gallery_filter_gifs
}

/** The album list, dropped over the grid from the album chip. */
@Composable
private fun AlbumSheet(ui: GalleryUi, act: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = ui.albumsOpen,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 150)) + slideInVertically(tween(if (kb.reduceMotion) 0 else 200)) { -it / 8 },
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 120)),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().background(kb.board),
        ) {
            items(ui.albums, key = { it.kind.name + ":" + it.id }) { album ->
                AlbumTile(album, selected = album.kind == ui.album.kind && album.id == ui.album.id) {
                    act(GalleryAction.OpenAlbum(album))
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(album: GalleryAlbum, selected: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.chip),
        ) {
            album.cover?.let {
                AsyncImage(
                    model = MediaThumb(it, album.coverIsVideo, TILE_PX),
                    contentDescription = null,
                    imageLoader = rememberGalleryImageLoader(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(kb.accent.copy(alpha = 0.25f)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            albumName(album),
            color = if (selected) kb.accent else kb.suggestionText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(album.count.toString(), color = kb.secondaryText, fontSize = 11.sp)
    }
}

/** No grant yet: what the tool does, the grant, and the picker that needs none. */
@Composable
private fun NoAccess(act: (GalleryAction) -> Unit) {
    EmptyState(
        icon = Icons.Outlined.PhotoLibrary,
        title = stringResource(R.string.ime_gallery_access_title),
        body = stringResource(R.string.ime_gallery_access_body),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.ime_gallery_access_allow), primary = true) { act(GalleryAction.AskPermission) }
            PillButton(stringResource(R.string.ime_gallery_access_pick)) { act(GalleryAction.PickWithSystem) }
        }
    }
}

/** The picture under a held finger, big, until it lifts. */
@Composable
private fun Peek(item: com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = if (item.isVideo) MediaThumb(item.uri, true, PEEK_PX) else item.uri,
            contentDescription = null,
            imageLoader = rememberGalleryImageLoader(),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(14.dp)),
        )
        if (item.isVideo) PhotoPill(durationText(item.durationMs), Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}

/** Something to dismiss behind a card: a soft scrim that a tap clears. */
@Composable
private fun Scrimmed(visible: Boolean, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 120)) + scaleIn(tween(if (kb.reduceMotion) 0 else 160), initialScale = 0.96f),
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 100)) + scaleOut(tween(if (kb.reduceMotion) 0 else 100), targetScale = 0.98f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            content = content,
        )
    }
}

@Composable
private fun NoticeLine(notice: GalleryNotice?, modifier: Modifier, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 150)) + slideInVertically(tween(if (kb.reduceMotion) 0 else 180)) { -it },
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 150)),
        modifier = modifier,
    ) {
        var last by remember { mutableStateOf(notice) }
        if (notice != null) last = notice
        val n = last ?: return@AnimatedVisibility
        Text(
            noticeText(n),
            color = kb.popupText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(kb.popup)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun noticeText(n: GalleryNotice): String = when (n.kind) {
    GalleryNotice.Kind.LIMIT -> pluralStringResource(R.plurals.ime_gallery_notice_limit, n.count, n.count)
    GalleryNotice.Kind.NOT_ACCEPTED -> stringResource(R.string.ime_gallery_notice_not_accepted)
    GalleryNotice.Kind.SHARED -> pluralStringResource(R.plurals.ime_gallery_notice_shared, n.count, n.count)
    GalleryNotice.Kind.COPIED -> pluralStringResource(R.plurals.ime_gallery_notice_copied, n.count, n.count)
    GalleryNotice.Kind.SAVED -> pluralStringResource(R.plurals.ime_gallery_notice_saved, n.count, n.count)
    GalleryNotice.Kind.FAILED -> pluralStringResource(R.plurals.ime_gallery_notice_failed, n.count, n.count)
    GalleryNotice.Kind.SENT -> pluralStringResource(R.plurals.ime_gallery_notice_sent, n.count, n.count)
    GalleryNotice.Kind.NO_VIDEOS -> stringResource(R.string.ime_gallery_notice_no_videos)
    GalleryNotice.Kind.REPLACED -> stringResource(R.string.ime_gallery_notice_replaced)
    GalleryNotice.Kind.NOT_REPLACED -> stringResource(R.string.ime_gallery_notice_not_replaced)
    GalleryNotice.Kind.SAVE_FAILED -> stringResource(R.string.ime_gallery_notice_save_failed)
    GalleryNotice.Kind.IMMICH_FAILED -> stringResource(R.string.ime_gallery_notice_immich_failed)
    GalleryNotice.Kind.IMMICH_REFUSED -> stringResource(R.string.ime_gallery_notice_immich_refused)
    GalleryNotice.Kind.UPLOADED -> pluralStringResource(R.plurals.ime_gallery_notice_uploaded, n.count, n.count)
    GalleryNotice.Kind.ALREADY_UPLOADED -> pluralStringResource(R.plurals.ime_gallery_notice_already_uploaded, n.count, n.count)
    GalleryNotice.Kind.UPLOAD_FAILED -> pluralStringResource(R.plurals.ime_gallery_notice_upload_failed, n.count, n.count)
}

private const val COVER_PX = 96
private const val TILE_PX = 320
private const val PEEK_PX = 720

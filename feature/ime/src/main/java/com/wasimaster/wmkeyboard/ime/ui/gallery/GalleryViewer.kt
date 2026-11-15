package com.wasimaster.wmkeyboard.ime.ui.gallery

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryKind
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.gallery.GalleryAction
import com.wasimaster.wmkeyboard.ime.gallery.GalleryUi
import com.wasimaster.wmkeyboard.ime.gallery.SendMode
import com.wasimaster.wmkeyboard.ime.gallery.ViewerUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A photo or video full size, at the sheet's full height: swipe between
 * them, pinch or double-tap to zoom, drag down to put it away. The bars over
 * it pick it, open it in the editor, and send; a tap on the picture hides
 * them for an unobstructed look.
 */
@Composable
internal fun GalleryViewer(ui: GalleryUi, viewer: ViewerUi, act: (GalleryAction) -> Unit) {
    val items = ui.viewerItems
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { act(GalleryAction.CloseViewer) }
        return
    }
    val pager = rememberPagerState(initialPage = viewer.index.coerceIn(0, items.lastIndex)) { items.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { act(GalleryAction.ViewerPage(it)) }
    }
    // The grid behind keeps paging in as the viewer nears its end.
    LaunchedEffect(pager.currentPage, items.size) {
        if (!viewer.fromSelection && pager.currentPage >= items.size - 8) act(GalleryAction.LoadMore)
    }
    val scope = rememberCoroutineScope()
    val drop = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissPx = with(density) { 140.dp.toPx() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pager.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }
    val zoomed = scale > 1f + ZOOM_EPSILON
    val fade = (1f - abs(drop.value) / (dismissPx * 2.5f)).coerceIn(0.3f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(PhotoBackdrop.copy(alpha = fade))
            .onSizeChanged { viewport = it }
            .pointerInput(zoomed) {
                if (zoomed) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(drop.value) > dismissPx) {
                                act(GalleryAction.CloseViewer)
                            } else {
                                drop.animateTo(0f, tween(180))
                            }
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drop.snapTo(drop.value + amount) }
                }
            },
    ) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            key = { items.getOrNull(it)?.id ?: it.toLong() },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = drop.value
                    val s = 1f - (abs(drop.value) / (dismissPx * 6f)).coerceIn(0f, 0.15f)
                    scaleX = s
                    scaleY = s
                },
        ) { page ->
            val item = items[page]
            val current = page == pager.currentPage
            if (item.kind == GalleryKind.VIDEO) {
                VideoPage(item, current, viewer.muted, onTap = { act(GalleryAction.ViewerChrome) })
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    imageLoader = rememberGalleryImageLoader(),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomAndPan(
                            zoomed = current && zoomed,
                            onTransform = { z, pan ->
                                if (!current) return@zoomAndPan
                                val next = (scale * z).coerceIn(1f, MAX_ZOOM)
                                scale = next
                                offset = if (next <= 1f + ZOOM_EPSILON) Offset.Zero else clamp(offset + pan, next, viewport)
                            },
                            onDoubleTap = {
                                if (scale > 1f + ZOOM_EPSILON) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_ZOOM
                                    offset = Offset.Zero
                                }
                            },
                            onTap = { act(GalleryAction.ViewerChrome) },
                        )
                        .graphicsLayer {
                            if (current) {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                        },
                )
            }
        }

        val item = items.getOrNull(pager.currentPage) ?: items.first()
        AnimatedVisibility(viewer.chrome && drop.value == 0f, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Box(Modifier.fillMaxSize()) {
                TopBar(ui, viewer, item, pager.currentPage, items.size, act, Modifier.align(Alignment.TopCenter))
                BottomBar(ui, item, act, Modifier.align(Alignment.BottomCenter)) { index ->
                    scope.launch { pager.animateScrollToPage(index) }
                }
            }
        }
        if (viewer.infoOpen) InfoCard(item, Modifier.align(Alignment.Center)) { act(GalleryAction.ViewerInfo) }
    }
}

private fun clamp(candidate: Offset, scale: Float, viewport: IntSize): Offset {
    val slackX = (viewport.width * (scale - 1f) / 2f).coerceAtLeast(0f)
    val slackY = (viewport.height * (scale - 1f) / 2f).coerceAtLeast(0f)
    return Offset(candidate.x.coerceIn(-slackX, slackX), candidate.y.coerceIn(-slackY, slackY))
}

@Composable
private fun TopBar(
    ui: GalleryUi,
    viewer: ViewerUi,
    item: GalleryItem,
    page: Int,
    count: Int,
    act: (GalleryAction) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ime_gallery_viewer_close)) {
            act(GalleryAction.CloseViewer)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ime_gallery_viewer_counter, page + 1, count),
                color = OnPhoto,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (item.dateMs > 0) Text(takenText(item.dateMs), color = OnPhoto.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
        }
        PhotoButton(Icons.Outlined.Info, stringResource(R.string.ime_gallery_viewer_info), active = viewer.infoOpen) {
            act(GalleryAction.ViewerInfo)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(38.dp).clickable { act(GalleryAction.Toggle(item)) }, contentAlignment = Alignment.Center) {
            SelectRing(ui.orderOf(item.id), size = 28.dp)
        }
    }
}

@Composable
private fun BottomBar(
    ui: GalleryUi,
    item: GalleryItem,
    act: (GalleryAction) -> Unit,
    modifier: Modifier,
    jump: (Int) -> Unit,
) {
    val kb = LocalKbTheme.current
    Column(
        modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
            .padding(bottom = 10.dp, top = 24.dp),
    ) {
        if (ui.selection.size > 1 || ui.viewer?.fromSelection == true) {
            val loader = rememberGalleryImageLoader()
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                itemsIndexed(ui.selection, key = { _, it -> it.id }) { i, s ->
                    val on = s.id == item.id
                    AsyncImage(
                        model = MediaThumb.of(s, 144),
                        contentDescription = null,
                        imageLoader = loader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(if (on) 50.dp else 42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (on) Modifier.background(kb.accent) else Modifier)
                            .clickable {
                                val inList = ui.viewerItems.indexOfFirst { it.id == s.id }
                                if (inList >= 0) jump(inList) else act(GalleryAction.OpenViewer(i, fromSelection = true))
                            },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.kind == GalleryKind.IMAGE) {
                PhotoButton(Icons.Outlined.Edit, stringResource(R.string.ime_gallery_edit)) { act(GalleryAction.OpenEditor(item.id)) }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(qualityLabel(ui.quality)),
                color = OnPhoto,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable {
                        val all = com.wasimaster.wmkeyboard.core.media.gallery.GalleryQuality.entries
                        act(GalleryAction.SetQuality(all[(ui.quality.ordinal + 1) % all.size]))
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            val count = ui.selection.size
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(kb.accent)
                    .clickable {
                        // Nothing picked: Send means this one.
                        if (count == 0) act(GalleryAction.Toggle(item))
                        act(GalleryAction.Send(SendMode.NORMAL))
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, tint = onAccent(kb.accent), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (count > 1) stringResource(R.string.ime_gallery_send_count, count) else stringResource(R.string.ime_gallery_send),
                    color = onAccent(kb.accent),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * A video page: plays while it is the page on screen, loops, and pauses the
 * moment it is swiped away. Preview only: what is sent is the file itself.
 */
@Composable
private fun VideoPage(item: GalleryItem, current: Boolean, muted: Boolean, onTap: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var length by remember { mutableIntStateOf(item.durationMs.toInt()) }
    var scrubWidth by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(current, view) {
        val v = view ?: return@LaunchedEffect
        if (current) {
            v.start()
            playing = true
        } else {
            v.pause()
            playing = false
        }
    }
    LaunchedEffect(muted, player) {
        val vol = if (muted) 0f else 1f
        runCatching { player?.setVolume(vol, vol) }
    }
    LaunchedEffect(playing, view) {
        while (playing) {
            view?.let {
                position = it.currentPosition
                if (it.duration > 0) length = it.duration
            }
            delay(200)
        }
    }
    DisposableEffect(item.id) { onDispose { runCatching { view?.stopPlayback() } } }
    // An Immich video (#1205) plays from its original, fetched first; its
    // server picture stands in, with a spinner, until it is here.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val playUri by androidx.compose.runtime.produceState(if (item.isRemote) null else item.uri, item.id) {
        if (item.isRemote) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.wasimaster.wmkeyboard.ime.gallery.immich.ImmichLibrary.fetchFromServer(appContext, item)?.uri
            }
        }
    }
    val source = playUri
    if (source == null) {
        Box(Modifier.fillMaxSize().clickable(onClick = onTap), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = MediaThumb.of(item, REMOTE_VIDEO_STILL_PX),
                contentDescription = null,
                imageLoader = rememberGalleryImageLoader(),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            androidx.compose.material3.CircularProgressIndicator(color = OnPhoto)
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(source)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        player = mp
                        val vol = if (muted) 0f else 1f
                        mp.setVolume(vol, vol)
                        if (current) start()
                    }
                    view = this
                }
            },
            modifier = Modifier.align(Alignment.Center),
        )
        Box(Modifier.fillMaxSize().clickable(onClick = onTap))
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 132.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoButton(
                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                stringResource(if (playing) R.string.ime_gallery_pause else R.string.ime_gallery_play),
                size = 34.dp,
            ) {
                val v = view ?: return@PhotoButton
                if (playing) v.pause() else v.start()
                playing = !playing
            }
            Spacer(Modifier.width(8.dp))
            Text(durationText(position.toLong()), color = OnPhoto, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .onSizeChanged { scrubWidth = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(length) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val to = ((change.position.x / scrubWidth).coerceIn(0f, 1f) * length).toInt()
                            position = to
                            view?.seekTo(to)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(OnPhoto.copy(alpha = 0.3f)))
                val f = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(OnPhoto))
            }
            Spacer(Modifier.width(8.dp))
            Text(durationText(length.toLong()), color = OnPhoto, fontSize = 11.sp)
        }
    }
}

/** What is known about the picture, and what will and will not travel with it. */
@Composable
private fun InfoCard(item: GalleryItem, modifier: Modifier, onDismiss: () -> Unit) {
    Column(
        modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(onClick = onDismiss)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (item.name.isNotBlank()) Text(item.name, color = OnPhoto, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (item.dateMs > 0) InfoLine(R.string.ime_gallery_info_taken, takenText(item.dateMs))
        if (item.width > 0) InfoLine(R.string.ime_gallery_info_size, stringResource(R.string.ime_gallery_info_dimensions, item.shownWidth, item.shownHeight))
        if (item.size > 0) InfoLine(R.string.ime_gallery_info_bytes, sizeText(item.size))
        if (item.isVideo && item.durationMs > 0) InfoLine(R.string.ime_gallery_info_length, durationText(item.durationMs))
        if (item.bucketName.isNotBlank()) InfoLine(R.string.ime_gallery_info_album, item.bucketName)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ime_gallery_info_privacy), color = OnPhoto.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

@Composable
private fun InfoLine(label: Int, value: String) {
    Row {
        Text(stringResource(label), color = OnPhoto.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(value, color = OnPhoto, fontSize = 12.sp)
    }
}

/** The size of the server picture an Immich video shows while it downloads (#1205). */
private const val REMOTE_VIDEO_STILL_PX = 1440

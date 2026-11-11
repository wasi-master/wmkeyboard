package com.wasimaster.wmkeyboard.ime.ui.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Deselect
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SaveAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Subtitles
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryQuality
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEdit
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEditRenderer
import com.wasimaster.wmkeyboard.core.settings.GalleryCaption
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.gallery.GalleryAction
import com.wasimaster.wmkeyboard.ime.gallery.GalleryTextEntry
import com.wasimaster.wmkeyboard.ime.gallery.GalleryTextField
import com.wasimaster.wmkeyboard.ime.gallery.GalleryUi
import com.wasimaster.wmkeyboard.ime.gallery.SendMode
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.clock.clockChip
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The send bar's own height, which the grid keeps clear under its last row. */
internal val SendBarHeight = 132.dp

/**
 * The bar that rises when something is picked: the picks in order (drag one
 * to move it, tap to edit it, the cross to drop it), the caption, the
 * quality, and Send, whose hold opens the other ways out.
 */
@Composable
internal fun GallerySelectionBar(
    ui: GalleryUi,
    typing: GalleryTextEntry?,
    captionSetting: GalleryCaption,
    act: (GalleryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    Column(
        modifier
            .fillMaxWidth()
            .background(kb.board.copy(alpha = 0.97f))
            .padding(bottom = 6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(kb.divider))
        val sending = ui.sending
        if (sending != null) SendingLine(sending.done, sending.total)
        PickStrip(ui, act)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (captionSetting != GalleryCaption.OFF) {
                CaptionBox(ui.caption, typing?.takeIf { it.field == GalleryTextField.CAPTION }, Modifier.weight(1f)) {
                    act(if (typing?.field == GalleryTextField.CAPTION) GalleryAction.TextDone else GalleryAction.CaptionFocus)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            QualityChip(ui, act)
            SendButton(ui.selection.size, enabled = sending == null, act)
        }
    }
}

@Composable
private fun SendingLine(done: Int, total: Int) {
    val kb = LocalKbTheme.current
    val f by animateFloatAsState(
        targetValue = if (total == 0) 0f else (done + 0.35f) / total,
        animationSpec = tween(if (kb.reduceMotion) 0 else 300),
        label = "sending",
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.ime_gallery_sending, (done + 1).coerceAtMost(total), total),
            color = kb.secondaryText,
            fontSize = 11.sp,
        )
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(kb.chip)) {
            Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(3.dp).background(kb.accent))
        }
    }
}

/** The picks in send order. A hold and a drag sideways moves one. */
@Composable
private fun PickStrip(ui: GalleryUi, act: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    val loader = rememberGalleryImageLoader()
    val haptic = LocalHapticFeedback.current
    val stepPx = with(LocalDensity.current) { (THUMB + 6.dp).toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragX by remember { mutableFloatStateOf(0f) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().height(THUMB + 12.dp),
    ) {
        itemsIndexed(ui.selection, key = { _, it -> it.id }) { index, item ->
            val edited = ui.edits[item.id]?.current?.isEmpty == false
            val lifted = dragging == index
            Box(
                Modifier
                    .animateItem()
                    .size(THUMB)
                    .graphicsLayer {
                        translationX = if (lifted) dragX else 0f
                        scaleX = if (lifted) 1.1f else 1f
                        scaleY = if (lifted) 1.1f else 1f
                        shadowElevation = if (lifted) 8f else 0f
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(kb.chip)
                    .pointerInput(item.id, index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragging = index
                                dragX = 0f
                            },
                            onDragEnd = {
                                val to = (index + (dragX / stepPx).roundToInt()).coerceIn(0, ui.selection.lastIndex)
                                if (to != index) act(GalleryAction.Move(index, to))
                                dragging = -1
                                dragX = 0f
                            },
                            onDragCancel = {
                                dragging = -1
                                dragX = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            dragX += amount.x
                        }
                    }
                    .clickable {
                        if (item.kind == GalleryKind.IMAGE) {
                            act(GalleryAction.OpenEditor(item.id))
                        } else {
                            act(GalleryAction.OpenViewer(index, fromSelection = true))
                        }
                    },
            ) {
                PickThumb(item, ui.editOf(item.id), loader)
                Text(
                    (index + 1).toString(),
                    color = onAccent(kb.accent),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(kb.accent)
                        .padding(horizontal = 5.dp),
                )
                // Every photo carries the pencil, so a tap reads as "edit"; an edited one wears the accent.
                if (item.kind == GalleryKind.IMAGE) {
                    if (edited) {
                        CornerMark(Icons.Outlined.Edit, Alignment.BottomEnd, background = kb.accent, tint = onAccent(kb.accent))
                    } else {
                        CornerMark(Icons.Outlined.Edit, Alignment.BottomEnd)
                    }
                } else if (edited) {
                    CornerMark(Icons.Outlined.Edit, Alignment.BottomEnd)
                }
                val remove = stringResource(R.string.ime_gallery_remove_desc)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { act(GalleryAction.Remove(item.id)) }
                        .semantics { contentDescription = remove },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = OnPhoto, modifier = Modifier.size(12.dp)) }
            }
        }
    }
}

/**
 * A pick's picture in the strip. An edited photo is drawn with its edit, by
 * the renderer that draws the sent copy, from a small decode; the system
 * thumbnail stays under it until that is ready, and the last drawing stays
 * up while the next one renders, so a change does not flash back to the
 * original.
 */
@Composable
private fun PickThumb(item: GalleryItem, edit: PhotoEdit, loader: ImageLoader) {
    val context = LocalContext.current
    var rendered by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.uri, edit) {
        if (edit.isEmpty || item.kind != GalleryKind.IMAGE) {
            rendered = null
            return@LaunchedEffect
        }
        rendered = withContext(Dispatchers.Default) {
            val src = MediaPrep.decode(context, item.uri, EDITED_THUMB_EDGE) ?: return@withContext null
            val stickers = PreviewStickers()
            runCatching { PhotoEditRenderer.render(src, edit, stickers).asImageBitmap() }.getOrNull()
        } ?: rendered
    }
    val shown = rendered
    if (shown == null) {
        AsyncImage(
            model = MediaThumb.of(item, THUMB_PX),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CaptionBox(caption: String, typing: GalleryTextEntry?, modifier: Modifier, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Subtitles, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = typing?.text ?: caption,
            placeholder = stringResource(R.string.ime_gallery_caption_hint),
            active = typing != null,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Steps through the four sizes; the total under it is a guess from the pixel counts. */
@Composable
private fun QualityChip(ui: GalleryUi, act: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    val q = ui.quality
    val next = GalleryQuality.entries[(q.ordinal + 1) % GalleryQuality.entries.size]
    val total = ui.selection.sumOf { MediaPrep.estimateBytes(it, q, edited = ui.edits[it.id]?.current?.isEmpty == false) }
    Column(
        Modifier
            .height(40.dp)
            .clockChip(selected = q == GalleryQuality.ORIGINAL || q == GalleryQuality.HD)
            .clickable { act(GalleryAction.SetQuality(next)) }
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val fg = if (q == GalleryQuality.ORIGINAL || q == GalleryQuality.HD) kb.chipActiveText else kb.chipText
        // Own line heights: the inherited 24 sp line box held the two lines a line apart.
        Text(stringResource(qualityLabel(q)), color = fg, fontSize = 12.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (total > 0) {
            Text(
                stringResource(R.string.ime_gallery_size_estimate, sizeText(total)),
                color = fg.copy(alpha = 0.75f),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 1,
            )
        }
    }
}

internal fun qualityLabel(q: GalleryQuality): Int = when (q) {
    GalleryQuality.ORIGINAL -> R.string.ime_gallery_quality_original
    GalleryQuality.HD -> R.string.ime_gallery_quality_hd
    GalleryQuality.STANDARD -> R.string.ime_gallery_quality_standard
    GalleryQuality.SMALL -> R.string.ime_gallery_quality_small
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(count: Int, enabled: Boolean, act: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    val label = pluralStringResource(R.plurals.ime_gallery_send_desc, count, count)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(kb.accent.copy(alpha = if (enabled) 1f else 0.5f))
            .combinedClickable(
                enabled = enabled,
                onClick = { act(GalleryAction.Send(SendMode.NORMAL)) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    act(GalleryAction.SendMenu(true))
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, tint = onAccent(kb.accent), modifier = Modifier.size(22.dp))
        if (count > 1) {
            Text(
                count.toString(),
                color = kb.accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(onAccent(kb.accent))
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/** Send's hold menu: every other way out of the bar. */
@Composable
internal fun SendMenu(
    hasCaption: Boolean,
    canShareToApp: Boolean,
    act: (GalleryAction) -> Unit,
    modifier: Modifier = Modifier,
    canUploadToImmich: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .width(250.dp)
            .clip(shape)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .padding(vertical = 4.dp),
    ) {
        @Composable
        fun entry(icon: ImageVector, label: Int, mode: SendMode?) = MenuRow(icon, stringResource(label)) {
            act(if (mode == null) GalleryAction.ClearSelection else GalleryAction.Send(mode))
        }
        entry(Icons.Outlined.Description, R.string.ime_gallery_send_as_file, SendMode.AS_FILE)
        if (hasCaption) entry(Icons.Outlined.Subtitles, R.string.ime_gallery_send_without_caption, SendMode.WITHOUT_CAPTION)
        if (canShareToApp) entry(Icons.Outlined.OpenInNew, R.string.ime_gallery_share_to_app, SendMode.SHARE_TO_APP)
        entry(Icons.Outlined.Share, R.string.ime_gallery_share, SendMode.SHARE)
        entry(Icons.Outlined.ContentCopy, R.string.ime_gallery_copy, SendMode.COPY)
        entry(Icons.Outlined.Link, R.string.ime_gallery_send_as_link, SendMode.AS_LINK)
        entry(Icons.Outlined.SaveAlt, R.string.ime_gallery_save, SendMode.SAVE)
        if (canUploadToImmich) entry(Icons.Outlined.CloudUpload, R.string.ime_gallery_upload_immich, SendMode.TO_IMMICH)
        entry(Icons.Outlined.Deselect, R.string.ime_gallery_clear_selection, null)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kb.popupText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = kb.popupText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The Ask fallback: the field took none of it, so share or copy, the user's call. */
@Composable
internal fun FallbackCard(count: Int, act: (GalleryAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .padding(16.dp)
            .clip(shape)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .padding(16.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.ime_gallery_fallback_title, count, count),
            color = kb.popupText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ime_gallery_fallback_body), color = kb.popupText.copy(alpha = 0.8f), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(stringResource(R.string.ime_gallery_fallback_share), primary = true) { act(GalleryAction.FallbackChoice(true)) }
            CardButton(stringResource(R.string.ime_gallery_fallback_copy)) { act(GalleryAction.FallbackChoice(false)) }
            CardButton(stringResource(R.string.ime_gallery_fallback_cancel)) { act(GalleryAction.FallbackChoice(null)) }
        }
    }
}

@Composable
internal fun CardButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Text(
        label,
        color = if (primary) kb.chipActiveText else kb.chipText,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (primary) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private val THUMB = 56.dp
private const val THUMB_PX = 168

/** An edited pick's decode: a crop of a quarter of the photo still fills the 56 dp cell. */
private const val EDITED_THUMB_EDGE = 640

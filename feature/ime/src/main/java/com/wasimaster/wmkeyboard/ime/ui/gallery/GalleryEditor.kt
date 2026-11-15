package com.wasimaster.wmkeyboard.ime.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize as UnitIntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.CompareArrows
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RestartAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SaveAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SaveAs
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.media.gallery.edit.CropRect
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEdit
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEditRenderer
import com.wasimaster.wmkeyboard.core.media.gallery.edit.StickerLayer
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.gallery.EditorTool
import com.wasimaster.wmkeyboard.ime.gallery.EditorUi
import com.wasimaster.wmkeyboard.ime.gallery.GalleryAction
import com.wasimaster.wmkeyboard.ime.gallery.GalleryCallbacks
import com.wasimaster.wmkeyboard.ime.gallery.GalleryTextField
import com.wasimaster.wmkeyboard.ime.gallery.GalleryUi
import com.wasimaster.wmkeyboard.ime.gallery.replaceable
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The photo editor, at the sheet's full height: crop, draw, text, stickers,
 * filters and adjustments, every step undoable, the photo itself untouched.
 *
 * What is shown is drawn by [PhotoEditRenderer], the code that draws the sent
 * copy, at preview size: the turned and recoloured picture is redrawn when
 * those change, the layers over it when they change, and a text or sticker
 * under a finger is drawn live on top until it is let go.
 */
@Composable
internal fun GalleryEditor(state: KeyboardUiState, ui: GalleryUi, editor: EditorUi, callbacks: GalleryCallbacks) {
    val act = callbacks.onAction
    val item = ui.selection.firstOrNull { it.id == editor.itemId } ?: return
    val context = LocalContext.current
    val edit = ui.editOf(item.id)
    val history = ui.edits[item.id]
    val typing = state.galleryText?.takeIf { it.field == GalleryTextField.LAYER }

    // The upscaler's copy once the edit has one: the rest of the edit is in
    // fractions of the picture, so it draws on either the same way.
    val upscaled = ui.upscaledFile(item.id, edit)
    val source by produceState<Bitmap?>(null, item.id, upscaled) {
        val from = upscaled?.let(Uri::fromFile) ?: item.uri
        value = withContext(Dispatchers.IO) { MediaPrep.decode(context, from, PREVIEW_EDGE) }
    }
    val stickers = remember { PreviewStickers() }

    // The layer a finger is moving: left out of the rendered picture and drawn live.
    var dragging by remember { mutableStateOf<Long?>(null) }

    // The brush-size slider in hand: the canvas shows the brush at that size,
    // and keeps it up a moment after the finger lifts so a tap shows it too.
    var sizing by remember { mutableStateOf(false) }
    var sizingLetGo by remember { mutableIntStateOf(0) }
    LaunchedEffect(sizingLetGo) {
        if (sizingLetGo == 0) return@LaunchedEffect
        delay(BRUSH_PREVIEW_LINGER_MS)
        sizing = false
    }

    val baseKey = edit.copy(crop = CropRect.FULL, aspect = com.wasimaster.wmkeyboard.core.media.gallery.edit.CropAspect.FREE,
        strokes = emptyList(), texts = emptyList(), stickers = emptyList())
    val base by produceState<Bitmap?>(null, source, baseKey) {
        val src = source ?: return@produceState
        value = withContext(Dispatchers.Default) { PhotoEditRenderer.base(src, baseKey) }
    }
    val layersKey = Triple(edit.strokes, edit.texts.filterNot { it.id == dragging }, edit.stickers.filterNot { it.id == dragging })
    val layered by produceState<Bitmap?>(null, base, layersKey) {
        val b = base ?: return@produceState
        value = withContext(Dispatchers.Default) {
            PhotoEditRenderer.withLayers(b, edit.copy(texts = layersKey.second, stickers = layersKey.third), stickers)
        }
    }
    val shown = if (editor.comparing) source else layered ?: base

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(PhotoBackdrop),
        ) {
            EditorTopBar(editor, history?.canUndo == true, history?.canRedo == true, !edit.isEmpty, typing != null, act)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val bmp = shown
                if (bmp == null) {
                    CircularProgressIndicator(color = OnPhoto, modifier = Modifier.align(Alignment.Center).size(32.dp))
                } else {
                    EditorCanvas(
                        bitmap = bmp,
                        turned = base?.let { UnitIntSize(it.width, it.height) } ?: UnitIntSize(bmp.width, bmp.height),
                        edit = edit,
                        editor = editor,
                        dragging = dragging,
                        onDragging = { dragging = it },
                        stickers = stickers,
                        showWhole = editor.tool == EditorTool.CROP || editor.comparing,
                        sizingBrush = sizing && editor.tool == EditorTool.DRAW,
                        act = act,
                    )
                }
            }
            if (typing == null) {
                EditorControls(
                    editor = editor,
                    edit = edit,
                    pictureRatio = base?.let { it.width.toFloat() / it.height } ?: 1f,
                    source = source,
                    photoSize = photoSize(item, source, edit),
                    callbacks = callbacks,
                    onBrushSizing = { held ->
                        sizing = true
                        if (!held) sizingLetGo++
                    },
                )
                ToolRail(editor.tool, act)
            }
        }
        SaveCard(
            visible = editor.saveMenuOpen && typing == null,
            replaceable = item.replaceable,
            act = act,
        )
    }
}

/**
 * Where the edited photo goes in the gallery, under the top bar's Save: a
 * new picture beside the original, or the original itself, rewritten.
 */
@Composable
private fun SaveCard(visible: Boolean, replaceable: Boolean, act: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 120)) + scaleIn(tween(if (kb.reduceMotion) 0 else 160), initialScale = 0.96f),
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 100)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    act(GalleryAction.SaveMenu(false))
                },
        ) {
            val shape = kb.cardShape()
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 50.dp, end = 8.dp)
                    .width(272.dp)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CARDS)
                    .background(kb.popup)
                    .chipBorder(kb, shape)
                    .padding(vertical = 4.dp),
            ) {
                SaveRow(
                    Icons.Outlined.ContentCopy,
                    stringResource(R.string.ime_gallery_editor_save_copy),
                    stringResource(R.string.ime_gallery_editor_save_copy_body),
                    enabled = true,
                ) { act(GalleryAction.SaveEdit(replace = false)) }
                SaveRow(
                    Icons.Outlined.SaveAs,
                    stringResource(R.string.ime_gallery_editor_replace),
                    stringResource(
                        if (replaceable) R.string.ime_gallery_editor_replace_body else R.string.ime_gallery_editor_replace_unavailable,
                    ),
                    enabled = replaceable,
                ) { act(GalleryAction.SaveEdit(replace = true)) }
            }
        }
    }
}

@Composable
private fun SaveRow(icon: ImageVector, title: String, body: String, enabled: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kb.popupText.copy(alpha = alpha), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = kb.popupText.copy(alpha = alpha), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(body, color = kb.popupText.copy(alpha = 0.7f * alpha), fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

/**
 * The photo's own size, upright and before any upscale: the library's
 * measurement where it has one, else the preview's, which is the photo's own
 * size for any photo small enough to upscale.
 */
private fun photoSize(item: GalleryItem, preview: Bitmap?, edit: PhotoEdit): Pair<Int, Int> {
    if (item.width > 0 && item.height > 0) {
        return if (item.orientation % 180 == 0) item.width to item.height else item.height to item.width
    }
    val p = preview ?: return 0 to 0
    val f = edit.upscale?.factor ?: 1
    return p.width / f to p.height / f
}

/** Sticker pictures for the preview, decoded once per editor. */
internal class PreviewStickers : PhotoEditRenderer.StickerSource {
    private val cache = HashMap<String, Bitmap?>()

    @Synchronized
    override fun bitmap(layer: StickerLayer): Bitmap? {
        val path = layer.path ?: return null
        return cache.getOrPut(path) { decodeStickerFile(path) }
    }
}

@Composable
private fun EditorTopBar(
    editor: EditorUi,
    canUndo: Boolean,
    canRedo: Boolean,
    edited: Boolean,
    typing: Boolean,
    act: (GalleryAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoButton(Icons.Outlined.Close, stringResource(R.string.ime_gallery_editor_close), size = 36.dp) {
            act(GalleryAction.CloseEditor)
        }
        Spacer(Modifier.weight(1f))
        if (!typing) {
            PhotoButton(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.ime_gallery_editor_undo), size = 36.dp, enabled = canUndo) {
                act(GalleryAction.Undo)
            }
            Spacer(Modifier.width(6.dp))
            PhotoButton(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.ime_gallery_editor_redo), size = 36.dp, enabled = canRedo) {
                act(GalleryAction.Redo)
            }
            Spacer(Modifier.width(6.dp))
            // Hold for the original: before and after under one thumb.
            val compare = stringResource(R.string.ime_gallery_editor_compare)
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (editor.comparing) LocalKbTheme.current.accent else Color.Black.copy(alpha = 0.42f))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            act(GalleryAction.Compare(true))
                            tryAwaitRelease()
                            act(GalleryAction.Compare(false))
                        })
                    }
                    .semantics { contentDescription = compare },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, tint = OnPhoto, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(6.dp))
            PhotoButton(Icons.Outlined.RestartAlt, stringResource(R.string.ime_gallery_editor_reset), size = 36.dp, enabled = edited) {
                act(GalleryAction.ResetEdit)
            }
            Spacer(Modifier.width(6.dp))
            if (editor.saving) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OnPhoto, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            } else {
                PhotoButton(
                    Icons.Outlined.SaveAlt,
                    stringResource(R.string.ime_gallery_editor_save),
                    size = 36.dp,
                    enabled = edited,
                    active = editor.saveMenuOpen,
                ) { act(GalleryAction.SaveMenu(!editor.saveMenuOpen)) }
            }
            Spacer(Modifier.width(6.dp))
        }
        PhotoButton(Icons.Outlined.Check, stringResource(R.string.ime_gallery_editor_done), size = 36.dp, active = true) {
            act(if (typing) GalleryAction.TextDone else GalleryAction.CloseEditor)
        }
    }
}

/** The editor preview's longest side, in px: sharp at screen width, never the full camera frame. */
private const val PREVIEW_EDGE = 2048

/** How long the brush preview stays up after the size slider is let go. */
private const val BRUSH_PREVIEW_LINGER_MS = 700L

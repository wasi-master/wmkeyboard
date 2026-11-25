package com.wasimaster.wmkeyboard.ime.ui.files

import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileKind
import com.wasimaster.wmkeyboard.core.files.FileKinds
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.InstallMobile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.files.FileAction
import com.wasimaster.wmkeyboard.ime.files.FileManagerUi
import com.wasimaster.wmkeyboard.ime.files.PreviewUi
import com.wasimaster.wmkeyboard.ime.files.TypeWhat
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.codeFontFamily
import com.wasimaster.wmkeyboard.ime.ui.gallery.DOUBLE_TAP_ZOOM
import com.wasimaster.wmkeyboard.ime.ui.gallery.MAX_ZOOM
import com.wasimaster.wmkeyboard.ime.ui.gallery.MediaThumb
import com.wasimaster.wmkeyboard.ime.ui.gallery.OnPhoto
import com.wasimaster.wmkeyboard.ime.ui.gallery.PhotoBackdrop
import com.wasimaster.wmkeyboard.ime.ui.gallery.ZOOM_EPSILON
import com.wasimaster.wmkeyboard.ime.ui.gallery.rememberGalleryImageLoader
import com.wasimaster.wmkeyboard.ime.ui.gallery.zoomAndPan
import com.wasimaster.wmkeyboard.ime.ui.highlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A file shown in the keyboard. Both of the owner's picks live here, each
 * for its own kind: pictures, videos and recordings take the panel as a
 * viewer (4A), with the folder's other pictures along the bottom; text,
 * code, PDFs, app packages and zips sit under a header card with the file's
 * kind, size and date (4B). Either way the actions are the same: Send it into
 * the chat, Type its name, path or words, Share, Open it in its app, Info.
 */
@Composable
internal fun PreviewFace(ui: FileManagerUi, preview: PreviewUi, act: (FileAction) -> Unit) {
    val media = !preview.byPlugin && (preview.kind == FileKind.IMAGE || preview.kind == FileKind.VIDEO || preview.kind == FileKind.AUDIO)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (media) MediaTop(preview, act) else CardTop(preview, act)
            if (preview.choices.size > 1) ViewWith(preview, act)
            preview.offer?.let { OfferBanner(it, act) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(preview.entry.path) {
                        // A firm sideways swipe goes to the next or the previous file.
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (abs(total) > SWIPE_PX) act(FileAction.PreviewStep(if (total < 0) 1 else -1))
                            },
                        ) { _, amount -> total += amount }
                    },
            ) {
                when {
                    preview.byImport -> ImportBody(preview, act)
                    preview.byPlugin -> PluginBody(preview, act)
                    preview.kind == FileKind.IMAGE -> ImageBody(preview.entry)
                    // One player per file: swiping to the next clip starts afresh.
                    preview.kind == FileKind.VIDEO -> androidx.compose.runtime.key(preview.entry.path) { VideoBody(preview.entry) }
                    preview.kind == FileKind.AUDIO -> AudioBody(preview)
                    preview.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = LocalKbTheme.current.secondaryText, strokeWidth = 2.dp)
                    }
                    preview.text != null -> TextBody(preview)
                    preview.kind == FileKind.PDF && preview.pdfPage != null -> PdfBody(preview, act)
                    preview.kind == FileKind.APK -> AppBody(preview)
                    preview.zipItems != null -> ZipBody(preview, act)
                    else -> NoPreview(preview)
                }
            }
            if (media && preview.kind != FileKind.AUDIO) Filmstrip(ui, preview, act)
            PreviewActions(ui, preview, act)
        }
        if (preview.typeMenu) {
            Scrimmed(true, onDismiss = { act(FileAction.ToggleTypeMenu) }) {
                TypeMenu(preview, textual = FileKinds.isTextual(preview.kind), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), act = act)
            }
        }
    }
}

/** The viewer's top line (4A): back, the name, "3 of 42 · size · dimensions", Info. */
@Composable
private fun MediaTop(preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().background(kb.popup).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(icon = Icons.Outlined.ChevronLeft, description = stringResource(R.string.ime_files_preview_back), size = 34.dp) { act(FileAction.ClosePreview) }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(preview.entry.name, color = kb.popupText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val facts = preview.facts
            val parts = listOfNotNull(
                stringResource(R.string.ime_files_preview_position, preview.index + 1, preview.count),
                bytes(context, preview.entry.size),
                if (facts.width != null && facts.height != null) "${facts.width} × ${facts.height}" else null,
            )
            Text(parts.joinToString(" · "), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
        }
        RoundButton(icon = Icons.Outlined.Info, description = stringResource(R.string.ime_files_info), size = 34.dp) { act(FileAction.Info) }
        Spacer(Modifier.width(4.dp))
    }
}

/** The header card (4B): back, the kind's tile with its badge, the name, kind, size, date. */
@Composable
private fun CardTop(preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().background(kb.popup).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(icon = Icons.Outlined.ChevronLeft, description = stringResource(R.string.ime_files_preview_back), size = 34.dp) { act(FileAction.ClosePreview) }
        Spacer(Modifier.width(8.dp))
        KindTile(preview.entry, 40.dp, picked = false, thumbnails = false)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(preview.entry.name, color = kb.popupText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val lines = preview.text?.takeIf { !preview.byPlugin }?.let { t -> t.count { it == '\n' } + 1 }
            val by = preview.choices.firstOrNull { it.key == preview.choice && preview.byPlugin }?.label
            val parts = listOfNotNull(
                by?.let { stringResource(R.string.ime_files_preview_by, it) } ?: stringResource(kindName(preview.kind)),
                bytes(context, preview.entry.size),
                lines?.let { pluralStringResource(R.plurals.ime_files_lines, it, it) },
                preview.pdfCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ime_files_pages, it, it) },
            )
            Text(parts.joinToString(" · "), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(icon = Icons.Outlined.Info, description = stringResource(R.string.ime_files_info), size = 34.dp) { act(FileAction.Info) }
        Spacer(Modifier.width(2.dp))
    }
}

@Composable
private fun ImageBody(entry: FileEntry) {
    val context = LocalContext.current
    var scale by remember(entry.path) { mutableFloatStateOf(1f) }
    var offset by remember(entry.path) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(PhotoBackdrop)) {
        AsyncImage(
            model = DocFiles.uriOf(context, entry.path),
            contentDescription = entry.name,
            imageLoader = rememberGalleryImageLoader(),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .zoomAndPan(
                    zoomed = scale > 1f + ZOOM_EPSILON,
                    onTransform = { z, pan ->
                        scale = (scale * z).coerceIn(1f, MAX_ZOOM)
                        offset = if (scale <= 1f + ZOOM_EPSILON) Offset.Zero else offset + pan
                    },
                    onDoubleTap = {
                        scale = if (scale > 1f + ZOOM_EPSILON) 1f else DOUBLE_TAP_ZOOM
                        offset = Offset.Zero
                    },
                    onTap = {},
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** A recording: its title and artist where it has them, play and pause, and how far it has got. */
@Composable
private fun AudioBody(preview: PreviewUi) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val entry = preview.entry
    var player by remember(entry.path) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(entry.path) { mutableStateOf(false) }
    var position by remember(entry.path) { mutableIntStateOf(0) }
    var length by remember(entry.path) { mutableIntStateOf((preview.facts.durationMs ?: 0L).toInt()) }
    DisposableEffect(entry.path) {
        // Opened off the main thread: a document can take a while to open.
        // Released by whichever side finishes last, never from the main
        // thread while it is still opening (that would wait on it).
        var gone = false
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var made: MediaPlayer? = null
            val p = runCatching {
                MediaPlayer().also { mp ->
                    made = mp
                    mp.setDataSource(context.applicationContext, DocFiles.uriOf(context, entry.path))
                    mp.setOnCompletionListener { playing = false }
                    mp.prepare()
                }
            }.onFailure { runCatching { made?.release() } }.getOrNull() ?: return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (gone) {
                    runCatching { p.release() }
                } else {
                    length = p.duration
                    player = p
                }
            }
        }
        onDispose {
            gone = true
            runCatching { player?.release() }
            player = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(250)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        KindTile(entry, 84.dp, picked = false, thumbnails = false, corner = 22.dp)
        Spacer(Modifier.height(14.dp))
        Text(preview.facts.title ?: entry.name, color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center)
        val by = listOfNotNull(preview.facts.artist, preview.facts.album).joinToString(" · ")
        if (by.isNotEmpty()) Text(by, color = kb.secondaryText, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(16.dp))
        SeekBar(
            fraction = if (length > 0) position.toFloat() / length else 0f,
            colour = kb.accent,
            track = kb.chip,
            modifier = Modifier.fillMaxWidth(),
        ) { f ->
            val p = player ?: return@SeekBar
            if (length <= 0) return@SeekBar
            position = (f.coerceIn(0f, 1f) * length).toInt()
            runCatching { p.seekTo(position) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(clock(position), color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(clock(length), color = kb.secondaryText, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        RoundButton(
            icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            description = stringResource(if (playing) R.string.ime_files_pause else R.string.ime_files_play),
            primary = true,
            size = 52.dp,
        ) {
            val p = player ?: return@RoundButton
            runCatching { if (p.isPlaying) p.pause() else p.start() }
            playing = runCatching { p.isPlaying }.getOrDefault(false)
        }
    }
}

private fun clock(ms: Int): String = clockOf(ms)

/** Text and code (4B): line numbers, the code's colours, the keyboard's monospace face. */
@Composable
private fun TextBody(preview: PreviewUi) {
    val kb = LocalKbTheme.current
    val text = preview.text.orEmpty()
    val code = preview.kind == FileKind.CODE
    val styled = remember(preview.entry.path, text, preview.syntax) {
        val syntax = preview.syntax
        when {
            syntax != null -> com.wasimaster.wmkeyboard.ime.ui.highlightTokens(text, syntax, kb.dark)
            code -> highlight(text, FileKinds.codeLanguage(preview.entry.name), kb.dark)
            else -> androidx.compose.ui.text.AnnotatedString(text)
        }
    }
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    val numbers = remember(lines) { (1..lines).joinToString("\n") }
    Box(Modifier.fillMaxSize().background(if (kb.dark) Color(0xFF15161A) else Color(0xFFF6F7F9))) {
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Text(numbers, color = kb.secondaryText.copy(alpha = 0.6f), fontSize = 11.5.sp, lineHeight = 18.sp, fontFamily = codeFontFamily(), textAlign = TextAlign.End)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(styled, color = kb.suggestionText, fontSize = 11.5.sp, lineHeight = 18.sp, fontFamily = codeFontFamily(), softWrap = false)
                if (preview.textCut) {
                    Text(stringResource(R.string.ime_files_preview_cut), color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** A PDF a page at a time, on white, with the page number and the way to the next. */
@Composable
private fun PdfBody(preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val page = preview.pdfPage ?: return
    val image = remember(page) { page.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(if (kb.dark) Color(0xFF2A2C31) else Color(0xFFE8EAED))) {
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState()),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ChevronLeft,
                contentDescription = stringResource(R.string.ime_files_page_previous),
                tint = OnPhoto,
                modifier = Modifier.size(34.dp).clip(CircleShape).clickable(enabled = preview.pdfIndex > 0) { act(FileAction.PdfStep(-1)) }.padding(6.dp),
            )
            Text(
                stringResource(R.string.ime_files_page_of, preview.pdfIndex + 1, preview.pdfCount),
                color = OnPhoto,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = stringResource(R.string.ime_files_page_next),
                tint = OnPhoto,
                modifier = Modifier.size(34.dp).clip(CircleShape).clickable(enabled = preview.pdfIndex < preview.pdfCount - 1) { act(FileAction.PdfStep(1)) }.padding(6.dp),
            )
        }
    }
}

/** An app package: its icon, name, id, version and the oldest Android it runs on, read as the installer reads them. */
@Composable
private fun AppBody(preview: PreviewUi) {
    val kb = LocalKbTheme.current
    val facts = preview.facts
    val icon = remember(preview.appIcon) { preview.appIcon?.let { runCatching { it.toBitmap(144, 144).asImageBitmap() }.getOrNull() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(72.dp))
        } else {
            KindTile(preview.entry, 72.dp, picked = false, thumbnails = false, corner = 18.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text(facts.appLabel ?: preview.entry.name, color = kb.suggestionText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        facts.appPackage?.let { Text(it, color = kb.secondaryText, fontSize = 12.sp, fontFamily = codeFontFamily()) }
        Spacer(Modifier.height(12.dp))
        facts.appVersion?.let { InfoPair(stringResource(R.string.ime_files_app_version), it) }
        facts.appMinSdk?.let { InfoPair(stringResource(R.string.ime_files_app_min_sdk), it.toString()) }
        preview.zipItems?.let { InfoPair(stringResource(R.string.ime_files_app_entries), "%,d".format(it.size)) }
        if (facts.appLabel == null) {
            Text(stringResource(R.string.ime_files_app_unreadable), color = kb.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun InfoPair(label: String, value: String) {
    val kb = LocalKbTheme.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = kb.secondaryText, fontSize = 12.5.sp, modifier = Modifier.width(120.dp))
        Text(value, color = kb.suggestionText, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** What a zip holds, folders and sizes, and Extract here. */
@Composable
private fun ZipBody(preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val items = preview.zipItems.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.ime_files_zip_items, items.size, items.size),
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (FileKinds.isZip(preview.entry.name)) {
                PillButton(stringResource(R.string.ime_files_extract_here), icon = Icons.Outlined.FolderOpen, primary = true, height = 32.dp) { act(FileAction.Unzip) }
            }
        }
        val list = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 6.dp)) {
            itemsIndexed(items, key = { i, it -> "$i:${it.path}" }) { _, item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    val name = item.path.substringAfterLast('/')
                    val depth = item.path.count { it == '/' }
                    Spacer(Modifier.width((depth.coerceAtMost(6) * 12).dp))
                    if (item.isDirectory) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(kindIcon(FileKinds.of(name, "")), contentDescription = null, tint = kindColour(FileKinds.of(name, "")), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(name, color = kb.suggestionText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                    if (!item.isDirectory && item.size >= 0) Text(bytes(context, item.size), color = kb.secondaryText, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * "View with": the keyboard's own view and each plugin that reads this type,
 * as chips under the header. The one picked is kept for files of this type.
 */
@Composable
private fun ViewWith(preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier.fillMaxWidth().background(kb.popup).horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.ime_files_view_with), color = kb.secondaryText, fontSize = 11.5.sp)
        for (choice in preview.choices) {
            com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip(
                choice.label ?: stringResource(
                    if (choice.key == com.wasimaster.wmkeyboard.ime.files.PreviewChoice.IMPORT) R.string.ime_files_view_import else R.string.ime_files_view_built_in,
                ),
                selected = choice.key == preview.choice,
            ) { act(FileAction.PreviewWith(choice.key)) }
        }
    }
}

/**
 * A plugin from the add-on repositories that would draw this file: its name,
 * what it does, Get (its page in the add-on store, where it installs after
 * its permissions show) and Not now, which stops offering it for this type.
 */
@Composable
private fun OfferBanner(offer: com.wasimaster.wmkeyboard.ime.files.PluginOffer, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(kb.popup)
            .padding(start = 12.dp, end = 6.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(kb.accent.copy(alpha = 0.12f))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ime_files_offer_title, offer.name),
                color = kb.popupText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (offer.description.isNotBlank()) {
                Text(
                    offer.description,
                    color = kb.secondaryText,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        PillButton(stringResource(R.string.ime_files_offer_get), icon = Icons.Outlined.Download, primary = true, height = 32.dp) {
            act(FileAction.GetPlugin)
        }
        RoundButton(icon = Icons.Outlined.Close, description = stringResource(R.string.ime_files_offer_not_now), size = 32.dp) {
            act(FileAction.DismissOffer)
        }
    }
}

/**
 * What a plugin drew, in the keyboard's theme, with nothing to tap but the
 * host's own copy and type buttons; or why it drew nothing, with the way back
 * to the built-in view or another app.
 */
@Composable
private fun PluginBody(preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val ui = preview.pluginUi
    val error = preview.pluginError
    when {
        preview.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = kb.secondaryText, strokeWidth = 2.dp)
        }
        error != null || ui == null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            KindTile(preview.entry, 64.dp, picked = false, thumbnails = false, corner = 16.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                error?.resolve(context) ?: stringResource(R.string.ime_files_no_preview),
                color = kb.secondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview.choices.any { it.key == com.wasimaster.wmkeyboard.ime.files.PreviewChoice.BUILT_IN }) {
                    PillButton(stringResource(R.string.ime_files_view_built_in), height = 34.dp) {
                        act(FileAction.PreviewWith(com.wasimaster.wmkeyboard.ime.files.PreviewChoice.BUILT_IN))
                    }
                }
                PillButton(stringResource(R.string.ime_files_open_with), icon = Icons.Outlined.OpenInNew, height = 34.dp) { act(FileAction.OpenWith) }
            }
        }
        else -> {
            val plugins = LocalFilePlugins.current
            val pluginId = com.wasimaster.wmkeyboard.core.plugins.PluginPreviewRef.parse(preview.choice)?.first
            val font = rememberPreviewFont(preview)
            val store = remember { com.wasimaster.wmkeyboard.core.plugins.PluginStore.get(context) }
            val style = remember(kb, pluginId, font, plugins) {
                com.wasimaster.wmkeyboard.ime.ui.keyboardPluginStyle(
                    kb,
                    pluginId,
                    store,
                    grammars = plugins?.let { p -> { language: String -> p.grammarForLanguage(language) } },
                    previewFont = font,
                )
            }
            androidx.compose.runtime.CompositionLocalProvider(com.wasimaster.wmkeyboard.core.plugins.ui.LocalPluginPanelStyle provides style) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    com.wasimaster.wmkeyboard.core.plugins.ui.PluginWidgetList(
                        widgets = ui.root,
                        host = StillInputs,
                        onEvent = {},
                        onInsert = { text -> act(FileAction.InsertPluginText(text)) },
                        onCopy = { text -> act(FileAction.CopyPluginText(text)) },
                    )
                }
            }
        }
    }
}

/**
 * A file the app imports, as its import dialog would put it: the dialog's
 * picture of the contents (a theme's miniature, a layout's grid, a pack's
 * stickers), what importing it does, what will not come across, and Import,
 * which opens that dialog to ask anything left to ask and do it.
 */
@Composable
private fun ImportBody(preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val found = preview.importable ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        found.visual?.let { visual ->
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides kb.suggestionText) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) { visual() }
            }
        }
        Text(found.title, color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (found.body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(found.body, color = kb.secondaryText, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (found.notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            for (note in found.notes) {
                Text("• $note", color = kb.secondaryText, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.ime_files_import), icon = Icons.Outlined.InstallMobile, primary = true, height = 38.dp) { act(FileAction.Import) }
    }
}

/** A preview has no text boxes the keys can reach: every one stays as drawn. */
private object StillInputs : com.wasimaster.wmkeyboard.core.plugins.ui.PluginInputHost {
    override fun value(id: String): String = ""
    override fun isFocused(id: String): Boolean = false
    override fun onFocus(id: String) = Unit
    override fun onPaste(id: String) = Unit
    override val onValueChange: ((String, String) -> Unit)? = null
}

/**
 * The previewed file's own font, for `ui.font_sample`: loaded when the file
 * is a font Android can read, else null and the sample is drawn in the
 * panel's font.
 */
@Composable
private fun rememberPreviewFont(preview: PreviewUi): androidx.compose.ui.text.font.FontFamily? {
    val context = LocalContext.current
    val path = preview.entry.path
    val font by androidx.compose.runtime.produceState<androidx.compose.ui.text.font.FontFamily?>(null, path) {
        if (preview.kind != FileKind.FONT) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val typeface = if (!DocFiles.isDoc(path)) {
                    android.graphics.Typeface.createFromFile(path)
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.contentResolver.openFileDescriptor(DocFiles.uriOf(context, path), "r")?.use { fd ->
                        android.graphics.Typeface.Builder(fd.fileDescriptor).build()
                    }
                } else {
                    null
                }
                typeface?.takeIf { it != android.graphics.Typeface.DEFAULT }?.let { androidx.compose.ui.text.font.FontFamily(it) }
            }.getOrNull()
        }
    }
    return font
}

@Composable
private fun NoPreview(preview: PreviewUi) {
    val kb = LocalKbTheme.current
    // Still being read for an import: a moment more, rather than "no preview" and then one.
    if (preview.importChecking) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = kb.secondaryText, strokeWidth = 2.dp)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        KindTile(preview.entry, 72.dp, picked = false, thumbnails = false, corner = 18.dp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.ime_files_no_preview), color = kb.secondaryText, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** The folder's other pictures and videos along the bottom (4A); the one showing is outlined. */
@Composable
private fun Filmstrip(ui: FileManagerUi, preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val media = remember(ui.entries) {
        ui.entries.filter { val k = FileKinds.of(it); k == FileKind.IMAGE || k == FileKind.VIDEO }
    }
    if (media.size < 2) return
    val at = media.indexOfFirst { it.path == preview.entry.path }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (at - 3).coerceAtLeast(0))
    LaunchedEffect(at) { state.animateScrollToItem((at - 3).coerceAtLeast(0)) }
    LazyRow(
        Modifier.fillMaxWidth().background(PhotoBackdrop).padding(vertical = 8.dp),
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(media, key = { _, it -> it.path }) { i, entry ->
            val current = i == at
            val thumb = remember(entry.path) { MediaThumb(DocFiles.uriOf(context, entry.path), FileKinds.of(entry) == FileKind.VIDEO, THUMB_PX, entry.modifiedMs / 1000) }
            AsyncImage(
                model = thumb,
                contentDescription = entry.name,
                imageLoader = rememberGalleryImageLoader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (current) Modifier.border(2.dp, kb.accent, RoundedCornerShape(8.dp)) else Modifier.graphicsLayer { alpha = 0.7f })
                    .clickable { act(FileAction.Preview(entry)) },
            )
        }
    }
}

/**
 * Send to chat first, then Type (its choices above it), Share and Open;
 * a text file also offers Copy text. Nothing types into a password field.
 */
@Composable
private fun PreviewActions(ui: FileManagerUi, preview: PreviewUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val textual = FileKinds.isTextual(preview.kind) && !preview.byPlugin
    val media = !preview.byPlugin && (preview.kind == FileKind.IMAGE || preview.kind == FileKind.VIDEO || preview.kind == FileKind.AUDIO)
    if (media) {
        Row(
            Modifier.fillMaxWidth().background(PhotoBackdrop).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(stringResource(R.string.ime_files_send_to_chat), modifier = Modifier.weight(1f), icon = Icons.Outlined.Send, primary = true) { act(FileAction.Send) }
            if (!ui.secureField) RoundButton(icon = Icons.Outlined.TextFields, description = stringResource(R.string.ime_files_type), size = 36.dp) { act(FileAction.ToggleTypeMenu) }
            RoundButton(icon = Icons.Outlined.Share, description = stringResource(R.string.ime_files_share), size = 36.dp) { act(FileAction.Share) }
            RoundButton(icon = Icons.Outlined.OpenInNew, description = stringResource(R.string.ime_files_open_with), size = 36.dp) { act(FileAction.OpenWith) }
        }
    } else {
        val importable = preview.importable != null
        ActionStrip(
            buildList {
                // A file the app imports leads with Import, the one thing most people open it for.
                if (importable) add(BarAction(Icons.Outlined.InstallMobile, stringResource(R.string.ime_files_import), primary = true) { act(FileAction.Import) })
                add(BarAction(Icons.Outlined.Send, stringResource(R.string.ime_files_send), primary = !importable) { act(FileAction.Send) })
                if (!ui.secureField) add(BarAction(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.ime_files_type)) { act(FileAction.ToggleTypeMenu) })
                if (textual && preview.text != null) add(BarAction(Icons.Outlined.ContentCopy, stringResource(R.string.ime_files_copy_text)) { act(FileAction.CopyText) })
                add(BarAction(Icons.Outlined.Share, stringResource(R.string.ime_files_share)) { act(FileAction.Share) })
                add(BarAction(Icons.Outlined.OpenInNew, stringResource(R.string.ime_files_open_with)) { act(FileAction.OpenWith) })
            },
        )
    }
}

@Composable
private fun TypeMenu(preview: PreviewUi, textual: Boolean, modifier: Modifier, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.padding(horizontal = 24.dp).clip(shape).background(kb.popup).padding(vertical = 4.dp)) {
        listOfNotNull(
            TypeWhat.NAME to R.string.ime_files_type_name,
            TypeWhat.PATH to R.string.ime_files_type_path,
            if (textual && preview.entry.size <= com.wasimaster.wmkeyboard.ime.files.FileManagerController.TYPE_TEXT_MAX) {
                TypeWhat.CONTENTS to R.string.ime_files_type_contents
            } else {
                null
            },
        ).forEach { (what, label) ->
            Text(
                stringResource(label),
                color = kb.popupText,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { act(FileAction.Type(what)) }.padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

internal fun kindName(kind: FileKind): Int = when (kind) {
    FileKind.FOLDER -> R.string.ime_files_kind_folder
    FileKind.IMAGE -> R.string.ime_files_kind_image
    FileKind.VIDEO -> R.string.ime_files_kind_video
    FileKind.AUDIO -> R.string.ime_files_kind_audio
    FileKind.PDF -> R.string.ime_files_kind_pdf
    FileKind.DOC -> R.string.ime_files_kind_doc
    FileKind.SHEET -> R.string.ime_files_kind_sheet
    FileKind.SLIDES -> R.string.ime_files_kind_slides
    FileKind.TEXT -> R.string.ime_files_kind_text
    FileKind.CODE -> R.string.ime_files_kind_code
    FileKind.ARCHIVE -> R.string.ime_files_kind_archive
    FileKind.APK -> R.string.ime_files_kind_apk
    FileKind.EBOOK -> R.string.ime_files_kind_ebook
    FileKind.FONT -> R.string.ime_files_kind_font
    FileKind.OTHER -> R.string.ime_files_kind_other
}

private const val SWIPE_PX = 120f

package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AttachFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBox
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DocumentScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Draw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragIndicator
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatBold
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatItalic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListBulleted
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListNumbered
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatStrikethrough
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatUnderlined
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HorizontalRule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFormat
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.notes.NoteAttachments
import com.wasimaster.wmkeyboard.core.notes.rich.NoteBlockStyle
import com.wasimaster.wmkeyboard.core.notes.rich.NoteDraft
import com.wasimaster.wmkeyboard.core.notes.rich.NoteFont
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMark
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkKind
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup
import com.wasimaster.wmkeyboard.core.notes.rich.NoteRichEdits
import com.wasimaster.wmkeyboard.core.notes.rich.NoteRichPalette
import com.wasimaster.wmkeyboard.core.notes.rich.NoteUnderline
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.ime.docscan.PageScanScreen
import com.wasimaster.wmkeyboard.ime.ui.ADD_CAMERA
import com.wasimaster.wmkeyboard.ime.ui.ADD_DRAWING
import com.wasimaster.wmkeyboard.ime.ui.ADD_PHOTO
import com.wasimaster.wmkeyboard.ime.ui.ADD_SCAN
import com.wasimaster.wmkeyboard.ime.ui.NoteRichStyle
import com.wasimaster.wmkeyboard.ime.wmcalendar.NoteScanText
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sheets and full-screen tools the rich editor's toolbar opens (#1586). */
internal enum class RichPanel { FORMAT, ADD, DRAWING, SCAN_PICTURES, SCAN_TEXT, REARRANGE }

/**
 * Everything the rich editor's toolbar can do, set up once in the editor's
 * composition: the format and add sheets, the photo, camera and file
 * pickers, the drawing canvas, the scanner, and the rearrange view. Each
 * change goes through [NoteRichController.apply] on the field that had the
 * caret, so it lands where the user was writing, and is one Undo step.
 */
@Stable
internal class NoteRichTools(
    val controller: NoteRichController,
    private val bodyNow: () -> String,
    private val setBody: (String) -> Unit,
) {
    internal var panel by mutableStateOf<RichPanel?>(null)

    /** [op] on the focused text; a step of its own for Undo. */
    fun edit(op: (NoteDraft) -> NoteDraft) {
        val before = bodyNow()
        val after = controller.apply(before, op) ?: return
        controller.record(before, force = true)
        setBody(after)
    }

    /** [mark] from the format sheet: on the selection, the word at the caret, or the next letters typed. */
    fun mark(mark: NoteMark) {
        val before = bodyNow()
        var pending = controller.pending
        val after = controller.apply(before) { d ->
            val pick = NoteRichEdits.pickMark(d, pending, mark)
            pending = pick.pending
            pick.draft ?: d
        }
        controller.pending = pending
        if (after != null) {
            controller.record(before, force = true)
            setBody(after)
        }
    }

    fun block(style: NoteBlockStyle) = edit { NoteRichEdits.setBlock(it, style) }

    fun clearMarks() {
        controller.pending = emptyList()
        edit { d -> if (d.collapsed) NoteRichEdits.wordAround(d)?.let(NoteRichEdits::clearMarks)?.let { NoteDraft(it.text, it.end) } ?: d else NoteRichEdits.clearMarks(d) }
    }

    fun insertPictures(names: List<String>) {
        if (names.isNotEmpty()) edit { NoteRichEdits.insertImages(it, names) }
    }

    fun insertText(text: String) {
        if (text.isNotBlank()) edit { NoteRichEdits.insertBlock(it, text.trim()) }
    }

    fun undo() {
        controller.undo(bodyNow())?.let(setBody)
    }

    fun redo() {
        controller.redo(bodyNow())?.let(setBody)
    }

    /** The marks on the text at the caret, and the ones waiting, for the sheet's on states. */
    fun activeMarks(): Set<NoteMark> {
        val d = controller.focusedDraft(bodyNow()) ?: return controller.pending.toSet()
        return NoteRichEdits.marksAt(d) + controller.pending
    }

    fun activeBlock(): NoteBlockStyle? = controller.focusedDraft(bodyNow())?.let(NoteRichEdits::blockAt)
}

/**
 * Sets up [NoteRichTools] and draws whichever of its sheets or dialogs is
 * open. [startAdd] is what the keyboard's editor sent the note here for (a
 * photo, the camera, a drawing or a scan), started once on arrival.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberNoteRichTools(
    body: String,
    onBody: (String) -> Unit,
    controller: NoteRichController,
    settings: LiveSettings,
    startAdd: String?,
): NoteRichTools {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestBody by rememberUpdatedState(body)
    val latestOnBody by rememberUpdatedState(onBody)
    val tools = remember(controller) { NoteRichTools(controller, { latestBody }, { latestOnBody(it) }) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun takeIn(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        scope.launch {
            val names = withContext(Dispatchers.IO) { uris.mapNotNull { importNotePicture(context, it) } }
            busy = false
            tools.insertPictures(names)
            if (names.size < uris.size) message = context.getString(R.string.notes_rich_picture_failed)
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { takeIn(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { takeIn(it) }
    val cameraFile = remember { File(File(context.cacheDir, CAMERA_DIR), "photo.jpg") }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken && cameraFile.length() > 0) takeIn(listOf(Uri.fromFile(cameraFile)))
    }
    val launchCamera: () -> Unit = {
        runCatching {
            cameraFile.parentFile?.mkdirs()
            cameraFile.delete()
            takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.clipboard", cameraFile))
        }
    }
    // The app holds the camera permission for its camera tool, and Android
    // then refuses the capture intent to it until the permission is granted.
    val cameraGrant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else message = context.getString(R.string.notes_rich_camera_denied)
    }
    val camera: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraGrant.launch(Manifest.permission.CAMERA)
        }
    }
    val photos: () -> Unit = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val files: () -> Unit = { pickFiles.launch(arrayOf("image/*")) }

    // Sent here by the keyboard's editor for one of these: start it once.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startAdd) {
        if (started || startAdd == null) return@LaunchedEffect
        started = true
        when (startAdd) {
            ADD_PHOTO -> photos()
            ADD_CAMERA -> camera()
            ADD_DRAWING -> tools.panel = RichPanel.DRAWING
            ADD_SCAN -> tools.panel = RichPanel.SCAN_PICTURES
        }
    }

    when (tools.panel) {
        RichPanel.FORMAT -> ModalBottomSheet(onDismissRequest = { tools.panel = null }) { FormatSheet(tools) }
        RichPanel.ADD -> ModalBottomSheet(onDismissRequest = { tools.panel = null }) {
            AddSheet(
                onPick = { item ->
                    tools.panel = null
                    when (item) {
                        AddItem.PHOTOS -> photos()
                        AddItem.CAMERA -> camera()
                        AddItem.DRAWING -> tools.panel = RichPanel.DRAWING
                        AddItem.TABLE -> tools.edit { NoteRichEdits.insertBlock(it, NoteRichEdits.TABLE_TEMPLATE) }
                        AddItem.FILES -> files()
                        AddItem.SCAN -> tools.panel = RichPanel.SCAN_PICTURES
                        AddItem.SCAN_TEXT -> tools.panel = RichPanel.SCAN_TEXT
                        AddItem.DIVIDER -> tools.edit { NoteRichEdits.insertBlock(it, NoteRichEdits.DIVIDER) }
                        AddItem.REARRANGE -> tools.panel = RichPanel.REARRANGE
                    }
                },
            )
        }
        RichPanel.DRAWING -> NoteDrawingDialog(
            onDismiss = { tools.panel = null },
            onSave = { bitmap ->
                tools.panel = null
                scope.launch {
                    val name = withContext(Dispatchers.IO) {
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        bitmap.recycle()
                        NoteAttachments.save(context.filesDir, out.toByteArray(), "png")
                    }
                    if (name != null) tools.insertPictures(listOf(name)) else message = context.getString(R.string.notes_rich_picture_failed)
                }
            },
        )
        RichPanel.SCAN_PICTURES, RichPanel.SCAN_TEXT -> {
            val asText = tools.panel == RichPanel.SCAN_TEXT
            Dialog(
                onDismissRequest = { tools.panel = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    PageScanScreen(
                        downloads = MeteredDecision.ASK,
                        onDone = { pages ->
                            tools.panel = null
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { readScan(context, pages, asText, settings) }
                                busy = false
                                if (result.text.isNotBlank()) tools.insertText(result.text)
                                tools.insertPictures(result.pictures)
                                if (asText && result.unread) message = context.getString(R.string.notes_rich_scan_unread)
                            }
                        },
                        onCancel = { tools.panel = null },
                        onOpenSettings = {
                            tools.panel = null
                            context.startActivity(
                                MainActivityContract.intent(context).putExtra(MainActivityContract.EXTRA_OPEN_SETTING, "privacy_native_downloads_title"),
                            )
                        },
                    )
                }
            }
        }
        RichPanel.REARRANGE -> NoteRearrangeDialog(
            body = body,
            onDismiss = { tools.panel = null },
            onReorder = { reordered ->
                if (reordered != latestBody) {
                    controller.record(latestBody, force = true)
                    controller.values.clear()
                    latestOnBody(reordered)
                }
            },
        )
        null -> Unit
    }
    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = { Text(stringResource(R.string.notes_rich_working)) },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) } },
            text = { Text(text) },
        )
    }
    return tools
}

/** What a scan left to put in the note: the pages read as text, the pages as pictures, and whether some page could not be read. */
private class ScanResult(val text: String, val pictures: List<String>, val unread: Boolean)

/**
 * Each scanned page kept as a picture, or with [asText] read into text by the
 * phone's own reader; a page that cannot be read is kept as a picture instead.
 */
private suspend fun readScan(context: Context, pages: List<File>, asText: Boolean, settings: LiveSettings): ScanResult {
    val texts = ArrayList<String>()
    val pictures = ArrayList<String>()
    var unread = false
    for (page in pages) {
        val uri = Uri.fromFile(page)
        if (asText) {
            val bitmap = MediaPrep.decode(context, uri, OCR_EDGE)
            val text = bitmap?.let { NoteScanText.read(context, it, settings.value) }
            bitmap?.recycle()
            if (text != null) {
                texts += text
                page.delete()
                continue
            }
            unread = true
        }
        importNotePicture(context, uri)?.let { pictures += it }
        page.delete()
    }
    return ScanResult(texts.joinToString("\n\n"), pictures, unread)
}

/**
 * A picture into the note's attachments: decoded upright, no wider or taller
 * than [PICTURE_EDGE], and stored as a JPEG (a PNG when it has transparency).
 * Returns its name, or null when it could not be read or written.
 */
internal fun importNotePicture(context: Context, uri: Uri): String? {
    val bitmap = MediaPrep.decode(context, uri, PICTURE_EDGE) ?: return null
    return try {
        val out = ByteArrayOutputStream()
        val png = bitmap.hasAlpha()
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        NoteAttachments.save(context.filesDir, out.toByteArray(), if (png) "png" else "jpg")
    } catch (_: Exception) {
        null
    } finally {
        bitmap.recycle()
    }
}

private const val MAX_PICK = 10
private const val PICTURE_EDGE = 2048
private const val OCR_EDGE = 3072
private const val JPEG_QUALITY = 85
private const val CAMERA_DIR = "notes_camera"

/**
 * The toolbar along the bottom while a note is open in the rich editor:
 * Format, a task box, Add, then Undo and Redo, with the slash list over it
 * while a line holds `/name`.
 */
@Composable
internal fun NoteRichToolbar(tools: NoteRichTools, body: String) {
    val controller = tools.controller
    val draft = controller.focusedDraft(body)
    val slash = remember(draft) { draft?.let(::slashQuery) }
    Column(Modifier.fillMaxWidth()) {
        if (slash != null && draft != null) SlashCard(tools, draft, slash)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton(Icons.Outlined.TextFormat, stringResource(R.string.notes_rich_format)) { tools.panel = RichPanel.FORMAT }
            ToolbarButton(Icons.Outlined.CheckBox, stringResource(R.string.notes_rich_todo)) { tools.block(NoteBlockStyle.TODO) }
            ToolbarButton(Icons.Outlined.Add, stringResource(R.string.notes_rich_add)) { tools.panel = RichPanel.ADD }
            ToolbarButton(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.notes_rich_undo), enabled = controller.canUndo) { tools.undo() }
            ToolbarButton(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.notes_rich_redo), enabled = controller.canRedo) { tools.redo() }
        }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
}

/** What follows a `/` alone at the start of the caret's line: "" for the slash itself, null when the line is anything else. */
private fun slashQuery(d: NoteDraft): String? {
    if (!d.collapsed) return null
    val start = d.text.lastIndexOf('\n', d.start - 1) + 1
    val typed = d.text.substring(start, d.start)
    if (!typed.startsWith("/") || typed.length > SLASH_MAX) return null
    return typed.substring(1).takeIf { q -> q.all { it.isLetter() } }
}

private const val SLASH_MAX = 17

/** The slash list over the toolbar, as in the screenshots on the issue: styles, lists, inserts. */
@Composable
private fun SlashCard(tools: NoteRichTools, draft: NoteDraft, query: String) {
    val entries = slashEntries().filter { (label, _) -> label.split(' ').any { it.startsWith(query, ignoreCase = true) } }
    if (entries.isEmpty()) return
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().heightIn(max = 240.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            for ((label, pick) in entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            // The `/name` goes, then the pick applies where it stood.
                            tools.edit { d ->
                                val at = d.start - query.length - 1
                                if (at < 0 || d.text.getOrNull(at) != '/') return@edit d
                                val cleared = NoteDraft(d.text.removeRange(at, d.start), at)
                                pick.inline?.invoke(cleared) ?: cleared
                            }
                            pick.panel?.let { tools.panel = it }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(pick.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** One entry of the slash list: an edit made in place, or a sheet or tool opened after the `/name` goes. */
private class SlashPick(val icon: ImageVector, val inline: ((NoteDraft) -> NoteDraft)? = null, val panel: RichPanel? = null)

@Composable
private fun slashEntries(): List<Pair<String, SlashPick>> = listOf(
    stringResource(R.string.notes_rich_title) to SlashPick(Icons.Outlined.TextFields, { NoteRichEdits.setBlock(it, NoteBlockStyle.TITLE) }),
    stringResource(R.string.notes_rich_subtitle) to SlashPick(Icons.Outlined.TextFields, { NoteRichEdits.setBlock(it, NoteBlockStyle.SUBTITLE) }),
    stringResource(R.string.notes_rich_heading) to SlashPick(Icons.Outlined.TextFields, { NoteRichEdits.setBlock(it, NoteBlockStyle.HEADING) }),
    stringResource(R.string.notes_rich_body) to SlashPick(Icons.Outlined.TextFields, { NoteRichEdits.setBlock(it, NoteBlockStyle.BODY) }),
    stringResource(R.string.notes_rich_quote) to SlashPick(Icons.Outlined.TextFormat, { NoteRichEdits.setBlock(it, NoteBlockStyle.QUOTE) }),
    stringResource(R.string.notes_rich_bullets) to SlashPick(Icons.Outlined.FormatListBulleted, { NoteRichEdits.setBlock(it, NoteBlockStyle.BULLET) }),
    stringResource(R.string.notes_rich_numbers) to SlashPick(Icons.Outlined.FormatListNumbered, { NoteRichEdits.setBlock(it, NoteBlockStyle.NUMBERED) }),
    stringResource(R.string.notes_rich_todos) to SlashPick(Icons.Outlined.CheckBox, { NoteRichEdits.setBlock(it, NoteBlockStyle.TODO) }),
    stringResource(R.string.notes_rich_divider) to SlashPick(Icons.Outlined.HorizontalRule, { NoteRichEdits.insertBlock(it, NoteRichEdits.DIVIDER) }),
    stringResource(R.string.notes_rich_table) to SlashPick(Icons.Outlined.GridOn, { NoteRichEdits.insertBlock(it, NoteRichEdits.TABLE_TEMPLATE) }),
    stringResource(R.string.notes_rich_drawing) to SlashPick(Icons.Outlined.Draw, panel = RichPanel.DRAWING),
    stringResource(R.string.notes_rich_scan) to SlashPick(Icons.Outlined.DocumentScanner, panel = RichPanel.SCAN_PICTURES),
    stringResource(R.string.notes_rich_scan_text) to SlashPick(Icons.Outlined.DocumentScanner, panel = RichPanel.SCAN_TEXT),
    stringResource(R.string.notes_rich_more) to SlashPick(Icons.Outlined.Add, panel = RichPanel.ADD),
)

/** The format sheet: paragraph styles, the inline formats, lists, colours, fonts and sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatSheet(tools: NoteRichTools) {
    val marks = tools.activeMarks()
    val block = tools.activeBlock()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.notes_rich_format), style = MaterialTheme.typography.titleLarge)
        // Paragraph styles, each named in its own size, as the screenshots on the issue show them.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val styles = listOf(
                Triple(NoteBlockStyle.TITLE, R.string.notes_rich_title, 24.sp),
                Triple(NoteBlockStyle.SUBTITLE, R.string.notes_rich_subtitle, 20.sp),
                Triple(NoteBlockStyle.HEADING, R.string.notes_rich_heading, 17.sp),
                Triple(NoteBlockStyle.BODY, R.string.notes_rich_body, 15.sp),
                Triple(NoteBlockStyle.QUOTE, R.string.notes_rich_quote, 14.sp),
            )
            for ((style, label, size) in styles) {
                FilterChip(
                    selected = block == style,
                    onClick = { tools.block(style) },
                    label = { Text(stringResource(label), fontSize = size, fontWeight = if (style == NoteBlockStyle.BODY || style == NoteBlockStyle.QUOTE) FontWeight.Normal else FontWeight.SemiBold) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkToggle(Icons.Outlined.FormatBold, R.string.notes_rich_bold, NoteMark.BOLD in marks) { tools.mark(NoteMark.BOLD) }
            MarkToggle(Icons.Outlined.FormatItalic, R.string.notes_rich_italic, NoteMark.ITALIC in marks) { tools.mark(NoteMark.ITALIC) }
            MarkToggle(Icons.Outlined.FormatUnderlined, R.string.notes_rich_underline, NoteMark.UNDERLINE in marks) { tools.mark(NoteMark.UNDERLINE) }
            MarkToggle(Icons.Outlined.FormatStrikethrough, R.string.notes_rich_strike, NoteMark.STRIKE in marks) { tools.mark(NoteMark.STRIKE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkToggle(Icons.Outlined.FormatListBulleted, R.string.notes_rich_bullets, block == NoteBlockStyle.BULLET) { tools.block(NoteBlockStyle.BULLET) }
            MarkToggle(Icons.Outlined.FormatListNumbered, R.string.notes_rich_numbers, block == NoteBlockStyle.NUMBERED) { tools.block(NoteBlockStyle.NUMBERED) }
            MarkToggle(Icons.Outlined.CheckBox, R.string.notes_rich_todos, block == NoteBlockStyle.TODO) { tools.block(NoteBlockStyle.TODO) }
            MarkToggle(Icons.Outlined.HorizontalRule, R.string.notes_rich_divider, false) { tools.edit { NoteRichEdits.insertBlock(it, NoteRichEdits.DIVIDER) } }
        }
        SheetLabel(R.string.notes_rich_underline_style)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((style, label) in listOf(
                NoteUnderline.WAVY to R.string.notes_rich_wavy,
                NoteUnderline.DOTTED to R.string.notes_rich_dotted,
                NoteUnderline.DASHED to R.string.notes_rich_dashed,
                NoteUnderline.DOUBLE to R.string.notes_rich_double,
            )) {
                val mark = NoteMark.underline(style)
                FilterChip(selected = mark in marks, onClick = { tools.mark(mark) }, label = { Text(stringResource(label)) })
            }
        }
        SheetLabel(R.string.notes_rich_text_colour)
        SwatchRow(
            colours = NoteRichPalette.TEXT,
            current = marks.firstOrNull { it.kind == NoteMarkKind.COLOR }?.value,
            fill = { NoteRichStyle.color(it) ?: Color.Gray },
            onPick = { tools.mark(NoteMark.color(it)) },
            onNone = marks.firstOrNull { it.kind == NoteMarkKind.COLOR }?.let { current -> { tools.mark(current) } },
        )
        SheetLabel(R.string.notes_rich_highlight)
        SwatchRow(
            colours = NoteRichPalette.HIGHLIGHT.map { it ?: NoteRichPalette.DEFAULT_HIGHLIGHT },
            current = marks.firstOrNull { it.kind == NoteMarkKind.HIGHLIGHT }?.let { it.value ?: NoteRichPalette.DEFAULT_HIGHLIGHT },
            fill = { NoteRichStyle.color(it) ?: Color.Yellow },
            onPick = { hex -> tools.mark(NoteMark.highlight(hex.takeIf { it != NoteRichPalette.DEFAULT_HIGHLIGHT })) },
            onNone = marks.firstOrNull { it.kind == NoteMarkKind.HIGHLIGHT }?.let { current -> { tools.mark(current) } },
        )
        SheetLabel(R.string.notes_rich_font)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val font = marks.firstOrNull { it.kind == NoteMarkKind.FONT }
            FilterChip(selected = font == null, onClick = { font?.let(tools::mark) }, label = { Text(stringResource(R.string.notes_rich_font_system)) })
            for ((f, label) in listOf(NoteFont.SERIF to R.string.notes_rich_font_serif, NoteFont.MONO to R.string.notes_rich_font_mono, NoteFont.HAND to R.string.notes_rich_font_hand)) {
                val mark = NoteMark.font(f)
                FilterChip(
                    selected = mark in marks,
                    onClick = { tools.mark(mark) },
                    label = { Text(stringResource(label), fontFamily = NoteRichStyle.font(f.css)) },
                )
            }
        }
        SheetLabel(R.string.notes_rich_size)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val size = marks.firstOrNull { it.kind == NoteMarkKind.SIZE }
            FilterChip(selected = size == null, onClick = { size?.let(tools::mark) }, label = { Text(stringResource(R.string.notes_rich_size_normal)) })
            val labels = listOf(R.string.notes_rich_size_small, R.string.notes_rich_size_large, R.string.notes_rich_size_larger, R.string.notes_rich_size_huge)
            for ((value, label) in NoteRichPalette.SIZES.zip(labels)) {
                val mark = NoteMark.size(value)
                FilterChip(selected = mark in marks, onClick = { tools.mark(mark) }, label = { Text(stringResource(label)) })
            }
        }
        TextButton(onClick = { tools.clearMarks() }) { Text(stringResource(R.string.notes_rich_clear)) }
    }
}

@Composable
private fun SheetLabel(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MarkToggle(icon: ImageVector, label: Int, checked: Boolean, onClick: () -> Unit) {
    val description = stringResource(label)
    FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        Icon(icon, contentDescription = description)
    }
}

/** A row of colours to pick, the current one ringed, and a "none" that takes the current one off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(colours: List<String>, current: String?, fill: (String) -> Color, onPick: (String) -> Unit, onNone: (() -> Unit)?) {
    val ring = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (hex in colours) {
            val on = current == hex
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(if (on) 3.dp else 1.dp, if (on) ring else outline, CircleShape)
                    .padding(if (on) 5.dp else 3.dp)
                    .clip(CircleShape)
                    .background(fill(hex))
                    .clickable { onPick(hex) }
                    .semantics { contentDescription = hex },
            )
        }
        if (onNone != null) {
            TextButton(onClick = onNone) { Text(stringResource(R.string.notes_rich_colour_none)) }
        }
    }
}

/** The Add sheet's tiles. */
private enum class AddItem { PHOTOS, CAMERA, DRAWING, TABLE, FILES, SCAN, SCAN_TEXT, DIVIDER, REARRANGE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddSheet(onPick: (AddItem) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text(stringResource(R.string.notes_rich_add), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AddTile(Icons.Outlined.PhotoLibrary, R.string.notes_rich_photos) { onPick(AddItem.PHOTOS) }
            AddTile(Icons.Outlined.PhotoCamera, R.string.notes_rich_camera) { onPick(AddItem.CAMERA) }
            AddTile(Icons.Outlined.Draw, R.string.notes_rich_drawing) { onPick(AddItem.DRAWING) }
            AddTile(Icons.Outlined.GridOn, R.string.notes_rich_table) { onPick(AddItem.TABLE) }
            AddTile(Icons.Outlined.AttachFile, R.string.notes_rich_files) { onPick(AddItem.FILES) }
            AddTile(Icons.Outlined.DocumentScanner, R.string.notes_rich_scan) { onPick(AddItem.SCAN) }
            AddTile(Icons.Outlined.DocumentScanner, R.string.notes_rich_scan_text) { onPick(AddItem.SCAN_TEXT) }
            AddTile(Icons.Outlined.HorizontalRule, R.string.notes_rich_divider) { onPick(AddItem.DIVIDER) }
            AddTile(Icons.Outlined.DragIndicator, R.string.notes_rich_rearrange) { onPick(AddItem.REARRANGE) }
        }
    }
}

@Composable
private fun AddTile(icon: ImageVector, label: Int, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 2)
    }
}

/**
 * Rearrange (#1586): every block of the note (a line, a row of pictures, a
 * table) as a row with a handle; hold the handle and drag to move it. The
 * order is written back once, when the dialog closes.
 */
@Composable
private fun NoteRearrangeDialog(body: String, onDismiss: () -> Unit, onReorder: (String) -> Unit) {
    val read = remember(body) { NoteMarkup.read(body) }
    val units = remember(read) { NoteRichEdits.units(read) }
    val picture = stringResource(R.string.notes_rich_rearrange_picture)
    val table = stringResource(R.string.notes_rich_table)
    val divider = stringResource(R.string.notes_rich_divider)
    val code = stringResource(R.string.notes_rich_rearrange_code)
    val empty = stringResource(R.string.notes_rich_rearrange_empty)
    val labels = remember(units) {
        units.map { unit ->
            val line = read.lines[unit.first]
            when (line.kind) {
                NoteMarkup.LineKind.IMAGES -> picture
                NoteMarkup.LineKind.TABLE -> table
                NoteMarkup.LineKind.DIVIDER -> divider
                NoteMarkup.LineKind.CODE -> code
                else -> NoteMarkup.visible(read, line).text.ifBlank { empty }.take(80)
            }
        }
    }
    var order by remember(units) { mutableStateOf(units.indices.toList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notes_rich_rearrange)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                ReorderableColumn(
                    items = order,
                    label = { labels[it] },
                    onReorder = { order = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onReorder(NoteRichEdits.reorder(body, order))
                onDismiss()
            }) { Text(stringResource(CommonR.string.common_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}
